package dev.btc.core.integrity;

import dev.btc.core.api.integrity.IntegrityAPI.CheckDefinition;
import dev.btc.core.api.integrity.IntegrityAPI.CheckGroup;
import dev.btc.core.api.integrity.IntegrityAPI.CheckHandle;
import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationModel;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The checks that exist, who owns them, and how much each player has accumulated against them.
 *
 * <p>Registration carries an owner for the same reason every other registry in this fork does
 * ({@code bindBreakSpeedProvider}, {@code registerCatchUpHandler}): a check may only be removed by
 * whoever added it, and a disabled plugin takes its checks with it.
 *
 * <p>Violation levels decay continuously rather than on a timer. Storing the last update and applying
 * the decay on read costs nothing when nobody is looking, and removes a scheduled task that would have
 * to be Folia-aware for no benefit.
 */
public final class CheckRegistry {

    /** A registered check together with its owner. */
    public record RegisteredCheck(Plugin owner, CheckDefinition definition) {}

    /** A player's standing against one check. */
    private static final class Level {
        private double value;
        private long updatedAtNanos;

        Level(double value, long updatedAtNanos) {
            this.value = value;
            this.updatedAtNanos = updatedAtNanos;
        }
    }

    private final Map<CheckId, RegisteredCheck> checks = new ConcurrentHashMap<>();
    private final Map<UUID, Map<CheckId, Level>> levels = new ConcurrentHashMap<>();

    public CheckHandle register(Plugin owner, CheckDefinition definition) {
        if (owner == null || definition == null || definition.id() == null) {
            throw new IllegalArgumentException("a check needs an owner and an identified definition");
        }
        RegisteredCheck previous = checks.putIfAbsent(definition.id(), new RegisteredCheck(owner, definition));
        if (previous != null) {
            throw new IllegalStateException(
                "check " + definition.id() + " is already registered by " + previous.owner().getName());
        }
        return new Handle(definition.id());
    }

    /**
     * Removes a check, refusing to let one plugin unregister another's.
     *
     * @throws IllegalStateException when {@code owner} does not own the check
     */
    public void unregister(Plugin owner, CheckId checkId) {
        RegisteredCheck registered = checks.get(checkId);
        if (registered == null) {
            return;
        }
        if (!registered.owner().equals(owner)) {
            throw new IllegalStateException(
                checkId + " is owned by " + registered.owner().getName() + " and cannot be removed by "
                    + owner.getName());
        }
        checks.remove(checkId, registered);
    }

    /** Drops every check owned by a plugin being disabled. */
    public void clearOwner(Plugin owner) {
        checks.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
    }

    public Optional<RegisteredCheck> find(CheckId checkId) {
        return Optional.ofNullable(checks.get(checkId));
    }

    /** Whether the check exists and its activation policy currently applies to this player. */
    public boolean appliesTo(CheckId checkId, Player player) {
        RegisteredCheck registered = checks.get(checkId);
        if (registered == null) {
            return false;
        }
        try {
            return registered.definition().activationPolicy().appliesTo(player);
        } catch (RuntimeException failure) {
            // A policy that throws must not silently disable the check it guards; treat it as applying
            // and let the violation path report the problem.
            return true;
        }
    }

    /**
     * Adds to a player's level and returns the new value, after applying decay since the last update.
     */
    public double addViolation(UUID player, CheckId checkId, double amount) {
        RegisteredCheck registered = checks.get(checkId);
        double decayPerSecond = registered == null ? 0 : registered.definition().violationModel().decayPerSecond();
        long now = System.nanoTime();

        Map<CheckId, Level> perCheck = levels.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        Level level = perCheck.computeIfAbsent(checkId, k -> new Level(0, now));
        synchronized (level) {
            level.value = decayed(level, decayPerSecond, now) + amount;
            level.updatedAtNanos = now;
            return level.value;
        }
    }

    /** A player's current level against a check, decay applied. */
    public double violationLevel(UUID player, CheckId checkId) {
        Map<CheckId, Level> perCheck = levels.get(player);
        if (perCheck == null) {
            return 0;
        }
        Level level = perCheck.get(checkId);
        if (level == null) {
            return 0;
        }
        RegisteredCheck registered = checks.get(checkId);
        double decayPerSecond = registered == null ? 0 : registered.definition().violationModel().decayPerSecond();
        synchronized (level) {
            return decayed(level, decayPerSecond, System.nanoTime());
        }
    }

    /**
     * The response a level warrants, or {@link Response#NONE} in observation mode.
     *
     * <p>Observation short-circuits everything: a check being observed must never act, whatever its
     * thresholds say. That is what makes it safe to arm a new check on a live server.
     */
    public Response responseFor(CheckId checkId, double level) {
        return responseFor(checkId, level, ClientPlatform.JAVA);
    }

    /**
     * Same as {@link #responseFor(CheckId, double)}, knowing where the session's client comes from.
     *
     * <p>A movement check facing a session of {@link ClientPlatform#UNKNOWN} origin never acts: the
     * prediction it would enforce may be a Java prediction applied to a Bedrock player, and Geyser
     * itself warns that this gets honest players flagged. Unknown keeps the movement family in
     * observation for that session — detection and journal untouched, the response alone withheld.
     * Combat and interaction checks do not read the origin: Geyser translates a click and an entity
     * interaction without altering what they measure.
     */
    public Response responseFor(CheckId checkId, double level, ClientPlatform platform) {
        RegisteredCheck registered = checks.get(checkId);
        if (registered == null) {
            return Response.NONE;
        }
        if (registered.definition().group() == CheckGroup.MOVEMENT
            && (platform == null || platform == ClientPlatform.UNKNOWN)) {
            return Response.NONE;
        }
        // Panic suspends acting, never detecting: the level was already accumulated by the caller and
        // the violation is still journalled and alerted on. Only the response stops.
        if (PanicSwitch.engaged()) {
            return Response.NONE;
        }
        ViolationModel model = registered.definition().violationModel();
        if (model.observationOnly()) {
            return Response.NONE;
        }
        if (level >= model.restrictThreshold()) {
            return Response.RESTRICT;
        }
        if (level >= model.setbackThreshold()) {
            return Response.SETBACK;
        }
        return Response.NONE;
    }

    /** What the platform may do on its own. Deliberately stops short of any sanction. */
    public enum Response {
        NONE,
        SETBACK,
        RESTRICT
    }

    public void clearPlayer(UUID player) {
        levels.remove(player);
    }

    /** Registered check identifiers. Diagnostics only. */
    public java.util.Set<CheckId> registered() {
        return java.util.Set.copyOf(checks.keySet());
    }

    private static double decayed(Level level, double decayPerSecond, long nowNanos) {
        if (decayPerSecond <= 0) {
            return level.value;
        }
        double elapsedSeconds = (nowNanos - level.updatedAtNanos) / 1_000_000_000.0;
        return Math.max(0, level.value - decayPerSecond * elapsedSeconds);
    }

    private final class Handle implements CheckHandle {
        private final CheckId id;

        Handle(CheckId id) {
            this.id = id;
        }

        @Override
        public CheckId id() {
            return id;
        }

        @Override
        public void close() {
            checks.remove(id);
        }
    }
}
