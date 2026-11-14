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

        // DAB is not scheduled: its loop reads world and entity state from the global thread, which
        // Folia forbids. Said once at startup, so dab.enabled=true is not mistaken for a working feature.
        // shortcut: no per-region rewrite yet; schedule it again only once DABManager.isActive() can be true.
        if (BTCCoreConfig.dearEnabled) {
            Bukkit.getLogger().warning("[BTCCore] dab.enabled is true but DAB is disabled until it is "
                + "rewritten per region (the old loop is not Folia-safe). No entity AI will be switched off.");
        }
    }

    // ==================== TICK ====================

    private void onTick() {
        currentTick++;
        PerformanceManager.onTickStart(currentTick);
        CombatLogManager.onTickStart(currentTick);
        PlayerDataBackupManager.onTick(currentTick);

        // Batched inventory updates are flushed at the end of each player's own tick, in
        // ServerPlayer#doTick, so they stay on that player's region thread. Sweeping every player
        // from the global scheduler here would touch connections owned by other regions.
    }

    // ==================== PLAYER JOIN / QUIT ====================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();

        // Maintenance mode check
        if (MaintenanceModeManager.isEnabled() && !MaintenanceModeManager.canBypass(player)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, MaintenanceModeManager.getKickMessage(player.getUniqueId()));
            return;
        }

        // Join queue check
        if (JoinQueueManager.isEnabled()) {
            if (!JoinQueueManager.addToQueue(player.getUniqueId())) {
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER, dev.btc.core.text.PlayerTexts.shared()
                    .render(player.getUniqueId(), dev.btc.core.text.PlayerText.LOGIN_QUEUE_FULL, java.util.Map.of()));
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
        try {
            VanishManager.onPlayerQuit(player);
            TeleportWarmupManager.cancelWarmup(player);
            PlayerSimulationCache.clear(player.getUniqueId());
            // Exemptions, declarations, violation levels, session origin and the engine's session state
            // are all keyed by this player. Until 2026-09-15 nothing called this: a player's state
            // outlived their session and would have judged their next login.
            dev.btc.core.integrity.IntegrityAPIImpl.forgetPlayer(player.getUniqueId());
            BatchedInventoryUpdates.remove(player.getUniqueId());
            ExploitLogger.removePlayer(player);
            CombatLogManager.handleLogout(player);
        } finally {
            // Whatever failed above: the engine's session is dropped (it would judge the next login), the
            // zones are left and the player is no longer followed.
            try {
                dev.btc.core.integrity.engine.SentinelEngine.forgetSession(player.getUniqueId());
            } finally {
                ZONES.quit(player);
            }
        }
    }

    // ==================== MOVEMENT ====================

    /**
     * A velocity the server is about to send this player. The integrity engine folds it into its
     * prediction so that knockback, a plugin's {@code setVelocity} or an attack's recoil is expected
     * rather than flagged — read from the fork, never declared (design D15). MONITOR: what is
     * recorded is the vector that actually leaves, after every other listener has had its say.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        // On the attack path the event carries the pre-hit vector and the knockback is on the
        // player: see SentVelocity.
        org.bukkit.util.Vector fromEvent = event.getVelocity();
        org.bukkit.util.Vector onPlayer = event.getPlayer().getVelocity();
        double[] sent = dev.btc.core.integrity.engine.SentVelocity.of(
            new double[] {fromEvent.getX(), fromEvent.getY(), fromEvent.getZ()},
            new double[] {onPlayer.getX(), onPlayer.getY(), onPlayer.getZ()});
        dev.btc.core.integrity.engine.SentinelEngine.serverVelocity(
            event.getPlayer().getUniqueId(), sent[0], sent[1], sent[2]);
    }

    /**
     * What the client said about its sprint. The server may stop a sprint on its own (an attack does)
     * without the client learning it; the integrity engine bounds movement by what the client plays,
     * so it keeps the announcement — even a cancelled one, which the client still plays.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerToggleSprint(org.bukkit.event.player.PlayerToggleSprintEvent event) {
        dev.btc.core.integrity.engine.SentinelEngine.clientSprint(
            event.getPlayer().getUniqueId(), event.isSprinting());
    }

    /**
     * The end of a client tick (task 16.7). The deterministic checks of the integrity engine call
     * "the same tick" what a client sent between two of these, not what one server tick received.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClientTickEnd(io.papermc.paper.event.packet.ClientTickEndEvent event) {
        try {
            dev.btc.core.integrity.engine.SentinelEngine.clientTickEnd(event.getPlayer().getUniqueId());
        } catch (RuntimeException failure) {
            reportEngineFailure("client tick end", failure);
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean ENGINE_FAILURE_REPORTED =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** An engine hook that throws must never reach the event bus; the first failure is logged, the rest are not. */
    private static void reportEngineFailure(String hook, RuntimeException failure) {
        if (ENGINE_FAILURE_REPORTED.compareAndSet(false, true)) {
            Bukkit.getLogger().log(java.util.logging.Level.WARNING,
                "[BTCCore] the integrity engine failed on " + hook + "; further failures of its hooks are not logged.", failure);
        }
    }

    /**
     * A change of held slot, for the slot-swap check (task 16.7). Paper fires it only when the slot
     * really changes; MONITOR and not ignoring cancelled: a refused change is still the client's.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        try {
            dev.btc.core.integrity.engine.SentinelEngine.heldSlotChanged(event.getPlayer().getUniqueId());
        } catch (RuntimeException failure) {
            reportEngineFailure("held slot change", failure);
        }
    }

    /**
     * A container was closed, by the player or by the server (task 16.7). The engine learns which
     * container ids this player has had open, and which the client itself closed; the event fires before
     * the server swaps the container.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        try {
            dev.btc.core.integrity.engine.SentinelEngine.containerClosed(event.getPlayer().getUniqueId(),
                event.getReason() == org.bukkit.event.inventory.InventoryCloseEvent.Reason.PLAYER);
        } catch (RuntimeException failure) {
            reportEngineFailure("container close", failure);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null) return;

        // Teleport warmup move check
        TeleportWarmupManager.checkPlayerMove(player, to);

        // Player simulation cache for anticheat
        if (AnticheatConfig.sentinelEnabled) {
            PlayerSimulationCache.updateCache(
                player.getUniqueId(),
                to.getX(), to.getY(), to.getZ(),
                ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle().getBoundingBox()
            );
        }

        // Velocity validation (async, NMS)
        if (AnticheatConfig.sentinelEnabled && AnticheatConfig.velocityCheckEnabled) {
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
        // Zones: a teleport within the world is told here, just before the player is moved; one to
        // another world when it has arrived (PlayerChangedWorldEvent), since it may be deferred.
        if (event.getTo().getWorld() == event.getFrom().getWorld()) ZONES.at(event.getPlayer(), event.getTo());
    }

    // ==================== ZONES (BTCCoreAPI#registerZone) ====================
    // A change of block is told by the move hook (CellChanges); the rest of what moves a player is
    // told here. Each call returns after one volatile read while no zone is registered.

    private static final dev.btc.core.zone.ZoneIndex ZONES = dev.btc.core.zone.ZoneIndex.shared();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        ZONES.at(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPostRespawn(com.destroystokyo.paper.event.player.PlayerPostRespawnEvent event) {
        ZONES.at(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoinZones(PlayerJoinEvent event) {
        ZONES.at(event.getPlayer(), event.getPlayer().getLocation());
    }

    // ==================== INTERACTION / ANTICHEAT ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Entity target)) return;
        Player player = event.getPlayer();

        if (AnticheatConfig.sentinelEnabled && AnticheatConfig.reachCheckEnabled) {
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

    // A hit is not a removal (a piercing arrow, a bouncing trident): the slot is freed only here.
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
        if (!event.isCancelled()) ZONES.worldUnloaded(event.getWorld().getUID());
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
            dev.btc.core.config.SlimeWorldConfig.getInstance().applyRules(level);
        } catch (Exception e) {
            Bukkit.getLogger().warning("[BTCCore] Failed to apply GameRules for SlimeWorld " + event.getSlimeWorld().getBukkitWorld().getName() + ": " + e.getMessage());
        }
    }

    // ==================== ENTITY OPTIMIZATIONS (DAB on spawn) ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        // Off with the periodic loop: an AI switched off here would never be switched back on.
        if (!DABManager.isActive() || !BTCCoreConfig.dearEnabled) return;
        Entity entity = event.getEntity();
        boolean nearPlayer = false;
        for (Player player : entity.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(entity.getLocation()) <= BTCCoreConfig.dearStartDistanceSquared) {
                nearPlayer = true;
                break;
            }
        }
        if (!nearPlayer && entity instanceof org.bukkit.entity.LivingEntity living) {
            DABManager.disableAi(living);
        }
    }

    /**
     * Gives their AI back to mobs an earlier run left switched off by DAB.
     *
     * <p>{@code setAI(false)} is saved with the mob, so a mob DAB switched off stays {@code NoAI} across
     * restarts and chunk unloads. Only a mob carrying the DAB mark is touched: a NoAI mob without it was
     * set that way by an admin, an NPC plugin or a model. Fired on the region thread that owns the chunk.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        if (DABManager.isActive()) return;
        for (Entity entity : event.getEntities()) {
            if (entity instanceof org.bukkit.entity.LivingEntity living) {
                DABManager.restoreIfMarked(living);
            }
        }
    }
}
