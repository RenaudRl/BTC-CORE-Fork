package dev.btc.core.mining;

import dev.btc.core.api.mining.BreakSpeedContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
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
    private final BlockState state;
    private final ItemStack tool;
    private final double tableMultiplier;
    private final boolean inToolDomain;
    private final dev.btc.core.api.mining.ToolFamily toolFamily;

    private Block bukkitBlock;
    private org.bukkit.inventory.ItemStack bukkitTool;

    NmsBreakSpeedContext(final ServerPlayer player, final BlockPos pos, final BlockState state,
                         final ItemStack tool, final double tableMultiplier, final boolean inToolDomain,
                         final dev.btc.core.api.mining.ToolFamily toolFamily) {
        this.player = player;
        this.pos = pos;
        this.state = state;
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
            this.bukkitTool = CraftItemStack.asBukkitMirror(this.tool);
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

    @Override
    public double blockHardness() {
        // The hardness of the server state itself, not of the Material: a plugin that registers its
        // own block states (CraftEngine) sets the hardness per state, and the progression vanilla
        // computes divides by exactly this number.
        return this.state.getDestroySpeed(this.player.level(), this.pos);
    }

    @Override
    public double toolSpeed() {
        // Mirrors the first lines of Player#getDestroySpeed: the item's speed on this state, plus
        // MINING_EFFICIENCY only when the tool is actually efficient here. Haste, fatigue, water,
        // air and BLOCK_BREAK_SPEED come after and are deliberately left out — the last one is the
        // attribute this system writes, and reading it back would feed a factor into itself.
        float speed = this.tool.getDestroySpeed(this.state);
        if (speed > 1.0F) {
            speed += (float) this.player.getAttributeValue(Attributes.MINING_EFFICIENCY);
        }
        return speed;
    }

    @Override
    public boolean correctToolForDrops() {
        return this.player.hasCorrectToolForDrops(this.state);
    }
}

