package dev.btc.core.integrity.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tick-end bucket: a burst for the backlog of a freeze, then one tick end per server tick. */
class ClientTickLedgerTest {

    private static final int BURST = 40;
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private static int endedOver(ClientTickLedger ledger, long startNanos, int count, long stepMillis, boolean bounded) {
        int ended = 0;
        for (int i = 0; i < count; i++) {
            if (ledger.tickEnded(startNanos + TimeUnit.MILLISECONDS.toNanos(stepMillis * i), TICK_NANOS, BURST, bounded)) {
                ended++;
            }
        }
        return ended;
    }

    @Test
    void aSustainedFloodEndsTheBurstThenOneTickPerServerTick() {
        // 200 tick ends 5 ms apart: the 40 held last 200 ms; talking refills one per 45 ms (9/10 of a server tick),
        // which counts only once the bucket is under the jitter allowance (10, from 150 ms): 180 ms, then 225 to 990 ms.
        assertEquals(BURST + 19, endedOver(new ClientTickLedger(1), 0, 200, 5, true));
    }

    @Test
    void anHonestClientAtTwentyTicksASecondNeverRunsDry() {
        assertEquals(1_000, endedOver(new ClientTickLedger(1), 0, 1_000, 50, true));
    }

    @Test
    void theBucketRefillsToTheBurstAndNoFurther() {
        ClientTickLedger ledger = new ClientTickLedger(1);
        assertEquals(BURST, endedOver(ledger, 0, BURST + 5, 0, true), "the burst, then dry");

        long tenSecondsLater = TimeUnit.SECONDS.toNanos(10);
        assertEquals(BURST, endedOver(ledger, tenSecondsLater, BURST + 5, 0, true), "ten seconds of silence saved 40, not 200");
    }

    /** Spends the whole burst at {@code nowNanos}: the bucket is dry. */
    private static ClientTickLedger dry(long nowNanos) {
        ClientTickLedger ledger = new ClientTickLedger(1);
        endedOver(ledger, nowNanos, BURST + 5, 0, true);
        return ledger;
    }

    @ParameterizedTest
    @ValueSource(longs = {40, 90, 240})
    void steadyPacketsWithTheTickEndsHeldBackEarnOnlyTheJitterAllowance(long stepMillis) {
        // Review 14/11: small gaps between packets do not add up into a silence.
        ClientTickLedger ledger = dry(0);
        long now = 0;
        for (long elapsed = stepMillis; elapsed <= 2_000; elapsed += stepMillis) {
            now = TimeUnit.MILLISECONDS.toNanos(elapsed);
            ledger.packetSeen(now);
        }
        assertEquals(ClientTickLedger.ACTIVE_CREDITS, endedOver(ledger, now, ClientTickLedger.ACTIVE_CREDITS + 10, 0, true));
    }

    @Test
    void aSilenceOfAQuarterSecondOrMoreCreditsItsWholeLength() {
        ClientTickLedger ledger = dry(0);
        // 500 ms with no packet: ten server ticks of silence, on top of the jitter allowance.
        assertEquals(ClientTickLedger.ACTIVE_CREDITS + 10, endedOver(ledger, TimeUnit.MILLISECONDS.toNanos(500), ClientTickLedger.ACTIVE_CREDITS + 20, 0, true));
    }

    /** What {@link #refusedWithJitter} saw, so a test can prove its stream really had lots and gaps. */
    private record JitterRun(int refused, int largestLot, long longestGapNanos) {
    }

    /**
     * An honest client ending a tick every {@code periodNanos} for {@code seconds}, as the bench of 14/11 saw it:
     * each tick end takes 20-60 ms to arrive, one in ten 100-200 ms; the stream stays in order (a late one holds back
     * those behind it) and the region thread handles what arrived during a server tick at once, at the next tick start.
     */
    private static JitterRun refusedWithJitter(long periodNanos, long seconds, long seed) {
        Random random = new Random(seed);
        ClientTickLedger ledger = new ClientTickLedger(1);
        long ticks = TimeUnit.SECONDS.toNanos(seconds) / periodNanos;
        long arrived = 0;
        long previous = -1;
        int lot = 0;
        int largestLot = 0;
        long longestGap = 0;
        int refused = 0;
        for (long k = 0; k < ticks; k++) {
            long delayMillis = random.nextInt(10) == 0 ? 100 + random.nextInt(101) : 20 + random.nextInt(41);
            arrived = Math.max(arrived, k * periodNanos + TimeUnit.MILLISECONDS.toNanos(delayMillis));
            long handled = (arrived + periodNanos - 1) / periodNanos * periodNanos;
            if (handled == previous) {
                lot++;
            } else {
                longestGap = previous < 0 ? 0 : Math.max(longestGap, handled - previous);
                lot = 1;
                previous = handled;
            }
            largestLot = Math.max(largestLot, lot);
            if (!ledger.tickEnded(handled, periodNanos, BURST, true)) {
                refused++;
            }
        }
        return new JitterRun(refused, largestLot, longestGap);
    }

    @Test
    void anHonestClientWithJitterAndLotsNeverRunsDryInFiveMinutes() {
        // Bench 14/11 (B.2): about one line per bot and per second, idle bots included.
        JitterRun run = refusedWithJitter(TICK_NANOS, 300, 1114L);
        assertTrue(run.largestLot() >= 3, "the stream has lots: " + run);
        assertTrue(run.longestGapNanos() >= TimeUnit.MILLISECONDS.toNanos(100), "the stream has gaps: " + run);
        assertEquals(0, run.refused(), () -> run.toString());
    }

    @Test
    void atTickRateFortyAnHonestClientWithJitterNeverRunsDry() {
        // Bench 14/11: 109 lines in 30 s at /tick rate 40.
        JitterRun run = refusedWithJitter(TimeUnit.MILLISECONDS.toNanos(25), 300, 4040L);
        assertTrue(run.largestLot() >= 3, "the stream has lots: " + run);
        assertEquals(0, run.refused(), () -> run.toString());
    }

    @Test
    void aSustainedThirtyASecondIsRefusedEverySecondAtTheMarginRate() {
        // 30 tick ends a second against a refill of 20 / 0.9 = 22.2: about 7.8 refused each second, once dry.
        ClientTickLedger ledger = new ClientTickLedger(1);
        long step = TimeUnit.SECONDS.toNanos(1) / 30;
        int[] refusedPerSecond = new int[60];
        for (int i = 0; i < 30 * 60; i++) {
            if (!ledger.tickEnded(i * step, TICK_NANOS, BURST, true)) {
                refusedPerSecond[i / 30]++;
            }
        }
        for (int second = 5; second < 60; second++) {
            int refused = refusedPerSecond[second];
            assertTrue(refused >= 7 && refused <= 9, "second " + second + ": " + refused + " refused");
        }
    }

    @Test
    void unboundedEveryTickEndEndsATick() {
        assertEquals(200, endedOver(new ClientTickLedger(1), 0, 200, 0, false));
    }
}
