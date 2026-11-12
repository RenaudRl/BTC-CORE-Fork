package dev.btc.core.parallel;

/**
 * Whether a player has ticked in the world it is in: false from the moment it enters one - login,
 * respawn, a teleport to another world - until its first tick there.
 *
 * <p>Under {@code parallel-world-ticking} a menu asked for in between is put off by one tick. It
 * was asked by code that began before the player changed world, and may belong to the world it
 * left: once open, the thread of the new world would validate it against a block of the old one.
 *
 * <p>Written by the main thread (the arrival) and by the thread of the world (the tick), never at
 * the same time: the barrier of the world tick stands between them.
 */
public final class WorldArrival {

    /** How long a menu that may not open yet is put off, before it is asked again. */
    public static final long RETRY_DELAY_TICKS = 1L;

    private boolean ticked;

    /** The player entered a world. */
    public void arrived() {
        this.ticked = false;
    }

    /** The player ticked in the world it is in. */
    public void ticked() {
        this.ticked = true;
    }

    /** With the option off a menu opens at once, as before. */
    public boolean mayOpenMenu(boolean parallel) {
        return !parallel || this.ticked;
    }
}
