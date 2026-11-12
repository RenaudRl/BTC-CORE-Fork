package dev.btc.core.world;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.infernalsuite.asp.AdvancedSlimePaper;
import com.infernalsuite.asp.level.SlimeLevelInstance;
import com.mojang.logging.LogUtils;
import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.event.world.WorldTickEndEvent;
import dev.btc.core.event.world.WorldTickStartEvent;
import dev.btc.core.parallel.CrossWorldQueue;
import dev.btc.core.parallel.MidTickBudget;
import dev.btc.core.parallel.ResumedTeleport;
import dev.btc.core.parallel.WorldLifecycleGate;
import dev.btc.core.parallel.WorldOwnership;
import dev.btc.core.parallel.WorldQuarantine;
import dev.btc.core.parallel.WorldTickCoordinator;
import dev.btc.core.parallel.WorldTickSteps;
import io.papermc.paper.event.entity.EntityMoveEvent;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.ReportedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The world part of a server tick under {@code parallel-world-ticking}: the glue between
 * {@code MinecraftServer.tickChildren} and {@link WorldTickCoordinator}, which holds the scheduling.
 *
 * <p>With {@code parallel-world-ticking} and {@code world.quarantine} both off {@link #ACTIVE} is
 * false and the vanilla loop of {@code tickChildren} runs untouched; this class then only fires the
 * world tick events, and only if something listens.
 *
 * <p>{@code world.quarantine}: a Slime world whose tick threw is taken out of the server instead of
 * stopping it; {@link WorldQuarantine} decides, here the decision is carried out.
 *
 * <p>Loop ported from DivineMC "Parallel world ticking" (NONPLAYT, BX Team, GPL-3.0), itself from
 * SparklyPaper (SparklyPower).
 */
public final class WorldTickLoop {

    /** {@code parallel-world-ticking.enabled}, read once: a world cannot change thread while the server runs. */
    public static final boolean PARALLEL = BTCCoreConfig.parallelWorldTickingEnabled;
    /**
     * The worlds tick behind the coordinator instead of in the vanilla loop: on their own threads,
     * or - with {@code world.quarantine} alone - still one after the other on the main thread, but
     * with a failing world handed back instead of thrown.
     */
    public static final boolean ACTIVE = PARALLEL || BTCCoreConfig.worldQuarantineEnabled;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int AUTO_CORES_PER_PERMIT = 2;
    private static final String THREAD_NAME_PREFIX = "BTC World Tick - ";

    private static final WorldTickCoordinator<ServerLevel> COORDINATOR =
        new WorldTickCoordinator<>(PARALLEL, permits(), WorldThread::new);
    /** What the world threads left to the main thread this tick, because it reaches into another world. */
    private static final CrossWorldQueue DEFERRED = new CrossWorldQueue();
    private static final ResumedTeleport RESUMED = new ResumedTeleport();
    private static final WorldQuarantine QUARANTINE =
        new WorldQuarantine(BTCCoreConfig.worldQuarantineMaxPerMinute, System::nanoTime);

    private WorldTickLoop() {
    }

    private static int permits() {
        int configured = BTCCoreConfig.parallelWorldTickingThreads;
        return configured > 0 ? configured : Math.max(1, Runtime.getRuntime().availableProcessors() / AUTO_CORES_PER_PERMIT);
    }

    /** Ticks every world once and comes back when all of them are done. Main thread. */
    public static void tick(MinecraftServer server, BooleanSupplier haveTime) {
        Map<ServerLevel, Throwable> failures =
            COORDINATOR.tickAll(server.getAllLevels(), level -> tickLevel(server, level, haveTime));
        if (!failures.isEmpty()) {
            // As in the vanilla loop a world that throws stops the server, unless world.quarantine takes
            // it out instead - but only once every world has finished its tick, and with every failure
            // in the crash instead of the first one.
            WorldTickSteps.settle(failures, COORDINATOR::isReleased,
                (level, thrown) -> LOGGER.warn("World {} was unloaded while it ticked: what its last tick threw is only logged.",
                    level.getWorld().getName(), thrown),
                (level, thrown) -> failingTick(server, level, thrown));
        }
        // After the quarantines of this tick, so that no journey ends in a world that just stopped ticking.
        DEFERRED.drain(failure -> LOGGER.error("What a world thread left to the main thread, because it reached into "
            + "another world, threw.", failure));
    }

    /**
     * Something that reaches into every world was asked on the thread of one of them: the main
     * thread does it once every world has ticked, this same server tick.
     *
     * @return false when the caller is to do it now: the option is off, or this is not a world thread
     */
    public static boolean deferToMain(Runnable task) {
        if (CrossWorldQueue.route(PARALLEL, TickThread.isServerLevelTickThread(), false) == CrossWorldQueue.Route.INLINE) return false;
        DEFERRED.defer(task);
        return true;
    }

    /**
     * An entity is to travel - a teleport, a portal, an ender pearl. Asked on the thread of a world
     * that does not tick both the world it is in and the one it goes to, the journey is made by the
     * main thread once every world has ticked, if the entity is still there and the target still
     * ticks.
     *
     * @param target  the world it goes to; null when only the journey itself can tell (a portal)
     * @param api     the Bukkit call that asked and cannot report the outcome any more, for a warning
     *                logged once per call site; null for a journey of the game itself
     * @return false when the caller is to make the journey now
     */
    public static boolean deferJourney(Entity traveller, @Nullable ServerLevel target, Runnable journey, @Nullable String api) {
        boolean ownsAll = CrossWorldQueue.ownsJourney(target != null,
            TickThread.isTickThreadFor(traveller.level()), target != null && TickThread.isTickThreadFor(target));
        if (CrossWorldQueue.route(PARALLEL, TickThread.isServerLevelTickThread(), ownsAll) == CrossWorldQueue.Route.INLINE) return false;
        if (api != null) warnDeferred(api, traveller, target);
        DEFERRED.deferJourney(journey,
            () -> CrossWorldQueue.staleReason(traveller.isRemoved(),
                target != null && target.getServer().getLevel(target.dimension()) != target,
                target != null && COORDINATOR.isSuspended(target)),
            stale -> {
                if (traveller.isRemoved()) return; // an entity that died or merged meanwhile is no news
                LOGGER.warn("{} does not travel{}: {}.", traveller, target == null ? "" : " to world " + target.getWorld().getName(), stale);
            });
        return true;
    }

    /**
     * {@link #deferJourney} for a teleport whose events were fired on this thread, a listener of
     * which sent the entity to another world: the main thread runs {@code teleport} again, and
     * {@link #resumesTeleport} tells that run not to fire them a second time.
     */
    public static boolean deferChangedJourney(Entity traveller, ServerLevel target, Runnable teleport) {
        return deferJourney(traveller, target, () -> RESUMED.resume(traveller, teleport), null);
    }

    /** Is this teleport the one {@link #deferChangedJourney} left to the main thread, its events already fired? */
    public static boolean resumesTeleport(Entity traveller) {
        return PARALLEL && WorldOwnership.isMainOnly(TickThread.isTickThread(), TickThread.isServerLevelTickThread())
            && RESUMED.take(traveller);
    }

    /**
     * A journey is about to be made on this thread - asked on the main thread, or on the thread that
     * ticks both worlds. It is refused, as a deferred one is, when the world it goes to is
     * quarantined or left the server.
     *
     * @return true when the journey is not to be made
     */
    public static boolean refusesJourney(Entity traveller, ServerLevel target) {
        if (target == traveller.level()) return false; // within a world: nothing arrives anywhere
        String stale = CrossWorldQueue.staleReason(false,
            target.getServer().getLevel(target.dimension()) != target, COORDINATOR.isSuspended(target));
        if (stale == null) return false;
        LOGGER.warn("{} does not travel to world {}: {}.", traveller, target.getWorld().getName(), stale);
        return true;
    }

    /**
     * A menu is to open for a player. Asked on the thread of a world the player is not in, it is
     * opened by the main thread once every world has ticked, if the player is still on the server:
     * opened here, it would be written while the thread of the player's world ticks that player.
     *
     * @param api the call that asked and cannot return the view any more, for a warning logged once
     *            per call site
     * @return false when the caller is to open it now
     */
    public static boolean deferMenu(Entity player, Runnable open, String api) {
        boolean owns = TickThread.isTickThreadFor(player.level());
        if (CrossWorldQueue.route(PARALLEL, TickThread.isServerLevelTickThread(), owns) == CrossWorldQueue.Route.INLINE) return false;
        String site = callSite();
        if (DEFERRED.firstTime(api + " " + site)) {
            LOGGER.warn("{} called {} on thread '{}' for {}, who is in world {}: the menu is opened at the end of this "
                    + "tick, and the call returned without a view. Ask from the scheduler of the player instead. Logged "
                    + "once per call site.",
                site, api, Thread.currentThread().getName(), player, player.level().getWorld().getName(), new Throwable("the call"));
        }
        DEFERRED.defer(() -> {
            if (!player.isRemoved()) open.run(); // a player who left meanwhile gets no menu
        });
        return true;
    }

    /** The plugin frame nearest to the caller. The stack is only walked on the paths that should not exist. */
    private static String callSite() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
            .filter(frame -> frame.getDeclaringClass().getClassLoader() instanceof ConfiguredPluginClassLoader)
            .findFirst()
            .map(frame -> ((ConfiguredPluginClassLoader) frame.getDeclaringClass().getClassLoader()).getConfiguration().getName()
                + " (" + frame.toStackTraceElement() + ")")
            .orElse("the server"));
    }

    private static void warnDeferred(String api, Entity traveller, ServerLevel target) {
        String site = callSite(); // an extension is to ask with teleportAsync
        if (!DEFERRED.firstTime(api + " " + site)) return;
        LOGGER.warn("{} called {} on thread '{}' for a journey between two worlds ({} to world {}): it is made at the "
                + "end of this tick, and was reported as done without knowing. Use teleportAsync, whose result tells the "
                + "outcome. Logged once per call site.",
            site, api, Thread.currentThread().getName(), traveller, target.getWorld().getName(), new Throwable("the call"));
    }

    /** A world whose tick threw: quarantined (null), or the crash of the vanilla loop. */
    private static ReportedException failingTick(MinecraftServer server, ServerLevel level, Throwable thrown) {
        CrashReport report = CrashReport.forThrowable(thrown, "Exception ticking world");
        level.fillReportDetails(report);
        WorldQuarantine.Verdict verdict = QUARANTINE.decide(BTCCoreConfig.worldQuarantineEnabled,
            level instanceof SlimeLevelInstance, level.dimension() == Level.OVERWORLD,
            BTCCoreConfig.worldQuarantineSaveBeforeUnload, thrown);
        if (verdict == WorldQuarantine.Verdict.CRASH) return new ReportedException(report);
        quarantine(server, level, report, verdict == WorldQuarantine.Verdict.SAVE_AND_UNLOAD);
        return null;
    }
    /** The world gets its own crash report, stops ticking, and leaves the server once its players are out. */
    private static void quarantine(MinecraftServer server, ServerLevel level, CrashReport report, boolean save) {
        String name = level.getWorld().getName();
        LOGGER.error("World {} threw while ticking. It is quarantined: no longer ticked, its players are moved out, "
            + "then it is unloaded {}.", name, save ? "after one save" : "without saving", report.getException());
        server.fillSystemReport(report.getSystemReport());
        Path file = server.getServerDirectory().resolve("crash-reports")
            .resolve("crash-" + Util.getFilenameFormattedDateTime() + "-world-" + level.getWorld().getUID() + ".txt");
        if (report.saveToFile(file, ReportType.CRASH)) {
            LOGGER.error("The crash report of world {} has been saved to: {}", name, file.toAbsolutePath());
        } else {
            LOGGER.error("The crash report of world {} could not be saved to disk.", name);
        }
        COORDINATOR.suspend(level);
        AdvancedSlimePaper.instance().quarantine((SlimeLevelInstance) level, save); // only a Slime world gets this verdict
    }

    /**
     * The body of the vanilla loop for one world, on whichever thread ticks it. Only what
     * {@code level.tick} throws can end in a quarantine: the rest stops the server.
     */
    private static void tickLevel(MinecraftServer server, ServerLevel level, BooleanSupplier haveTime) {
        WorldThread thread = Thread.currentThread() instanceof WorldThread worldThread ? worldThread : null;
        if (thread != null) thread.currentlyTickingServerLevel = level;
        try {
            ProfilerFiller profiler = Profiler.get(); // inactive on a world thread
            WorldTickSteps.run(() -> {
                level.hasPhysicsEvent = BlockPhysicsEvent.getHandlerList().getRegisteredListeners().length > 0;
                level.hasEntityMoveEvent = EntityMoveEvent.getHandlerList().getRegisteredListeners().length > 0;
                level.updateLagCompensationTick();
                level.hopperFlags.skipHopperEvents = level.paperConfig().hopper.disableMoveEvent
                    || InventoryMoveItemEvent.getHandlerList().getRegisteredListeners().length == 0;
                profiler.push(() -> level + " " + level.dimension().identifier());
                profiler.push("tick");
                tickStart(level, server.getTickCount());
            }, () -> {
                try {
                    level.tick(haveTime);
                } catch (Throwable failure) {
                    // A quarantined world does not stop the server: the profiler of this tick goes on.
                    profiler.pop();
                    profiler.pop();
                    throw failure;
                }
            }, () -> {
                tickEnd(level, server.getTickCount());
                profiler.pop();
                profiler.pop();
                level.explosionDensityCache.clear();
            });
        } finally {
            if (thread != null) thread.currentlyTickingServerLevel = null;
        }
    }
    /** Fires {@link WorldTickStartEvent} if something listens. Called on the thread about to tick the world. */
    public static void tickStart(ServerLevel level, int tick) {
        if (WorldTickStartEvent.getHandlerList().getRegisteredListeners().length > 0) {
            new WorldTickStartEvent(level.getWorld(), tick).callEvent();
        }
        WorldTickMetrics metrics = level.tickMetrics; // world.metrics: null with the option off
        if (metrics != null) metrics.begin(System.nanoTime());
    }

    /** Fires {@link WorldTickEndEvent} if something listens. Called on the thread that ticked the world. */
    public static void tickEnd(ServerLevel level, int tick) {
        WorldTickMetrics metrics = level.tickMetrics; // world.metrics: null with the option off
        if (metrics != null) {
            metrics.end(System.nanoTime(), level.getChunkSource().getLoadedChunksCount(),
                level.moonrise$getEntityLookup().getEntityCount(), level.players().size());
        }
        if (WorldTickEndEvent.getHandlerList().getRegisteredListeners().length > 0) {
            new WorldTickEndEvent(level.getWorld(), tick).callEvent();
        }
    }

    /**
     * Mid-tick chunk tasks on a world thread: its own world only, with its own back-off, so that it
     * neither polls a world another thread is ticking nor writes the server-wide back-off fields.
     *
     * @return false on any other thread, which then runs Moonrise's own mid-tick tasks
     */
    public static boolean midTick() {
        if (!(Thread.currentThread() instanceof WorldThread thread)) return false;
        ServerLevel level = thread.currentlyTickingServerLevel;
        long start = System.nanoTime();
        if (level == null || start - thread.nextMidTickNanos < 0L) return true;
        for (;;) {
            boolean ranOne = level.getChunkSource().pollTask();
            long now = System.nanoTime();
            if (ranOne && now - start < MidTickBudget.ALLOWANCE_NANOS) continue;
            thread.nextMidTickNanos = now + MidTickBudget.delayNanos(now - start, !ranOne);
            return true;
        }
    }

    /**
     * A world is about to be created, registered, unloaded or closed: under
     * {@code parallel-world-ticking} only the main thread may, see {@link WorldLifecycleGate}.
     *
     * @param operation what is asked, for the message: "unload world dungeon_1"
     */
    public static void ensureLifecycle(String operation) {
        WorldLifecycleGate.Caller caller = TickThread.isServerLevelTickThread() ? WorldLifecycleGate.Caller.WORLD_THREAD
            : TickThread.isTickThread() ? WorldLifecycleGate.Caller.MAIN : WorldLifecycleGate.Caller.OTHER;
        WorldLifecycleGate.check(PARALLEL, caller, operation);
    }

    /** A world left the server: its thread stops. */
    public static void release(ServerLevel level) {
        COORDINATOR.release(level);
        if (BTCCoreConfig.worldLeakDiagnosticEnabled) WorldDiagnostics.unloaded(level);
    }

    /** End of the server's stop sequence: every world thread stops. */
    public static void shutdown() {
        COORDINATOR.shutdown();
    }

    private static final class WorldThread extends TickThread.ServerLevelTickThread {

        private long nextMidTickNanos = System.nanoTime();

        WorldThread(ServerLevel level, Runnable task) {
            super(task, THREAD_NAME_PREFIX + level.getWorld().getName());
            this.setDaemon(true); // parked between two ticks: must never be what keeps a stopping JVM alive
        }
    }
}
