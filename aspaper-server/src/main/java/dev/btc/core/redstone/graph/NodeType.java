package dev.btc.core.redstone.graph;

/**
 * The kinds of node a compiled redstone graph can hold.
 *
 * <p>Only components whose behaviour can be expressed purely in terms of "input signal strength in,
 * output signal strength out" are compiled. Anything else (movable blocks, entities) stays in the
 * world and is reached through {@link #PISTON} / {@link #GENERIC_OUTPUT} boundary nodes.
 */
public enum NodeType {

    /** Redstone dust. Never ticked: its strength follows its inputs within the same update. */
    WIRE,

    /** Repeater. Has a 1-4 tick delay, can be locked from the side by another repeater/comparator. */
    REPEATER,

    /** Comparator, in compare or subtract mode. Reads a side input and an optional container override. */
    COMPARATOR,

    /** Redstone torch (standing or wall). Inverts the block it is attached to, 1 tick delay. */
    TORCH,

    /** Redstone lamp. Lights instantly, extinguishes after 2 ticks. */
    LAMP,

    /** Lever. Flipped by the player only: never updated by other nodes, never ticked. */
    LEVER,

    /** Button. Pressed by the player, schedules its own depowering. */
    BUTTON,

    /** Pressure plate. Driven by entities in the world, not by the graph. */
    PRESSURE_PLATE,

    /** Block of redstone, or any node folded into a fixed value by the constant-folding pass. */
    CONSTANT,

    /**
     * Piston (normal or sticky). Output-only boundary node: the graph decides whether it should be
     * powered, the world performs the actual block movement.
     */
    PISTON,

    /**
     * Any other block the graph must power but does not simulate (doors, trapdoors, dispensers,
     * note blocks, rails...). Output-only, state written straight back to the world.
     */
    GENERIC_OUTPUT;

    /** True when other nodes may drive this node's input. */
    public boolean acceptsInput() {
        return switch (this) {
            case LEVER, BUTTON, PRESSURE_PLATE, CONSTANT -> false;
            default -> true;
        };
    }

    /** True when this node can be a source of signal for other nodes. */
    public boolean producesOutput() {
        return switch (this) {
            case LAMP, PISTON, GENERIC_OUTPUT -> false;
            default -> true;
        };
    }
}
