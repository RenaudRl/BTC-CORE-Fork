package dev.btc.core.integrity;

import dev.btc.core.integrity.sanction.SanctionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The name a sanction was issued under (task 1.2b of the panel's player sheet): added to the schema
 * without touching existing rows, written by the insert, nullable.
 *
 * <p>Runs the statements exactly as the service sends them, on H2 in PostgreSQL mode, like
 * {@link IdentityLinkClosureTest}. The partial index is skipped there because H2 has none.
 */
class SanctionPlayerNameTest {

    private static final String PREFIX = "test_";

    private Connection db;

    @BeforeEach
    void open() throws SQLException {
        db = DriverManager.getConnection(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private void applySchema() throws SQLException {
        try (Statement statement = db.createStatement()) {
            for (String sql : SanctionStore.schema(PREFIX)) {
                if (!sql.contains("WHERE")) { // H2 has no partial index
                    statement.execute(sql);
                }
            }
        }
    }

    /** Inserts one warning; {@code name} {@code null} writes SQL NULL. */
    private void insert(String name) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(SanctionStore.insertStatement(PREFIX))) {
            String[] columns = SanctionStore.INSERT_COLUMNS;
            for (int i = 0; i < columns.length; i++) {
                int index = i + 1;
                switch (columns[i]) {
                    case "player_uuid" -> statement.setString(index, UUID.randomUUID().toString());
                    case "type" -> statement.setString(index, "WARN");
                    case "scope" -> statement.setString(index, "NETWORK");
                    case "network_id" -> statement.setString(index, "btc");
                    case "reason" -> statement.setString(index, "test");
                    case "silent" -> statement.setBoolean(index, false);
                    case "actor_kind" -> statement.setString(index, "ANTICHEAT_AUTO");
                    case "created_at" -> statement.setLong(index, 1L);
                    case "player_name" -> {
                        if (name == null) {
                            statement.setNull(index, Types.VARCHAR);
                        } else {
                            statement.setString(index, name);
                        }
                    }
                    default -> statement.setNull(index, Types.VARCHAR);
                }
            }
            statement.executeUpdate();
        }
    }

    private String[] storedNames() throws SQLException {
        try (Statement statement = db.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT player_name FROM " + PREFIX + "sanctions ORDER BY id")) {
            java.util.List<String> names = new java.util.ArrayList<>();
            while (rows.next()) {
                names.add(rows.getString(1));
            }
            return names.toArray(String[]::new);
        }
    }

    @Test
    @DisplayName("the insert writes the name the sanction was issued under, and NULL when there is none")
    void insertWritesTheName() throws SQLException {
        assertTrue(Arrays.asList(SanctionStore.INSERT_COLUMNS).contains("player_name"),
            "the insert must name the player_name column");
        applySchema();

        insert("Steve");
        insert(null);

        assertEquals(Arrays.asList("Steve", null), Arrays.asList(storedNames()));
    }

    @Test
    @DisplayName("the column is added idempotently to a table that already holds sanctions")
    void migrationKeepsExistingRows() throws SQLException {
        // A table as the previous version created it: no player_name, one row already in it.
        try (Statement statement = db.createStatement()) {
            statement.execute("CREATE TABLE " + PREFIX + "sanctions ("
                + " id BIGSERIAL PRIMARY KEY, player_uuid VARCHAR(36) NULL, ip_address VARCHAR(45) NULL,"
                + " type VARCHAR(16) NOT NULL, scope VARCHAR(16) NOT NULL, network_id VARCHAR(128) NOT NULL,"
                + " server_id VARCHAR(128) NULL, reason TEXT NOT NULL, silent BOOLEAN NOT NULL DEFAULT FALSE,"
                + " actor_id VARCHAR(36) NULL, actor_kind VARCHAR(16) NOT NULL, evidence TEXT NULL,"
                + " created_at BIGINT NOT NULL, expires_at BIGINT NULL, revoked_at BIGINT NULL,"
                + " revoked_by VARCHAR(36) NULL, revoked_reason TEXT NULL)");
            statement.execute("INSERT INTO " + PREFIX + "sanctions (player_uuid, type, scope, network_id,"
                + " reason, actor_kind, created_at) VALUES ('" + UUID.randomUUID()
                + "', 'WARN', 'NETWORK', 'btc', 'old', 'ANTICHEAT_AUTO', 1)");
        }

        applySchema();
        applySchema(); // a second start must change nothing and fail on nothing

        assertNull(storedNames()[0], "a sanction from before the column existed has no name noted");
        insert("Alex");
        assertEquals(Arrays.asList(null, "Alex"), Arrays.asList(storedNames()));
    }
}
