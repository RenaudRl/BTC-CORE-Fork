package dev.btc.core.integrity;

import dev.btc.core.api.integrity.IntegrityAPI;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of {@link IntegrityAPI}, published through {@code META-INF/services}.
 *
 * <p>Deliberately thin: it validates arguments and delegates. The registries own the invariants, so
 * that the engine's own check paths and this public facade cannot drift apart — they read the same
 * structures.
 *
 * <p>Resolved by extensions through {@code Services.service()}, the same mechanism already in
 * production for {@code BTCCoreAPI}.
 */
public final class IntegrityAPIImpl implements IntegrityAPI {

    private static final ExemptionRegistry EXEMPTIONS = new ExemptionRegistry();
    private static final DeclarationRegistry DECLARATIONS = new DeclarationRegistry();
    private static final CheckRegistry CHECKS = new CheckRegistry();
    private static final ViolationBus VIOLATIONS = new ViolationBus();

    /** Engine-side access to the exemption state. */
    public static ExemptionRegistry exemptions() {
        return EXEMPTIONS;
    }

    /** Engine-side access to what extensions have declared. */
    public static DeclarationRegistry declarations() {
        return DECLARATIONS;
    }

    /** Engine-side access to the check registry and violation levels. */
    public static CheckRegistry checks() {
        return CHECKS;
    }

    /** Engine-side access to the violation bus. */
    public static ViolationBus violations() {
        return VIOLATIONS;
    }

    /**
     * Drops everything a player owned. Called when they disconnect.
     *
     * <p>Not part of the public API: an extension has no business clearing another's state.
     */
    public static void forgetPlayer(UUID player) {
        EXEMPTIONS.clearPlayer(player);
        DECLARATIONS.clearPlayer(player);
        CHECKS.clearPlayer(player);
    }

    /**
     * Drops everything a plugin owned. Called when it is disabled.
     *
     * <p>A disabled plugin cannot close its own handles; leaving its exemptions live would be a
     * permanent hole opened by an ordinary reload.
     */
    public static void forgetOwner(Plugin owner) {
        EXEMPTIONS.clearOwner(owner);
        DECLARATIONS.clearOwner(owner);
        CHECKS.clearOwner(owner);
        VIOLATIONS.clearOwner(owner);
    }

    // ==================== EXEMPTION ====================

    @Override
    public ExemptionHandle exempt(Plugin owner, Player player, ExemptionScope scope, Duration ttl) {
        requirePlayer(player);
        return EXEMPTIONS.grant(owner, player.getUniqueId(), scope, ttl);
    }

    @Override
    public ExemptionHandle exemptAll(Plugin owner, Player player, Set<CheckGroup> groups,
                                     ExemptionReason reason, Duration ttl) {
        requirePlayer(player);
        if (groups == null || groups.isEmpty()) {
            throw new IllegalArgumentException("a grouped exemption needs at least one group");
        }
        return EXEMPTIONS.grantAll(owner, player.getUniqueId(), groups, reason, ttl);
    }

    @Override
    public boolean isExempted(Player player, ExemptionScope scope) {
        requirePlayer(player);
        return EXEMPTIONS.isExempted(player.getUniqueId(), scope);
    }

    // ==================== DECLARATION ====================

    @Override
    public ExemptionHandle declareCustomMechanic(Plugin owner, Player player, CustomMechanic mechanic,
                                                 ExemptionScope scope, Duration ttl) {
        requirePlayer(player);
        if (mechanic == null) {
            throw new IllegalArgumentException("a declaration needs a mechanic");
        }
        // The declaration records what was applied; the exemption bounds how long the engine will
        // tolerate the resulting divergence. Both are needed: the vector alone cannot say when the
        // client is expected to have caught up.
        DECLARATIONS.declareMechanic(player.getUniqueId(), mechanic, ttl);
        return EXEMPTIONS.grant(owner, player.getUniqueId(), scope, ttl);
    }

