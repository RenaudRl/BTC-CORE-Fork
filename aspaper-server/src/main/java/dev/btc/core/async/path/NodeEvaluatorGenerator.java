/*
 * Derived from Petal "Async Pathfinding" (peaches94, GPL-3.0-only, https://github.com/Bloom-host/Petal),
 * fixed and updated by Kaiiju (GPL-3.0-only, https://github.com/KaiijuMC/Kaiiju), as carried by Leaf
 * (org.dreeam.leaf.async.path.NodeEvaluatorGenerator and the generators it declares in each
 * navigation class), GPL-3.0.
 *
 * Changed for BTC-CORE: the three generators live here instead of in the navigation classes, and
 * there is none for amphibious navigation - AmphibiousNodeEvaluator rewrites the mob's maluses
 * between prepare() and done(), which must stay on the entity's thread.
 */
package dev.btc.core.async.path;

import dev.btc.core.config.BTCCoreConfig;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.jspecify.annotations.Nullable;

/** Builds a fresh evaluator of a navigation's kind for {@link NodeEvaluatorCache}. */
@FunctionalInterface
public interface NodeEvaluatorGenerator {

    NodeEvaluator generate(NodeEvaluatorFeatures nodeEvaluatorFeatures);

    NodeEvaluatorGenerator WALK = features -> configure(new WalkNodeEvaluator(), features);
    NodeEvaluatorGenerator FLY = features -> configure(new FlyNodeEvaluator(), features);
    NodeEvaluatorGenerator SWIM = features -> configure(new SwimNodeEvaluator(features.allowBreaching()), features);

    // What a navigation hands its PathFinder: null while the option is off, and the PathFinder then
    // searches on the calling thread exactly as vanilla does.

    static @Nullable NodeEvaluatorGenerator walk() {
        return BTCCoreConfig.asyncPathfindingEnabled ? WALK : null;
    }

    static @Nullable NodeEvaluatorGenerator fly() {
        return BTCCoreConfig.asyncPathfindingEnabled ? FLY : null;
    }

    static @Nullable NodeEvaluatorGenerator swim() {
        return BTCCoreConfig.asyncPathfindingEnabled ? SWIM : null;
    }

    private static NodeEvaluator configure(final NodeEvaluator nodeEvaluator, final NodeEvaluatorFeatures features) {
        nodeEvaluator.setCanPassDoors(features.canPassDoors());
        nodeEvaluator.setCanFloat(features.canFloat());
        nodeEvaluator.setCanWalkOverFences(features.canWalkOverFences());
        nodeEvaluator.setCanOpenDoors(features.canOpenDoors());
        return nodeEvaluator;
    }
}
