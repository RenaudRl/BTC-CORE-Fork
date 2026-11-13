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
 * {@code interval} server ticks, on a phase of its own so that the warm worlds of a server do not
 * all tick on the same server tick. The tick after skipped ones makes up for them, up to a cap: see
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
    /**
     * Most random ticks per section one tick that makes up for skipped ones draws: one per block
     * of the section. A higher random-tick-speed than that is kept as it is; only the catch-up stops
     * there, so that one tick of a warm world never costs more than a whole section drawn once.
     */
    public static final int MAX_CATCH_UP_RANDOM_TICKS = 4096;

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
        this.skipped = 0; // a frozen world makes up for nothing: the warm ticks it skipped before the freeze are not owed
        return true;
    }

    /**
     * {@code world.freeze-at-rest.warm}: a warm world ticks once every {@code interval} server
     * ticks, the server ticks where {@code serverTick + phase} is a multiple of it. The tick that
     * follows skipped ones - including the first one after a player comes back - makes up for them,
     * at most {@code maxCatchUp}.
     *
     * @param warm       no player is in the world and it is not the main world
     * @param serverTick the tick count of the server
     * @param phase      a number of the world's own (a hash of its id), so that warm worlds tick apart
     * @param interval   server ticks per tick of a warm world; 1 or less, every tick
     * @param maxCatchUp most skipped ticks one tick makes up for; 0, none
     * @return true when the world does not tick this server tick
     */
    public boolean warmSkips(boolean warm, long serverTick, int phase, int interval, int maxCatchUp) {
        if (warm && interval > 1 && Math.floorMod(serverTick + phase, (long) interval) != 0) {
            this.skipped++;
            return true;
        }
        this.catchUp = Math.min(this.skipped, Math.max(0, maxCatchUp));
        this.skipped = 0;
        return false;
    }

    /**
     * Whether the last {@link #warmSkips} skipped the tick. Such a tick still moves the chunk system
     * of the world on - tickets age, chunks unload with their entities, the auto-save runs - so that
     * those count in server ticks; the rest of the tick waits. False after a tick, and after
     * {@link #skipsTick} froze the world: a frozen world has no chunk left to move on.
     */
    public boolean warmSkipped() {
        return this.skipped > 0;
    }

    /** Skipped ticks the current tick of the world makes up for: random ticks and lit furnaces. */
    public int catchUp() {
        return this.catchUp;
    }

    /**
     * Random ticks per section for a tick that makes up for {@code catchUp} skipped ones: the
     * random ticks of every tick drawn now, at most {@link #MAX_CATCH_UP_RANDOM_TICKS} (or
     * {@code tickSpeed} itself, if it is higher). Each one picks a block at random, as it would have
     * in its own tick, so a crop, a sapling or ice gets as many on average; the light and the weather
     * are those of now. With nothing to make up for, {@code tickSpeed} as it is.
     */
    public static int randomTicks(int tickSpeed, int catchUp) {
        if (catchUp <= 0) return tickSpeed;
        return (int) Math.min(Math.max(tickSpeed, MAX_CATCH_UP_RANDOM_TICKS), (long) tickSpeed * (catchUp + 1L));
    }
}
