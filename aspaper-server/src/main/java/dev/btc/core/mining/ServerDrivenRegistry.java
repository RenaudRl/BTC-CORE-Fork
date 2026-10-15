package dev.btc.core.mining;

import dev.btc.core.api.mining.ServerDrivenBlocks;
import dev.btc.core.config.BTCCoreConfig;
import java.util.BitSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The set that arms the server-driven mode, as the plugin that owns the provider published it.
 *
 * <p>Resolved once, at publication, into two {@link StateIdSet}s — block states by state id, items by
 * item id — and swapped in behind a {@code volatile} reference. A tick reads the reference and two
 * bits; it never locks, never allocates and never touches the Bukkit objects the plugin passed in.
 * This is read-only configuration, of the same kind as the bound provider.
 */
public final class ServerDrivenRegistry {

    private ServerDrivenRegistry() {
    }

    private static final org.apache.logging.log4j.Logger LOGGER =
        org.apache.logging.log4j.LogManager.getLogger("BTCCore");

    /** Above this many block states, the set probably comes from a tag that is far too wide. */
    static final int LARGE_SET_WARNING = 4096;

    /**
     * One resolved publication.
     *
     * @param owner  the plugin that published it
     * @param states the block states that arm the mode, by state id
     * @param items  the items that arm the mode in the main hand, by item id
     * @param timing the timings of the mode
     */
    public record Published(Plugin owner, StateIdSet states, StateIdSet items, ServerDrivenBlocks.Timing timing) {

        /** Whether this block state arms the mode. */
        public boolean arms(final BlockState state) {
            return this.states.contains(Block.getId(state));
        }

        /** Whether holding this stack arms the mode. */
        public boolean armsWith(final ItemStack stack) {
            return !stack.isEmpty() && this.items.contains(BuiltInRegistries.ITEM.getId(stack.getItem()));
        }
    }

    private static volatile @Nullable Published published;

    /**
     * Publishes a set, if {@code owner} owns the bound provider.
     *
     * @return {@code true} when accepted
     */
    public static boolean publish(final Plugin owner, final ServerDrivenBlocks blocks) {
        if (!BlockBreakSpeedRegistry.isProviderOwner(owner)) {
            LOGGER.warn("[BTCCore] server-driven: " + owner.getName() + " a voulu publier l'ensemble des blocs "
                + "conduits par le serveur sans posseder le fournisseur de vitesse lie — refuse.");
            return false;
        }
        if (blocks.isEmpty()) {
            published = null;
            return true;
        }
        final Published resolved = new Published(owner, resolveStates(blocks), resolveItems(blocks), blocks.timing());
        if (resolved.states().size() > LARGE_SET_WARNING) {
            LOGGER.warn("[BTCCore] server-driven: l'ensemble publie par " + owner.getName() + " arme "
                + resolved.states().size() + " etats de bloc — un tag trop large arme le mode presque partout.");
        }
        published = resolved;
        return true;
    }

    /** Clears the set, if {@code owner} published it. */
    public static void clear(final Plugin owner) {
        final Published current = published;
        if (current != null && current.owner().equals(owner)) {
            published = null;
        }
    }

    /**
     * The set in force: published, its owner still enabled, and the switch on.
     *
     * @return the set, or {@code null} when nothing arms the mode
     */
    public static @Nullable Published live() {
        if (!isSwitchOn()) return null;
        final Published current = published;
        if (current == null || !current.owner().isEnabled()) return null;
        return current;
    }

    /** The timings in force: the published ones, or the defaults when nothing is published. */
    public static ServerDrivenBlocks.Timing timing() {
        final Published current = live();
        return current == null ? ServerDrivenBlocks.Timing.DEFAULT : current.timing();
    }

    /** Whether the server may drive digs at all: break speed and {@code mining.server-driven} on. */
    public static boolean isSwitchOn() {
        return BTCCoreConfig.breakSpeedEnabled && BTCCoreConfig.serverDrivenEnabled;
    }

    private static StateIdSet resolveStates(final ServerDrivenBlocks blocks) {
        final BitSet ids = new BitSet();
        for (final Material material : blocks.blocks()) {
            final Block block = CraftMagicNumbers.getBlock(material);
            if (block == null || material.isAir()) {
                LOGGER.warn("[BTCCore] server-driven: " + material + " n'est pas un bloc — ignore.");
                continue;
            }
            for (final BlockState state : block.getStateDefinition().getPossibleStates()) {
                ids.set(Block.getId(state));
            }
        }
        for (final BlockData data : blocks.states()) {
            if (data instanceof CraftBlockData craft) {
                ids.set(Block.getId(craft.getState()));
            }
        }
        return StateIdSet.of(ids);
    }

    private static StateIdSet resolveItems(final ServerDrivenBlocks blocks) {
        final BitSet ids = new BitSet();
        for (final Material material : blocks.armingItems()) {
            final Item item = CraftMagicNumbers.getItem(material);
            if (item == null || material.isAir()) {
                LOGGER.warn("[BTCCore] server-driven: " + material + " n'est pas un objet — ignore.");
                continue;
            }
            ids.set(BuiltInRegistries.ITEM.getId(item));
        }
        return StateIdSet.of(ids);
    }
}
