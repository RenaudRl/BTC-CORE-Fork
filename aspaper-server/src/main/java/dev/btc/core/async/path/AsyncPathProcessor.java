/*
 * Derived from Petal "Async Pathfinding" (peaches94, GPL-3.0-only, https://github.com/Bloom-host/Petal),
 * fixed and updated by Kaiiju (GPL-3.0-only, https://github.com/KaiijuMC/Kaiiju), as carried by Leaf
 * (org.dreeam.leaf.async.path.AsyncPathProcessor), GPL-3.0.
 *
 * Changed for BTC-CORE: a fixed pool with one rule when it is full or stopped - the caller runs the
 * search itself. Upstream's policies that drop or flush queued searches are not ported: a dropped
 * search is a mob that waits for ever.
 */
package dev.btc.core.async.path;

import dev.btc.core.config.BTCCoreConfig;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** The pool behind {@code async.async-pathfinding}. Searches go in; nothing comes back but a published result. */
public final class AsyncPathProcessor {

    private static final String THREAD_PREFIX = "BTC Async Pathfinding";
    public static final Logger LOGGER = LogManager.getLogger(THREAD_PREFIX);
    private static final int QUEUED_SEARCHES_PER_THREAD = 256;
    private static final long KEEP_ALIVE_SECONDS = 60L;
    private static final int CORES_PER_AUTO_THREAD = 4;

    // Guarded by the class lock.
    private static ThreadPoolExecutor pool;

    private AsyncPathProcessor() {
    }

    static void queue(final Runnable search) {
        pool().execute(search);
    }

    private static synchronized ThreadPoolExecutor pool() {
        if (pool == null) {
            final int configured = BTCCoreConfig.asyncPathfindingThreads;
            final int threads = configured > 0
                ? configured
                : Math.max(1, Runtime.getRuntime().availableProcessors() / CORES_PER_AUTO_THREAD);
            final AtomicInteger ids = new AtomicInteger();
            pool = new ThreadPoolExecutor(
                threads, threads, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(threads * QUEUED_SEARCHES_PER_THREAD),
                task -> {
                    final Thread thread = new Thread(task, THREAD_PREFIX + " #" + ids.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 2);
                    return thread;
                },
                (task, executor) -> task.run() // full or stopped: the requesting thread searches now
            );
            pool.allowCoreThreadTimeOut(true);
        }
        return pool;
    }

    /** Stops the pool. Called once the server has stopped ticking; a later request would start a new one. */
    public static synchronized void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }
}
