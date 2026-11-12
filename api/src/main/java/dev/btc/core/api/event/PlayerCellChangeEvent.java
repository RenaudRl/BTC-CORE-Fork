package dev.btc.core.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Fired when a move of a player takes it into another block: the event a listener of
 * {@code PlayerMoveEvent} wants when it only cares where the player stands, not about every
 * fraction of a block or every turn of the head.
 *
 * <p>Fired right after a {@code PlayerMoveEvent} that was neither cancelled nor redirected, with
 * the blocks of its two locations, when they differ: a move the player makes on foot, in flight or
 * driving a vehicle, on the thread that fires {@code PlayerMoveEvent} - the one that ticks the
 * player's world. The block left is the block the previous event reached. Not fired by a teleport,
 * a respawn or a change of world ({@code PlayerTeleportEvent}, {@code PlayerChangedWorldEvent}),
 * nor for a player carried by a vehicle it does not drive. Not cancellable: cancel the
 * {@code PlayerMoveEvent}, which comes first.
 *
 * <p>Nothing is computed or allocated while no listener is registered. The coordinates are those
 * of blocks; {@link #changesChunk()} and {@link #changesCell(int)} tell whether the move also
 * leaves a larger cell, so a listener that works by chunk or by region grid returns at once on the
 * moves that do not concern it.
 */
public class PlayerCellChangeEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();
    private static final int CHUNK_SIDE = 16;

    private final int fromX;
    private final int fromY;
    private final int fromZ;
    private final int toX;
    private final int toY;
    private final int toZ;

    public PlayerCellChangeEvent(Player player, int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        super(player);
        this.fromX = fromX;
        this.fromY = fromY;
        this.fromZ = fromZ;
        this.toX = toX;
        this.toY = toY;
        this.toZ = toZ;
    }

    public int getFromX() {
        return this.fromX;
    }

    public int getFromY() {
        return this.fromY;
    }

    public int getFromZ() {
        return this.fromZ;
    }

    public int getToX() {
        return this.toX;
    }

    public int getToY() {
        return this.toY;
    }

    public int getToZ() {
        return this.toZ;
    }

    /** Whether the move crosses the border of a chunk. */
    public boolean changesChunk() {
        return this.changesColumn(CHUNK_SIDE);
    }

    /**
     * Whether the move leaves a column of {@code side} by {@code side} blocks, whatever the
     * height, of the grid that has a corner at x 0, z 0.
     *
     * @throws IllegalArgumentException if {@code side} is below 1
     */
    public boolean changesColumn(int side) {
        requireSide(side);
        return Math.floorDiv(this.fromX, side) != Math.floorDiv(this.toX, side)
            || Math.floorDiv(this.fromZ, side) != Math.floorDiv(this.toZ, side);
    }

    /**
     * Whether the move leaves a cube of {@code side} blocks a side, of the grid that has a corner
     * at 0, 0, 0.
     *
     * @throws IllegalArgumentException if {@code side} is below 1
     */
    public boolean changesCell(int side) {
        return this.changesColumn(side) || Math.floorDiv(this.fromY, side) != Math.floorDiv(this.toY, side);
    }

    private static void requireSide(int side) {
        if (side < 1) throw new IllegalArgumentException("A cell is at least one block a side, not " + side);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
