package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.Location;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BTCCore: Central manager for performance optimizations.
 */
public final class PerformanceManager {

    private static final Map<Long, Integer> chunkRedstoneUpdates = new ConcurrentHashMap<>();
    private static final Map<Long, Long> lazyChunkTickets = new ConcurrentHashMap<>();
    private static int lightUpdatesThisTick = 0;
    private static long currentTick = 0;

    private PerformanceManager() {}

    /**
     * Called at the start of each server tick.
     */
    public static void onTickStart(long tick) {
        currentTick = tick;
        lightUpdatesThisTick = 0;
        chunkRedstoneUpdates.clear();
    }

    /**
     * Checks if collision should be calculated for an entity based on nearby entity count.
     */
    public static boolean shouldCalculateCollision(Entity entity, int nearbyEntityCount) {
        if (!BTCCoreConfig.collisionThrottleEnabled) {
            return true;
        }

        if (nearbyEntityCount <= BTCCoreConfig.collisionThrottleMaxEntities) {
            return true;
        }

        // Throttle based on entity count - check every N ticks
        int frequency = 1 + (nearbyEntityCount / BTCCoreConfig.collisionThrottleMaxEntities);
        return (currentTick % frequency) == 0;
    }

    /**
     * Checks if a particle should be sent to a player based on distance.
     */
    public static boolean shouldSendParticle(Player player, Location particleLocation) {
        if (!BTCCoreConfig.particleCullingEnabled) {
            return true;
        }

        if (player.getWorld() != particleLocation.getWorld()) {
            return false;
        }

        double distanceSquared = player.getLocation().distanceSquared(particleLocation);
        int maxDistanceSquared = BTCCoreConfig.particleCullingDistance * BTCCoreConfig.particleCullingDistance;

        return distanceSquared <= maxDistanceSquared;
    }

    /**
     * Checks if a sound should be sent to a player based on distance.
     */
    public static boolean shouldSendSound(Player player, Location soundLocation) {
        if (!BTCCoreConfig.soundCullingEnabled) {
            return true;
        }

        if (player.getWorld() != soundLocation.getWorld()) {
            return false;
        }

        double distanceSquared = player.getLocation().distanceSquared(soundLocation);
        int maxDistanceSquared = BTCCoreConfig.soundCullingDistance * BTCCoreConfig.soundCullingDistance;

        return distanceSquared <= maxDistanceSquared;
    }

    /**
     * Checks if a BetterHUD packet should be sent to a player based on distance.
     */
    public static boolean shouldSendBetterHud(Player player, Location hudSourceLocation) {
        if (!BTCCoreConfig.betterHudCullingEnabled) {
            return true;
        }

        if (player.getWorld() != hudSourceLocation.getWorld()) {
            return false;
        }

        double distanceSquared = player.getLocation().distanceSquared(hudSourceLocation);
        int maxDistanceSquared = BTCCoreConfig.betterHudCullingDistance * BTCCoreConfig.betterHudCullingDistance;

        return distanceSquared <= maxDistanceSquared;
    }

    /**
     * Checks if a light update should be processed this tick.
     */
    public static boolean shouldProcessLightUpdate() {
        if (!BTCCoreConfig.lightThrottleEnabled) {
            return true;
        }

        if (lightUpdatesThisTick >= BTCCoreConfig.lightThrottleMaxPerTick) {
            return false;
        }

        lightUpdatesThisTick++;
        return true;
    }

    /**
     * Checks if a redstone update should be processed for a chunk.
     */
    public static boolean shouldProcessRedstoneUpdate(long chunkKey) {
        if (!BTCCoreConfig.redstoneThrottleEnabled) {
            return true;
        }

        int updates = chunkRedstoneUpdates.getOrDefault(chunkKey, 0);
        if (updates >= BTCCoreConfig.redstoneThrottleMaxPerChunk) {
            return false;
        }

        chunkRedstoneUpdates.put(chunkKey, updates + 1);
        return true;
    }

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
        if (!BTCCoreConfig.lazyChunkTicketsEnabled) {
            return false;
        }

        Long registeredTick = lazyChunkTickets.get(chunkKey);
        if (registeredTick == null) {
            return false;
        }

        if (currentTick - registeredTick > BTCCoreConfig.lazyChunkTicketsRetentionTicks) {
            lazyChunkTickets.remove(chunkKey);
            return false;
        }

        return true;
    }

    /**
     * Gets the current server tick.
     */
    public static long getCurrentTick() {
        return currentTick;
    }
}

