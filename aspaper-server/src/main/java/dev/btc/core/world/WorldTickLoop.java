package dev.btc.core.world;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.infernalsuite.asp.AdvancedSlimePaper;
import com.infernalsuite.asp.level.SlimeLevelInstance;
import com.mojang.logging.LogUtils;
import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.event.world.WorldTickEndEvent;
import dev.btc.core.event.world.WorldTickStartEvent;
import dev.btc.core.parallel.MidTickBudget;
import dev.btc.core.parallel.WorldQuarantine;
import dev.btc.core.parallel.WorldTickCoordinator;
import dev.btc.core.parallel.WorldTickSteps;
import io.papermc.paper.event.entity.EntityMoveEvent;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.ReportedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
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
        if (failures.isEmpty()) return;

        // As in the vanilla loop a world that throws stops the server, unless world.quarantine takes
        // it out instead - but only once every world has finished its tick, and with every failure
        // in the crash instead of the first one.
        WorldTickSteps.settle(failures, COORDINATOR::isReleased,
            (level, thrown) -> LOGGER.warn("World {} was unloaded while it ticked: what its last tick threw is only logged.",
                level.getWorld().getName(), thrown),
            (level, thrown) -> failingTick(server, level, thrown));
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
                // shortcut: still one static for every world, so worlds with different hopper settings
                // race on it; made per world with the other shared statics (step 5 of the port).
                HopperBlockEntity.skipHopperEvents = level.paperConfig().hopper.disableMoveEvent
                    || InventoryMoveItemEvent.getHandlerList().getRegisteredListeners().length == 0;
                profiler.push(() -> level + " " + level.dimension().identifier());
                profiler.push("tick");
                tickStart(level, server.getTickCount());
            }, () -> level.tick(haveTime), () -> {
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
    }

    /** Fires {@link WorldTickEndEvent} if something listens. Called on the thread that ticked the world. */
    public static void tickEnd(ServerLevel level, int tick) {
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

    /** A world left the server: its thread stops. */
    public static void release(ServerLevel level) {
        COORDINATOR.release(level);
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
