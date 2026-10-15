package dev.btc.core.mining;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which arm swings the click-rate limiter leaves alone: those of a dig. */
class MiningSwingTest {

    @Test
    @DisplayName("a swing during a dig is never counted, however long the dig lasts")
    void swingDuringADig() {
        assertTrue(MiningSwing.isMining(true, 1_000L, MiningSwing.NEVER));
        assertTrue(MiningSwing.isMining(true, 1_000L, 0L));
    }

    @Test
    @DisplayName("the swings of the pause after a dig are still the dig's")
    void swingsOfThePauseAfterADig() {
        assertTrue(MiningSwing.isMining(false, 100L, 100L));
        assertTrue(MiningSwing.isMining(false, 100L + MiningSwing.GRACE_TICKS, 100L));
    }

    @Test
    @DisplayName("past the pause a swing is a click again: an autoclicker is counted")
    void swingAfterThePause() {
        assertFalse(MiningSwing.isMining(false, 100L + MiningSwing.GRACE_TICKS + 1, 100L));
    }

    @Test
    @DisplayName("a player who never dug is counted from the first swing")
    void neverDug() {
        assertFalse(MiningSwing.isMining(false, 0L, MiningSwing.NEVER));
        assertFalse(MiningSwing.isMining(false, Long.MAX_VALUE / 2, MiningSwing.NEVER));
    }
}
