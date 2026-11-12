/*
 * Derived from Petal "Async Pathfinding" (peaches94, GPL-3.0-only, https://github.com/Bloom-host/Petal),
 * fixed and updated by Kaiiju (GPL-3.0-only, https://github.com/KaiijuMC/Kaiiju), as carried by Leaf
 * (org.dreeam.leaf.async.path.NodeEvaluatorCache), GPL-3.0.
 */
package dev.btc.core.async.path;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.Objects;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;

/**
 * Evaluators lent to one search at a time, so a search never shares one with the navigation that
 * asked for it. Both maps are only touched under the class lock; the heap and the neighbour array
 * are per thread.
 */
public final class NodeEvaluatorCache {

    private static final Int2ObjectOpenHashMap<ArrayDeque<NodeEvaluator>> NODE_EVALUATORS = new Int2ObjectOpenHashMap<>();
    private static final Object2ObjectOpenHashMap<NodeEvaluator, NodeEvaluatorGenerator> NODE_EVALUATOR_TO_GENERATOR = new Object2ObjectOpenHashMap<>();

    public static final ThreadLocal<BinaryHeap> HEAP_LOCAL = ThreadLocal.withInitial(BinaryHeap::new);
    public static final ThreadLocal<Node[]> NEIGHBORS_LOCAL = ThreadLocal.withInitial(() -> new Node[32]);

    private NodeEvaluatorCache() {
    }

    public static synchronized NodeEvaluator takeNodeEvaluator(final NodeEvaluatorGenerator generator, final NodeEvaluator localNodeEvaluator) {
        final int nodeEvaluatorFeatures = NodeEvaluatorFeatures.fromNodeEvaluator(localNodeEvaluator);
        NodeEvaluator nodeEvaluator = NODE_EVALUATORS.computeIfAbsent(nodeEvaluatorFeatures, key -> new ArrayDeque<>()).poll();

        if (nodeEvaluator == null) {
            nodeEvaluator = generator.generate(NodeEvaluatorFeatures.unpack(nodeEvaluatorFeatures));
        }

        NODE_EVALUATOR_TO_GENERATOR.put(nodeEvaluator, generator);

        return nodeEvaluator;
    }

    public static synchronized void returnNodeEvaluator(final NodeEvaluator nodeEvaluator) {
        final NodeEvaluatorGenerator generator = NODE_EVALUATOR_TO_GENERATOR.remove(nodeEvaluator);
        Objects.requireNonNull(generator, "NodeEvaluator already returned");

        final int feature = NodeEvaluatorFeatures.fromNodeEvaluator(nodeEvaluator);
        NODE_EVALUATORS.computeIfAbsent(feature, key -> new ArrayDeque<>()).offer(nodeEvaluator);
    }

    public static synchronized void removeNodeEvaluator(final NodeEvaluator nodeEvaluator) {
        NODE_EVALUATOR_TO_GENERATOR.remove(nodeEvaluator);
    }
}
