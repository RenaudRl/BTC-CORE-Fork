package dev.btc.core.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The clock of the server-driven mode: how the mode is entered, kept and left, and how a dig the
 * server measures adds up, pauses and ends — including what a forged client can and cannot get out of
 * it (design 6.G.10, §5 and §10).
 */
class ServerDigClockTest {

    private static final double EPSILON = 1.0e-12;
    private static final int HEARTBEAT = 5;
    private static final int LINGER = 40;
    private static final int PAUSE = 5;

    /** Advances one tick at a time from {@code from}, swinging at every tick, until the dig completes. */
    private static int ticksToComplete(final ServerDigClock clock, final long from, final double rate) {
        for (int i = 0; i < 10_000; i++) {
            final long now = from + i;
            clock.swing(now);
            if (clock.advance(now, rate, HEARTBEAT) == ServerDigClock.Step.COMPLETE) return i + 1;
        }
        return -1;
    }

    @Nested
    @DisplayName("the mode")
    class Mode {

        @Test
        @DisplayName("arming reports the entry once, then only extends the linger")
        void armReportsTheEntryOnce() {
            final ServerDigClock clock = new ServerDigClock();

            assertTrue(clock.arm(100, LINGER));
            assertFalse(clock.arm(110, LINGER));
            assertTrue(clock.isArmed());
            // Extended to 110 + 40: still on at 145, off at 150.
            assertFalse(clock.shouldDisarm(145, false));
            assertTrue(clock.shouldDisarm(150, false));
        }

        @Test
        @DisplayName("a shorter arm never shortens the linger already granted")
        void armNeverShortens() {
            final ServerDigClock clock = new ServerDigClock();
            clock.arm(100, 200);
            clock.arm(101, 5);

            assertFalse(clock.shouldDisarm(250, false));
            assertTrue(clock.shouldDisarm(300, false));
        }

        @Test
        @DisplayName("a dig the clock measures keeps the mode on past the linger")
        void digKeepsTheModeOn() {
            final ServerDigClock clock = new ServerDigClock();
            clock.arm(0, LINGER);
            clock.startDig(10);

            assertFalse(clock.shouldDisarm(1_000, true));
            // A dig the clock does not measure (vanilla) does not hold the mode.
            clock.abortDig();
            assertTrue(clock.shouldDisarm(1_000, true));
        }

        @Test
        @DisplayName("an unarmed clock never asks to disarm")
        void unarmedNeverDisarms() {
            assertFalse(new ServerDigClock().shouldDisarm(1_000, false));
        }

        @Test
        @DisplayName("reset forgets the mode, the dig and the pause")
        void resetForgetsEverything() {
            final ServerDigClock clock = new ServerDigClock();
            clock.arm(0, LINGER);
            clock.startDig(0);
            clock.finishDig(10, 20, LINGER);

            clock.reset();

            assertFalse(clock.isArmed());
            assertFalse(clock.isDigging());
            clock.startDig(11);
            // The pause of the break before the reset is gone.
            assertEquals(ServerDigClock.Step.STAGE_CHANGED, clock.advance(11, 0.5, HEARTBEAT));
        }
    }

    @Nested
    @DisplayName("the sum")
    class Sum {

        @Test
        @DisplayName("the tick of the START counts, as vanilla's rate x (elapsed + 1) does")
        void startTickCounts() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(10);

            assertEquals(ServerDigClock.Step.STAGE_CHANGED, clock.advance(10, 0.25, HEARTBEAT));
            assertEquals(0.25, clock.progress(), EPSILON);
        }

        @Test
        @DisplayName("a rate of 0.1 per tick breaks on the 10th tick, never before")
        void breaksAfterOneOverRateTicks() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(0);

