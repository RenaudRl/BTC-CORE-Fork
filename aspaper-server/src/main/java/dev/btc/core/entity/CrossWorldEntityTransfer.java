package dev.btc.core.entity;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BTCCore: Cross-World Entity Transfer System.
 * Allows entities (pets, mounts) to follow players between worlds.
 */
public final class CrossWorldEntityTransfer {

    private CrossWorldEntityTransfer() {}

    /**
     * Transfers all owned entities to follow a player to a new world.
     *
     * @param player The player being teleported
     * @param destination The destination location
     * @return List of entities that were transferred
     */
    public static List<Entity> transferOwnedEntities(Player player, Location destination) {
        List<Entity> transferred = new ArrayList<>();
        World sourceWorld = player.getWorld();
        World targetWorld = destination.getWorld();

        if (sourceWorld == targetWorld) {
            return transferred;
        }

        // Find all owned entities near the player
        List<Entity> ownedEntities = findOwnedEntities(player, 16.0);

        for (Entity entity : ownedEntities) {
            try {
                // Calculate relative offset from player
                Location entityLoc = entity.getLocation();
                double offsetX = entityLoc.getX() - player.getLocation().getX();
                double offsetZ = entityLoc.getZ() - player.getLocation().getZ();

                // Create destination for entity
                Location entityDest = destination.clone().add(offsetX, 0, offsetZ);
                entityDest.setY(destination.getY());

                // Teleport the entity
                if (entity.teleport(entityDest)) {
                    transferred.add(entity);
                }
            } catch (Exception e) {
                // Log but continue with other entities
                org.bukkit.Bukkit.getLogger().warning(
                        "[BTCCore] Failed to transfer entity " + entity.getType() + ": " + e.getMessage());
            }
        }

        return transferred;
    }

    /**
     * Finds all entities owned by a player within a radius.
     *
     * @param player The owner player
     * @param radius The search radius
     * @return List of owned entities
     */
    public static List<Entity> findOwnedEntities(Player player, double radius) {
        List<Entity> owned = new ArrayList<>();
        UUID playerUUID = player.getUniqueId();

        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (isOwnedBy(entity, playerUUID)) {
                owned.add(entity);
            }
        }

        // Also check if player is riding something
        Entity vehicle = player.getVehicle();
        if (vehicle != null && !owned.contains(vehicle)) {
            owned.add(vehicle);
        }

        // Check for leashed entities
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof org.bukkit.entity.LivingEntity living) {
                if (living.isLeashed() && living.getLeashHolder() != null 
                        && living.getLeashHolder().equals(player)) {
                    if (!owned.contains(entity)) {
                        owned.add(entity);
                    }
                }
            }
        }

        return owned;
    }

    /**
     * Checks if an entity is owned by a specific player.
     *
     * @param entity The entity to check
     * @param ownerUUID The owner's UUID
     * @return true if the entity is owned by the player
     */
    public static boolean isOwnedBy(Entity entity, UUID ownerUUID) {
        if (entity instanceof Tameable tameable) {
            if (tameable.isTamed() && tameable.getOwner() != null) {
                return tameable.getOwner().getUniqueId().equals(ownerUUID);
            }
        }

        // Check for other ownership types via PDC or metadata
        if (entity.hasMetadata("owner")) {
            for (org.bukkit.metadata.MetadataValue value : entity.getMetadata("owner")) {
                if (value.asString().equals(ownerUUID.toString())) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Prepares entities for cross-world transfer by dismounting passengers.
     *
     * @param player The player being transferred
     * @return List of entities that were passengers
     */
    public static List<Entity> prepareForTransfer(Player player) {
        List<Entity> passengers = new ArrayList<>();

        // Get all passengers recursively
        collectPassengers(player, passengers);

        // Eject all passengers
        player.eject();
        if (player.getVehicle() != null) {
            player.leaveVehicle();
        }

        return passengers;
    }

    private static void collectPassengers(Entity entity, List<Entity> list) {
        for (Entity passenger : entity.getPassengers()) {
            if (!list.contains(passenger)) {
                list.add(passenger);
                collectPassengers(passenger, list);
            }
        }
    }
}

