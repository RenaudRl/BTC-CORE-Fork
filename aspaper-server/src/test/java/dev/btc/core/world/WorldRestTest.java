package dev.btc.core.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
