package dev.btc.core.stats.sql;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * No JDBC on a game thread: every public method of {@link StatsSqlStore} starts here.
 *
 * <p>Throws by default, so a test that reaches the store from a game thread fails. The server
 * switches to {@link #logOnly} at startup: in production a wrong call is reported once, not turned
 * into a crash of the tick.
 */
public final class StatsSqlThreadGuard {

    private static final Logger LOGGER = LogManager.getLogger("BTCCore");
    private static final AtomicBoolean REPORTED = new AtomicBoolean();
    private static volatile Predicate<Thread> gameThread = thread -> false;
    private static volatile boolean logOnly;

    private StatsSqlThreadGuard() {}

    /** @param isGameThread main, region, world, global tick or packet-processing thread */
    public static void install(Predicate<Thread> isGameThread, boolean production) {
        gameThread = isGameThread;
        logOnly = production;
    }

    public static void assertNotGameThread() {
        Thread current = Thread.currentThread();
        if (!gameThread.test(current)) {
            return;
        }
        IllegalStateException misuse = new IllegalStateException("stats-sql store called from game thread " + current.getName());
        if (!logOnly) {
            throw misuse;
        }
        if (REPORTED.compareAndSet(false, true)) {
            LOGGER.error("[BTCCore] stats-sql: database call on a game thread (reported once)", misuse);
        }
    }
}