            assertEquals(10, ticksToComplete(clock, 0, 0.1 + 1.0e-9));
        }

        @Test
        @DisplayName("a rate of 1/100 (5 s) takes 100 ticks")
        void fiveSecondsIsOneHundredTicks() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(0);

            assertEquals(100, ticksToComplete(clock, 0, 1.0 / 100.0 + 1.0e-12));
        }

        @Test
        @DisplayName("the stage is reported once per tenth, and kept within 0..9")
        void stagesPerTenth() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(0);
            int changes = 0;
            for (int t = 0; t < 9; t++) {
                clock.swing(t);
                if (clock.advance(t, 0.1 + 1.0e-9, HEARTBEAT) == ServerDigClock.Step.STAGE_CHANGED) changes++;
            }
            assertEquals(9, changes);
            assertEquals(9, clock.stage());
            assertEquals(0, ServerDigClock.stageOf(0.0));
            assertEquals(0, ServerDigClock.stageOf(0.05));
            assertEquals(9, ServerDigClock.stageOf(0.95));
            assertEquals(9, ServerDigClock.stageOf(1.5));
            assertEquals(0, ServerDigClock.stageOf(Double.NaN));
        }

        @Test
        @DisplayName("a slow rate reports ADVANCED between two stages")
        void advancedBetweenStages() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(0);

            assertEquals(ServerDigClock.Step.STAGE_CHANGED, clock.advance(0, 0.01, HEARTBEAT));
            assertEquals(ServerDigClock.Step.ADVANCED, clock.advance(1, 0.01, HEARTBEAT));
        }

        @Test
        @DisplayName("a rate that is not a number or not positive adds nothing; an infinite one completes")
        void unusableRates() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(0);

            assertEquals(ServerDigClock.Step.PAUSED, clock.advance(0, Double.NaN, HEARTBEAT));
            assertEquals(ServerDigClock.Step.PAUSED, clock.advance(1, 0.0, HEARTBEAT));
            assertEquals(ServerDigClock.Step.PAUSED, clock.advance(2, -1.0, HEARTBEAT));
            assertEquals(0.0, clock.progress(), EPSILON);
            assertEquals(ServerDigClock.Step.COMPLETE, clock.advance(3, Double.POSITIVE_INFINITY, HEARTBEAT));
        }

        @Test
        @DisplayName("no dig, nothing done")
        void idleWithoutDig() {
            assertEquals(ServerDigClock.Step.IDLE, new ServerDigClock().advance(0, 1.0, HEARTBEAT));
        }

        @Test
        @DisplayName("a lag-compensated counter that jumps bills the ticks elapsed, at most four")
        void catchUpIsBilledAndCapped() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(0);
            clock.advance(0, 0.01, HEARTBEAT);

            clock.swing(3);
            clock.advance(3, 0.01, HEARTBEAT);
            assertEquals(0.04, clock.progress(), 1.0e-9);

            clock.swing(100);
            clock.advance(100, 0.01, HEARTBEAT);
            assertEquals(0.04 + 0.01 * ServerDigClock.MAX_CATCH_UP_TICKS, clock.progress(), 1.0e-9);
        }

        @Test
        @DisplayName("two advances in the same tick bill that tick once")
        void sameTickBilledOnce() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(5);
            clock.advance(5, 0.1, HEARTBEAT);
            clock.advance(5, 0.1, HEARTBEAT);

            assertEquals(0.1, clock.progress(), EPSILON);
        }

        @Test
        @DisplayName("a vanilla dig taken over keeps its progress, but never completes on takeover")
        void takeOverKeepsProgress() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDigAt(0, 0.6);
            assertEquals(0.6, clock.progress(), EPSILON);

            clock.startDigAt(0, 5.0);
            assertEquals(ServerDigClock.MAX_CARRIED_PROGRESS, clock.progress(), EPSILON);
            clock.startDigAt(0, Double.NaN);
            assertEquals(0.0, clock.progress(), EPSILON);
            clock.startDigAt(0, -1.0);
            assertEquals(0.0, clock.progress(), EPSILON);
        }

        @Test
        @DisplayName("restart keeps the dig going from zero; abort ends it")
        void restartAndAbort() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(0);
            clock.advance(0, 0.5, HEARTBEAT);

            clock.restartDig();
            assertTrue(clock.isDigging());
            assertEquals(0.0, clock.progress(), EPSILON);
            assertEquals(ServerDigClock.NO_STAGE, clock.stage());

            clock.abortDig();
            assertFalse(clock.isDigging());
        }
    }

    @Nested
    @DisplayName("pauses")
    class Pauses {

        @Test
        @DisplayName("no swing for more than the heartbeat pauses the dig without aborting it")
        void heartbeatPauses() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(0);
            clock.advance(0, 0.1, HEARTBEAT);

            assertEquals(ServerDigClock.Step.PAUSED, clock.advance(HEARTBEAT + 1, 0.1, HEARTBEAT));
            assertTrue(clock.isDigging());
            assertEquals(0.1, clock.progress(), EPSILON);
        }

        @Test
        @DisplayName("the ticks of a pause are not billed when the swings come back")
        void pausedTicksAreNotBilled() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(0);
            clock.advance(0, 0.01, HEARTBEAT);
            for (long t = 1; t <= 20; t++) clock.advance(t, 0.01, HEARTBEAT); // silence after tick 5

            clock.swing(21);
            clock.advance(21, 0.01, HEARTBEAT);
            // 0..5 billed (6 ticks), 6..20 paused, 21 billed once.
            assertEquals(0.07, clock.progress(), 1.0e-9);
        }

        @Test
        @DisplayName("after a break, the next dig waits the pause (vanilla destroyDelay)")
        void postBreakPause() {
            final ServerDigClock clock = new ServerDigClock();
            clock.arm(0, LINGER);
            clock.startDig(0);
            clock.finishDig(10, PAUSE, LINGER);

            assertFalse(clock.isDigging());
            assertTrue(clock.isArmed());
            clock.startDig(11);
            assertEquals(ServerDigClock.Step.PAUSED, clock.advance(11, 0.5, HEARTBEAT));
            clock.swing(14);
            assertEquals(ServerDigClock.Step.PAUSED, clock.advance(14, 0.5, HEARTBEAT));
            clock.swing(15);
            assertEquals(ServerDigClock.Step.STAGE_CHANGED, clock.advance(15, 0.5, HEARTBEAT));
        }

        @Test
        @DisplayName("a break extends the mode by the linger")
        void breakExtendsTheMode() {
            final ServerDigClock clock = new ServerDigClock();
            clock.arm(0, 5);
            clock.startDig(0);
            clock.finishDig(100, PAUSE, LINGER);

            assertFalse(clock.shouldDisarm(139, false));
            assertTrue(clock.shouldDisarm(140, false));
        }
    }

    @Nested
    @DisplayName("attacks of §10")
    class Attacks {

        @Test
        @DisplayName("START then silence: the dig never breaks without swings, however long it waits")
        void startThenSilenceNeverBreaks() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(0);
            for (long t = 0; t < 2_000; t++) {
                assertTrue(clock.advance(t, 0.05, HEARTBEAT) != ServerDigClock.Step.COMPLETE);
            }
            assertTrue(clock.progress() < 1.0);
        }

        @Test
        @DisplayName("forged swings at every tick break no faster than the rate allows")
        void forgedSwingsAreBoundedByTheRate() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(0);

            // Swinging several times per tick changes nothing: progress is billed per tick.
            int ticks = -1;
            for (int i = 0; i < 100; i++) {
                clock.swing(i);
                clock.swing(i);
                clock.swing(i);
                if (clock.advance(i, 0.04 + 1.0e-9, HEARTBEAT) == ServerDigClock.Step.COMPLETE) {
                    ticks = i + 1;
                    break;
                }
            }
            assertEquals(25, ticks);
        }

        @Test
        @DisplayName("spamming START restarts the sum from zero: no progress is carried by a new START")
        void startSpamRestarts() {
            final ServerDigClock clock = new ServerDigClock();
            clock.startDig(0);
            clock.advance(0, 0.4, HEARTBEAT);
            clock.startDig(1);

            assertEquals(0.0, clock.progress(), EPSILON);
        }
    }
}
