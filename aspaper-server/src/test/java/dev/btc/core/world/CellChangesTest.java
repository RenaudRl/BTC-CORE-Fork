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

    @Test
    @DisplayName("a change of block reaches the zones with no listener of the event; a move within a block does not")
    void movesFeedTheZones() {
        org.bukkit.World world = org.mockito.Mockito.mock(org.bukkit.World.class);
        org.mockito.Mockito.when(world.getUID()).thenReturn(java.util.UUID.randomUUID());
        org.bukkit.entity.Player player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        org.mockito.Mockito.when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        java.util.List<String> calls = new java.util.ArrayList<>();
        dev.btc.core.zone.ZoneIndex zones = new dev.btc.core.zone.ZoneIndex(id -> true);
        zones.register(enabledPlugin(), world.getUID(), new org.bukkit.util.BoundingBox(0, 0, 0, 1, 1, 1), new dev.btc.core.api.zone.ZoneListener() {
            @Override
            public void onEnter(org.bukkit.entity.Player who, dev.btc.core.api.zone.Zone entered) {
                calls.add("enter");
            }
        });

        CellChanges.moved(player, new org.bukkit.Location(world, 0.7, 0, 0.5), new org.bukkit.Location(world, 0.2, 0, 0.5), zones);
        assertEquals(java.util.List.of(), calls, "within a block of the zone: nothing is looked up");
        CellChanges.moved(player, new org.bukkit.Location(world, 1.5, 0, 0.5), new org.bukkit.Location(world, 0.5, 0, 0.5), zones);
        assertEquals(java.util.List.of("enter"), calls);
    }

    @Test
    @DisplayName("with zones but no listener of the event, a change of block allocates no event")
    void noEventBuiltWithoutListener() {
        assertEquals(0, PlayerCellChangeEvent.getHandlerList().getRegisteredListeners().length);
        dev.btc.core.zone.ZoneIndex zones = new dev.btc.core.zone.ZoneIndex(id -> true);
        zones.register(enabledPlugin(), java.util.UUID.randomUUID(), new org.bukkit.util.BoundingBox(0, 0, 0, 1, 1, 1), new dev.btc.core.api.zone.ZoneListener() {
        });
        org.bukkit.Location from = new org.bukkit.Location(null, 0.5, 64, 0.5); // no world: the zones return at once, only the event could allocate
        org.bukkit.Location to = new org.bukkit.Location(null, 1.5, 64, 0.5);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        final int moves = 1000; // few enough to stay out of C2, whose escape analysis could hide the allocation

        CellChanges.moved(null, from, to, zones); // class loading and the baked listener array, once
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < moves; i++) CellChanges.moved(null, from, to, zones);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertTrue(allocated < 8 * 1024, allocated + " bytes for " + moves + " changes of block");
    }

    private static org.bukkit.plugin.Plugin enabledPlugin() {
        org.bukkit.plugin.Plugin plugin = org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class);
        org.mockito.Mockito.when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }
}
