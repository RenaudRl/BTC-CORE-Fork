package dev.btc.core.island.sql;

import dev.btc.core.api.island.CatchUpJournal;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

/**
 * The SQL-backed operation journal, on PostgreSQL or MySQL.
 *
 * <p>Shares the {@link DataSource} and the schema with {@link SqlIslandOwnershipSource}, which
 * creates both tables; this class only writes to one of them. Built from that source rather than
 * from a bare data source so the two can never disagree on the dialect.
 *
 * <p>A write that collides on {@code (operation_id, system_key)} is reported as a replay rather than
 * an error, and the existing row is left untouched. That is what makes a crashed activation safe to
 * re-run: the second pass finds its own rows already present and the caller can tell the two apart.
 */
public final class SqlCatchUpJournal implements CatchUpJournal {

    private final DataSource dataSource;
    private final IslandOwnershipSchema schema;

    public SqlCatchUpJournal(DataSource dataSource, SqlIslandOwnershipSource ownershipSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schema = Objects.requireNonNull(ownershipSource, "ownershipSource").schema();
    }

    @Override
    public boolean record(Entry entry) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(schema.recordOperation)) {
            statement.setString(1, entry.operationId().toString());
            statement.setString(2, entry.systemKey());
            statement.setString(3, entry.island().islandId().toString());
            statement.setString(4, entry.island().worldName());
            statement.setLong(5, entry.fromEpochMillis());
            statement.setLong(6, entry.toEpochMillis());
            statement.setString(7, entry.backendId());
            statement.setLong(8, entry.fencingToken());
            statement.setString(9, entry.status().name());
            statement.setInt(10, entry.schemaVersion());
            statement.setInt(11, entry.operations());
            if (entry.resultHash() == null) {
                statement.setNull(12, Types.CHAR);
            } else {
                statement.setString(12, entry.resultHash());
            }
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            // Surfaced to the registry, which logs it and carries on: the world has already changed,
            // and refusing the commit over a bookkeeping failure would replay work that succeeded.
            throw new IllegalStateException("Could not journal catch-up operation " + entry.operationId()
                + '/' + entry.systemKey(), exception);
        }
    }
}
