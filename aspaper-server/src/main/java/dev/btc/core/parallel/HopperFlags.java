package dev.btc.core.parallel;

/**
 * Paper's "Optimize Hoppers" flags: whether the hoppers of a world still fire
 * {@code InventoryMoveItemEvent}. Paper keeps them in statics, which two worlds ticking at once
 * would overwrite for each other, so under {@code parallel-world-ticking} each world has its own.
 *
 * <p>Per world as in SparklyPaper (SparklyPower), rewritten.
 */
public final class HopperFlags {

    /** The flags every world uses while one thread ticks them all: Paper's statics, as they were. */
    public static final HopperFlags SHARED = new HopperFlags();

    /** Set before each tick of the world: nothing listens, or the world turned the event off. */
    public boolean skipHopperEvents;
    public boolean skipPullModeEventFire;
    public boolean skipPushModeEventFire;

    private HopperFlags() {
    }

    /** The flags of one world: its own when each world has its thread, else {@link #SHARED}. */
    public static HopperFlags forWorld(boolean parallel) {
        return parallel ? new HopperFlags() : SHARED;
    }
}
