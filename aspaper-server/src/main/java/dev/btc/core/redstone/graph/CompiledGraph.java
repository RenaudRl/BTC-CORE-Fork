package dev.btc.core.redstone.graph;

import java.util.HashMap;
import java.util.Map;

/**
 * A redstone circuit compiled into a directed weighted graph.
 *
 * <p>A graph covers one bounding box of one world. While it exists, the blocks inside that box are
 * simulated here and the world copy is stale until the next write-back; the manager owning the
 * graph is responsible for invalidating it as soon as the box is edited.
 */
public final class CompiledGraph {

    private final Node[] nodes;

    /** Block position (packed) to node index, for translating world events into graph events. */
    private final Map<Long, Integer> index;

    private final TickQueue queue = new TickQueue();

    /** Inclusive bounding box of every compiled block, packed as min/max on each axis. */
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public CompiledGraph(final Node[] nodes, final int minX, final int minY, final int minZ,
                         final int maxX, final int maxY, final int maxZ) {
        this.nodes = nodes;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.index = HashMap.newHashMap(nodes.length);
        for (int i = 0; i < nodes.length; i++) {
            this.index.put(nodes[i].pos, i);
        }
    }

    public Node[] nodes() {
        return this.nodes;
    }

    public TickQueue queue() {
        return this.queue;
    }

    public int size() {
        return this.nodes.length;
    }

    /** Node index at this packed position, or {@code -1} when the position is not part of the graph. */
    public int nodeAt(final long packedPos) {
        return this.index.getOrDefault(packedPos, -1);
    }

    /** True when the coordinates fall inside the compiled bounding box. */
    public boolean contains(final int x, final int y, final int z) {
        return x >= this.minX && x <= this.maxX
            && y >= this.minY && y <= this.maxY
            && z >= this.minZ && z <= this.maxZ;
    }

    /** True when the graph has settled and can be written back to the world. */
    public boolean isQuiescent() {
        return this.queue.isEmpty();
    }

    public int minX() {
        return this.minX;
    }

    public int minY() {
        return this.minY;
    }

    public int minZ() {
        return this.minZ;
    }

    public int maxX() {
        return this.maxX;
    }

    public int maxY() {
        return this.maxY;
    }

    public int maxZ() {
        return this.maxZ;
    }
}
