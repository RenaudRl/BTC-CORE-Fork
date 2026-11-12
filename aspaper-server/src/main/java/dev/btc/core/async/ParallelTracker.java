/*
 * Derived from DivineMC, patch "Parallel entity tracker" and
 * org.bxteam.divinemc.async.tracking.ParallelEntityTracker (NONPLAYT, BX Team), GPL-3.0,
 * https://github.com/BX-Team/DivineMC - itself derived from Petal's multithreaded tracker
 * (peaches94, GPL-3.0).
 *
 * Kept from DivineMC: the visibility test split out of ChunkMap.TrackedEntity#updatePlayer so it
 * can be evaluated read-only, the batches joined inside the tick, the batch size. Narrowed for
 * BTC-CORE: the scan only answers "would the tracker tick change anything", Paper's own code then
 * does every change on the owning thread, and nothing is sent from a pool thread.
 */
package dev.btc.core.async;

import com.google.common.util.concurrent.Uninterruptibles;
import dev.btc.core.config.BTCCoreConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The thread-facing half of {@code async.parallel-tracker}: a pool, a barrier, and the visibility
 * comparison the pool runs. Nothing in here knows about entities, so it is tested without a server;
 * the caller is {@code ChunkMap#btc$parallelTrackerTick}.
 */
public final class ParallelTracker {

    /** Below this many entities per batch, handing work to another thread costs more than it saves. */
    static final int MIN_BATCH_SIZE = 64;
    private static final int MAX_AUTO_THREADS = 4;
    private static final long KEEP_ALIVE_SECONDS = 60L;

    // Guarded by the class lock. Several worlds may share it: batches never share an index range.
    private static ThreadPoolExecutor pool;

    private ParallelTracker() {
    }

    /** One slice of the tracked entities, {@code from} inclusive to {@code to} exclusive. */
    @FunctionalInterface
    public interface Batch {
        void run(int from, int to);
    }

    /**
     * Whether one nearby player is in the wrong state: should see the entity and does not (enters),
     * or sees it and should not (leaves). Reads only.
     *
     * @param nearby raw backing array of the chunk's player list, valid up to {@code len}
     * @param self   the tracked entity, never compared against itself
     */
    public static <P> boolean visibilityChanged(final P[] nearby, final int len, final Object self,
                                                final Predicate<? super P> wantsToSee, final Predicate<? super P> seen) {
        for (int i = 0; i < len; ++i) {
            final P player = nearby[i];
            if (player != self && wantsToSee.test(player) != seen.test(player)) {
                return true;
            }
        }
        return false;
    }

    /** Runs {@code batch} over {@code [0, len)} on the shared pool and returns once all of it is done. */
    public static void runBatches(final int len, final Batch batch) {
        final ThreadPoolExecutor executor = pool();
        runBatches(executor, executor.getMaximumPoolSize(), len, batch);
    }

    /**
     * Splits {@code [0, len)} between {@code workers} pool threads and the caller, and does not
     * return, normally or by exception, while a pool thread is still inside {@code batch}: that is
     * the barrier the tracker relies on before it mutates anything.
     */
    public static void runBatches(final ExecutorService executor, final int workers, final int len, final Batch batch) {
        final int batches = Math.min(workers + 1, len / MIN_BATCH_SIZE);
        if (batches <= 1) {
            batch.run(0, len);
            return;
        }

        final int step = (len + batches - 1) / batches;
        final List<Future<?>> pending = new ArrayList<>(batches - 1);
        Throwable failure = null;
        int from = 0;
        for (; from + step < len; from += step) {
            final int batchFrom = from;
            final int batchTo = from + step;
            try {
                pending.add(executor.submit(() -> batch.run(batchFrom, batchTo)));
            } catch (final RejectedExecutionException stopped) {
                // The pool was stopped under us (server going down): the range still has to be covered.
                failure = runHere(batch, batchFrom, batchTo, failure);
            }
        }
        failure = runHere(batch, from, len, failure); // the caller takes the last range instead of only waiting

        for (final Future<?> future : pending) {
            try {
                Uninterruptibles.getUninterruptibly(future);
            } catch (final ExecutionException e) {
                if (failure == null) {
                    failure = e.getCause();
                }
            }
        }
        if (failure != null) {
            // Paper lets a tracker exception end the tick; so do we, once everybody has stopped.
            throw new IllegalStateException("Parallel tracker batch failed", failure);
        }
    }

    private static Throwable runHere(final Batch batch, final int from, final int to, final Throwable failure) {
        try {
            batch.run(from, to);
            return failure;
        } catch (final Throwable thrown) {
            return failure == null ? thrown : failure;
        }
    }

    private static synchronized ThreadPoolExecutor pool() {
        if (pool == null) {
            final int configured = BTCCoreConfig.parallelTrackerThreads;
            final int threads = configured > 0
                ? configured
                : Math.max(1, Math.min(MAX_AUTO_THREADS, Runtime.getRuntime().availableProcessors() / 2));
            final AtomicInteger ids = new AtomicInteger();
            pool = new ThreadPoolExecutor(threads, threads, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
                final Thread thread = new Thread(task, "BTC Parallel Tracker #" + ids.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            pool.allowCoreThreadTimeOut(true);
        }
        return pool;
    }

    /** Stops the pool. Called once the server has stopped ticking; a later tick would start a new one. */
    public static synchronized void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }
}
