package dev.btc.core.api.mining;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * One block a player has just started breaking.
 *
 * <p>Handed to a {@link BreakSpeedProvider} exactly once per block entered, at the moment the client
 * announces it began digging. Nothing here is retained afterwards: an implementation that needs the
 * values later must copy them.
 */
public interface BreakSpeedContext {

    /** The player who started digging. */
    Player player();

    /** The block being dug. */
    Block block();

    /**
     * The item in the main hand, which may be air.
     *
     * <p>Air is a normal answer, not a missing value: a bare hand is a legitimate way to break a
     * block and carries no tool affinity.
     */
    ItemStack tool();

    /**
     * The multiplier the platform tables already resolved — tool affinity times hardness correction.
     *
     * <p>Exposed so a provider can see what it is building on, and override it outright for a block
     * the platform tables cannot know about, such as a custom block whose family is decided by a
     * plugin rather than by a vanilla tag.
     */
    double tableMultiplier();

    /**
     * The family of the held tool, resolved from the vanilla item tags.
     *
     * <p>Offered so a provider does not have to classify the tool itself: the platform already did
     * it, from the tags rather than from the material's name, and two classifications of the same
     * thing drift.
     */
    ToolFamily toolFamily();

    /**
     * Whether the held tool is in its domain for this block.
     *
     * <p>{@code true} both when the tool matches the block's family and when neither has a family —
     * an item that is not a digging tool is never "out of domain", it simply carries no affinity.
     */
    boolean inToolDomain();
}
