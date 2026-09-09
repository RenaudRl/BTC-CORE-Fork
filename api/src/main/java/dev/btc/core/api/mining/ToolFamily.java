package dev.btc.core.api.mining;

/**
 * The family a digging tool belongs to.
 *
 * <p>Resolved from the vanilla item tags — {@code minecraft:pickaxes} and its siblings — rather than
 * from the material's name. A name test such as {@code endsWith("_PICKAXE")} answers wrong for every
 * tool a datapack or a plugin adds, and answers wrong in silence.
 */
public enum ToolFamily {

    PICKAXE,
    AXE,
    SHOVEL,
    HOE,

    /**
     * Anything that is not a digging tool: a bare hand, a sword, a torch.
     *
     * <p>Not a failure to classify — a hand is a legitimate way to break a block, and it carries no
     * affinity in either direction.
     */
    NONE
}
