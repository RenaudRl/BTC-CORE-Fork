package dev.btc.core.integrity.chat;

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatJournalLogTest {

    private static LoggerContext started() {
        LoggerContext context = new LoggerContext("chat-journal-test");
        context.start(new DefaultConfiguration());
        return context;
    }

    @Test
    @DisplayName("the journal logger writes to its own file and not to the main log")
    void journalHasItsOwnFileAndIsNotAdditive(@TempDir Path folder) throws IOException {
        LoggerContext context = started();
        try {
            assertTrue(ChatJournalLog.install(context, folder, 90));
            context.getLogger(ChatJournalLog.LOGGER_NAME).info("ts=1 uuid=x rule=advertising");
            context.getLogger("some.other.logger").info("not the journal");
            LoggerConfig config = context.getConfiguration().getLoggerConfig(ChatJournalLog.LOGGER_NAME);
            assertEquals(ChatJournalLog.LOGGER_NAME, config.getName());
            assertFalse(config.isAdditive(), "what is journalled must not reach the main log through the root logger");
        } finally {
            context.stop();
        }
        String written = Files.readString(folder.resolve("latest.log"));
        assertTrue(written.contains("rule=advertising"), written);
        assertFalse(written.contains("not the journal"), written);
    }

    @Test
    @DisplayName("a rollover deletes rolled files older than the retention and keeps the recent ones")
    void rolloverDeletesPastRetention(@TempDir Path folder) throws IOException {
        Path old = Files.createFile(folder.resolve("2026-01-01-1.log.gz"));
        Path recent = Files.createFile(folder.resolve("2026-02-01-1.log.gz"));
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(100))));
        Files.setLastModifiedTime(recent, FileTime.from(Instant.now().minus(Duration.ofDays(10))));
        LoggerContext context = started();
        try {
            assertTrue(ChatJournalLog.install(context, folder, 90));
            context.getLogger(ChatJournalLog.LOGGER_NAME).info("a line to roll");
            RollingFileAppender appender = context.getConfiguration().getAppender(ChatJournalLog.APPENDER_NAME);
            appender.getManager().rollover();
        } finally {
            context.stop();
        }
        assertFalse(Files.exists(old), "older than 90 days");
        assertTrue(Files.exists(recent), "10 days old");
    }

    @Test
    @DisplayName("a logger the admin already routed in log4j2.xml is left alone")
    void adminRoutingWins(@TempDir Path folder) throws IOException {
        LoggerContext context = started();
        try {
            assertTrue(ChatJournalLog.install(context, folder, 90));
            assertFalse(ChatJournalLog.install(context, folder, 90), "the second install finds the logger configured");
        } finally {
            context.stop();
        }
    }
}
