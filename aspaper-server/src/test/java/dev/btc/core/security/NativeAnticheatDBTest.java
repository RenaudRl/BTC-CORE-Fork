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
    void aLongerValueIsClippedToTheColumnAndMarked() {
        final String clipped = NativeAnticheatDB.clip("y".repeat(400), NativeAnticheatDB.DETAILS_WIDTH);
        assertEquals(NativeAnticheatDB.DETAILS_WIDTH, clipped.length());
        assertTrue(clipped.endsWith("…"), "a clipped line says it was clipped");
    }
}
