package dev.btc.core.performance;

import dev.btc.core.async.path.AsyncPath;
import dev.btc.core.async.path.AsyncPathProcessor;
import dev.btc.core.config.BTCCoreConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real path object: the pool searches, the path stays empty until its owner asks, and an owner
 * that cannot wait searches itself.
 */
class AsyncPathTest {

    private static final BlockPos TARGET = new BlockPos(1, 2, 3);
    /** What AsyncPathProcessor queues per thread before it has the caller search. */
    private static final int QUEUE_OF_ONE_THREAD = 256;

    private static Path found() {
        return new Path(List.of(new Node(1, 2, 3)), TARGET, true);
    }

    @Test
    @DisplayName("a path searched on the pool stays empty until its owner asks for it")
    void thePoolDoesNotApply() throws InterruptedException {
        PathFinder finder = new PathFinder(new WalkNodeEvaluator(), 16);
        AtomicReference<Thread> searchedOn = new AtomicReference<>();
        CountDownLatch searching = new CountDownLatch(1);

        AsyncPath path = new AsyncPath(finder, new ArrayList<>(), Set.of(TARGET), ignored -> {
            searchedOn.set(Thread.currentThread());
            searching.countDown();
            return found();
        });
        assertTrue(searching.await(10, TimeUnit.SECONDS), "the pool never ran the search");
        synchronized (finder) {
            // the search holds this monitor until it has handed its result over
        }

        assertNotSame(Thread.currentThread(), searchedOn.get());
        assertTrue(path.nodes.isEmpty(), "the pool thread must not copy the result into the path");

        assertTrue(path.isProcessed());
        assertEquals(1, path.nodes.size());
    }

    @Test
    @DisplayName("an owner that cannot wait searches on its own thread")
    void theOwnerSearchesWhenItCannotWait() {
        PathFinder finder = new PathFinder(new WalkNodeEvaluator(), 16);
        List<Thread> searchedOn = new ArrayList<>();
        AsyncPath path;

        synchronized (finder) { // keeps the pool out, as a busy pool would
            path = new AsyncPath(finder, new ArrayList<>(), Set.of(TARGET), ignored -> {
                synchronized (searchedOn) {
                    searchedOn.add(Thread.currentThread());
                }
                return found();
            });
            assertEquals(1, path.getNodeCount());
        }
        synchronized (finder) {
            // lets the queued task through: it finds the result already there
        }

        synchronized (searchedOn) {
            assertEquals(1, searchedOn.size());
            assertSame(Thread.currentThread(), searchedOn.get(0));
        }
    }

    @Test
    @DisplayName("a search that throws leaves an empty, unreachable path instead of a mob waiting for ever")
    void aFailedSearchPublishesAnEmptyPath() throws InterruptedException {
        PathFinder finder = new PathFinder(new WalkNodeEvaluator(), 16);
        CountDownLatch searching = new CountDownLatch(1);

        AsyncPath path = new AsyncPath(finder, new ArrayList<>(), Set.of(TARGET), ignored -> {
            searching.countDown();
            throw new IllegalStateException("boom (expected by the test)");
        });
        assertTrue(searching.await(10, TimeUnit.SECONDS), "the pool never ran the search");
        synchronized (finder) {
            // the search holds this monitor until it has handed its result over
        }

        assertTrue(path.isProcessed(), "the failure must still hand a result over");
        assertEquals(0, path.getNodeCount());
        assertFalse(path.canReach());
        assertEquals(TARGET, path.getTarget());
    }

    @Test
    @DisplayName("a full pool has the requesting thread search at once")
    void aFullPoolSearchesOnTheCaller() throws InterruptedException {
        final int configured = BTCCoreConfig.asyncPathfindingThreads;
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch workerBusy = new CountDownLatch(1);
        AsyncPathProcessor.shutdown(); // the next request starts a pool of the size set below
        BTCCoreConfig.asyncPathfindingThreads = 1;
        try {
            new AsyncPath(new PathFinder(new WalkNodeEvaluator(), 16), new ArrayList<>(), Set.of(TARGET), ignored -> {
                workerBusy.countDown();
                await(release);
                return found();
            });
            assertTrue(workerBusy.await(10, TimeUnit.SECONDS), "the pool never ran the search");
            for (int i = 0; i < QUEUE_OF_ONE_THREAD; i++) {
                new AsyncPath(new PathFinder(new WalkNodeEvaluator(), 16), new ArrayList<>(), Set.of(TARGET), ignored -> found());
            }

            AtomicReference<Thread> searchedOn = new AtomicReference<>();
            new AsyncPath(new PathFinder(new WalkNodeEvaluator(), 16), new ArrayList<>(), Set.of(TARGET), ignored -> {
                searchedOn.set(Thread.currentThread());
                return found();
            });

            assertSame(Thread.currentThread(), searchedOn.get(), "one request too many must be searched by its caller, before the request returns");
        } finally {
            release.countDown();
            BTCCoreConfig.asyncPathfindingThreads = configured;
            AsyncPathProcessor.shutdown();
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
