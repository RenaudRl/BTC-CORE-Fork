package com.infernalsuite.asp.loaders.mysql;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.exceptions.WorldLockedException;
import com.infernalsuite.asp.api.loaders.LockableSlimeLoader;
import com.infernalsuite.asp.api.loaders.UpdatableLoader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * PostgreSQL loader for SlimeWorld.
 * Uses the same schema as MySQL but with PostgreSQL-compatible SQL syntax.
 *
 * <p>BTC-CORE (AD-096): this source is shared by several servers, so it leases each world to one
 * of them at a time ({@link LockableSlimeLoader}). The lease lives in the {@code worlds} row:
 * {@code lock_owner} names the holding server and {@code locked} is the end of the lease, in epoch
 * milliseconds of the <em>database</em> clock — every server compares against the same clock, so
 * skew between servers cannot hand one world to two of them.
 */
public class PostgresLoader extends UpdatableLoader implements LockableSlimeLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresLoader.class);
    private static final int CURRENT_DB_VERSION = 2;

    /** Default lease length (AD-096): the longest a crashed server keeps its worlds out of reach. */
    public static final Duration DEFAULT_LOCK_LEASE = Duration.ofSeconds(60);
    /** Default renewal period: three renewals per lease, so one missed renewal never loses it. */
    public static final Duration DEFAULT_LOCK_RENEW_INTERVAL = Duration.ofSeconds(20);

    /**
     * This server's identity as a lease holder. CloudNet hands a service no name of its own, so
     * the identity is drawn once per JVM: a restarted server is a new holder, and only waits for
     * its own crashed lease to expire.
     */
    private static final UUID JVM_LOCK_OWNER = UUID.randomUUID();

    private static final String NOW_MS = "(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT";
    /** A row this server may write: nobody holds it, this server does, or the lease ran out. */
    private static final String WRITABLE_BY_OWNER =
            "(worlds.lock_owner IS NULL OR worlds.lock_owner = ? OR worlds.locked < " + NOW_MS + ")";

    // PostgreSQL uses SERIAL instead of AUTO_INCREMENT, and different syntax
    private static final String CREATE_VERSIONING_TABLE_QUERY =
            "CREATE TABLE IF NOT EXISTS database_version (id SERIAL PRIMARY KEY, version INTEGER);";
    private static final String INSERT_VERSION_QUERY =
            "INSERT INTO database_version (id, version) VALUES (1, ?) ON CONFLICT (id) DO UPDATE SET version = ?;";
    private static final String GET_VERSION_QUERY = "SELECT version FROM database_version WHERE id = 1;";
    private static final String ALTER_LOCKED_COLUMN_QUERY =
            "ALTER TABLE worlds ALTER COLUMN locked TYPE BIGINT;";
    private static final String ADD_LOCK_OWNER_COLUMN_QUERY =
            "ALTER TABLE worlds ADD COLUMN IF NOT EXISTS lock_owner VARCHAR(36);";

    private static final String CREATE_WORLDS_TABLE_QUERY =
            "CREATE TABLE IF NOT EXISTS worlds (id SERIAL PRIMARY KEY, name VARCHAR(255) UNIQUE, locked BIGINT DEFAULT 0, "
                    + "lock_owner VARCHAR(36), world BYTEA);";
    private static final String SELECT_WORLD_QUERY = "SELECT world FROM worlds WHERE name = ?;";
    private static final String UPDATE_WORLD_QUERY =
            "INSERT INTO worlds (name, world) VALUES (?, ?) ON CONFLICT (name) DO UPDATE SET world = EXCLUDED.world WHERE "
                    + WRITABLE_BY_OWNER + ";";
    private static final String DELETE_WORLD_QUERY = "DELETE FROM worlds WHERE name = ?;";
    private static final String LIST_WORLDS_QUERY = "SELECT name FROM worlds;";

    private static final String ACQUIRE_LOCK_QUERY =
            "UPDATE worlds SET lock_owner = ?, locked = " + NOW_MS + " + ? WHERE name = ? AND " + WRITABLE_BY_OWNER + ";";
    private static final String RENEW_LOCK_QUERY =
            "UPDATE worlds SET locked = " + NOW_MS + " + ? WHERE name = ? AND lock_owner = ?;";
    private static final String RELEASE_LOCK_QUERY =
            "UPDATE worlds SET lock_owner = NULL, locked = 0 WHERE name = ? AND lock_owner = ?;";
    private static final String SELECT_LOCK_QUERY = "SELECT locked FROM worlds WHERE name = ?;";

    private final HikariDataSource source;
    private final String lockOwner;
    private final Duration lockLease;
    private final Duration lockRenewInterval;

    public PostgresLoader(String host, int port, String database, boolean useSSL, String username, String password) throws SQLException {
        this(host, port, database, useSSL, username, password, DEFAULT_LOCK_LEASE, DEFAULT_LOCK_RENEW_INTERVAL);
    }

    /**
     * @param lockLease         how long a lease lasts without renewal — the longest a crashed
     *                          server keeps its worlds out of reach
     * @param lockRenewInterval how often a held lease is renewed; strictly shorter than the lease
     */
    public PostgresLoader(String host, int port, String database, boolean useSSL, String username, String password,
                          Duration lockLease, Duration lockRenewInterval) throws SQLException {
        this(dataSource(host, port, database, useSSL, username, password), lockLease, lockRenewInterval, JVM_LOCK_OWNER);
    }

    public PostgresLoader(HikariDataSource hikariDataSource) throws SQLException {
        this(hikariDataSource, DEFAULT_LOCK_LEASE, DEFAULT_LOCK_RENEW_INTERVAL, JVM_LOCK_OWNER);
    }

    /**
     * Full constructor. {@code lockOwner} exists for tests that play two servers against one
     * database; production code uses the per-JVM identity of the other constructors.
     */
    public PostgresLoader(HikariDataSource hikariDataSource, Duration lockLease, Duration lockRenewInterval,
                          UUID lockOwner) throws SQLException {
        if (lockRenewInterval.isNegative() || lockRenewInterval.isZero() || lockLease.compareTo(lockRenewInterval) <= 0) {
            throw new IllegalArgumentException("The lock renewal interval (" + lockRenewInterval
                    + ") must be positive and strictly shorter than the lease (" + lockLease + ")");
        }
        this.source = hikariDataSource;
        this.lockOwner = lockOwner.toString();
        this.lockLease = lockLease;
        this.lockRenewInterval = lockRenewInterval;
        init();
    }

    private static HikariDataSource dataSource(String host, int port, String database, boolean useSSL, String username, String password) {
        HikariConfig hikariConfig = new HikariConfig();
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s?ssl=%b", host, port, database, useSSL);
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setConnectionTimeout(5000);
        hikariConfig.setPoolName("BTCCore-Postgres-Pool");
        return new HikariDataSource(hikariConfig);
    }

    public void close() {
        if (source != null && !source.isClosed()) {
            source.close();
        }
    }

    @Override
    public void update() throws IOException, NewerStorageException {
        try (Connection con = source.getConnection()) {
            int version;
            try (PreparedStatement statement = con.prepareStatement(GET_VERSION_QUERY);
                 ResultSet set = statement.executeQuery()) {
                version = set.next() ? set.getInt(1) : -1;
            }

            if (version > CURRENT_DB_VERSION) {
                throw new NewerStorageException(CURRENT_DB_VERSION, version);
            }

            if (version < CURRENT_DB_VERSION) {
                LOGGER.warn("Your SWM PostgreSQL database is outdated. The update process will start in 10 seconds.");
                LOGGER.warn("Shut down the server to prevent your database from being updated.");

                try {
                    Thread.sleep(10000L);
                } catch (InterruptedException ignored) {
                    LOGGER.info("Update process aborted.");
                    return;
                }

                if (version < 1) {
                    try (PreparedStatement statement = con.prepareStatement(ALTER_LOCKED_COLUMN_QUERY)) {
                        statement.executeUpdate();
                    }
                }

                // v2 (BTC-CORE, AD-096): the lease holder. Idempotent, because a database created
                // by this version already has the column.
                try (PreparedStatement statement = con.prepareStatement(ADD_LOCK_OWNER_COLUMN_QUERY)) {
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = con.prepareStatement(INSERT_VERSION_QUERY)) {
                    statement.setInt(1, CURRENT_DB_VERSION);
                    statement.setInt(2, CURRENT_DB_VERSION);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public byte[] readWorld(String worldName) throws UnknownWorldException, IOException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(SELECT_WORLD_QUERY)) {
            statement.setString(1, worldName);
            ResultSet set = statement.executeQuery();
            if (!set.next()) {
                throw new UnknownWorldException(worldName);
            }
            return set.getBytes("world");
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public boolean worldExists(String worldName) throws IOException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(SELECT_WORLD_QUERY)) {
            statement.setString(1, worldName);
            ResultSet set = statement.executeQuery();
            return set.next();
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public List<String> listWorlds() throws IOException {
        List<String> worldList = new ArrayList<>();
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(LIST_WORLDS_QUERY)) {
            ResultSet set = statement.executeQuery();
            while (set.next()) {
                worldList.add(set.getString("name"));
            }
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
        return worldList;
    }

    /**
     * Saves the world, unless another server holds an unexpired lease on it: a server whose lease
     * was taken over must not overwrite the new holder's world.
     *
     * @throws WorldLockedException if another server holds the lease
     */
    @Override
    public void saveWorld(String worldName, byte[] serializedWorld) throws IOException {
        int written;
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(UPDATE_WORLD_QUERY)) {
            statement.setString(1, worldName);
            statement.setBytes(2, serializedWorld);
            statement.setString(3, lockOwner);
            written = statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
        if (written > 0) return;

        // Nothing written means the row exists and another server holds it (an insert always writes).
        OptionalLong lockedUntil = lockedUntil(worldName);
        if (lockedUntil.isEmpty()) {
            throw new IOException("World " + worldName + " was deleted while it was being saved");
        }
        throw new WorldLockedException(worldName, Instant.ofEpochMilli(lockedUntil.getAsLong()));
    }

    @Override
    public void deleteWorld(String worldName) throws IOException, UnknownWorldException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(DELETE_WORLD_QUERY)) {
            statement.setString(1, worldName);
            if (statement.executeUpdate() == 0) {
                throw new UnknownWorldException(worldName);
            }
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public void acquireLock(String worldName) throws UnknownWorldException, WorldLockedException, IOException {
        int taken;
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(ACQUIRE_LOCK_QUERY)) {
            statement.setString(1, lockOwner);
            statement.setLong(2, lockLease.toMillis());
            statement.setString(3, worldName);
            statement.setString(4, lockOwner);
            taken = statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
        if (taken > 0) return;

        OptionalLong lockedUntil = lockedUntil(worldName);
        if (lockedUntil.isEmpty()) {
            throw new UnknownWorldException(worldName);
        }
        throw new WorldLockedException(worldName, Instant.ofEpochMilli(lockedUntil.getAsLong()));
    }

    @Override
    public boolean renewLock(String worldName) throws IOException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(RENEW_LOCK_QUERY)) {
            statement.setLong(1, lockLease.toMillis());
            statement.setString(2, worldName);
            statement.setString(3, lockOwner);
            return statement.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public void releaseLock(String worldName) throws IOException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(RELEASE_LOCK_QUERY)) {
            statement.setString(1, worldName);
            statement.setString(2, lockOwner);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    public Duration lockRenewInterval() {
        return lockRenewInterval;
    }

    /** The end of the current lease on {@code worldName}, empty when the world does not exist. */
    private OptionalLong lockedUntil(String worldName) throws IOException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(SELECT_LOCK_QUERY)) {
            statement.setString(1, worldName);
            ResultSet set = statement.executeQuery();
            return set.next() ? OptionalLong.of(set.getLong(1)) : OptionalLong.empty();
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    private void init() throws SQLException {
        try (Connection con = source.getConnection()) {
            try (PreparedStatement statement = con.prepareStatement(CREATE_WORLDS_TABLE_QUERY)) {
                statement.execute();
            }
            try (PreparedStatement statement = con.prepareStatement(CREATE_VERSIONING_TABLE_QUERY)) {
                statement.execute();
            }
        }
    }
}
