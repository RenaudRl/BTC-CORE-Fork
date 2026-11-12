package dev.btc.core.world;

import com.mojang.logging.LogUtils;
import dev.btc.core.config.BTCCoreConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * What {@code /btccore} reads of the worlds: what each one costs ({@code world.metrics},
 * {@link WorldTickMetrics}) and which unloaded worlds are still in memory
 * ({@code world.leak-diagnostic}, {@link WorldLeakTracker}). Both off by default: nothing here runs.
 */
public final class WorldDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final WorldLeakTracker LEAKS = new WorldLeakTracker();

    private WorldDiagnostics() {
    }

    /** The worlds that cost the most, the costliest first; empty with {@code world.metrics} off. Any thread. */
    public static List<WorldTickMetrics.Row> costliest(MinecraftServer server, int limit) {
        List<WorldTickMetrics.Row> rows = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            if (level.tickMetrics != null) rows.add(level.tickMetrics.row(level.getWorld().getName()));
        }
        return WorldTickMetrics.costliest(rows, limit);
    }

    /** A world left the server: it is looked for again once {@code check-after-seconds} have passed. */
    public static void unloaded(ServerLevel level) {
        LEAKS.unloaded(level.getWorld().getName(), level, System.nanoTime(), collections());
        CompletableFuture.delayedExecutor(BTCCoreConfig.worldLeakDiagnosticCheckAfterSeconds, TimeUnit.SECONDS)
            .execute(WorldDiagnostics::reportOverdue);
    }

    private static void reportOverdue() {
        long grace = TimeUnit.SECONDS.toNanos(BTCCoreConfig.worldLeakDiagnosticCheckAfterSeconds);
        for (WorldLeakTracker.Retained world : LEAKS.overdue(System.nanoTime(), grace, collections())) {
            LOGGER.warn("World {} is still in memory {} s after its unload, {} garbage collection(s) later: something may "
                    + "still hold it (a plugin that kept its World, a task, a listener), or the collector has not got to "
                    + "it yet. A heap dump tells which. Logged once per world; /btccore leaks lists them.",
                world.world(), TimeUnit.NANOSECONDS.toSeconds(world.ageNanos()), world.collections());
        }
    }

    /** Every unloaded world still in memory, the oldest unload first. Any thread. */
    public static List<WorldLeakTracker.Retained> retained() {
        return LEAKS.retained(System.nanoTime(), collections());
    }

    private static long collections() {
        long collections = 0L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0L, collector.getCollectionCount()); // -1: this collector does not count
        }
        return collections;
    }
}
