package dev.btc.core.security;

import dev.btc.core.security.NativeAnticheatDB.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How {@link NativeAnticheatDB} wires the backlog around the schema check, without a database. */
class JournalWiringTest {

    private final List<String> warnings = new ArrayList<>();
    private final Logger log = Logger.getAnonymousLogger();
    private final List<Row> written = new ArrayList<>();
    private final List<Boolean> enabledStates = new ArrayList<>();
    private final CompletableFuture<Void> schema = new CompletableFuture<>();

    JournalWiringTest() {
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
    }

    private JournalBacklog<Row> start(int capacity) {
        JournalBacklog<Row> backlog = new JournalBacklog<>(capacity);
        NativeAnticheatDB.startJournal(backlog, () -> schema, written::add, enabledStates::add, log);
        return backlog;
    }

    private static Row row(String details) {
        return Row.of("uuid", "name", "check", details);
    }

    @Test
    void linesRaisedBeforeTheSchemaIsReadyAreWrittenWhenItIs() {
        JournalBacklog<Row> backlog = start(8);

        assertFalse(backlog.offer(row("early")), "held, the caller must not write it itself");
        assertEquals(List.of(), written);

        schema.complete(null);

        assertEquals(List.of(row("early")), written);
        assertEquals(List.of(true), enabledStates);
        assertTrue(backlog.offer(row("live")), "open: the caller writes");
    }

    @Test
    void whenTheSchemaFailsHeldLinesAreLostWithAWarningAndTheJournalStaysShut() {
        JournalBacklog<Row> backlog = start(8);
        backlog.offer(row("early"));

        schema.completeExceptionally(new IllegalStateException("no database"));

        assertEquals(List.of(), written);
        assertEquals(List.of(false), enabledStates);
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("1 line(s)"), warnings.get(0));
        assertFalse(backlog.offer(row("later")));
    }

    @Test
    void aBacklogThatOverflowsWarnsOncePerOverflowAndTheTotalIsGivenAtTheEnd() {
        int[] overflows = {0};
        JournalBacklog<Row> backlog = new JournalBacklog<>(2, () -> overflows[0]++);
        NativeAnticheatDB.startJournal(backlog, () -> schema, written::add, enabledStates::add, log);

        for (int i = 0; i < 5; i++) {
            backlog.offer(row("line " + i));
        }
        assertEquals(1, overflows[0], "one warning however many lines are dropped");

        schema.complete(null);

        assertEquals(2, written.size());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("3 journal line(s)"), warnings.get(0));
    }
}
