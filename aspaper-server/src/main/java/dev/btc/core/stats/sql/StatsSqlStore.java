package dev.btc.core.stats.sql;

import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The player statistics rows, one JSON document per player, each protected by a lease.
 *
 * <p>A row is read only after its lease is taken, and every write of the owner names the owner and
 * the generation it was given with the lease: once another service has taken the row over, the
 * former owner's writes change nothing ({@code false} comes back) instead of overwriting.
 *
 * <p>One connection, checked before use and reopened when it died; one caller at a time. Nothing
 * here may run on a game thread ({@link StatsSqlThreadGuard}).
 */
public final class StatsSqlStore implements AutoCloseable {

    /** Opens a connection; the password travels in driver properties, never in a URL or a log. */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    /** A player's row. {@code payload} is null while nothing was ever stored. */
    public record Row(@Nullable String payload, int dataVersion, long revision, long generation) {}

    /** One document to store under a lease; {@code release} frees the row in the same statement. */
    public record Write(UUID player, long generation, String payload, int dataVersion, String source, boolean release) {}

    /** The database was created by a newer build: this one must not touch it. */
    public static final class NewerSchemaException extends SQLException {
        NewerSchemaException(String found) {
            super("stats-sql " + StatsSqlStatements.SCHEMA_VERSION_KEY + " " + found + " is newer than "
                + StatsSqlStatements.SCHEMA_VERSION + ", the layer stays closed");
        }
    }

    public static final String SOURCE_RUNTIME = "runtime";
    public static final String SOURCE_IMPORT = "json-import";
    public static final String SOURCE_MERGE = "merge";
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;
    /** Any constant shared by every service: who creates the tables first holds it. */
    private static final long SCHEMA_LOCK_KEY = 0x4254435F53544154L;

    private final StatsSqlStatements sql;
    private final ConnectionSource source;
    private final UUID owner;
    private final long leaseMs;
    private final boolean advisorySchemaLock;
    private final ReentrantLock lock = new ReentrantLock();
    private @Nullable Connection connection;

    /**
     * @param owner              this JVM, drawn once at startup
     * @param advisorySchemaLock PostgreSQL only: serialises table creation between services
     */
    public StatsSqlStore(StatsSqlStatements sql, ConnectionSource source, UUID owner, long leaseMs, boolean advisorySchemaLock) {
        this.sql = sql;
        this.source = source;
        this.owner = owner;
        this.leaseMs = leaseMs;
        this.advisorySchemaLock = advisorySchemaLock;
    }

    /** The PostgreSQL connection of a configuration. Builds a URL from validated parts only. */
    public static ConnectionSource postgres(StatsSqlConfig config) {
        String url = "jdbc:postgresql://" + config.host() + ":" + config.port() + "/" + config.database();
        Properties properties = properties(config);
        return () -> DriverManager.getConnection(url, properties);
    }

    /** What the driver is given besides the URL. Holds the password: never printed. */
    static Properties properties(StatsSqlConfig config) {
        Properties properties = new Properties();
        properties.setProperty("user", config.username());
        properties.setProperty("password", config.password());
        properties.setProperty("sslmode", config.sslMode());
        if (!config.sslRootCert().isEmpty()) {
            properties.setProperty("sslrootcert", config.sslRootCert());
        }
        properties.setProperty("connectTimeout", Integer.toString(config.connectTimeoutSeconds()));
        properties.setProperty("socketTimeout", Integer.toString(config.socketTimeoutSeconds()));
        properties.setProperty("ApplicationName", "btccore-stats-sql");
        return properties;
    }

    /** Creates the tables when they are missing and checks the schema version. */
    public void ensureSchema() throws SQLException {
        run(db -> {
            db.setAutoCommit(false);
            try (Statement statement = db.createStatement()) {
                if (advisorySchemaLock) {
                    try (PreparedStatement wait = db.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                        wait.setLong(1, SCHEMA_LOCK_KEY);
                        wait.execute();
                    }
                }
                statement.execute(sql.createStats);
                statement.execute(sql.createMeta);
                String version = schemaVersion(db);
                if (version == null) {
                    try (PreparedStatement insert = db.prepareStatement(sql.insertMeta)) {
                        insert.setString(1, StatsSqlStatements.SCHEMA_VERSION_KEY);
                        insert.setString(2, Integer.toString(StatsSqlStatements.SCHEMA_VERSION));
                        insert.executeUpdate();
                    }
                } else if (!version.matches("\\d{1,9}") || Integer.parseInt(version) > StatsSqlStatements.SCHEMA_VERSION) {
                    throw new NewerSchemaException(version);
                }
                db.commit();
            } catch (SQLException failure) {
                db.rollback();
                throw failure;
            } finally {
                db.setAutoCommit(true);
            }
            return null;
        });
    }

