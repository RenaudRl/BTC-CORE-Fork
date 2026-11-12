package dev.btc.core.parallel;

import dev.btc.core.parallel.WorldQuarantine.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Test T18 of the port map, as written in {@code conception-slime-sous-pwt.md} §3.4, and its parts. */
class WorldQuarantineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final long HEALTHY_TICK_MILLIS = 20L;
    private static final int MAX_PER_MINUTE = 3;

    private final AtomicLong nanos = new AtomicLong();
    private final WorldQuarantine quarantine = new WorldQuarantine(MAX_PER_MINUTE, this.nanos::get);
    private final Throwable boom = new IllegalStateException("boom");

    private void advance(Duration time) {
        this.nanos.addAndGet(time.toNanos());
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
    @DisplayName("T18 - a world that throws is quarantined, the others finish their tick and tick on without it")
    void world_throwing_is_quarantined_and_others_finish() {
        WorldTickCoordinator<String> coordinator = new WorldTickCoordinator<>(true, 3, (world, task) -> {
            Thread thread = new Thread(task, "test world tick - " + world);
            thread.setDaemon(true);
            return thread;
        });
        try {
            Set<String> ticked = ConcurrentHashMap.newKeySet();
            List<String> worlds = List.of("a", "bad", "c");
            Map<String, Throwable> failures = assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(worlds, world -> {
                if (world.equals("bad")) throw new IllegalStateException("boom");
                sleep(HEALTHY_TICK_MILLIS); // slower than the failure: the barrier has to wait for them
                ticked.add(world);
            }));
            assertEquals(Set.of("a", "c"), ticked);
            assertEquals(Set.of("bad"), failures.keySet());

            Throwable failure = failures.get("bad");
            assertEquals(Verdict.SAVE_AND_UNLOAD, this.quarantine.decide(true, true, false, true, failure));
            assertEquals(Verdict.UNLOAD_ONLY, this.quarantine.decide(true, true, false, false, failure));
            assertEquals(Verdict.UNLOAD_ONLY, this.quarantine.decide(true, true, false, true, new StackOverflowError()));
            assertEquals(Verdict.CRASH, this.quarantine.decide(true, true, false, true, new RuntimeException(new OutOfMemoryError())));
            assertEquals(Verdict.CRASH, this.quarantine.decide(true, true, false, true, failure), "a fourth quarantine within a minute");
            advance(Duration.ofMinutes(2));
            assertEquals(Verdict.CRASH, this.quarantine.decide(true, true, true, true, failure), "the default world");
            assertEquals(Verdict.CRASH, this.quarantine.decide(true, false, false, true, failure), "not a Slime world");
            assertEquals(Verdict.CRASH, this.quarantine.decide(false, true, false, true, failure), "option off");

            coordinator.suspend("bad");
            ticked.clear();
            Map<String, Throwable> next = assertTimeoutPreemptively(TIMEOUT, () -> coordinator.tickAll(worlds, world -> {
                if (world.equals("bad")) throw new IllegalStateException("ticked again");
                ticked.add(world);
            }));
            assertEquals(Map.of(), next);
            assertEquals(Set.of("a", "c"), ticked);
        } finally {
            coordinator.shutdown();
        }
    }

    @Test
    @DisplayName("only a Slime world other than the default world can be quarantined, and only when the option is on")
    void only_a_non_default_slime_world_with_the_option_on() {
        assertEquals(Verdict.CRASH, this.quarantine.decide(false, true, false, true, this.boom), "option off");
        assertEquals(Verdict.CRASH, this.quarantine.decide(true, false, false, true, this.boom), "not a Slime world");
        assertEquals(Verdict.CRASH, this.quarantine.decide(true, true, true, true, this.boom), "the default world");
        assertEquals(Verdict.SAVE_AND_UNLOAD, this.quarantine.decide(true, true, false, true, this.boom));
    }

    @Test
    @DisplayName("save-before-unload off, or an Error anywhere in the causes: unloaded without a save")
    void no_save_when_switched_off_or_after_an_error() {
        assertEquals(Verdict.UNLOAD_ONLY, this.quarantine.decide(true, true, false, false, this.boom));
        assertEquals(Verdict.UNLOAD_ONLY,
            this.quarantine.decide(true, true, false, true, new RuntimeException(new StackOverflowError())));
    }

    @Test
    @DisplayName("a JVM-level error anywhere in the causes stops the server, even in a chain that loops")
    void a_virtual_machine_error_always_crashes() {
        IllegalStateException outer = new IllegalStateException("outer");
        IllegalStateException inner = new IllegalStateException("inner", outer);
        outer.initCause(inner);

        assertEquals(Verdict.CRASH, this.quarantine.decide(true, true, false, true, new OutOfMemoryError()));
        assertEquals(Verdict.CRASH,
            this.quarantine.decide(true, true, false, true, new RuntimeException(new IllegalStateException(new InternalError()))));
        assertEquals(Verdict.SAVE_AND_UNLOAD, this.quarantine.decide(true, true, false, true, outer), "a looping chain ends");
    }

    @Test
    @DisplayName("one quarantine too many within a minute stops the server; a minute later the count starts again")
    void the_limit_per_minute_crashes_then_recovers() {
        for (int i = 0; i < MAX_PER_MINUTE; i++) {
            assertEquals(Verdict.SAVE_AND_UNLOAD, this.quarantine.decide(true, true, false, true, this.boom));
            advance(Duration.ofSeconds(1));
        }
        assertEquals(Verdict.CRASH, this.quarantine.decide(true, true, false, true, this.boom));
        assertEquals(Verdict.CRASH, this.quarantine.decide(true, true, false, true, this.boom), "a refusal does not count as a quarantine");

        advance(Duration.ofMinutes(1));
        assertEquals(Verdict.SAVE_AND_UNLOAD, this.quarantine.decide(true, true, false, true, this.boom));
    }

    @Test
    @DisplayName("a failure that stops the server anyway does not use up the minute's allowance")
    void a_crash_verdict_is_not_counted() {
        for (int i = 0; i < MAX_PER_MINUTE; i++) {
            assertEquals(Verdict.CRASH, this.quarantine.decide(true, false, false, true, this.boom));
            assertEquals(Verdict.CRASH, this.quarantine.decide(true, true, false, true, new OutOfMemoryError()));
        }
        assertEquals(Verdict.SAVE_AND_UNLOAD, this.quarantine.decide(true, true, false, true, this.boom));
    }
}
