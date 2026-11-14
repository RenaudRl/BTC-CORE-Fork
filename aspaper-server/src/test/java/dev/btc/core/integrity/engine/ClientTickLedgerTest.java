package dev.btc.core.integrity.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        // 200 tick ends 5 ms apart: the 40 held last 200 ms, during which talking refills nothing (the bucket is
        // above the jitter allowance); then one per server tick from 150 ms to 995 ms: 16.
        assertEquals(BURST + 16, endedOver(new ClientTickLedger(1), 0, 200, 5, true));
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
        assertEquals(ClientTickLedger.ACTIVE_CREDITS, endedOver(ledger, now, 10, 0, true));
    }

    @Test
    void aSilenceOfAQuarterSecondOrMoreCreditsItsWholeLength() {
        ClientTickLedger ledger = dry(0);
        // 500 ms with no packet: ten server ticks of silence, on top of the jitter allowance.
        assertEquals(ClientTickLedger.ACTIVE_CREDITS + 10, endedOver(ledger, TimeUnit.MILLISECONDS.toNanos(500), 20, 0, true));
    }

    @Test
    void unboundedEveryTickEndEndsATick() {
        assertEquals(200, endedOver(new ClientTickLedger(1), 0, 200, 0, false));
    }
}
