package dev.btc.core.async;

import dev.btc.core.config.BTCCoreConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BTCCore: Async Entity Tracker.
 * Offloads entity tracking computations (visibility checks, distance calculations,
 * packet construction) to a dedicated thread pool.
 *
 * The tracking computation runs asynchronously, but packet dispatch is deferred
 * to the entity's region thread (Folia-safe).
 *
 * Wired via:
 * - NMS hook in apply-btccore-patches.py: intercepts ChunkMap.EntityTracker.tick()
 * - Thread pool configured by BTCCoreConfig.asyncEntityTrackerEnabled / asyncEntityTrackerThreads
 */
public final class AsyncEntityTracker {
    private static final AtomicInteger threadCounter = new AtomicInteger(0);
    private static volatile ExecutorService trackerPool;
    private static final ConcurrentLinkedQueue<Runnable> pendingDispatch = new ConcurrentLinkedQueue<>();

    private AsyncEntityTracker() {}

    /**
     * Initializes the async tracker thread pool.
     */
    public static void init() {
        if (!BTCCoreConfig.asyncEntityTrackerEnabled) return;
        if (trackerPool != null && !trackerPool.isShutdown()) return;

        int threads = BTCCoreConfig.asyncEntityTrackerThreads;
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "BTCCore-EntityTracker-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };

        trackerPool = Executors.newFixedThreadPool(threads, factory);
        Bukkit.getLogger().info("[BTCCore] Async Entity Tracker pool initialized: " + threads + " threads");
    }

    /**
     * Shuts down the tracker thread pool.
     */
    public static void shutdown() {
        if (trackerPool != null && !trackerPool.isShutdown()) {
            trackerPool.shutdown();
            try {
                trackerPool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            trackerPool = null;
        }
    }

    /**
     * Checks if async entity tracking is enabled.
     */
    public static boolean isEnabled() {
        return BTCCoreConfig.asyncEntityTrackerEnabled && trackerPool != null && !trackerPool.isShutdown();
    }

    /**
     * Submits an entity tracking computation to the async pool.
     * The computation should perform visibility/distance checks and queue
     * any resulting packets for dispatch on the region thread.
     *
     * @param task The tracking computation to run async
     */
    public static void submitTracking(Runnable task) {
        if (!isEnabled()) {
            task.run();
            return;
        }
        trackerPool.submit(task);
    }

    /**
     * Queue a packet dispatch to be executed on the main/region thread.
     * Called from async tracking tasks.
     */
    public static void queueDispatch(Runnable dispatch) {
        pendingDispatch.add(dispatch);
    }

    /**
     * Flushes all pending packet dispatches.
     * Called from the global region scheduler tick (BTCCoreListener.onTick).
     */
    public static void flushDispatch() {
        if (pendingDispatch.isEmpty()) return;
        Runnable r;
        while ((r = pendingDispatch.poll()) != null) {
            try {
                r.run();
            } catch (Exception e) {
                Bukkit.getLogger().warning("[BTCCore] Error flushing entity tracker dispatch: " + e.getMessage());
            }
        }
    }
}
