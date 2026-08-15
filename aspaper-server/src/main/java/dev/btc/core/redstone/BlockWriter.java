package dev.btc.core.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * The write-back's own path into the world.
 *
 * <p>Compiling a circuit removes the neighbour updates, not the block writes: every dust block whose
 * power moved still has to be stored and sent to clients. Those writes then become the whole cost of
 * a compiled tick, which is why they get their own path here instead of going through
 * {@code Level#setBlock} with default flags.
 *
 * <p>What the default flags cost is not a guess. {@code Block.UPDATE_CLIENTS} leaves
 * {@code UPDATE_KNOWN_SHAPE} unset, and {@code Level#markAndNotifyBlock} then runs, <em>per block
 * written</em>, {@code updateIndirectNeighbourShapes} twice, {@code updateNeighbourShapes} over all
 * six neighbours, a Bukkit {@code BlockPhysicsEvent}, and a point-of-interest refresh. None of that
 * can change anything here: a compiled node only ever rewrites a state property — dust power, a
 * diode's POWERED, a lamp's LIT — and never the block itself, so its shape is the shape it already
 * had.
 *
 * <p>Alternate Current reached the same conclusion and carries an equivalent helper
 * ({@code alternate.current.wire.LevelHelper#setWireState}), which is precisely why it was faster
 * than a compiled graph that did strictly less redstone work.
 */
final class BlockWriter {

    private BlockWriter() {
    }

    /**
     * Flags for a state change that cannot alter the block's shape, but may alter its light.
     *
     * <p>Used for everything but dust: a torch emits light 7 and a lamp light 15, so those writes
     * must keep the lighting, height-map and block-entity handling of the normal path. Only the
     * shape updates are dropped.
     */
    static final int STATE_ONLY = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * Stores a new dust power and tells clients about it, and does nothing else.
     *
     * <p>Safe to strip this far for dust and dust alone: redstone wire emits no light, occupies no
     * height map, carries no block entity, and keeps the same collision shape at every power level,
     * so the only observable part of the write is the stored state and the packet.
     *
     * @return true when the stored state actually changed
     */
    static boolean setWirePower(final ServerLevel level, final BlockPos pos, final BlockState state) {
        final int y = pos.getY();
        if (y < level.getMinY() || y > level.getMaxY()) {
            return false;
        }

        final int x = pos.getX();
        final int z = pos.getZ();
        final ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
        final LevelChunkSection section = chunk.getSections()[level.getSectionIndex(y)];
        if (section == null) {
            return false;
        }

        final BlockState previous = section.setBlockState(x & 15, y & 15, z & 15, state);
        if (previous == state) {
            return false;
        }

        level.getChunkSource().blockChanged(pos);
        chunk.markUnsaved();
        return true;
    }
}
