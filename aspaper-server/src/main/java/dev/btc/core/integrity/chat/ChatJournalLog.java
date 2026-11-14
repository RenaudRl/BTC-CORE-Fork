package dev.btc.core.integrity.chat;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.CompositeTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.OnStartupTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.TimeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.action.Action;
import org.apache.logging.log4j.core.appender.rolling.action.DeleteAction;
import org.apache.logging.log4j.core.appender.rolling.action.IfFileName;
import org.apache.logging.log4j.core.appender.rolling.action.IfLastModified;
import org.apache.logging.log4j.core.appender.rolling.action.PathCondition;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A log file of its own for the chat journal ({@code btc.chatguard.journal}): it carries a uuid and a name
 * for every finding, whispers included, so it is kept apart from the main log and deleted after
 * {@code journal.retention-days} (90 by default). Added to the running log4j2 configuration, so it needs no
 * edit of {@code log4j2.xml} and survives an admin's own one.
 *
 * <p>The logger is not additive: a line goes to this file only, never to {@code latest.log} or the console.
 */
public final class ChatJournalLog {

    public static final String LOGGER_NAME = "btc.chatguard.journal";
    static final String APPENDER_NAME = "BtcChatGuardJournal";

    /** Where the file lives, relative to the server folder like {@code logs/latest.log}. */
    public static final Path DEFAULT_FOLDER = Path.of("logs", "chatguard");

    private ChatJournalLog() {}

    /** Installs the file on the server's log4j2 context. A failure is returned, never thrown: the chat must go on. */
    public static String installOnServer(int retentionDays) {
        try {
            if (LogManager.getContext(false) instanceof LoggerContext context) {
                return install(context, DEFAULT_FOLDER, retentionDays)
                    ? null : "the logger " + LOGGER_NAME + " is already configured in log4j2.xml, left as it is";
            }
            return "log4j2 is not the logging backend, the chat journal stays in the main log";
        } catch (IOException | RuntimeException failure) {
            return "the chat journal file could not be set up (" + failure.getMessage() + "), it stays in the main log";
        }
    }

    /**
     * @return {@code false} when the configuration already has a logger of that name (the admin routed it)
     */
    static boolean install(LoggerContext context, Path folder, int retentionDays) throws IOException {
        Configuration config = context.getConfiguration();
        if (config.getLoggers().containsKey(LOGGER_NAME)) {
            return false;
        }
        Files.createDirectories(folder);
        PatternLayout layout = PatternLayout.newBuilder().withConfiguration(config)
            .withPattern("[%d{yyyy-MM-dd HH:mm:ss}] %msg%n").build();
        // Deleting runs after each rollover; rolling at startup too means a restart prunes even a quiet log.
        Action prune = DeleteAction.createDeleteAction(folder.toString(), false, 1, false, null,
            new PathCondition[] {IfFileName.createNameCondition("*.log.gz", null),
                IfLastModified.createAgeCondition(org.apache.logging.log4j.core.appender.rolling.action.Duration
                    .parse("P" + retentionDays + "D"))}, null, config);
        DefaultRolloverStrategy strategy = DefaultRolloverStrategy.newBuilder().withMax("1000").withConfig(config)
            .withCustomActions(new Action[] {prune}).build();
        RollingFileAppender appender = RollingFileAppender.newBuilder().setName(APPENDER_NAME)
            .withFileName(folder.resolve("latest.log").toString())
            .withFilePattern(folder.resolve("%d{yyyy-MM-dd}-%i.log.gz").toString())
            .setLayout(layout)
            .withPolicy(CompositeTriggeringPolicy.createPolicy(
                TimeBasedTriggeringPolicy.newBuilder().withInterval(1).withModulate(true).build(),
                OnStartupTriggeringPolicy.createPolicy(1)))
            .withStrategy(strategy).setConfiguration(config).build();
        appender.start();
        config.addAppender(appender);
        LoggerConfig journal = new LoggerConfig(LOGGER_NAME, Level.INFO, false);
        journal.addAppender(appender, null, null);
        config.addLogger(LOGGER_NAME, journal);
        context.updateLoggers();
        return true;
    }
}
