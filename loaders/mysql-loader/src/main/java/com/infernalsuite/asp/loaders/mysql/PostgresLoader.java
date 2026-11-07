package com.infernalsuite.asp.loaders.mysql;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.exceptions.WorldAlreadyExistsException;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * PostgreSQL loader for SlimeWorld.
 * Uses the same schema as MySQL but with PostgreSQL-compatible SQL syntax.
 *
 * <p>BTC-CORE (AD-096): this source is shared by several servers, so it leases each world to one
 * of them at a time ({@link LockableSlimeLoader}). The lease lives in the {@code worlds} row:
 * {@code lock_owner} names the holding server, {@code locked} is the end of the lease in epoch
 * milliseconds of the <em>database</em> clock (so skew between servers cannot hand one world to
 * two of them), and {@code lock_generation} is the fencing token, drawn from a sequence at every
 * acquisition and never reused. Every write is conditioned on owner <em>and</em> generation in the
 * same statement, so a server that lost its lease can never write again, even once the world is
 * free.
 */
public class PostgresLoader extends UpdatableLoader implements LockableSlimeLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresLoader.class);
    private static final int CURRENT_DB_VERSION = 3;

    /** Default lease length (AD-096): the longest a crashed server keeps its worlds out of reach. */
    public static final Duration DEFAULT_LOCK_LEASE = Duration.ofSeconds(60);
    /** Default renewal period: three renewals per lease, so one missed renewal never loses it. */
    public static final Duration DEFAULT_LOCK_RENEW_INTERVAL = Duration.ofSeconds(20);
    /** Default bound on opening a connection to the database. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /**
     * Default bound on waiting for the database to answer: a statement stuck on a dead link would
     * otherwise hang the renewal thread or a save forever.
     */
    public static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(10);
    /** Advisory lock key serialising schema creation and migration between servers starting together. */
    private static final long SCHEMA_LOCK_KEY = 0x4254_4353_4c49_4d45L; // "BTCSLIME"

    /** What the host and the database may contain: they are pasted into the JDBC URL. */
    private static final Pattern URL_SAFE = Pattern.compile("[A-Za-z0-9._-]+");
    /** A generation no lease ever has: the sequence starts at 1. */
    private static final long NO_GENERATION = -1L;

    /**
     * This server's identity as a lease holder. CloudNet hands a service no name of its own, so
     * the identity is drawn once per JVM: a restarted server is a new holder, and only waits for
     * its own crashed lease to expire.
     */
    private static final UUID JVM_LOCK_OWNER = UUID.randomUUID();

    private static final String NOW_MS = "(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT";
    private static final String NEXT_GENERATION = "nextval('worlds_lock_generation_seq')";
    /** The row is under this server's lease of this generation (owner, generation). */
    private static final String HELD_BY_ME = "worlds.lock_owner = ? AND worlds.lock_generation = ?";
    /** Nobody holds the row: never leased, released, or the lease ran out. */
    private static final String FREE_OR_EXPIRED = "(worlds.lock_owner IS NULL OR worlds.locked < " + NOW_MS + ")";

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
    private static final String ADD_LOCK_GENERATION_COLUMN_QUERY =
            "ALTER TABLE worlds ADD COLUMN IF NOT EXISTS lock_generation BIGINT NOT NULL DEFAULT 0;";
    private static final String CREATE_GENERATION_SEQUENCE_QUERY =
            "CREATE SEQUENCE IF NOT EXISTS worlds_lock_generation_seq;";

    private static final String CREATE_WORLDS_TABLE_QUERY =
            "CREATE TABLE IF NOT EXISTS worlds (id SERIAL PRIMARY KEY, name VARCHAR(255) UNIQUE, locked BIGINT DEFAULT 0, "
                    + "lock_owner VARCHAR(36), lock_generation BIGINT NOT NULL DEFAULT 0, world BYTEA);";
    private static final String SELECT_WORLD_QUERY = "SELECT world FROM worlds WHERE name = ?;";
    private static final String LIST_WORLDS_QUERY = "SELECT name FROM worlds;";

    private static final String SAVE_HELD_WORLD_QUERY =
            "UPDATE worlds SET world = ? WHERE name = ? AND " + HELD_BY_ME + ";";
    /** A world that does not exist yet, created already leased to this server. */
    private static final String CREATE_LEASED_WORLD_QUERY =
            "INSERT INTO worlds (name, world, lock_owner, lock_generation, locked) VALUES (?, ?, ?, " + NEXT_GENERATION
                    + ", " + NOW_MS + " + ?) ON CONFLICT (name) DO NOTHING RETURNING lock_generation;";
    private static final String DELETE_WORLD_QUERY =
            "DELETE FROM worlds WHERE name = ? AND ((" + HELD_BY_ME + ") OR " + FREE_OR_EXPIRED + ");";

    private static final String ACQUIRE_LOCK_QUERY =
            "UPDATE worlds SET lock_owner = ?, lock_generation = " + NEXT_GENERATION + ", locked = " + NOW_MS + " + ? "
                    + "WHERE name = ? AND (worlds.lock_owner = ? OR " + FREE_OR_EXPIRED + ") RETURNING lock_generation;";
    private static final String RENEW_LOCKS_QUERY =
            "UPDATE worlds SET locked = " + NOW_MS + " + ? FROM unnest(?::text[], ?::bigint[]) AS held(name, generation) "
                    + "WHERE worlds.name = held.name AND worlds.lock_generation = held.generation AND worlds.lock_owner = ? "
                    + "RETURNING worlds.name;";
    private static final String RELEASE_LOCK_QUERY =
            "UPDATE worlds SET lock_owner = NULL, locked = 0 WHERE name = ? AND " + HELD_BY_ME + ";";
    private static final String RELEASE_ALL_LOCKS_QUERY =
            "UPDATE worlds SET lock_owner = NULL, locked = 0 WHERE lock_owner = ?;";
    private static final String SELECT_LOCK_QUERY = "SELECT locked FROM worlds WHERE name = ?;";
    private static final String SCHEMA_LOCK_QUERY = "SELECT pg_advisory_xact_lock(?);";

    private final HikariDataSource source;
    private final String lockOwner;
    private final Duration lockLease;
    private final Duration lockRenewInterval;
    /** World name to the generation this server holds on it. */
    private final Map<String, Long> held = new ConcurrentHashMap<>();

    public PostgresLoader(String host, int port, String database, boolean useSSL, String username, String password) throws SQLException {
        this(host, port, database, useSSL, username, password, DEFAULT_LOCK_LEASE, DEFAULT_LOCK_RENEW_INTERVAL,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_SOCKET_TIMEOUT);
    }

    /**
     * @param lockLease         how long a lease lasts without renewal — the longest a crashed
     *                          server keeps its worlds out of reach
     * @param lockRenewInterval how often a held lease is renewed; at most half the lease
     * @param connectTimeout    the longest opening a connection may take (whole seconds)
     * @param socketTimeout     the longest the database may take to answer (whole seconds)
     */
    public PostgresLoader(String host, int port, String database, boolean useSSL, String username, String password,
                          Duration lockLease, Duration lockRenewInterval, Duration connectTimeout,
                          Duration socketTimeout) throws SQLException {
        this(dataSource(host, port, database, useSSL, username, password, connectTimeout, socketTimeout),
                lockLease, lockRenewInterval, JVM_LOCK_OWNER);
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
        if (lockRenewInterval.isNegative() || lockRenewInterval.isZero()
                || lockRenewInterval.multipliedBy(2).compareTo(lockLease) > 0) {
            throw new IllegalArgumentException("The lock renewal interval (" + lockRenewInterval
                    + ") must be positive and at most half the lease (" + lockLease + ")");
        }
        this.source = hikariDataSource;
        this.lockOwner = lockOwner.toString();
        this.lockLease = lockLease;
        this.lockRenewInterval = lockRenewInterval;
        init();
    }

    private static HikariDataSource dataSource(String host, int port, String database, boolean useSSL, String username,
                                               String password, Duration connectTimeout, Duration socketTimeout) {
        requireUrlSafe("host", host);
        requireUrlSafe("database", database);
        HikariConfig hikariConfig = new HikariConfig();
        // The driver takes both timeouts in whole seconds.
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s?ssl=%b&connectTimeout=%d&socketTimeout=%d",
                host, port, database, useSSL, connectTimeout.toSeconds(), socketTimeout.toSeconds());
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setConnectionTimeout(5000);
        hikariConfig.setPoolName("BTCCore-Postgres-Pool");
        return new HikariDataSource(hikariConfig);
    }

    /** The message names the parameter, never the value. */
    private static void requireUrlSafe(String what, String value) {
        if (value == null || !URL_SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("The PostgreSQL " + what + " must only contain letters, digits, '.', '_' or '-'");
        }
    }

    public void close() {
        if (source != null && !source.isClosed()) {
            source.close();
        }
    }

    @Override
    public void update() throws IOException, NewerStorageException {
        try (Connection con = source.getConnection()) {
            int version = readVersion(con);

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

                // BTC-CORE (AD-096): the schema lock is taken after the wait, so a server starting
                // alongside never waits on it longer than the migration itself (its socket timeout
                // would fire); the version is read again under it, another server may have migrated.
                underSchemaLock(con, () -> migrate(con, readVersion(con)));
            }
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    private static int readVersion(Connection con) throws SQLException {
        try (PreparedStatement statement = con.prepareStatement(GET_VERSION_QUERY);
             ResultSet set = statement.executeQuery()) {
            return set.next() ? set.getInt(1) : -1;
        }
    }

    private static void migrate(Connection con, int version) throws SQLException {
        if (version >= CURRENT_DB_VERSION) return;
        if (version < 1) {
            try (PreparedStatement statement = con.prepareStatement(ALTER_LOCKED_COLUMN_QUERY)) {
                statement.executeUpdate();
            }
        }

        // v2 (lease holder) and v3 (fencing generation), BTC-CORE AD-096. Both idempotent and
        // run together: a database created by this version already has the columns, and a
        // bench database may sit at v2, written before the generation existed.
        for (String step : List.of(ADD_LOCK_OWNER_COLUMN_QUERY, ADD_LOCK_GENERATION_COLUMN_QUERY,
                CREATE_GENERATION_SEQUENCE_QUERY)) {
            try (PreparedStatement statement = con.prepareStatement(step)) {
                statement.executeUpdate();
            }
        }

        try (PreparedStatement statement = con.prepareStatement(INSERT_VERSION_QUERY)) {
            statement.setInt(1, CURRENT_DB_VERSION);
            statement.setInt(2, CURRENT_DB_VERSION);
            statement.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface SchemaWork {
        void run() throws SQLException;
    }

    /**
     * Runs {@code work} in one transaction holding the schema advisory lock: two servers starting
     * on an empty database would otherwise race their {@code CREATE ... IF NOT EXISTS} (which is not
     * atomic in PostgreSQL) or their migration. The lock ends with the transaction.
     */
    private static void underSchemaLock(Connection con, SchemaWork work) throws SQLException {
        boolean autoCommit = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            try (PreparedStatement lock = con.prepareStatement(SCHEMA_LOCK_QUERY)) {
                lock.setLong(1, SCHEMA_LOCK_KEY);
                lock.execute();
            }
            work.run();
            con.commit();
        } catch (SQLException | RuntimeException failure) {
            con.rollback();
            throw failure;
        } finally {
            con.setAutoCommit(autoCommit);
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
     * Always refused: a write without its generation could land a stale copy under a newer lease,
     * or insert again a world deleted meanwhile. Use {@link #saveWorld(String, byte[], long)} or
     * {@link #createWorld}.
     *
     * @throws WorldLockedException always
     */
    @Override
    public void saveWorld(String worldName, byte[] serializedWorld) throws IOException {
        throw new WorldLockedException(worldName, "World " + worldName + " is in a shared source: a save that "
                + "carries no lease generation is refused. Save the loaded world, or create a new one with createWorld.");
    }

    @Override
    public long createWorld(String worldName, byte[] serializedWorld) throws WorldAlreadyExistsException, IOException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(CREATE_LEASED_WORLD_QUERY)) {
            statement.setString(1, worldName);
            statement.setBytes(2, serializedWorld);
            statement.setString(3, lockOwner);
            statement.setLong(4, lockLease.toMillis());
            try (ResultSet created = statement.executeQuery()) {
                if (created.next()) {
                    long generation = created.getLong(1);
                    held.put(worldName, generation);
                    return generation;
                }
            }
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
        throw new WorldAlreadyExistsException(worldName);
    }

    @Override
    public void saveWorld(String worldName, byte[] serializedWorld, long generation) throws IOException {
        int written;
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(SAVE_HELD_WORLD_QUERY)) {
            statement.setBytes(1, serializedWorld);
            statement.setString(2, worldName);
            statement.setString(3, lockOwner);
            statement.setLong(4, generation);
            written = statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
        if (written == 0) {
            throw new WorldLockedException(worldName);
        }
    }

    /**
     * Deletes the world if this server holds its lease, or if nobody does (free or expired).
     *
     * @throws WorldLockedException  if another server holds an unexpired lease on it
     * @throws UnknownWorldException if it does not exist
     */
    @Override
    public void deleteWorld(String worldName) throws IOException, UnknownWorldException {
        Long generation = held.get(worldName);
        int deleted;
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(DELETE_WORLD_QUERY)) {
            statement.setString(1, worldName);
            statement.setString(2, lockOwner);
            statement.setLong(3, generation == null ? NO_GENERATION : generation);
            deleted = statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
        if (deleted > 0) {
            if (generation != null) held.remove(worldName, generation);
            return;
        }
        refuse(worldName);
    }

    @Override
    public long acquireLock(String worldName) throws UnknownWorldException, WorldLockedException, IOException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(ACQUIRE_LOCK_QUERY)) {
            statement.setString(1, lockOwner);
            statement.setLong(2, lockLease.toMillis());
            statement.setString(3, worldName);
            statement.setString(4, lockOwner);
            try (ResultSet taken = statement.executeQuery()) {
                if (taken.next()) {
                    long generation = taken.getLong(1);
                    held.put(worldName, generation);
                    return generation;
                }
            }
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
        refuse(worldName);
        throw new AssertionError("unreachable: refuse always throws");
    }

    @Override
    public Map<String, Long> heldLocks() {
        return Collections.unmodifiableMap(held);
    }

    @Override
    public Set<String> renewLocks() throws IOException {
        Map<String, Long> snapshot = Map.copyOf(held);
        if (snapshot.isEmpty()) return Set.of();

        String[] names = snapshot.keySet().toArray(String[]::new);
        Long[] generations = new Long[names.length];
        for (int i = 0; i < names.length; i++) {
            generations[i] = snapshot.get(names[i]);
        }

        Set<String> renewed = new HashSet<>();
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(RENEW_LOCKS_QUERY)) {
            statement.setLong(1, lockLease.toMillis());
            statement.setArray(2, con.createArrayOf("text", names));
            statement.setArray(3, con.createArrayOf("int8", generations));
            statement.setString(4, lockOwner);
            try (ResultSet set = statement.executeQuery()) {
                while (set.next()) {
                    renewed.add(set.getString(1));
                }
            }
        } catch (SQLException ex) {
            throw new IOException(ex);
        }

        // Only a lease still held under the same generation is lost: one released or re-taken
        // meanwhile has already left, or changed generation, and is not this renewal's business.
        Set<String> lost = new HashSet<>();
        snapshot.forEach((name, generation) -> {
            if (!renewed.contains(name) && held.remove(name, generation)) lost.add(name);
        });
        return lost;
    }

    @Override
    public void releaseLock(String worldName, long generation) throws IOException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(RELEASE_LOCK_QUERY)) {
            statement.setString(1, worldName);
            statement.setString(2, lockOwner);
            statement.setLong(3, generation);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
        held.remove(worldName, generation);
    }

    @Override
    public void releaseAllLocks() throws IOException {
        try (Connection con = source.getConnection();
             PreparedStatement statement = con.prepareStatement(RELEASE_ALL_LOCKS_QUERY)) {
            statement.setString(1, lockOwner);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
        held.clear();
    }

    @Override
    public Duration lockRenewInterval() {
        return lockRenewInterval;
    }

    @Override
    public Duration lockLease() {
        return lockLease;
    }

    /** Throws why a conditioned write matched no row: the world is missing, or someone else holds it. */
    private void refuse(String worldName) throws UnknownWorldException, IOException {
        OptionalLong lockedUntil = lockedUntil(worldName);
        if (lockedUntil.isEmpty()) {
            throw new UnknownWorldException(worldName);
        }
        throw new WorldLockedException(worldName, Instant.ofEpochMilli(lockedUntil.getAsLong()));
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
            underSchemaLock(con, () -> {
                for (String step : List.of(CREATE_WORLDS_TABLE_QUERY, CREATE_VERSIONING_TABLE_QUERY,
                        CREATE_GENERATION_SEQUENCE_QUERY)) {
                    try (PreparedStatement statement = con.prepareStatement(step)) {
                        statement.execute();
                    }
                }
            });
        }
    }
}
