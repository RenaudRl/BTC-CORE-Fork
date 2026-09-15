package dev.btc.core.integrity.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cadence balance, driven tick by tick with a synthetic clock.
 *
 * <p>Each case is a client behaviour, not a number: an honest client, a fast one, a laggy one that
 * bursts. The thresholds are guesses until 4.1 measures them; what these tests pin is the shape —
 * what is forgiven and what is not — so that tuning a constant cannot silently change the rule.
 */
class TimerCheckTest {

    private static final long MILLIS = 1_000_000L;

    @Test
    void anHonestClientAtOnePacketPerTickNeverCrosses() {
        SessionState state = new SessionState();
        long now = 1_000 * MILLIS;
        for (int packet = 0; packet < 2_000; packet++) {
            assertEquals(0, TimerCheck.observe(state, now), "packet " + packet);
            now += 50 * MILLIS;
        }
        assertEquals(0, state.timerBalanceMillis(), 1e-9);
    }

    @Test
    void jitterAroundTheTickIsAbsorbed() {
        SessionState state = new SessionState();
        long now = 1_000 * MILLIS;
        // Alternating 40 / 60 ms: same average cadence, never ahead by more than one tick.
        for (int packet = 0; packet < 1_000; packet++) {
            assertEquals(0, TimerCheck.observe(state, now));
            now += (packet % 2 == 0 ? 40 : 60) * MILLIS;
        }
    }

    @Test
    void aClientRunningTwiceAsFastIsReportedOnceItIsAheadEnough() {
        SessionState state = new SessionState();
        long now = 1_000 * MILLIS;
        double ahead = 0;
        int packetsUntilViolation = 0;
        for (int packet = 0; packet < 100 && ahead == 0; packet++) {
            ahead = TimerCheck.observe(state, now);
            now += 25 * MILLIS; // two packets per tick
            packetsUntilViolation++;
        }
        assertTrue(ahead > TimerCheck.VIOLATION_MILLIS, "reported ahead by " + ahead + " ms");
        // 25 ms gained per packet, 300 ms to cross: the first packet only starts the clock.
        assertEquals(14, packetsUntilViolation);
        assertEquals(0, state.timerBalanceMillis(), 1e-9, "the balance is reset once reported");
    }

    @Test
    void aStallFollowedByABurstOfTheSameSizeIsForgiven() {
        SessionState state = new SessionState();
        long now = 1_000 * MILLIS;
        TimerCheck.observe(state, now);
        // Two seconds of nothing, then the forty packets those two seconds would have carried.
        now += 2_000 * MILLIS;
        for (int packet = 0; packet < 40; packet++) {
            assertEquals(0, TimerCheck.observe(state, now), "burst packet " + packet);
            now += 1 * MILLIS;
        }
    }

    @Test
    void aStallCannotBankMoreThanTheDebtCeiling() {
        SessionState state = new SessionState();
        long now = 1_000 * MILLIS;
        TimerCheck.observe(state, now);
        // Ten seconds of nothing would be 200 packets of credit; the ceiling allows about 60 before
        // the threshold, so a 100-packet burst crosses. A ceiling that did not exist would let a
        // client stall on purpose and then run free for as long as it stalled.
        now += 10_000 * MILLIS;
        double ahead = 0;
        for (int packet = 0; packet < 100 && ahead == 0; packet++) {
            ahead = TimerCheck.observe(state, now);
            now += 1 * MILLIS;
        }
        assertTrue(ahead > 0, "the burst after a long stall must eventually be reported");
    }

    @Test
    void theFirstPacketOnlyStartsTheClock() {
        SessionState state = new SessionState();
        assertEquals(0, TimerCheck.observe(state, 5 * MILLIS));
        assertEquals(0, state.timerBalanceMillis(), 1e-9);
        assertEquals(5 * MILLIS, state.lastMoveNanos());
    }
}
