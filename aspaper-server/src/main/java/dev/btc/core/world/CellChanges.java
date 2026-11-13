package dev.btc.core.world;

import dev.btc.core.api.event.PlayerCellChangeEvent;
import dev.btc.core.zone.ZoneIndex;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Fires {@link PlayerCellChangeEvent} from the two places a {@code PlayerMoveEvent} is let through
 * ({@code ServerGamePacketListenerImpl#handleMovePlayer} and {@code #handleMoveVehicle}), with the
 * two locations of that event: the block the player left is always the block it had reached.
 *
 * <p>The same change of block feeds the zones of {@link ZoneIndex}. No option: with no listener and
 * no zone registered a move costs one static call, the length of the listener array and one
 * volatile read, and nothing is kept on the player.
 */
public final class CellChanges {

    private CellChanges() {
    }

    public static void moved(Player player, Location from, Location to) {
        moved(player, from, to, ZoneIndex.shared());
    }

    static void moved(Player player, Location from, Location to, ZoneIndex zones) {
        boolean listened = PlayerCellChangeEvent.getHandlerList().getRegisteredListeners().length != 0;
        if (!listened && zones.isEmpty()) return;
        PlayerCellChangeEvent change = between(player, from.getBlockX(), from.getBlockY(), from.getBlockZ(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        if (change == null) return;
        zones.at(player, to); // before the event: a listener of the event that teleports the player has the last word
        if (listened) change.callEvent();
    }

    /** The event of a move between two blocks, or null when they are the same block. */
    static PlayerCellChangeEvent between(Player player, int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        if (fromX == toX && fromY == toY && fromZ == toZ) return null;
        return new PlayerCellChangeEvent(player, fromX, fromY, fromZ, toX, toY, toZ);
    }
}
