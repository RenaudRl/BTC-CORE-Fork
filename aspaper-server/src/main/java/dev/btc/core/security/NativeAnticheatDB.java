package dev.btc.core.security;

import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.integrity.IntegrityDatabase;
import org.bukkit.Bukkit;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/**
 * Asynchronous PostgreSQL journalling for Sentinel violation reports.
 *
 * <p>PostgreSQL, not MySQL: the rest of the BTC stack (permissions, island ownership) already runs on
 * it, and a third persistence technology in one project is not defensible. The MySQL driver that
 * remains on this module's runtime classpath is a legacy Bukkit-compatibility dependency for third
 * party plugins — it is unrelated to this class and must not be removed on its account.
 *
 * <p>Connection handling lives in {@link IntegrityDatabase}, which owns the one thread and the one
 * validated connection the whole integrity platform writes through. This class used to open a second
 * connection on a second thread carrying the same name, which meant two independent lifecycles to
 * reason about for no benefit.
 */
public final class NativeAnticheatDB {

    /** Most recent violations returned by a history lookup. */
    private static final int HISTORY_LIMIT = 10;

    /** Width of the {@code details} column: a longer value would fail the insert and lose the line. */
    static final int DETAILS_WIDTH = 255;

    /** Width of the {@code check_type} column. */
    static final int CHECK_TYPE_WIDTH = 50;

    private static volatile boolean enabled = false;

    private NativeAnticheatDB() {}

    /** Cuts a value to its column so the journal keeps a clipped line rather than none. */
    static String clip(final String value, final int width) {
        return value.length() <= width ? value : value.substring(0, width - 1) + "…";
    }

    private static String table() {
        return AnticheatConfig.storageTablePrefix + "sentinel_violations";
    }

    /**
     * Verifies the journal schema, using the {@code storage.*} settings of {@code anticheat.yml}.
     *
     * <p>Does nothing when storage is disabled. {@link IntegrityDatabase#configure()} must have run
     * first; it is what reads those settings.
     */
    public static void init() {
        if (!IntegrityDatabase.isEnabled()) {
            return;
        }
        IntegrityDatabase.applySchema("violation journal schema", new String[] {
            """
            CREATE TABLE IF NOT EXISTS %s (
                id           BIGSERIAL PRIMARY KEY,
                player_uuid  VARCHAR(36)  NOT NULL,
                player_name  VARCHAR(16)  NOT NULL,
                check_type   VARCHAR(50)  NOT NULL,
                details      VARCHAR(255) NOT NULL,
                created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
            )""".formatted(table()),

            "CREATE INDEX IF NOT EXISTS idx_%ssentinel_violations_uuid ON %s (player_uuid, created_at DESC)"
                .formatted(AnticheatConfig.storageTablePrefix, table()),

            "CREATE INDEX IF NOT EXISTS idx_%ssentinel_violations_name ON %s (player_name, created_at DESC)"
                .formatted(AnticheatConfig.storageTablePrefix, table())
        }).thenRun(() -> {
            enabled = true;
            Bukkit.getLogger().info("[SentinelDB] PostgreSQL journal ready, schema verified.");
        }).exceptionally(failure -> {
            enabled = false;
            Bukkit.getLogger().warning("[SentinelDB] Could not open the PostgreSQL journal; "
                + "violations are still detected and acted on, but not journalled.");
            return null;
        });
    }

    /** Whether violations are being journalled. */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Deletes journal lines older than the horizon.
     *
     * <p>Deleted, not anonymised — the opposite of what happens to a sanction. A sanction is a decision
     * that has to stay auditable; a violation line is a suspicion, and an anonymised suspicion is of no
     * use to anyone while still being a row about somebody.
     *
     * @return how many lines were removed
     */
    public static java.util.concurrent.CompletableFuture<Integer> purgeOlderThan(long retentionMillis) {
        if (!enabled) {
            return java.util.concurrent.CompletableFuture.completedFuture(0);
        }
        long horizon = System.currentTimeMillis() - retentionMillis;
        return IntegrityDatabase.query("violation journal purge", connection -> {
            // created_at is TIMESTAMPTZ here, unlike the sanction table's epoch millis, so the horizon
            // is converted rather than compared as a number.
            String sql = "DELETE FROM " + table() + " WHERE created_at < ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setTimestamp(1, new java.sql.Timestamp(horizon));
                return stmt.executeUpdate();
            }
        }).exceptionally(IntegrityDatabase.degradeTo(0));
    }

    public static void reportViolation(String uuid, String name, String checkType, String details) {
        if (!enabled) {
            return;
        }
        IntegrityDatabase.execute("journal violation for " + name, connection -> {
            String insert = "INSERT INTO " + table()
                + " (player_uuid, player_name, check_type, details) VALUES (?, ?, ?, ?)";
            try (PreparedStatement stmt = connection.prepareStatement(insert)) {
                stmt.setString(1, uuid);
                stmt.setString(2, name);
                stmt.setString(3, clip(checkType, CHECK_TYPE_WIDTH));
                stmt.setString(4, clip(details, DETAILS_WIDTH));
                stmt.executeUpdate();
            }
            return null;
        });
    }

    public static void fetchRecentViolationsAsync(String playerName, java.util.function.Consumer<List<String>> callback) {
        if (!enabled) {
            callback.accept(List.of("[SentinelDB] Violation journalling is disabled (storage.enabled)."));
            return;
        }

        IntegrityDatabase.query("read violations for " + playerName, connection -> {
            List<String> logs = new ArrayList<>();
            String query = "SELECT check_type, details, created_at FROM " + table()
                + " WHERE player_name = ? ORDER BY created_at DESC LIMIT " + HISTORY_LIMIT;
            try (PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setString(1, playerName);
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        logs.add(rs.getTimestamp("created_at") + " - "
                            + rs.getString("check_type") + " - "
                            + rs.getString("details"));
                    }
                }
            }
            return logs;
        }).exceptionally(failure -> List.of("Journal lookup failed. See console."))
          .thenAccept(callback);
    }
}
