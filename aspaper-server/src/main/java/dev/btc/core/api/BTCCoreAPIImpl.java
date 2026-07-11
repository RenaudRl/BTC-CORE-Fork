package dev.btc.core.api;

import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.world.BlockValueCache;
import dev.btc.core.performance.DABManager;
import dev.btc.core.performance.PerformanceManager;
import dev.btc.core.qol.*;
import dev.btc.core.security.CombatLogManager;
import dev.btc.core.security.ExploitLogger;
import dev.btc.core.entity.CrossWorldEntityTransfer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * BTCCore API implementation.
 * Bridges the public interface to the internal server-side managers.
 * Registered via META-INF/services for Services.service() lookup.
 */
public final class BTCCoreAPIImpl implements BTCCoreAPI {

    @Override
    public boolean isZeroFeatureEnabledFor(String feature, String worldName) {
        return BTCCoreConfig.isZeroFeatureEnabledFor(feature, worldName);
    }

    @Override
    public boolean isStaticGraphEnabledFor(String worldName) {
        return BTCCoreConfig.isStaticGraphEnabledFor(worldName);
    }

    @Override
    public double getChunkValue(String worldName, int chunkX, int chunkZ) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return -1.0;
        return BlockValueCache.getChunkValue(((CraftWorld) world).getHandle(), chunkX, chunkZ);
    }

    @Override
    public void setChunkValue(String worldName, int chunkX, int chunkZ, double value) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        BlockValueCache.setChunkValue(((CraftWorld) world).getHandle(), chunkX, chunkZ, value);
    }

    @Override
    public void addToChunkValue(String worldName, int chunkX, int chunkZ, double delta) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        BlockValueCache.addToChunkValue(((CraftWorld) world).getHandle(), chunkX, chunkZ, delta);
    }

    @Override
    public void invalidateChunk(String worldName, int chunkX, int chunkZ) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        BlockValueCache.invalidateChunk(((CraftWorld) world).getHandle(), chunkX, chunkZ);
    }

    @Override
    public boolean isInCombat(Player player) {
        return CombatLogManager.isInCombat(player);
    }

    @Override
    public int getRemainingCombatTime(Player player) {
        return CombatLogManager.getRemainingCombatTime(player);
    }

    @Override
    public void tagCombat(Player player) {
        CombatLogManager.tagPlayer(player);
    }

    @Override
    public void untagCombat(Player player) {
        CombatLogManager.untagPlayer(player);
    }

    @Override
    public int getVanishLevel(Player player) {
        return VanishManager.getVanishLevel(player);
    }

    @Override
    public boolean isVanished(Player player) {
        return VanishManager.isVanished(player);
    }

    @Override
    public boolean shouldCalculateCollision(Entity entity, int nearbyEntityCount) {
        return PerformanceManager.shouldCalculateCollision(entity, nearbyEntityCount);
    }

    @Override
    public boolean shouldSendParticle(Player player, Location particleLocation) {
        return PerformanceManager.shouldSendParticle(player, particleLocation);
    }

    @Override
    public boolean shouldSendSound(Player player, Location soundLocation) {
        return PerformanceManager.shouldSendSound(player, soundLocation);
    }

    @Override
    public boolean shouldSendBetterHud(Player player, Location hudSourceLocation) {
        return PerformanceManager.shouldSendBetterHud(player, hudSourceLocation);
    }

    @Override
    public List<Entity> transferOwnedEntities(Player player, Location destination) {
        return CrossWorldEntityTransfer.transferOwnedEntities(player, destination);
    }

    @Override
    public List<Entity> findOwnedEntities(Player player, double radius) {
        return CrossWorldEntityTransfer.findOwnedEntities(player, radius);
    }

    @Override
    public boolean isOwnedBy(Entity entity, UUID ownerUUID) {
        return CrossWorldEntityTransfer.isOwnedBy(entity, ownerUUID);
    }

    @Override
    public int getPlayerCPS(Player player) {
        return ExploitLogger.trackClick(player);
    }

    @Override
    public void setEntityAlwaysTick(Entity entity) {
        DABManager.setEntityAlwaysTick(entity);
    }

    @Override
    public boolean isEntityAlwaysTick(Entity entity) {
        return DABManager.isEntityAlwaysTick(entity);
    }

    @Override
    public double getCurrentMspt() {
        return PerformanceManager.getCurrentMspt();
    }

    @Override
    public boolean isMaintenanceMode() {
        return MaintenanceModeManager.isEnabled();
    }

    @Override
    public int getQueuePosition(UUID uuid) {
        return JoinQueueManager.getQueuePosition(uuid);
    }

    @Override
    public int getQueueSize() {
        return JoinQueueManager.getQueueSize();
    }
}
