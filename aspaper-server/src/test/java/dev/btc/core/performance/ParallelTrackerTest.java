package dev.btc.core.performance;

import dev.btc.core.async.ParallelTracker;
import dev.btc.core.config.BTCCoreConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two things {@code async.parallel-tracker} adds to Paper's tracker: the read-only comparison
 * that decides whether an entity's audience changed, and the barrier the tick thread waits behind
 * before it changes anything. Both are plain Java, so no server is needed.
 */
class ParallelTrackerTest {

    private static final int WORKERS = 3;
    private static final int ENTITIES = 1000;

    private final ExecutorService executor = Executors.newFixedThreadPool(WORKERS);

    @AfterEach
    void stopExecutor() {
        this.executor.shutdownNow();
    }

    private static boolean changed(final Set<String> wanted, final Set<String> seen, final String... nearby) {
        return ParallelTracker.visibilityChanged(nearby, nearby.length, "self", wanted::contains, seen::contains);
    }

    @Test
    @DisplayName("a player who should see the entity and does not yet is a change (enters)")
    void aPlayerEnteringIsAChange() {
        assertTrue(changed(Set.of("ana", "bob"), Set.of("ana"), "ana", "bob"));
    }

    @Test
    @DisplayName("a player who sees the entity and should not any more is a change (leaves)")
    void aPlayerLeavingIsAChange() {
        assertTrue(changed(Set.of("ana"), Set.of("ana", "bob"), "ana", "bob"));
    }

    @Test
    @DisplayName("nobody entering or leaving is no change, and the entity itself is never compared")
    void aSteadyAudienceIsNoChange() {
        assertFalse(changed(Set.of("ana", "self"), Set.of("ana"), "ana", "bob", "self"));
    }

    @Test
    @DisplayName("slots of the raw array past the list size are not read")
    void staleSlotsPastTheSizeAreIgnored() {
        final String[] raw = {"ana", "left-over"};

        assertFalse(ParallelTracker.visibilityChanged(raw, 1, "self", Set.of("ana", "left-over")::contains, Set.of("ana")::contains));
    }

    @Test
    @DisplayName("every index is handed out exactly once, part of it to pool threads")
    void everyIndexIsCoveredOnce() {
        final AtomicIntegerArray visits = new AtomicIntegerArray(ENTITIES);
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();

        ParallelTracker.runBatches(this.executor, WORKERS, ENTITIES, (from, to) -> {
            threads.add(Thread.currentThread());
            for (int i = from; i < to; ++i) {
                visits.incrementAndGet(i);
            }
        });

        for (int i = 0; i < ENTITIES; ++i) {
            assertEquals(1, visits.get(i), "index " + i);
        }
        assertTrue(threads.contains(Thread.currentThread()), "the caller works too");
        assertTrue(threads.size() > 1, "the pool was used");
    }

    @Test
    @DisplayName("the call does not return while a pool thread is still inside a batch")
    void theBarrierWaitsForTheSlowestBatch() {
        final Thread caller = Thread.currentThread();
        final AtomicBoolean slowBatchDone = new AtomicBoolean();

        ParallelTracker.runBatches(this.executor, WORKERS, ENTITIES, (from, to) -> {
            if (Thread.currentThread() != caller && from == 0) {
                sleep(300);
                slowBatchDone.set(true);
            }
        });

        assertTrue(slowBatchDone.get(), "returned before the first batch had finished");
    }

    @Test
    @DisplayName("a failing batch is reported only once the other batches have finished")
    void aFailureDoesNotBreakTheBarrier() {
        final Thread caller = Thread.currentThread();
        final AtomicBoolean slowBatchDone = new AtomicBoolean();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
            ParallelTracker.runBatches(this.executor, WORKERS, ENTITIES, (from, to) -> {
                if (Thread.currentThread() == caller) {
                    throw new IllegalArgumentException("boom");
                }
                if (from == 0) {
                    sleep(300);
                    slowBatchDone.set(true);
                }
            }));

        assertEquals("boom", thrown.getCause().getMessage());
        assertTrue(slowBatchDone.get(), "the failure escaped while a pool thread was still working");
    }

    @Test
    @DisplayName("too few entities to be worth a hand-off stay on the calling thread")
    void aSmallListStaysOnTheCaller() {
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();

        ParallelTracker.runBatches(this.executor, WORKERS, 100, (from, to) -> threads.add(Thread.currentThread()));

        assertEquals(Set.of(Thread.currentThread()), threads);
    }

    @Test
    @DisplayName("a pool already stopped still gets the whole range done, on the caller")
    void aStoppedPoolFallsBackToTheCaller() throws InterruptedException {
        this.executor.shutdown();
        assertTrue(this.executor.awaitTermination(5, TimeUnit.SECONDS));
        final AtomicIntegerArray visits = new AtomicIntegerArray(ENTITIES);

        ParallelTracker.runBatches(this.executor, WORKERS, ENTITIES, (from, to) -> {
            for (int i = from; i < to; ++i) {
                visits.incrementAndGet(i);
            }
        });

        for (int i = 0; i < ENTITIES; ++i) {
            assertEquals(1, visits.get(i), "index " + i);
        }
    }

    @Test
    @DisplayName("stopping the pool while a tick waits behind the barrier does not leave it waiting for ever")
    void stoppingDuringABarrierDoesNotBlock() {
        final int twoBatches = 128; // one for the pool, one for the caller
        final int configured = BTCCoreConfig.parallelTrackerThreads;
        final CountDownLatch workerBusy = new CountDownLatch(1);
        final CountDownLatch secondQueued = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        ParallelTracker.shutdown(); // the next barrier starts a pool of the size set below
        BTCCoreConfig.parallelTrackerThreads = 1;
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                // One worker: the first barrier keeps it busy, so the pool batch of the second stays queued.
                final Thread first = daemon(() -> ParallelTracker.runBatches(twoBatches, (from, to) -> {
                    if (from == 0) {
                        workerBusy.countDown();
                        await(release);
                    }
                }));
                assertTrue(workerBusy.await(10, TimeUnit.SECONDS), "the pool never took the first batch");
                final Thread second = daemon(() -> ParallelTracker.runBatches(twoBatches, (from, to) -> {
                    if (from != 0) {
                        secondQueued.countDown(); // the caller's own range, run once the pool batch is handed over
                    }
                }));
                assertTrue(secondQueued.await(10, TimeUnit.SECONDS), "the second barrier never started");

                ParallelTracker.shutdown(); // the watchdog, from its own thread
                release.countDown();

                first.join();
                second.join(); // never returns if the queued batch was thrown away
            });
        } finally {
            release.countDown();
            BTCCoreConfig.parallelTrackerThreads = configured;
            ParallelTracker.shutdown();
        }
    }

    private static Thread daemon(final Runnable task) {
        final Thread thread = new Thread(task, "test-tick-thread");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(final long millis) {
        try {
            new CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
