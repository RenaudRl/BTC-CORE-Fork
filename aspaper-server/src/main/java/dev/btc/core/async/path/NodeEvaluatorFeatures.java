/*
 * Derived from Petal "Async Pathfinding" (peaches94, GPL-3.0-only, https://github.com/Bloom-host/Petal),
 * fixed and updated by Kaiiju (GPL-3.0-only, https://github.com/KaiijuMC/Kaiiju), as carried by Leaf
 * (org.dreeam.leaf.async.path.NodeEvaluatorFeatures), GPL-3.0.
 */
package dev.btc.core.async.path;

import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;

/** What makes two evaluators interchangeable, packed into the key of {@link NodeEvaluatorCache}. */
public record NodeEvaluatorFeatures(
    NodeEvaluatorType type,
    boolean canPassDoors,
    boolean canFloat,
    boolean canWalkOverFences,
    boolean canOpenDoors,
    boolean allowBreaching
) {

    private static final NodeEvaluatorType[] NODE_EVALUATOR_TYPES = NodeEvaluatorType.values();

    public static int fromNodeEvaluator(final NodeEvaluator nodeEvaluator) {
        final NodeEvaluatorType type = NodeEvaluatorType.fromNodeEvaluator(nodeEvaluator);
        final boolean allowBreaching = nodeEvaluator instanceof SwimNodeEvaluator swimNodeEvaluator && swimNodeEvaluator.allowBreaching;
        return pack(type, nodeEvaluator.canPassDoors(), nodeEvaluator.canFloat(), nodeEvaluator.canWalkOverFences(), nodeEvaluator.canOpenDoors(), allowBreaching);
    }

    public static int pack(final NodeEvaluatorType type,
                           final boolean canPassDoors,
                           final boolean canFloat,
                           final boolean canWalkOverFences,
                           final boolean canOpenDoors,
                           final boolean allowBreaching) {
        int value = 0;
        value |= type.ordinal();
        if (canPassDoors) {
            value |= 0b100;
        }
        if (canFloat) {
            value |= 0b1000;
        }
        if (canWalkOverFences) {
            value |= 0b10000;
        }
        if (canOpenDoors) {
            value |= 0b100000;
        }
        if (allowBreaching) {
            value |= 0b1000000;
        }
        return value;
    }

    public static NodeEvaluatorFeatures unpack(final int value) {
        final NodeEvaluatorType type = NODE_EVALUATOR_TYPES[value & 0b11];
        final boolean canPassDoors = (value & 0b100) != 0;
        final boolean canFloat = (value & 0b1000) != 0;
        final boolean canWalkOverFences = (value & 0b10000) != 0;
        final boolean canOpenDoors = (value & 0b100000) != 0;
        final boolean allowBreaching = (value & 0b1000000) != 0;
        return new NodeEvaluatorFeatures(type, canPassDoors, canFloat, canWalkOverFences, canOpenDoors, allowBreaching);
    }
}
