package dev.btc.core.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * BTCCore Public API interface.
 * Provides access to BTC Core features for external plugins.
 *
 * Usage:
 * <pre>
 *     BTCCoreAPI api = BTCCoreAPI.instance();
 *
 *     // Check if a feature is enabled
 *     if (api.isZeroFeatureEnabledFor("recipes", player.getWorld().getName())) {
 *         // Vanilla recipes are disabled in this world
 *     }
 *
 *     // Check combat status
 *     if (api.isInCombat(player)) {
 *         // Player is in combat
 *     }
 * </pre>
 */
public interface BTCCoreAPI {

    // ==================== ZERO FEATURES ====================

    boolean isZeroFeatureEnabledFor(String feature, String worldName);

    boolean isStaticGraphEnabledFor(String worldName);

    // ==================== BLOCK VALUE CACHE ====================

    /**
     * Gets the cached block value for a chunk.
     * The level parameter accepts the Bukkit World object for convenience.
     *
     * @param worldName The world name
     * @param chunkX    The chunk X coordinate
     * @param chunkZ    The chunk Z coordinate
     * @return The cached value, or -1 if not cached
     */
    double getChunkValue(String worldName, int chunkX, int chunkZ);

    void setChunkValue(String worldName, int chunkX, int chunkZ, double value);

    void addToChunkValue(String worldName, int chunkX, int chunkZ, double delta);

    void invalidateChunk(String worldName, int chunkX, int chunkZ);

    // ==================== COMBAT ====================

    boolean isInCombat(Player player);

    int getRemainingCombatTime(Player player);

    void tagCombat(Player player);

    void untagCombat(Player player);

    // ==================== VANISH ====================

    int getVanishLevel(Player player);

    boolean isVanished(Player player);

    // ==================== PERFORMANCE ====================

    boolean shouldCalculateCollision(Entity entity, int nearbyEntityCount);

    boolean shouldSendParticle(Player player, Location particleLocation);

    boolean shouldSendSound(Player player, Location soundLocation);

    /**
     * Distance-culling hint for BetterHud elements.
     *
     * @param player           the recipient player
     * @param hudSourceLocation the world location the HUD element is sourced from
     * @return {@code true} if the HUD element should be sent to the player.
     */
    boolean shouldSendBetterHud(Player player, Location hudSourceLocation);

    // ==================== CROSS-WORLD ENTITY TRANSFER ====================

    List<Entity> transferOwnedEntities(Player player, Location destination);

    List<Entity> findOwnedEntities(Player player, double radius);

    boolean isOwnedBy(Entity entity, UUID ownerUUID);

    // ==================== CPS ====================

    int getPlayerCPS(Player player);

    // ==================== DAB EXEMPTION ====================

    /**
     * Marks an entity to always tick its AI, exempting it from DAB (Dynamic Activation of Brain).
     * Useful for boss mobs or entities that must always have active AI regardless of player proximity.
     *
     * @param entity The entity to exempt from DAB.
     */
    void setEntityAlwaysTick(Entity entity);

    /**
     * Checks if an entity is exempt from DAB.
     *
     * @param entity The entity to check.
     * @return {@code true} if the entity always ticks its AI.
     */
    boolean isEntityAlwaysTick(Entity entity);

    // ==================== MSPT ====================

    /**
     * Gets the current server MSPT (milliseconds per tick).
     * Plugins can use this to throttle async tasks under load.
     *
     * @return The average milliseconds per tick over the last 5 seconds.
     */
    double getCurrentMspt();

    // ==================== MAINTENANCE ====================

    boolean isMaintenanceMode();

    // ==================== JOIN QUEUE ====================

    int getQueuePosition(UUID uuid);

    int getQueueSize();

    /**
     * Gets the instance of the BTCCore API.
     *
     * @return the instance of the BTCCore API
     */
    static BTCCoreAPI instance() {
        return Holder.INSTANCE;
    }

    class Holder {
        private static final BTCCoreAPI INSTANCE = net.kyori.adventure.util.Services.service(BTCCoreAPI.class)
            .orElseThrow(() -> new IllegalStateException("BTCCoreAPI implementation not found — server must be running BTC-CORE"));
    }
}
