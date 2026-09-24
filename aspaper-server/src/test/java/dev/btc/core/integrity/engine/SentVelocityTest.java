package dev.btc.core.integrity.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Tests which vector the engine records when {@code PlayerVelocityEvent} fires.
 *
 * <p>The defect guarded here was measured on the 26.3 bench on 24/09/2026: on the attack path
 * ({@code Player#attack}), CraftBukkit fires the event with the target's velocity from <em>before</em>
 * the hit, while the packet it then sends carries the knockback. 217 honest {@code speed} lines in ten
 * minutes, each "moved 0.400" against a bound that had never seen the 0.4 knockback.
 */
class SentVelocityTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("on the attack path the knockback read on the player wins over the stale event vector")
    void attackPathKeepsTheKnockback() {
        double[] walking = {0.05, -0.078, 0.0};
        double[] knockedBack = {0.4, 0.361, 0.0};

        assertArrayEquals(knockedBack, SentVelocity.of(walking, knockedBack), EPS);
    }

    @Test
    @DisplayName("on the ordinary path both vectors agree and that vector is kept")
    void ordinaryPathKeepsTheVector() {
        double[] velocity = {0.1, 0.42, -0.2};

        assertArrayEquals(velocity, SentVelocity.of(velocity, velocity.clone()), EPS);
    }

    @Test
    @DisplayName("a plugin that raised the event vector is honoured")
    void aRaisedEventVectorWins() {
        double[] raised = {1.5, 0.8, 0.0};
        double[] current = {0.4, 0.361, 0.0};

        assertArrayEquals(raised, SentVelocity.of(raised, current), EPS);
    }
}
