package dev.btc.core.api.integrity;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Public facade of the BTC-CORE integrity platform (Sentinel).
 *
 * <p>This interface exists so that an extension can tell the engine the truth about a custom
 * mechanic instead of switching the engine off. That distinction is the whole reason the platform is
 * native: a third-party anticheat faced with a non-vanilla mechanic can only exempt a check — which
 * blinds it for the duration — whereas an engine that lives inside the server can widen its model of
 * what is legitimate and stay armed.
 *
 * <h2>Two mechanisms, one contract</h2>
 * <ul>
 *   <li>{@link #exempt} suspends a group of checks for a typed reason and a bounded time. Use it when
 *       there is nothing precise to declare.</li>
 *   <li>{@link #declareCustomMechanic}, {@link #declareTeleport} and
 *       {@link #declareClientSideTerrain} state <em>what</em> the server just did. The checks stay
 *       armed and simply know more. Prefer these whenever the information exists.</li>
 * </ul>
 *
 * <h2>Ownership</h2>
 * Everything returned here is owned by the caller: only the holder of a handle may close it, and every
 * handle expires on its own. This is deliberate. NoCheatPlus documents, in its own source, that
 * exemptions shared as plain flags make plugins fight each other; Matrix goes further and disables a
 * check server-wide while one player performs a legitimate action. Neither is reproducible through
 * this API, by construction.
 *
 * <h2>Stability</h2>
 * This is a stable facade. Detection internals, scoring and verdict logic are deliberately absent and
 * will never appear here. New capabilities arrive as {@code default} methods whose fallback is always
 * the safe answer — an unknown check is never exempt. Enum constants are added, never removed.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * IntegrityAPI integrity = IntegrityAPI.instance();
 *
 * // A scripted launch: declare the vector, then apply it.
 * Vector push = direction.multiply(power);
 * try (var handle = integrity.declareCustomMechanic(plugin, player,
 *         new IntegrityAPI.CustomMechanic(IntegrityAPI.MechanicType.LAUNCH, Optional.of(push)),
 *         new IntegrityAPI.ExemptionScope(IntegrityAPI.CheckGroup.MOVEMENT,
 *                                         IntegrityAPI.ExemptionReason.SCRIPTED_KNOCKBACK),
 *         Duration.ofMillis(500))) {
 *     player.setVelocity(push);
 * }
 * }</pre>
 *
 * An extension must tolerate this API being absent, exactly as it does for {@code BTCCoreAPI}:
 * {@code runCatching { IntegrityAPI.instance() }.getOrNull()}.
 */
public interface IntegrityAPI {

    // ==================== IDENTITY ====================

    /**
     * Identifier of a check, namespaced by its owner.
     *
     * <p>Namespacing is not decoration: sixty extensions register against this platform, and a bare
     * name like {@code "speed"} would collide sooner rather than later.
     *
     * @param namespace owning plugin or subsystem, lowercase
     * @param name check name within that namespace, lowercase
     */
    record CheckId(String namespace, String name) {
        public CheckId {
            if (namespace == null || namespace.isBlank()) {
                throw new IllegalArgumentException("check namespace must not be blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("check name must not be blank");
            }
        }

        @Override
        public String toString() {
            return namespace + ':' + name;
        }
    }

    /** Families of checks an exemption or declaration can scope to. */
    enum CheckGroup {
        /** Position, velocity, flight, fall, phase, timing of movement packets. */
        MOVEMENT,
        /** Attack reach, hit validity, attack cadence, knockback response. */
        COMBAT,
        /** Block breaking and placing, container and item interaction. */
        INTERACTION,
        /** Absolute position changes: teleports, world transfers, setbacks. */
        POSITION,
        /** Player pose, hitbox and visibility coherence. */
        POSTURE
    }

    // ==================== EXEMPTION ====================

    /**
     * Why a check is being relaxed. The engine uses the reason to decide <em>which part</em> of its
     * evaluation to relax, so a reason is never decorative.
     */
    enum ExemptionReason {
        SCRIPTED_KNOCKBACK,
        SCRIPTED_TELEPORT,
        VEHICLE_LAUNCH,
        CUSTOM_PROJECTILE_LAUNCH,
        PLUGIN_CONTROLLED_MOVEMENT,
        /** Area damage, auras and hazard zones that move or hurt without a melee attacker. */
        ENVIRONMENTAL_HAZARD,
        /** Camera-controlled sequences where the client is not driving the player at all. */
        CINEMATIC,
        ADMIN_OVERRIDE
    }

    /**
     * What is being relaxed, and why.
     *
     * <p>A scope is always a group plus a reason — never a whole check. Relaxing "the flight check"
     * during a wall vault would blind the server to actual flight; widening the vertical tolerance for
     * a launch reason does not.
     */
    record ExemptionScope(CheckGroup group, ExemptionReason reason) {
        public ExemptionScope {
            if (group == null) {
                throw new IllegalArgumentException("exemption scope requires a check group");
            }
            if (reason == null) {
                throw new IllegalArgumentException("exemption scope requires a reason");
            }
        }
    }

    /**
     * A live exemption, owned by whoever requested it.
     *
     * <p>Closing is idempotent and reserved to the holder. The TTL is a backstop, not a formality: an
     * extension that crashes between acquiring and releasing must not leave a permanent hole.
     */
    interface ExemptionHandle extends AutoCloseable {

        /** Whether this handle still contributes to an active exemption. */
        boolean isActive();

        /** Time left before automatic expiry; {@link Duration#ZERO} once expired or closed. */
        Duration remaining();

        /** Releases this handle's contribution. Idempotent; other holders are unaffected. */
        @Override
        void close();
    }

    /**
     * Suspends a group of checks for one player, for a bounded time.
     *
     * <p>Exemptions on the same {@code (player, scope)} are reference-counted: the relaxation lasts
     * until the last holder closes or expires, so two extensions cannot cancel each other's work.
     *
     * @param owner the plugin that will hold the handle
     * @param player the player concerned
     * @param scope which group is relaxed, and why
     * @param ttl mandatory upper bound on the exemption's life
     * @return a handle owned by {@code owner}
     */
    ExemptionHandle exempt(Plugin owner, Player player, ExemptionScope scope, Duration ttl);

    /**
     * Suspends several groups at once under one reason, returning a single handle.
     *
     * <p>Cinematics need this: a camera sequence has to drop movement, combat, interaction and
     * position together, and stacking four handles that must all be closed correctly is a leak waiting
     * to happen.
     */
    ExemptionHandle exemptAll(Plugin owner, Player player, Set<CheckGroup> groups,
                              ExemptionReason reason, Duration ttl);

    /** Whether the given scope is currently relaxed for this player. */
    boolean isExempted(Player player, ExemptionScope scope);

    // ==================== DECLARATION ====================

    /** Kind of server-driven change being declared. */
    enum MechanicType {
        /** A single impulse, typically from a hit. */
        KNOCKBACK,
        /** A self-propelling launch: leap, grapple, elytra boost, wind charge. */
        LAUNCH,
        /**
         * A velocity the server re-asserts every tick for as long as the mechanic lasts: a wall
         * climb, a conveyor, a tractor beam.
         *
         * <p>Distinct from {@link #LAUNCH} for a reason that decides whether a check fires. A launch
         * is an impulse the engine expects to <em>decay</em> under gravity and drag; a sustained
         * velocity does not decay, because it is rewritten before it can. An engine that models a
         * wall climb as a launch predicts a fall that never comes and flags every climb.
         */
        SUSTAINED_VELOCITY,
        /** Area effect drawing entities inward. */
        AOE_PULL,
        /** Area effect pushing entities outward. */
        AOE_PUSH,
        /** A movement-speed change applied by an attribute or effect. */
        SPEED_MODIFIER,
        /** Flight granted to a player who would not otherwise have it. */
        FLIGHT_GRANT,
        /** A pose or hitbox overridden by the server. */
        POSTURE_OVERRIDE,
        /** Damage immunity granted or reset outside the vanilla cadence. */
        INVULNERABILITY_WINDOW
    }

    /**
     * What the server just applied to a player.
     *
     * @param type the kind of change
     * @param appliedVelocity the exact vector applied, when the mechanic is a velocity change. Supply
     *     it whenever it is known: it is the difference between the engine accepting <em>this</em>
     *     displacement and the engine accepting <em>any</em> displacement.
     */
    record CustomMechanic(MechanicType type, Optional<Vector> appliedVelocity) {
        public CustomMechanic {
            if (type == null) {
                throw new IllegalArgumentException("a declared mechanic requires a type");
            }
            if (appliedVelocity == null) {
                throw new IllegalArgumentException("appliedVelocity must be an Optional, not null");
            }
        }
    }

    /**
     * Declares a server-driven change <em>before</em> the client packet it will produce.
     *
     * <p>Timing is the point. Declaring after the packet has been evaluated is a race the caller
     * loses; such a declaration is reported as late and does not clear an existing violation.
     *
     * @return a handle that expires with {@code ttl}, same ownership contract as {@link #exempt}
     */
    ExemptionHandle declareCustomMechanic(Plugin owner, Player player, CustomMechanic mechanic,
                                          ExemptionScope scope, Duration ttl);

    /** Why a player is being relocated. */
    enum TeleportKind {
        SCRIPTED,
        /** Round start, round end, respawn inside a minigame arena. */
        ARENA_PHASE,
        /** A mob ability that moves the player: swap, ender grab, pull. */
        MOB_ABILITY,
        ADMIN,
        WORLD_TRANSFER
    }

    /**
     * Declares where a player is about to be moved.
     *
     * <p>Deliberately not an exemption: the engine accepts arrival at {@code destination} and keeps
     * refusing every other displacement during the window. A blanket suspension would open a hole
     * exactly where teleports are most useful to an attacker.
     */
    ExemptionHandle declareTeleport(Plugin owner, Player player, Location destination,
                                    TeleportKind kind, Duration ttl);

    /** How a client's view of the world departs from the server's. */
    enum TerrainDivergence {
        /** The client sees solid blocks the server does not have. */
        PHANTOM_SOLID,
        /** The client sees air where the server has blocks. */
        PHANTOM_AIR,
        /** The client sees different blocks than the server has, of comparable shape. */
        PHANTOM_REPLACED
    }

    /** A live terrain-divergence declaration, owned by its requester. */
    interface ClientTerrainHandle extends AutoCloseable {
        boolean isActive();

        Duration remaining();

        @Override
        void close();
    }

    /**
     * Declares that a player is being shown a world geometry that differs from the server's.
     *
     * <p>Sent-per-viewer blocks are invisible to any engine that reasons from server collision, and
     * mechanics built on them — quicksand, avalanches, phantom platforms — otherwise produce
     * guaranteed, permanent false positives on phase, fall and scaffold checks.
     *
     * <p>Only checks that reason from block collision are affected, and only for this player inside
     * this region. Everything else stays armed.
     */
    ClientTerrainHandle declareClientSideTerrain(Plugin owner, Player player, BoundingBox region,
                                                 TerrainDivergence kind, Duration ttl);

    // ==================== DURABLE DECLARATIONS ====================

    /**
     * Declares that an item legitimately reaches further than melee range.
     *
     * <p>Registered once, consulted continuously — a ranged weapon must not require an exemption per
     * shot.
     */
    void registerRangedWeapon(Plugin owner, NamespacedKey item, double maxRange);

    /** Supplies a reach bound that cannot be known ahead of time. */
    @FunctionalInterface
    interface RangeProvider {
        /**
         * @return the maximum legitimate distance for this use, or a negative value to fall back to
         *     the vanilla bound
         */
        double maxRange(Player player, Entity target);
    }

    /**
     * Declares an item whose reach depends on the shot rather than on a fixed value.
     *
     * <p>Homing and ricocheting projectiles have no single range: the legitimate distance depends on
     * the target that was acquired.
     */
    void registerDynamicRangeProvider(Plugin owner, NamespacedKey item, RangeProvider provider);

    /**
     * Declares a persistent-data key that marks technical entities.
     *
     * <p>Anchors, hitbox carriers and display stands are furniture, not participants; targeting and
     * reach checks must ignore them.
     */
    void registerTechnicalEntityMarker(Plugin owner, NamespacedKey pdcKey);

    /**
     * Hands validation of a vehicle's movement to its owner.
     *
     * <p>Where an extension already validates and rolls back its own vehicle, the platform must not
     * apply a correction of its own: two correctors on one entity fight, and the player pays.
     */
    void delegateVehicleValidation(Plugin owner, UUID vehicleEntityId);

    // ==================== CHECKS ====================

    /** Decides whether a check applies to a player right now. */
    @FunctionalInterface
    interface CheckActivationPolicy {
        boolean appliesTo(Player player);

        /** A policy that applies everywhere. */
        static CheckActivationPolicy always() {
            return player -> true;
        }
    }

    /**
     * How violations accumulate and what thresholds they cross.
     *
     * @param decayPerSecond how fast a violation level falls back toward zero
     * @param setbackThreshold level at which the platform corrects position
     * @param restrictThreshold level at which the platform withdraws a privilege
     * @param observationOnly when true the check journals but never acts, whatever the thresholds say
     */
    record ViolationModel(double decayPerSecond, double setbackThreshold,
                          double restrictThreshold, boolean observationOnly) {

        /** A model that detects and journals without ever acting in game. */
        public static ViolationModel observing(double decayPerSecond) {
            return new ViolationModel(decayPerSecond, Double.MAX_VALUE, Double.MAX_VALUE, true);
        }
    }

    /** Definition of a check being registered. */
    record CheckDefinition(CheckId id, CheckGroup group,
                           CheckActivationPolicy activationPolicy, ViolationModel violationModel) {}

    /** Registration of a custom check, revocable only by its owner. */
    interface CheckHandle extends AutoCloseable {
        CheckId id();

        @Override
        void close();
    }

    /** Registers a check owned by {@code owner}. */
    CheckHandle registerCheck(Plugin owner, CheckDefinition check);

    /** Removes a check. A plugin may only remove a check it registered. */
    void unregisterCheck(Plugin owner, CheckId checkId);

    // ==================== VIOLATIONS ====================

    /**
     * A raised violation.
     *
     * @param playerId who triggered it
     * @param check which check raised it
     * @param violationLevel the accumulated level after this violation
     * @param verboseDetail human-readable detail, for staff diagnosis
     * @param cancelled whether a prior listener already absorbed the default response
     */
    record ViolationEvent(UUID playerId, CheckId check, double violationLevel,
                          String verboseDetail, boolean cancelled) {}

    /** Receives violations. */
    @FunctionalInterface
    interface ViolationListener {
        /**
         * @return {@code true} to absorb the platform's default response for this violation
         */
        boolean onViolation(ViolationEvent event);
    }

    /** A live subscription, closed by its owner. */
    interface ViolationSubscription extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Subscribes to violations.
     *
     * <p>Events are republished on the scheduler that owns the player, never delivered from a Netty or
     * scoring thread — a listener may therefore touch the world safely.
     */
    ViolationSubscription onViolation(Plugin owner, ViolationListener listener);

    // ==================== STATE ====================

    /**
     * Aggregate state of one check for one player.
     *
     * @param violationLevel current accumulated level
     * @param exempted whether the check's group is relaxed right now
     * @param activeExemptionReason the reason, when exempted
     * @param enabledInCurrentContext whether the check's activation policy currently applies
     */
    record PlayerCheckState(double violationLevel, boolean exempted,
                            Optional<ExemptionReason> activeExemptionReason,
                            boolean enabledInCurrentContext) {}

    /** Reads the current state of a check for a player. */
    PlayerCheckState stateOf(Player player, CheckId checkId);

    // ==================== ACCESS ====================

    /**
     * @return the running implementation
     * @throws IllegalStateException when the server is not running BTC-CORE
     */
    static IntegrityAPI instance() {
        return Holder.INSTANCE;
    }

    class Holder {
        private static final IntegrityAPI INSTANCE =
            net.kyori.adventure.util.Services.service(IntegrityAPI.class)
                .orElseThrow(() -> new IllegalStateException(
                    "IntegrityAPI implementation not found — server must be running BTC-CORE"));
    }
}
