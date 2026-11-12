package dev.btc.core.performance;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The collision shape of a retracting piston - its base and its head on the way back - kept instead
 * of being joined again on every question ({@code performance.cached-piston-shapes}).
 *
 * <p>Vanilla joins the two shapes each time an entity asks what it may collide with. For a piston
 * of vanilla, facing the way it moves, with its head at one of the three offsets it stands at, the
 * answer is one of 18 shapes: they are built once, with the calls vanilla makes, and handed out.
 * Everything else - a piston that extends, a moved block, a head elsewhere, a facing that does not
 * match (Paper's allow-permanent-block-break-exploits) - takes the vanilla path.
 *
 * <p>From Lithium "moving_block_shapes" (2No2Name, LGPL-3.0-only), as ported by Leaf "Lithium:
 * Cache retracting piston collision shapes" (HaHaWTH), which added the test on the facing. The
 * shapes cannot be written and are shared by every world.
 */
public final class PistonShapes {

    /** A head this near its base is the short one, as {@code PistonMovingBlockEntity#getCollisionShape} picks it. */
    private static final float SHORT_HEAD_BELOW = 0.25F;

    private PistonShapes() {
    }

    /** Built by the first piston that retracts with the option on: nothing with the option off. */
    private static final class Cache {
        static final VoxelShape[] SHAPES = build();
    }

    /**
     * @param moved     the block a retracting source piston carries
     * @param direction the direction of its block entity
     * @param offset    how far out the head is, in blocks
     * @return the shape vanilla would build, null when it is not one of those kept
     */
    public static @Nullable VoxelShape retracting(BlockState moved, Direction direction, float offset) {
        int step = PistonShapeSlots.step(offset);
        if (step == PistonShapeSlots.NO_STEP || !(moved.is(Blocks.PISTON) || moved.is(Blocks.STICKY_PISTON))
            || moved.getValue(PistonBaseBlock.FACING) != direction) {
            return null;
        }
        return Cache.SHAPES[PistonShapeSlots.slot(direction.get3DDataValue(), step)];
    }

    private static VoxelShape[] build() {
        VoxelShape[] shapes = new VoxelShape[PistonShapeSlots.SLOTS];
        for (Direction direction : Direction.values()) {
            for (int step = 0; step < PistonShapeSlots.STEPS; step++) {
                shapes[PistonShapeSlots.slot(direction.get3DDataValue(), step)] = shape(direction, step * PistonShapeSlots.STEP_LENGTH);
            }
        }
        return shapes;
    }

    /** The calls of {@code PistonMovingBlockEntity#getCollisionShape}; a sticky piston has the shape of a piston. */
    private static VoxelShape shape(Direction direction, float offset) {
        VoxelShape base = Blocks.PISTON.defaultBlockState()
            .setValue(PistonBaseBlock.FACING, direction)
            .setValue(PistonBaseBlock.EXTENDED, true)
            .getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        VoxelShape head = Blocks.PISTON_HEAD.defaultBlockState()
            .setValue(PistonHeadBlock.FACING, direction)
            .setValue(PistonHeadBlock.SHORT, offset < SHORT_HEAD_BELOW)
            .getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return Shapes.or(base, head.move(direction.getStepX() * offset, direction.getStepY() * offset, direction.getStepZ() * offset));
    }
}
