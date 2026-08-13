package dev.btc.core.redstone.graph;

import java.util.ArrayDeque;

/**
 * Scheduled ticks for a compiled graph, bucketed by delay and priority.
 *
 * <p>Vanilla stores scheduled ticks in a sorted set keyed by (time, priority, insertion order),
 * which costs a comparison-based insert per event. A compiled graph knows two things vanilla does
 * not: delays are small and bounded, and priorities are a fixed set of four. That turns the whole
 * structure into a rotating ring of {@code delay x priority} FIFO queues, where scheduling and
 * polling are both O(1) and allocation-free once warm.
 */
public final class TickQueue {

    /** Longest delay a vanilla redstone component can schedule (repeater on 4 ticks). */
    public static final int MAX_DELAY = 4;

    private static final int PRIORITIES = TickPriority.ORDER.length;

    /** Ring of queues, indexed by {@code (offset * PRIORITIES) + priority}. */
    private final ArrayDeque<Integer>[] queues;

    /** Index into the ring representing "this tick". */
    private int cursor;

    private int size;

    @SuppressWarnings("unchecked")
    public TickQueue() {
        this.queues = new ArrayDeque[MAX_DELAY * PRIORITIES];
        for (int i = 0; i < this.queues.length; i++) {
            this.queues[i] = new ArrayDeque<>();
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
        this.queues[slot].addLast(node);
        this.size++;
    }

    /**
     * Removes and returns the node indices due this game tick, in priority order, then advances the
     * ring by one tick.
     *
     * @param sink receives the due node indices in execution order
     */
    public void drainCurrentTick(final java.util.function.IntConsumer sink) {
        final int base = this.cursor * PRIORITIES;
        for (int priority = 0; priority < PRIORITIES; priority++) {
            final ArrayDeque<Integer> queue = this.queues[base + priority];
            while (!queue.isEmpty()) {
                this.size--;
                sink.accept(queue.pollFirst());
            }
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
        for (final ArrayDeque<Integer> queue : this.queues) {
            queue.clear();
        }
        this.size = 0;
        this.cursor = 0;
    }
}
