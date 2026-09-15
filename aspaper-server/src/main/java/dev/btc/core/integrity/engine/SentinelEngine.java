package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckActivationPolicy;
import dev.btc.core.api.integrity.IntegrityAPI.CheckDefinition;
import dev.btc.core.api.integrity.IntegrityAPI.CheckGroup;
import dev.btc.core.api.integrity.IntegrityAPI.CheckHandle;
import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionReason;
import dev.btc.core.api.integrity.IntegrityAPI.TeleportKind;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationEvent;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationModel;
import dev.btc.core.integrity.CheckRegistry;
import dev.btc.core.integrity.ExemptionRegistry;
import dev.btc.core.integrity.SentinelHooks;
import dev.btc.core.integrity.SentinelHooks.SessionObserver;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage 1 of the integrity engine, in observation: it detects, journals and tells, and acts on nothing.
 *
 * <p>Installed on {@link SentinelHooks}, so it sees what the client claimed before the server has
 * decided anything about it. Two checks live here today, both packet-only — they read no world state,
 * which is why they can run on the packet path without a hop:
 *
 * <ul>
 *   <li>{@code sentinel:timer} — movement-packet cadence against real time ({@link TimerCheck});</li>
 *   <li>{@code sentinel:teleport-arrival} — after a teleport the fork armed and the client confirmed,
 *       the first position the client sends must be the destination. Positions sent between the two
 *       are about the place the client has not left yet and are ignored, not judged: a check that
 *       does not know this flags every teleport as a blink.</li>
 * </ul>
 *
 * <p>Both are registered with {@link ViolationModel#observing(double)}: {@link CheckRegistry#responseFor}
 * answers {@code NONE} whatever the level says. That is not a configuration that could drift; it is the
 * model the check is registered with, and arming it is a code change in phase 4, after 4.1 has measured
 * the false-positive rate of what is journalled here.
 *
 * <p>An exemption on the check's group suppresses the violation, never the measurement: the timer
 * balance keeps running under a cinematic so that the first packet after it is judged against a
 * clock, not against nothing.
 */
public final class SentinelEngine implements SessionObserver {

    /** Namespace of the fork's own checks. Extensions register theirs under their own. */
    static final String NAMESPACE = "sentinel";

    static final CheckId TIMER = new CheckId(NAMESPACE, "timer");
    static final CheckId TELEPORT_ARRIVAL = new CheckId(NAMESPACE, "teleport-arrival");

    /**
     * How far from the destination the confirmed arrival may land.
     *
     * <p>A client that accepts a teleport sets its position to the exact doubles it was sent, so the
     * honest distance is zero. Relative teleports are the exception: the client resolves them against
     * its own position, which may trail the server's by the movement still in flight, so a fraction of
     * a block is tolerated. Half a block is deliberately generous for observation; 4.1 narrows it from
     * the journal, not from here.
     */
    static final double ARRIVAL_TOLERANCE = 0.5;

    /** Timer levels decay at one packet-worth per second of clean cadence. */
    private static final double TIMER_DECAY_PER_SECOND = 1.0;

    /** A missed arrival is rare and binary; it decays slowly so a pattern stays visible. */
    private static final double ARRIVAL_DECAY_PER_SECOND = 0.05;

    private final CheckRegistry checks;
    private final ExemptionRegistry exemptions;
    private final ServerAdapter server;
    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();
    private final List<CheckHandle> registrations = new ArrayList<>();

    SentinelEngine(final CheckRegistry checks, final ExemptionRegistry exemptions,
                   final ServerAdapter server) {
        this.checks = checks;
        this.exemptions = exemptions;
        this.server = server;
    }

    /**
     * Registers the built-in checks under {@code owner} and installs the engine on the seam.
     *
     * @return a handle that uninstalls the engine and removes its checks
     */
    public static AutoCloseable install(final Plugin owner, final CheckRegistry checks,
                                        final ExemptionRegistry exemptions, final ServerAdapter server) {
        final SentinelEngine engine = new SentinelEngine(checks, exemptions, server);
        engine.registerChecks(owner);
        final AutoCloseable seam = SentinelHooks.install(engine);
        installed = engine;
        return () -> {
            seam.close();
            if (installed == engine) {
                installed = null;
            }
            engine.unregisterChecks();
            engine.sessions.clear();
        };
    }

    /** The engine currently on the seam, for the disconnect path. {@code null} when none. */
    private static volatile SentinelEngine installed;

    /** Drops the session of a player who left, on whichever engine is installed. */
    public static void forgetSession(final UUID player) {
        final SentinelEngine engine = installed;
        if (engine != null) {
            engine.forget(player);
        }
    }

    void registerChecks(final Plugin owner) {
        registrations.add(checks.register(owner, new CheckDefinition(
            TIMER, CheckGroup.MOVEMENT, CheckActivationPolicy.always(),
            ViolationModel.observing(TIMER_DECAY_PER_SECOND))));
        registrations.add(checks.register(owner, new CheckDefinition(
            TELEPORT_ARRIVAL, CheckGroup.POSITION, CheckActivationPolicy.always(),
            ViolationModel.observing(ARRIVAL_DECAY_PER_SECOND))));
    }

    void unregisterChecks() {
        registrations.forEach(CheckHandle::close);
        registrations.clear();
    }

    /** Drops a session. Called when the player disconnects; a stale state would judge their next login. */
    public void forget(final UUID player) {
        sessions.remove(player);
    }

    /** Number of sessions being observed. Diagnostics only. */
    public int sessionCount() {
        return sessions.size();
    }

    // ------------------------------------------------------------------ movement

    @Override
    public void onMove(final UUID player, final double x, final double y, final double z,
                       final float yRot, final float xRot, final boolean onGround,
                       final boolean hasPosition, final boolean hasRotation) {
        final SessionState state = session(player);
        if (state.awaitingTeleportAck()) {
            // Still about the old place. Not judged, and not counted: the stall a teleport causes
            // would otherwise be charged to the cadence.
            return;
        }
        if (state.expectingArrival() && hasPosition) {
            state.arrived();
            observeArrival(player, state, x, y, z);
        }
        final double aheadMillis = TimerCheck.observe(state, System.nanoTime());
        if (aheadMillis > 0 && !exempted(player, CheckGroup.MOVEMENT)) {
            violate(player, TIMER, aheadMillis / TimerCheck.TICK_MILLIS,
                String.format(Locale.ROOT, "cadence %.0f ms ahead of real time", aheadMillis));
        }
    }

    private void observeArrival(final UUID player, final SessionState state,
                                final double x, final double y, final double z) {
        final double dx = x - state.teleportX();
        final double dy = y - state.teleportY();
        final double dz = z - state.teleportZ();
        final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        final Optional<TeleportKind> declared = server.consumeDeclaredTeleport(player, x, y, z);
        if (distance <= ARRIVAL_TOLERANCE) {
            declared.ifPresent(kind -> server.verbose(String.format(Locale.ROOT,
                "[Sentinel] %s arrived as expected after a %s teleport", name(player), kind)));
            return;
        }
        if (exempted(player, CheckGroup.POSITION)) {
            return;
        }
        violate(player, TELEPORT_ARRIVAL, 1.0, String.format(Locale.ROOT,
            "arrived %.2f blocks from the confirmed destination (%.2f, %.2f, %.2f)%s",
            distance, state.teleportX(), state.teleportY(), state.teleportZ(),
            declared.map(kind -> ", declared as " + kind).orElse("")));
    }

    // ------------------------------------------------------------------ teleports (3.8)

    @Override
    public void onTeleportExpected(final UUID player, final double x, final double y, final double z) {
        session(player).teleportExpected(x, y, z);
    }

    @Override
    public void onTeleportAcknowledged(final UUID player) {
        session(player).teleportAcknowledged();
    }

    // ------------------------------------------------------------------ not observed yet

    @Override
    public void onAttack(final UUID player, final int targetEntityId) {
        // Reach reads the target's hitbox and the attacker's interaction-range attribute: world
        // state, hence the region-thread check of 3.3/3.4, which arrives with the prediction.
    }

    @Override
    public void onInteractEntity(final UUID player, final int targetEntityId,
                                 final double hitX, final double hitY, final double hitZ,
                                 final boolean secondaryAction) {
    }

    @Override
    public void onUseItem(final UUID player, final boolean againstBlock) {
    }

    @Override
    public void onContainerClick(final UUID player, final int containerId, final int slot,
                                 final int button, final int inputOrdinal) {
    }

    // ------------------------------------------------------------------ plumbing

    private SessionState session(final UUID player) {
        return sessions.computeIfAbsent(player, id -> new SessionState());
    }

    private boolean exempted(final UUID player, final CheckGroup group) {
        final Optional<ExemptionReason> reason = exemptions.reasonFor(player, group);
        return reason.isPresent();
    }

    private void violate(final UUID player, final CheckId check, final double amount, final String detail) {
        final double level = checks.addViolation(player, check, amount);
        final String name = name(player);
        server.journal(player, name, check, detail);
        server.publish(player, new ViolationEvent(player, check, level, detail, false));
        server.verbose(String.format(Locale.ROOT, "[Sentinel] %s %s (VL %.1f): %s",
            name, check, level, detail));
    }

    private String name(final UUID player) {
        return server.playerName(player).orElse(player.toString());
    }
}
