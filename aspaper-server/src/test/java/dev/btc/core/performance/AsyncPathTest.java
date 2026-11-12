package dev.btc.core.performance;

import dev.btc.core.async.path.AsyncPath;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real path object: the pool searches, the path stays empty until its owner asks, and an owner
 * that cannot wait searches itself.
 */
class AsyncPathTest {

    private static final BlockPos TARGET = new BlockPos(1, 2, 3);

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
}
