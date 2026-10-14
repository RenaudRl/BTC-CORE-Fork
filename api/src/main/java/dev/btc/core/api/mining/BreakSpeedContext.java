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

    /**
     * The hardness of the block state being dug, as the server reads it ({@code -1.0} for an
     * unbreakable block, {@code 0.0} for one that breaks in a single tick).
     *
     * <p>This is the number vanilla divides by, taken from the server-side block <em>state</em> and
     * not from the {@link org.bukkit.Material}: a custom block registered per state (CraftEngine)
     * carries its own hardness there, which the material of its carrier block does not.
     *
     * <p>The default implementation reads the material and exists only so that implementations
     * written before this method keep compiling; the platform always overrides it.
     *
     * @since 26.3 (fork build after 26.3.build.1)
     */
    default double blockHardness() {
        return block().getType().getHardness();
    }

    /**
     * The speed of the held tool on this block state, before haste, fatigue, water, air and the
     * {@code block_break_speed} attribute: the item's own speed, plus the player's
     * {@code mining_efficiency} attribute when the tool is efficient here (speed above 1).
     *
     * <p>{@code 1.0} means the tool is no faster than a bare hand on this block. The value
     * excludes the attribute this API writes, so a provider can divide by it without feeding its own
     * previous answer back into itself.
     *
     * <p>The default implementation asks Bukkit and is only an approximation (the enchantment rather
     * than the attribute); the platform always overrides it with the exact value.
     *
     * @since 26.3 (fork build after 26.3.build.1)
     */
    default double toolSpeed() {
        return block().getDestroySpeed(tool(), true);
    }

    /**
     * Whether the held tool is the correct one for this block's drops — the condition vanilla uses to
     * pick a divisor of 30 over 100 in the progression.
     *
     * <p>The default implementation asks Bukkit; the platform overrides it with the server's own
     * answer.
     *
     * @since 26.3 (fork build after 26.3.build.1)
     */
    default boolean correctToolForDrops() {
        return block().isPreferredTool(tool());
    }
}
