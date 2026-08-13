package dev.btc.core.api.drop;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Decides what a block, an entity or a loot table drops, in place of the vanilla loot table.
 *
 * <p>A provider replaces the roll rather than editing its outcome, which is what makes it cover the
 * paths a Bukkit event never sees: explosions, pistons, {@code /loot}, fire, structure chests
 * generated far from any player. The vanilla table is not rolled at all unless the provider asks for
 * it through {@link DropContext#vanillaDrops()}.
 *
 * <p>Called on the region thread that owns the drop. It must not block, must not touch another
 * region, and must not schedule work that assumes the main thread.
 */
@FunctionalInterface
public interface DropProvider {

    /**
     * Produces the drops for one roll.
     *
     * @param context what is being rolled and why
     * @return the stacks to drop — an empty list means "drop nothing", and {@code null} means
     *         "I decline, roll the vanilla table"
     */
    List<ItemStack> provide(DropContext context);
}
