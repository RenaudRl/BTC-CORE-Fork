package dev.btc.core.redstone.graph;

/**
 * Executes a {@link CompiledGraph}.
 *
 * <p>Two distinct operations, exactly as vanilla separates them:
 * <ul>
 *   <li>an <em>update</em> recomputes what a node's state <em>should</em> be and may schedule a
 *       tick; instant components (dust, lamps turning on, output blocks) apply straight away;</li>
 *   <li>a <em>tick</em> is when a delayed component actually changes state.</li>
 * </ul>
 *
 * <p>Nothing here reads or writes the world. Propagation uses an explicit work list rather than
 * recursion, because a long dust line would otherwise blow the stack.
 *
 * <p>Every structure below is an {@code int} array sized once at construction. A graph that ticks
 * every game tick for hours must not allocate, and the work list has a hard bound: a node is queued
 * at most once at a time, so it can never hold more than one entry per node.
 */
public final class GraphRuntime {

    private final CompiledGraph graph;

    /** Ring buffer of nodes waiting for an update within the current propagation burst. */
    private final int[] pending;

    /** Guards against a node being queued twice in the same burst. */
    private final boolean[] queued;

    private int head;
    private int tail;

    /**
     * Nodes whose state moved since the last write-back.
     *
     * <p>Kept as a list rather than a flag per node so that the write-back visits what changed
     * instead of scanning the whole graph. On a large circuit almost nothing moves on most ticks,
     * and a full scan is then the most expensive thing left in the tick.
     */
    private final int[] dirty;

    private final boolean[] dirtyQueued;

    private int dirtyCount;

    public GraphRuntime(final CompiledGraph graph) {
        this.graph = graph;
        final int size = graph.size();
        // One slot per node, plus one: a ring buffer cannot distinguish full from empty otherwise.
        this.pending = new int[size + 1];
        this.queued = new boolean[size];
        this.dirty = new int[size];
        this.dirtyQueued = new boolean[size];
    }

    public CompiledGraph graph() {
        return this.graph;
    }

    /**
     * Runs every tick due this game tick, then settles the resulting propagation.
     *
     * @return true when at least one node changed, meaning a write-back is worth doing
     */
    public boolean tick() {
        final TickQueue queue = this.graph.queue();
        boolean moved = false;
        for (int node = queue.pollDue(); node >= 0; node = queue.pollDue()) {
            if (this.applyTick(node)) {
                moved = true;
            }
        }
        queue.advance();
        return this.settle() || moved;
    }

    /**
     * Drives an input node from outside the graph — a lever flipped, a plate stepped on, a
     * container's comparator override changed.
     *
     * @param node     index of the node to force
     * @param strength new output strength, 0-15
     * @return true when something changed
     */
    public boolean setInput(final int node, final int strength) {
        final Node target = this.graph.nodes()[node];
        if (!target.setStrength(Math.clamp(strength, 0, 15))) {
            return false;
        }
        this.markDirty(node);
        this.enqueueOutputs(target);
        this.settle();
        return true;
    }

    /**
     * Re-evaluates a node whose inputs moved outside the graph — the only case today being a
     * comparator whose container was refilled, since that value replaces its back input.
     *
     * @return true when something changed
     */
    public boolean refresh(final int node) {
        this.enqueue(node);
        return this.settle();
    }

    // --- write-back hand-off -------------------------------------------------------------------

    /** Number of entries at the head of {@link #dirtyNodes()} that are meaningful. */
    public int dirtyCount() {
        return this.dirtyCount;
    }

    /** Indices of the nodes that moved since the last {@link #clearDirty()}. */
    public int[] dirtyNodes() {
        return this.dirty;
    }

    /** Called by the write-back once it has pushed every dirty node into the world. */
    public void clearDirty() {
        for (int i = 0; i < this.dirtyCount; i++) {
            this.dirtyQueued[this.dirty[i]] = false;
        }
        this.dirtyCount = 0;
    }

    // --- internals -----------------------------------------------------------------------------

    /** Drains the propagation work list until the graph stops changing. */
    private boolean settle() {
        boolean changed = false;
        while (this.head != this.tail) {
            final int node = this.pending[this.head];
            this.head = this.head + 1 == this.pending.length ? 0 : this.head + 1;
            this.queued[node] = false;
            if (this.applyUpdate(node)) {
                changed = true;
            }
        }
        return changed;
    }

    private void enqueue(final int node) {
        if (this.queued[node]) {
            return;
        }
        this.queued[node] = true;
        this.pending[this.tail] = node;
        this.tail = this.tail + 1 == this.pending.length ? 0 : this.tail + 1;
    }

    private void enqueueOutputs(final Node node) {
        for (final int output : node.outputs) {
            this.enqueue(output);
        }
    }

