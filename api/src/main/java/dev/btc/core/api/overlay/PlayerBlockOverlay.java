package dev.btc.core.api.overlay;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;

/**
 * The blocks one player sees in place of the real ones, in the world the player is in.
 *
 * <p>Each covered cell holds the state shown to the player and a <em>solid</em> flag. Writes send the
 * difference to the client at once, one multi-block packet per touched chunk section; a cell set to
 * the state it already shows sends nothing. Writes must run on the player's owning thread (see the
 * {@linkplain dev.btc.core.api.overlay package documentation}); reads are safe from any thread.
 *
 * @since 26.3 (fork build after 26.3.build.1)
 */
public interface PlayerBlockOverlay {

    /** @return the player this overlay is shown to */
    Player getPlayer();

    /**
     * @return the world the covered cells belong to, or {@code null} while the overlay is empty
     */
    @Nullable World getWorld();

    /**
     * Covers cells, or changes what covered cells show.
     *
     * <p>Positions outside the world's build height are skipped. Setting a cell again replaces its
     * state and its solid flag; changing only the flag sends nothing.
     *
     * @param world  the player's current world — any other world is refused, so a caller cannot draw
     *               one world's blocks into another
     * @param blocks the cells and the state each one shows
     * @param solid  {@code true} when the server's movement checks should count these cells as
     *               ground (a fake floor the player walks on)
     * @return the number of cells whose shown state changed
     * @throws IllegalStateException    off the player's owning thread
     * @throws IllegalArgumentException when {@code world} is not the player's current world
     */
    int setBlocks(World world, Map<BlockVector, BlockData> blocks, boolean solid);

    /**
     * Uncovers cells: the player sees the real blocks again (cells in unloaded chunks are refreshed
     * by the next chunk send).
     *
     * @param world     the player's current world
     * @param positions the cells to uncover; cells that are not covered are ignored
     * @return the number of cells uncovered
     * @throws IllegalStateException    off the player's owning thread
     * @throws IllegalArgumentException when {@code world} is not the player's current world
     */
    int removeBlocks(World world, Collection<BlockVector> positions);

    /**
     * Uncovers every cell and shows the real blocks again.
     *
     * @return the number of cells uncovered
     * @throws IllegalStateException off the player's owning thread
     */
    int clear();

    /**
     * @return the state shown at this cell of {@link #getWorld()}, or {@code null} when the cell is
     *         not covered
     */
    @Nullable BlockData getBlock(int x, int y, int z);

    /** @return {@code true} when this cell of {@link #getWorld()} is covered */
    boolean isCovered(int x, int y, int z);

    /** @return {@code true} when this cell is covered and marked solid */
    boolean isSolid(int x, int y, int z);

    /** @return the number of covered cells */
    int size();

    /** @return {@code true} when no cell is covered */
    default boolean isEmpty() {
        return size() == 0;
    }
}
