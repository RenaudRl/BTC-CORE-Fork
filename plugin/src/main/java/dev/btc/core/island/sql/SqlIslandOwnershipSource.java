package dev.btc.core.island.sql;

import dev.btc.core.api.island.IslandKey;
import dev.btc.core.api.island.IslandLease;
import dev.btc.core.api.island.IslandOwnershipSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The SQL-backed authority on island ownership, on PostgreSQL or MySQL.
 *
 * <p>Takes a {@link DataSource} rather than opening its own pool: the plugin already runs one for
 * the world loaders, and a second pool would be a second thing to size, monitor and exhaust.
 *
 * <p>Every method fails closed. A lost connection reads as "this backend may not advance this
 * island", never as "nobody owns it" — the cost of a refusal is some lost offline progression, the
 * cost of a wrong grant is the same island produced twice on two backends.
 *
 * <p><b>Not reachable yet, and deliberately left that way.</b> Nothing in this fork constructs this
 * class, and — more to the point — no statement anywhere inserts a row into {@code
 * btc_island_ownership}: {@link IslandOwnershipSchema} only ever creates, selects and updates it.
 * Bound as it stands, {@link #resolve} would answer empty for every world and quietly turn island
 * catch-up off. Making this the bound source is therefore a two-part job — a writer that registers
 * an island when its world is provisioned, and a perimeter richer than origin-plus-radius, since a
 * game mode may own an arbitrary set of chunks rather than a square. Until both exist, the game
 * extension binds its own single-backend source instead.
 */
public final class SqlIslandOwnershipSource implements IslandOwnershipSource {

    private static final Logger LOGGER = Logger.getLogger("BTCCore/IslandOwnership");

    /**
     * How long a claim stays valid.
     *
     * <p>Long enough that a slow activation does not lose its own lease mid-flight, short enough
     * that a backend killed with {@code SIGKILL} does not strand its islands for minutes. A crashed
     * backend's islands become claimable again once this elapses; the fencing token is what stops
     * the dead backend from committing if it comes back.
     */
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(90);

    private final DataSource dataSource;
    private final Duration leaseDuration;
    private final SqlDialect dialect;
    private final IslandOwnershipSchema schema;

    public SqlIslandOwnershipSource(DataSource dataSource) throws SQLException {
        this(dataSource, DEFAULT_LEASE_DURATION);
    }

    public SqlIslandOwnershipSource(DataSource dataSource, Duration leaseDuration) throws SQLException {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        try (Connection connection = dataSource.getConnection()) {
            this.dialect = SqlDialect.detect(connection);
            this.schema = new IslandOwnershipSchema(dialect);
            createSchema(connection);
        }
        LOGGER.info(() -> "Island ownership store ready on " + dialect);
    }

    /**
     * The flavour detected on the connection this source was built from.
     *
     * @return the dialect in use
     */
    public SqlDialect dialect() {
        return dialect;
    }

    /**
     * The statements this source was built with, so a journal can share them.
     *
     * @return the schema
     */
    IslandOwnershipSchema schema() {
        return schema;
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(schema.createOwnershipTable);
            statement.execute(schema.createJournalTable);
            if (schema.createJournalIndex != null) {
                statement.execute(schema.createJournalIndex);
            }
        }
    }

    @Override
    public Optional<IslandKey> resolve(String worldName) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(IslandOwnershipSchema.SELECT_BY_WORLD)) {
            statement.setString(1, worldName);
            try (ResultSet set = statement.executeQuery()) {
                if (!set.next()) {
                    return Optional.empty();
                }
                return Optional.of(new IslandKey(worldName, UUID.fromString(set.getString(1).trim())));
            }
        } catch (SQLException | IllegalArgumentException exception) {
            LOGGER.log(Level.WARNING, exception, () -> "Could not resolve island for world '" + worldName + '\'');
            return Optional.empty();
        }
    }

    @Override
    public Optional<IslandLease> claim(IslandKey island, String backendId) {
        long now = System.currentTimeMillis();
        long expiresAt = now + leaseDuration.toMillis();

        // One transaction so the bump and the read of the bumped token cannot interleave with a
        // competing backend: the UPDATE takes the row lock, the SELECT reads under it.
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement statement = connection.prepareStatement(IslandOwnershipSchema.CLAIM)) {
                    statement.setString(1, backendId);
                    statement.setLong(2, expiresAt);
                    statement.setString(3, island.islandId().toString());
                    statement.setString(4, backendId);
                    statement.setLong(5, now);
                    updated = statement.executeUpdate();
                }
                if (updated == 0) {
                    connection.rollback();
                    return Optional.empty();
                }

                long token;
                try (PreparedStatement statement = connection.prepareStatement(IslandOwnershipSchema.SELECT_TOKEN)) {
                    statement.setString(1, island.islandId().toString());
                    try (ResultSet set = statement.executeQuery()) {
                        if (!set.next()) {
                            connection.rollback();
                            return Optional.empty();
                        }
                        token = set.getLong(1);
                    }
                }
                connection.commit();
                return Optional.of(new IslandLease(backendId, token, Instant.ofEpochMilli(expiresAt)));
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.WARNING, exception, () -> "Could not claim " + island);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Long> lastCommittedEpochMillis(IslandKey island) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(IslandOwnershipSchema.SELECT_LAST_COMMITTED)) {
            statement.setString(1, island.islandId().toString());
            try (ResultSet set = statement.executeQuery()) {
                if (!set.next()) {
                    return Optional.empty();
                }
                long value = set.getLong(1);
                return set.wasNull() ? Optional.empty() : Optional.of(value);
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.WARNING, exception, () -> "Could not read the last commit of " + island);
            return Optional.empty();
        }
    }

    @Override
    public boolean commit(IslandKey island, IslandLease lease, long toEpochMillis) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(IslandOwnershipSchema.COMMIT)) {
            statement.setLong(1, toEpochMillis);
            statement.setString(2, island.islandId().toString());
            statement.setString(3, lease.backendId());
            statement.setLong(4, lease.fencingToken());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            // Refusing here replays the window rather than losing it: the handlers are idempotent
            // per operation id, so a replay costs a second pass, while a false commit loses the work.
            LOGGER.log(Level.WARNING, exception, () -> "Could not commit " + island);
            return false;
        }
    }

    @Override
    public void abandon(IslandKey island, IslandLease lease) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(IslandOwnershipSchema.ABANDON)) {
            statement.setString(1, island.islandId().toString());
            statement.setString(2, lease.backendId());
            statement.setLong(3, lease.fencingToken());
            statement.executeUpdate();
        } catch (SQLException exception) {
            // Nothing to do about it: the lease expires on its own, which is exactly the case that
            // timeout exists for.
            LOGGER.log(Level.WARNING, exception, () -> "Could not release the lease on " + island);
        }
    }

    @Override
    public boolean ownsChunk(IslandKey island, int chunkX, int chunkZ) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(IslandOwnershipSchema.SELECT_PERIMETER)) {
            statement.setString(1, island.islandId().toString());
            try (ResultSet set = statement.executeQuery()) {
                if (!set.next()) {
                    return false;
                }
                int originX = set.getInt(1);
                int originZ = set.getInt(2);
                int radius = set.getInt(3);
                return Math.abs(chunkX - originX) <= radius && Math.abs(chunkZ - originZ) <= radius;
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.WARNING, exception,
                () -> "Could not read the perimeter of " + island + "; treating the chunk as outside it");
            return false;
        }
    }
}
