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
import dev.btc.core.security.exploit.BurstWindow;
import dev.btc.core.security.exploit.DeterministicChecks;
import dev.btc.core.security.exploit.InteractBurst;
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
import java.util.function.LongSupplier;

/**
 * Stage 1 of the integrity engine, in observation: it detects, journals and tells, and acts on nothing.
 *
 * <p>Installed on {@link SentinelHooks}, so it sees what the client claimed before the server has
 * decided anything about it. Thirteen checks live here:
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
 *   <li>{@code sentinel:reach} — an attack against the reach the server grants ({@link ReachCheck});</li>
 *   <li>{@code sentinel:interact-burst} — entity interactions in a burst ({@link InteractBurst}).
 *       Counted per session in O(1), journalled once per window, never blocked;</li>
 *   <li>{@code sentinel:multi-target}, {@code sentinel:slot-swap}, {@code sentinel:unknown-window},
 *       {@code sentinel:closed-window}, {@code sentinel:wall-hit} — the deterministic checks of 16.7
 *       ({@link DeterministicChecks}). The first two judge what happened inside one <em>client</em> tick,
 *       delimited by the client's own end-of-tick packet; the next two judge a container click against the
 *       containers the server showed this session; the last judges an attack against the target boxes of
 *       the reach check. Each is off | observe in {@code anticheat.yml}, with no enforce.</li>
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

    /**
     * How often a moving session is pinged for its round trip (3.5). One ping a second is a few
     * bytes against a movement stream of twenty packets a second, and a round trip older than a
     * second is what {@code getPing()} already offers.
     */
    static final long PING_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    /** Timer levels decay at one packet-worth per second of clean cadence. */
    private static final double TIMER_DECAY_PER_SECOND = 1.0;

    /** A missed arrival is rare and binary; it decays slowly so a pattern stays visible. */
    private static final double ARRIVAL_DECAY_PER_SECOND = 0.05;

    /** Speed and fly excess decays at one block-worth per second: a burst stays visible for a while. */
    private static final double ENVELOPE_DECAY_PER_SECOND = 1.0;

    /** Fall and phase are binary like a missed arrival; a single hit fades in five seconds. */
    private static final double BINARY_DECAY_PER_SECOND = 0.2;

    private static final double REACH_DECAY_PER_SECOND = 0.5;

    /** A burst is reported at most once a window and is binary: it decays slowly so a pattern stays visible. */
    private static final double BURST_DECAY_PER_SECOND = 0.05;

    /** The 16.7 checks are binary and rare for an honest client: one line fades in twenty seconds. */
    private static final double DETERMINISTIC_DECAY_PER_SECOND = 0.05;

    private final CheckRegistry checks;
    private final ExemptionRegistry exemptions;
    private final ServerAdapter server;
    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();
    private final List<CheckHandle> registrations = new ArrayList<>();
    private final Map<CheckId, CheckGroup> groups = new HashMap<>();

    private final InteractBurst.Settings burst;

    /** The deterministic checks of 16.7: their modes and thresholds, fixed for the engine's lifetime. */
    private final DeterministicChecks.Settings deterministic;

    /** Clock of the interaction window and of the report gates; injected so a test needs no sleep. */
    private final LongSupplier burstClock;

    SentinelEngine(final CheckRegistry checks, final ExemptionRegistry exemptions,
                   final ServerAdapter server, final InteractBurst.Settings burst,
                   final DeterministicChecks.Settings deterministic, final LongSupplier burstClock) {
        this.checks = checks;
        this.exemptions = exemptions;
        this.server = server;
        this.burst = burst;
        this.deterministic = deterministic;
        this.burstClock = burstClock;
    }

    /**
     * Registers the built-in checks under {@code owner} and installs the engine on the seam, with the
     * entity-interaction counter set as {@code anticheat.yml} says.
     *
     * @return a handle that uninstalls the engine and removes its checks
     */
    public static AutoCloseable install(final Plugin owner, final CheckRegistry checks,
                                        final ExemptionRegistry exemptions, final ServerAdapter server) {
        return install(owner, checks, exemptions, server, InteractBurst.Settings.configured());
    }

    static AutoCloseable install(final Plugin owner, final CheckRegistry checks,
                                 final ExemptionRegistry exemptions, final ServerAdapter server,
                                 final InteractBurst.Settings burst) {
        return install(owner, checks, exemptions, server, burst, System::nanoTime);
    }

    static AutoCloseable install(final Plugin owner, final CheckRegistry checks,
                                 final ExemptionRegistry exemptions, final ServerAdapter server,
                                 final InteractBurst.Settings burst, final LongSupplier burstClock) {
        return install(owner, checks, exemptions, server, burst, DeterministicChecks.Settings.configured(),
            burstClock);
    }

    static AutoCloseable install(final Plugin owner, final CheckRegistry checks,
                                 final ExemptionRegistry exemptions, final ServerAdapter server,
                                 final InteractBurst.Settings burst, final DeterministicChecks.Settings deterministic,
                                 final LongSupplier burstClock) {
        final SentinelEngine engine = new SentinelEngine(checks, exemptions, server, burst, deterministic, burstClock);
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

    /**
     * Drops the state of the connection {@code session} (the player's entity id) of a player who left, on
     * whichever engine is installed. A quit handled late, after the same player came back, finds the state
     * of the new connection (see {@link #bindSession}) and leaves it alone.
     */
    public static void forgetSession(final UUID player, final int session) {
        final SentinelEngine engine = installed;
        if (engine != null) {
            engine.forget(player, session);
        }
    }

    /**
     * Ties the state of {@code player} to the connection {@code session} (its entity id), at join. A state
     * left by another connection of the same player, one whose quit has not been handled yet, is replaced.
     */
    public static void bindSession(final UUID player, final int session) {
        final SentinelEngine engine = installed;
        if (engine != null) {
            engine.bind(player, session);
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

    /**
     * The sprint state this player's client announced, as {@code PlayerToggleSprintEvent} reports it,
     * on the player's region thread — cancelled or not: a refused sprint is still the client's.
     * See {@link MovementPredictor#asTheClientSprints}.
     */
    public static void clientSprint(final UUID player, final boolean sprinting) {
        final SentinelEngine engine = installed;
        if (engine != null) {
            engine.heard(player).clientSprinting(sprinting);
        }
    }

    /**
     * The client ended a tick ({@code ClientTickEndEvent}), on the player's region thread. What the
     * 16.7 checks call "the same tick" is what happened between two of these. A player with no session
     * yet is not given one: the first movement packet makes it.
     */
    public static void clientTickEnd(final UUID player) {
        final SentinelEngine engine = installed;
        if (engine != null) {
            final SessionState state = engine.sessions.get(player);
            if (state != null) {
                engine.observeTickEnd(player, state);
            }
        }
    }

    /**
     * The player's held slot changed ({@code PlayerItemHeldEvent}, which Paper fires only on a real
     * change), on the player's region thread.
     */
    public static void heldSlotChanged(final UUID player) {
        final SentinelEngine engine = installed;
        if (engine != null) {
            engine.observeSlotSwap(player);
        }
    }

    /**
     * A container was closed ({@code InventoryCloseEvent}, whatever the reason), on the player's region
     * thread, before the server swaps it for the player's own inventory: the id being closed is still
     * the open one. {@code byClient} is whether the client asked for it ({@code Reason.PLAYER}) rather than
     * the server. A player with no session is not given one.
     */
    public static void containerClosed(final UUID player, final boolean byClient) {
        final SentinelEngine engine = installed;
        if (engine != null) {
            engine.observeContainerClosed(player, byClient);
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
        register(owner, InteractBurst.CHECK, CheckGroup.INTERACTION, BURST_DECAY_PER_SECOND);
        register(owner, DeterministicChecks.MULTI_TARGET, CheckGroup.COMBAT, DETERMINISTIC_DECAY_PER_SECOND);
        register(owner, DeterministicChecks.SLOT_SWAP, CheckGroup.INTERACTION, DETERMINISTIC_DECAY_PER_SECOND);
        register(owner, DeterministicChecks.UNKNOWN_WINDOW, CheckGroup.INTERACTION, DETERMINISTIC_DECAY_PER_SECOND);
        register(owner, DeterministicChecks.CLOSED_WINDOW, CheckGroup.INTERACTION, DETERMINISTIC_DECAY_PER_SECOND);
        register(owner, DeterministicChecks.WALL_HIT, CheckGroup.COMBAT, DETERMINISTIC_DECAY_PER_SECOND);
        register(owner, DeterministicChecks.TICK_END_FLOOD, CheckGroup.INTERACTION, DETERMINISTIC_DECAY_PER_SECOND);
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

    /** Drops the state of a connection that ended; a stale state would judge the next login. */
    void forget(final UUID player, final int session) {
        sessions.computeIfPresent(player, (id, state) -> state.belongsTo(session) ? null : state);
    }

    void bind(final UUID player, final int session) {
        sessions.compute(player, (id, state) -> {
            final SessionState bound = state != null && state.belongsTo(session) ? state : newSession();
            bound.bindTo(session);
            return bound;
        });
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
        if (hasPosition && !(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))) {
            // The seam fires before vanilla's containsInvalidValues, which kicks the player for this:
            // a NaN or infinite claim is not a position, and as a reference it would blind the next judgement.
            return;
        }
        final SessionState state = heard(player);
        if (state.awaitingTeleportAck()) {
            // Still about the old place. Not judged, and not counted: the stall a teleport causes
            // would otherwise be charged to the cadence.
            return;
        }
        // No set-back came between the last packet and this one: the step it judged was accepted.
        state.releaseHeldPhase().ifPresent(held -> report(player, held.divergence(), held.origin()));
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
        if (state.pingDue(now, PING_INTERVAL)) {
            // On the movement path rather than on a scheduler: a session that sends no movement
            // has nothing to compensate, and this thread is the one that owns the connection.
            final int id = RoundTripMeter.nextId();
            state.roundTrip().sent(id, now);
            server.ping(player, id);
        }
    }

    @Override
    public void onPong(final UUID player, final int id) {
        // Network thread: only the meter, which is built for it, is touched.
        final SessionState state = sessions.get(player);
        if (state != null) {
            state.roundTrip().received(id, System.nanoTime());
        }
    }

    private void observePosition(final UUID player, final SessionState state,
                                 final double x, final double y, final double z,
                                 final boolean onGround, final long now) {
        final boolean fromReference = state.hasLastPosition();
        final Optional<MovementContext> read = server.movementContext(player,
            fromReference ? state.lastX() : x, fromReference ? state.lastZ() : z, x, y, z);
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
            if (divergence.check().equals(PHASE)) {
                state.holdPhase(divergence, context.platform());
            } else {
                report(player, divergence, context.platform());
            }
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
        heard(player).teleportAcknowledged();
    }

    // ------------------------------------------------------------------ combat

    @Override
    public void onAttack(final UUID player, final int targetEntityId) {
        final SessionState state = heard(player);
        observeMultiTarget(player, state, targetEntityId);
        final long roundTrip = state.roundTrip().lastRoundTripNanos().orElse(-1);
        final Optional<ReachContext> read = server.reachContext(player, targetEntityId, roundTrip);
        if (read.isEmpty()) {
            return;
        }
        ReachCheck.judge(read.get()).ifPresent(divergence -> report(player, divergence, read.get().platform()));
        observeWallHit(player, state, targetEntityId, read.get());
    }

    // ------------------------------------------------------------------ entity interaction (10.14)

    @Override
    public void onInteractEntity(final UUID player, final int targetEntityId,
                                 final double hitX, final double hitY, final double hitZ,
                                 final boolean secondaryAction) {
        heard(player).ticks().acted(ClientTickLedger.Action.INTERACTION);
        if (burst.mode() == InteractBurst.Mode.OFF) {
            return;
        }
        // The window is fed whether or not the group is exempted, like the other counters: an exemption
        // silences the line, it does not stop the measurement.
        final BurstWindow window = session(player).interactBurst();
        if (!window.record(burstClock.getAsLong()) || exempted(player, CheckGroup.INTERACTION)) {
            return;
        }
        violate(player, InteractBurst.CHECK, 1.0, InteractBurst.detail(server.platformOf(player),
            window.lastCount(), TimeUnit.NANOSECONDS.toMillis(window.lastSpanNanos()), burst.windowMillis(),
            window.threshold(), targetEntityId));
    }

    // ------------------------------------------------------------------ deterministic checks (16.7)

    /** Several different targets attacked inside one client tick: a client picks one target per tick. */
    private void observeMultiTarget(final UUID player, final SessionState state, final int targetEntityId) {
        // Recorded whatever the mode: the ledger also answers the slot-swap check.
        final int targets = state.ticks().attacked(targetEntityId);
        if (deterministic.multiTarget() != InteractBurst.Mode.OBSERVE || !state.ticks().armed()
            || targets <= deterministic.maxTargetsPerTick()) {
            return;
        }
        reportDeterministic(player, state, DeterministicChecks.MULTI_TARGET, DeterministicChecks.multiTargetDetail(
            server.platformOf(player), targets, deterministic.maxTargetsPerTick(), targetEntityId));
    }

    /** The held slot changed after an action of the same client tick: the client sends the slot first. */
    private void observeSlotSwap(final UUID player) {
        final SessionState state = sessions.get(player);
        if (state == null) {
            return;
        }
        heard(state);
        if (deterministic.slotSwap() != InteractBurst.Mode.OBSERVE || !state.ticks().armed()) {
            return;
        }
        final ClientTickLedger.Action action = state.ticks().actionThisTick();
        if (action == null) {
            return;
        }
        reportDeterministic(player, state, DeterministicChecks.SLOT_SWAP,
            DeterministicChecks.slotSwapDetail(server.platformOf(player), action.label()));
    }

    /**
     * An attack through a wall, judged on the boxes the reach check already read. The cheap filters come
     * first, so that a ray is only cast for an attack within reach, by a player these checks judge, and
     * at most once per {@code wall-hit.min-interval-millis}.
     */
    private void observeWallHit(final UUID player, final SessionState state, final int targetEntityId,
                                final ReachContext context) {
        if (deterministic.wallHit() != InteractBurst.Mode.OBSERVE || !WallHitCheck.inScope(context)
            || exempted(player, groups.get(DeterministicChecks.WALL_HIT)) || server.exemptGameMode(player)
            || !state.wallTestDue(burstClock.getAsLong(), deterministic.wallMinIntervalNanos())) {
            return;
        }
        WallHitCheck.judge(context, (fromX, fromY, fromZ, toX, toY, toZ) ->
                server.blockedBetween(player, fromX, fromY, fromZ, toX, toY, toZ))
            .ifPresent(verdict -> {
                // The line of the box the target occupies now, with what the world shows on it.
                final ReachCheck.Box current = context.targetBoxes().get(0);
                final double[] ray = WallHitCheck.ray(context, current);
                final String seen = server.describeWallHit(player, targetEntityId,
                    ray[0], ray[1], ray[2], ray[3], ray[4], ray[5]);
                reportDeterministic(player, state, DeterministicChecks.WALL_HIT,
                    DeterministicChecks.wallHitDetail(context.platform(), targetEntityId, verdict.boxes(),
                        verdict.distance(), WallHitCheck.evidence(ray, current, seen)));
            });
    }

    /**
     * The client ended a tick. Tick ends sent faster than the server ticks, once the burst saved up for a
     * freeze is spent, are a client splitting its own actions: the excess ends no tick (the ledger sees to
     * it) and is journalled. With the check off, every tick end ends a tick.
     */
    private void observeTickEnd(final UUID player, final SessionState state) {
        final boolean bounded = deterministic.tickEndFlood() == InteractBurst.Mode.OBSERVE;
        final int burst = deterministic.tickEndBurst();
        if (!state.ticks().tickEnded(burstClock.getAsLong(), server.tickNanos(), burst, bounded)) {
            reportDeterministic(player, state, DeterministicChecks.TICK_END_FLOOD,
                DeterministicChecks.tickEndFloodDetail(server.platformOf(player), burst));
        }
    }

    private void observeContainerClosed(final UUID player, final boolean byClient) {
        final SessionState state = sessions.get(player);
        if (state != null && judgesContainers()) {
            // What the client closed is named by its packet (onContainerClose); the open id only proves what was shown.
            final int open = server.openContainerId(player);
            if (byClient) {
                state.containers().shown(open);
            } else {
                state.containers().closedByServer(open);
            }
        }
    }

    @Override
    public void onContainerOpen(final UUID player, final int containerId) {
        final SessionState state = sessions.get(player);
        if (state != null && judgesContainers()) {
            state.containers().opened(containerId, burstClock.getAsLong());
        }
    }

    @Override
    public void onContainerClose(final UUID player, final int containerId, final int openContainerId) {
        final SessionState state = sessions.get(player);
        if (state == null) {
            return;
        }
        heard(state);
        if (judgesContainers() && !state.containers().clientClosed(containerId, openContainerId,
                burstClock.getAsLong(), ContainerLedger.raceWindowNanos(server.latencyMillis(player)))
            && deterministic.closedWindow() == InteractBurst.Mode.OBSERVE) {
            reportDeterministic(player, state, DeterministicChecks.CLOSED_WINDOW,
                DeterministicChecks.closeMismatchDetail(server.platformOf(player), containerId, openContainerId));
        }
    }

    /**
     * Journals a 16.7 line unless the group is exempted, the game mode is one these checks do not judge,
     * or this check journalled for this player less than the report interval ago. An exemption silences
     * the line, never the measurement: the ledgers are fed before this is reached.
     */
    private void reportDeterministic(final UUID player, final SessionState state, final CheckId check,
                                     final String detail) {
        final CheckGroup group = groups.get(check);
        if (exempted(player, group) || server.exemptGameMode(player)
            || !state.reportDue(check, burstClock.getAsLong(), deterministic.reportIntervalNanos())) {
            return;
        }
        violate(player, check, 1.0, detail);
    }

    // ------------------------------------------------------------------ not observed yet

    @Override
    public void onUseItem(final UUID player, final boolean againstBlock) {
        heard(player).ticks().acted(ClientTickLedger.Action.ITEM_USE);
    }

    @Override
    public void onContainerClick(final UUID player, final int containerId, final int slot,
                                 final int button, final int inputOrdinal) {
        final SessionState state = heard(player);
        if (!judgesContainers()) {
            return;
        }
        final int open = server.openContainerId(player);
        final ContainerLedger.Verdict verdict = state.containers().clicked(containerId, open);
        if (verdict == ContainerLedger.Verdict.UNKNOWN && deterministic.unknownWindow() == InteractBurst.Mode.OBSERVE) {
            reportDeterministic(player, state, DeterministicChecks.UNKNOWN_WINDOW,
                DeterministicChecks.unknownWindowDetail(server.platformOf(player), containerId, open));
        } else if (verdict == ContainerLedger.Verdict.CLOSED
            && deterministic.closedWindow() == InteractBurst.Mode.OBSERVE) {
            reportDeterministic(player, state, DeterministicChecks.CLOSED_WINDOW,
                DeterministicChecks.closedWindowDetail(server.platformOf(player), containerId, open));
        }
    }

    /** Whether either container check is on: the ledger is only fed when something reads it. */
    private boolean judgesContainers() {
        return deterministic.unknownWindow() == InteractBurst.Mode.OBSERVE
            || deterministic.closedWindow() == InteractBurst.Mode.OBSERVE;
    }

    // ------------------------------------------------------------------ plumbing

    private SessionState session(final UUID player) {
        return sessions.computeIfAbsent(player, id -> newSession());
    }

    /** The session of a player whose packet just arrived: the tick-end bucket learns the client is not silent. */
    private SessionState heard(final UUID player) {
        return heard(session(player));
    }

    private SessionState heard(final SessionState state) {
        state.ticks().packetSeen(burstClock.getAsLong(), server.tickNanos());
        return state;
    }

    private SessionState newSession() {
        return new SessionState(burst.newWindow(), deterministic.maxTargetsPerTick());
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
