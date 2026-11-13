package dev.btc.core.performance;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The sleeping hoppers of one world ({@code performance.sleeping-block-entities.enabled}).
 *
 * <p>A hopper whose transfer attempt moved nothing goes to sleep: its tick returns at once until it is
 * woken. It is woken by whatever can give it something to do: its own inventory or block state, the
 * contents of a block container next to it (the one above it pulls from, the one in front it pushes into),
 * a neighbouring block, an item or a container entity moving over or next to it. An attempt that a
 * plugin refused, or that met a container entity (whose contents change without telling anyone),
 * keeps it awake: the hopper goes on retrying at the vanilla rate.
 *
 * <p>Idea from Lithium's "sleeping block entities" (CaffeineMC, LGPL-3.0-only), as ported to Paper by
 * Leaves and Leaf (GPL-3.0). This is a smaller rewrite for hoppers only, no code copied: Lithium tracks
 * inventory and entity changes per chunk section, here a change wakes the positions around it.
 *
 * <p>Threads: every call for a position comes from the thread that owns it (the world's thread, or the
 * region's on Folia); the map is concurrent because several regions of one world write to it.
 */
public final class HopperSleep {

    /** The sleep state of one hopper, read by its tick. */
    public static final class Nap {
        volatile boolean asleep;

        public boolean asleep() {
            return this.asleep;
        }
    }

    private static final int[][] SIDES = {{0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};

    private final ConcurrentHashMap<Long, Nap> sleeping = new ConcurrentHashMap<>();

    public boolean isEmpty() {
        return this.sleeping.isEmpty();
    }

    /**
     * After a transfer attempt of the hopper at x, y, z.
     *
     * @param moved     an item went in or out
     * @param keepAwake a plugin refused the attempt or a container entity took part in it
     */
    public void attempted(int x, int y, int z, Nap nap, boolean moved, boolean keepAwake) {
        if (moved || keepAwake) return;
        nap.asleep = true;
        this.sleeping.put(pack(x, y, z), nap);
    }

    /** Wakes the hopper at x, y, z, if it sleeps. */
    public void wake(int x, int y, int z) {
        Nap nap = this.sleeping.remove(pack(x, y, z));
        if (nap != null) nap.asleep = false;
    }

    /** The hopper at x, y, z is gone (broken, chunk unloaded). */
    public void forget(int x, int y, int z, Nap nap) {
        nap.asleep = false;
        this.sleeping.remove(pack(x, y, z), nap);
    }

    /**
     * The safety net ({@code wake-interval-ticks}): a sleeping hopper retries once every {@code interval}
     * ticks whatever woke it or not, on a phase set by its position so the hoppers of a world do not all
     * retry on the same tick.
     *
     * @param posHash a hash of the position, the one the throttle uses
     * @param step    ticks between two calls of the hopper's tick: the throttle's, or 1; exactly one call
     *                of every {@code interval} ticks falls in the window of {@code step} ticks tested here
     */
    public static boolean retryDue(long gameTime, long posHash, int interval, int step) {
        return Math.floorMod(gameTime + posHash, (long) interval) < step;
    }

    /** The contents of the block container at x, y, z changed: wake it and the six hoppers around it. */
    public void changed(int x, int y, int z) {
        if (this.sleeping.isEmpty()) return;
        this.wake(x, y, z);
        for (int[] side : SIDES) this.wake(x + side[0], y + side[1], z + side[2]);
    }

    /**
     * An entity covering this box moved (or appeared). An item wakes the hoppers that could suck it: the one
     * it is in and the one under it. A container entity also wakes every hopper next to it, which could push
     * into it or pull from it.
     */
    public void entityMoved(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, boolean container) {
        if (this.sleeping.isEmpty()) return;
        int around = container ? 1 : 0;
        int x1 = floor(maxX) + around, y1 = floor(maxY) + around, z1 = floor(maxZ) + around;
        for (int x = floor(minX) - around; x <= x1; x++) {
            for (int z = floor(minZ) - around; z <= z1; z++) {
                for (int y = floor(minY) - 1; y <= y1; y++) this.wake(x, y, z);
            }
        }
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    /** Same packing as BlockPos#asLong: 26 bits of x, 26 of z, 12 of y. */
    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}
