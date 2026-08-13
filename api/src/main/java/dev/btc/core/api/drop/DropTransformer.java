package dev.btc.core.api.drop;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Rewrites the drops of every roll, whatever produced them.
 *
 * <p>Where a {@link DropProvider} answers for one block or one entity, a transformer sees them all —
 * which is what a server swapping every vanilla item for its own catalogue equivalent needs. It runs
 * after the provider, or after the vanilla table when no provider claimed the roll.
 *
 * <p>Registering one costs a vanilla roll on every single drop in the game, since the transformer
 * has to be shown something. Register at most one, and keep it cheap.
 *
 * <p>Called on the region thread that owns the drop, with the same restrictions as a provider.
 */
@FunctionalInterface
public interface DropTransformer {

    /**
     * Rewrites one set of drops.
     *
     * @param context what is being rolled and why
     * @param drops   what the provider or the vanilla table produced; never {@code null}, and safe
     *                to mutate
     * @return the stacks to drop, or {@code null} to leave {@code drops} untouched
     */
    List<ItemStack> transform(DropContext context, List<ItemStack> drops);
}
