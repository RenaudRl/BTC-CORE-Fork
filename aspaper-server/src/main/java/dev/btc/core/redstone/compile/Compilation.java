package dev.btc.core.redstone.compile;

import dev.btc.core.redstone.graph.CompiledGraph;

/**
 * The product of a successful compilation: the graph itself, plus the world bindings the manager
 * must keep in sync while the graph is live.
 *
 * @param graph              the compiled circuit
 * @param containerNodes     indices of the comparator nodes that read a container
 * @param containerPositions packed position of the container each of those comparators reads, in the
 *                           same order; when the container's contents change, the manager refreshes
 *                           {@code containerOverride} on the matching node
 */
public record Compilation(CompiledGraph graph, int[] containerNodes, long[] containerPositions) {

    public Compilation {
        if (containerNodes.length != containerPositions.length) {
            throw new IllegalArgumentException("container bindings must pair a node with a position");
        }
    }
}
