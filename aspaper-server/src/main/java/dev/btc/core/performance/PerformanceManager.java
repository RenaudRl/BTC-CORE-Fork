package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.Location;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BTCCore: Central manager for performance optimizations.
 * Provides utility methods used by the public API, Bukkit event hooks, and NMS patches.
 * All mutable state is thread-safe (volatile or concurrent collections).
 */
public final class PerformanceManager {

    private static volatile long currentTick = 0;

    // Current MSPT (milliseconds per tick), updated at tick start.
    // Read by plugins via BTCCoreAPI.getCurrentMspt() to throttle async work under load.
    private static volatile double currentMspt = 0.0;

    // Light throttle (atomic for thread safety)
    private static final AtomicInteger lightUpdatesThisTick = new AtomicInteger(0);

    // Lazy chunk tickets
    private static final Map<Long, Long> lazyChunkTickets = new ConcurrentHashMap<>();

    private PerformanceManager() {}

    /**
     * Called at the start of each server tick.
     */
    public static void onTickStart(long tick) {
        currentTick = tick;
        // Read the server's average tick time (MSPT over last ~5s).
        // Available on Paper/Folia via Server.getAverageTickTime().
        try {
            currentMspt = Bukkit.getServer().getAverageTickTime();
        } catch (NoSuchMethodError ignored) {
            // Fallback for very old APIs — leave at 0.0
        }
        lightUpdatesThisTick.set(0);
        ProjectilePool.onTickStart();
        BatchedInventoryUpdates.onTickStart(tick);
    }

    // ==================== COLLISION THROTTLE ====================

    public static boolean shouldCalculateCollision(Entity entity, int nearbyEntityCount) {
        if (!BTCCoreConfig.collisionThrottleEnabled) return true;
        if (nearbyEntityCount <= BTCCoreConfig.collisionThrottleMaxEntities) return true;
        int frequency = 1 + (nearbyEntityCount / BTCCoreConfig.collisionThrottleMaxEntities);
        return (currentTick % frequency) == 0;
    }

    // ==================== PARTICLE/SOUND/HUD CULLING ====================

    public static boolean shouldSendParticle(Player player, Location particleLocation) {
        if (!BTCCoreConfig.particleCullingEnabled) return true;
        if (player.getWorld() != particleLocation.getWorld()) return false;
        double distanceSquared = player.getLocation().distanceSquared(particleLocation);
        int maxDistanceSquared = BTCCoreConfig.particleCullingDistance * BTCCoreConfig.particleCullingDistance;
        return distanceSquared <= maxDistanceSquared;
    }

    public static boolean shouldSendSound(Player player, Location soundLocation) {
        if (!BTCCoreConfig.soundCullingEnabled) return true;
        if (player.getWorld() != soundLocation.getWorld()) return false;
        double distanceSquared = player.getLocation().distanceSquared(soundLocation);
        int maxDistanceSquared = BTCCoreConfig.soundCullingDistance * BTCCoreConfig.soundCullingDistance;
        return distanceSquared <= maxDistanceSquared;
    }

    public static boolean shouldSendBetterHud(Player player, Location hudSourceLocation) {
        if (!BTCCoreConfig.betterHudCullingEnabled) return true;
        if (player.getWorld() != hudSourceLocation.getWorld()) return false;
        double distanceSquared = player.getLocation().distanceSquared(hudSourceLocation);
        int maxDistanceSquared = BTCCoreConfig.betterHudCullingDistance * BTCCoreConfig.betterHudCullingDistance;
        return distanceSquared <= maxDistanceSquared;
    }

    // ==================== LIGHT THROTTLE ====================

    /**
     * Checks if a light update should be processed this tick.
     * Called from NMS hook in ThreadedLevelLightEngine.
     */
    public static boolean shouldProcessLightUpdate() {
        if (!BTCCoreConfig.lightThrottleEnabled) return true;
        if (lightUpdatesThisTick.get() >= BTCCoreConfig.lightThrottleMaxPerTick) return false;
        lightUpdatesThisTick.incrementAndGet();
        return true;
    }

    // ==================== LAZY CHUNK TICKETS ====================

    /**
     * Registers a lazy chunk ticket for extended retention.
     */
    public static void registerLazyChunkTicket(long chunkKey) {
        if (BTCCoreConfig.lazyChunkTicketsEnabled) {
            lazyChunkTickets.put(chunkKey, currentTick);
        }
    }

    /**
     * Checks if a chunk should remain loaded due to lazy ticket.
     */
    public static boolean hasLazyChunkTicket(long chunkKey) {
        if (!BTCCoreConfig.lazyChunkTicketsEnabled) return false;
        Long registeredTick = lazyChunkTickets.get(chunkKey);
        if (registeredTick == null) return false;
        if (currentTick - registeredTick > BTCCoreConfig.lazyChunkTicketsRetentionTicks) {
            lazyChunkTickets.remove(chunkKey);
            return false;
        }
        return true;
    }

    // ==================== UTILITY ====================

    public static long getCurrentTick() {
        return currentTick;
    }

    /**
     * Returns the current server MSPT (milliseconds per tick).
     * This is the average tick time over the last ~5 seconds.
     * Plugins use this to throttle async tasks when the server is under load.
     *
     * @return The average MSPT, or 0.0 if unavailable.
     */
    public static double getCurrentMspt() {
        return currentMspt;
    }
}
