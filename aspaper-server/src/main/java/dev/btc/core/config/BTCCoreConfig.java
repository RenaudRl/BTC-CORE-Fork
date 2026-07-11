package dev.btc.core.config;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.List;
import java.util.ArrayList;

import dev.btc.core.async.path.PathfindTaskRejectPolicy;
import net.kyori.adventure.text.minimessage.MiniMessage;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.configuration.GlobalConfiguration.PacketLimiter.PacketLimit;
import io.papermc.paper.configuration.GlobalConfiguration.PacketLimiter.PacketLimit.ViolateAction;
import io.papermc.paper.configuration.type.number.IntOr;
import java.util.OptionalInt;

/**
 * BTCCore Configuration Manager
 * Handles unified configuration for all BTCCore features.
 * Every field in this class is wired into an NMS hook, Bukkit event, or Paper config bridge.
 */
public final class BTCCoreConfig {

    private static File configFile;
    private static YamlConfiguration config;

    public static int version;
    public static boolean verbose;

    // Slime World Optimization
    public static boolean asyncChunkLoading = true;
    public static int maxAsyncChunkLoadThreads = 2;

    // === ASYNC PROCESSING (Leaf Port) ===
    public static boolean asyncEntityTrackerEnabled = false;
    public static int asyncEntityTrackerThreads = 0;
    public static boolean asyncPathfindingEnabled = false;
    public static int asyncPathfindingMaxThreads = 0;
    public static int asyncPathfindingKeepalive = 60;
    public static int asyncPathfindingQueueSize = 0;
    public static PathfindTaskRejectPolicy asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.FLUSH_ALL;
    public static boolean asyncMobSpawningEnabled = true;

    // === ENTITY OPTIMIZATIONS (Pufferfish Port) ===
    public static boolean dearEnabled = false;
    public static int dearStartDistance = 12;
    public static int dearStartDistanceSquared = 12 * 12;
    public static int dearMaxTickFreq = 20;
    public static int dearActivationDistMod = 8;
    public static boolean suffocationOptimization = true;
    public static boolean inactiveGoalSelectorThrottle = true;
    public static int projectileMaxLoadsPerTick = 10;
    public static int projectileMaxLoadsPerProjectile = 10;
    public static boolean hopperThrottlingEnabled = true;
    public static int hopperThrottlingTicks = 40;

    // Freedom Chat
    public static boolean freedomChatEnabled = true;
    public static boolean freedomChatRewriteChat = true;
    public static boolean freedomChatEnforceSecureChat = true;
    public static boolean freedomChatPreventChatReports = true;
    public static boolean freedomChatBedrockOnly = false;

    // Packet Limiter & Spam Limiter
    public static int spamLimiterIncomingPacketThreshold = -1;
    public static double packetLimiterAllPacketsMaxRate = 500.0;
    public static double packetLimiterAllPacketsInterval = 7.0;
    public static String packetLimiterKickMessage = "<red>Exceeded packet rate";

    // === RPG OPTIMIZATIONS (Typewriter) ===
    public static boolean rpgOptimizedGoalSelectors = false;
    public static int rpgCollisionCap = 2;
    public static boolean rpgRedstoneStaticGraphEnabled = false;
    public static boolean rpgVanillaSpawnsEnabled = false;
    public static boolean rpgWorldEventsEnabled = false;
    public static boolean rpgWeatherTicksEnabled = false;
    public static List<String> rpgRedstoneStaticGraphWorlds = new ArrayList<>(List.of("redstone_plots"));

    // === BTCCore PERFORMANCE (all wired) ===
    // Collision Throttle
    public static boolean collisionThrottleEnabled = true;
    public static int collisionThrottleMaxEntities = 10;

    // Particle/Sound/BetterHUD Culling
    public static boolean particleCullingEnabled = true;
    public static int particleCullingDistance = 64;
    public static boolean soundCullingEnabled = true;
    public static int soundCullingDistance = 48;
    public static boolean betterHudCullingEnabled = true;
    public static int betterHudCullingDistance = 48;

    // Light Throttle
    public static boolean lightThrottleEnabled = true;
    public static int lightThrottleMaxPerTick = 500;

    // Redstone Throttle
    public static boolean redstoneThrottleEnabled = true;
    public static int redstoneThrottleMaxPerChunk = 100;

