package dev.btc.core.integrity;

import dev.btc.core.integrity.sanction.IdentityLinkStore;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the alias closure (AD-090) and the alias-seeded active read against a real SQL engine.
 *
 * <p>H2 in PostgreSQL mode stands in for PostgreSQL: the statements are executed exactly as the
 * service sends them, so a closure that loops, stops one hop short or walks one hop too far fails
 * here rather than at a login. Two things H2 lacks are left out of the in-memory schema and covered
 * as text instead: the partial index (an optimisation, not a rule) and {@code ON CONFLICT DO UPDATE}.
 */
class IdentityLinkClosureTest {

    private static final String PREFIX = "test_";
    private static final String NETWORK = "btc";

    private Connection db;

    @BeforeEach
    void open() throws SQLException {
        db = DriverManager.getConnection(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        try (Statement statement = db.createStatement()) {
            for (String sql : IdentityLinkStore.schema(PREFIX)) {
                statement.execute(sql);
            }
            for (String sql : SanctionStore.schema(PREFIX)) {
                if (!sql.contains("WHERE")) { // H2 has no partial index
                    statement.execute(sql);
                }
            }
        }
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    @DisplayName("A–B–C: a Bedrock account relinked to another Java account joins one person")
    void closesTransitively() throws SQLException {
        UUID a = UUID.randomUUID(); // java
        UUID b = UUID.randomUUID(); // bedrock
        UUID c = UUID.randomUUID(); // java, linked to b after an unlink from a
        link(b, a);
        link(b, c);

        assertEquals(Set.of(a, b, c), aliases(a));
        assertEquals(Set.of(a, b, c), aliases(c), "the closure walks links in both directions");
    }

    @Test
    @DisplayName("the closure stops at four links from the seed")
    void depthIsBounded() throws SQLException {
        List<UUID> chain = new ArrayList<>();
        for (int i = 0; i <= IdentityLinkStore.MAX_LINK_DEPTH + 1; i++) {
            chain.add(UUID.randomUUID());
        }
        for (int i = 0; i + 1 < chain.size(); i++) {
            // Alternate roles so that the chain is a valid series of bedrock/java pairs.
            if (i % 2 == 0) {
                link(chain.get(i + 1), chain.get(i));
            } else {
                link(chain.get(i), chain.get(i + 1));
            }
        }

        Set<UUID> reached = aliases(chain.get(0));
        assertEquals(Set.copyOf(chain.subList(0, IdentityLinkStore.MAX_LINK_DEPTH + 1)), reached);
        assertFalse(reached.contains(chain.get(IdentityLinkStore.MAX_LINK_DEPTH + 1)),
            "a fifth link must not be followed");
    }

    @Test
    @DisplayName("a cycle of links terminates and yields each account once")
    void cyclesTerminate() throws SQLException {
        UUID bedrock1 = UUID.randomUUID();
        UUID bedrock2 = UUID.randomUUID();
        UUID java1 = UUID.randomUUID();
        UUID java2 = UUID.randomUUID();
        link(bedrock1, java1);
        link(bedrock2, java1);
        link(bedrock2, java2);
        link(bedrock1, java2); // closes the cycle

        List<UUID> rows = aliasRows(bedrock1);
        assertEquals(Set.of(bedrock1, bedrock2, java1, java2), Set.copyOf(rows));
        assertEquals(4, rows.size(), "DISTINCT must leave each account once");
    }

    @Test
    @DisplayName("an account with no link is only itself")
    void unlinkedIsAlone() throws SQLException {
        UUID alone = UUID.randomUUID();
        link(UUID.randomUUID(), UUID.randomUUID());
        assertEquals(Set.of(alone), aliases(alone));
    }

    @Test
    @DisplayName("a ban on the Bedrock account stops the Java account it was once linked to")
    void banFollowsTheJournal() throws SQLException {
        UUID bedrock = UUID.randomUUID();
        UUID java = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        ban(bedrock, false);
        link(bedrock, java);

        assertEquals(1, active(java).size(), "the link journal carries the ban to the Java account");
        assertEquals(1, active(bedrock).size(), "the target itself stays banned");
        assertTrue(active(stranger).isEmpty(), "nobody unrelated is touched");
    }

    @Test
    @DisplayName("the live Floodgate pair catches a first linked session not yet journaled")
    void livePairSeedsTheCheck() throws SQLException {
        UUID bedrock = UUID.randomUUID();
        UUID java = UUID.randomUUID();
        ban(bedrock, false);

        assertTrue(active(java).isEmpty(), "without the pair, the journal knows nothing yet");
        assertEquals(1, active(java, bedrock).size(), "seeded with the pair, the ban is found");
    }

    @Test
    @DisplayName("a revoked ban on an alias is not enforced")
    void revokedAliasBanIsIgnored() throws SQLException {
        UUID bedrock = UUID.randomUUID();
        UUID java = UUID.randomUUID();
        ban(bedrock, false);
        link(bedrock, java);

        // Positive control: without it, a broken closure would make the assertion below pass anyway.
        assertEquals(1, active(java).size(), "the ban must be seen through the alias before it is revoked");

        revokeAll();

        assertTrue(active(java).isEmpty(), "once revoked, the alias ban no longer applies");
        assertTrue(active(bedrock).isEmpty());
    }

    @Test
    @DisplayName("history reads through the aliases and keeps each row's own player_uuid")
    void historyCoversAliases() throws SQLException {
        UUID bedrock = UUID.randomUUID();
        UUID java = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        ban(bedrock, false);
        ban(java, false);
        ban(stranger, false);
        link(bedrock, java);

        List<UUID> rows = history(java);

        assertEquals(Set.of(bedrock, java), Set.copyOf(rows));
        assertEquals(2, rows.size());
        assertTrue(history(UUID.randomUUID()).isEmpty());
    }

    @Test
    @DisplayName("recording a known pair moves last_seen only, and nothing ever deletes a pair")
    void recordKeepsFirstSeen() {
        String record = IdentityLinkStore.recordStatement(PREFIX).replaceAll("\\s+", " ");
        assertTrue(record.contains("ON CONFLICT (bedrock_uuid, java_uuid) DO UPDATE SET last_seen = EXCLUDED.last_seen"));
        assertFalse(record.contains("first_seen = "), "first_seen is the date of the first sighting");
        assertEquals(4, record.chars().filter(character -> character == '?').count());
        for (String sql : IdentityLinkStore.schema(PREFIX)) {
            assertFalse(sql.contains("DROP") || sql.contains("DELETE"), "the migration is additive only");
            if (sql.contains("CREATE INDEX")) {
                assertTrue(sql.contains("idx_" + PREFIX), "index name is not prefixed: " + sql);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private void link(UUID bedrock, UUID java) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement("INSERT INTO " + PREFIX
            + "identity_links (bedrock_uuid, java_uuid, first_seen, last_seen) VALUES (?, ?, 0, 0)")) {
            statement.setString(1, bedrock.toString());
            statement.setString(2, java.toString());
            statement.executeUpdate();
        }
    }

    private void ban(UUID player, boolean revoked) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(SanctionStore.insertStatement(PREFIX))) {
            statement.setString(1, player.toString());
            statement.setString(2, null);
            statement.setString(3, "BAN");
            statement.setString(4, "SERVER");
            statement.setString(5, NETWORK);
            statement.setString(6, null);
            statement.setString(7, "test");
            statement.setBoolean(8, false);
            statement.setString(9, UUID.randomUUID().toString());
            statement.setString(10, "HUMAN");
            statement.setString(11, null);
            statement.setLong(12, 1L);
            statement.setNull(13, java.sql.Types.BIGINT);
            statement.executeUpdate();
        }
        if (revoked) {
            revokeAll();
        }
    }

    private void revokeAll() throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.executeUpdate("UPDATE " + PREFIX + "sanctions SET revoked_at = 2");
        }
    }

    private List<UUID> history(UUID seed) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(SanctionStore.historyStatement(PREFIX))) {
            statement.setArray(1, seeds(seed));
            statement.setInt(2, 50);
            try (ResultSet rows = statement.executeQuery()) {
                List<UUID> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(UUID.fromString(rows.getString("player_uuid")));
                }
                return found;
            }
        }
    }

    private Set<UUID> aliases(UUID seed) throws SQLException {
        return Set.copyOf(aliasRows(seed));
    }

    private List<UUID> aliasRows(UUID seed) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(IdentityLinkStore.aliasesStatement(PREFIX))) {
            statement.setArray(1, seeds(seed));
            return readUuids(statement);
        }
    }

    private List<UUID> active(UUID... seeds) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(SanctionStore.activeStatement(PREFIX))) {
            statement.setArray(1, seeds(seeds));
            statement.setString(2, NETWORK);
            statement.setLong(3, System.currentTimeMillis());
            try (ResultSet rows = statement.executeQuery()) {
                List<UUID> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(UUID.fromString(rows.getString("player_uuid")));
                }
                return found;
            }
        }
    }

    private java.sql.Array seeds(UUID... uuids) throws SQLException {
        Set<String> values = new HashSet<>();
        for (UUID uuid : uuids) {
            values.add(uuid.toString());
        }
        // Same call as SanctionService.uuidArray.
        return db.createArrayOf("varchar", values.toArray());
    }

    private static List<UUID> readUuids(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            List<UUID> found = new ArrayList<>();
            while (rows.next()) {
                found.add(UUID.fromString(rows.getString(1)));
            }
            return found;
        }
    }
}
