package dev.btc.core.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.btc.core.api.mining.BreakDecision;
import dev.btc.core.api.mining.BreakSpeedProvider;
import dev.btc.core.api.mining.ServerDrivenBlocks;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How the platform reads a provider's {@link BreakDecision} (design 6.G.10, §3 and §10): what is
 * followed, what is declined, what is refused, and who may be driven by the server.
 */
class BreakDecisionTest {

    private static final double EPSILON = 1.0e-12;
    private static final double TABLE = 0.4;

    @Nested
    @DisplayName("decide")
    class Decide {

        @Test
        @DisplayName("a decline, or no answer, keeps the tables")
        void declineKeepsTheTables() {
            for (BreakDecision decision : new BreakDecision[] {BreakDecision.declined(), null}) {
                final BreakSpeedFactor.Verdict verdict = BreakSpeedFactor.decide(TABLE, decision, true);
                assertEquals(BreakSpeedFactor.Kind.FACTOR, verdict.kind());
                assertEquals(TABLE, verdict.factor(), EPSILON);
                assertEquals(BreakSpeedFactor.Warning.NONE, verdict.warning());
            }
        }

        @Test
        @DisplayName("a refusal is a factor of 0 and stops the dig")
        void refusal() {
            final BreakSpeedFactor.Verdict verdict = BreakSpeedFactor.decide(TABLE, BreakDecision.refused(), true);

            assertTrue(verdict.refused());
            assertEquals(0.0, verdict.factor(), EPSILON);
        }

        @Test
        @DisplayName("a factor replaces the tables; zero or less refuses; a non-number is declined and reported")
        void factor() {
            assertEquals(2.5, BreakSpeedFactor.decide(TABLE, BreakDecision.factor(2.5), true).factor(), EPSILON);
            assertTrue(BreakSpeedFactor.decide(TABLE, BreakDecision.factor(0.0), true).refused());
            assertTrue(BreakSpeedFactor.decide(TABLE, BreakDecision.factor(-3.0), true).refused());
            for (double unusable : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                final BreakSpeedFactor.Verdict verdict = BreakSpeedFactor.decide(TABLE, BreakDecision.factor(unusable), true);
                assertEquals(BreakSpeedFactor.Kind.FACTOR, verdict.kind());
                assertEquals(TABLE, verdict.factor(), EPSILON);
                assertEquals(BreakSpeedFactor.Warning.UNUSABLE_NUMBER, verdict.warning());
            }
        }

        @Test
        @DisplayName("an allowed server-driven rate is followed, with no platform factor on the attribute")
        void serverDrivenFollowed() {
            final BreakSpeedFactor.Verdict verdict = BreakSpeedFactor.decide(TABLE, BreakDecision.serverDriven(0.01), true);

            assertEquals(BreakSpeedFactor.Kind.SERVER_DRIVEN, verdict.kind());
            assertEquals(1.0, verdict.factor(), EPSILON);
            assertEquals(0.01, verdict.progressPerTick(), EPSILON);
        }

        @Test
        @DisplayName("a server-driven rate where it is not allowed is a decline, reported once")
        void serverDrivenNotAllowed() {
            final BreakSpeedFactor.Verdict verdict = BreakSpeedFactor.decide(TABLE, BreakDecision.serverDriven(0.01), false);

            assertEquals(BreakSpeedFactor.Kind.FACTOR, verdict.kind());
            assertEquals(TABLE, verdict.factor(), EPSILON);
            assertEquals(BreakSpeedFactor.Warning.SERVER_DRIVEN_NOT_ALLOWED, verdict.warning());
        }

        @Test
        @DisplayName("a server-driven rate of zero or less refuses, even where it is not allowed")
        void serverDrivenZeroRefuses() {
            assertTrue(BreakSpeedFactor.decide(TABLE, BreakDecision.serverDriven(0.0), true).refused());
            assertTrue(BreakSpeedFactor.decide(TABLE, BreakDecision.serverDriven(-1.0), false).refused());
        }

