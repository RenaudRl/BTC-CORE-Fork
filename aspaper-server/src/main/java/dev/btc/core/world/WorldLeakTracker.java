package dev.btc.core.world;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The worlds that left the server and are still in memory ({@code world.leak-diagnostic}): each is
 * followed through a weak reference, which keeps nothing alive.
 *
 * <p>A world still reachable is not a proven leak: the collector may simply not have got to it.
 * That is why a world is told with its age and the collections run since its unload, and reported
 * once.
 */
public final class WorldLeakTracker {

    /** Past this many unloaded worlds still in memory, the oldest is no longer followed. */
    public static final int MAX_TRACKED = 512;

    /** A world that left the server and is still reachable. */
    public record Retained(String world, long ageNanos, long collections) {
    }

    private static final class Unloaded {
        final String world;
        final Reference<Object> level;
        final long atNanos;
        final long collectionsThen;
        boolean reported;

        Unloaded(String world, Reference<Object> level, long atNanos, long collectionsThen) {
            this.world = world;
            this.level = level;
            this.atNanos = atNanos;
            this.collectionsThen = collectionsThen;
        }

        Retained retained(long nowNanos, long collections) {
            return new Retained(this.world, nowNanos - this.atNanos, collections - this.collectionsThen);
        }
    }

    private final Deque<Unloaded> unloaded = new ArrayDeque<>();

    /**
     * @param collections how many garbage collections the JVM has run so far
     */
    public void unloaded(String world, Object level, long nowNanos, long collections) {
        this.follow(world, new WeakReference<>(level), nowNanos, collections);
    }

    synchronized void follow(String world, Reference<Object> level, long nowNanos, long collections) {
        if (this.unloaded.size() >= MAX_TRACKED) this.unloaded.removeFirst();
        this.unloaded.addLast(new Unloaded(world, level, nowNanos, collections));
    }

    /** The worlds still in memory {@code graceNanos} after their unload, each returned once: to log. */
    public synchronized List<Retained> overdue(long nowNanos, long graceNanos, long collections) {
        this.forgetCollected();
        List<Retained> overdue = new ArrayList<>();
        for (Unloaded world : this.unloaded) {
            if (world.reported || nowNanos - world.atNanos < graceNanos) continue;
            world.reported = true;
            overdue.add(world.retained(nowNanos, collections));
        }
        return overdue;
    }

    /** Every unloaded world still in memory, the oldest unload first. */
    public synchronized List<Retained> retained(long nowNanos, long collections) {
        this.forgetCollected();
        List<Retained> retained = new ArrayList<>();
        for (Unloaded world : this.unloaded) retained.add(world.retained(nowNanos, collections));
        return retained;
    }

    private void forgetCollected() {
        this.unloaded.removeIf(world -> world.level.get() == null);
    }
}
