package dev.btc.core.redstone.graph;

import java.util.ArrayDeque;

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
 */
public final class GraphRuntime {

    private final CompiledGraph graph;

    /** Nodes waiting for an update within the current propagation burst. */
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();

    /** Guards against a node being queued twice in the same burst. */
    private final boolean[] queued;

    public GraphRuntime(final CompiledGraph graph) {
        this.graph = graph;
        this.queued = new boolean[graph.size()];
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
        final boolean[] changed = {false};
        this.graph.queue().drainCurrentTick(node -> {
            if (this.applyTick(node)) {
                changed[0] = true;
            }
        });
        if (this.settle()) {
            changed[0] = true;
        }
        return changed[0];
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

    // --- internals -----------------------------------------------------------------------------

    /** Drains the propagation work list until the graph stops changing. */
    private boolean settle() {
        boolean changed = false;
        while (!this.pending.isEmpty()) {
            final int node = this.pending.pollFirst();
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
        this.pending.addLast(node);
    }

    private void enqueueOutputs(final Node node) {
        for (final int output : node.outputs) {
            this.enqueue(output);
        }
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
                    return true;
                }
                if (!shouldBeLit && node.powered) {
                    // ...but take 2 ticks to go dark.
                    this.graph.queue().schedule(index, 2, TickPriority.NORMAL);
                }
                return false;
            }
            case PISTON, GENERIC_OUTPUT -> {
                return node.setPowered(node.backInput(nodes) > 0);
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
                    this.enqueueOutputs(node);
                    return true;
                }
                return false;
            }
            case COMPARATOR -> {
                if (node.setStrength(comparatorOutput(node, nodes))) {
                    this.enqueueOutputs(node);
                    return true;
                }
                return false;
            }
            case TORCH -> {
                final boolean shouldBeLit = node.backInput(nodes) == 0;
                if (node.setStrength(shouldBeLit ? 15 : 0)) {
                    this.enqueueOutputs(node);
                    return true;
                }
                return false;
            }
            case LAMP -> {
                // Re-check: the lamp may have been re-powered during the 2-tick delay.
                return node.setPowered(node.backInput(nodes) > 0);
            }
            case BUTTON -> {
                if (node.setStrength(0)) {
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
