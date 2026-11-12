package dev.btc.core.performance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpacityIndexTest {

    private static long[] bitsWith(int x, int y, int z) {
        long[] bits = new long[OpacityIndex.WORDS];
        OpacityIndex.mark(bits, x, y, z);
        return bits;
    }

    @Test
    @DisplayName("a key gives its three section coordinates back, negative ones included")
    void aKeyRoundTrips() {
        long key = OpacityIndex.key(-3, -4, 5);

        assertEquals(-3, OpacityIndex.sectionX(key));
        assertEquals(-4, OpacityIndex.sectionY(key));
        assertEquals(5, OpacityIndex.sectionZ(key));
        assertEquals(OpacityIndex.key(-1, -1, -1), OpacityIndex.keyAt(-1, -16, -9));
    }

    @Test
    @DisplayName("a section nobody indexed hides nothing and is asked for")
    void anUnknownSectionHidesNothing() {
        OpacityIndex index = new OpacityIndex();

        assertFalse(index.opaque(40, 70, -20));
        assertArrayEquals(new long[] {OpacityIndex.keyAt(40, 70, -20)}, index.due(4));
    }

    @Test
    @DisplayName("an indexed section answers block by block, below zero as well")
    void anIndexedSectionAnswersBlockByBlock() {
        OpacityIndex index = new OpacityIndex();
        index.index(OpacityIndex.keyAt(-1, -1, -1), bitsWith(-1, -1, -1));

        assertTrue(index.opaque(-1, -1, -1));
        assertFalse(index.opaque(-2, -1, -1));
        assertFalse(index.opaque(-1, -2, -1));
        assertFalse(index.opaque(-1, -1, -2));
    }

    @Test
    @DisplayName("a block that changes is seen at once, in a section that held nothing opaque too")
    void aChangedBlockIsSeen() {
        OpacityIndex index = new OpacityIndex();
        index.index(OpacityIndex.keyAt(3, 3, 3), bitsWith(3, 3, 3));
        index.index(OpacityIndex.keyAt(20, 3, 3), null);

        index.blockChanged(3, 3, 3, false);
        index.blockChanged(4, 3, 3, true);
        index.blockChanged(20, 3, 3, true);

        assertFalse(index.opaque(3, 3, 3));
        assertTrue(index.opaque(4, 3, 3));
        assertTrue(index.opaque(20, 3, 3));
        assertFalse(index.opaque(21, 3, 3));
    }

    @Test
    @DisplayName("a change in a section not indexed yet leaves it unknown")
    void aChangeElsewhereIsIgnored() {
        OpacityIndex index = new OpacityIndex();

        index.blockChanged(3, 3, 3, true);

        assertFalse(index.opaque(3, 3, 3));
    }

    @Test
    @DisplayName("a forgotten section hides nothing again")
    void aForgottenSectionHidesNothing() {
        OpacityIndex index = new OpacityIndex();
        long key = OpacityIndex.keyAt(3, 3, 3);
        index.index(key, bitsWith(3, 3, 3));

        index.forget(key);

        assertFalse(index.opaque(3, 3, 3));
    }

    private static Set<Long> threeIndexed(OpacityIndex index) {
        Set<Long> indexed = new HashSet<>();
        for (int x = 0; x < 3; x++) {
            long key = OpacityIndex.key(x, 0, 0);
            index.index(key, null);
            indexed.add(key);
        }
        return indexed;
    }

    private static Set<Long> eightMissed(OpacityIndex index) {
        Set<Long> missed = new HashSet<>();
        for (int x = 10; x < 18; x++) {
            index.opaque(x * 16, 0, 0);
            missed.add(OpacityIndex.key(x, 0, 0));
        }
        return missed;
    }

    private static int among(Set<Long> keys, long[] due) {
        int count = 0;
        for (long key : due) {
            count += keys.contains(key) ? 1 : 0;
        }
        return count;
    }

    @Test
    @DisplayName("with nothing missed, one indexed section is read again per call, whatever the budget, and they come round in turn")
    void oneIndexedSectionIsReadAgainPerCall() {
        OpacityIndex index = new OpacityIndex();
        Set<Long> indexed = threeIndexed(index);

        Set<Long> seen = new HashSet<>();
        for (int call = 0; call < 3; call++) {
            long[] due = index.due(16);
            assertEquals(1, due.length, "a budget of 16 does not read 16 indexed sections again");
            seen.add(due[0]);
        }
        assertEquals(indexed, seen, "three calls go round three sections");
        assertEquals(1, among(indexed, index.due(16)), "and the round starts again");
    }

    @Test
    @DisplayName("the misses take their whole budget, and the section read again comes on top")
    void theMissesTakeTheirWholeBudget() {
        OpacityIndex index = new OpacityIndex();
        Set<Long> indexed = threeIndexed(index);
        Set<Long> missed = eightMissed(index);

        long[] due = index.due(4);

        assertEquals(4, among(missed, due));
        assertEquals(1, among(indexed, due));
        assertEquals(5, due.length);
    }

    @Test
    @DisplayName("a budget of one still reads an indexed section again while misses wait")
    void aBudgetOfOneStillRefreshes() {
        OpacityIndex index = new OpacityIndex();
        Set<Long> indexed = threeIndexed(index);
        Set<Long> missed = eightMissed(index);

        long[] due = index.due(1);

        assertEquals(1, among(missed, due));
        assertEquals(1, among(indexed, due));
    }
}
