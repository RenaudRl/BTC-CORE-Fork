package dev.btc.core.stats.sql;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsMergeTest {

    private static final String DATABASE =
        "{\"stats\":{\"minecraft:custom\":{\"minecraft:mob_kills\":42,\"minecraft:jump\":10}},\"DataVersion\":4000}";
    private static final String FILE =
        "{\"stats\":{\"minecraft:custom\":{\"minecraft:mob_kills\":40,\"minecraft:jump\":12},\"minecraft:mined\":{\"minecraft:stone\":7}},\"DataVersion\":4001}";
    private static final String MERGED =
        "{\"stats\":{\"minecraft:custom\":{\"minecraft:mob_kills\":42,\"minecraft:jump\":12},\"minecraft:mined\":{\"minecraft:stone\":7}},\"DataVersion\":4001}";

    @Test
    void pendingIsMergedByMaxPerKey() {
        assertEquals(MERGED, StatsMerge.max(DATABASE, FILE));
    }

    @Test
    void recoveryIsIdempotent() {
        assertEquals(MERGED, StatsMerge.max(StatsMerge.max(DATABASE, FILE), FILE));
        assertEquals(MERGED, StatsMerge.max(MERGED, MERGED));
    }

    @Test
    void aMissingSideGivesTheOther() {
        assertEquals(FILE, StatsMerge.max(null, FILE));
        assertEquals(DATABASE, StatsMerge.max(DATABASE, null));
        assertNull(StatsMerge.max(null, null));
    }

    @Test
    void serviceSwitchDuringOutageMergesByMaxAndDocumentsKnownLoss() {
        String resetDuringOutage = "{\"stats\":{\"minecraft:custom\":{\"minecraft:mob_kills\":0}},\"DataVersion\":4000}";

        // Accepted limit (Q-A): the reset made while the database was down does not survive the merge.
        assertEquals(DATABASE, StatsMerge.max(DATABASE, resetDuringOutage));
    }

    @Test
    void corruptJsonIsRejectedNotMerged() {
        assertThrows(IllegalArgumentException.class, () -> StatsMerge.max(DATABASE, "{\"stats\":"));
        assertThrows(IllegalArgumentException.class, () -> StatsMerge.max("[]", FILE));
        assertEquals(4001, StatsMerge.dataVersion(FILE));
        assertEquals(0, StatsMerge.dataVersion("{\"stats\":{}}"));
    }

    @Test
    void everyStatementUsesParametersAndOnlyThePrefixIsConcatenated() throws IllegalAccessException {
        StatsSqlStatements prefixed = new StatsSqlStatements("zz_", "NOW_MS");
        StatsSqlStatements bare = new StatsSqlStatements("", "NOW_MS");
        int checked = 0;
        for (Field field : StatsSqlStatements.class.getDeclaredFields()) {
            if (field.getType() != String.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            String sql = (String) field.get(prefixed);
            assertEquals(field.get(bare), sql.replace("zz_player_stats", "player_stats"), field.getName() + ": only the table names carry the prefix");
            String literals = sql.replace("'runtime'", "").replace("'json-import'", "");
            assertFalse(literals.contains("'") || literals.contains("\"") || literals.contains(";") || literals.contains("--"), field.getName() + ": no literal but the two source constants");
            boolean takesValues = sql.startsWith("UPDATE") || sql.startsWith("INSERT") || sql.contains(" WHERE ");
            assertEquals(takesValues, sql.contains("?"), field.getName() + ": values only ever travel as parameters");
            checked++;
        }
        assertTrue(checked >= 16, "statements checked: " + checked);
    }
}
