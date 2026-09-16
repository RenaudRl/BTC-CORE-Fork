package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckActivationPolicy;
import dev.btc.core.api.integrity.IntegrityAPI.CheckDefinition;
import dev.btc.core.api.integrity.IntegrityAPI.CheckGroup;
import dev.btc.core.api.integrity.IntegrityAPI.CheckHandle;
import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionReason;
import dev.btc.core.api.integrity.IntegrityAPI.TeleportKind;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationEvent;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationModel;
import dev.btc.core.integrity.CheckRegistry;
import dev.btc.core.integrity.ExemptionRegistry;
import dev.btc.core.integrity.SentinelHooks;
import dev.btc.core.integrity.SentinelHooks.SessionObserver;
import dev.btc.core.integrity.engine.MovementPredictor.Divergence;
import dev.btc.core.integrity.engine.MovementPredictor.Judgement;
import dev.btc.core.integrity.engine.ReachCheck.ReachContext;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Stage 1 of the integrity engine, in observation: it detects, journals and tells, and acts on nothing.
 *
 * <p>Installed on {@link SentinelHooks}, so it sees what the client claimed before the server has
 * decided anything about it. Seven checks live here:
 *
 * <ul>
 *   <li>{@code sentinel:timer} — movement-packet cadence against real time ({@link TimerCheck}).
 *       Packet-only, no world read;</li>
 *   <li>{@code sentinel:teleport-arrival} — after a teleport the fork armed and the client confirmed,
 *       the first position the client sends must be the destination. Positions sent between the two
 *       are about the place the client has not left yet and are ignored, not judged: a check that
 *       does not know this flags every teleport as a blink;</li>
 *   <li>{@code sentinel:speed}, {@code sentinel:fly}, {@code sentinel:fall}, {@code sentinel:phase} —
 *       the position claim against the envelope of {@link MovementPredictor}, built from the player's
 *       live limits (3.4) and the world at the claimed position, read through the adapter on the
 *       region thread;</li>
 *   <li>{@code sentinel:reach} — an attack against the reach the server grants ({@link ReachCheck}).</li>
 * </ul>
 *
 * <p>All are registered with {@link ViolationModel#observing(double)}: {@link CheckRegistry#responseFor}
 * answers {@code NONE} whatever the level says. That is not a configuration that could drift; it is the
 * model the check is registered with, and arming it is a code change in phase 4, after 4.1 has measured
 * the false-positive rate of what is journalled here.
 *
 * <p>An exemption on the check's group suppresses the violation, never the measurement: the timer
 * balance keeps running under a cinematic so that the first packet after it is judged against a
 * clock, not against nothing; the position memory keeps updating so that the first packet after it
 * is judged against a momentum, not against a teleport.
 *
 * <p>Every journalled divergence of the movement family carries the session's origin (D14, 3.7):
 * the Bedrock margin of 4.5 is measured from those lines, per check, and from nothing else.
 */
public final class SentinelEngine implements SessionObserver {

    /** Namespace of the fork's own checks. Extensions register theirs under their own. */
    static final String NAMESPACE = "sentinel";

    static final CheckId TIMER = new CheckId(NAMESPACE, "timer");
    static final CheckId TELEPORT_ARRIVAL = new CheckId(NAMESPACE, "teleport-arrival");
    static final CheckId SPEED = MovementPredictor.SPEED;
    static final CheckId FLY = MovementPredictor.FLY;
    static final CheckId FALL = MovementPredictor.FALL;
    static final CheckId PHASE = MovementPredictor.PHASE;
    static final CheckId REACH = ReachCheck.REACH;

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

    /**
     * How long a velocity the server sent stays in the prediction. The client applies it on receipt,
     * one round trip later at most; a second covers any round trip a player can still play through.
     */
    static final long SERVER_VELOCITY_TTL = TimeUnit.SECONDS.toNanos(1);

    /** Timer levels decay at one packet-worth per second of clean cadence. */
    private static final double TIMER_DECAY_PER_SECOND = 1.0;

    /** A missed arrival is rare and binary; it decays slowly so a pattern stays visible. */
    private static final double ARRIVAL_DECAY_PER_SECOND = 0.05;

    /** Speed and fly excess decays at one block-worth per second: a burst stays visible for a while. */
    private static final double ENVELOPE_DECAY_PER_SECOND = 1.0;

    /** Fall and phase are binary like a missed arrival; a single hit fades in five seconds. */
    private static final double BINARY_DECAY_PER_SECOND = 0.2;

    private static final double REACH_DECAY_PER_SECOND = 0.5;

    private final CheckRegistry checks;
    private final ExemptionRegistry exemptions;
    private final ServerAdapter server;
    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();
    private final List<CheckHandle> registrations = new ArrayList<>();
    private final Map<CheckId, CheckGroup> groups = new HashMap<>();

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

    /** The engine currently on the seam, for the paths that reach it from listener code. {@code null} when none. */
    private static volatile SentinelEngine installed;

    /** Drops the session of a player who left, on whichever engine is installed. */
    public static void forgetSession(final UUID player) {
        final SentinelEngine engine = installed;
        if (engine != null) {
            engine.forget(player);
        }
    }

    /**
     * A velocity the server is sending this player — knockback, a plugin's {@code setVelocity}, an
     * attack's recoil — as {@code PlayerVelocityEvent} reports it, on the player's region thread.
     *
     * <p>Read from the fork rather than declared (D15): the server sent it, so the server knows it.
     * The prediction allows it for {@link #SERVER_VELOCITY_TTL}. Explosion knockback does not pass
     * here — vanilla sends it inside the explosion packet, not as a velocity — and is a known gap
     * the observation journal will size.
     */
    public static void serverVelocity(final UUID player, final double x, final double y, final double z) {
        final SentinelEngine engine = installed;
        if (engine != null) {
            engine.session(player).serverVelocity(x, y, z, System.nanoTime() + SERVER_VELOCITY_TTL);
        }
    }

    void registerChecks(final Plugin owner) {
        register(owner, TIMER, CheckGroup.MOVEMENT, TIMER_DECAY_PER_SECOND);
        register(owner, TELEPORT_ARRIVAL, CheckGroup.POSITION, ARRIVAL_DECAY_PER_SECOND);
        register(owner, SPEED, CheckGroup.MOVEMENT, ENVELOPE_DECAY_PER_SECOND);
        register(owner, FLY, CheckGroup.MOVEMENT, ENVELOPE_DECAY_PER_SECOND);
        register(owner, FALL, CheckGroup.MOVEMENT, BINARY_DECAY_PER_SECOND);
        register(owner, PHASE, CheckGroup.MOVEMENT, BINARY_DECAY_PER_SECOND);
        register(owner, REACH, CheckGroup.COMBAT, REACH_DECAY_PER_SECOND);
    }

    private void register(final Plugin owner, final CheckId id, final CheckGroup group,
                          final double decayPerSecond) {
        registrations.add(checks.register(owner, new CheckDefinition(
            id, group, CheckActivationPolicy.always(), ViolationModel.observing(decayPerSecond))));
        groups.put(id, group);
    }

    void unregisterChecks() {
        registrations.forEach(CheckHandle::close);
        registrations.clear();
        groups.clear();
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
        final long now = System.nanoTime();
        final double aheadMillis = TimerCheck.observe(state, now);
        if (aheadMillis > 0 && !exempted(player, CheckGroup.MOVEMENT)) {
            violate(player, TIMER, aheadMillis / TimerCheck.TICK_MILLIS,
                String.format(Locale.ROOT, "cadence %.0f ms ahead of real time", aheadMillis));
        }
        if (hasPosition) {
            observePosition(player, state, x, y, z, onGround, now);
        }
    }

    private void observePosition(final UUID player, final SessionState state,
                                 final double x, final double y, final double z,
                                 final boolean onGround, final long now) {
        final Optional<MovementContext> read = server.movementContext(player, x, y, z);
        if (read.isEmpty()) {
            // Nothing sound to judge against: the next position starts over as a reference.
            state.forgetPosition();
            return;
        }
        final MovementContext context = read.get();
        final boolean verticalFree = MovementPredictor.verticalFree(context, now, state);
        if (!state.hasLastPosition()) {
            state.moved(x, y, z, context.supported(), onGround, verticalFree,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
            return;
        }
        final Judgement judgement = MovementPredictor.judge(state, context, x, y, z, onGround, now);
        for (final Divergence divergence : judgement.divergences()) {
            report(player, divergence, context.platform());
        }
        state.moved(x, y, z, context.supported(), onGround, verticalFree,
            judgement.horizontalCap(), judgement.verticalCap());
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

    // ------------------------------------------------------------------ combat

    @Override
    public void onAttack(final UUID player, final int targetEntityId) {
        final Optional<ReachContext> read = server.reachContext(player, targetEntityId);
        if (read.isEmpty()) {
            return;
        }
        ReachCheck.judge(read.get()).ifPresent(divergence -> report(player, divergence, read.get().platform()));
    }

    // ------------------------------------------------------------------ not observed yet

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

    /** Reports a divergence unless its group is exempted, stamping the session's origin on it. */
    private void report(final UUID player, final Divergence divergence, final ClientPlatform origin) {
        final CheckGroup group = groups.get(divergence.check());
        if (group != null && exempted(player, group)) {
            return;
        }
        violate(player, divergence.check(), divergence.amount(),
            "[origin " + origin + "] " + divergence.detail());
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
