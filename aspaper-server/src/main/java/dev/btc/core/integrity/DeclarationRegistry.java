package dev.btc.core.integrity;

import dev.btc.core.api.integrity.IntegrityAPI.ClientTerrainHandle;
import dev.btc.core.api.integrity.IntegrityAPI.CustomMechanic;
import dev.btc.core.api.integrity.IntegrityAPI.RangeProvider;
import dev.btc.core.api.integrity.IntegrityAPI.TeleportKind;
import dev.btc.core.api.integrity.IntegrityAPI.TerrainDivergence;
import dev.btc.core.api.integrity.IntegrityAPI.VisibilityProvider;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What extensions have told the engine about their own behaviour.
 *
 * <p>Declarations differ from exemptions in kind, not in degree. An exemption says "stop looking"; a
 * declaration says "here is what I did". The engine can fold a declaration into its prediction and stay
 * armed, which is the capability a third-party anticheat cannot have and the reason this platform is
 * native.
 *
 * <p>Everything here is read from check paths that may run off the region thread, so every structure is
 * concurrent and nothing dereferences a world.
 */
public final class DeclarationRegistry {

    /** How far a player may land from a declared destination and still be accepted, in blocks. */
    private static final double TELEPORT_TOLERANCE = 0.5;

    /** A velocity declaration, valid until its deadline. */
    public record DeclaredMechanic(CustomMechanic mechanic, long deadlineNanos) {
        public boolean live(long nowNanos) {
            return nowNanos - deadlineNanos < 0;
        }
    }

    /** A teleport declaration: a destination, not a blind window. */
    public record DeclaredTeleport(String worldName, double x, double y, double z,
                                   TeleportKind kind, long deadlineNanos) {
        public boolean live(long nowNanos) {
            return nowNanos - deadlineNanos < 0;
        }

        /** Whether an arrival matches what was declared. */
        public boolean matches(Location arrival) {
            if (arrival.getWorld() == null || !arrival.getWorld().getName().equals(worldName)) {
                return false;
            }
            double dx = arrival.getX() - x;
            double dy = arrival.getY() - y;
            double dz = arrival.getZ() - z;
            return (dx * dx + dy * dy + dz * dz) <= TELEPORT_TOLERANCE * TELEPORT_TOLERANCE;
        }
    }

    /** A region where the client's geometry deliberately differs from the server's. */
    public record DeclaredTerrain(BoundingBox region, TerrainDivergence kind, long deadlineNanos,
                                  AtomicBoolean released) {
        public boolean live(long nowNanos) {
            return !released.get() && nowNanos - deadlineNanos < 0;
        }
    }

    private final Map<UUID, CopyOnWriteArrayList<DeclaredMechanic>> mechanics = new ConcurrentHashMap<>();
    private final Map<UUID, CopyOnWriteArrayList<DeclaredTeleport>> teleports = new ConcurrentHashMap<>();
    private final Map<UUID, CopyOnWriteArrayList<DeclaredTerrain>> terrain = new ConcurrentHashMap<>();

    private final Map<NamespacedKey, Double> rangedWeapons = new ConcurrentHashMap<>();
    private final Map<NamespacedKey, RangeProvider> dynamicRanges = new ConcurrentHashMap<>();
    private final Set<NamespacedKey> technicalMarkers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> delegatedVehicles = ConcurrentHashMap.newKeySet();

    private final Map<NamespacedKey, Plugin> declarationOwners = new ConcurrentHashMap<>();

    /** A visibility provider and the plugin that posted it. */
    private record VisibilitySource(Plugin owner, VisibilityProvider provider) {}

    private final CopyOnWriteArrayList<VisibilitySource> visibilitySources = new CopyOnWriteArrayList<>();

    // ==================== VISIBILITY ====================

    public AutoCloseable registerVisibilityProvider(Plugin owner, VisibilityProvider provider) {
        if (owner == null || provider == null) {
            throw new IllegalArgumentException("a visibility provider needs an owner and a provider");
        }
        VisibilitySource source = new VisibilitySource(owner, provider);
        visibilitySources.add(source);
        return () -> visibilitySources.remove(source);
    }

