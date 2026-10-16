package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Projectile;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BTCCore: Projectile Pooling.
 * Tracks active projectile counts per world to prevent projectile spam
 * from causing excessive chunk loading and entity ticking.
 *
 * Wired via:
 * - Bukkit event: ProjectileLaunchEvent in BTCCoreListener (tracks new projectiles)
 * - Bukkit event: EntityRemoveEvent (releases the slot of a projectile that goes away)
 * - NMS hook: ThrownEnderpearl.tick(), around the chunk ticket a pearl requests when it
 *   crosses into another chunk (see {@link #tryLoadChunk(int)})
 */
public class ProjectilePool {
    private static final int HARD_CAP_PER_WORLD = 200;

    private static final Map<String, AtomicInteger> worldProjectileCounts = new ConcurrentHashMap<>();
    /** Projectile entity id -> world it was counted in; a projectile is released at most once. */
    private static final Map<Integer, String> countedProjectileWorlds = new ConcurrentHashMap<>();
    private static final Map<Integer, AtomicInteger> projectileChunkLoads = new ConcurrentHashMap<>();
    private static final Set<Integer> poolExemptProjectileIds = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger chunkLoadsThisTick = new AtomicInteger(0);
    private static final NamespacedKey POOL_EXEMPTION_KEY =
            new NamespacedKey("btc-core", "projectile-pool-exempt");

    /**
     * Called when a projectile is launched.
     * Returns true if the projectile should be allowed, false if rejected (hard cap exceeded).
     */
    public static boolean onLaunch(Projectile projectile) {
        if (!BTCCoreConfig.projectilePoolingEnabled) return true;
        if (isPoolExempt(projectile)) {
            poolExemptProjectileIds.add(projectile.getEntityId());
            return true;
        }

        if (!admit(projectile.getEntityId(), projectile.getWorld().getName())) {
            projectile.remove();
            return false;
        }
        return true;
    }

    /**
     * Called when a projectile is removed (hit, expire, etc).
     */
    public static void onRemove(Projectile projectile) {
        if (!BTCCoreConfig.projectilePoolingEnabled) return;
        if (poolExemptProjectileIds.remove(projectile.getEntityId()) || isPoolExempt(projectile)) return;

        release(projectile.getEntityId());
    }

    /** Counts a projectile against its world; false when the world is already at the hard cap. */
    static boolean admit(int projectileEntityId, String worldName) {
        AtomicInteger count = worldProjectileCounts.computeIfAbsent(worldName, k -> new AtomicInteger(0));
        if (count.get() >= HARD_CAP_PER_WORLD) return false;

        count.incrementAndGet();
        countedProjectileWorlds.put(projectileEntityId, worldName);
        return true;
    }

    /** Frees the slot taken by {@link #admit}; a projectile that was never counted frees nothing. */
    static void release(int projectileEntityId) {
        projectileChunkLoads.remove(projectileEntityId);
        String worldName = countedProjectileWorlds.remove(projectileEntityId);
        if (worldName == null) return;

        AtomicInteger count = worldProjectileCounts.get(worldName);
        if (count != null) {
            count.decrementAndGet();
        }
    }

    /**
     * Asks whether a projectile may load a chunk now. Called from the NMS hook in
     * ThrownEnderpearl.tick(), the one place where a projectile asks the server to load chunks
     * for it (the ender pearl chunk ticket).
     *
     * A refusal means "do not load": the caller skips the ticket and the projectile lives on.
     * It must never remove the projectile. Both budgets are charged only when the load is
     * granted, so a refused projectile does not eat the budget of the others.
     *
     * @param projectileEntityId The entity ID of the projectile
     * @return true if the chunk load should proceed, false to skip
     */
    public static boolean tryLoadChunk(int projectileEntityId) {
        if (!BTCCoreConfig.projectilePoolingEnabled) return true;
        if (poolExemptProjectileIds.contains(projectileEntityId)) return true;

        AtomicInteger loads = projectileChunkLoads.computeIfAbsent(projectileEntityId, k -> new AtomicInteger(0));
        if (loads.get() >= BTCCoreConfig.projectileMaxLoadsPerProjectile) return false;

        int used;
        do {
            used = chunkLoadsThisTick.get();
            if (used >= BTCCoreConfig.projectileMaxLoadsPerTick) return false;
        } while (!chunkLoadsThisTick.compareAndSet(used, used + 1));

        loads.incrementAndGet();
        return true;
    }

    /**
     * Global per-tick projectile chunk load counter.
     * Called from PerformanceManager.onTickStart().
     */
    public static void onTickStart() {
        chunkLoadsThisTick.set(0);
    }

    /**
     * Clears all tracking data for a world (on world unload).
     */
    public static void clearWorld(String worldName) {
        worldProjectileCounts.remove(worldName);
        countedProjectileWorlds.values().removeIf(worldName::equals);
    }

    private static boolean isPoolExempt(Projectile projectile) {
        return projectile.getPersistentDataContainer().has(POOL_EXEMPTION_KEY, PersistentDataType.BYTE);
    }
}
