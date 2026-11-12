package dev.btc.core.performance;

/**
 * Where the shape of a retracting piston is kept ({@code performance.piston-shape-cache.enabled}): one
 * slot per direction and per offset the head stands at.
 *
 * <p>A piston moves in two ticks, so its head stands at an offset of 0, 0.5 or 1 block. Any other
 * offset - a block entity loaded with a progress of its own - has no slot and takes the vanilla
 * path.
 */
public final class PistonShapeSlots {

    /** Offsets a head stands at. */
    public static final int STEPS = 3;
    /** Directions a piston faces. */
    public static final int DIRECTIONS = 6;
    public static final int SLOTS = DIRECTIONS * STEPS;
    public static final int NO_STEP = -1;
    /** Blocks between two offsets. */
    public static final float STEP_LENGTH = 0.5F;

    private PistonShapeSlots() {
    }

    /** The step of an offset in blocks, {@link #NO_STEP} when the head stands at none of them. */
    public static int step(float offset) {
        float steps = offset / STEP_LENGTH;
        int step = (int) steps;
        return step == steps && step >= 0 && step < STEPS ? step : NO_STEP; // NaN equals nothing
    }

    /** @param direction the 3D data value of the direction, 0 to 5 */
    public static int slot(int direction, int step) {
        return direction * STEPS + step;
    }
}
