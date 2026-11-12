package dev.btc.core.playerdata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Disk work on player files, off the game threads, in order for each player.
 *
 * <p>Work for one UUID runs in the order it was handed in, one piece at a time; work for different
 * players runs side by side. That per-player order is the lock: a copy of a player's file can never
 * overlap a write of the same file, and two writes never land out of order.
 *
 * <p>Once {@link #shutdown} was called, work runs on the calling thread instead of being dropped,
 * behind whatever is still queued for its player: a late save is slow, never lost nor out of order.
 */
public final class PlayerDataIo {

    private static final Logger LOGGER = LogManager.getLogger("BTCCore");
    private static final int THREADS = 2;
    private static final PlayerDataIo SHARED = new PlayerDataIo();

    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
    private final AtomicInteger threadIds = new AtomicInteger();
    private volatile ExecutorService pool;
    private volatile boolean closed;

    /** The instance the server uses; its threads start with the first piece of work. */
    public static PlayerDataIo shared() {
        return SHARED;
    }

    /**
     * Queues {@code work} behind whatever is already queued for {@code id}.
     *
     * @return completes when the work has run; a failure of the work is logged, not rethrown
     */
    public CompletableFuture<Void> submit(UUID id, Runnable work) {
        if (closed) {
            await(id); // what a shutdown that timed out left queued for this player still lands first
            run(id, work);
            return CompletableFuture.completedFuture(null);
        }
        ExecutorService executor = pool();
        CompletableFuture<Void> next = tails.compute(id, (key, tail) ->
            (tail == null ? CompletableFuture.<Void>completedFuture(null) : tail)
                .handleAsync((done, failure) -> {
                    run(id, work);
                    return null;
                }, executor));
        return next.handle((done, failure) -> {
            tails.remove(id, next);
            if (failure != null) {
                run(id, work); // the threads were stopped in between: late, on this thread, not lost
            }
            return null;
        });
    }

    /** Blocks until the work queued so far for {@code id} has run; returns at once when there is none. */
    public void await(UUID id) {
        CompletableFuture<Void> tail = tails.get(id);
        if (tail != null) {
            tail.handle((done, failure) -> null).join();
        }
    }

    /**
     * Waits for everything queued, then stops the threads.
     *
     * @return false when work was still queued after {@code timeout}
     */
    public boolean shutdown(long timeout, TimeUnit unit) {
        closed = true;
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        boolean drained = true;
        while (!tails.isEmpty()) {
            try {
                CompletableFuture.allOf(tails.values().toArray(CompletableFuture[]::new))
                    .get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                drained = false;
                break;
            } catch (java.util.concurrent.TimeoutException late) {
                drained = false;
                break;
            } catch (java.util.concurrent.ExecutionException impossible) {
                // Only a stopped pool does that, and submit() then runs the work itself.
            }
        }
        ExecutorService executor = pool;
        if (executor != null) {
            executor.shutdown();
        }
        if (!drained) {
            LOGGER.warn("[BTCCore] Player file work was still queued for {} player(s) at shutdown.", tails.size());
        }
        return drained;
    }

    private static void run(UUID id, Runnable work) {
        try {
            work.run();
        } catch (Throwable failure) {
            LOGGER.warn("[BTCCore] Player file work failed for {}", id, failure);
        }
    }

    private ExecutorService pool() {
        ExecutorService executor = pool;
        if (executor == null) {
            synchronized (this) {
                executor = pool;
                if (executor == null) {
                    pool = executor = Executors.newFixedThreadPool(THREADS, task -> {
                        Thread thread = new Thread(task, "btccore-playerdata-io-" + threadIds.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    });
                }
            }
        }
        return executor;
    }
}