        @Test
        @DisplayName("a server-driven non-number (a division by a zero hardness) is declined")
        void serverDrivenNonNumber() {
            for (double unusable : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                final BreakSpeedFactor.Verdict verdict = BreakSpeedFactor.decide(TABLE, BreakDecision.serverDriven(unusable), true);
                assertEquals(BreakSpeedFactor.Kind.FACTOR, verdict.kind());
                assertEquals(BreakSpeedFactor.Warning.UNUSABLE_NUMBER, verdict.warning());
            }
        }
    }

    @Nested
    @DisplayName("who may be driven by the server")
    class Allowed {

        @Test
        @DisplayName("a vanilla block of hardness 0, switch on, player not opted out")
        void allowedCase() {
            assertTrue(BreakSpeedFactor.serverDrivenAllowed(true, false, "minecraft", 0.0F));
        }

        @Test
        @DisplayName("not with the switch off, an opted-out player, another namespace or a real hardness")
        void refusedCases() {
            assertFalse(BreakSpeedFactor.serverDrivenAllowed(false, false, "minecraft", 0.0F));
            assertFalse(BreakSpeedFactor.serverDrivenAllowed(true, true, "minecraft", 0.0F));
            assertFalse(BreakSpeedFactor.serverDrivenAllowed(true, false, "craftengine", 0.0F));
            assertFalse(BreakSpeedFactor.serverDrivenAllowed(true, false, "minecraft", 0.5F));
            assertFalse(BreakSpeedFactor.serverDrivenAllowed(true, false, "minecraft", -1.0F));
        }

        @Test
        @DisplayName("the platform never finishes a CraftEngine block")
        void neverFinishesCraftEngine() {
            assertFalse(BreakSpeedFactor.platformMayFinish("craftengine"));
            assertTrue(BreakSpeedFactor.platformMayFinish("minecraft"));
        }
    }

    @Nested
    @DisplayName("the mode is inert outside the published set")
    class InertOutsideTheSet {

        @Test
        @DisplayName("the clock drives only a published, platform-finishable block; leaves, hay, dirt are not")
        void clockDrivesOnlyThePublishedSet() {
            assertTrue(BreakSpeedFactor.clockMayDrive(true, "minecraft"));
            // Not in the set: a vanilla block with a hoe in hand keeps the normal dig.
            assertFalse(BreakSpeedFactor.clockMayDrive(false, "minecraft"));
            // In the set but another plugin's: armed for the view, never finished by the platform.
            assertFalse(BreakSpeedFactor.clockMayDrive(true, "craftengine"));
            assertFalse(BreakSpeedFactor.clockMayDrive(false, "craftengine"));
        }

        @Test
        @DisplayName("the zero view needs the aim on a published block; a held item alone arms nothing")
        void zeroViewNeedsTheAim() {
            assertTrue(BreakSpeedFactor.zeroViewWanted(true, false));
            assertFalse(BreakSpeedFactor.zeroViewWanted(false, false));
        }

        @Test
        @DisplayName("no zero view while a dig of a block outside the set is under way")
        void noZeroViewOverANormalDig() {
            assertFalse(BreakSpeedFactor.zeroViewWanted(true, true));
            assertFalse(BreakSpeedFactor.zeroViewWanted(false, true));
        }
    }

    @Nested
    @DisplayName("asking the provider")
    class Asking {

        @Test
        @DisplayName("a throw or a null answer is a decline; the failure is reported")
        void failureIsADecline() {
            final AtomicReference<Throwable> reported = new AtomicReference<>();

            assertInstanceOf(BreakDecision.Declined.class, BreakSpeedFactor.askDecision(() -> {
                throw new IllegalStateException("boom");
            }, reported::set));
            assertInstanceOf(IllegalStateException.class, reported.get());
            assertInstanceOf(BreakDecision.Declined.class, BreakSpeedFactor.askDecision(() -> null, reported::set));
        }

        @Test
        @DisplayName("a VirtualMachineError is not swallowed")
        void fatalIsRethrown() {
            assertThrows(StackOverflowError.class, () -> BreakSpeedFactor.askDecision(() -> {
                throw new StackOverflowError();
            }, failure -> { }));
        }