    /**
     * Whether {@code viewer} sees {@code target}, as far as anyone has told the engine.
     *
     * <p>One {@code false} wins over any number of {@code true}: features that hide a pair are counted,
     * and the pair stays hidden while any of them wants it so. Empty when no provider has an opinion —
     * the engine then falls back on server entity state. A provider that throws has no opinion: an
     * extension's bug must not blind a check, nor grant a hit.
     */
    public Optional<Boolean> canSee(Player viewer, UUID target) {
        boolean anyVisible = false;
        for (VisibilitySource source : visibilitySources) {
            Optional<Boolean> opinion;
            try {
                opinion = source.provider().canSee(viewer, target);
            } catch (RuntimeException failure) {
                continue;
            }
            if (opinion == null || opinion.isEmpty()) {
                continue;
            }
            if (!opinion.get()) {
                return Optional.of(false);
            }
            anyVisible = true;
        }
        return anyVisible ? Optional.of(true) : Optional.empty();
    }

    /** Whether some feature shows {@code viewer} a client-only entity under this id. */
    public boolean isPhantomEntity(Player viewer, int entityId) {
        for (VisibilitySource source : visibilitySources) {
            try {
                if (source.provider().isPhantomEntity(viewer, entityId)) {
                    return true;
                }
            } catch (RuntimeException failure) {
                // No opinion; see canSee.
            }
        }
        return false;
    }

    // ==================== TRANSIENT DECLARATIONS ====================

    public void declareMechanic(UUID player, CustomMechanic mechanic, Duration ttl) {
        mechanics.computeIfAbsent(player, k -> new CopyOnWriteArrayList<>())
            .add(new DeclaredMechanic(mechanic, System.nanoTime() + ttl.toNanos()));
    }

    /**
     * The velocity the server declared for this player, if any is still live.
     *
     * <p>Returned as the vector rather than a boolean so that a movement check can accept exactly this
     * displacement and keep refusing anything beyond it.
     */
    public Optional<Vector> declaredVelocity(UUID player) {
        CopyOnWriteArrayList<DeclaredMechanic> list = mechanics.get(player);
        if (list == null) {
            return Optional.empty();
        }
        long now = System.nanoTime();
        list.removeIf(declared -> !declared.live(now));
        return list.stream()
            .map(declared -> declared.mechanic().appliedVelocity())
            .filter(Optional::isPresent)
            .map(Optional::get)
            .reduce(Vector::add);
    }

    public void declareTeleport(UUID player, Location destination, TeleportKind kind, Duration ttl) {
        if (destination.getWorld() == null) {
            throw new IllegalArgumentException("a declared teleport destination must have a world");
        }
        teleports.computeIfAbsent(player, k -> new CopyOnWriteArrayList<>())
            .add(new DeclaredTeleport(destination.getWorld().getName(),
                destination.getX(), destination.getY(), destination.getZ(),
                kind, System.nanoTime() + ttl.toNanos()));
    }

    /**
     * Whether an arrival was declared, consuming the declaration when it matches.
     *
     * <p>Consuming matters: a declaration authorises one relocation, not a window during which any
     * relocation passes.
     */
    public boolean consumeDeclaredTeleport(UUID player, Location arrival) {
        CopyOnWriteArrayList<DeclaredTeleport> list = teleports.get(player);
        if (list == null) {
            return false;
        }
        long now = System.nanoTime();
        list.removeIf(declared -> !declared.live(now));
        for (DeclaredTeleport declared : list) {
            if (declared.matches(arrival)) {
                list.remove(declared);
                return true;
            }
        }
        return false;
    }

    public ClientTerrainHandle declareTerrain(UUID player, BoundingBox region,
                                              TerrainDivergence kind, Duration ttl) {
        DeclaredTerrain declared = new DeclaredTerrain(region.clone(), kind,
            System.nanoTime() + ttl.toNanos(), new AtomicBoolean(false));
        CopyOnWriteArrayList<DeclaredTerrain> list =
            terrain.computeIfAbsent(player, k -> new CopyOnWriteArrayList<>());
        list.add(declared);
        return new TerrainHandle(list, declared);
    }

