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
    public boolean isRedstoneCompilerEnabledFor(String worldName) {
        return BTCCoreConfig.isRedstoneCompilerEnabledFor(worldName);
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
    public int getMsptThreshold() {
        return BTCCoreConfig.msptThreshold;
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

    @Override
    public void registerBlockDrops(org.bukkit.plugin.Plugin owner, org.bukkit.Material block,
                                   dev.btc.core.api.drop.DropProvider provider) {
        dev.btc.core.drop.DropRegistry.registerBlock(owner, block, provider);
    }

    @Override
    public void registerEntityDrops(org.bukkit.plugin.Plugin owner, org.bukkit.entity.EntityType type,
                                    dev.btc.core.api.drop.DropProvider provider) {
        dev.btc.core.drop.DropRegistry.registerEntity(owner, type, provider);
    }

    @Override
    public void registerLootTableDrops(org.bukkit.plugin.Plugin owner, org.bukkit.NamespacedKey lootTable,
                                       dev.btc.core.api.drop.DropProvider provider) {
        dev.btc.core.drop.DropRegistry.registerTable(owner, lootTable, provider);
    }

    @Override
    public void registerDropTransformer(org.bukkit.plugin.Plugin owner,
                                        dev.btc.core.api.drop.DropTransformer transformer) {
        dev.btc.core.drop.DropRegistry.registerTransformer(owner, transformer);
    }

    @Override
    public void unregisterDrops(org.bukkit.plugin.Plugin owner) {
        dev.btc.core.drop.DropRegistry.unregisterAll(owner);
    }

    @Override
    public boolean hasDropOverrides() {
        return dev.btc.core.drop.DropRegistry.hasOverrides();
    }

    @Override
    public java.util.Optional<dev.btc.core.api.island.IslandKey> islandForWorld(String worldName) {
        return dev.btc.core.island.IslandCatchUpRegistry.ownershipSource()
            .flatMap(source -> source.resolve(worldName));
    }

    @Override
    public boolean isIslandChunkOwned(dev.btc.core.api.island.IslandKey island, int chunkX, int chunkZ) {
        return dev.btc.core.island.IslandCatchUpRegistry.ownershipSource()
            .map(source -> source.ownsChunk(island, chunkX, chunkZ))
            .orElse(false);
    }

    @Override
    public void registerCatchUpHandler(org.bukkit.plugin.Plugin owner, String systemKey, int schemaVersion,
                                       dev.btc.core.api.island.CatchUpHandler handler) {
        dev.btc.core.island.IslandCatchUpRegistry.register(owner, systemKey, schemaVersion, handler);
    }

    @Override
    public void unregisterCatchUpHandlers(org.bukkit.plugin.Plugin owner) {
        dev.btc.core.island.IslandCatchUpRegistry.unregisterAll(owner);
    }

    @Override
    public void bindIslandOwnershipSource(dev.btc.core.api.island.IslandOwnershipSource source) {
        dev.btc.core.island.IslandCatchUpRegistry.bindOwnershipSource(source);
    }

    @Override
    public void bindCatchUpJournal(dev.btc.core.api.island.CatchUpJournal journal) {
        dev.btc.core.island.IslandCatchUpRegistry.bindJournal(journal);
    }

    @Override
    public void setIslandBackendId(String backendId) {
        dev.btc.core.island.IslandCatchUpRegistry.setBackendId(backendId);
    }

    @Override
    public boolean activateIsland(World world) {
        java.util.Objects.requireNonNull(world, "world");
        return dev.btc.core.island.IslandCatchUpRegistry.activate(world)
            .map(activation -> {
                // The activation event is fired here rather than inside the registry so that the
                // registry stays free of Bukkit's event bus, and so a refused activation cannot
                // announce itself as one that happened.
                new dev.btc.core.api.island.IslandActivationEvent(
                    activation.island(), activation.lease(), world,
                    activation.from(), activation.to(), activation.clamped()
                ).callEvent();
                return true;
            })
            .orElse(false);
    }

    @Override
    public java.util.concurrent.CompletionStage<Boolean> activateIslandAsync(World world) {
        java.util.Objects.requireNonNull(world, "world");
        return dev.btc.core.island.IslandCatchUpRegistry.activateAsync(world)
            .thenCompose(activation -> {
                if (activation.isEmpty()) {
                    return java.util.concurrent.CompletableFuture.completedFuture(false);
                }

                // Bukkit's event bus is not an async persistence callback. Publish the accepted
                // activation on the global region context after the canonical commit completed.
                org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("BTCCore");
                if (plugin == null) {
                    return java.util.concurrent.CompletableFuture.completedFuture(true);
                }
                java.util.concurrent.CompletableFuture<Boolean> published =
                    new java.util.concurrent.CompletableFuture<>();
                try {
                    dev.btc.core.island.IslandCatchUpRegistry.Activation accepted = activation.get();
                    Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> {
                        try {
                            new dev.btc.core.api.island.IslandActivationEvent(
                                accepted.island(), accepted.lease(), world,
                                accepted.from(), accepted.to(), accepted.clamped()
                            ).callEvent();
                            published.complete(true);
                        } catch (Throwable throwable) {
                            published.completeExceptionally(throwable);
                        }
                    });
                } catch (Throwable throwable) {
                    published.completeExceptionally(throwable);
                }
                return published;
            });
    }

    @Override
    public java.util.concurrent.CompletionStage<Boolean> resumeIslandChunkAsync(World world,
                                                                                 int chunkX,
                                                                                 int chunkZ) {
        java.util.Objects.requireNonNull(world, "world");
        return dev.btc.core.island.IslandCatchUpRegistry.resumeChunkAsync(world, chunkX, chunkZ);
    }

    @Override
    public boolean applyRandomTick(org.bukkit.block.Block block) {
        java.util.Objects.requireNonNull(block, "block");

        // Region ownership is checked before anything is read: on a regionised server, reading block
        // state from the wrong thread is the kind of fault that shows up much later as corruption
        // rather than as an exception here. Bukkit's own check is used rather than an internal one,
        // so this stays correct whether the server is regionised or not.
        if (!Bukkit.isOwnedByCurrentRegion(block)) {
            throw new IllegalStateException(
                "applyRandomTick must run on the region owning " + block.getWorld().getName()
                    + " at " + block.getX() + ',' + block.getY() + ',' + block.getZ());
        }

        net.minecraft.server.level.ServerLevel level = ((CraftWorld) block.getWorld()).getHandle();
        net.minecraft.core.BlockPos pos =
            new net.minecraft.core.BlockPos(block.getX(), block.getY(), block.getZ());

        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        if (!state.isRandomlyTicking()) {
            return false;
        }

        // Vanilla owns the rule. We only decide that a tick happens, never what it does.
        state.randomTick(level, pos, level.getRandom());
        return true;
    }

    @Override
    public java.util.List<org.bukkit.block.Block> collectRandomlyTickingBlocks(org.bukkit.Chunk chunk, int limit) {
        java.util.Objects.requireNonNull(chunk, "chunk");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (!Bukkit.isOwnedByCurrentRegion(chunk.getWorld(), chunk.getX(), chunk.getZ())) {
            throw new IllegalStateException(
                "collectRandomlyTickingBlocks must run on the region owning chunk "
                    + chunk.getX() + ',' + chunk.getZ() + " of " + chunk.getWorld().getName());
        }

        net.minecraft.server.level.ServerLevel level = ((CraftWorld) chunk.getWorld()).getHandle();
        net.minecraft.world.level.chunk.LevelChunk handle =
            level.getChunk(chunk.getX(), chunk.getZ());

        java.util.List<org.bukkit.block.Block> found = new java.util.ArrayList<>();
        net.minecraft.world.level.chunk.LevelChunkSection[] sections = handle.getSections();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;

        for (int index = 0; index < sections.length; index++) {
            net.minecraft.world.level.chunk.LevelChunkSection section = sections[index];
            // An air-only section cannot hold a randomly ticking block, and on a skyblock island
            // that is nearly every section. Skipping them is what keeps this scan affordable.
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int baseY = (handle.getMinSectionY() + index) << 4;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!section.getBlockState(x, y, z).isRandomlyTicking()) {
                            continue;
                        }
                        found.add(chunk.getWorld().getBlockAt(baseX + x, baseY + y, baseZ + z));
                        if (found.size() >= limit) {
                            return found;
                        }
                    }
                }
            }
        }
        return found;
    }

    @Override
    public void setEmittedRedstonePower(Location location, int power) {
        World world = location.getWorld();
        if (world == null) return;
        dev.btc.core.redstone.RedstoneEmitterManager.set(
            world, location.getBlockX(), location.getBlockY(), location.getBlockZ(), power);
    }
}
