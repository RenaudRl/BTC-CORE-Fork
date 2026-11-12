package dev.btc.core.world;

/**
 * Whether a world at rest skips its tick ({@code world.freeze-at-rest}).
 *
 * <p>A world is at rest when nothing is in it: no player, no ticket holding a chunk, no chunk
 * left loaded. It still ticks for {@link #REST_TICKS_BEFORE_FREEZE} ticks, then stops until it is
 * occupied again; it wakes the tick it is. There is no frozen state to restore: every tick asks
 * again, and the count is all that is kept.
 *
 * <p>Asked once per server tick and per world, by the main thread, before the world's turn - with
 * parallel world ticking too, where the worlds that tick are chosen before any thread is handed
 * one. Not thread-safe, and need not be.
 */
public final class WorldRest {

    /**
     * Ticks a world goes on ticking once at rest: five seconds, for what its last chunks queued
     * (block events, removals) to be done before it stops.
     */
    public static final int REST_TICKS_BEFORE_FREEZE = 100;

    private int restTicks;

    /**
     * @param occupied a player is in the world, a ticket holds one of its chunks, or a chunk is still loaded
     * @return true when the world does not tick this server tick
     */
    public boolean skipsTick(boolean occupied) {
        if (occupied) {
            this.restTicks = 0;
            return false;
        }
        if (this.restTicks < REST_TICKS_BEFORE_FREEZE) {
            this.restTicks++;
            return false;
        }
        return true;
    }
}
