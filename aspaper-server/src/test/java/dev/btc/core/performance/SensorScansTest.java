package dev.btc.core.performance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** The loops must be indistinguishable from the streams they stand for. */
class SensorScansTest {

    private static final long SEED = 20261112L;
    private static final int ROUNDS = 200;
    private static final int MAX_SIZE = 24;
    /** Few distinct distances, so that ties are the rule: the order among equals is what is tested. */
    private static final int DISTINCT_DISTANCES = 4;

    private record Seen(int id, int distance, boolean kept) {}

    private static List<Seen> randomScan(Random random) {
        List<Seen> scan = new ArrayList<>();
        int size = random.nextInt(MAX_SIZE + 1);
        for (int id = 0; id < size; id++) {
            scan.add(new Seen(id, random.nextInt(DISTINCT_DISTANCES), random.nextBoolean()));
        }
        return scan;
    }

    @Test
    @DisplayName("kept gives the list of filter + collect, in encounter order, and a list of its own")
    void keptMatchesTheStream() {
        Random random = new Random(SEED);
        for (int round = 0; round < ROUNDS; round++) {
            List<Seen> scan = randomScan(random);

            List<Seen> kept = SensorScans.kept(scan, Seen::kept);

            assertEquals(scan.stream().filter(Seen::kept).collect(Collectors.toList()), kept);
            assertNotSame(scan, kept);
            kept.add(new Seen(-1, 0, true)); // mutable, as Collectors.toList() gives
        }
    }

    @Test
    @DisplayName("keptSorted gives the list of filter + sorted + collect, equals left in encounter order")
    void keptSortedMatchesTheStream() {
        Comparator<Seen> nearestFirst = Comparator.comparingInt(Seen::distance);
        Random random = new Random(SEED);
        for (int round = 0; round < ROUNDS; round++) {
            List<Seen> scan = randomScan(random);

            assertEquals(
                scan.stream().filter(Seen::kept).sorted(nearestFirst).collect(Collectors.toList()),
                SensorScans.keptSorted(scan, Seen::kept, nearestFirst));
        }
    }

    @Test
    @DisplayName("any answers as anyMatch does and tests the same elements, none after the first match")
    void anyMatchesTheStream() {
        Random random = new Random(SEED);
        for (int round = 0; round < ROUNDS; round++) {
            List<Seen> scan = randomScan(random);
            List<Integer> testedByStream = new ArrayList<>();
            List<Integer> testedByLoop = new ArrayList<>();

            boolean expected = scan.stream().anyMatch(recording(testedByStream));
            boolean actual = SensorScans.any(scan, recording(testedByLoop));

            assertEquals(expected, actual);
            assertEquals(testedByStream, testedByLoop);
        }
    }

    private static Predicate<Seen> recording(List<Integer> tested) {
        return seen -> {
            tested.add(seen.id());
            return seen.kept();
        };
    }
}
