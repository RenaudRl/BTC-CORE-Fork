package dev.btc.core.world;

import dev.btc.core.api.event.PlayerCellChangeEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code PlayerCellChangeEvent}: one event per change of block, which tells the larger cells it leaves. */
class CellChangesTest {

    private static PlayerCellChangeEvent move(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return CellChanges.between(null, fromX, fromY, fromZ, toX, toY, toZ);
    }

    @Test
    @DisplayName("a move within a block is no event, a move of one block on any axis is one, with both blocks")
    void oneEventPerChangeOfBlock() {
        assertNull(move(3, 64, -7, 3, 64, -7));
        assertNotNull(move(3, 64, -7, 4, 64, -7));
        assertNotNull(move(3, 64, -7, 3, 65, -7));
        PlayerCellChangeEvent change = move(3, 64, -7, 3, 64, -8);
        assertNotNull(change);
        assertEquals("3 64 -7 > 3 64 -8", change.getFromX() + " " + change.getFromY() + " " + change.getFromZ()
            + " > " + change.getToX() + " " + change.getToY() + " " + change.getToZ());
    }

    @Test
    @DisplayName("a chunk is left across x or z, never across y, and the border between -1 and 0 is one")
    void changesChunk() {
        assertFalse(move(0, 64, 0, 15, 64, 15).changesChunk());
        assertTrue(move(15, 64, 0, 16, 64, 0).changesChunk());
        assertTrue(move(0, 64, 15, 0, 64, 16).changesChunk());
        assertFalse(move(0, 15, 0, 0, 16, 0).changesChunk());
        assertTrue(move(-1, 64, 0, 0, 64, 0).changesChunk());
        assertFalse(move(-16, 64, -1, -1, 64, -16).changesChunk());
    }

    @Test
    @DisplayName("a cell of any side is left on the three axes, a column on two; negative coordinates fall in their own cells")
    void changesCellAndColumn() {
        assertFalse(move(0, 0, 0, 7, 7, 7).changesCell(8));
        assertTrue(move(7, 0, 0, 8, 0, 0).changesCell(8));
        assertTrue(move(0, 7, 0, 0, 8, 0).changesCell(8));
        assertFalse(move(0, 7, 0, 0, 8, 0).changesColumn(8));
        assertTrue(move(0, 0, 7, 0, 0, 8).changesColumn(8));
        assertTrue(move(-1, 0, 0, 0, 0, 0).changesCell(8), "-1 and 0 are in two cells");
        assertFalse(move(-8, -8, -8, -1, -1, -1).changesCell(8));
        assertTrue(move(3, 64, 3, 4, 64, 3).changesCell(1), "a cell of one block is the block");
        assertThrows(IllegalArgumentException.class, () -> move(0, 0, 0, 1, 0, 0).changesCell(0));
        assertThrows(IllegalArgumentException.class, () -> move(0, 0, 0, 1, 0, 0).changesColumn(-4));
    }
}