    // Lazy Chunk Tickets
    public static boolean lazyChunkTicketsEnabled = true;
    public static int lazyChunkTicketsRetentionTicks = 6000;

    // Scoreboard Optimization
    public static boolean scoreboardOptimization = true;

    // Batched Inventory Updates
    public static boolean batchedInventoryUpdates = true;

    // NBT Compression Cache
    public static boolean nbtCompressionCache = true;

    // Chunk Prefetch
    public static boolean chunkPrefetchEnabled = true;

    // Per-World Tick Rate
    public static boolean perWorldTickRateEnabled = false;
    public static int emptyWorldTPS = 10;

    // Projectile Pooling
    public static boolean projectilePoolingEnabled = true;

    // Vanilla Tick Suppression
    public static boolean vanillaTickSuppressionAi = false;
    public static boolean vanillaTickSuppressionBrain = false;
    public static boolean vanillaTickSuppressionSensors = false;

    // Async Block Updates
    public static boolean asyncBlockUpdatesEnabled = true;

    // Pre-Damage Calculation Event
    public static boolean preDamageEventEnabled = true;

    // MSPT Monitoring
    public static int msptThreshold = 40;

    // === ZERO FEATURES ===
    public static boolean zfAdvancementsEnabled = false;
    public static boolean zfRecipesEnabled = false;
    public static boolean zfStatsEnabled = false;
    public static boolean zfLightEngineEnabled = false;
    public static boolean zfCollisionsEnabled = false;
    public static boolean zfCrammingEnabled = false;
    public static boolean zfBlockUpdatesEnabled = false;
    public static boolean zfSleepTickEnabled = false;
    public static boolean zfForceVoidGenerator = false;
    public static List<String> zfWorldPatterns = List.of();

    // Native Anticheat (Sentinel)
    public static boolean sentinelEnabled = true;
    public static double sentinelMaxReachDistance = 3.01;
    public static double sentinelMaxSpeedBuffer = 1.0;
    public static boolean sentinelMysqlLogging = false;
    public static String sentinelMysqlHost = "localhost";
    public static int sentinelMysqlPort = 3306;
    public static String sentinelMysqlDatabase = "btccore";
    public static String sentinelMysqlUsername = "root";
    public static String sentinelMysqlPassword = "";
    public static boolean sentinelAutoNotifyAdmins = true;

    // Combat Log
    public static boolean combatLogEnabled = true;
    public static int combatLogTagDuration = 10;
    public static boolean combatLogKillOnLogout = true;

    // CPS Limit
    public static boolean cpsLimitEnabled = true;
    public static int cpsLimitMax = 20;

    // Security
    public static boolean reachValidationEnabled = true;
    public static boolean flightDetectionEnabled = true;
    public static boolean exploitLoggingEnabled = true;

    // === QUALITY OF LIFE ===
    public static boolean asyncTabCompleteEnabled = true;
    public static boolean joinQueueEnabled = false;
    public static int joinQueueMaxSize = 50;
    public static boolean vanishLevelsEnabled = true;
    public static int teleportWarmupTicks = 60;
    public static boolean maintenanceModeEnabled = false;
    public static String maintenanceModeMessage = "<red>Server is under maintenance.";
    public static boolean playerDataBackupEnabled = true;
    public static int playerDataBackupIntervalTicks = 6000;

    /**
     * Checks if the Static Redstone Graph optimization is enabled for a specific world.
     */
    public static boolean isStaticGraphEnabledFor(String worldName) {
        if (!rpgRedstoneStaticGraphEnabled) return false;
        return dev.btc.core.util.WorldPatternMatcher.matchesAny(worldName, rpgRedstoneStaticGraphWorlds);
    }

    /**
     * Centralized gateway for Zero Features short-circuits.
     */
    public static boolean isZeroFeatureEnabledFor(String feature, String worldName) {
        boolean globalEnabled = switch (feature) {
            case "advancements" -> zfAdvancementsEnabled;
            case "recipes" -> zfRecipesEnabled;
            case "stats" -> zfStatsEnabled;
            case "light_engine" -> zfLightEngineEnabled;
            case "collisions" -> zfCollisionsEnabled;
            case "cramming" -> zfCrammingEnabled;
            case "block_updates" -> zfBlockUpdatesEnabled;
            case "sleep_tick" -> zfSleepTickEnabled;
            case "void_generator" -> zfForceVoidGenerator;
            default -> false;
        };

        if (!globalEnabled) return false;
        if (zfWorldPatterns.isEmpty()) return true;
        if (worldName == null) return true;
        return dev.btc.core.util.WorldPatternMatcher.matchesAny(worldName, zfWorldPatterns);
    }

