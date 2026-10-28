package dev.btc.core.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import org.junit.jupiter.api.Test;

class OverlayIndexTest {

    private static Long2ObjectMap<Short2ObjectMap<String>> cells(final Object... xyzState) {
        final Long2ObjectMap<Short2ObjectMap<String>> batch = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < xyzState.length; i += 4) {
            OverlayIndex.addCell(batch, (int) xyzState[i], (int) xyzState[i + 1], (int) xyzState[i + 2], (String) xyzState[i + 3]);
        }
        return batch;
    }

    private static Long2ObjectMap<ShortSet> positions(final int... xyz) {
        final Long2ObjectMap<ShortSet> batch = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < xyz.length; i += 3) {
            OverlayIndex.addPosition(batch, xyz[i], xyz[i + 1], xyz[i + 2]);
        }
        return batch;
    }

    @Test
    void keysRoundTripIncludingNegativeCoordinates() {
        final int[][] blocks = {{0, 0, 0}, {-1, -64, -1}, {17, 319, -33}, {-30000000, 5, 29999999}};
        for (final int[] b : blocks) {
            final long key = OverlayIndex.sectionKeyOfBlock(b[0], b[1], b[2]);
            final short local = OverlayIndex.local(b[0], b[1], b[2]);
            assertEquals(b[0], OverlayIndex.blockX(key, local));
            assertEquals(b[1], OverlayIndex.blockY(key, local));
            assertEquals(b[2], OverlayIndex.blockZ(key, local));
        }
    }

    @Test
    void sectionKeyMatchesSectionPosPacking() {
        // SectionPos.asLong(x, y, z) for (1, -4, -2): x in the top 22 bits, z in the middle 22, y in the low 20
        final long expected = ((1L & 4194303L) << 42) | (-4L & 1048575L) | ((-2L & 4194303L) << 20);
        assertEquals(expected, OverlayIndex.sectionKey(1, -4, -2));
        assertEquals(1, OverlayIndex.sectionX(expected));
        assertEquals(-4, OverlayIndex.sectionY(expected));
        assertEquals(-2, OverlayIndex.sectionZ(expected));
    }

    @Test
    void groupingPutsCellsOfOneSectionTogether() {
        final Long2ObjectMap<Short2ObjectMap<String>> batch = cells(0, 0, 0, "a", 15, 15, 15, "b", 16, 0, 0, "c");
        assertEquals(2, batch.size());
        assertEquals(2, batch.get(OverlayIndex.sectionKey(0, 0, 0)).size());
        assertEquals(1, batch.get(OverlayIndex.sectionKey(1, 0, 0)).size());
    }

    @Test
    void emptyIndexCoversNothing() {
        final OverlayIndex<String> index = new OverlayIndex<>();
        assertTrue(index.isEmpty());
        assertNull(index.section(OverlayIndex.sectionKey(0, 0, 0)));
        assertNull(index.get(OverlayIndex.sectionKey(0, 0, 0), (short) 0));
    }

    @Test
    void putReportsOnlyVisibleChangesGroupedBySection() {
        final OverlayIndex<String> index = new OverlayIndex<>();
        final Long2ObjectMap<Short2ObjectMap<String>> first = index.put(cells(1, 2, 3, "stone", 20, 2, 3, "dirt"), false);
        assertEquals(2, first.size());
        assertEquals(2, index.size());

        final Long2ObjectMap<Short2ObjectMap<String>> second = index.put(cells(1, 2, 3, "stone", 20, 2, 3, "glass"), true);
        assertEquals(1, second.size(), "an unchanged cell sends nothing");
        assertEquals("glass", second.get(OverlayIndex.sectionKeyOfBlock(20, 2, 3)).get(OverlayIndex.local(20, 2, 3)));
        assertEquals(2, index.size());
        assertTrue(index.isSolid(OverlayIndex.sectionKeyOfBlock(1, 2, 3), OverlayIndex.local(1, 2, 3)),
            "re-setting the same state still updates the solid flag");
    }

    @Test
    void solidFlagCanBeCleared() {
        final OverlayIndex<String> index = new OverlayIndex<>();
        index.put(cells(0, 0, 0, "stone"), true);
        index.put(cells(0, 0, 0, "stone"), false);
        assertFalse(index.isSolid(OverlayIndex.sectionKey(0, 0, 0), OverlayIndex.local(0, 0, 0)));
    }

    @Test
    void removeReturnsOnlyCoveredCellsAndDropsEmptySections() {
        final OverlayIndex<String> index = new OverlayIndex<>();
        index.put(cells(0, 0, 0, "a", 1, 0, 0, "b"), true);
        final Long2ObjectMap<ShortSet> removed = index.remove(positions(0, 0, 0, 5, 5, 5, 100, 0, 0));
        assertEquals(1, removed.size());
        assertEquals(1, removed.get(OverlayIndex.sectionKey(0, 0, 0)).size());
        assertEquals(1, index.size());
        assertFalse(index.isSolid(OverlayIndex.sectionKey(0, 0, 0), OverlayIndex.local(0, 0, 0)));

        index.remove(positions(1, 0, 0));
        assertTrue(index.isEmpty());
        assertNull(index.section(OverlayIndex.sectionKey(0, 0, 0)));
    }

    @Test
    void clearReturnsEveryCellAndEmpties() {
        final OverlayIndex<String> index = new OverlayIndex<>();
        index.put(cells(0, 0, 0, "a", 40, 0, 0, "b", 41, 0, 0, "c"), false);
        final Long2ObjectMap<ShortSet> removed = index.clear();
        assertEquals(2, removed.size());
        assertEquals(3, removed.values().stream().mapToInt(ShortSet::size).sum());
        assertTrue(index.isEmpty());
    }

    @Test
    void publishedSectionIsNotChangedByLaterWrites() {
        final OverlayIndex<String> index = new OverlayIndex<>();
        index.put(cells(0, 0, 0, "a"), false);
        final OverlayIndex.Section<String> before = index.section(OverlayIndex.sectionKey(0, 0, 0));
        index.put(cells(1, 0, 0, "b"), false);
        index.remove(positions(0, 0, 0));
        assertEquals(1, before.size(), "a reader holding a snapshot keeps a consistent view");
        assertEquals("a", before.get(OverlayIndex.local(0, 0, 0)));
    }
}
