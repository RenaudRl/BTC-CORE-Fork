package dev.btc.core.performance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatencySpreadTest {

    private static final int VANILLA_PERIOD = 601;

    /** How often each place of the list is served over the ticks of one period, starting anywhere. */
    private static int[] servedOverOnePeriod(int players, int startSlot) {
        int[] served = new int[players];
        int slot = startSlot;
        for (int tick = 0; tick < VANILLA_PERIOD; tick++) {
            slot = LatencySpread.nextSlot(slot);
            LatencySpread.forEachDue(slot, players, place -> served[place]++);
        }
        return served;
    }

    @Test
    @DisplayName("over one period every player is served once, whatever the size of the list and the tick it starts on")
    void everyPlayerIsServedOncePerPeriod() {
        for (int players : new int[] {0, 1, 200, 600, 601, 602, 1500}) {
            for (int startSlot : new int[] {0, 299, 600}) {
                int[] served = servedOverOnePeriod(players, startSlot);
                for (int place = 0; place < players; place++) {
                    assertEquals(1, served[place], "place " + place + " of " + players + ", from slot " + startSlot);
                }
            }
        }
    }

    @Test
    @DisplayName("a player is served every 601 ticks, the period of vanilla")
    void thePeriodIsThatOfVanilla() {
        int slot = 0;
        int ticks = 0;
        do {
            slot = LatencySpread.nextSlot(slot);
            ticks++;
        } while (slot != 0);

        assertEquals(VANILLA_PERIOD, ticks);
    }

    @Test
    @DisplayName("a tick serves one player per 601 in the list, not the whole list")
    void aTickServesAFewPlayers() {
        int[] served = new int[1];
        LatencySpread.forEachDue(0, 1500, place -> served[0]++);
        assertEquals(3, served[0], "places 0, 601 and 1202");

        served[0] = 0;
        LatencySpread.forEachDue(300, 1500, place -> served[0]++);
        assertEquals(2, served[0], "places 300 and 901");
    }
}