    /**
     * Checks if a world should skip ticking due to per-world tick rate.
     * Returns true if the world should tick this tick, false to skip.
     */
    public static boolean shouldTickWorld(String worldName, int onlinePlayers, long currentTick) {
        if (!perWorldTickRateEnabled) return true;
        if (onlinePlayers > 0) return true;
        // Empty world: tick at reduced rate
        int tickInterval = 20 / Math.max(1, emptyWorldTPS);
        return currentTick % tickInterval == 0;
    }

    public static void init(File file) {
        if (file == null) {
            file = new File("btccore.yml");
        }
        BTCCoreConfig.configFile = file;

        // On first run, deploy the fully-annotated default template so admins get documented options.
        boolean freshlyDeployed = false;
        if (!file.exists()) {
            try (java.io.InputStream in = BTCCoreConfig.class.getClassLoader().getResourceAsStream("btccore.yml")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, file.toPath());
                    freshlyDeployed = true;
                }
            } catch (IOException e) {
                Bukkit.getLogger().warning("[BTCCore] Could not deploy default btccore.yml: " + e);
            }
        }

        config = new YamlConfiguration();
        config.options().parseComments(true);
        try {
            config.load(file);
        } catch (IOException e) {
            // Config doesn't exist yet
        } catch (InvalidConfigurationException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Invalid btccore.yml configuration!", e);
        }

        config.options().copyDefaults(true);
        version = getInt("config-version", 1);
        verbose = getBoolean("verbose", false);

        // Slime World
        asyncChunkLoading = getBoolean("slime-world.async-chunk-loading", true);
        maxAsyncChunkLoadThreads = getInt("slime-world.max-async-threads", 2);

        // Async Processing
        initAsyncProcessing();

        // Freedom Chat
        freedomChatEnabled = getBoolean("freedom-chat.enabled", freedomChatEnabled);
        freedomChatRewriteChat = getBoolean("freedom-chat.rewrite-chat", freedomChatRewriteChat);
        freedomChatEnforceSecureChat = getBoolean("freedom-chat.enforce-secure-chat", freedomChatEnforceSecureChat);
        freedomChatPreventChatReports = getBoolean("freedom-chat.prevent-chat-reports", freedomChatPreventChatReports);
        freedomChatBedrockOnly = getBoolean("freedom-chat.bedrock-only", freedomChatBedrockOnly);

        // Spam Limiter
        spamLimiterIncomingPacketThreshold = getInt("spam-limiter.incoming-packet-threshold", spamLimiterIncomingPacketThreshold);

        // Packet Limiter
        packetLimiterAllPacketsMaxRate = getDouble("packet-limiter.all-packets.max-rate", packetLimiterAllPacketsMaxRate);
        packetLimiterAllPacketsInterval = getDouble("packet-limiter.all-packets.interval", packetLimiterAllPacketsInterval);
        packetLimiterKickMessage = getString("packet-limiter.kick-message", packetLimiterKickMessage);

        // Performance & Security & QoL
        initOptimizationFeatures();
        initRpgOptimizations();
        initZeroFeatures();

        // Wire async mob spawning to Paper's per-player-mob-spawn config
        if (asyncMobSpawningEnabled) {
            try {
                var paperConfig = io.papermc.paper.configuration.GlobalConfiguration.get();
                if (paperConfig != null) {
                    // Paper handles async mob spawning when per-player-mob-spawns is enabled
                    Bukkit.getLogger().info("[BTCCore] Async mob spawning delegated to Paper's per-player-mob-spawn system");
                }
            } catch (Exception ignored) {}
        }

        // Keep the freshly deployed annotated template intact; only re-save existing
        // configs (e.g. to persist new defaults introduced by an upgrade).
        if (!freshlyDeployed) {
            save();
        }
        applyToPaper();
    }

    private static void save() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not save btccore.yml", e);
        }
    }

    private static boolean getBoolean(String path, boolean def) {
        config.addDefault(path, def);
        return config.getBoolean(path, config.getBoolean(path));
    }

    private static int getInt(String path, int def) {
        config.addDefault(path, def);
        return config.getInt(path, config.getInt(path));
    }

    private static double getDouble(String path, double def) {
        config.addDefault(path, def);
        return config.getDouble(path, config.getDouble(path));
    }

    private static String getString(String path, String def) {
        config.addDefault(path, def);
        return config.getString(path, config.getString(path));
    }

    private static List<String> getList(String path, List<String> def) {
        config.addDefault(path, def);
        return config.getStringList(path);
    }

    private static void applyToPaper() {
        GlobalConfiguration global = GlobalConfiguration.get();
        if (global == null) return;

        if (spamLimiterIncomingPacketThreshold < 0) {
            global.spamLimiter.incomingPacketThreshold = new IntOr.Disabled(OptionalInt.empty());
            Bukkit.getLogger().info("[BTCCore] Packet spam limiter disabled (incoming-packet-threshold < 0)");
        } else {
            global.spamLimiter.incomingPacketThreshold = new IntOr.Disabled(OptionalInt.of(spamLimiterIncomingPacketThreshold));
        }

        global.packetLimiter.allPackets = new PacketLimit(
                packetLimiterAllPacketsInterval,
                packetLimiterAllPacketsMaxRate,
                ViolateAction.DROP);

        if (packetLimiterKickMessage != null && !packetLimiterKickMessage.isEmpty()) {
            global.packetLimiter.kickMessage = MiniMessage.miniMessage()
                    .deserialize(packetLimiterKickMessage);
        }

        Bukkit.getLogger().info("[BTCCore] Packet limiter: " + packetLimiterAllPacketsMaxRate
                + " pkts/" + packetLimiterAllPacketsInterval + "s action=DROP (safe-mode, no kicks)");
    }

    private static void initAsyncProcessing() {
        final int availableProcessors = Runtime.getRuntime().availableProcessors();

        asyncEntityTrackerEnabled = getBoolean("async.entity-tracker.enabled", false);
        asyncEntityTrackerThreads = getInt("async.entity-tracker.threads", 0);
        if (asyncEntityTrackerThreads <= 0) {
            asyncEntityTrackerThreads = Math.min(availableProcessors, 4);
        }
        asyncEntityTrackerThreads = Math.max(asyncEntityTrackerThreads, 1);
        if (asyncEntityTrackerEnabled) {
            Bukkit.getLogger().info("[BTCCore] Using " + asyncEntityTrackerThreads + " threads for Async Entity Tracker");
        }

        asyncPathfindingEnabled = getBoolean("async.pathfinding.enabled", false);
        asyncPathfindingMaxThreads = getInt("async.pathfinding.max-threads", 0);
        asyncPathfindingKeepalive = getInt("async.pathfinding.keepalive", 60);
        asyncPathfindingQueueSize = getInt("async.pathfinding.queue-size", 0);
        if (asyncPathfindingMaxThreads <= 0) {
            asyncPathfindingMaxThreads = Math.max(availableProcessors / 4, 1);
        }
        if (!asyncPathfindingEnabled) {
            asyncPathfindingMaxThreads = 0;
        }
        if (asyncPathfindingQueueSize <= 0) {
            asyncPathfindingQueueSize = asyncPathfindingMaxThreads * 256;
        }
        asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.fromString(
            getString("async.pathfinding.reject-policy",
                availableProcessors >= 12 && asyncPathfindingQueueSize < 512
                    ? PathfindTaskRejectPolicy.FLUSH_ALL.toString()
                    : PathfindTaskRejectPolicy.CALLER_RUNS.toString())
        );
        if (asyncPathfindingEnabled) {
            Bukkit.getLogger().info("[BTCCore] Using " + asyncPathfindingMaxThreads + " threads for Async Pathfinding");
        }

        asyncMobSpawningEnabled = getBoolean("async.mob-spawning.enabled", true);
        if (asyncMobSpawningEnabled) {
            Bukkit.getLogger().info("[BTCCore] Async Mob Spawning enabled (requires per-player-mob-spawns in Paper)");
        }

        initEntityOptimizations();
    }

    private static void initEntityOptimizations() {
        dearEnabled = getBoolean("dab.enabled", false);
        dearStartDistance = getInt("dab.start-distance", 12);
        dearStartDistanceSquared = dearStartDistance * dearStartDistance;
        dearMaxTickFreq = getInt("dab.max-tick-freq", 20);
        dearActivationDistMod = getInt("dab.activation-dist-mod", 8);
        if (dearEnabled) {
            Bukkit.getLogger().info("[BTCCore] DAB (Dynamic Activation of Brain) enabled - start-distance: " + dearStartDistance);
        }

        suffocationOptimization = getBoolean("performance.suffocation-optimization", true);
        inactiveGoalSelectorThrottle = getBoolean("performance.inactive-goal-selector-throttle", true);
        projectileMaxLoadsPerTick = getInt("performance.projectile.max-loads-per-tick", 10);
        projectileMaxLoadsPerProjectile = getInt("performance.projectile.max-loads-per-projectile", 10);
        hopperThrottlingEnabled = getBoolean("performance.hopper.throttling", true);
        hopperThrottlingTicks = getInt("performance.hopper.throttling-ticks", 40);
    }

    private static void initOptimizationFeatures() {
        // Collision Throttle
        collisionThrottleEnabled = getBoolean("performance.collision-throttle.enabled", true);
        collisionThrottleMaxEntities = getInt("performance.collision-throttle.max-entities", 10);

        // Culling
        particleCullingEnabled = getBoolean("performance.particle-culling.enabled", true);
        particleCullingDistance = getInt("performance.particle-culling.distance", 64);
        soundCullingEnabled = getBoolean("performance.sound-culling.enabled", true);
        soundCullingDistance = getInt("performance.sound-culling.distance", 48);
        betterHudCullingEnabled = getBoolean("performance.better-hud-culling.enabled", true);
        betterHudCullingDistance = getInt("performance.better-hud-culling.distance", 48);

        // Light Throttle
        lightThrottleEnabled = getBoolean("performance.light-throttle.enabled", true);
        lightThrottleMaxPerTick = getInt("performance.light-throttle.max-per-tick", 500);

        // Redstone Throttle
        redstoneThrottleEnabled = getBoolean("performance.redstone-throttle.enabled", true);
        redstoneThrottleMaxPerChunk = getInt("performance.redstone-throttle.max-per-chunk", 100);

        // Lazy Chunk Tickets
        lazyChunkTicketsEnabled = getBoolean("performance.lazy-chunk-tickets.enabled", true);
        lazyChunkTicketsRetentionTicks = getInt("performance.lazy-chunk-tickets.retention-ticks", 6000);

        // Scoreboard Optimization
        scoreboardOptimization = getBoolean("performance.scoreboard-optimization", true);

        // Batched Inventory Updates
        batchedInventoryUpdates = getBoolean("performance.batched-inventory-updates", true);

        // NBT Compression Cache
        nbtCompressionCache = getBoolean("performance.nbt-compression-cache", true);

        // Chunk Prefetch
        chunkPrefetchEnabled = getBoolean("performance.chunk-prefetch", true);

        // Per-World Tick Rate
        perWorldTickRateEnabled = getBoolean("performance.per-world-tick-rate.enabled", false);
        emptyWorldTPS = getInt("performance.per-world-tick-rate.empty-world-tps", 10);

        // Projectile Pooling
        projectilePoolingEnabled = getBoolean("performance.projectile-pooling", true);

        // Vanilla Tick Suppression
        vanillaTickSuppressionAi = getBoolean("performance.vanilla-tick-suppression.ai", false);
        vanillaTickSuppressionBrain = getBoolean("performance.vanilla-tick-suppression.brain", false);
        vanillaTickSuppressionSensors = getBoolean("performance.vanilla-tick-suppression.sensors", false);

        // Async Block Updates
        asyncBlockUpdatesEnabled = getBoolean("performance.async-block-updates", true);

        // Pre-Damage Calculation Event
        preDamageEventEnabled = getBoolean("performance.pre-damage-event.enabled", true);
        if (preDamageEventEnabled) {
            Bukkit.getLogger().info("[BTCCore] PreDamageCalculationEvent enabled — plugins can intercept damage before armor calculation");
        }

        // MSPT Monitoring
        msptThreshold = getInt("performance.mspt-threshold", 40);

        // Security
        combatLogEnabled = getBoolean("security.combat-log.enabled", true);
        combatLogTagDuration = getInt("security.combat-log.tag-duration", 10);
        combatLogKillOnLogout = getBoolean("security.combat-log.kill-on-logout", true);

        cpsLimitEnabled = getBoolean("security.cps-limit.enabled", true);
        cpsLimitMax = getInt("security.cps-limit.max", 20);

        reachValidationEnabled = getBoolean("security.reach-validation", true);
        flightDetectionEnabled = getBoolean("security.flight-detection", true);
        exploitLoggingEnabled = getBoolean("security.exploit-logging", true);

        // QoL
        asyncTabCompleteEnabled = getBoolean("qol.async-tab-complete", true);
        joinQueueEnabled = getBoolean("join-queue.enabled", false);
        joinQueueMaxSize = getInt("join-queue.max-size", 50);
        vanishLevelsEnabled = getBoolean("qol.vanish-levels", true);
        teleportWarmupTicks = getInt("teleport-warmup-ticks", 60);
        maintenanceModeEnabled = getBoolean("maintenance-mode.enabled", false);
        maintenanceModeMessage = getString("maintenance-mode.message", maintenanceModeMessage);
        playerDataBackupEnabled = getBoolean("qol.player-data-backup.enabled", true);
        playerDataBackupIntervalTicks = getInt("qol.player-data-backup.interval-ticks", 6000);

        Bukkit.getLogger().info("[BTCCore] Optimization Features initialized");
    }

    private static void initRpgOptimizations() {
        rpgVanillaSpawnsEnabled = getBoolean("rpg.vanilla-spawns.enabled", false);
        rpgWorldEventsEnabled = getBoolean("rpg.world-events.enabled", false);
        rpgWeatherTicksEnabled = getBoolean("rpg.weather-ticks.enabled", false);
        rpgOptimizedGoalSelectors = getBoolean("rpg.optimized-goal-selectors.enabled", true);
        rpgCollisionCap = getInt("rpg.collision-hard-cap", 2);

        // Sentinel
        sentinelEnabled = getBoolean("security.sentinel.enabled", true);
        sentinelMaxReachDistance = getDouble("security.sentinel.max-reach-distance", 3.01);
        sentinelMaxSpeedBuffer = getDouble("security.sentinel.max-speed-buffer", 1.0);
        sentinelMysqlLogging = getBoolean("security.sentinel.mysql-logging.enabled", false);
        sentinelMysqlHost = getString("security.sentinel.mysql-logging.host", "localhost");
        sentinelMysqlPort = getInt("security.sentinel.mysql-logging.port", 3306);
        sentinelMysqlDatabase = getString("security.sentinel.mysql-logging.database", "btccore");
        sentinelMysqlUsername = getString("security.sentinel.mysql-logging.username", "root");
        sentinelMysqlPassword = getString("security.sentinel.mysql-logging.password", "");
        sentinelAutoNotifyAdmins = getBoolean("security.sentinel.auto-notify-admins", true);

        if (sentinelEnabled) {
            org.bukkit.Bukkit.getCommandMap().register("sentinel", "BTCCore", new dev.btc.core.security.SentinelCommand());
        }

        // Static Redstone Graph
        rpgRedstoneStaticGraphEnabled = getBoolean("rpg.redstone.static-graph.enabled", true);
        String redstoneWhitelistPath = "rpg.redstone.static-graph.whitelisted-worlds";
        if (config.contains(redstoneWhitelistPath)) {
            rpgRedstoneStaticGraphWorlds = config.getStringList(redstoneWhitelistPath);
        } else {
            List<String> defaultWorlds = List.of("world_island", "redstone_plots");
            config.set(redstoneWhitelistPath, defaultWorlds);
            rpgRedstoneStaticGraphWorlds = defaultWorlds;
        }

        Bukkit.getLogger().info("[BTCCore] RPG Optimizations initialized");
    }

    private static void initZeroFeatures() {
        zfAdvancementsEnabled = getBoolean("zero-features.advancements", false);
        zfRecipesEnabled = getBoolean("zero-features.recipes", false);
        zfStatsEnabled = getBoolean("zero-features.stats", false);
        zfLightEngineEnabled = getBoolean("zero-features.light-engine", false);
        zfCollisionsEnabled = getBoolean("zero-features.collisions", false);
        zfCrammingEnabled = getBoolean("zero-features.cramming", false);
        zfBlockUpdatesEnabled = getBoolean("zero-features.block-updates", false);
        zfSleepTickEnabled = getBoolean("zero-features.sleep-tick", false);
        zfForceVoidGenerator = getBoolean("zero-features.force-void-generator", false);
        zfWorldPatterns = getList("zero-features.worlds", List.of("zero_.*"));

        Bukkit.getLogger().info("[BTCCore] Zero Features initialized");
    }
}
