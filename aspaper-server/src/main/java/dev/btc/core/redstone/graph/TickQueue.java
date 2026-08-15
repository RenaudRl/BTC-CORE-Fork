package dev.btc.core.redstone.graph;

import java.util.Arrays;

/**
 * Scheduled ticks for a compiled graph, bucketed by delay and priority.
 *
 * <p>Vanilla stores scheduled ticks in a sorted set keyed by (time, priority, insertion order),
 * which costs a comparison-based insert per event. A compiled graph knows two things vanilla does
 * not: delays are small and bounded, and priorities are a fixed set of four. That turns the whole
 * structure into a rotating ring of {@code delay x priority} FIFO queues, where scheduling and
 * polling are both O(1) and allocation-free once warm.
 *
 * <p>The buckets are {@code int} arrays rather than {@code ArrayDeque<Integer>}. A deque of boxed
 * integers allocates an {@code Integer} for every node index above 127 on every single schedule, on
 * a path that runs every game tick — the exact opposite of what compiling a circuit is for.
 */
public final class TickQueue {

    /** Longest delay a vanilla redstone component can schedule (repeater on 4 ticks). */
    public static final int MAX_DELAY = 4;

    private static final int PRIORITIES = TickPriority.ORDER.length;

    private static final int INITIAL_CAPACITY = 8;

    /** Ring of buckets, indexed by {@code (offset * PRIORITIES) + priority}. */
    private final int[][] buckets = new int[MAX_DELAY * PRIORITIES][];

    /** Number of entries held by each bucket. */
    private final int[] counts = new int[MAX_DELAY * PRIORITIES];

    /** How far each bucket has been drained during the current tick. */
    private final int[] drained = new int[MAX_DELAY * PRIORITIES];

    /** Index into the ring representing "this tick". */
    private int cursor;

    private int size;

    public TickQueue() {
        for (int i = 0; i < this.buckets.length; i++) {
            this.buckets[i] = new int[INITIAL_CAPACITY];
        }
    }

    /**
     * Schedules a node to be ticked later.
     *
     * @param node     index of the node in the graph
     * @param delay    number of ticks to wait, 1 to {@value #MAX_DELAY}
     * @param priority ordering among the ticks landing on the same game tick
     */
    public void schedule(final int node, final int delay, final TickPriority priority) {
        if (delay < 1 || delay > MAX_DELAY) {
            throw new IllegalArgumentException("delay must be within 1.." + MAX_DELAY + ", got " + delay);
        }
        final int slot = ((this.cursor + delay - 1) % MAX_DELAY) * PRIORITIES + priority.ordinal();
        if (this.counts[slot] == this.buckets[slot].length) {
            this.buckets[slot] = Arrays.copyOf(this.buckets[slot], this.counts[slot] * 2);
        }
        this.buckets[slot][this.counts[slot]++] = node;
        this.size++;
    }

    /**
     * Returns the next node due this game tick, in priority order, or {@code -1} once this tick's
     * buckets are exhausted.
     *
     * <p>Polling rather than draining into a callback keeps the caller's loop free of a capturing
     * lambda, which would otherwise be allocated on every world tick of every compiled zone.
     * A node scheduled while this loop runs is picked up correctly: with a minimum delay of one
     * tick it always lands past the current cursor.
     */
    public int pollDue() {
        final int base = this.cursor * PRIORITIES;
        for (int priority = 0; priority < PRIORITIES; priority++) {
            final int slot = base + priority;
            if (this.drained[slot] < this.counts[slot]) {
                this.size--;
                return this.buckets[slot][this.drained[slot]++];
            }
        }
        return -1;
    }

    /** Clears this tick's buckets and moves the ring on by one tick. */
    public void advance() {
        final int base = this.cursor * PRIORITIES;
        for (int priority = 0; priority < PRIORITIES; priority++) {
            final int slot = base + priority;
            // A node scheduled onto this slot after it was drained belongs to a later pass of the
            // ring, four ticks from now, so it must survive rather than be dropped here.
            final int carried = this.counts[slot] - this.drained[slot];
            if (carried > 0) {
                System.arraycopy(this.buckets[slot], this.drained[slot], this.buckets[slot], 0, carried);
            }
            this.counts[slot] = carried;
            this.drained[slot] = 0;
        }
        this.cursor = (this.cursor + 1) % MAX_DELAY;
    }

    /** True when nothing is scheduled at all: the graph has reached quiescence. */
    public boolean isEmpty() {
        return this.size == 0;
    }

    public int size() {
        return this.size;
    }

    public void clear() {
        Arrays.fill(this.counts, 0);
        Arrays.fill(this.drained, 0);
        this.size = 0;
        this.cursor = 0;
    }
}
