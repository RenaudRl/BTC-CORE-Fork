package dev.btc.core.parallel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A clone made off the main thread waits for the main thread to copy the world. */
class MainThreadHandOffTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final long SLICE_MILLIS = 5L;

    @Test
    @DisplayName("what the main thread did is handed back, and nothing is reported")
    void result_of_the_main_thread_is_returned() {
        List<Long> reported = new ArrayList<>();
        String copy = MainThreadHandOff.await(CompletableFuture.completedFuture("copy"), SLICE_MILLIS, () -> false, reported::add, "copy");
        assertEquals("copy", copy);
        assertEquals(List.of(), reported);
    }

    @Test
    @DisplayName("a server that begins to stop after the hand-over ends the wait with an error instead of leaving the thread blocked")
    void stop_begun_while_waiting_ends_the_wait() {
        AtomicInteger looks = new AtomicInteger();
        IllegalStateException refused = assertTimeoutPreemptively(TIMEOUT, () -> assertThrows(IllegalStateException.class,
            () -> MainThreadHandOff.await(new CompletableFuture<>(), SLICE_MILLIS, () -> looks.incrementAndGet() >= 3, waited -> { },
                "copy world dungeon_1")));
        assertEquals(3, looks.get()); // looked at after each slice, not once
        assertTrue(refused.getMessage().contains("copy world dungeon_1"), refused.getMessage());
    }

    @Test
    @DisplayName("a main thread that is late is reported once, with the time waited")
    void late_main_thread_is_reported_once() {
        List<Long> reported = new ArrayList<>();
        AtomicInteger looks = new AtomicInteger();
        assertTimeoutPreemptively(TIMEOUT, () -> assertThrows(IllegalStateException.class,
            () -> MainThreadHandOff.await(new CompletableFuture<>(), SLICE_MILLIS,
                () -> looks.incrementAndGet() >= MainThreadHandOff.REPORT_AFTER_SLICES * 3, reported::add, "copy")));
        assertEquals(List.of(MainThreadHandOff.REPORT_AFTER_SLICES * SLICE_MILLIS), reported);
    }

    @Test
    @DisplayName("what the copy threw on the main thread is thrown to the caller as it is")
    void failure_of_the_main_thread_is_rethrown() {
        IllegalArgumentException thrown = new IllegalArgumentException("broken chunk");
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(thrown);
        assertSame(thrown, assertThrows(IllegalArgumentException.class,
            () -> MainThreadHandOff.await(failed, SLICE_MILLIS, () -> false, waited -> { }, "copy")));
    }

    @Test
    @DisplayName("a caller that left because the server stops is no longer served: the main thread does not copy the world for nobody")
    void work_of_a_caller_that_left_on_stop_is_skipped() {
        CompletableFuture<String> handed = new CompletableFuture<>();
        assertTimeoutPreemptively(TIMEOUT, () -> assertThrows(IllegalStateException.class,
            () -> MainThreadHandOff.await(handed, SLICE_MILLIS, () -> true, waited -> { }, "copy")));

        AtomicInteger copies = new AtomicInteger();
        MainThreadHandOff.fulfil(handed, () -> "copy " + copies.incrementAndGet());
        assertEquals(0, copies.get());
    }

    @Test
    @DisplayName("an interrupted caller is no longer served either, and keeps its interruption")
    void work_of_an_interrupted_caller_is_skipped() {
        CompletableFuture<String> handed = new CompletableFuture<>();
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> MainThreadHandOff.await(handed, SLICE_MILLIS, () -> false, waited -> { }, "copy"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // not left to the next test
        }

        AtomicInteger copies = new AtomicInteger();
        MainThreadHandOff.fulfil(handed, () -> "copy " + copies.incrementAndGet());
        assertEquals(0, copies.get());
    }

    @Test
    @DisplayName("the task of the main thread hands over what the work gave, or what it threw")
    void task_hands_over_the_result_or_the_failure() {
        CompletableFuture<String> done = new CompletableFuture<>();
        MainThreadHandOff.fulfil(done, () -> "copy");
        assertEquals("copy", done.join());

        IllegalArgumentException thrown = new IllegalArgumentException("broken chunk");
        CompletableFuture<String> failed = new CompletableFuture<>();
        MainThreadHandOff.fulfil(failed, () -> { throw thrown; });
        assertSame(thrown, assertThrows(IllegalArgumentException.class,
            () -> MainThreadHandOff.await(failed, SLICE_MILLIS, () -> false, waited -> { }, "copy")));
    }
}
