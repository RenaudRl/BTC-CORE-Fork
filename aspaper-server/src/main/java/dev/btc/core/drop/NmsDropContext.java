package dev.btc.core.drop;

import dev.btc.core.api.drop.DropContext;
import dev.btc.core.api.drop.DropSource;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link DropContext} handed to providers, reading straight from the loot context.
 *
 * <p>Every accessor resolves on demand and caches: a provider that only looks at the block type must
 * not pay for converting the entity, and {@link #vanillaDrops()} must not roll the table until
 * somebody actually asks. One instance serves one roll and is not thread-safe, which is fine — a
 * roll never leaves the region thread that started it.
 */
final class NmsDropContext implements DropContext {

    private static final Object UNRESOLVED = new Object();

    private final LootTable table;
    private final LootContext context;

    private DropSource source;
    private Object lootTable = UNRESOLVED;
    private Object origin = UNRESOLVED;
    private Object blockData = UNRESOLVED;
    private Object entity = UNRESOLVED;
    private Object player = UNRESOLVED;
    private Object tool = UNRESOLVED;
    private List<ItemStack> vanillaDrops;

    NmsDropContext(final LootTable table, final LootContext context) {
        this.table = table;
        this.context = context;
    }

    @Override
    public DropSource source() {
        if (this.source == null) this.source = classify();
        return this.source;
    }

    /**
     * Classifies the roll.
     *
     * <p>Order matters: a fishing table carries {@code THIS_ENTITY} — the angler — so testing for an
     * entity first would file every catch under {@link DropSource#ENTITY}.
     */
    private DropSource classify() {
        if (this.context.hasParameter(LootContextParams.BLOCK_STATE)) return DropSource.BLOCK;

        NamespacedKey key = lootTable();
        if (key != null) {
            String path = key.getKey();
            if (path.startsWith("chests/")) return DropSource.CONTAINER;
            if (path.contains("fishing")) return DropSource.FISHING;
        }

        if (this.context.hasParameter(LootContextParams.THIS_ENTITY)) return DropSource.ENTITY;
        return DropSource.OTHER;
    }

    @Override
    public NamespacedKey lootTable() {
        if (this.lootTable == UNRESOLVED) {
            // craftLootTable is stamped on at registration time; a table a plugin built itself has none.
            this.lootTable = this.table.craftLootTable == null ? null : this.table.craftLootTable.getKey();
        }
        return (NamespacedKey) this.lootTable;
    }

    @Override
    public World world() {
        return this.context.getLevel().getWorld();
    }

    @Override
    public Location origin() {
        if (this.origin == UNRESOLVED) {
            Vec3 vec = this.context.getOptionalParameter(LootContextParams.ORIGIN);
            this.origin = vec == null ? null : new Location(world(), vec.x, vec.y, vec.z);
        }
        return (Location) this.origin;
    }

    @Override
    public BlockData blockData() {
        if (this.blockData == UNRESOLVED) {
            var state = this.context.getOptionalParameter(LootContextParams.BLOCK_STATE);
            this.blockData = state == null ? null : CraftBlockData.createData(state);
        }
        return (BlockData) this.blockData;
    }

    @Override
    public Material blockType() {
        BlockData data = blockData();
        return data == null ? null : data.getMaterial();
    }

    @Override
    public Entity entity() {
        if (this.entity == UNRESOLVED) {
            net.minecraft.world.entity.Entity nms = this.context.getOptionalParameter(LootContextParams.THIS_ENTITY);
            this.entity = nms == null ? null : nms.getBukkitEntity();
        }
        return (Entity) this.entity;
    }

    /**
     * The player credited with the drop.
     *
     * <p>Three parameters can name one, and they do not overlap: a mob death fills
     * {@code LAST_DAMAGE_PLAYER}, a block break or a fishing catch fills {@code THIS_ENTITY}, and a
     * projectile kill fills {@code ATTACKING_ENTITY} with the shooter.
     */
    @Override
    public Player player() {
        if (this.player == UNRESOLVED) {
            this.player = resolvePlayer();
        }
        return (Player) this.player;
    }

    private Player resolvePlayer() {
        net.minecraft.world.entity.player.Player last = this.context.getOptionalParameter(LootContextParams.LAST_DAMAGE_PLAYER);
        if (last != null && last.getBukkitEntity() instanceof Player bukkit) return bukkit;

        net.minecraft.world.entity.Entity self = this.context.getOptionalParameter(LootContextParams.THIS_ENTITY);
        if (self != null && self.getBukkitEntity() instanceof Player bukkit) return bukkit;

        net.minecraft.world.entity.Entity attacker = this.context.getOptionalParameter(LootContextParams.ATTACKING_ENTITY);
        if (attacker != null && attacker.getBukkitEntity() instanceof Player bukkit) return bukkit;

        return null;
    }

    @Override
    public ItemStack tool() {
        if (this.tool == UNRESOLVED) {
            ItemInstance instance = this.context.getOptionalParameter(LootContextParams.TOOL);
            // TOOL widened to ItemInstance in 26.2; only a real stack can be handed back to a plugin.
            this.tool = instance instanceof net.minecraft.world.item.ItemStack stack
                ? CraftItemStack.asBukkitCopy(stack)
                : null;
        }
        return (ItemStack) this.tool;
    }

    @Override
    public Float explosionRadius() {
        return this.context.getOptionalParameter(LootContextParams.EXPLOSION_RADIUS);
    }

    @Override
    public List<ItemStack> vanillaDrops() {
        if (this.vanillaDrops == null) {
            List<ItemStack> collected = new ArrayList<>();
            // btcVanillaRandomItemsRaw is the untouched vanilla body, so this cannot re-enter the
            // drop API and loop.
            this.table.btcVanillaRandomItemsRaw(this.context, stack -> {
                if (!stack.isEmpty()) collected.add(CraftItemStack.asBukkitCopy(stack));
            });
            this.vanillaDrops = collected;
        }
        return this.vanillaDrops;
    }
}
