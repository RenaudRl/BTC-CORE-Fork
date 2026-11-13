package dev.btc.core.world;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.infernalsuite.asp.AdvancedSlimePaper;
import com.infernalsuite.asp.level.SlimeLevelInstance;
import com.mojang.logging.LogUtils;
import dev.btc.core.config.AnticheatConfig;
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
import dev.btc.core.playerdata.SaveAlignment;
import io.papermc.paper.event.entity.EntityMoveEvent;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.ReportedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

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
            COORDINATOR.tickAll(awake(server.getAllLevels()), level -> tickLevel(server, level, haveTime));
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
     * {@code world.freeze-at-rest}: whether this world skips its tick, asked once per server tick.
     * Main thread, before the world's turn: no world thread runs then, so its players and its
     * tickets are read as they stand. A player or a ticket that comes in later wakes it next tick.
     * The vanilla loop of {@code tickChildren} asks it ({@link #ACTIVE} false); a world it skips warm
     * still moves its chunk system on, here: see {@link #tickChunkSystem}.
     */
    public static boolean atRest(ServerLevel level) {
        if (!skips(level)) return false;
        // world.freeze-at-rest.warm: in the vanilla loop the main thread is the thread of every world
        if (chunksToMoveOn(level)) tickChunkSystem(level);
        return true;
    }

    /** {@link #atRest} without the chunk system of a warm-skipped world: the decision alone. */
    private static boolean skips(ServerLevel level) {
        if (BTCCoreConfig.worldFreezeAtRestEnabled && level.btc$rest.skipsTick(occupied(level))) return true;
        // world.freeze-at-rest.warm: "no player" is per world - its players are read as they stand, as above;
        // the phase from the world's id keeps the warm worlds from all ticking on the same server tick
        return BTCCoreConfig.worldWarmEnabled && level.btc$rest.warmSkips(level.dimension() != Level.OVERWORLD && level.players().isEmpty(),
            level.getServer().getTickCount(), level.uuid.hashCode(), BTCCoreConfig.worldWarmTickInterval, BTCCoreConfig.worldWarmMaxCatchUpTicks);
    }

    /**
     * {@code world.freeze-at-rest.warm}: skipped ticks this tick of the world makes up for - random
     * ticks and lit furnaces. 0 with the option off, whatever was left counted. Read by the thread
     * ticking the world.
     */
    public static int catchUp(ServerLevel level) {
        return BTCCoreConfig.worldWarmEnabled ? level.btc$rest.catchUp() : 0;
    }

    /** {@code world.freeze-at-rest.warm}: this server tick the world skipped, decided by {@link #skips} before its turn. */
    private static boolean warmSkipped(ServerLevel level) {
        return BTCCoreConfig.worldWarmEnabled && level.btc$rest.warmSkipped();
    }

    /**
     * {@link #warmSkipped}, with tickets or chunk holders to move on. Main thread, before the world's
     * turn, as {@link #occupied}: both tables are concurrent, and a ticket added after the read is
     * moved on next tick.
     */
    private static boolean chunksToMoveOn(ServerLevel level) {
        if (!warmSkipped(level)) return false;
        var chunks = level.moonrise$getChunkTaskScheduler().chunkHolderManager;
        return level.btc$rest.chunksToMoveOn(chunks.hasTickets(), chunks.size());
    }

    /**
     * {@code world.freeze-at-rest.warm}: what a world still does on a tick it skips warm - the time
     * of its chunk system goes on as {@code level.tick} moves it: tickets age and expire, chunks
     * without one unload with their entities (and the unload events), the auto-save runs. So ticket
     * timeouts and {@code delay-chunk-unloads-by} count in server ticks, and a world unloaded while
     * warm has no chunk left loaded for long. On the thread of the world.
     */
    private static void tickChunkSystem(ServerLevel level) {
        // tickChunks false: no chunk, entity or spawn tick. haveTime is not read down this path (as in purgeUnload)
        level.getChunkSource().tick(() -> true, false);
    }

    private static boolean occupied(ServerLevel level) {
        var chunks = level.moonrise$getChunkTaskScheduler().chunkHolderManager;
        return WorldRest.occupied(level.dimension() == Level.OVERWORLD, !level.players().isEmpty(), chunks.hasTickets(), chunks.size());
    }

    /**
     * The worlds handed to a thread this server tick: all of them with {@code world.freeze-at-rest}
     * off. A warm-skipped world with chunks to move on is handed over too, for its chunk system alone:
     * see {@link #tickLevel}, which then reads only {@link #warmSkipped}, fixed since this choice.
     */
    private static Iterable<ServerLevel> awake(Iterable<ServerLevel> levels) {
        if (!BTCCoreConfig.worldFreezeAtRestEnabled && !BTCCoreConfig.worldWarmEnabled) return levels;
        List<ServerLevel> awake = new ArrayList<>();
        for (ServerLevel level : levels) {
            if (!skips(level) || chunksToMoveOn(level)) awake.add(level);
        }
        return awake;
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
     * A world is to be saved ({@code world.save()} of a plugin). Asked on the thread of another
     * world, the main thread saves it once every world has ticked, unless it left the server
     * meanwhile: its unload saved it.
     *
     * @return false when the caller is to save it now
     */
    public static boolean deferSave(ServerLevel level, Runnable save) {
        if (TickThread.isTickThreadFor(level)) return false;
        return deferToMain(() -> {
            if (level.getServer().getLevel(level.dimension()) == level) save.run();
        });
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
        return target != traveller.level() && refusesArrival(traveller, target); // within a world: nothing arrives anywhere
    }

    /** {@link #refusesJourney} for an arrival that is one even in the world the entity is in: a respawn. */
    public static boolean refusesArrival(Entity traveller, ServerLevel target) {
        String stale = CrossWorldQueue.staleReason(false,
            target.getServer().getLevel(target.dimension()) != target, COORDINATOR.isSuspended(target));
        if (stale == null) return false;
        LOGGER.warn("{} does not travel to world {}: {}.", traveller, target.getWorld().getName(), stale);
        return true;
    }

    /**
     * Where a player respawns when the world its respawn chose is refused: the spawn of the server,
     * and the overworld when the world of that spawn is refused as well. The overworld is the last
     * resort and is not asked: a player who respawns has to be in a world.
     */
    public static TeleportTransition respawnElsewhere(ServerPlayer player) {
        TeleportTransition spawn = TeleportTransition.createDefault(player, TeleportTransition.DO_NOTHING);
        if (!refusesArrival(player, spawn.newLevel())) return spawn;
        ServerLevel overworld = player.level().getServer().overworld();
        // The spawn the overworld keeps for itself: Level#getRespawnData is the spawn of the server, the one just refused, in another world
        LevelData.RespawnData respawn = overworld.serverLevelData.getRespawnData();
        Vec3 position = Vec3.atBottomCenterOf(player.adjustSpawnLocation(overworld, respawn.pos()));
        return new TeleportTransition(overworld, position, Vec3.ZERO, respawn.yaw(), respawn.pitch(), TeleportTransition.DO_NOTHING);
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
        if (!deferForPlayer(player, open)) return false;
        String site = callSite();
        if (DEFERRED.firstTime(api + " " + site)) {
            LOGGER.warn("{} called {} on thread '{}' for {}, who is in world {}: the menu is opened at the end of this "
                    + "tick, and the call returned without a view. Ask from the scheduler of the player instead. Logged "
                    + "once per call site.",
                site, api, Thread.currentThread().getName(), player, player.level().getWorld().getName(), new Throwable("the call"));
        }
        return true;
    }

    /**
     * Something that writes the menu of a player - opening one, closing it. Asked on the thread of a
     * world the player is not in, the main thread does it once every world has ticked, if the player
     * is still on the server. A close returns nothing the caller could miss: no warning.
     *
     * @return false when the caller is to do it now
     */
    public static boolean deferForPlayer(Entity player, Runnable task) {
        boolean owns = TickThread.isTickThreadFor(player.level());
        if (CrossWorldQueue.route(PARALLEL, TickThread.isServerLevelTickThread(), owns) == CrossWorldQueue.Route.INLINE) return false;
        DEFERRED.defer(() -> {
            if (!player.isRemoved()) task.run(); // a player who left meanwhile has no menu
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
        boolean save = verdict == WorldQuarantine.Verdict.SAVE_AND_UNLOAD;
        quarantine(server, level, report, save,
            save ? null : WorldQuarantine.unsavedUnloadReason(BTCCoreConfig.worldQuarantineSaveBeforeUnload, thrown));
        return null;
    }
    /**
     * The world gets its own crash report, stops ticking, and leaves the server once its players are out.
     *
     * @param unsavedReason why it is unloaded without a save; {@code null} when it is saved
     */
    private static void quarantine(MinecraftServer server, ServerLevel level, CrashReport report, boolean save,
                                   String unsavedReason) {
        String name = level.getWorld().getName();
        LOGGER.error("World {} threw while ticking. It is quarantined: no longer ticked, its players are moved out, "
            + "then it is unloaded {}.", name, save ? "after one save" : "without saving", report.getException());
        if (!save) {
            // BTC-CORE (5.1): the players keep their inventories while the world goes back to its last save. Main thread,
            // the worlds having ticked: nothing writes the player list. Counts only, no player is named.
            SlimeLevelInstance slime = (SlimeLevelInstance) level; // only a Slime world gets this verdict
            Set<UUID> present = level.players().stream().map(Entity::getUUID).collect(Collectors.toSet());
            boolean tracked = AnticheatConfig.saveAlignmentMode != SaveAlignment.Mode.OFF;
            LOGGER.warn(WorldQuarantine.unsavedUnloadLine(name, unsavedReason, present.size(),
                tracked ? OptionalInt.of(slime.saveAlignment().exposedBy(present).size()) : OptionalInt.empty(),
                tracked ? slime.saveAlignment().secondsSinceLastSave() : OptionalLong.empty()));
        }
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
            if (warmSkipped(level)) {
                // a failure here ends as one of level.tick would: in a quarantine, if the option allows it
                WorldTickSteps.run(() -> { }, () -> tickChunkSystem(level), () -> { });
                return;
            }
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
