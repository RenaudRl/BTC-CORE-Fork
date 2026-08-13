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

    boolean isRedstoneCompilerEnabledFor(String worldName);

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

    // ==================== REDSTONE EMISSION ====================

    /**
     * Makes the block at {@code location} emit an analog redstone level, or clears the emission when
     * {@code power <= 0}. This lets a custom block — such as a plugin machine — drive a comparator or
     * a redstone wire, which a plain Bukkit block cannot do.
     *
     * <p>The change refreshes neighbouring redstone immediately, so it must be called on the region
     * that owns the block. Emissions are held in memory and are not persisted across a restart; a
     * plugin re-applies them when it reloads its own blocks.
     *
     * @param location the block that should emit
     * @param power    the analog level, 0–15 (values {@code <= 0} clear the emission, values above 15
     *                 are clamped)
     */
    void setEmittedRedstonePower(Location location, int power);

    // ==================== DROPS ====================

    /**
     * Takes over what a block drops, whatever destroys it.
     *
     * <p>This is not an event: the provider is consulted where the loot table would be rolled, so it
     * also answers for the paths no Bukkit event reports — explosions, pistons, fire, {@code /loot},
     * a falling block landing badly. A plugin that only listens to {@code BlockBreakEvent} leaks
     * vanilla items through every one of those.
     *
     * <p>One provider per material; registering again replaces the previous one. The registration
     * stops applying as soon as {@code owner} is disabled.
     *
     * @param owner    the plugin the registration belongs to
     * @param block    the block material to answer for
     * @param provider the provider, which may decline a given roll by returning {@code null}
     */
    void registerBlockDrops(org.bukkit.plugin.Plugin owner, org.bukkit.Material block,
                            dev.btc.core.api.drop.DropProvider provider);

    /**
     * Takes over what an entity type drops on death, shearing or milking.
     *
     * @param owner    the plugin the registration belongs to
     * @param type     the entity type to answer for
     * @param provider the provider, which may decline a given roll by returning {@code null}
     */
    void registerEntityDrops(org.bukkit.plugin.Plugin owner, org.bukkit.entity.EntityType type,
                             dev.btc.core.api.drop.DropProvider provider);

    /**
     * Takes over one exact loot table, by key.
     *
     * <p>The most precise of the three, and the only way to reach a table that belongs to neither a
     * block nor an entity: structure chests, fishing, villager gifts, archaeology. It also wins over
     * a block or entity registration covering the same roll.
     *
     * @param owner     the plugin the registration belongs to
     * @param lootTable the loot table key, for instance {@code minecraft:chests/simple_dungeon}
     * @param provider  the provider, which may decline a given roll by returning {@code null}
     */
    void registerLootTableDrops(org.bukkit.plugin.Plugin owner, org.bukkit.NamespacedKey lootTable,
                                dev.btc.core.api.drop.DropProvider provider);

    /**
     * Rewrites the drops of every roll in the game, after providers and vanilla.
     *
     * <p>Meant for a server that swaps each vanilla item for its own catalogue equivalent. Be aware
     * of the cost: a transformer has to be shown the vanilla result, so registering one forces the
     * vanilla table to be rolled and copied on every drop.
     *
     * @param owner       the plugin the registration belongs to
     * @param transformer the transformer
     */
    void registerDropTransformer(org.bukkit.plugin.Plugin owner,
                                 dev.btc.core.api.drop.DropTransformer transformer);

    /**
     * Drops every drop registration a plugin made.
     *
     * <p>Optional — a disabled plugin's registrations are ignored on their own — but useful to
     * rebuild a set of providers on a config reload.
     *
     * @param owner the plugin whose registrations should go
     */
    void unregisterDrops(org.bukkit.plugin.Plugin owner);

    /**
     * Whether anything is registered against the drop API.
     *
     * @return {@code true} when at least one provider or transformer is registered
     */
    boolean hasDropOverrides();

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
