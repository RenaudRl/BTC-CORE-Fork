package dev.btc.core.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decisions behind the dig hook: which factor wins, when a block is refused, when the client is
 * told.
 *
 * <p>The hook itself needs a player, a connection and an attribute instance; what it decides does
 * not, and that is where a mistake would stay invisible in game — a table multiplied onto a curve
 * that already contains it is a speed that merely feels slightly wrong.
 */
class BreakSpeedFactorTest {

    private static final double EPSILON = 1.0e-12;

    @Test
    @DisplayName("a provider factor replaces the tables instead of multiplying them")
    void providerFactorReplacesTheTables() {
        BreakSpeedFactor.Resolution resolution = BreakSpeedFactor.resolve(0.4, OptionalDouble.of(2.5));

        assertEquals(2.5, resolution.factor(), EPSILON);
        assertFalse(resolution.refused());
        // Counter-check: multiplying would have given 1.0, which is not what was answered.
        assertTrue(Math.abs(resolution.factor() - 0.4 * 2.5) > 0.1);
    }

    @Test
    @DisplayName("a provider that declines, or no provider at all, leaves the tables alone")
    void declineKeepsTheTables() {
        BreakSpeedFactor.Resolution resolution = BreakSpeedFactor.resolve(0.4, OptionalDouble.empty());

        assertEquals(0.4, resolution.factor(), EPSILON);
        assertFalse(resolution.refused());
    }

    @Test
    @DisplayName("a factor of zero refuses the block and writes exactly zero")
    void zeroRefusesTheBlock() {
        BreakSpeedFactor.Resolution resolution = BreakSpeedFactor.resolve(1.0, OptionalDouble.of(0.0));

        assertTrue(resolution.refused());
        assertEquals(0.0, resolution.factor(), 0.0);
        assertEquals(-1.0, BreakSpeedFactor.modifierAmount(resolution.factor()), 0.0);
    }

    @Test
    @DisplayName("a negative factor is a refusal too, never a negative speed")
    void negativeIsClampedToRefusal() {
        BreakSpeedFactor.Resolution resolution = BreakSpeedFactor.resolve(1.0, OptionalDouble.of(-3.0));

        assertTrue(resolution.refused());
        assertEquals(0.0, resolution.factor(), 0.0);
    }

    @Test
    @DisplayName("NaN and infinity are declined, not written to the attribute")
    void unusableAnswersAreDeclined() {
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            BreakSpeedFactor.Resolution resolution = BreakSpeedFactor.resolve(0.4, OptionalDouble.of(bad));

            assertFalse(BreakSpeedFactor.isUsable(bad));
            assertFalse(resolution.refused());
            assertEquals(0.4, resolution.factor(), EPSILON);
        }
    }

    @Test
    @DisplayName("a tiny positive factor is a slow dig, not a refusal")
    void tinyPositiveIsNotARefusal() {
        BreakSpeedFactor.Resolution resolution = BreakSpeedFactor.resolve(1.0, OptionalDouble.of(0.001));

        assertFalse(resolution.refused());
        assertEquals(0.001, resolution.factor(), EPSILON);
    }

    @Test
    @DisplayName("the modifier amount is the factor minus one, because the operation scales the total")
    void modifierAmountIsFactorMinusOne() {
        assertEquals(0.0, BreakSpeedFactor.modifierAmount(1.0), 0.0);
        assertEquals(1.5, BreakSpeedFactor.modifierAmount(2.5), EPSILON);
        assertEquals(-0.6, BreakSpeedFactor.modifierAmount(0.4), EPSILON);
    }

    @Test
    @DisplayName("a provider that throws is declined and reported, never propagated")
    void aThrowingProviderIsDeclined() {
        java.util.List<Throwable> reported = new java.util.ArrayList<>();
        IllegalStateException boom = new IllegalStateException("boom");

        OptionalDouble answer = BreakSpeedFactor.askProvider(() -> {
            throw boom;
        }, reported::add);

        assertTrue(answer.isEmpty());
        assertEquals(java.util.List.of(boom), reported);
        // A linkage error from a half-loaded plugin is declined as well.
        assertTrue(BreakSpeedFactor.askProvider(() -> {
            throw new NoClassDefFoundError("x");
        }, reported::add).isEmpty());
        assertEquals(2, reported.size());
    }

    @Test
    @DisplayName("a fatal VM error is rethrown, not swallowed")
    void aFatalErrorIsRethrown() {
        java.util.List<Throwable> reported = new java.util.ArrayList<>();

        org.junit.jupiter.api.Assertions.assertThrows(OutOfMemoryError.class,
            () -> BreakSpeedFactor.askProvider(() -> {
                throw new OutOfMemoryError("heap");
            }, reported::add));
        assertTrue(reported.isEmpty());
    }

    @Test
    @DisplayName("a healthy answer passes through untouched, a null answer is a decline")
    void healthyAndNullAnswers() {
        java.util.List<Throwable> reported = new java.util.ArrayList<>();

        assertEquals(2.5, BreakSpeedFactor.askProvider(() -> OptionalDouble.of(2.5), reported::add).getAsDouble(), EPSILON);
        assertEquals(0.0, BreakSpeedFactor.askProvider(() -> OptionalDouble.of(0.0), reported::add).getAsDouble(), 0.0);
        assertTrue(BreakSpeedFactor.askProvider(() -> null, reported::add).isEmpty());
        assertTrue(reported.isEmpty());
    }

    @Test
    @DisplayName("a provider class is reported once, a different class is reported on its own")
    void failureIsReportedOncePerClass() {
        java.util.Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();

        assertTrue(BreakSpeedFactor.isFirstFailureOf(seen, String.class));
        assertFalse(BreakSpeedFactor.isFirstFailureOf(seen, String.class));
        assertTrue(BreakSpeedFactor.isFirstFailureOf(seen, Integer.class));
    }

    @Test
    @DisplayName("the client is not sent a factor it already holds")
    void unchangedFactorSendsNothing() {
        // A refusal repeated: the modifier already sits at -1, as it does after the first START.
        assertFalse(BreakSpeedFactor.needsPacket(-1.0, 0.0));
        // An ordinary factor repeated.
        assertFalse(BreakSpeedFactor.needsPacket(1.5, 2.5));
        // Nothing on the attribute and nothing to write: the client holds the unmodified value.
        assertFalse(BreakSpeedFactor.needsPacket(null, 1.0));
    }

    @Test
    @DisplayName("every change of factor is sent, including to and from a refusal and to neutral")
    void changedFactorIsSent() {
        assertTrue(BreakSpeedFactor.needsPacket(null, 0.0));
        assertTrue(BreakSpeedFactor.needsPacket(null, 2.5));
        assertTrue(BreakSpeedFactor.needsPacket(-1.0, 2.5));
        assertTrue(BreakSpeedFactor.needsPacket(1.5, 0.0));
        // Back to neutral after a refusal: the modifier has to go, and the client has to hear it.
        assertTrue(BreakSpeedFactor.needsPacket(-1.0, 1.0));
    }

    @Test
    @DisplayName("a gap below the epsilon is the same factor, a gap above it is not")
    void epsilonSeparatesNoiseFromChange() {
        assertFalse(BreakSpeedFactor.needsPacket(1.5, 2.5 + BreakSpeedFactor.SAME_FACTOR_EPSILON / 10));
        assertTrue(BreakSpeedFactor.needsPacket(1.5, 2.5 + BreakSpeedFactor.SAME_FACTOR_EPSILON * 10));
    }
}
