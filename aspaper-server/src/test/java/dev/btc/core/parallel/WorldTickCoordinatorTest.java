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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    private long alive() {
        return this.created.stream().filter(Thread::isAlive).count();
    }

    @Test
    @DisplayName("T6 - what a world posts to the main thread during the phase runs after the barrier, not before")
    void main_thread_runs_no_task_while_waiting() {
        WorldTickCoordinator<String> coordinator = coordinator(true, 2);
        Queue<Runnable> mainQueue = new ConcurrentLinkedQueue<>();
        AtomicBoolean posted = new AtomicBoolean();
        AtomicBoolean ranDuringPhase = new AtomicBoolean();
        AtomicBoolean tickOver = new AtomicBoolean();
        AtomicBoolean backBeforeTickOver = new AtomicBoolean();

        assertTimeoutPreemptively(TIMEOUT, () -> {
            coordinator.tickAll(List.of("a", "b"), world -> {
                if (!world.equals("a")) return;
                mainQueue.add(() -> posted.set(true)); // a deferred teleport, say
                sleep(SLOW_TICK_MILLIS);               // the main thread has all the time it needs to misbehave
                ranDuringPhase.set(posted.get());
                tickOver.set(true);
            });
            backBeforeTickOver.set(!tickOver.get());
            for (Runnable task; (task = mainQueue.poll()) != null; ) task.run(); // the main thread, back from the barrier
        });

        assertFalse(backBeforeTickOver.get(), "the main thread came back while a world was still ticking");
        assertFalse(ranDuringPhase.get(), "the main thread ran a task while a world was still ticking");
        assertTrue(posted.get());
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

    @Test
    @DisplayName("T9 - each world ticks once per call, always on its own thread")
    void tick_events_fire_once_per_world_per_tick_on_world_thread() {
        int ticks = 5;
        List<String> worlds = List.of("a", "b", "c");
        WorldTickCoordinator<String> coordinator = coordinator(true, 3);
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        Map<String, Set<Thread>> threads = new ConcurrentHashMap<>();

        assertTimeoutPreemptively(TIMEOUT, () -> {
            for (int tick = 0; tick < ticks; tick++) {
                coordinator.tickAll(worlds, world -> {
                    calls.computeIfAbsent(world, key -> new AtomicInteger()).incrementAndGet();
                    threads.computeIfAbsent(world, key -> ConcurrentHashMap.newKeySet()).add(Thread.currentThread());
                });
            }
        });

        Set<Thread> seen = ConcurrentHashMap.newKeySet();
        for (String world : worlds) {
            assertEquals(ticks, calls.get(world).get(), world);
            assertEquals(1, threads.get(world).size(), world + " changed thread");
            Thread thread = threads.get(world).iterator().next();
            assertNotSame(Thread.currentThread(), thread);
            assertEquals(THREAD_PREFIX + world, thread.getName());
            assertTrue(seen.add(thread), world + " shares its thread");
        }
    }
}
