package dev.btc.core.mining;

import dev.btc.core.api.mining.ToolFamily;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The two tables the platform can fill on its own: tool affinity, and the hardness corrections.
 *
 * <p>Both answer from vanilla data — the {@code mineable/*} block tags and the tool item tags —
 * rather than from a hand-written list of materials. A list would have to be revisited at every
 * Minecraft version and would drift in silence when it was not; the tags are maintained by the game
 * and by the server's own datapacks, so a block added later lands in the right family without
 * anything here being touched.
 */
public final class BreakSpeedTables {

    private BreakSpeedTables() {
    }

    /** Obsidian: 50 → 25. Applied as a multiplier, never by editing the registry. */
    private static final double OBSIDIAN_CORRECTION = 50.0 / 25.0;

    /** Raw ore blocks: 5 → 3, the hardness of the ore they came from. */
    private static final double RAW_ORE_BLOCK_CORRECTION = 5.0 / 3.0;

    /**
     * The block family a held item is the tool for, or {@code null} when it is not a digging tool.
     *
     * <p>A sword, a torch or an empty hand answer {@code null}, and that is deliberate: they are not
     * the wrong tool for anything, they are simply no tool, and penalising them would slow a bare
     * hand below what vanilla already gives it.
     */
    private static TagKey<Block> domainOf(final ItemStack tool) {
        return switch (familyOf(tool)) {
            case PICKAXE -> BlockTags.MINEABLE_WITH_PICKAXE;
            case AXE -> BlockTags.MINEABLE_WITH_AXE;
            case SHOVEL -> BlockTags.MINEABLE_WITH_SHOVEL;
            case HOE -> BlockTags.MINEABLE_WITH_HOE;
            case NONE -> null;
        };
    }

    /**
     * The family a held item belongs to, from the vanilla item tags.
     *
     * <p>Also the answer handed to a break-speed provider, so a plugin never has to classify the
     * tool a second time from its material name.
     */
    public static ToolFamily familyOf(final ItemStack tool) {
        if (tool.isEmpty()) return ToolFamily.NONE;
        if (tool.is(ItemTags.PICKAXES)) return ToolFamily.PICKAXE;
        if (tool.is(ItemTags.AXES)) return ToolFamily.AXE;
        if (tool.is(ItemTags.SHOVELS)) return ToolFamily.SHOVEL;
        if (tool.is(ItemTags.HOES)) return ToolFamily.HOE;
        return ToolFamily.NONE;
    }

    /** Whether a block belongs to any tool's family at all. */
    private static boolean hasFamily(final BlockState state) {
        return state.is(BlockTags.MINEABLE_WITH_PICKAXE)
            || state.is(BlockTags.MINEABLE_WITH_AXE)
            || state.is(BlockTags.MINEABLE_WITH_SHOVEL)
            || state.is(BlockTags.MINEABLE_WITH_HOE);
    }

    /**
     * Whether the held tool is in its domain for this block.
     *
     * <p>Answers {@code true} for a block that belongs to no family and for an item that is no tool:
     * out of domain means "this tool has a family, the block has another one", not "these two do not
     * match".
     */
    public static boolean inDomain(final BlockState state, final ItemStack tool) {
        TagKey<Block> domain = domainOf(tool);
        if (domain == null) return true;
        if (state.is(domain)) return true;
        return !hasFamily(state);
    }

    /**
     * The affinity factor for a tool on a block.
     *
     * @param offDomainFactor the factor to apply out of domain, {@code 0.4} by default — a penalty
     *                        rather than a refusal, because refusing teaches the player they were
     *                        wrong while slowing them down teaches them why
     */
    public static double affinity(final BlockState state, final ItemStack tool, final double offDomainFactor) {
        return inDomain(state, tool) ? 1.0 : offDomainFactor;
    }

    /**
     * The correction owed to a block whose vanilla hardness was set for a game this server is not
     * playing.
     *
     * <p>Only two blocks are corrected, and one candidate is deliberately left alone:
     *
     * <ul>
     *   <li><b>Obsidian</b> guards a portal one builds once in vanilla; here it is an ordinary
     *       building material, and fifteen seconds a block turns a façade into a toll.</li>
     *   <li><b>Raw ore blocks</b> are harder than the ore they came from, which punishes a player
     *       for having tidied their chest. Aligning them changes no balance and removes a penalty
     *       nobody chose.</li>
     *   <li><b>Ancient debris</b> keeps its hardness. Its slowness <em>is</em> what makes the tier:
     *       debris that breaks quickly stops being sought and starts being collected.</li>
     * </ul>
     *
     * @return the multiplier, {@code 1.0} for every block that is not corrected
     */
    public static double hardnessCorrection(final BlockState state) {
        if (state.is(Blocks.OBSIDIAN)) return OBSIDIAN_CORRECTION;
        if (state.is(Blocks.RAW_IRON_BLOCK) || state.is(Blocks.RAW_COPPER_BLOCK) || state.is(Blocks.RAW_GOLD_BLOCK)) {
            return RAW_ORE_BLOCK_CORRECTION;
        }
        return 1.0;
    }
}
