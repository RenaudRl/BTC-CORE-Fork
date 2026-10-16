package dev.btc.core.mining;

import dev.btc.core.api.mining.BreakDecision;
import dev.btc.core.api.mining.BreakSpeedProvider;
import dev.btc.core.config.BTCCoreConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
 * platform's, in {@link BreakSpeedTables}; the curve (tier, profession, hardness reference) belongs
 * to the {@link BreakSpeedProvider} a plugin binds. A provider that answers with a factor
 * <em>replaces</em> the tables — it carries the whole curve, and applying both would count the same
 * thing twice. The tables apply alone with no provider bound, or when the provider declines with
 * an empty answer, which is a smaller but real effect — and the absence of a provider is announced
 * once rather than passing for a configuration that works. A provider factor of zero is a refusal:
 * see {@link #onStartDestroy}.
 *
 * <p>Everything here is static and read from the thread that ticks the player (see
 * {@link #onStartDestroy} for the thread contract).
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

    private static volatile boolean warnedAboutUnusableFactor = false;

    private static volatile boolean warnedAboutServerDrivenNotAllowed = false;

    /** Provider classes whose failure has been logged; concurrent so a regionised build stays safe. */
    private static final java.util.Set<String> FAILED_PROVIDERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ==================== REGISTRATION ====================

    /**
     * Binds the source of the player-dependent factor. One at a time, one owner: the plugin that
     * holds the binding may replace it (a reload), another plugin is refused while the holder is
     * enabled (see {@link SingleOwner}).
     *
     * @return {@code true} when the provider was bound
     */
    public static boolean bindProvider(final Plugin owner, final BreakSpeedProvider newProvider) {
        Owned<BreakSpeedProvider> current = provider;
        if (current != null && !SingleOwner.accepts(current.owner(), current.owner().isEnabled(), owner)) {
            LOGGER.warn("[BTCCore] break-speed: " + owner.getName() + " a voulu lier un fournisseur alors que "
                + current.owner().getName() + " en tient deja un — refuse.");
            return false;
        }
        provider = new Owned<>(owner, newProvider);
        return true;
    }

    /** Whether {@code owner} holds the bound provider and is still enabled. */
    public static boolean isProviderOwner(final Plugin owner) {
        Owned<BreakSpeedProvider> current = provider;
        return current != null && current.owner().isEnabled() && current.owner().equals(owner);
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
     *
     * <p><b>Who decides the factor.</b> With a provider bound, its factor <em>replaces</em> the
     * platform tables: the provider carries the whole curve (affinity, hardness, tier) and the
     * tables are only handed to it as context. The tables apply alone when no provider is bound or
     * when it declines with {@link OptionalDouble#empty()}.
     *
     * <p><b>Refusal.</b> A provider factor of zero (or less) means the block must not be dug at all.
     * The attribute is set to zero and sent, and this method returns {@code true}: the hook in
     * {@code handleBlockBreakAction} then returns before {@code getDestroyProgress}, exactly as if a
     * {@code BlockDamageEvent} had been cancelled — no first crack, no {@code isDestroyingBlock}.
     *
     * <p><b>Thread.</b> {@code handleBlockBreakAction} runs from the packet handler after
     * {@code PacketUtils.ensureRunningOnSameThread}, i.e. on the thread that ticks the player: the
     * main thread on this single-region fork, the region thread that owns the player under a
     * regionised build. The targeted block is within interaction reach of that player, so the
     * block, its chunk, the player's inventory and attributes are all read on their own thread, and
     * this method touches nothing else. The bound provider is called on that same thread and must
     * answer from memory (see {@link BreakSpeedProvider}); this class performs no I/O and holds no
     * per-player state beyond the modifier on the player's own attribute.
     *
     * <p><b>Server-driven digs.</b> A {@link BreakDecision.ServerDriven} answer is followed only for a
     * vanilla block of hardness 0, with {@code mining.server-driven.enabled} on and the player not
     * opted out; the attribute then carries no platform factor (1.0) and the caller measures the dig.
     * Anywhere else it is a decline, logged once.
     *
     * @return {@link BreakDecision.Refused} when the dig must stop here,
     *         {@link BreakDecision.ServerDriven} when the caller must measure it at that rate, and
     *         {@link BreakDecision.Declined} when vanilla reads the progress from the attribute just
     *         written — including when the system is switched off
     */
    public static BreakDecision onStartDestroy(final ServerPlayer player, final BlockPos pos, final BlockState state) {
        if (!BTCCoreConfig.breakSpeedEnabled) return VANILLA;

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

        BreakDecision decision = VANILLA;
        BreakSpeedProvider bound = live();
        if (bound != null) {
            // The provider is another plugin's code inside the player's packet handler: a failure
            // there must cost a decline, not a disconnected player.
            final NmsBreakSpeedContext context =
                new NmsBreakSpeedContext(player, pos, state, tool, table, inDomain, family);
            decision = BreakSpeedFactor.askDecision(
                () -> bound.decide(context),
                failure -> warnOnceAboutProviderFailure(bound, failure));
        } else {
            warnOnceAboutMissingProvider();
        }

        // "Switch on" here also means a set published by a live owner: the mode is only kept on with
        // one, and a server-driven dig outside the mode could never finish.
        final boolean serverDrivenAllowed = BreakSpeedFactor.serverDrivenAllowed(
            ServerDrivenRegistry.live() != null && player.gameMode.isSurvival(),
            player.gameMode.btcServerDrivenOptOut,
            BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace(),
            state.getDestroySpeed(player.level(), pos));
        BreakSpeedFactor.Verdict verdict = BreakSpeedFactor.decide(table, decision, serverDrivenAllowed);
        switch (verdict.warning()) {
            case UNUSABLE_NUMBER -> warnOnceAboutUnusableFactor(decision);
            case SERVER_DRIVEN_NOT_ALLOWED -> warnOnceAboutServerDrivenNotAllowed(state);
            case NONE -> { }
        }
        apply(player, verdict.factor());
        return switch (verdict.kind()) {
            case FACTOR -> VANILLA;
            case REFUSED -> REFUSED;
            case SERVER_DRIVEN -> BreakDecision.serverDriven(verdict.progressPerTick());
        };
    }

    /** The answer that leaves the progress to vanilla, shared: a record without components. */
    private static final BreakDecision VANILLA = BreakDecision.declined();

    private static final BreakDecision REFUSED = BreakDecision.refused();

    /**
     * Writes a freshly computed speed and pushes it to the player straight away — unless it is the
     * speed the client already holds.
     *
     * <p>The entity tracker would send the same attribute on its next pass, one tick later. One tick
     * is enough for the client to animate the first frames of the swing at the old speed and to
     * disagree with the server about when the block falls, so this pays one extra small packet to
     * have the two sides start from the same value.
     *
     * <p><b>Why an unchanged value sends nothing.</b> {@code START_DESTROY_BLOCK} is a client packet:
     * a modified client can send it as fast as it likes, and every one would cost a packet. The last
     * factor sent to a player is the modifier this system owns on <em>that player's own</em>
     * attribute instance, which the client is always resynchronised from (respawn, dimension
     * change, tracker pass), so it needs no map of its own, no cleanup on quit and no
     * synchronisation. Comparing against it is the per-player memory.
     */
    private static void apply(final ServerPlayer player, final double factor) {
        AttributeInstance instance = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
        if (instance == null) return;

        AttributeModifier previous = instance.getModifier(MODIFIER_ID);
        if (!BreakSpeedFactor.needsPacket(previous == null ? null : previous.amount(), factor)) return;

        if (factor == 1.0) {
            instance.removeModifier(MODIFIER_ID);
        } else {
            // ADD_MULTIPLIED_TOTAL scales the finished value, so the amount is the factor minus one;
            // a refusal (factor 0) is therefore an amount of -1.
            instance.addOrUpdateTransientModifier(new AttributeModifier(
                MODIFIER_ID, BreakSpeedFactor.modifierAmount(factor), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }

        player.connection.send(new ClientboundUpdateAttributesPacket(player.getId(), List.of(instance)));
    }

    /**
     * Says once per provider class that it threw, with the stack trace of the first time.
     *
     * <p>The block is dug with the platform tables meanwhile. One entry per class, not per block
     * entered: a provider that always fails would otherwise fill the log at the pace of the swings.
     */
    private static void warnOnceAboutProviderFailure(final BreakSpeedProvider provider, final Throwable failure) {
        if (!BreakSpeedFactor.isFirstFailureOf(FAILED_PROVIDERS, provider.getClass())) return;
        LOGGER.error("[BTCCore] break-speed: le fournisseur " + provider.getClass().getName()
            + " a leve une exception — repli sur les tables tant qu'il echoue."
            + " Cet avertissement n'est emis qu'une fois par classe.", failure);
    }

    /**
     * Says once that a provider answered with something that is not a number.
     *
     * <p>The answer is declined (the tables apply); logging every block entered would bury the
     * server console under a bug that is the provider's, not the player's.
     */
    private static void warnOnceAboutUnusableFactor(final BreakDecision value) {
        if (warnedAboutUnusableFactor) return;
        warnedAboutUnusableFactor = true;
        LOGGER.warn("[BTCCore] break-speed: le fournisseur a rendu un nombre inutilisable (" + value
            + ") — la reponse est ignoree et les tables s'appliquent. Cet avertissement n'est emis qu'une fois.");
    }

    /**
     * Says once that a server-driven dig was asked where the platform does not drive one.
     *
     * <p>The answer is declined, so the block breaks as it always did. The usual causes are the
     * switch {@code mining.server-driven.enabled} left off, a block of hardness above 0 or of another
     * namespace, or a player opted out (Bedrock) whom the provider did not recognise.
     */
    private static void warnOnceAboutServerDrivenNotAllowed(final BlockState state) {
        if (warnedAboutServerDrivenNotAllowed) return;
        warnedAboutServerDrivenNotAllowed = true;
        LOGGER.warn("[BTCCore] server-driven: le fournisseur a demande une casse conduite par le serveur pour "
            + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + ", ou elle n'est pas admise (interrupteur "
            + "mining.server-driven.enabled, aucun ensemble publie, bloc hors minecraft: ou de durete > 0, joueur "
            + "exclu ou hors survie) — traitee comme un declin. Cet avertissement n'est emis qu'une fois.");
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
