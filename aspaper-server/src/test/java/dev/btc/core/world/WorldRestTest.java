package dev.btc.core.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code world.freeze-at-rest}: a world stops ticking once it has been at rest long enough, and wakes at once. */
class WorldRestTest {

    private static final boolean OCCUPIED = true;
    private static final boolean AT_REST = false;

    private final WorldRest rest = new WorldRest();

    private void ticksOnAtRest(int ticks, String when) {
        for (int tick = 1; tick <= ticks; tick++) {
            assertFalse(this.rest.skipsTick(AT_REST), when + ": tick " + tick + " at rest was skipped");
        }
    }

    @Test
    @DisplayName("a world is occupied by a player, a ticket or a loaded chunk, the main world always, and by nothing else")
    void whatOccupiesAWorld() {
        assertFalse(WorldRest.occupied(false, false, false, 0), "nothing in it");
        assertTrue(WorldRest.occupied(true, false, false, 0), "the main world, empty");
        assertTrue(WorldRest.occupied(false, true, false, 0), "a player");
        assertTrue(WorldRest.occupied(false, false, true, 0), "a ticket");
        assertTrue(WorldRest.occupied(false, false, false, 1), "a chunk still loaded");
    }

    @Test
    @DisplayName("an occupied world ticks every tick")
    void occupiedWorldAlwaysTicks() {
        for (int tick = 0; tick < 3 * WorldRest.REST_TICKS_BEFORE_FREEZE; tick++) {
            assertFalse(this.rest.skipsTick(OCCUPIED));
        }
    }

    @Test
    @DisplayName("a world at rest ticks on for the delay, then skips every tick")
    void freezesAfterTheDelay() {
        ticksOnAtRest(WorldRest.REST_TICKS_BEFORE_FREEZE, "from the start");
        assertTrue(this.rest.skipsTick(AT_REST), "the tick after the delay");
        assertTrue(this.rest.skipsTick(AT_REST), "and the next");
    }

    @Test
    @DisplayName("one occupied tick starts the delay again")
    void anOccupiedTickStartsTheDelayAgain() {
        ticksOnAtRest(WorldRest.REST_TICKS_BEFORE_FREEZE - 1, "before the visit");
        assertFalse(this.rest.skipsTick(OCCUPIED));
        ticksOnAtRest(WorldRest.REST_TICKS_BEFORE_FREEZE, "after the visit");
        assertTrue(this.rest.skipsTick(AT_REST));
    }

    @Test
    @DisplayName("a frozen world ticks the tick it is occupied, and needs the whole delay to freeze again")
    void wakesAtOnce() {
        ticksOnAtRest(WorldRest.REST_TICKS_BEFORE_FREEZE, "before the freeze");
        assertTrue(this.rest.skipsTick(AT_REST));
        assertFalse(this.rest.skipsTick(OCCUPIED), "the tick it is occupied");
        ticksOnAtRest(WorldRest.REST_TICKS_BEFORE_FREEZE, "after the wake");
        assertTrue(this.rest.skipsTick(AT_REST));
    }

    // ==================== warm: world.freeze-at-rest.warm ====================

    private static final boolean WARM = true;
    private static final boolean PLAYER_IN = false;

    @Test
    @DisplayName("a warm world ticks once every interval, and that tick makes up for the ticks skipped")
    void warmWorldTicksOnceEveryInterval() {
        for (int round = 0; round < 3; round++) {
            for (int skip = 1; skip <= 3; skip++) {
                assertTrue(this.rest.warmSkips(WARM, 4, 100), "round " + round + ", skip " + skip);
            }
            assertFalse(this.rest.warmSkips(WARM, 4, 100), "round " + round + ": the fourth tick");
            assertEquals(3, this.rest.catchUp());
        }
    }

    @Test
    @DisplayName("what a tick makes up for is capped, and nothing at all with a cap of 0")
    void catchUpIsCapped() {
        for (int skip = 0; skip < 9; skip++) this.rest.warmSkips(WARM, 10, 4);
        assertFalse(this.rest.warmSkips(WARM, 10, 4));
        assertEquals(4, this.rest.catchUp());

        for (int skip = 0; skip < 9; skip++) this.rest.warmSkips(WARM, 10, 0);
        assertFalse(this.rest.warmSkips(WARM, 10, 0));
        assertEquals(0, this.rest.catchUp());
    }

    @Test
    @DisplayName("a player coming in makes the world tick at once, making up for what was skipped, then every tick")
    void aPlayerComingInTicksAtOnce() {
        assertTrue(this.rest.warmSkips(WARM, 20, 100));
        assertTrue(this.rest.warmSkips(WARM, 20, 100));
        assertFalse(this.rest.warmSkips(PLAYER_IN, 20, 100), "the tick the player is in");
        assertEquals(2, this.rest.catchUp());
        assertFalse(this.rest.warmSkips(PLAYER_IN, 20, 100));
        assertEquals(0, this.rest.catchUp(), "the next tick makes up for nothing");
    }

    @Test
    @DisplayName("an interval of 1 or less never skips a tick")
    void intervalOfOneTicksEveryTick() {
        for (int tick = 0; tick < 10; tick++) {
            assertFalse(this.rest.warmSkips(WARM, 1, 100));
            assertFalse(this.rest.warmSkips(WARM, 0, 100));
            assertEquals(0, this.rest.catchUp());
        }
    }

    @Test
    @DisplayName("random ticks of a tick that makes up for others: one set per tick, never past the int range")
    void randomTicksOfACatchUp() {
        assertEquals(3, WorldRest.randomTicks(3, 0));
        assertEquals(12, WorldRest.randomTicks(3, 3));
        assertEquals(0, WorldRest.randomTicks(0, 3), "random ticking off stays off");
        assertEquals(Integer.MAX_VALUE, WorldRest.randomTicks(Integer.MAX_VALUE / 2, 5));
    }
}
