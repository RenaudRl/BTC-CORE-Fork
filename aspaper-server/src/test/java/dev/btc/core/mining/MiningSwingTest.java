package dev.btc.core.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which ceiling the click-rate limiter applies to an arm swing: the mining one, only near a server-seen dig. */
class MiningSwingTest {

    private static final int MAX = 20;
    private static final int MINING_MAX = 30;

    @Test
    @DisplayName("the swings of the dig tick and of the pause after it are the dig's")
    void swingsOfTheDigAndItsPause() {
        assertTrue(MiningSwing.isMining(100L, 100L));
        assertTrue(MiningSwing.isMining(100L + MiningSwing.GRACE_TICKS, 100L));
    }

    @Test
    @DisplayName("past the pause a swing is a click again: an autoclicker is counted at the normal ceiling")
    void swingAfterThePause() {
        assertFalse(MiningSwing.isMining(100L + MiningSwing.GRACE_TICKS + 1, 100L));
    }

    @Test
    @DisplayName("a player the server never saw dig has the normal ceiling from the first swing")
    void neverDug() {
        assertFalse(MiningSwing.isMining(0L, MiningSwing.NEVER));
        assertFalse(MiningSwing.isMining(Long.MAX_VALUE / 2, MiningSwing.NEVER));
        assertEquals(MAX, MiningSwing.limit(0L, MiningSwing.NEVER, MAX, MINING_MAX));
    }

    @Test
    @DisplayName("a dig stamped in the future (clock gone back) opens no window")
    void negativeDeltaIsNotMining() {
        assertFalse(MiningSwing.isMining(100L, 101L));
        assertFalse(MiningSwing.isMining(0L, Long.MAX_VALUE / 2));
        assertEquals(MAX, MiningSwing.limit(100L, 5_000L, MAX, MINING_MAX));
    }

    @Test
    @DisplayName("one START and then only swings: the window closes by itself, no swing renews it")
    void startOnceThenFloodDoesNotKeepTheWindowOpen() {
        final long startTick = 100L;
        // the swings never stamp lastDig, so lastDig stays startTick however many arrive
        for (long tick = startTick; tick <= startTick + 1_000L; tick++) {
            final int expected = tick <= startTick + MiningSwing.GRACE_TICKS ? MINING_MAX : MAX;
            assertEquals(expected, MiningSwing.limit(tick, startTick, MAX, MINING_MAX), "tick " + tick);
        }
    }

    @Test
    @DisplayName("the mining ceiling never lowers the normal one")
    void miningMaxBelowMaxIsIgnored() {
        assertEquals(MAX, MiningSwing.limit(100L, 100L, MAX, 10));
    }
}
