package dev.btc.core.integrity;

import dev.btc.core.integrity.sanction.SanctionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the statements themselves.
 *
 * <p>These do not need a database, and the failures they catch are ones a database would only reveal in
 * production: a column that stopped being selected, a placeholder count that drifted from the column
 * list, a prefix honoured in the table name but forgotten in an index name.
 */
class SanctionStoreTest {

    private static final String PREFIX = "test_";

    /** Every column {@code SanctionService.read} pulls out of a row. */
    private static final String[] COLUMNS_THE_MAPPER_READS = {
        "id", "player_uuid", "ip_address", "type", "scope", "network_id", "server_id", "reason",
        "silent", "actor_id", "actor_kind", "evidence", "created_at", "expires_at",
        "revoked_at", "revoked_by", "revoked_reason"
    };

    @Test
    @DisplayName("the schema refuses an automatic ban in the database itself")
    void schemaForbidsAutomaticBan() {
        String create = SanctionStore.schema(PREFIX)[0];
        assertTrue(create.contains("ck_" + PREFIX + "sanctions_no_auto_ban"),
            "the constraint must be named, so a violation says which rule was broken");
        assertTrue(create.replaceAll("\\s+", " ")
                .contains("CHECK (NOT (type = 'BAN' AND actor_kind = 'ANTICHEAT_AUTO'))"),
            "the doctrine has to be enforced by the database, not only by the record");
    }

    @Test
    @DisplayName("both reads return every column the mapper consumes")
    void readsCoverTheMapper() {
        // The drift this catches: someone narrows a SELECT for a listing, and every field it stopped
        // returning silently becomes empty in the reconstructed sanction.
        for (String statement : new String[] {
            SanctionStore.historyStatement(PREFIX),
            SanctionStore.activeStatement(PREFIX)
        }) {
            String projection = statement.substring(0, statement.indexOf("FROM"));
            for (String column : COLUMNS_THE_MAPPER_READS) {
                assertTrue(projection.contains(column),
                    "the mapper reads '" + column + "' but this statement does not select it:\n" + statement);
            }
        }
    }

    @Test
    @DisplayName("the insert has exactly one placeholder per column")
    void insertPlaceholdersMatchColumns() {
        String insert = SanctionStore.insertStatement(PREFIX);
        long placeholders = insert.chars().filter(character -> character == '?').count();
        assertEquals(SanctionStore.INSERT_COLUMNS.length, placeholders,
            "a placeholder count that drifts from the column list shifts every value by one");

        for (String column : SanctionStore.INSERT_COLUMNS) {
            assertTrue(insert.contains(column), "insert does not name column '" + column + "'");
        }
    }

    @Test
    @DisplayName("the prefix reaches index names too, not just the table")
    void prefixReachesIndexes() {
        // Two servers sharing a database with different prefixes: the tables would be separate but the
        // index names would collide, and the second server's schema creation would fail.
        for (String statement : SanctionStore.schema(PREFIX)) {
            if (statement.contains("CREATE INDEX")) {
                assertTrue(statement.contains("idx_" + PREFIX),
                    "index name is not prefixed: " + statement);
            }
        }
    }

    @Test
    @DisplayName("the active lookup never returns revoked or expired rows")
    void activeExcludesLiftedAndLapsed() {
        String active = SanctionStore.activeStatement(PREFIX).replaceAll("\\s+", " ");
        assertTrue(active.contains("revoked_at IS NULL"), "a lifted sanction must not be enforced");
        assertTrue(active.contains("expires_at IS NULL OR expires_at > ?"),
            "a lapsed sanction must not be enforced");
    }

    @Test
    @DisplayName("the purge erases the address and nothing else")
    void purgeKeepsTheSanction() {
        String purge = SanctionStore.purgeAddressesStatement(PREFIX).replaceAll("\\s+", " ");
        assertTrue(purge.startsWith("UPDATE"), "retention erases a field; it never deletes a sanction");
        assertFalse(purge.contains("DELETE"), "the moderation history itself is never purged");
        assertTrue(purge.contains("SET ip_address = NULL"));
    }
}
