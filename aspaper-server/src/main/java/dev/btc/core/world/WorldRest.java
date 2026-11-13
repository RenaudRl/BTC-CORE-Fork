package dev.btc.core.world;

/**
 * Whether a world at rest skips its tick ({@code world.freeze-at-rest}), and how often a world with
 * no player ticks ({@code world.freeze-at-rest.warm}).
 *
 * <p>A world is at rest when nothing is in it: no player, no ticket holding a chunk, no chunk
 * left loaded. It still ticks for {@link #REST_TICKS_BEFORE_FREEZE} ticks, then stops until it is
 * occupied again; it wakes the tick it is. There is no frozen state to restore: every tick asks
 * again, and the count is all that is kept.
 *
 * <p>A warm world is one with no player that is not the main world: it ticks once every
 * {@code interval} server ticks. The tick after skipped ones makes up for them, up to a cap: see
 * {@link #catchUp()}.
 *
 * <p>Asked once per server tick and per world, by the main thread, before the world's turn - with
 * parallel world ticking too, where the worlds that tick are chosen before any thread is handed
 * one. Not thread-safe, and need not be: {@link #catchUp()} is read by the thread ticking the world,
 * after it was handed the world.
 */
public final class WorldRest {

    /**
     * Ticks a world goes on ticking once at rest: five seconds, for what its last chunks queued
     * (block events, removals) to be done before it stops.
     */
    public static final int REST_TICKS_BEFORE_FREEZE = 100;

    private int restTicks;
    private int skipped;
    private int catchUp;

    /**
     * A player is in the world, a ticket holds one of its chunks, or a chunk is still loaded:
     * something may need a tick. The main world always counts as occupied: its game time is the
     * clock plugins and commands read for the whole server.
     */
    public static boolean occupied(boolean mainWorld, boolean players, boolean tickets, int loadedChunks) {
        return mainWorld || players || tickets || loadedChunks != 0;
    }

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

    /**
     * {@code world.freeze-at-rest.warm}: a warm world ticks once every {@code interval} server
     * ticks. The tick that follows skipped ones - including the first one after a player comes back -
     * makes up for them, at most {@code maxCatchUp}.
     *
     * @param warm       no player is in the world and it is not the main world
     * @param interval   server ticks per tick of a warm world; 1 or less, every tick
     * @param maxCatchUp most skipped ticks one tick makes up for; 0, none
     * @return true when the world does not tick this server tick
     */
    public boolean warmSkips(boolean warm, int interval, int maxCatchUp) {
        if (warm && this.skipped + 1 < interval) {
            this.skipped++;
            return true;
        }
        this.catchUp = Math.min(this.skipped, Math.max(0, maxCatchUp));
        this.skipped = 0;
        return false;
    }

    /** Skipped ticks the current tick of the world makes up for: random ticks and lit furnaces. */
    public int catchUp() {
        return this.catchUp;
    }

    /**
     * Random ticks per section for a tick that makes up for {@code catchUp} skipped ones: the
     * random ticks of every tick drawn now. Each one picks a block at random, as it would have in
     * its own tick, so a crop, a sapling or ice gets as many on average; the light and the weather
     * are those of now.
     */
    public static int randomTicks(int tickSpeed, int catchUp) {
        return (int) Math.min(Integer.MAX_VALUE, (long) tickSpeed * (catchUp + 1L));
    }
}
