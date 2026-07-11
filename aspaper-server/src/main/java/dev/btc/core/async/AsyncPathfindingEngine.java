package dev.btc.core.async;

import dev.btc.core.config.BTCCoreConfig;
import org.bukkit.Bukkit;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BTCCore: Async Pathfinding Engine.
 * Offloads pathfinding computations to a dedicated thread pool.
 * Path results are queued and applied on the entity's region thread (Folia-safe).
 *
 * The engine uses a bounded thread pool with configurable reject policy
 * (FLUSH_ALL, CALLER_RUNS, DISCARD, DISCARD_OLDEST) to handle overload.
 *
 * Wired via:
 * - NMS hook in apply-btccore-patches.py: intercepts Mob.navigation.computePath()
 * - Thread pool configured by BTCCoreConfig.asyncPathfindingEnabled / asyncPathfindingMaxThreads
 */
public final class AsyncPathfindingEngine {
    private static final AtomicInteger threadCounter = new AtomicInteger(0);
    private static volatile ExecutorService pathfindingPool;
    private static final ConcurrentLinkedQueue<Runnable> pendingPaths = new ConcurrentLinkedQueue<>();

    private AsyncPathfindingEngine() {}

    /**
     * Initializes the async pathfinding thread pool.
     */
    public static void init() {
        if (!BTCCoreConfig.asyncPathfindingEnabled) return;
        if (pathfindingPool != null && !pathfindingPool.isShutdown()) return;

        int threads = BTCCoreConfig.asyncPathfindingMaxThreads;
        if (threads <= 0) {
            threads = Math.max(Runtime.getRuntime().availableProcessors() / 4, 1);
        }

        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "BTCCore-Pathfinder-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };

        // Use a fixed thread pool. The reject policy is handled in submitPathfinding().
        pathfindingPool = Executors.newFixedThreadPool(threads, factory);
        Bukkit.getLogger().info("[BTCCore] Async Pathfinding pool initialized: " + threads + " threads, queue=" + BTCCoreConfig.asyncPathfindingQueueSize);
    }

    /**
     * Shuts down the pathfinding thread pool.
     */
    public static void shutdown() {
        if (pathfindingPool != null && !pathfindingPool.isShutdown()) {
            pathfindingPool.shutdown();
            try {
                pathfindingPool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pathfindingPool = null;
        }
    }

    /**
     * Checks if async pathfinding is enabled.
     */
    public static boolean isEnabled() {
        return BTCCoreConfig.asyncPathfindingEnabled && pathfindingPool != null && !pathfindingPool.isShutdown();
    }

    /**
     * Submits a pathfinding computation to the async pool.
     * If the pool is overloaded, applies the configured reject policy.
     *
     * @param task The pathfinding computation to run async
     * @return true if the task was submitted, false if rejected
     */
    public static boolean submitPathfinding(Runnable task) {
        if (!isEnabled()) {
            task.run();
            return true;
        }

        try {
            pathfindingPool.submit(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Apply reject policy
            switch (BTCCoreConfig.asyncPathfindingRejectPolicy) {
                case CALLER_RUNS -> {
                    task.run();
                    return true;
                }
                case DISCARD -> {
                    return false;
                }
                case DISCARD_OLDEST -> {
                    // Drain oldest pending and submit new
                    pendingPaths.poll();
                    try {
                        pathfindingPool.submit(task);
                        return true;
                    } catch (java.util.concurrent.RejectedExecutionException ex) {
                        return false;
                    }
                }
                case FLUSH_ALL -> {
                    // Flush all pending tasks (run them on caller thread) then submit new
                    Runnable r;
                    while ((r = pendingPaths.poll()) != null) {
                        try { r.run(); } catch (Exception ignored) {}
                    }
                    try {
                        pathfindingPool.submit(task);
                        return true;
                    } catch (java.util.concurrent.RejectedExecutionException ex) {
                        task.run(); // Run on caller as last resort
                        return true;
                    }
                }
                default -> {
                    task.run();
                    return true;
                }
            }
        }
    }

    /**
     * Queue a path result to be applied on the entity's region thread.
     */
    public static void queuePathResult(Runnable result) {
        pendingPaths.add(result);
    }

    /**
     * Flushes all pending path results.
     * Called from the global region scheduler tick (BTCCoreListener.onTick).
     */
    public static void flushResults() {
        if (pendingPaths.isEmpty()) return;
        Runnable r;
        while ((r = pendingPaths.poll()) != null) {
            try {
                r.run();
            } catch (Exception e) {
                Bukkit.getLogger().warning("[BTCCore] Error applying pathfinding result: " + e.getMessage());
            }
        }
    }
}
