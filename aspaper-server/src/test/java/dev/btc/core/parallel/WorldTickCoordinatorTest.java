package dev.btc.core.parallel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduling of {@code parallel-world-ticking}, on plain strings instead of worlds: tests T1 to
 * T6 and T9 of the port map ({@code pwt-carte-conflits.md} §5).
 */
class WorldTickCoordinatorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final long SLOW_TICK_MILLIS = 150L;
    private static final long SHORT_TICK_MILLIS = 20L;
    private static final long MEET_SECONDS = 5L;
    private static final String THREAD_PREFIX = "test world tick - ";

    private final List<Thread> created = new CopyOnWriteArrayList<>();
    private final List<WorldTickCoordinator<String>> coordinators = new ArrayList<>();

    @AfterEach
    void stopThreads() {
        this.coordinators.forEach(WorldTickCoordinator::shutdown);
    }

    private WorldTickCoordinator<String> coordinator(boolean parallel, int permits) {
        WorldTickCoordinator<String> coordinator = new WorldTickCoordinator<>(parallel, permits, (world, task) -> {
            Thread thread = new Thread(task, THREAD_PREFIX + world);
            thread.setDaemon(true);
            this.created.add(thread);
            return thread;
        });
        this.coordinators.add(coordinator);
        return coordinator;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @Test
    @DisplayName("T1 - tickAll returns only once the slowest world is done")
    void barrier_waits_for_every_world() {
        WorldTickCoordinator<String> coordinator = coordinator(true, 3);
        Set<String> done = ConcurrentHashMap.newKeySet();

        assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(List.of("a", "slow", "c"), world -> {
            if (world.equals("slow")) sleep(SLOW_TICK_MILLIS);
            done.add(world);
        }));

        assertEquals(Set.of("a", "slow", "c"), done);
    }

    @Test
    @DisplayName("T2 - never more worlds ticking at once than there are permits")
    void concurrency_never_exceeds_permits() {
        int permits = 2;
        WorldTickCoordinator<String> coordinator = coordinator(true, permits);
        AtomicInteger ticking = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();

        assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(List.of("a", "b", "c", "d", "e", "f"), world -> {
            most.accumulateAndGet(ticking.incrementAndGet(), Math::max);
            sleep(SHORT_TICK_MILLIS);
            ticking.decrementAndGet();
        }));

        assertTrue(most.get() <= permits, "worlds ticking at once: " + most.get());
    }

    @Test
    @DisplayName("two worlds really tick at the same time when two permits allow it")
    void two_worlds_tick_at_the_same_time() {
        WorldTickCoordinator<String> coordinator = coordinator(true, 2);
        CyclicBarrier bothInside = new CyclicBarrier(2);

        Map<String, Throwable> failures = assertTimeoutPreemptively(TIMEOUT, () ->
            coordinator.tickAll(List.of("a", "b"), world -> {
                try {
                    bothInside.await(MEET_SECONDS, TimeUnit.SECONDS); // passes only with the other world inside its tick
                } catch (Exception alone) {
                    throw new IllegalStateException(world + " ticked alone", alone);
                }
            }));

        assertEquals(Map.of(), failures);
    }

    @Test
    @DisplayName("T3 - switched off: every world on the calling thread, in order, and no thread created")
    void disabled_runs_sequentially_on_caller_thread_in_order() {
        WorldTickCoordinator<String> coordinator = coordinator(false, 4);
        List<String> order = new ArrayList<>();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();

        coordinator.tickAll(List.of("a", "b", "c"), world -> {
            order.add(world);
            threads.add(Thread.currentThread());
        });

        assertEquals(List.of("a", "b", "c"), order);
        assertEquals(Set.of(Thread.currentThread()), threads);
        assertTrue(this.created.isEmpty(), "threads created: " + this.created);
    }

    @Test
    @DisplayName("T4 - a world added while the phase runs is first ticked by the next call")
    void world_added_during_phase_is_first_ticked_next_tick() {
        WorldTickCoordinator<String> coordinator = coordinator(true, 1); // one permit: "a" is done before "b" is handed over
        List<String> worlds = new ArrayList<>(List.of("a", "b"));
        Queue<String> ticked = new ConcurrentLinkedQueue<>();

        Map<String, Throwable> failures = assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(worlds, world -> {
            if (world.equals("a")) {
                synchronized (worlds) {
                    worlds.add("late");
                }
            }
            ticked.add(world);
        }));

        assertEquals(Map.of(), failures);
        assertEquals(Set.of("a", "b"), Set.copyOf(ticked));

        ticked.clear();
        assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(List.of("a", "b", "late"), ticked::add));
        assertEquals(Set.of("a", "b", "late"), Set.copyOf(ticked));
    }

    @Test
    @DisplayName("T5 - a hundred worlds ticked then released leave no thread behind")
    void removed_world_releases_its_thread() {
        int cycles = 100;
        WorldTickCoordinator<String> coordinator = coordinator(true, 2);

        assertTimeoutPreemptively(TIMEOUT, () -> {
            for (int cycle = 0; cycle < cycles; cycle++) {
                String world = "instance_" + cycle;
                coordinator.tickAll(List.of("hub", world), ignored -> { });
                coordinator.release(world);
            }
        });

        assertEquals(cycles + 1, this.created.size(), "one thread per world that ticked");
        long deadline = System.nanoTime() + TIMEOUT.toNanos() / 2;
        while (alive() > 1 && System.nanoTime() < deadline) sleep(SHORT_TICK_MILLIS);
        assertEquals(1, alive(), "only the hub, never released, keeps its thread");
    }

    @Test
    @DisplayName("a world released when no phase ever ran is not remembered: with the options off nothing ticks through here, and every unloaded world would stay referenced until the server stops")
    void release_without_any_phase_retains_nothing() {
        WorldTickCoordinator<String> coordinator = coordinator(false, 1);

        coordinator.release("instance_1");

        assertFalse(coordinator.isReleased("instance_1"));
    }

    @Test
    @DisplayName("T5 - shutdown stops the thread of every world, released or not")
    void shutdown_stops_every_world_thread() {
        WorldTickCoordinator<String> coordinator = coordinator(true, 2);
        assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(List.of("hub", "mine", "end"), ignored -> { }));
        assertEquals(3, alive(), "each world keeps its thread between two ticks");

        coordinator.shutdown();

        long deadline = System.nanoTime() + TIMEOUT.toNanos() / 2;
        while (alive() > 0 && System.nanoTime() < deadline) sleep(SHORT_TICK_MILLIS);
        assertEquals(0, alive());
    }

    private long alive() {
        return this.created.stream().filter(Thread::isAlive).count();
    }

    @Test
    @DisplayName("T6 - what a world posts to the main thread during the phase runs once no world ticks any more")
    void main_thread_runs_no_task_while_waiting() throws Exception {
        WorldTickCoordinator<String> coordinator = coordinator(true, 2);
        ExecutorService main = Executors.newSingleThreadExecutor(); // the main thread and its task queue
        AtomicInteger ticking = new AtomicInteger();
        AtomicInteger tickingWhenTheTaskRan = new AtomicInteger(-1);
        CountDownLatch taskRan = new CountDownLatch(1);
        try {
            Future<?> phase = main.submit(() -> coordinator.tickAll(List.of("a", "b"), world -> {
                ticking.incrementAndGet();
                if (world.equals("a")) {
                    main.execute(() -> { // a deferred teleport, say
                        tickingWhenTheTaskRan.set(ticking.get());
                        taskRan.countDown();
                    });
                    sleep(SLOW_TICK_MILLIS); // the main thread has all the time it needs to misbehave
                }
                ticking.decrementAndGet();
            }));
            phase.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertTrue(taskRan.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "the posted task never ran");
        } finally {
            main.shutdownNow();
        }

        assertEquals(0, tickingWhenTheTaskRan.get(), "worlds still ticking when the main thread ran the task");
    }
    @Test
    @DisplayName("a world that throws is reported with what it threw, and the others still tick - one after the other too")
    void a_failing_world_is_reported_and_the_others_still_tick() {
        for (boolean parallel : new boolean[] {true, false}) {
            WorldTickCoordinator<String> coordinator = coordinator(parallel, 2);
            Set<String> ticked = ConcurrentHashMap.newKeySet();
            Error overflow = new StackOverflowError();

            Map<String, Throwable> failures = assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(List.of("bad", "a", "worse", "b"), world -> {
                if (world.equals("bad")) throw new IllegalStateException("boom");
                if (world.equals("worse")) throw overflow;
                ticked.add(world);
            }));

            assertEquals(Set.of("a", "b"), ticked, "parallel=" + parallel);
            assertEquals(List.of("bad", "worse"), List.copyOf(failures.keySet()), "in the order given, parallel=" + parallel);
            assertEquals("boom", failures.get("bad").getMessage());
            assertSame(overflow, failures.get("worse"));
        }
    }

    @Test
    @DisplayName("a world that cannot be handed to its thread is reported with what was thrown, its permit goes back and the others tick")
    void a_world_that_cannot_be_handed_over_is_reported_and_its_permit_goes_back() {
        Error noThread = new OutOfMemoryError("unable to create native thread");
        WorldTickCoordinator<String> coordinator = new WorldTickCoordinator<>(true, 1, (world, task) -> {
            if (world.equals("starved")) throw noThread;
            Thread thread = new Thread(task, THREAD_PREFIX + world);
            thread.setDaemon(true);
            return thread;
        });
        this.coordinators.add(coordinator);
        Queue<String> ticked = new ConcurrentLinkedQueue<>();

        // One permit: "a" is handed over, and the barrier passed, only if the permit of "starved" came back.
        Map<String, Throwable> failures = assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(List.of("starved", "a"), ticked::add));

        assertEquals(List.of("a"), List.copyOf(ticked));
        assertEquals(Set.of("starved"), failures.keySet());
        assertSame(noThread, failures.get("starved"));
    }

    @Test
    @DisplayName("a world released by another world before its turn is not ticked, gets no thread and is not a failure")
    void a_world_released_before_its_turn_is_left_out() {
        WorldTickCoordinator<String> coordinator = coordinator(true, 1); // one permit: "a" is done before "gone" is handed over
        Queue<String> ticked = new ConcurrentLinkedQueue<>();

        Map<String, Throwable> failures = assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(List.of("a", "gone"), world -> {
            if (world.equals("a")) coordinator.release("gone"); // a plugin unloading another world from its tick
            ticked.add(world);
        }));

        assertEquals(List.of("a"), List.copyOf(ticked));
        assertEquals(Map.of(), failures);
        assertEquals(1, this.created.size(), "threads created: " + this.created);
        assertTrue(coordinator.isReleased("gone"));
        assertFalse(coordinator.isReleased("a"));
    }

    @Test
    @DisplayName("a world released while it is handed to its thread is gone, not failed")
    void a_world_released_while_handed_over_is_not_a_failure() {
        List<WorldTickCoordinator<String>> self = new ArrayList<>();
        WorldTickCoordinator<String> coordinator = new WorldTickCoordinator<>(true, 1, (world, task) -> {
            if (world.equals("gone")) {
                self.get(0).release("gone"); // the unload lands between the look-up of its executor and the hand-over
                return null;                 // what a shut-down executor ends in: nothing runs
            }
            Thread thread = new Thread(task, THREAD_PREFIX + world);
            thread.setDaemon(true);
            return thread;
        });
        self.add(coordinator);
        this.coordinators.add(coordinator);
        Queue<String> ticked = new ConcurrentLinkedQueue<>();

        Map<String, Throwable> failures = assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(List.of("gone", "a"), ticked::add));

        assertEquals(List.of("a"), List.copyOf(ticked));
        assertEquals(Map.of(), failures);
    }

    @Test
    @DisplayName("what a world released while it ticked threw is handed back, flagged as released")
    void a_failure_of_a_world_released_while_it_ticked_is_flagged() {
        WorldTickCoordinator<String> coordinator = coordinator(true, 2);
        CyclicBarrier bothInside = new CyclicBarrier(2);

        Map<String, Throwable> failures = assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(List.of("a", "leaving"), world -> {
            try {
                bothInside.await(MEET_SECONDS, TimeUnit.SECONDS);
            } catch (Exception alone) {
                throw new IllegalStateException(world + " ticked alone", alone);
            }
            if (world.equals("a")) coordinator.release("leaving");
            else throw new IllegalStateException("closed under my feet");
        }));

        assertEquals(Set.of("leaving"), failures.keySet());
        assertEquals("closed under my feet", failures.get("leaving").getMessage());
        assertTrue(coordinator.isReleased("leaving"), "still known as released once the phase is over");
    }

    @Test
    @DisplayName("suspending a world already released keeps nothing of it: a world of that name ticks again")
    void suspend_after_release_keeps_nothing() {
        WorldTickCoordinator<String> coordinator = coordinator(true, 1);
        List<String> worlds = List.of("a", "gone");
        Queue<String> ticked = new ConcurrentLinkedQueue<>();

        assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(worlds, world -> {
            if (world.equals("a")) coordinator.release("gone");
        }));
        coordinator.suspend("gone"); // what a quarantine would do with a world that is no longer there

        assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(worlds, ticked::add));
        assertEquals(Set.of("a", "gone"), Set.copyOf(ticked));
    }

    @Test
    @DisplayName("a world tells whether it is suspended, until it is released")
    void a_world_tells_whether_it_is_suspended() {
        WorldTickCoordinator<String> coordinator = coordinator(false, 1);
        coordinator.tickAll(List.of("a", "b"), ignored -> { });
        assertFalse(coordinator.isSuspended("b"));

        coordinator.suspend("b");
        assertTrue(coordinator.isSuspended("b"));
        assertFalse(coordinator.isSuspended("a"));

        coordinator.release("b");
        assertFalse(coordinator.isSuspended("b"));
    }

    @Test
    @DisplayName("a suspended world is left out of every tick until it is released")
    void a_suspended_world_is_skipped_until_released() {
        WorldTickCoordinator<String> coordinator = coordinator(true, 2);
        List<String> worlds = List.of("a", "bad");
        Queue<String> ticked = new ConcurrentLinkedQueue<>();

        coordinator.suspend("bad");
        assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(worlds, ticked::add));
        assertEquals(List.of("a"), List.copyOf(ticked));

        coordinator.release("bad"); // unloaded: a world of that name loaded later ticks again
        ticked.clear();
        assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(worlds, ticked::add));
        assertEquals(Set.of("a", "bad"), Set.copyOf(ticked));
    }

    private record Fired(String event, String world, int tick, Thread thread) {
    }

    @Test
    @DisplayName("T9 - the tick events fire once per world per tick, start then end, on the thread of the world; no end after a tick that threw")
    void tick_events_fire_once_per_world_per_tick_on_world_thread() {
        int ticks = 5;
        int failingTick = 2;
        List<String> worlds = List.of("a", "b", "c");
        WorldTickCoordinator<String> coordinator = coordinator(true, 3);
        Queue<Fired> listener = new ConcurrentLinkedQueue<>();

        assertTimeoutPreemptively(TIMEOUT, () -> {
            for (int tick = 0; tick < ticks; tick++) {
                int now = tick;
                coordinator.tickAll(worlds, world -> WorldTickSteps.run( // the turn of a world, as WorldTickLoop runs it
                    () -> listener.add(new Fired("start", world, now, Thread.currentThread())),
                    () -> {
                        if (world.equals("b") && now == failingTick) throw new IllegalStateException("boom");
                    },
                    () -> listener.add(new Fired("end", world, now, Thread.currentThread()))));
            }
        });

        Set<Thread> seen = ConcurrentHashMap.newKeySet();
        for (String world : worlds) {
            List<Fired> fired = listener.stream().filter(event -> event.world().equals(world)).toList();
            List<String> expected = new ArrayList<>();
            for (int tick = 0; tick < ticks; tick++) {
                expected.add("start " + tick);
                if (!world.equals("b") || tick != failingTick) expected.add("end " + tick);
            }
            assertEquals(expected, fired.stream().map(event -> event.event() + " " + event.tick()).toList(), world);

            Set<Thread> threads = fired.stream().map(Fired::thread).collect(Collectors.toSet());
            assertEquals(1, threads.size(), world + " changed thread");
            Thread thread = threads.iterator().next();
            assertNotSame(Thread.currentThread(), thread);
            assertEquals(THREAD_PREFIX + world, thread.getName());
            assertTrue(seen.add(thread), world + " shares its thread");
        }
    }
}
