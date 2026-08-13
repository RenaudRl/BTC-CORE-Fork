package dev.btc.core.api.drop;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Everything known about a drop roll, handed to a {@link DropProvider} or a {@link DropTransformer}.
 *
 * <p>Most accessors are nullable because a loot table is rolled in very different situations: a
 * block break has a block state and no entity, a mob death has an entity and no block state, a
 * structure chest has neither. Read only what the source you registered for guarantees.
 *
 * <p>Instances live for the duration of one roll and must not be retained.
 */
public interface DropContext {

    /**
     * A best-effort classification of what is being rolled.
     *
     * @return the kind of event, never {@code null}
     */
    DropSource source();

    /**
     * The identifier of the loot table being rolled.
     *
     * <p>This is the authoritative identity of the roll. It is {@code null} only for tables built at
     * runtime that were never registered — a plugin passing its own {@code LootTable} object, for
     * instance.
     *
     * @return the loot table key, or {@code null} for an unregistered table
     */
    NamespacedKey lootTable();

    /**
     * The world the roll happens in.
     *
     * @return the world, never {@code null}
     */
    World world();

    /**
     * Where the drop originates.
     *
     * @return the origin, or {@code null} when the table declares no origin
     */
    Location origin();

    /**
     * The block being broken.
     *
     * @return the block data, or {@code null} when this is not a block roll
     */
    BlockData blockData();

    /**
     * The material of the block being broken, a shortcut over {@link #blockData()}.
     *
     * @return the material, or {@code null} when this is not a block roll
     */
    Material blockType();

    /**
     * The entity the table is rolled for — the mob that died, the sheep being sheared.
     *
     * @return the entity, or {@code null} when this is not an entity roll
     */
    Entity entity();

    /**
     * The player credited with the drop: the killer, the breaker, the fisher.
     *
     * <p>Absent for every non-player cause — an explosion nobody lit, a hopper, a structure chest
     * generated during world generation. A provider must never assume it is present.
     *
     * @return the player, or {@code null} when no player caused the roll
     */
    Player player();

    /**
     * The tool used, when the roll declares one.
     *
     * @return a copy of the tool, or {@code null} when no tool took part
     */
    ItemStack tool();

    /**
     * The radius of the explosion that caused this drop.
     *
     * <p>Present only when an explosion is responsible. A provider replaying vanilla behaviour needs
     * it: vanilla destroys part of a block's drops in an explosion, each stack surviving with a
     * probability of {@code 1 / radius}. Ignoring it makes TNT mining strictly better than digging.
     *
     * @return the radius, or {@code null} when no explosion is involved
     */
    Float explosionRadius();

    /**
     * Rolls the vanilla loot table and returns what it produced.
     *
     * <p>Computed on first call and cached for the rest of this roll, so it costs nothing until a
     * provider actually asks for it. Use it to <em>augment</em> vanilla rather than replace it.
     *
     * @return the vanilla drops, possibly empty, never {@code null}
     */
    List<ItemStack> vanillaDrops();
}
