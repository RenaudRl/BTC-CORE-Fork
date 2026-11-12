/*
 * Derived from Petal "Async Pathfinding" (peaches94, GPL-3.0-only, https://github.com/Bloom-host/Petal),
 * fixed and updated by Kaiiju (GPL-3.0-only, https://github.com/KaiijuMC/Kaiiju), as carried by Leaf
 * (org.dreeam.leaf.async.path.NodeEvaluatorType), GPL-3.0.
 */
package dev.btc.core.async.path;

import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;

public enum NodeEvaluatorType {
    WALK,
    SWIM,
    AMPHIBIOUS,
    FLY;

    public static NodeEvaluatorType fromNodeEvaluator(final NodeEvaluator nodeEvaluator) {
        return switch (nodeEvaluator) {
            case SwimNodeEvaluator ignored -> SWIM;
            case FlyNodeEvaluator ignored -> FLY;
            case AmphibiousNodeEvaluator ignored -> AMPHIBIOUS;
            default -> WALK;
        };
    }
}