    /**
     * Whether this position sits in a region where the client's geometry was declared divergent.
     *
     * <p>Checks that reason from block collision must consult this before flagging: a player standing
     * on a block that exists only in packets sent to them is not phasing.
     */
    public boolean isClientTerrainDeclared(UUID player, double x, double y, double z) {
        CopyOnWriteArrayList<DeclaredTerrain> list = terrain.get(player);
        if (list == null) {
            return false;
        }
        long now = System.nanoTime();
        list.removeIf(declared -> !declared.live(now));
        return list.stream().anyMatch(declared -> declared.region().contains(x, y, z));
    }

    // ==================== DURABLE DECLARATIONS ====================

    public void registerRangedWeapon(Plugin owner, NamespacedKey item, double maxRange) {
        if (maxRange <= 0) {
            throw new IllegalArgumentException("a ranged weapon needs a positive range");
        }
        rangedWeapons.put(item, maxRange);
        declarationOwners.put(item, owner);
    }

    public void registerDynamicRangeProvider(Plugin owner, NamespacedKey item, RangeProvider provider) {
        dynamicRanges.put(item, provider);
        declarationOwners.put(item, owner);
    }

    /**
     * The legitimate reach for this item, preferring a dynamic provider over a fixed value.
     *
     * <p>A provider that throws is treated as absent rather than allowed to break a check: an
     * extension's bug must not become a hole.
     */
    public OptionalDoubleRange declaredRange(NamespacedKey item, Player player, Entity target) {
        RangeProvider provider = dynamicRanges.get(item);
        if (provider != null) {
            try {
                double range = provider.maxRange(player, target);
                if (range > 0) {
                    return new OptionalDoubleRange(true, range);
                }
            } catch (RuntimeException ignored) {
                // Fall through to the fixed declaration, then to the vanilla bound.
            }
        }
        Double fixed = rangedWeapons.get(item);
        return fixed == null ? OptionalDoubleRange.absent() : new OptionalDoubleRange(true, fixed);
    }

    /** A range that may be absent, without allocating an {@code Optional<Double>} per check. */
    public record OptionalDoubleRange(boolean present, double value) {
        public static OptionalDoubleRange absent() {
            return new OptionalDoubleRange(false, 0);
        }
    }

    public void registerTechnicalEntityMarker(Plugin owner, NamespacedKey pdcKey) {
        technicalMarkers.add(pdcKey);
        declarationOwners.put(pdcKey, owner);
    }

    /** Keys that mark an entity as furniture rather than a participant. */
    public Set<NamespacedKey> technicalEntityMarkers() {
        return Set.copyOf(technicalMarkers);
    }

    public void delegateVehicleValidation(Plugin owner, UUID vehicleEntityId) {
        delegatedVehicles.add(vehicleEntityId);
    }

    /** Whether another system owns this vehicle's correction, and the platform must not act. */
    public boolean isVehicleDelegated(UUID vehicleEntityId) {
        return delegatedVehicles.contains(vehicleEntityId);
    }

    // ==================== LIFECYCLE ====================

    public void clearPlayer(UUID player) {
        mechanics.remove(player);
        teleports.remove(player);
        terrain.remove(player);
    }

    /** Drops every durable declaration owned by a plugin being disabled. */
    public void clearOwner(Plugin owner) {
        List<NamespacedKey> owned = declarationOwners.entrySet().stream()
            .filter(entry -> entry.getValue().equals(owner))
            .map(Map.Entry::getKey)
            .toList();
        owned.forEach(key -> {
            rangedWeapons.remove(key);
            dynamicRanges.remove(key);
            technicalMarkers.remove(key);
            declarationOwners.remove(key);
        });
        visibilitySources.removeIf(source -> source.owner().equals(owner));
    }

    private record TerrainHandle(CopyOnWriteArrayList<DeclaredTerrain> list, DeclaredTerrain declared)
        implements ClientTerrainHandle {

        @Override
        public boolean isActive() {
            return declared.live(System.nanoTime());
        }

        @Override
        public Duration remaining() {
            long left = declared.deadlineNanos() - System.nanoTime();
            return (declared.released().get() || left <= 0) ? Duration.ZERO : Duration.ofNanos(left);
        }

        @Override
        public void close() {
            if (declared.released().compareAndSet(false, true)) {
                list.remove(declared);
            }
        }
    }
}