    /**
     * Takes the lease of {@code player}, creating the row when there is none, then reads it.
     *
     * @param force take it even from a live owner (the generation still grows, which fences that owner)
     * @return null when another owner holds a live lease and {@code force} is false
     */
    public @Nullable Row acquire(UUID player, boolean force) throws SQLException {
        return run(db -> {
            if (lease(db, player, force) == 0) {
                if (exists(db, player)) {
                    return null;
                }
                try (PreparedStatement insert = db.prepareStatement(sql.create)) {
                    insert.setObject(1, player);
                    insert.setObject(2, owner);
                    insert.setLong(3, leaseMs);
                    insert.executeUpdate();
                } catch (SQLException raced) {
                    // Another service created the row in between: ask for its lease once, like anyone else.
                    if (!StatsSqlStatements.UNIQUE_VIOLATION.equals(raced.getSQLState())) throw raced;
                    if (lease(db, player, force) == 0) return null;
                }
            }
            return read(db, player);
        });
    }

    /** @return who holds the row, live or not; null when free or absent */
    public @Nullable UUID holder(UUID player) throws SQLException {
        return run(db -> holder(db, player));
    }

    /** @return false when the lease is no longer ours: nothing was written */
    public boolean write(Write write) throws SQLException {
        return writeBatch(List.of(write)).isEmpty();
    }

    /**
     * Stores every document in one transaction.
     *
     * @return the writes that changed nothing because their lease is no longer ours
     */
    public List<Write> writeBatch(List<Write> writes) throws SQLException {
        if (writes.isEmpty()) return List.of();
        return run(db -> {
            db.setAutoCommit(false);
            try (PreparedStatement keep = db.prepareStatement(sql.write);
                 PreparedStatement free = db.prepareStatement(sql.writeAndRelease)) {
                List<Write> kept = new ArrayList<>();
                List<Write> freed = new ArrayList<>();
                for (Write write : writes) {
                    PreparedStatement statement = write.release() ? free : keep;
                    int at = 1;
                    statement.setString(at++, write.payload());
                    statement.setInt(at++, write.dataVersion());
                    if (!write.release()) statement.setLong(at++, leaseMs);
                    statement.setString(at++, write.source());
                    statement.setObject(at++, write.player());
                    statement.setObject(at++, owner);
                    statement.setLong(at, write.generation());
                    statement.addBatch();
                    (write.release() ? freed : kept).add(write);
                }
                List<Write> fenced = new ArrayList<>();
                collectUnchanged(keep.executeBatch(), kept, fenced);
                collectUnchanged(free.executeBatch(), freed, fenced);
                db.commit();
                return fenced;
            } catch (SQLException failure) {
                db.rollback();
                throw failure;
            } finally {
                db.setAutoCommit(true);
            }
        });
    }

    /** Frees the row without writing. @return false when the lease was no longer ours */
    public boolean release(UUID player, long generation) throws SQLException {
        return run(db -> {
            try (PreparedStatement statement = db.prepareStatement(sql.release)) {
                bindOwned(statement, 1, player, generation);
                return statement.executeUpdate() == 1;
            }
        });
    }

    /**
     * Extends every lease in one round trip.
     *
     * @param held generation by player
     * @return the players whose lease is no longer ours
     */
    public List<UUID> renew(Map<UUID, Long> held) throws SQLException {
        if (held.isEmpty()) return List.of();
        return run(db -> {
            List<UUID> players = new ArrayList<>(held.keySet());
            try (PreparedStatement statement = db.prepareStatement(sql.renew)) {
                for (UUID player : players) {
                    statement.setLong(1, leaseMs);
                    bindOwned(statement, 2, player, held.get(player));
                    statement.addBatch();
                }
                List<UUID> lost = new ArrayList<>();
                collectUnchanged(statement.executeBatch(), players, lost);
                return lost;
            }
        });
    }

