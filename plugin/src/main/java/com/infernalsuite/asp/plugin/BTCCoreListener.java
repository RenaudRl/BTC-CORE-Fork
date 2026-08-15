package com.infernalsuite.asp.plugin;

import com.infernalsuite.asp.api.events.LoadSlimeWorldEvent;
import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.performance.*;
import dev.btc.core.qol.*;
import dev.btc.core.security.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/**
 * BTCCore: Main event listener.
 * Integrates all BTC Core systems: anticheat, performance, QoL, Zero Features.
 * All schedulers use Folia's GlobalRegionScheduler and RegionScheduler.
 */
public class BTCCoreListener implements Listener {

    private volatile long currentTick = 0;

    public BTCCoreListener() {
        // Start the global tick task (Folia-safe: global region scheduler)
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(SWPlugin.getInstance(), task -> onTick(), 1L, 1L);

        // DAB dynamic check — runs every 20 ticks (1 second)
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(SWPlugin.getInstance(), task -> DABManager.tick(), 20L, 20L);
    }

    // ==================== TICK ====================

    private void onTick() {
        currentTick++;
        PerformanceManager.onTickStart(currentTick);
        CombatLogManager.onTickStart(currentTick);
        PlayerDataBackupManager.onTick(currentTick);

        // Flush batched inventory updates at end of tick
        if (BTCCoreConfig.batchedInventoryUpdates) {
            BatchedInventoryUpdates.flushAll();
        }

        // Flush async entity tracker dispatches
        if (BTCCoreConfig.asyncEntityTrackerEnabled) {
            dev.btc.core.async.AsyncEntityTracker.flushDispatch();
        }

        // Flush async pathfinding results
        if (BTCCoreConfig.asyncPathfindingEnabled) {
            dev.btc.core.async.AsyncPathfindingEngine.flushResults();
        }
    }

    // ==================== PLAYER JOIN / QUIT ====================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();

