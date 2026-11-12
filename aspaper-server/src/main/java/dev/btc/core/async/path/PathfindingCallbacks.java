package dev.btc.core.async.path;

import net.minecraft.world.entity.Mob;

/**
 * BTC-CORE addition to the Petal port. A node evaluator calls {@code Mob#onPathfindingStart} from
 * {@code prepare()} and {@code Mob#onPathfindingDone} from {@code done()}, and {@code done()} runs
 * where the A* ran. Both are empty in {@code Mob}; a class that redefines either (the sniffer
 * rewrites a malus there, a plugin's mob may do anything) keeps its searches on its own thread.
 */
public final class PathfindingCallbacks {

    private static final ClassValue<Boolean> REDEFINED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            return redefines(type, Mob.class, "onPathfindingStart") || redefines(type, Mob.class, "onPathfindingDone");
        }
    };

    private PathfindingCallbacks() {
    }

    /** Whether {@code type} or one of its ancestors below {@code Mob} redefines a pathfinding callback. Cached per class. */
    public static boolean redefinedBy(final Class<? extends Mob> type) {
        return REDEFINED.get(type);
    }

    /** Whether the public no-argument {@code method} that {@code type} inherits is declared below {@code base}. */
    public static boolean redefines(final Class<?> type, final Class<?> base, final String method) {
        try {
            return type.getMethod(method).getDeclaringClass() != base;
        } catch (final ReflectiveOperationException | LinkageError unreadable) {
            return true; // cannot tell: that class searches on its own thread, as vanilla does
        }
    }
}
