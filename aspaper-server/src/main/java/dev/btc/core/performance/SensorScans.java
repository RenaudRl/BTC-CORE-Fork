package dev.btc.core.performance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The scans of the AI sensors written as plain loops ({@code performance.ai-sensor-loops}).
 *
 * <p>Each method gives what the stream it stands for gives: the same elements, in the same order,
 * the predicate called on the same elements and no further. Only the stream machinery goes away,
 * which is what a villager farm pays for on every sensor tick.
 *
 * <p>The removal of the streams of {@code PlayerSensor}, {@code GolemSensor}, {@code MobSensor} and
 * {@code TemptingSensor} is Leaf's (HaHaWTH, Taiyou06; MIT), https://github.com/Winds-Studio/Leaf.
 */
public final class SensorScans {

    private SensorScans() {}

    /** {@code stream().filter(keep).collect(toList())}: a new mutable list, encounter order kept. */
    public static <T> List<T> kept(Iterable<? extends T> candidates, Predicate<? super T> keep) {
        List<T> kept = new ArrayList<>();
        for (T candidate : candidates) {
            if (keep.test(candidate)) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    /** {@code stream().filter(keep).sorted(order).collect(toList())}: both sorts are stable. */
    public static <T> List<T> keptSorted(Iterable<? extends T> candidates, Predicate<? super T> keep, Comparator<? super T> order) {
        List<T> kept = kept(candidates, keep);
        kept.sort(order);
        return kept;
    }

    /** {@code stream().anyMatch(test)}: stops at the first match. */
    public static <T> boolean any(Iterable<? extends T> candidates, Predicate<? super T> test) {
        for (T candidate : candidates) {
            if (test.test(candidate)) {
                return true;
            }
        }
        return false;
    }
}
