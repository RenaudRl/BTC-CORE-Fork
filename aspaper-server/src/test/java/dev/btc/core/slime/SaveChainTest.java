package dev.btc.core.slime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One writer per Slime world (AD-096): the saves of a world are serialized, those of two worlds are not. */
class SaveChainTest {

    private static final long WAIT_SECONDS = 10;

    /** The pool the real saves run on is 4 threads wide: wide enough to overlap two saves if nothing stops it. */
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    /** An executor that runs nothing until the test says so: what has been handed to it is visible. */
    private static final class ManualExecutor implements Executor {
        final Deque<Runnable> handedOver = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            handedOver.add(task);
        }

        void runNext() {
            handedOver.poll().run();
        }
    }

    @Test
    @DisplayName("a second save is not even handed to the pool while the first has not ended")
    void aSecondSaveWaitsForTheFirst() {
        ManualExecutor executor = new ManualExecutor();
        SaveChain chain = new SaveChain();
        List<String> order = new ArrayList<>();

        CompletableFuture<Void> first = chain.then(() -> order.add("first"), executor);
        CompletableFuture<Void> second = chain.then(() -> order.add("second"), executor);

        assertEquals(1, executor.handedOver.size(), "only the first save is ready to run");
        executor.runNext();
        assertTrue(first.isDone());
        assertFalse(second.isDone());
        assertEquals(1, executor.handedOver.size(), "the second is handed over once the first has ended");
        executor.runNext();
        assertTrue(second.isDone());
        assertEquals(List.of("first", "second"), order);
    }

    @Test
    @DisplayName("a save that fails does not stop the next one, and the failure stays on its own save")
    void aFailedSaveDoesNotStopTheNext() {
        ManualExecutor executor = new ManualExecutor();
        SaveChain chain = new SaveChain();
        List<String> order = new ArrayList<>();

        CompletableFuture<Void> first = chain.then(() -> {
            order.add("first");
            throw new IllegalStateException("the source refused it");
        }, executor);
        CompletableFuture<Void> second = chain.then(() -> order.add("second"), executor);

        executor.runNext();
        assertTrue(first.isCompletedExceptionally());
        assertEquals(1, executor.handedOver.size(), "the second save is still handed over after the failure");
        executor.runNext();
        assertTrue(second.isDone() && !second.isCompletedExceptionally(), "the newer copy is written all the same");
        assertEquals(List.of("first", "second"), order);
    }

    @Test
    @DisplayName("the end of the chain, which the lease is released after, is the last save and not an earlier one")
    void lastIsTheEndOfTheChain() {
        ManualExecutor executor = new ManualExecutor();
        SaveChain chain = new SaveChain();
        assertTrue(chain.last().isDone(), "nothing to wait for before the first save");

        chain.then(() -> { }, executor);
        CompletableFuture<Void> second = chain.then(() -> { }, executor);

        executor.runNext();
        assertFalse(chain.last().isDone(), "the first save ended, the last has not");
        executor.runNext();
        assertTrue(chain.last().isDone());
        assertTrue(chain.last() == second);
    }

    @Test
    @DisplayName("on a pool wide enough to overlap them, the second save of a world starts only when the first ends")
    void twoSavesOfOneWorldDoNotOverlapOnThePool() throws Exception {
        SaveChain world = new SaveChain();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        world.then(() -> {
            firstEntered.countDown();
            await(releaseFirst);
        }, pool);
        CompletableFuture<Void> second = world.then(secondEntered::countDown, pool);

        assertTrue(firstEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first save started");
        // A bounded wait for an event that must not happen: a slow machine can only make this pass, never fail.
        assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS), "the second save started under the first");
        releaseFirst.countDown();
        second.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(0, secondEntered.getCount(), "the second save ran once the first had ended");
    }

    @Test
    @DisplayName("a world whose save is stuck does not hold up the save of another world")
    void twoWorldsDoNotBlockEachOther() throws Exception {
        SaveChain stuck = new SaveChain();
        SaveChain other = new SaveChain();
        CountDownLatch stuckEntered = new CountDownLatch(1);
        CountDownLatch releaseStuck = new CountDownLatch(1);

        CompletableFuture<Void> stuckSave = stuck.then(() -> {
            stuckEntered.countDown();
            await(releaseStuck);
        }, pool);
        assertTrue(stuckEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first world's save started");

        other.then(() -> { }, pool).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertFalse(stuckSave.isDone(), "the other world was saved while the first was still writing");
        releaseStuck.countDown();
        stuckSave.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a caller who arrives on another thread while the first save is being handed over goes behind it")
    void aCallerArrivingWhileTheFirstSaveIsBeingHandedOverGoesBehindIt() throws Exception {
        SaveChain world = new SaveChain();
        CountDownLatch insideExecute = new CountDownLatch(1);
        CountDownLatch leaveExecute = new CountDownLatch(1);
        List<Runnable> handedOver = new CopyOnWriteArrayList<>();
        // The executor holds the first caller inside its call: the second one then arrives at a known point.
        Executor executor = task -> {
            handedOver.add(task);
            if (handedOver.size() == 1) {
                insideExecute.countDown();
                await(leaveExecute);
            }
        };

        Thread first = new Thread(() -> world.then(() -> { }, executor));
        first.start();
        assertTrue(insideExecute.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first save reached the executor");
        Thread second = new Thread(() -> world.then(() -> { }, executor));
        second.start();
        // The second caller either went through (it returned) or waits for the first (blocked): it is then
        // certain to have met the first save in flight, which is the moment this test is about.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (second.getState() != Thread.State.TERMINATED && second.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        leaveExecute.countDown();
        first.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        second.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

        assertEquals(1, handedOver.size(), "the second save was handed over under the first, which has not run");
        handedOver.get(0).run();
        assertEquals(2, handedOver.size(), "the second save is handed over once the first has ended");
    }

    @Test
    @DisplayName("an executor that refuses the save fails that save, and the chain is not left stuck behind it")
    void anExecutorThatRefusesTheSaveDoesNotJamTheChain() throws Exception {
        SaveChain world = new SaveChain();
        List<String> order = new ArrayList<>();
        Executor shutDown = task -> {
            throw new RejectedExecutionException("the pool is shut down");
        };

        CompletableFuture<Void> refused = world.then(() -> order.add("refused"), shutDown);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> refused.get(WAIT_SECONDS, TimeUnit.SECONDS),
            "the caller must be told the save was not made");
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());

        ManualExecutor executor = new ManualExecutor();
        CompletableFuture<Void> next = world.then(() -> order.add("next"), executor);
        assertEquals(1, executor.handedOver.size(), "the next save is handed over, the chain is not stuck");
        executor.runNext();
        assertTrue(next.isDone() && !next.isCompletedExceptionally());
        assertEquals(List.of("next"), order, "the refused save never ran, the next one did");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("latch never released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
