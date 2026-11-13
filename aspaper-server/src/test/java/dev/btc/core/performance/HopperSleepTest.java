package dev.btc.core.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HopperSleepTest {

    private final HopperSleep sleep = new HopperSleep();
    private final HopperSleep.Nap nap = new HopperSleep.Nap();

    private void sleepAt(int x, int y, int z) {
        this.sleep.attempted(x, y, z, this.nap, false, false);
        assertTrue(this.nap.asleep());
    }

    /** How many times a sleeping hopper retries over {@code ticks}, its tick called every {@code step} ticks on its throttle phase. */
    private static int retries(long posHash, int interval, int step, int ticks) {
        int retries = 0;
        for (long gameTime = 0; gameTime < ticks; gameTime++) {
            if (Math.floorMod(gameTime + posHash, step) != 0) continue; // the throttle skips this tick
            if (HopperSleep.retryDue(gameTime, posHash, interval, step)) retries++;
        }
        return retries;
    }

    @Test
    void aSleepingHopperRetriesOncePerWakeInterval() {
        for (long posHash : new long[] {0, 7, -13, 123_456_789}) {
            assertEquals(5, retries(posHash, 200, 1, 1000), "no throttle, hash " + posHash);
            assertEquals(5, retries(posHash, 200, 40, 1000), "throttle 40, hash " + posHash);
            assertEquals(40, retries(posHash, 130, 40, 130 * 40), "interval not a multiple of the throttle, hash " + posHash);
        }
    }

    @Test
    void retriesAreSpreadOverTheIntervalByPosition() {
        java.util.Set<Long> ticks = new java.util.HashSet<>();
        for (long posHash = 0; posHash < 100; posHash++) {
            for (long gameTime = 0; gameTime < 200; gameTime++) {
                if (HopperSleep.retryDue(gameTime, posHash * 31, 200, 1)) ticks.add(gameTime);
            }
        }
        assertTrue(ticks.size() > 50, "hoppers retry on " + ticks.size() + " distinct ticks of 200");
    }

    @Test
    void anAttemptThatMovedNothingSleeps() {
        this.sleep.attempted(0, 64, 0, this.nap, false, false);
        assertTrue(this.nap.asleep());
        assertFalse(this.sleep.isEmpty());
    }

    @Test
    void anAttemptThatMovedStaysAwake() {
        this.sleep.attempted(0, 64, 0, this.nap, true, false);
        assertFalse(this.nap.asleep());
        assertTrue(this.sleep.isEmpty());
    }

    @Test
    void anAttemptRefusedByAPluginNeverSleeps() {
        this.sleep.attempted(0, 64, 0, this.nap, false, true);
        assertFalse(this.nap.asleep());
        assertTrue(this.sleep.isEmpty());
    }

    @Test
    void aWriteIntoTheHopperItselfWakesIt() {
        // CraftInventory#setItem -> HopperBlockEntity#setItem / BlockEntity#setChanged -> changed(own position)
        sleepAt(5, 64, -3);
        this.sleep.changed(5, 64, -3);
        assertFalse(this.nap.asleep());
        assertTrue(this.sleep.isEmpty());
    }

    @Test
    void aChangeOfTheContainerAboveOrInFrontWakesIt() {
        sleepAt(5, 64, -3);
        this.sleep.changed(5, 65, -3); // the chest it pulls from
        assertFalse(this.nap.asleep());

        sleepAt(5, 64, -3);
        this.sleep.changed(6, 64, -3); // the chest it pushes into, facing east
        assertFalse(this.nap.asleep());
    }

    @Test
    void aChangeTwoBlocksAwayLeavesItAsleep() {
        sleepAt(5, 64, -3);
        this.sleep.changed(5, 66, -3);
        this.sleep.changed(6, 65, -3);
        assertTrue(this.nap.asleep());
    }

    @Test
    void aNeighbourUpdateWakesIt() {
        sleepAt(5, 64, -3);
        this.sleep.wake(5, 64, -3);
        assertFalse(this.nap.asleep());
    }

    @Test
    void anItemFallingOnItWakesIt() {
        sleepAt(5, 64, -3);
        this.sleep.entityMoved(5.4, 65.2, -2.6, 5.65, 65.45, -2.35, false); // in the block above
        assertFalse(this.nap.asleep());

        sleepAt(5, 64, -3);
        this.sleep.entityMoved(5.4, 64.7, -2.6, 5.65, 64.95, -2.35, false); // in the bowl
        assertFalse(this.nap.asleep());
    }

    @Test
    void anItemStraddlingTheEdgeWakesTheHopperUnderEitherHalf() {
        sleepAt(5, 64, -3);
        this.sleep.entityMoved(4.85, 65.0, -2.6, 5.1, 65.25, -2.35, false);
        assertFalse(this.nap.asleep());
    }

    @Test
    void anItemBesideOrHighAboveLeavesItAsleep() {
        sleepAt(5, 64, -3);
        this.sleep.entityMoved(6.4, 65.2, -2.6, 6.65, 65.45, -2.35, false);
        this.sleep.entityMoved(5.4, 66.2, -2.6, 5.65, 66.45, -2.35, false);
        assertTrue(this.nap.asleep());
    }

    @Test
    void aContainerCartInFrontWakesIt() {
        sleepAt(5, 64, -3);
        this.sleep.entityMoved(6.01, 64.0, -2.99, 6.99, 64.7, -2.01, true);
        assertFalse(this.nap.asleep());
    }

    @Test
    void aForgottenHopperLeavesTheWorldEmpty() {
        sleepAt(5, 64, -3);
        this.sleep.forget(5, 64, -3, this.nap);
        assertFalse(this.nap.asleep());
        assertTrue(this.sleep.isEmpty());
    }

    @Test
    void aNewHopperAtTheSamePlaceIsNotForgottenByTheOldOne() {
        HopperSleep.Nap old = new HopperSleep.Nap();
        this.sleep.attempted(5, 64, -3, old, false, false);
        sleepAt(5, 64, -3);
        this.sleep.forget(5, 64, -3, old);
        assertFalse(this.sleep.isEmpty());
    }

    @Test
    void negativeAndHighPositionsDoNotCollide() {
        sleepAt(-30_000_000 + 1, -64, 29_999_999);
        this.sleep.changed(1, 320, 0);
        assertTrue(this.nap.asleep());
        this.sleep.wake(-30_000_000 + 1, -64, 29_999_999);
        assertFalse(this.nap.asleep());
    }
}