    /** Reads a row without a lease (offline access, migration). @return null when there is no row */
    public @Nullable Row peek(UUID player) throws SQLException {
        return run(db -> exists(db, player) ? read(db, player) : null);
    }

    /**
     * Replaces the document of a player nobody holds, if it is still at {@code revision}.
     *
     * @return false when a live lease exists or the row changed since it was read
     */
    public boolean writeOffline(UUID player, long revision, String payload, int dataVersion, String source) throws SQLException {
        return run(db -> {
            try (PreparedStatement statement = db.prepareStatement(sql.offlineWrite)) {
                statement.setString(1, payload);
                statement.setInt(2, dataVersion);
                statement.setString(3, source);
                statement.setObject(4, player);
                statement.setLong(5, revision);
                return statement.executeUpdate() == 1;
            }
        });
    }

    /**
     * Stores a document only where there is none: a new row, or a row reserved without a document.
     *
     * @return false when the player already has a document
     */
    public boolean importIfAbsent(UUID player, String payload, int dataVersion) throws SQLException {
        return run(db -> {
            try (PreparedStatement insert = db.prepareStatement(sql.importAbsent)) {
                insert.setString(1, player.toString());
                insert.setString(2, payload);
                insert.setInt(3, dataVersion);
                insert.setString(4, player.toString());
                if (insert.executeUpdate() == 1) return true;
            } catch (SQLException raced) {
                if (!StatsSqlStatements.UNIQUE_VIOLATION.equals(raced.getSQLState())) throw raced;
            }
            try (PreparedStatement fill = db.prepareStatement(sql.importReserved)) {
                fill.setString(1, payload);
                fill.setInt(2, dataVersion);
                fill.setObject(3, player);
                return fill.executeUpdate() == 1;
            }
        });
    }

    /** @throws SQLException when the database does not answer */
    public void probe() throws SQLException {
        run(db -> {
            try (Statement statement = db.createStatement()) {
                statement.execute(sql.probe);
            }
            return null;
        });
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closeQuietly();
        } finally {
            lock.unlock();
        }
    }

    private interface Work<T> {
        T on(Connection db) throws SQLException;
    }

    private <T> T run(Work<T> work) throws SQLException {
        StatsSqlThreadGuard.assertNotGameThread();
        lock.lock();
        try {
            if (connection == null || !connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                closeQuietly();
                connection = source.open();
            }
            return work.on(connection);
        } catch (SQLException failure) {
            closeQuietly(); // a connection that failed is not trusted again
            throw failure;
        } finally {
            lock.unlock();
        }
    }

    private void closeQuietly() {
        Connection open = connection;
        connection = null;
        if (open != null) {
            try {
                open.close();
            } catch (SQLException ignored) {
                // nothing to do with a connection that will not even close
            }
        }
    }

    private int lease(Connection db, UUID player, boolean force) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(force ? sql.takeover : sql.acquire)) {
            statement.setObject(1, owner);
            statement.setLong(2, leaseMs);
            statement.setObject(3, player);
            if (!force) statement.setObject(4, owner);
            return statement.executeUpdate();
        }
    }

    private Row read(Connection db, UUID player) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql.read)) {
            statement.setObject(1, player);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("stats-sql: the row of " + player + " vanished under its lease");
                return new Row(row.getString(1), row.getInt(2), row.getLong(3), row.getLong(4));
            }
        }
    }

    private @Nullable UUID holder(Connection db, UUID player) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql.holder)) {
            statement.setObject(1, player);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1, UUID.class) : null;
            }
        }
    }

    private boolean exists(Connection db, UUID player) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql.holder)) {
            statement.setObject(1, player);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private @Nullable String schemaVersion(Connection db) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql.selectMeta)) {
            statement.setString(1, StatsSqlStatements.SCHEMA_VERSION_KEY);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }

    private void bindOwned(PreparedStatement statement, int at, UUID player, long generation) throws SQLException {
        statement.setObject(at, player);
        statement.setObject(at + 1, owner);
        statement.setLong(at + 2, generation);
    }

    private static <T> void collectUnchanged(int[] counts, List<T> items, List<T> unchanged) {
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) unchanged.add(items.get(i));
        }
    }
}
