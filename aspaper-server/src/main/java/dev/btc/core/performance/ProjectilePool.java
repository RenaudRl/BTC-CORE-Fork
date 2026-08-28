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
 * - Bukkit event: ProjectileHitEvent / EntityRemoveEvent (decrements counts)
 * - NMS hook: chunk loading check in Projectile.tick() via apply-btccore-patches.py
 */
public class ProjectilePool {
    private static final int HARD_CAP_PER_WORLD = 200;

    private static final Map<String, AtomicInteger> worldProjectileCounts = new ConcurrentHashMap<>();
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

        String worldName = projectile.getWorld().getName();
        AtomicInteger count = worldProjectileCounts.computeIfAbsent(worldName, k -> new AtomicInteger(0));

        if (count.get() >= HARD_CAP_PER_WORLD) {
            projectile.remove();
            return false;
        }

        count.incrementAndGet();
        return true;
    }

    /**
     * Called when a projectile is removed (hit, expire, etc).
     */
    public static void onRemove(Projectile projectile) {
        if (!BTCCoreConfig.projectilePoolingEnabled) return;
        if (poolExemptProjectileIds.remove(projectile.getEntityId()) || isPoolExempt(projectile)) return;

        String worldName = projectile.getWorld().getName();
        AtomicInteger count = worldProjectileCounts.get(worldName);
        if (count != null) {
            count.decrementAndGet();
        }
        // Clean up per-projectile chunk load tracker
        projectileChunkLoads.remove(projectile.getEntityId());
    }

    /**
     * Checks if a projectile should be allowed to load a chunk.
     * Called from NMS hook in Projectile.tick().
     *
     * @param projectileEntityId The entity ID of the projectile
     * @return true if the chunk load should proceed, false to skip
     */
    public static boolean shouldLoadChunk(int projectileEntityId) {
        if (!BTCCoreConfig.projectilePoolingEnabled) return true;
        if (poolExemptProjectileIds.contains(projectileEntityId)) return true;

        AtomicInteger loads = projectileChunkLoads.computeIfAbsent(projectileEntityId, k -> new AtomicInteger(0));
        if (loads.get() >= BTCCoreConfig.projectileMaxLoadsPerProjectile) {
            return false;
        }
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
     * Checks if any projectile should be allowed to load a chunk this tick.
     * Called from NMS hook in Projectile.tick() before per-projectile check.
     */
    public static boolean shouldLoadChunkThisTick() {
        if (!BTCCoreConfig.projectilePoolingEnabled) return true;
        if (chunkLoadsThisTick.get() >= BTCCoreConfig.projectileMaxLoadsPerTick) return false;
        chunkLoadsThisTick.incrementAndGet();
        return true;
    }

    /**
     * Clears all tracking data for a world (on world unload).
     */
    public static void clearWorld(String worldName) {
        worldProjectileCounts.remove(worldName);
    }

    private static boolean isPoolExempt(Projectile projectile) {
        return projectile.getPersistentDataContainer().has(POOL_EXEMPTION_KEY, PersistentDataType.BYTE);
    }
}
