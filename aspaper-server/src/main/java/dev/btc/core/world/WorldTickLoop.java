package dev.btc.core.world;

import ca.spottedleaf.moonrise.common.util.TickThread;
import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.event.world.WorldTickEndEvent;
import dev.btc.core.event.world.WorldTickStartEvent;
import dev.btc.core.parallel.MidTickBudget;
import dev.btc.core.parallel.WorldTickCoordinator;
import io.papermc.paper.event.entity.EntityMoveEvent;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The world part of a server tick under {@code parallel-world-ticking}: the glue between
 * {@code MinecraftServer.tickChildren} and {@link WorldTickCoordinator}, which holds the scheduling.
 *
 * <p>With the option off {@link #ACTIVE} is false and the vanilla loop of {@code tickChildren} runs
 * untouched; this class then only fires the world tick events, and only if something listens.
 *
 * <p>Loop ported from DivineMC "Parallel world ticking" (NONPLAYT, BX Team, GPL-3.0), itself from
 * SparklyPaper (SparklyPower).
 */
public final class WorldTickLoop {

    /** {@code parallel-world-ticking.enabled}, read once: a world cannot change thread while the server runs. */
    public static final boolean PARALLEL = BTCCoreConfig.parallelWorldTickingEnabled;
    /** The worlds tick behind the coordinator instead of in the vanilla loop. */
    public static final boolean ACTIVE = PARALLEL;

    private static final int AUTO_CORES_PER_PERMIT = 2;
    private static final String THREAD_NAME_PREFIX = "BTC World Tick - ";

    private static final WorldTickCoordinator<ServerLevel> COORDINATOR =
        new WorldTickCoordinator<>(PARALLEL, permits(), WorldThread::new);

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

        // As in the vanilla loop a world that throws stops the server - but only once every world
        // has finished its tick, and with every failure in the report instead of the first one.
        ReportedException crash = null;
        for (Map.Entry<ServerLevel, Throwable> failure : failures.entrySet()) {
            CrashReport report = CrashReport.forThrowable(failure.getValue(), "Exception ticking world");
            failure.getKey().fillReportDetails(report);
            ReportedException reported = new ReportedException(report);
            if (crash == null) crash = reported; else crash.addSuppressed(reported);
        }
        throw crash;
    }

    /** The body of the vanilla loop for one world, on whichever thread ticks it. */
    private static void tickLevel(MinecraftServer server, ServerLevel level, BooleanSupplier haveTime) {
        WorldThread thread = Thread.currentThread() instanceof WorldThread worldThread ? worldThread : null;
        if (thread != null) thread.currentlyTickingServerLevel = level;
        try {
            level.hasPhysicsEvent = BlockPhysicsEvent.getHandlerList().getRegisteredListeners().length > 0;
            level.hasEntityMoveEvent = EntityMoveEvent.getHandlerList().getRegisteredListeners().length > 0;
            level.updateLagCompensationTick();
            // shortcut: still one static for every world, so worlds with different hopper settings
            // race on it; made per world with the other shared statics (step 5 of the port).
            HopperBlockEntity.skipHopperEvents = level.paperConfig().hopper.disableMoveEvent
                || InventoryMoveItemEvent.getHandlerList().getRegisteredListeners().length == 0;
            ProfilerFiller profiler = Profiler.get(); // inactive on a world thread
            profiler.push(() -> level + " " + level.dimension().identifier());
            profiler.push("tick");
            tickStart(level, server.getTickCount());
            level.tick(haveTime);
            tickEnd(level, server.getTickCount());
            profiler.pop();
            profiler.pop();
            level.explosionDensityCache.clear();
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
