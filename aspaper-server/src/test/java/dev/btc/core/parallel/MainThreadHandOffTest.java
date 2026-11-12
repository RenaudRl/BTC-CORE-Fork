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
}
