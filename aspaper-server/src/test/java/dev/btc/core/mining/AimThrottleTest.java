package dev.btc.core.mining;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** When the server-driven mode looks again at what a player aims at (design 6.G.10, E2). */
class AimThrottleTest {

    @Test
    @DisplayName("a check is due once per interval")
    void dueOncePerInterval() {
        final AimThrottle throttle = new AimThrottle();

        assertTrue(throttle.isDue(0, 2));
        assertFalse(throttle.isDue(1, 2));
        assertTrue(throttle.isDue(2, 2));
        assertTrue(throttle.isDue(3, 1));
    }

    @Test
    @DisplayName("a player who does not move keeps the previous answer, for at most 20 ticks")
    void stillPoseReusesTheAnswer() {
        final AimThrottle throttle = new AimThrottle();

        assertTrue(throttle.needsLook(0, 1, 2, 3, 10F, 20F));
        throttle.record(true);
        assertFalse(throttle.needsLook(5, 1, 2, 3, 10F, 20F));
        assertTrue(throttle.lastHit());
        assertTrue(throttle.needsLook(AimThrottle.MAX_REUSE_TICKS, 1, 2, 3, 10F, 20F));
    }

    @Test
    @DisplayName("moving or turning asks for a new look")
    void moveOrTurnAsksAgain() {
        final AimThrottle throttle = new AimThrottle();
        throttle.needsLook(0, 1, 2, 3, 10F, 20F);

        assertTrue(throttle.needsLook(1, 1.01, 2, 3, 10F, 20F));
        assertTrue(throttle.needsLook(2, 1.01, 2, 3, 11F, 20F));
        assertTrue(throttle.needsLook(3, 1.01, 2, 3, 11F, 21F));
        assertFalse(throttle.needsLook(4, 1.01, 2, 3, 11F, 21F));
    }

    @Test
    @DisplayName("reset forgets the pose and the answer")
    void resetForgets() {
        final AimThrottle throttle = new AimThrottle();
        throttle.isDue(0, 20);
        throttle.needsLook(0, 1, 2, 3, 10F, 20F);
        throttle.record(true);

        throttle.reset();

        assertFalse(throttle.lastHit());
        assertTrue(throttle.isDue(1, 20));
        assertTrue(throttle.needsLook(1, 1, 2, 3, 10F, 20F));
    }
}
