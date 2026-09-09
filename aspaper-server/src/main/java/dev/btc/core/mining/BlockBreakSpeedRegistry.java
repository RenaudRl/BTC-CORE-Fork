package dev.btc.core.mining;

import dev.btc.core.api.mining.BreakSpeedProvider;
import dev.btc.core.config.BTCCoreConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Digging speed decided per block entered, rather than per tool held.
 *
 * <p><b>Why the attribute and not a server-side progress loop.</b> The obvious implementation —
 * the one MMOItems and CraftEngine use — drives the destroy progress from the server, which needs a
 * repeating task for every player currently mining and puts the animation the player sees at the
 * mercy of the round trip. Writing {@code minecraft:block_break_speed} instead costs one packet per
 * block <em>entered</em>, a few dozen a minute for a player in full flow, and the client animates at
 * the right speed on its own because it holds the same number the server does.
 *
 * <p><b>The limit, which must not be worked around.</b> The attribute is a multiplier on a
 * progression vanilla short-circuits when the block's hardness is zero: wheat, carrots, beetroot and
 * sugar cane break in one tick whatever is written here, and multiplying a null duration yields a
 * null duration. Lowering the hardness in the server registry does not help either — the client
 * carries its own hardness table compiled into its jar, would break the block instantly, announce
 * it, be refused, and the block would flicker back into place in front of the player. That is the
 * worst of the three possible behaviours. A crop with a real breaking time has to be a custom block,
 * whose hardness the client receives with the block state.
 *
 * <p><b>Where the numbers come from.</b> Tool affinity and the two hardness corrections are the
 * platform's, in {@link BreakSpeedTables}; the part that depends on the player is not, and comes
 * from the {@link BreakSpeedProvider} a plugin binds. With no provider bound the tables still apply,
 * which is a smaller but real effect — and the absence is announced once rather than passing for a
 * configuration that works.
 *
 * <p>Everything here is static and read from the region thread that owns the block.
 */
public final class BlockBreakSpeedRegistry {

    private BlockBreakSpeedRegistry() {
    }

    private static final org.apache.logging.log4j.Logger LOGGER =
        org.apache.logging.log4j.LogManager.getLogger("BTCCore");

    /**
     * The modifier this system owns.
     *
     * <p>Stable, so each recomputation replaces the previous value instead of stacking onto it.
     */
    public static final Identifier MODIFIER_ID = Identifier.fromNamespaceAndPath("btccore", "break_speed");

    private record Owned<T>(Plugin owner, T value) {
    }

    private static volatile Owned<BreakSpeedProvider> provider;

    private static volatile boolean warnedAboutMissingProvider = false;

    // ==================== REGISTRATION ====================

    /**
     * Binds the source of the player-dependent factor. One at a time; binding again replaces it.
     */
    public static void bindProvider(final Plugin owner, final BreakSpeedProvider newProvider) {
        provider = new Owned<>(owner, newProvider);
    }

    /** Unbinds the provider a plugin bound, if it is still the bound one. */
    public static void unbindProvider(final Plugin owner) {
        Owned<BreakSpeedProvider> current = provider;
        if (current != null && current.owner().equals(owner)) {
            provider = null;
        }
    }

    /** Whether a provider is bound and its plugin still enabled. */
    public static boolean hasProvider() {
        return live() != null;
    }

    private static BreakSpeedProvider live() {
        Owned<BreakSpeedProvider> current = provider;
        if (current == null || !current.owner().isEnabled()) return null;
        return current.value();
    }

    // ==================== INTERCEPTION ====================

    /**
     * Recomputes and sends the player's break speed for the block they have just started digging.
     *
     * <p>Called from {@code ServerPlayerGameMode.handleBlockBreakAction} on
     * {@code START_DESTROY_BLOCK}, before the server evaluates the block's destroy progress — so the
     * insta-mine decision the server makes and the animation the client plays are taken from the
     * same number.
     */
    public static void onStartDestroy(final ServerPlayer player, final BlockPos pos, final BlockState state) {
        if (!BTCCoreConfig.breakSpeedEnabled) return;

        ItemStack tool = player.getMainHandItem();

        dev.btc.core.api.mining.ToolFamily family = BreakSpeedTables.familyOf(tool);

        boolean inDomain = true;
        double table = 1.0;
        if (BTCCoreConfig.breakSpeedAffinityEnabled) {
            inDomain = BreakSpeedTables.inDomain(state, tool);
            if (!inDomain) table *= BTCCoreConfig.breakSpeedOffDomainFactor;
        }
        if (BTCCoreConfig.breakSpeedHardnessCorrectionsEnabled) {
            table *= BreakSpeedTables.hardnessCorrection(state);
        }

        double factor = table;
        BreakSpeedProvider bound = live();
        if (bound != null) {
            OptionalDouble statistic = bound.statisticMultiplier(
                new NmsBreakSpeedContext(player, pos, tool, table, inDomain, family));
            if (statistic.isPresent()) factor = table * statistic.getAsDouble();
        } else {
            warnOnceAboutMissingProvider();
        }

        apply(player, factor);
    }

    /**
     * Writes a freshly computed speed and pushes it to the player straight away.
     *
     * <p>The entity tracker would send the same attribute on its next pass, one tick later. One tick
     * is enough for the client to animate the first frames of the swing at the old speed and to
     * disagree with the server about when the block falls, so this pays one extra small packet to
     * have the two sides start from the same value.
     */
    private static void apply(final ServerPlayer player, final double factor) {
        AttributeInstance instance = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
        if (instance == null) return;

        boolean changed;
        if (factor == 1.0) {
            changed = instance.removeModifier(MODIFIER_ID);
        } else {
            // ADD_MULTIPLIED_TOTAL scales the finished value, so the amount is the factor minus one.
            instance.addOrUpdateTransientModifier(
                new AttributeModifier(MODIFIER_ID, factor - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            changed = true;
        }

        if (changed) {
            player.connection.send(new ClientboundUpdateAttributesPacket(player.getId(), List.of(instance)));
        }
    }

    /**
     * Says once that the tables are running alone.
     *
     * <p>This is the failure this system is most likely to have: both halves valid, nothing joining
     * them. The platform computes and sends a speed, a progression system exists that would supply
     * the tier factor, and nobody bound the two together — with no message at all, because a smaller
     * multiplier is a perfectly plausible number.
     */
    private static void warnOnceAboutMissingProvider() {
        if (warnedAboutMissingProvider) return;
        warnedAboutMissingProvider = true;
        LOGGER.warn("[BTCCore] break-speed: aucune source de statistiques liee. Les affinites d'outil et les "
            + "corrections de durete s'appliquent, la courbe de palier non — un plugin doit appeler "
            + "BTCCoreAPI#bindBreakSpeedProvider.");
    }
}
