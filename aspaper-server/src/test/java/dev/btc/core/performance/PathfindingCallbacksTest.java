package dev.btc.core.performance;

import dev.btc.core.async.path.PathfindingCallbacks;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which mobs async pathfinding must leave alone: those whose class redefines a callback the node
 * evaluator would otherwise call on a pool thread. Classes are only inspected, never initialised,
 * so no server is needed.
 */
class PathfindingCallbacksTest {

    static class Base {
        public void onPathfindingStart() {
        }

        public void onPathfindingDone() {
        }
    }

    static class Plain extends Base {
    }

    static class RewritesDone extends Base {
        @Override
        public void onPathfindingDone() {
        }
    }

    static class InheritsTheRewrite extends RewritesDone {
    }

    @Test
    @DisplayName("a class that leaves a callback alone does not redefine it")
    void anUntouchedCallbackIsNotRedefined() {
        assertFalse(PathfindingCallbacks.redefines(Plain.class, Base.class, "onPathfindingDone"));
        assertFalse(PathfindingCallbacks.redefines(RewritesDone.class, Base.class, "onPathfindingStart"));
    }

    @Test
    @DisplayName("a class that overrides a callback redefines it, and so do its subclasses")
    void anOverriddenCallbackIsRedefined() {
        assertTrue(PathfindingCallbacks.redefines(RewritesDone.class, Base.class, "onPathfindingDone"));
        assertTrue(PathfindingCallbacks.redefines(InheritsTheRewrite.class, Base.class, "onPathfindingDone"));
    }

    @Test
    @DisplayName("a callback that cannot be read counts as redefined: that mob keeps the synchronous search")
    void anUnreadableCallbackCountsAsRedefined() {
        assertTrue(PathfindingCallbacks.redefines(Plain.class, Base.class, "noSuchCallback"));
    }

    @Test
    @DisplayName("the sniffer is left alone, an ordinary mob is not: Mob still has both callbacks under these names")
    void theRealMobClasses() {
        assertTrue(PathfindingCallbacks.redefinedBy(Sniffer.class));
        assertFalse(PathfindingCallbacks.redefinedBy(Zombie.class));
    }
}
