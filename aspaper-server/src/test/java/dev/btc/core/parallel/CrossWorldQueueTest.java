package dev.btc.core.parallel;

import dev.btc.core.parallel.CrossWorldQueue.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The journeys between two worlds under {@code parallel-world-ticking}: tests T12 and T13 of the
 * port map ({@code pwt-carte-conflits.md}, 5).
 */
class CrossWorldQueueTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int TRAVELLERS = 50;

    /** A world as the server guards it: only the thread ticking it, or the main one, may touch its residents. */
    private static final class World {
        final String name;
        final Set<Integer> residents = new HashSet<>(); // not thread safe, on purpose
        volatile Thread ticking;
        volatile Thread main;

        World(String name) {
            this.name = name;
        }

        Set<Integer> residents() {
            Thread current = Thread.currentThread();
            if (current != this.ticking && current != this.main) {
                throw new IllegalStateException("world " + this.name + " touched from " + current.getName());
            }
            return this.residents;
        }
    }

    @Test
    @DisplayName("T12 - fifty travellers cross between two worlds at the same tick, and back at the next: every journey is made, by the main thread, without a world thread touching the other world")
    void fifty_round_trips_complete_without_deadlock() {
        World a = new World("a");
        World b = new World("b");
        for (int id = 0; id < TRAVELLERS; id++) (id % 2 == 0 ? a : b).residents.add(id);
        int[] journeys = new int[TRAVELLERS];
        CrossWorldQueue queue = new CrossWorldQueue();
        WorldTickCoordinator<World> coordinator = new WorldTickCoordinator<>(true, 2, (world, task) -> {
            Thread thread = new Thread(task, "test world tick - " + world.name);
            thread.setDaemon(true);
            return thread;
        });

        try {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                a.main = b.main = Thread.currentThread();
                for (int tick = 0; tick < 2; tick++) {
                    Map<World, Throwable> failures = coordinator.tickAll(List.of(a, b), world -> {
                        world.ticking = Thread.currentThread();
                        World other = world == a ? b : a;
                        for (int id : List.copyOf(world.residents())) {
                            Runnable journey = () -> {
                                world.residents().remove(id);
                                other.residents().add(id);
                                journeys[id]++;
                            };
                            if (CrossWorldQueue.route(true, true, false) == Route.DEFERRED) queue.defer(journey);
                            else journey.run();
                        }
                    });
                    assertTrue(failures.isEmpty(), () -> "a world thread reached into the other world: " + failures);
                    List<Exception> thrown = new ArrayList<>();
                    assertEquals(TRAVELLERS, queue.drain(thrown::add));
                    assertTrue(thrown.isEmpty(), thrown::toString);
                }
            });
        } finally {
            coordinator.shutdown();
        }

        for (int id = 0; id < TRAVELLERS; id++) {
            assertEquals(2, journeys[id], "traveller " + id + " went and came back");
            assertTrue((id % 2 == 0 ? a : b).residents.contains(id), "traveller " + id + " is home");
        }
        assertEquals(TRAVELLERS, a.residents.size() + b.residents.size());
    }

    @Test
    @DisplayName("T13 - a journey to another world asked on the thread of a world is put off, not thrown: it runs when the main thread drains the queue, on that thread")
    void cross_world_teleport_from_world_thread_is_deferred_not_thrown() throws InterruptedException {
        assertEquals(Route.DEFERRED, CrossWorldQueue.route(true, true, false));
        assertEquals(Route.INLINE, CrossWorldQueue.route(true, true, true), "inside the world it ticks, a world thread acts at once");
        assertEquals(Route.INLINE, CrossWorldQueue.route(true, false, false), "the main thread owns every world");
        assertEquals(Route.INLINE, CrossWorldQueue.route(false, true, false), "option off: as before");

        CrossWorldQueue queue = new CrossWorldQueue();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        AtomicReference<Throwable> thrownWhereAsked = new AtomicReference<>();
        Thread worldThread = new Thread(() -> {
            try {
                queue.defer(() -> ranOn.set(Thread.currentThread()));
            } catch (Throwable thrown) {
                thrownWhereAsked.set(thrown);
            }
        });
        worldThread.start();
        worldThread.join();

        assertNull(thrownWhereAsked.get());
        assertNull(ranOn.get(), "nothing runs where it was asked");
        assertEquals(1, queue.drain(failure -> { }));
        assertSame(Thread.currentThread(), ranOn.get());
        assertEquals(0, queue.drain(failure -> { }), "a journey is made once");
    }

    @Test
    @DisplayName("a deferred task that throws is reported and the ones after it still run, with what a task defers in turn")
    void a_failing_task_is_reported_and_the_rest_still_runs() {
        CrossWorldQueue queue = new CrossWorldQueue();
        List<String> ran = new ArrayList<>();
        List<Exception> reported = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("second");
        queue.defer(() -> ran.add("first"));
        queue.defer(() -> {
            throw failure;
        });
        queue.defer(() -> {
            ran.add("third");
            queue.defer(() -> ran.add("asked by the third"));
        });

        assertEquals(4, queue.drain(reported::add));

        assertEquals(List.of("first", "third", "asked by the third"), ran);
        assertEquals(List.of(failure), reported);
    }

    @Test
    @DisplayName("a call site is new once: the warning about it is logged once, however often it runs")
    void a_site_is_new_only_once() {
        CrossWorldQueue queue = new CrossWorldQueue();

        assertTrue(queue.firstTime("Dungeon (Party.kt:42)"));
        assertFalse(queue.firstTime("Dungeon (Party.kt:42)"));
        assertTrue(queue.firstTime("Dungeon (Party.kt:57)"));
    }

    @Test
    @DisplayName("a journey is given up when, by the time the main thread gets to it, the entity is gone or the target world was unloaded or quarantined")
    void a_journey_whose_traveller_or_target_is_gone_is_given_up() {
        assertNull(CrossWorldQueue.staleReason(false, false, false));
        assertNotNull(CrossWorldQueue.staleReason(true, false, false));
        assertNotNull(CrossWorldQueue.staleReason(false, true, false));
        assertNotNull(CrossWorldQueue.staleReason(false, false, true));
    }
}
