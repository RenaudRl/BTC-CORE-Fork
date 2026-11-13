package dev.btc.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The journal lines raised before the schema is verified are replayed, not lost (start-up op lock lines). */
class JournalBacklogTest {

    private static List<String> openAndCollect(JournalBacklog<String> backlog) {
        List<String> replayed = new ArrayList<>();
        backlog.open(replayed::add);
        return replayed;
    }

    @Test
    @DisplayName("lines offered while the schema is checked are replayed, oldest first, when it is ready")
    void linesHeldWhileCheckingAreReplayedInOrder() {
        JournalBacklog<String> backlog = new JournalBacklog<>(8);
        backlog.hold();

        assertFalse(backlog.offer("first"));
        assertFalse(backlog.offer("second"));

        assertEquals(List.of("first", "second"), openAndCollect(backlog));
    }

    @Test
    @DisplayName("once open, the caller writes the line itself and nothing is held")
    void onceOpenTheCallerWrites() {
        JournalBacklog<String> backlog = new JournalBacklog<>(8);
        backlog.hold();
        openAndCollect(backlog);

        assertTrue(backlog.offer("live"));
    }

    @Test
    @DisplayName("a line is replayed once, not again at a second open")
    void aLineIsReplayedOnce() {
        JournalBacklog<String> backlog = new JournalBacklog<>(8);
        backlog.hold();
        backlog.offer("only");
        openAndCollect(backlog);

        assertEquals(List.of(), openAndCollect(backlog));
    }

    @Test
    @DisplayName("the backlog is bounded: the oldest lines are kept, the newest dropped and counted")
    void theBacklogIsBounded() {
        JournalBacklog<String> backlog = new JournalBacklog<>(2);
        backlog.hold();

        backlog.offer("a");
        backlog.offer("b");
        backlog.offer("c");
        backlog.offer("d");

        assertEquals(2, backlog.dropped());
        assertEquals(List.of("a", "b"), openAndCollect(backlog));
    }

    @Test
    @DisplayName("the first dropped line, and only it, raises the overflow callback; a closed backlog never does")
    void theFirstOverflowIsSignalledOnce() {
        int[] signals = {0};
        JournalBacklog<String> closed = new JournalBacklog<>(1, () -> signals[0]++);
        closed.offer("a");
        closed.offer("b");
        assertEquals(0, signals[0]);

        JournalBacklog<String> backlog = new JournalBacklog<>(1, () -> signals[0]++);
        backlog.hold();
        backlog.offer("a");
        assertEquals(0, signals[0], "not full yet");
        backlog.offer("b");
        backlog.offer("c");

        assertEquals(1, signals[0]);
    }

    @Test
    @DisplayName("with no schema check launched (storage off), a line is dropped, not accumulated")
    void withoutACheckLinesAreDropped() {
        JournalBacklog<String> backlog = new JournalBacklog<>(8);

        assertFalse(backlog.offer("lost"));

        assertEquals(List.of(), openAndCollect(backlog));
    }

    @Test
    @DisplayName("when the schema fails, what was held is forgotten and counted, and later lines are dropped")
    void whenTheSchemaFailsHeldLinesAreForgotten() {
        JournalBacklog<String> backlog = new JournalBacklog<>(8);
        backlog.hold();
        backlog.offer("held");

        assertEquals(1, backlog.giveUp());

        assertFalse(backlog.offer("later"));
        assertEquals(List.of(), openAndCollect(backlog));
    }
}
