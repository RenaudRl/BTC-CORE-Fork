package dev.btc.core.world;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * What one world costs ({@code world.metrics}): the time of its {@code level.tick}, averaged over
 * the last {@link #WINDOW_TICKS} ticks with the worst of them, and what it held at its last tick.
 *
 * <p>Written by the thread that ticks the world, which allocates nothing here; read by any thread
 * through {@link #row}, from fields published at the end of each tick.
 */
public final class WorldTickMetrics {

    /** How many ticks the average and the worst are taken over: 30 seconds at 20 ticks a second. */
    public static final int WINDOW_TICKS = 600;

    /** One world, as the command shows it. */
    public record Row(String world, long averageNanos, long worstNanos, int chunks, int entities, int players) {
    }

    private final long[] window;
    private int next;
    private int filled;
    private long sum;
    private long worst;
    private long began;

    private volatile long averageNanos;
    private volatile long worstNanos;
    private volatile int chunks;
    private volatile int entities;
    private volatile int players;

    public WorldTickMetrics() {
        this(WINDOW_TICKS);
    }

    WorldTickMetrics(int windowTicks) {
        this.window = new long[windowTicks];
    }

    /** The world is about to tick. */
    public void begin(long nowNanos) {
        this.began = nowNanos;
    }

    /** The world has ticked: the tick that just ended takes the place of the oldest of the window. */
    public void end(long nowNanos, int chunks, int entities, int players) {
        long took = nowNanos - this.began;
        long evicted = this.window[this.next];
        this.window[this.next] = took;
        this.next = (this.next + 1) % this.window.length;
        if (this.filled < this.window.length) this.filled++;
        this.sum += took - evicted;
        if (took >= this.worst) this.worst = took;
        else if (evicted == this.worst) this.worst = this.worstOfWindow(); // the worst tick just left the window

        this.averageNanos = this.sum / this.filled;
        this.worstNanos = this.worst;
        this.chunks = chunks;
        this.entities = entities;
        this.players = players;
    }

    private long worstOfWindow() {
        long worst = 0L;
        for (int i = 0; i < this.filled; i++) worst = Math.max(worst, this.window[i]);
        return worst;
    }

    /** Any thread. */
    public Row row(String world) {
        return new Row(world, this.averageNanos, this.worstNanos, this.chunks, this.entities, this.players);
    }

    /** The {@code limit} worlds that cost the most on average, the costliest first. */
    public static List<Row> costliest(Collection<Row> rows, int limit) {
        return rows.stream().sorted(Comparator.comparingLong(Row::averageNanos).reversed()).limit(Math.max(0, limit)).toList();
    }
}
