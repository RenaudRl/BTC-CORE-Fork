package dev.btc.core.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tier curve behind the break-speed attribute.
 *
 * <p>The curve is the one part of the system that can be tested without a server: everything else
 * needs block tags, a player and a connection. It is also the part where a mistake is invisible in
 * game — a tier read one step off is a speed that feels slightly wrong and never announces itself.
 *
 * <p>Each expectation is followed by a counter-check where one exists, because a test that only
 * asserts what the implementation happens to do stays green whatever the implementation does.
 */
class TierCurveTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    @DisplayName("the default curve spans the twelve tiers of the design, 100 % to 500 %")
    void defaultCurveSpansTheDesign() {
        TierCurve curve = new TierCurve(TierCurve.DEFAULT);

        assertEquals(12, curve.size());
        assertEquals(1.0, curve.multiplier(1), EPSILON);
        assertEquals(5.0, curve.multiplier(12), EPSILON);

        // Counter-check: the curve is not a flat 1.0 that would pass the two assertions above by
        // accident at tier 1 and be inert everywhere else.
        assertNotEquals(1.0, curve.multiplier(6), EPSILON);
    }

    @Test
    @DisplayName("tiers are one-based — tier 2 reads the second step, not the third")
    void tiersAreOneBased() {
        TierCurve curve = new TierCurve(List.of(1.0, 2.0, 3.0));

        assertEquals(1.0, curve.multiplier(1), EPSILON);
        assertEquals(2.0, curve.multiplier(2), EPSILON);
        assertEquals(3.0, curve.multiplier(3), EPSILON);
    }

    @Test
    @DisplayName("a tier outside the curve is clamped, not refused and not wrapped")
    void tiersOutsideTheCurveAreClamped() {
        TierCurve curve = new TierCurve(List.of(1.0, 2.0, 3.0));

        assertEquals(1.0, curve.multiplier(0), EPSILON);
        assertEquals(1.0, curve.multiplier(-7), EPSILON);
        assertEquals(3.0, curve.multiplier(4), EPSILON);
        assertEquals(3.0, curve.multiplier(4096), EPSILON);
    }

    @Test
    @DisplayName("an empty curve is refused at construction rather than answering 1.0 forever")
    void anEmptyCurveIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new TierCurve(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TierCurve(null));
    }

    @Test
    @DisplayName("the curve keeps its own copy of the steps it was given")
    void theCurveCopiesItsSteps() {
        java.util.List<Double> mutable = new java.util.ArrayList<>(List.of(1.0, 2.0));
        TierCurve curve = new TierCurve(mutable);

        mutable.set(1, 99.0);

        assertEquals(2.0, curve.multiplier(2), EPSILON);
    }
}