    @Override
    public ExemptionHandle declareTeleport(Plugin owner, Player player, Location destination,
                                           TeleportKind kind, Duration ttl) {
        requirePlayer(player);
        if (destination == null || kind == null) {
            throw new IllegalArgumentException("a declared teleport needs a destination and a kind");
        }
        DECLARATIONS.declareTeleport(player.getUniqueId(), destination, kind, ttl);
        return EXEMPTIONS.grant(owner, player.getUniqueId(),
            new ExemptionScope(CheckGroup.POSITION, ExemptionReason.SCRIPTED_TELEPORT), ttl);
    }

    @Override
    public ClientTerrainHandle declareClientSideTerrain(Plugin owner, Player player, BoundingBox region,
                                                        TerrainDivergence kind, Duration ttl) {
        requirePlayer(player);
        if (region == null || kind == null) {
            throw new IllegalArgumentException("a terrain declaration needs a region and a kind");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("a terrain declaration requires a positive TTL");
        }
        return DECLARATIONS.declareTerrain(player.getUniqueId(), region, kind, ttl);
    }

    // ==================== DURABLE DECLARATIONS ====================

    @Override
    public void registerRangedWeapon(Plugin owner, NamespacedKey item, double maxRange) {
        requireOwned(owner, item);
        DECLARATIONS.registerRangedWeapon(owner, item, maxRange);
    }

    @Override
    public void registerDynamicRangeProvider(Plugin owner, NamespacedKey item, RangeProvider provider) {
        requireOwned(owner, item);
        if (provider == null) {
            throw new IllegalArgumentException("a dynamic range needs a provider");
        }
        DECLARATIONS.registerDynamicRangeProvider(owner, item, provider);
    }

    @Override
    public void registerTechnicalEntityMarker(Plugin owner, NamespacedKey pdcKey) {
        requireOwned(owner, pdcKey);
        DECLARATIONS.registerTechnicalEntityMarker(owner, pdcKey);
    }

    @Override
    public void delegateVehicleValidation(Plugin owner, UUID vehicleEntityId) {
        if (owner == null || vehicleEntityId == null) {
            throw new IllegalArgumentException("delegation needs an owner and a vehicle");
        }
        DECLARATIONS.delegateVehicleValidation(owner, vehicleEntityId);
    }

    // ==================== CHECKS ====================

    @Override
    public CheckHandle registerCheck(Plugin owner, CheckDefinition check) {
        return CHECKS.register(owner, check);
    }

    @Override
    public void unregisterCheck(Plugin owner, CheckId checkId) {
        CHECKS.unregister(owner, checkId);
    }

    // ==================== VIOLATIONS ====================

    @Override
    public ViolationSubscription onViolation(Plugin owner, ViolationListener listener) {
        return VIOLATIONS.subscribe(owner, listener);
    }

    // ==================== STATE ====================

    @Override
    public PlayerCheckState stateOf(Player player, CheckId checkId) {
        requirePlayer(player);
        if (checkId == null) {
            throw new IllegalArgumentException("a state lookup needs a check id");
        }
        UUID id = player.getUniqueId();
        CheckGroup group = CHECKS.find(checkId)
            .map(registered -> registered.definition().group())
            .orElse(null);

        Optional<ExemptionReason> reason = group == null
            ? Optional.empty()
            : EXEMPTIONS.reasonFor(id, group);

        return new PlayerCheckState(
            CHECKS.violationLevel(id, checkId),
            reason.isPresent(),
            reason,
            CHECKS.appliesTo(checkId, player));
    }

    private static void requirePlayer(Player player) {
        if (player == null) {
            throw new IllegalArgumentException("a player is required");
        }
    }

    private static void requireOwned(Plugin owner, NamespacedKey key) {
        if (owner == null || key == null) {
            throw new IllegalArgumentException("a durable declaration needs an owner and a key");
        }
    }
}
