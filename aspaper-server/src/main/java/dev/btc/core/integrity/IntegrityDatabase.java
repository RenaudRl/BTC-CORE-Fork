package dev.btc.core.integrity;

import dev.btc.core.config.AnticheatConfig;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * The single PostgreSQL connection the integrity platform writes through.
 *
 * <p>One thread, one connection, reused. Not a pool, deliberately: every caller here is already
 * serialised onto this executor, so a pool would add a dependency and contend for nothing. The defect
 * this replaces was the opposite extreme — a connection opened and closed per statement.
 *
 * <p>The connection is validated before each use and reopened when it has died, which is what makes a
 * long-lived connection safe against a database restart or an idle timeout.
 *
 * <p>Everything is asynchronous. No caller on a region thread ever waits on the network: a violation
 * journal that stalls a tick would be a worse problem than the one it records.
 */
public final class IntegrityDatabase {

    /** Seconds allowed for validation before the connection is considered dead. */
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Sentinel-DB");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean enabled;
    private static String url = "";
    private static String user = "";
    private static String password = "";

    /** Owned exclusively by {@link #THREAD}. */
    private static Connection connection;

    private IntegrityDatabase() {}

    /**
     * Reads {@code storage.*} from {@code anticheat.yml} and marks the database usable.
     *
     * <p>Does not connect: the first query does. A server that starts while the database is down must
     * still start.
     */
    public static void configure() {
        if (!AnticheatConfig.storageEnabled) {
            enabled = false;
            return;
        }
        String host = AnticheatConfig.storageHost;
        if (host == null || host.isBlank()) {
            Bukkit.getLogger().warning(
                "[Sentinel] storage.enabled is true but storage.host is empty; persistence stays off.");
            enabled = false;
            return;
        }
        url = "jdbc:postgresql://" + host + ":" + AnticheatConfig.storagePort
            + "/" + AnticheatConfig.storageDatabase;
        user = AnticheatConfig.storageUsername;
        password = AnticheatConfig.storagePassword;
        enabled = true;
    }

    /** Whether persistence is configured. Callers must degrade rather than fail when it is not. */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Runs work against the connection on the database thread.
     *
     * @param label what the work is, used when reporting a failure
     * @param work receives a live connection
     * @return the result, or an exceptionally completed stage when persistence is off or the work
     *     failed. Never throws to the caller's thread.
     */
    public static <T> CompletableFuture<T> query(String label, SqlWork<T> work) {
        if (!enabled) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("integrity persistence is disabled (storage.enabled)"));
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        THREAD.submit(() -> {
            try {
                result.complete(work.apply(connection()));
            } catch (SQLException | RuntimeException failure) {
                Bukkit.getLogger().log(Level.WARNING, "[Sentinel] " + label + " failed.", failure);
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** Fire-and-forget variant for writes whose result nobody waits on. */
    public static void execute(String label, SqlWork<Void> work) {
        query(label, work).exceptionally(failure -> null);
    }

    /** Work that needs a connection and may fail with SQL. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /**
     * Applies a schema, statement by statement, idempotently.
     *
     * @return a stage completing once every statement has run
     */
    public static CompletableFuture<Void> applySchema(String label, String[] statements) {
        return query(label, connection -> {
            try (var statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            }
            return null;
        });
    }

    /** Maps a failure into a fallback value, so callers can degrade in one line. */
    public static <T> Function<Throwable, T> degradeTo(T fallback) {
        return failure -> fallback;
    }

    private static Connection connection() throws SQLException {
        if (connection == null || !connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
            closeQuietly();
            connection = DriverManager.getConnection(url, user, password);
        }
        return connection;
    }

    private static void closeQuietly() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Already dead; reopening is the point of this path.
        }
        connection = null;
    }
}
