package dev.btc.core.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A detail wider than its column must be clipped, never sent whole: PostgreSQL refuses the line. */
class NativeAnticheatDBTest {

    @Test
    void aValueThatFitsIsKeptAsIs() {
        final String details = "x".repeat(NativeAnticheatDB.DETAILS_WIDTH);
        assertSame(details, NativeAnticheatDB.clip(details, NativeAnticheatDB.DETAILS_WIDTH));
    }

    @Test
    void aUuidStandingInForAnUnknownNameFitsThePlayerNameColumn() {
        // SentinelEngine and OpLockListener fall back to the UUID text, 36 characters, for player_name VARCHAR(16).
        final String uuid = "11111111-2222-3333-4444-555555555555";

        final NativeAnticheatDB.Row row = NativeAnticheatDB.Row.of(uuid, uuid, "admin:op-lock", "detail");

        assertEquals(NativeAnticheatDB.PLAYER_NAME_WIDTH, row.name().length());
        assertEquals(uuid, row.uuid(), "the uuid column keeps the whole identity");
    }

    @Test
    void everyFieldOfARowIsCutToItsColumn() {
        final NativeAnticheatDB.Row row = NativeAnticheatDB.Row.of(
            "u".repeat(80), "n".repeat(80), "c".repeat(80), "d".repeat(400));

        assertEquals(NativeAnticheatDB.PLAYER_UUID_WIDTH, row.uuid().length());
        assertEquals(NativeAnticheatDB.PLAYER_NAME_WIDTH, row.name().length());
        assertEquals(NativeAnticheatDB.CHECK_TYPE_WIDTH, row.checkType().length());
        assertEquals(NativeAnticheatDB.DETAILS_WIDTH, row.details().length());
    }

    @Test
    void aLongerValueIsClippedToTheColumnAndMarked() {
        final String clipped = NativeAnticheatDB.clip("y".repeat(400), NativeAnticheatDB.DETAILS_WIDTH);
        assertEquals(NativeAnticheatDB.DETAILS_WIDTH, clipped.length());
        assertTrue(clipped.endsWith("…"), "a clipped line says it was clipped");
    }
}
