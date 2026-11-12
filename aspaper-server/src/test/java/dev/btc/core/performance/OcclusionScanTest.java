package dev.btc.core.performance;

import it.unimi.dsi.fastutil.ints.IntSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One section, the eye at x = 2.5 and a mob at x = 12.5 on the same line, a wall at x = 7 between
 * them or not.
 */
class OcclusionScanTest {

    private static final int RANGE = 32;
    private static final int WALL_X = 7;
    private static final int MOB = 41;
    private static final double EYE_X = 2.5D;
    private static final double EYE_Y = 8.5D;
    private static final double EYE_Z = 8.5D;
    private static final OcclusionScan.Candidate MOB_BOX = new OcclusionScan.Candidate(MOB, 12.2D, 8.0D, 8.2D, 12.8D, 9.8D, 8.8D);

    private static long[] wall() {
        return wallAt(WALL_X);
    }

    private static long[] wallAt(int x) {
        long[] bits = new long[OpacityIndex.WORDS];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                OpacityIndex.mark(bits, x, y, z);
            }
        }
        return bits;
    }

    private static OpacityIndex indexOf(long[] bits) {
        OpacityIndex index = new OpacityIndex();
        index.index(OpacityIndex.keyAt(0, 0, 0), bits);
        return index;
    }

    private static IntSet hidden(int range, OpacityIndex index, OcclusionScan.Candidate box) {
        return new OcclusionScan(range).hidden(index, EYE_X, EYE_Y, EYE_Z, List.of(box));
    }

    @Test
    @DisplayName("a mob behind a wall is hidden")
    void behindAWallIsHidden() {
        assertEquals(IntSet.of(MOB), hidden(RANGE, indexOf(wall()), MOB_BOX));
    }

    @Test
    @DisplayName("a mob with nothing in front is seen")
    void withNothingInFrontIsSeen() {
        assertTrue(hidden(RANGE, indexOf(null), MOB_BOX).isEmpty());
    }

    @Test
    @DisplayName("one block out of the wall on the line of sight and the mob is seen")
    void throughAHoleIsSeen() {
        OpacityIndex index = indexOf(wall());

        index.blockChanged(WALL_X, 8, 8, false);

        assertTrue(hidden(RANGE, index, MOB_BOX).isEmpty());
    }

    @Test
    @DisplayName("a wall the index does not know hides nothing, and its section is asked for")
    void anUnknownWallHidesNothing() {
        OpacityIndex index = new OpacityIndex();

        assertTrue(hidden(RANGE, index, MOB_BOX).isEmpty());
        assertEquals(OpacityIndex.keyAt(0, 0, 0), index.due(1)[0]);
    }

    @Test
    @DisplayName("a mob past the range is seen, wall or not")
    void pastTheRangeIsSeen() {
        assertTrue(hidden(OcclusionScan.MIN_RANGE, indexOf(wall()), MOB_BOX).isEmpty());
    }

    @Test
    @DisplayName("a box with a side longer than the bound is seen without a ray, a box just under it is hidden by the same wall")
    void aGiantBoxIsSeen() {
        int nearWall = 3; // right in front of the eye: everything past it is behind
        double from = 5.0D;
        OcclusionScan.Candidate giant = new OcclusionScan.Candidate(MOB, from, 8.0D, 8.2D, from + OcclusionScan.MAX_BOX_SIDE + 0.5D, 9.0D, 8.8D);
        OcclusionScan.Candidate large = new OcclusionScan.Candidate(MOB, from, 8.0D, 8.2D, from + OcclusionScan.MAX_BOX_SIDE, 9.0D, 8.8D);

        assertTrue(hidden(RANGE, indexOf(wallAt(nearWall)), giant).isEmpty());
        assertEquals(IntSet.of(MOB), hidden(RANGE, indexOf(wallAt(nearWall)), large));
    }

    @Test
    @DisplayName("a ray that fails reaches the caller: nothing is printed and swallowed box by box")
    void aFailingRayReachesTheCaller() {
        OpacityIndex none = null; // the first block a ray asks for fails

        assertThrows(NullPointerException.class, () -> hidden(RANGE, none, MOB_BOX));
    }

    @Test
    @DisplayName("a box the eye is in is seen")
    void aroundTheEyeIsSeen() {
        OcclusionScan.Candidate around = new OcclusionScan.Candidate(MOB, 2.0D, 8.0D, 8.0D, 3.0D, 9.0D, 9.0D);

        assertTrue(hidden(RANGE, indexOf(wall()), around).isEmpty());
    }

    @Test
    @DisplayName("the range is kept between its bounds")
    void theRangeIsBounded() {
        assertEquals(OcclusionScan.MIN_RANGE, OcclusionScan.boundedRange(0));
        assertEquals(64, OcclusionScan.boundedRange(64));
        assertEquals(OcclusionScan.MAX_RANGE, OcclusionScan.boundedRange(4096));
    }

    @Test
    @DisplayName("an always-visible type, a glowing, named or mounted entity is never hidden; any other may be")
    void whatIsNeverHidden() {
        assertFalse(OcclusionScan.neverHidden(false, false, false, false));
        assertTrue(OcclusionScan.neverHidden(true, false, false, false));
        assertTrue(OcclusionScan.neverHidden(false, true, false, false));
        assertTrue(OcclusionScan.neverHidden(false, false, true, false));
        assertTrue(OcclusionScan.neverHidden(false, false, false, true));
    }
}
