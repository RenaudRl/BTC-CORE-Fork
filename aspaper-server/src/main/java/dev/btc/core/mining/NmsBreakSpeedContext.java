package dev.btc.core.mining;

import dev.btc.core.api.mining.BreakSpeedContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;

/**
 * The context handed to a break-speed provider.
 *
 * <p>Built only when a provider is actually bound: without one, nothing here is worth the two
 * wrapper objects it costs on every block a player starts digging.
 */
final class NmsBreakSpeedContext implements BreakSpeedContext {

    private final ServerPlayer player;
    private final BlockPos pos;
    private final ItemStack tool;
    private final double tableMultiplier;
    private final boolean inToolDomain;
    private final dev.btc.core.api.mining.ToolFamily toolFamily;

    private Block bukkitBlock;
    private org.bukkit.inventory.ItemStack bukkitTool;

    NmsBreakSpeedContext(final ServerPlayer player, final BlockPos pos,
                         final ItemStack tool, final double tableMultiplier, final boolean inToolDomain,
                         final dev.btc.core.api.mining.ToolFamily toolFamily) {
        this.player = player;
        this.pos = pos;
        this.tool = tool;
        this.tableMultiplier = tableMultiplier;
        this.inToolDomain = inToolDomain;
        this.toolFamily = toolFamily;
    }

    @Override
    public dev.btc.core.api.mining.ToolFamily toolFamily() {
        return this.toolFamily;
    }

    @Override
    public Player player() {
        return this.player.getBukkitEntity();
    }

    @Override
    public Block block() {
        if (this.bukkitBlock == null) {
            this.bukkitBlock = CraftBlock.at(this.player.level(), this.pos);
        }
        return this.bukkitBlock;
    }

    @Override
    public org.bukkit.inventory.ItemStack tool() {
        if (this.bukkitTool == null) {
            this.bukkitTool = CraftItemStack.asCraftMirror(this.tool);
        }
        return this.bukkitTool;
    }

    @Override
    public double tableMultiplier() {
        return this.tableMultiplier;
    }

    @Override
    public boolean inToolDomain() {
        return this.inToolDomain;
    }
}

