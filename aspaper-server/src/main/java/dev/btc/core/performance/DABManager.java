package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * BTCCore: Dynamic Activation of Brain (DAB) Manager.
 * Dynamically enables/disables entity AI based on proximity to players.
 * Unlike the static on-spawn check, this runs periodically and can re-enable AI
 * when a player approaches.
 *
 * Folia-safe: setAI() calls are scheduled on each entity's region thread
 * via the RegionScheduler, not called directly from the global tick.
 *
 * Wired via:
 * - Periodic task in BTCCoreListener (runs every 20 ticks on global region scheduler)
 * - EntityRemoveEvent in BTCCoreListener for cleanup
 */
public class DABManager {
    private static final ConcurrentMap<Integer, Boolean> entityAiState = new ConcurrentHashMap<>();

    /** UUIDs of entities that must always tick their AI, exempt from DAB. */
    private static final Set<UUID> alwaysTickEntities = new CopyOnWriteArraySet<>();

    private static Plugin getPlugin() {
        return Bukkit.getPluginManager().getPlugin("ASPaper");
    }

    /**
     * Marks an entity to always tick its AI, exempting it from DAB.
     * The entity will never have its AI disabled by the DAB system.
     *
     * @param entity The entity to exempt.
     */
    public static void setEntityAlwaysTick(Entity entity) {
        if (entity != null) {
            alwaysTickEntities.add(entity.getUniqueId());
        }
    }

    /**
     * Checks if an entity is exempt from DAB.
     *
     * @param entity The entity to check.
     * @return {@code true} if the entity always ticks its AI.
     */
    public static boolean isEntityAlwaysTick(Entity entity) {
        return entity != null && alwaysTickEntities.contains(entity.getUniqueId());
    }

    /**
     * Removes the always-tick exemption for an entity.
     *
     * @param entityUniqueId The UUID of the entity to un-exempt.
     */
    public static void removeAlwaysTick(UUID entityUniqueId) {
        alwaysTickEntities.remove(entityUniqueId);
    }

    /**
     * Runs the DAB check for all living entities in all worlds.
     * Called periodically from BTCCoreListener on the global region scheduler.
     * The actual setAI() calls are dispatched to each entity's own region thread.
     */
    public static void tick() {
        if (!BTCCoreConfig.dearEnabled) return;
        Plugin plugin = getPlugin();
        if (plugin == null) return;

        for (var world : Bukkit.getWorlds()) {
            for (var entity : world.getLivingEntities()) {
                if (entity instanceof Player) continue;
                if (!(entity instanceof LivingEntity living)) continue;
                if (living.getEntityId() == -1) continue;

                // Skip entities exempted from DAB (bosses, always-active mobs)
                if (alwaysTickEntities.contains(living.getUniqueId())) {
                    continue;
                }

                int entityId = living.getEntityId();
                boolean nearPlayer = false;

                for (Player player : world.getPlayers()) {
                    try {
                        if (player.getLocation().distanceSquared(living.getLocation()) <= BTCCoreConfig.dearStartDistanceSquared) {
                            nearPlayer = true;
                            break;
                        }
                    } catch (IllegalArgumentException e) {
                        // Different worlds — skip
                        break;
                    }
                }

                boolean currentAi = entityAiState.getOrDefault(entityId, living.hasAI());

                if (!nearPlayer && currentAi) {
                    // Schedule setAI(false) on the entity's region thread
                    Bukkit.getRegionScheduler().run(plugin, living.getLocation(), task -> {
                        living.setAI(false);
                    });
                    entityAiState.put(entityId, false);
                } else if (nearPlayer && !currentAi) {
                    // Schedule setAI(true) on the entity's region thread
                    Bukkit.getRegionScheduler().run(plugin, living.getLocation(), task -> {
                        living.setAI(true);
                    });
                    entityAiState.put(entityId, true);
                }
            }
        }
    }

    /**
     * Removes tracking for an entity that has been removed.
     */
    public static void onEntityRemove(int entityId) {
        entityAiState.remove(entityId);
    }

    /**
     * Removes tracking and exemption for an entity that has been removed.
     */
    public static void onEntityRemove(int entityId, UUID uniqueId) {
        entityAiState.remove(entityId);
        alwaysTickEntities.remove(uniqueId);
    }

    /**
     * Clears all tracking data and exemptions.
     */
    public static void clearAll() {
        entityAiState.clear();
        alwaysTickEntities.clear();
    }
}
