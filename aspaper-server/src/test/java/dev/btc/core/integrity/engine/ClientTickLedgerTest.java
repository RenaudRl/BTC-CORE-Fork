package dev.btc.core.integrity.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The tick-end bucket: a burst for the backlog of a freeze, then one tick end per server tick. */
class ClientTickLedgerTest {

    private static final int BURST = 40;

    private static int endedOver(ClientTickLedger ledger, long startNanos, int count, long stepMillis, boolean bounded) {
        int ended = 0;
        for (int i = 0; i < count; i++) {
            if (ledger.tickEnded(startNanos + TimeUnit.MILLISECONDS.toNanos(stepMillis * i), BURST, bounded)) {
                ended++;
            }
        }
        return ended;
    }

    @Test
    void aSustainedFloodEndsTheBurstThenOneTickPerServerTick() {
        // 200 tick ends 5 ms apart: 995 ms, so 19 credits gained on top of the 40 held.
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

    @Test
    void unboundedEveryTickEndEndsATick() {
        assertEquals(200, endedOver(new ClientTickLedger(1), 0, 200, 0, false));
    }
}
