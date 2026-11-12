package dev.btc.core.performance;

import dev.btc.core.async.path.PathHandoff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule async pathfinding stands on: a pool thread hands a result over and nothing more; the
 * thread that owns the mob applies it, once, and only if it still asks for it.
 */
class PathHandoffTest {

    private static void onAWorker(final Runnable task) throws InterruptedException {
        Thread worker = new Thread(task, "test-worker");
        worker.start();
        worker.join();
    }

    @Test
    @DisplayName("a worker publishing a result applies nothing; the owner applies it, on its own thread")
    void publishingAppliesNothing() throws InterruptedException {
        PathHandoff<String> handoff = new PathHandoff<>();
        AtomicReference<Thread> appliedOn = new AtomicReference<>();
        AtomicReference<Thread> notifiedOn = new AtomicReference<>();
        handoff.whenApplied(() -> true, () -> notifiedOn.set(Thread.currentThread()));

        onAWorker(() -> handoff.publish("path"));

        assertFalse(handoff.isApplied(), "publishing must not apply");
        assertSame(null, notifiedOn.get(), "publishing must not run what waits for the result");

        assertTrue(handoff.applyIfPublished(result -> appliedOn.set(Thread.currentThread())));

        assertSame(Thread.currentThread(), appliedOn.get());
        assertSame(Thread.currentThread(), notifiedOn.get());
    }

    @Test
    @DisplayName("a request abandoned before it is read follows nothing up: not on the worker, not when read late, not afterwards")
    void anAbandonedRequestFollowsNothingUp() throws InterruptedException {
        PathHandoff<String> handoff = new PathHandoff<>();
        List<String> ran = new ArrayList<>();
        List<String> applied = new ArrayList<>();
        boolean[] current = {true};
        handoff.whenApplied(() -> current[0], () -> ran.add("booked before the result"));

        onAWorker(() -> handoff.publish("path")); // the search comes back while the request is still the current one
        assertEquals(List.of(), ran, "the worker must leave the follow-up to the owner");

        current[0] = false; // the navigation moved on to another path
        assertTrue(handoff.applyIfPublished(applied::add), "somebody still holding the old path may read it");
        handoff.whenApplied(() -> current[0], () -> ran.add("booked after the result"));

        assertEquals(List.of("path"), applied);
        assertEquals(List.of(), ran);
    }

    @Test
    @DisplayName("nothing is applied before a result is published")
    void nothingIsAppliedBeforeAResult() {
        PathHandoff<String> handoff = new PathHandoff<>();
        List<String> applied = new ArrayList<>();

        assertFalse(handoff.applyIfPublished(applied::add));

        assertTrue(applied.isEmpty());
        assertFalse(handoff.isApplied());
    }

    @Test
    @DisplayName("a result is applied once, and the first one published wins")
    void appliedOnceFirstWins() {
        PathHandoff<String> handoff = new PathHandoff<>();
        List<String> applied = new ArrayList<>();

        handoff.publish("first");
        handoff.publish("second");
        assertTrue(handoff.applyIfPublished(applied::add));
        assertTrue(handoff.applyIfPublished(applied::add));

        assertEquals(List.of("first"), applied);
    }

    @Test
    @DisplayName("what waited for a request that has been replaced since is not run")
    void aReplacedRequestIsNotFollowedUp() {
        PathHandoff<String> handoff = new PathHandoff<>();
        List<String> ran = new ArrayList<>();
        boolean[] current = {true};
        handoff.whenApplied(() -> current[0], () -> ran.add("stale"));
        handoff.whenApplied(() -> true, () -> ran.add("current"));

        current[0] = false; // the mob asked for another path in the meantime
        handoff.publish("path");
        handoff.applyIfPublished(result -> { });

        assertEquals(List.of("current"), ran);
    }
}