    private void markDirty(final int node) {
        if (this.dirtyQueued[node]) {
            return;
        }
        this.dirtyQueued[node] = true;
        this.dirty[this.dirtyCount++] = node;
    }

    /**
     * Recomputes a node after one of its inputs moved. Instant components apply immediately;
     * delayed ones schedule a tick.
     */
    private boolean applyUpdate(final int index) {
        final Node[] nodes = this.graph.nodes();
        final Node node = nodes[index];

        switch (node.type) {
            case WIRE -> {
                if (node.setStrength(node.backInput(nodes))) {
                    this.markDirty(index);
                    this.enqueueOutputs(node);
                    return true;
                }
                return false;
            }
            case REPEATER -> {
                node.locked = node.sideInput(nodes) > 0;
                if (node.locked) {
                    return false;
                }
                final boolean shouldBePowered = node.backInput(nodes) > 0;
                if (shouldBePowered != node.powered) {
                    this.graph.queue().schedule(index, node.repeaterDelay, repeaterPriority(node, shouldBePowered));
                }
                return false;
            }
            case COMPARATOR -> {
                if (comparatorOutput(node, nodes) != node.strength) {
                    this.graph.queue().schedule(index, 1, node.facingDiode ? TickPriority.HIGH : TickPriority.NORMAL);
                }
                return false;
            }
            case TORCH -> {
                // A torch is lit when the block it is attached to is NOT powered.
                final boolean shouldBeLit = node.backInput(nodes) == 0;
                if (shouldBeLit != node.powered) {
                    this.graph.queue().schedule(index, 1, TickPriority.NORMAL);
                }
                return false;
            }
            case LAMP -> {
                final boolean shouldBeLit = node.backInput(nodes) > 0;
                if (shouldBeLit && !node.powered) {
                    // Lamps light up instantly...
                    node.setPowered(true);
                    this.markDirty(index);
                    return true;
                }
                if (!shouldBeLit && node.powered) {
                    // ...but take 2 ticks to go dark.
                    this.graph.queue().schedule(index, 2, TickPriority.NORMAL);
                }
                return false;
            }
            case PISTON, GENERIC_OUTPUT -> {
                if (node.setPowered(node.backInput(nodes) > 0)) {
                    this.markDirty(index);
                    return true;
                }
                return false;
            }
            case LEVER, BUTTON, PRESSURE_PLATE, CONSTANT -> {
                // Driven from outside the graph only.
                return false;
            }
        }
        return false;
    }

    /** Applies the state change a scheduled tick was waiting for. */
    private boolean applyTick(final int index) {
        final Node[] nodes = this.graph.nodes();
        final Node node = nodes[index];

        switch (node.type) {
            case REPEATER -> {
                if (node.locked) {
                    return false;
                }
                final boolean shouldBePowered = node.backInput(nodes) > 0;
                if (node.setStrength(shouldBePowered ? 15 : 0)) {
                    this.markDirty(index);
                    this.enqueueOutputs(node);
                    return true;
                }
                return false;
            }
            case COMPARATOR -> {
                if (node.setStrength(comparatorOutput(node, nodes))) {
                    this.markDirty(index);
                    this.enqueueOutputs(node);
                    return true;
                }
                return false;
            }
            case TORCH -> {
                final boolean shouldBeLit = node.backInput(nodes) == 0;
                if (node.setStrength(shouldBeLit ? 15 : 0)) {
                    this.markDirty(index);
                    this.enqueueOutputs(node);
                    return true;
                }
                return false;
            }
            case LAMP -> {
                // Re-check: the lamp may have been re-powered during the 2-tick delay.
                if (node.setPowered(node.backInput(nodes) > 0)) {
                    this.markDirty(index);
                    return true;
                }
                return false;
            }
            case BUTTON -> {
                if (node.setStrength(0)) {
                    this.markDirty(index);
                    this.enqueueOutputs(node);
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Comparator output: in compare mode it passes its back input through unless a side input is
     * stronger; in subtract mode it subtracts the side input. A container override, when present,
     * replaces the back input.
     */
    private static int comparatorOutput(final Node node, final Node[] nodes) {
        final int back = Math.max(node.backInput(nodes), node.containerOverride);
        final int side = node.sideInput(nodes);
        if (node.subtractMode) {
            return Math.max(0, back - side);
        }
        return side > back ? 0 : back;
    }

    /**
     * Vanilla raises a repeater's tick priority when it feeds another diode, and again when it is
     * turning off, so that chains resolve in the same order as uncompiled redstone.
     */
    private static TickPriority repeaterPriority(final Node node, final boolean turningOn) {
        if (node.facingDiode) {
            return TickPriority.HIGHEST;
        }
        return turningOn ? TickPriority.HIGH : TickPriority.HIGHER;
    }
}