        @Test
        @DisplayName("a provider written before decide() keeps working through statisticMultiplier")
        void legacyProviderIsWrapped() {
            final BreakSpeedProvider declines = context -> OptionalDouble.empty();
            final BreakSpeedProvider answers = context -> OptionalDouble.of(1.7);
            final BreakSpeedProvider nulls = context -> null;

            assertInstanceOf(BreakDecision.Declined.class, declines.decide(null));
            assertEquals(1.7, assertInstanceOf(BreakDecision.Factor.class, answers.decide(null)).factor(), EPSILON);
            assertInstanceOf(BreakDecision.Declined.class, nulls.decide(null));
        }

        @Test
        @DisplayName("decisions compare by value")
        void valueEquality() {
            assertEquals(BreakDecision.declined(), BreakDecision.declined());
            assertEquals(BreakDecision.serverDriven(0.5), new BreakDecision.ServerDriven(0.5));
            assertSame(BreakDecision.Declined.class, BreakDecision.declined().getClass());
        }
    }

    @Nested
    @DisplayName("the published set and its timings")
    class Published {

        @Test
        @DisplayName("default timings: 2 s of linger, checks every 2 ticks, radius 1, heartbeat and pause 5")
        void defaults() {
            final ServerDrivenBlocks.Timing timing = ServerDrivenBlocks.Timing.DEFAULT;

            assertEquals(40, timing.lingerTicks());
            assertEquals(2, timing.aimCheckIntervalTicks());
            assertEquals(1, timing.aimNeighbourRadius());
            assertEquals(5, timing.heartbeatTicks());
            assertEquals(5, timing.postBreakPauseTicks());
        }

        @Test
        @DisplayName("every bound is enforced (the caller keeps its previous timing)")
        void boundsAreEnforced() {
            assertThrows(IllegalArgumentException.class, () -> new ServerDrivenBlocks.Timing(4, 2, 1, 5, 5));
            assertThrows(IllegalArgumentException.class, () -> new ServerDrivenBlocks.Timing(601, 2, 1, 5, 5));
            assertThrows(IllegalArgumentException.class, () -> new ServerDrivenBlocks.Timing(40, 0, 1, 5, 5));
            assertThrows(IllegalArgumentException.class, () -> new ServerDrivenBlocks.Timing(40, 21, 1, 5, 5));
            assertThrows(IllegalArgumentException.class, () -> new ServerDrivenBlocks.Timing(40, 2, -1, 5, 5));
            assertThrows(IllegalArgumentException.class, () -> new ServerDrivenBlocks.Timing(40, 2, 3, 5, 5));
            assertThrows(IllegalArgumentException.class, () -> new ServerDrivenBlocks.Timing(40, 2, 1, 1, 5));
            assertThrows(IllegalArgumentException.class, () -> new ServerDrivenBlocks.Timing(40, 2, 1, 41, 5));
            assertThrows(IllegalArgumentException.class, () -> new ServerDrivenBlocks.Timing(40, 2, 1, 5, -1));
            assertThrows(IllegalArgumentException.class, () -> new ServerDrivenBlocks.Timing(40, 2, 1, 5, 21));
            // The bounds themselves are valid.
            new ServerDrivenBlocks.Timing(5, 1, 0, 2, 0);
            new ServerDrivenBlocks.Timing(600, 20, 2, 40, 20);
        }

        @Test
        @DisplayName("an empty set is a clear; null parts are refused")
        void emptyAndNull() {
            assertTrue(new ServerDrivenBlocks(Set.of(), Set.of(), Set.of(), ServerDrivenBlocks.Timing.DEFAULT).isEmpty());
            assertThrows(NullPointerException.class,
                () -> new ServerDrivenBlocks(null, Set.of(), Set.of(), ServerDrivenBlocks.Timing.DEFAULT));
            assertThrows(NullPointerException.class,
                () -> new ServerDrivenBlocks(Set.of(), Set.of(), Set.of(), null));
        }
    }
}
