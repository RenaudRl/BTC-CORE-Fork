package dev.btc.core.parallel;

/**
 * A value left by a caller for code further down the same call, where CraftBukkit and Paper use a
 * static rather than a parameter (the tree type of a growing sapling, the source block of a sculk
 * spread, whether redstone wire answers a signal query). One slot for the whole server while one
 * thread ticks the worlds, one per thread once each world has its own.
 *
 * <p>Callers pass {@code WorldTickLoop.PARALLEL}, a constant: with it off only the plain field is
 * touched.
 */
public final class ThreadOverride<T> {

    private final ThreadLocal<T> perThread;
    private T shared;

    public ThreadOverride(T initial) {
        this.shared = initial;
        this.perThread = ThreadLocal.withInitial(() -> initial);
    }

    public T get(boolean parallel) {
        return parallel ? this.perThread.get() : this.shared;
    }

    public void set(boolean parallel, T value) {
        if (parallel) this.perThread.set(value);
        else this.shared = value;
    }
}