        // Maintenance mode check
        if (MaintenanceModeManager.isEnabled() && !MaintenanceModeManager.canBypass(player)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, MaintenanceModeManager.getKickMessage());
            return;
        }

        // Join queue check
        if (JoinQueueManager.isEnabled()) {
            if (!JoinQueueManager.addToQueue(player.getUniqueId())) {
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    net.kyori.adventure.text.Component.text("Server is full. Queue is full."));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoin(PlayerJoinEvent event) {
        VanishManager.onPlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        VanishManager.onPlayerQuit(player);
        TeleportWarmupManager.cancelWarmup(player);
        PlayerSimulationCache.clear(player.getUniqueId());
        ExploitLogger.removePlayer(player);
        CombatLogManager.handleLogout(player);
    }

    // ==================== MOVEMENT ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null) return;

        // Teleport warmup move check
        TeleportWarmupManager.checkPlayerMove(player, to);

        // Player simulation cache for anticheat
        if (BTCCoreConfig.sentinelEnabled) {
            PlayerSimulationCache.updateCache(
                player.getUniqueId(),
                to.getX(), to.getY(), to.getZ(),
                ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle().getBoundingBox()
            );
        }

        // Velocity validation (async, NMS)
        if (BTCCoreConfig.sentinelEnabled && AnticheatConfig.velocityCheckEnabled) {
            Location from = event.getFrom();
            AsyncPacketValidator.validateVelocity(
                ((CraftPlayer) player).getHandle(),
                from.getX(), from.getY(), from.getZ(),
                to.getX(), to.getY(), to.getZ()
            );
        }
    }

    // ==================== TELEPORT — CHUNK PREFETCH ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        // Prefetch chunks at destination before teleport completes
        ChunkPrefetch.prefetch(event.getPlayer(), event.getTo());
    }

    // ==================== INTERACTION / ANTICHEAT ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Entity target)) return;
        Player player = event.getPlayer();

        if (BTCCoreConfig.sentinelEnabled && AnticheatConfig.reachCheckEnabled) {
            Location eyeLoc = player.getEyeLocation();
            AsyncPacketValidator.validateReach(
                ((CraftPlayer) player).getHandle(),
                ((CraftEntity) target).getHandle(),
                eyeLoc.getX(), eyeLoc.getY(), eyeLoc.getZ(),
                player.getPing()
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction().isLeftClick()) return;
        // CPS check
        if (BTCCoreConfig.cpsLimitEnabled) {
            if (!ExploitLogger.checkClickAndBlock(event.getPlayer())) {
                event.setCancelled(true);
            }
        }
    }

    // ==================== COMBAT ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        if (event.getDamager() instanceof Player attacker) {
            CombatLogManager.tagCombat(attacker, victim);
        } else {
            CombatLogManager.tagPlayer(victim);
        }
    }

    // ==================== PROJECTILE POOLING ====================

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!ProjectilePool.onLaunch(projectile)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent event) {
        ProjectilePool.onRemove(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Projectile projectile) {
            ProjectilePool.onRemove(projectile);
        }
        DABManager.onEntityRemove(entity.getEntityId(), entity.getUniqueId());
    }

    // ==================== WORLD LOAD — PER-WORLD DISTANCES ====================

    // Slime worlds go through this too: AdvancedSlimePaper#loadWorld fires WorldLoadEvent once the
    // Bukkit world exists.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        PerWorldDistanceManager.apply(event.getWorld());
    }

    // ==================== WORLD UNLOAD — CLEANUP ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        String worldName = event.getWorld().getName();
        ProjectilePool.clearWorld(worldName);
        dev.btc.core.world.BlockValueCache.clearWorld(
            ((org.bukkit.craftbukkit.CraftWorld) event.getWorld()).getHandle()
        );
    }

    // ==================== ZERO FEATURES ====================

    // --- Recipes / Advancements ---
    // NOTE: 'zero-features.recipes' et 'zero-features.advancements' vident le contenu vanilla au
    // chargement (RecipeManager#prepare / ServerAdvancementManager#apply, apply-btccore-patches.py).
    // Le systeme reste entierement fonctionnel : aucun listener ne doit le bloquer ici.

    // --- Stats ---
    @EventHandler(priority = EventPriority.LOWEST)
    public void onStatisticIncrement(PlayerStatisticIncrementEvent event) {
        if (!BTCCoreConfig.isZeroFeatureEnabledFor("stats", event.getPlayer().getWorld().getName())) return;
        event.setCancelled(true);
    }

    // --- Collisions ---
    // NOTE: Collision "zero-feature" est appliqué via les hooks NMS (Collision Throttle,
    // apply-btccore-patches.py). Aucun EntityCollisionEvent Bukkit n'existe pour un listener.

    // --- Cramming ---
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamageCramming(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.CRAMMING) return;
        if (!BTCCoreConfig.isZeroFeatureEnabledFor("cramming", event.getEntity().getWorld().getName())) return;
        event.setCancelled(true);
    }

    // Block updates (zero-features.block-updates) are short-circuited in NMS, inside
    // NeighborUpdater.executeUpdate and FlowingFluid.spread. Listening to BlockPhysicsEvent here
    // would switch ServerLevel.hasPhysicsEvent on server-wide and make every world pay for the
    // event, including the ones the feature does not even cover.

    // --- Sleep Tick ---
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        if (!BTCCoreConfig.isZeroFeatureEnabledFor("sleep_tick", event.getPlayer().getWorld().getName())) return;
        event.setCancelled(true);
    }

    // ==================== SLIME WORLD ====================

    @EventHandler(priority = EventPriority.LOW)
    public void onLoadSlimeWorld(LoadSlimeWorldEvent event) {
        try {
            org.bukkit.World world = event.getSlimeWorld().getBukkitWorld();
            net.minecraft.server.level.ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
            dev.btc.core.config.SlimeWorldConfig.getInstance().applyRules(
                world.getName(),
                level.getGameRules()
            );
        } catch (Exception e) {
            Bukkit.getLogger().warning("[BTCCore] Failed to apply GameRules for SlimeWorld " + event.getSlimeWorld().getBukkitWorld().getName() + ": " + e.getMessage());
        }
    }

    // ==================== ENTITY OPTIMIZATIONS (DAB on spawn) ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!BTCCoreConfig.dearEnabled) return;
        Entity entity = event.getEntity();
        boolean nearPlayer = false;
        for (Player player : entity.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(entity.getLocation()) <= BTCCoreConfig.dearStartDistanceSquared) {
                nearPlayer = true;
                break;
            }
        }
        if (!nearPlayer && entity instanceof org.bukkit.entity.LivingEntity living) {
            living.setAI(false);
        }
    }
}
