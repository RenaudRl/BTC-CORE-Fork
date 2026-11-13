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

    /** The server tick, counted from 1: with a phase of 0 the first tick of a warm world is the interval-th. */
    private long serverTick;

    private boolean warm(boolean warm, int interval, int maxCatchUp) {
        return this.rest.warmSkips(warm, ++this.serverTick, 0, interval, maxCatchUp);
    }

    /** The server ticks out of {@code ticks} on which a warm world of this phase ticks. */
    private static java.util.List<Long> ticksOf(int phase, int interval, int ticks) {
        WorldRest world = new WorldRest();
        java.util.List<Long> ticked = new java.util.ArrayList<>();
        for (long tick = 1; tick <= ticks; tick++) {
            if (!world.warmSkips(WARM, tick, phase, interval, 0)) ticked.add(tick);
        }
        return ticked;
    }

    @Test
    @DisplayName("each warm world ticks once per interval on a server tick set by its phase, a negative one too")
    void warmWorldsTickApartByPhase() {
        assertEquals(java.util.List.of(20L, 40L, 60L), ticksOf(0, 20, 60));
        assertEquals(java.util.List.of(13L, 33L, 53L), ticksOf(7, 20, 60));
        assertEquals(java.util.List.of(5L, 25L, 45L), ticksOf(-5, 20, 60));
    }

    @Test
    @DisplayName("the tick a warm world gets to makes up for the ticks skipped since its last one, whatever its phase")
    void catchUpCountsTheTicksSinceTheLastOne() {
        WorldRest world = new WorldRest();
        for (long tick = 1; tick < 13; tick++) assertTrue(world.warmSkips(WARM, tick, 7, 20, 100));
        assertFalse(world.warmSkips(WARM, 13, 7, 20, 100));
        assertEquals(12, world.catchUp());
        for (long tick = 14; tick < 33; tick++) assertTrue(world.warmSkips(WARM, tick, 7, 20, 100));
        assertFalse(world.warmSkips(WARM, 33, 7, 20, 100));
        assertEquals(19, world.catchUp());
    }

    @Test
    @DisplayName("a warm world ticks once every interval, and that tick makes up for the ticks skipped")
    void warmWorldTicksOnceEveryInterval() {
        for (int round = 0; round < 3; round++) {
            for (int skip = 1; skip <= 3; skip++) {
                assertTrue(warm(WARM, 4, 100), "round " + round + ", skip " + skip);
            }
            assertFalse(warm(WARM, 4, 100), "round " + round + ": the fourth tick");
            assertEquals(3, this.rest.catchUp());
        }
    }

    @Test
    @DisplayName("what a tick makes up for is capped, and nothing at all with a cap of 0")
    void catchUpIsCapped() {
        for (int skip = 0; skip < 9; skip++) warm(WARM, 10, 4);
        assertFalse(warm(WARM, 10, 4));
        assertEquals(4, this.rest.catchUp());

        for (int skip = 0; skip < 9; skip++) warm(WARM, 10, 0);
        assertFalse(warm(WARM, 10, 0));
        assertEquals(0, this.rest.catchUp());
    }

    @Test
    @DisplayName("a player coming in makes the world tick at once, making up for what was skipped, then every tick")
    void aPlayerComingInTicksAtOnce() {
        assertTrue(warm(WARM, 20, 100));
        assertTrue(warm(WARM, 20, 100));
        assertFalse(warm(PLAYER_IN, 20, 100), "the tick the player is in");
        assertEquals(2, this.rest.catchUp());
        assertFalse(warm(PLAYER_IN, 20, 100));
        assertEquals(0, this.rest.catchUp(), "the next tick makes up for nothing");
    }

    @Test
    @DisplayName("a world that freezes makes up for nothing it skipped warm before the freeze")
    void aFreezeForgetsTheWarmTicksSkipped() {
        assertTrue(warm(WARM, 20, 100));
        assertTrue(warm(WARM, 20, 100));
        ticksOnAtRest(WorldRest.REST_TICKS_BEFORE_FREEZE, "before the freeze");
        assertTrue(this.rest.skipsTick(AT_REST), "frozen");
        assertFalse(this.rest.skipsTick(OCCUPIED), "woken");
        assertFalse(warm(PLAYER_IN, 20, 100));
        assertEquals(0, this.rest.catchUp());
    }

    @Test
    @DisplayName("every tick a warm world skips still moves its chunk system on, and no other tick is taken for one")
    void aWarmSkipMovesTheChunkSystemOn() {
        assertFalse(this.rest.warmSkipped(), "a world that never skipped");
        for (int skip = 1; skip <= 3; skip++) {
            assertTrue(warm(WARM, 4, 0));
            assertTrue(this.rest.warmSkipped(), "skip " + skip + ": tickets age and chunks unload in server ticks");
        }
        assertFalse(warm(WARM, 4, 0));
        assertFalse(this.rest.warmSkipped(), "the tick itself: level.tick moves the chunk system on");

        assertTrue(warm(WARM, 4, 0));
        assertFalse(warm(PLAYER_IN, 4, 0));
        assertFalse(this.rest.warmSkipped(), "a player came in: a whole tick");

        assertTrue(warm(WARM, 4, 0));
        ticksOnAtRest(WorldRest.REST_TICKS_BEFORE_FREEZE, "before the freeze");
        assertTrue(this.rest.skipsTick(AT_REST));
        assertFalse(this.rest.warmSkipped(), "frozen: no chunk is left to move on");
    }

    @Test
    @DisplayName("an interval of 1 or less never skips a tick")
    void intervalOfOneTicksEveryTick() {
        for (int tick = 0; tick < 10; tick++) {
            assertFalse(warm(WARM, 1, 100));
            assertFalse(warm(WARM, 0, 100));
            assertEquals(0, this.rest.catchUp());
        }
    }

    @Test
    @DisplayName("random ticks of a tick that makes up for others: one set per tick, at most one per block of the section")
    void randomTicksOfACatchUp() {
        assertEquals(3, WorldRest.randomTicks(3, 0));
        assertEquals(12, WorldRest.randomTicks(3, 3));
        assertEquals(3600, WorldRest.randomTicks(3, 1199));
        assertEquals(WorldRest.MAX_CATCH_UP_RANDOM_TICKS, WorldRest.randomTicks(3, 2000), "the catch-up stops at the budget");
        assertEquals(0, WorldRest.randomTicks(0, 3), "random ticking off stays off");
        assertEquals(5000, WorldRest.randomTicks(5000, 0), "nothing to make up for: the game rule as it is");
        assertEquals(5000, WorldRest.randomTicks(5000, 3), "a game rule above the budget is kept, not added to");
        assertEquals(Integer.MAX_VALUE / 2, WorldRest.randomTicks(Integer.MAX_VALUE / 2, 5));
    }
}
