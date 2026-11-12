package dev.btc.core.parallel;

/**
 * Which thread may touch which world under {@code parallel-world-ticking}: the decisions behind
 * {@code TickThread.isTickThreadFor} and its guards, kept apart from the thread classes so they can
 * be tested without a server.
 *
 * <p>Three kinds of thread: the main thread (a tick thread that is not a world thread), a world
 * thread (a tick thread bound to the world it is ticking), and everything else. With the option off
 * no world thread exists and every answer is the one Paper gives today.
 *
 * <p>Rules ported from DivineMC "Parallel world ticking" (NONPLAYT, BX Team, GPL-3.0), itself from
 * SparklyPaper (SparklyPower).
 */
public final class WorldOwnership {

    /**
     * {@code -Dbtccore.pwt.disable-hard-throw=true} turns the new guards into log lines. A debugging
     * aid for a bench, deliberately not a key of btccore.yml: a server must not run that way.
     */
    public static final String DISABLE_HARD_THROW_PROPERTY = "btccore.pwt.disable-hard-throw";

    private WorldOwnership() {
    }

    /**
     * May the current thread touch {@code world}?
     *
     * @param tickThread  the current thread is a tick thread (the main thread or a world thread)
     * @param worldThread the current thread is a world thread
     * @param tickedWorld the world a world thread is ticking; ignored otherwise
     */
    public static boolean ownsWorld(boolean tickThread, boolean worldThread, Object tickedWorld, Object world) {
        return worldThread ? tickedWorld == world : tickThread;
    }

    /** Is the current thread the main thread and nothing else? World threads are refused. */
    public static boolean isMainOnly(boolean tickThread, boolean worldThread) {
        return tickThread && !worldThread;
    }

    /** {@link #ownsWorld}, or a thread that ticks nothing at all (a read from an async task). */
    public static boolean ownsWorldOrIsAsync(boolean tickThread, boolean worldThread, Object tickedWorld, Object world) {
        return !tickThread || ownsWorld(tickThread, worldThread, tickedWorld, world);
    }

    /**
     * A save whose caller is told the outcome ({@code saveWorld} of the Slime API: it returns once
     * the world is written, or throws what went wrong). The copy it writes is taken by a thread that
     * owns the world; from the thread of another world that leaves the main thread, which is parked
     * until the caller's own tick is over - waiting for it would never end, and not waiting would
     * report a save that has not been made.
     *
     * @param worldThread the current thread is a world thread
     * @param ownsWorld   the current thread ticks the world to save
     * @param world       the name of the world to save, for the message
     * @throws IllegalStateException with the option on, on the thread of another world
     */
    public static void ensureMaySaveAndWait(boolean pwtEnabled, boolean worldThread, boolean ownsWorld, String world) {
        if (!pwtEnabled || !worldThread || ownsWorld) return;
        throw new IllegalStateException("Cannot save world " + world + " and wait for it from the thread of another world ("
            + Thread.currentThread().getName() + "): under parallel world ticking its copy is taken by its own thread or the "
            + "main thread, which waits for this tick to end. Run it from Bukkit.getGlobalRegionScheduler().execute(plugin, task)");
    }

    /** Do the new guards throw? Always, unless {@link #DISABLE_HARD_THROW_PROPERTY} is exactly {@code true}. */
    public static boolean hardThrow(String propertyValue) {
        return !Boolean.parseBoolean(propertyValue);
    }
}
