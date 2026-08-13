package dev.btc.core.drop;

import dev.btc.core.api.drop.DropProvider;
import dev.btc.core.api.drop.DropTransformer;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The drop registry behind the public drop API.
 *
 * <p>It exists because erasing drops after the fact does not work. A plugin listening to
 * {@code BlockBreakEvent} and {@code EntityDeathEvent} misses every other way an item reaches the
 * ground — explosions, pistons, fire, {@code /loot}, structure chests, shearing, villager gifts —
 * and each of those leaks a vanilla item onto a server that wants none. Hooking the loot roll itself
 * closes all of them at once, because every one of those paths ends in the same loot table.
 *
 * <p>Everything here is static and thread-safe: loot rolls run on whichever region thread owns the
 * drop, several at a time.
 */
public final class DropRegistry {

    private DropRegistry() {
    }

    private record Owned<T>(Plugin owner, T value) {
    }

    private static final Map<Material, Owned<DropProvider>> BLOCKS = new ConcurrentHashMap<>();
    private static final Map<EntityType, Owned<DropProvider>> ENTITIES = new ConcurrentHashMap<>();
    private static final Map<NamespacedKey, Owned<DropProvider>> TABLES = new ConcurrentHashMap<>();
    private static final List<Owned<DropTransformer>> TRANSFORMERS = new CopyOnWriteArrayList<>();

    /**
     * Whether anything at all is registered.
     *
     * <p>Read once per loot roll, so it must stay a plain field read. Without it, a server using
     * none of this would still pay for a context object on every drop in the game.
     */
    private static volatile boolean active = false;

    // ==================== REGISTRATION ====================

    public static void registerBlock(final Plugin owner, final Material block, final DropProvider provider) {
        BLOCKS.put(block, new Owned<>(owner, provider));
        active = true;
    }

    public static void registerEntity(final Plugin owner, final EntityType type, final DropProvider provider) {
        ENTITIES.put(type, new Owned<>(owner, provider));
        active = true;
    }

    public static void registerTable(final Plugin owner, final NamespacedKey table, final DropProvider provider) {
        TABLES.put(table, new Owned<>(owner, provider));
        active = true;
    }

    public static void registerTransformer(final Plugin owner, final DropTransformer transformer) {
        TRANSFORMERS.add(new Owned<>(owner, transformer));
        active = true;
    }

    /** Drops everything a plugin registered. Called on its own when the plugin disables. */
    public static void unregisterAll(final Plugin owner) {
        BLOCKS.values().removeIf(entry -> entry.owner().equals(owner));
        ENTITIES.values().removeIf(entry -> entry.owner().equals(owner));
        TABLES.values().removeIf(entry -> entry.owner().equals(owner));
        TRANSFORMERS.removeIf(entry -> entry.owner().equals(owner));
        recomputeActive();
    }

    public static boolean hasOverrides() {
        return active;
    }

    private static void recomputeActive() {
        active = !BLOCKS.isEmpty() || !ENTITIES.isEmpty() || !TABLES.isEmpty() || !TRANSFORMERS.isEmpty();
    }

    // ==================== INTERCEPTION ====================

    /**
     * Offers one loot roll to the registered providers.
     *
     * <p>Called from {@code LootTable.getRandomItemsRaw} before the vanilla body runs.
     *
     * @param table   the table about to be rolled
     * @param context the loot context of the roll
     * @param output  where the resulting stacks go — the caller wraps it in the usual stack splitter,
     *                so oversized stacks are still split as vanilla would
     * @return {@code true} when the drops have been produced here and the vanilla roll must be
     *         skipped, {@code false} to let vanilla run untouched
     */
    public static boolean intercept(final LootTable table, final LootContext context,
                                    final Consumer<net.minecraft.world.item.ItemStack> output) {
        if (!active) return false;

        NmsDropContext dropContext = new NmsDropContext(table, context);

        List<ItemStack> drops = null;
        DropProvider provider = resolveProvider(dropContext);
        if (provider != null) {
            drops = provider.provide(dropContext);
        }

        if (drops == null) {
            // Nobody claimed the roll. With no transformer there is nothing left to do, and letting
            // vanilla run is both correct and free — rolling it ourselves would only add a copy.
            if (TRANSFORMERS.isEmpty()) return false;
            drops = dropContext.vanillaDrops();
        }

        for (Owned<DropTransformer> entry : TRANSFORMERS) {
            if (!entry.owner().isEnabled()) continue;
            List<ItemStack> rewritten = entry.value().transform(dropContext, drops);
            if (rewritten != null) drops = rewritten;
        }

        for (ItemStack stack : drops) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) continue;
            output.accept(CraftItemStack.asNMSCopy(stack));
        }
        return true;
    }

    /**
     * Finds the provider that owns this roll, most specific first.
     *
     * <p>A loot table key beats a block or an entity because it names one exact table, where a
     * material covers every table that block can roll.
     */
    private static DropProvider resolveProvider(final NmsDropContext context) {
        if (!TABLES.isEmpty()) {
            NamespacedKey key = context.lootTable();
            if (key != null) {
                DropProvider provider = live(TABLES.get(key));
                if (provider != null) return provider;
            }
        }

        if (!BLOCKS.isEmpty()) {
            Material block = context.blockType();
            if (block != null) {
                DropProvider provider = live(BLOCKS.get(block));
                if (provider != null) return provider;
            }
        }

        if (!ENTITIES.isEmpty()) {
            org.bukkit.entity.Entity entity = context.entity();
            if (entity != null) {
                DropProvider provider = live(ENTITIES.get(entity.getType()));
                if (provider != null) return provider;
            }
        }

        return null;
    }

    /**
     * A registration only counts while its plugin is enabled.
     *
     * <p>A plugin that unloads without cleaning up would otherwise keep answering for its blocks
     * forever, and the drops it owns would vanish from the server with no way to get them back short
     * of a restart. Checking here costs one field read and makes {@link #unregisterAll} a courtesy
     * rather than a requirement.
     */
    private static DropProvider live(final Owned<DropProvider> entry) {
        if (entry == null || !entry.owner().isEnabled()) return null;
        return entry.value();
    }
}
