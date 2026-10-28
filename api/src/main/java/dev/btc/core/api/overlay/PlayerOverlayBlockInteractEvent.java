package dev.btc.core.api.overlay;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A player acted on a cell of their {@link PlayerBlockOverlay}.
 *
 * <p>The server has already answered the client: the action's sequence is acknowledged, the cell is
 * sent again (with whatever the overlay holds once listeners are done), and the real world is not
 * touched — no {@code BlockBreakEvent}, no {@code PlayerInteractEvent}, no placement. A listener that
 * wants the action to "happen" does it itself, for instance by removing the cell from the overlay on
 * {@link Action#FINISH_BREAK}. There is nothing to cancel.
 *
 * <p>Fired synchronously on the player's owning thread, once per client packet: a right click with
 * an empty main hand may come again for the off hand, as with {@code PlayerInteractEvent}.
 *
 * @since 26.3 (fork build after 26.3.build.1)
 */
public class PlayerOverlayBlockInteractEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** What the player did to the cell. */
    public enum Action {
        /** Started digging (left click). In creative mode this is the whole break. */
        START_BREAK,
        /** Stopped digging before the end. */
        ABORT_BREAK,
        /** Finished digging, as timed by the client against the shown block. */
        FINISH_BREAK,
        /** Right click on the cell, which includes trying to place a block against it. */
        INTERACT
    }

    private final Action action;
    private final Block block;
    private final BlockFace face;
    private final @Nullable EquipmentSlot hand;
    private final BlockData shown;

    public PlayerOverlayBlockInteractEvent(final @NotNull Player player, final @NotNull Action action,
                                           final @NotNull Block block, final @NotNull BlockFace face,
                                           final @Nullable EquipmentSlot hand, final @NotNull BlockData shown) {
        super(player);
        this.action = action;
        this.block = block;
        this.face = face;
        this.hand = hand;
        this.shown = shown;
    }

    /** @return what the player did */
    public @NotNull Action getAction() {
        return action;
    }

    /** @return the <em>real</em> block at the cell; read it, it is not what the player sees */
    public @NotNull Block getBlock() {
        return block;
    }

    /** @return the face of the cell the player aimed at */
    public @NotNull BlockFace getBlockFace() {
        return face;
    }

    /** @return the hand used for {@link Action#INTERACT}, {@code null} for the dig actions */
    public @Nullable EquipmentSlot getHand() {
        return hand;
    }

    /** @return the state the overlay showed at the cell when the player acted */
    public @NotNull BlockData getShownBlockData() {
        return shown.clone();
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
