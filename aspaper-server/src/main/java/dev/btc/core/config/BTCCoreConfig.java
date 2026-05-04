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
 * Handles unified configuration for all BTCCore features
 */
public final class BTCCoreConfig {

    private static File configFile;
    private static YamlConfiguration config;

    public static int version;
    public static boolean verbose;

    // World Settings
    public static boolean parallelWorldTicking = false;

    // Slime World Optimization
    public static boolean asyncChunkLoading = true;
    public static int maxAsyncChunkLoadThreads = 2;

    // Entity Settings
    public static int entityTrackingRange = 64;
    public static boolean optimizeEntityTracking = true;

    // Chunk Settings
    public static int maxChunkLoadDistance = 10;
    public static boolean preventChunkLoadOnMove = false;

    // Performance Settings
    public static int ticksPerAutoSave = 6000;
    public static boolean disableDebugScreen = false;

    // Sign Settings
    public static boolean signRightClickEdit = false;
    public static boolean signAllowColors = false;

    public static boolean asyncPlayerSave = false;
    public static boolean useSqliteForStats = false;

    // === ASYNC PROCESSING (Leaf Port) ===
    // Async Entity Tracker
    public static boolean asyncEntityTrackerEnabled = false;
    public static int asyncEntityTrackerThreads = 0;

    // Async Pathfinding
    public static boolean asyncPathfindingEnabled = false;
    public static int asyncPathfindingMaxThreads = 0;
    public static int asyncPathfindingKeepalive = 60;
    public static int asyncPathfindingQueueSize = 0;
    public static PathfindTaskRejectPolicy asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.FLUSH_ALL;

    // Async Mob Spawning
    public static boolean asyncMobSpawningEnabled = true;

    // === ENTITY OPTIMIZATIONS (Pufferfish Port) ===
    // Dynamic Entity Activation of Brain (DEAR)
    public static boolean dearEnabled = false;
    public static int dearStartDistance = 12;
    public static int dearStartDistanceSquared = 12 * 12;
    public static int dearMaxTickFreq = 20;
    public static int dearActivationDistMod = 8;
    
    // Suffocation Optimization
    public static boolean suffocationOptimization = true;
    
    // Inactive Goal Selector Throttle
    public static boolean inactiveGoalSelectorThrottle = true;
    
    // Projectile Chunk Loading Limits
    public static int projectileMaxLoadsPerTick = 10;
    public static int projectileMaxLoadsPerProjectile = 10;

    // Hopper Throttling (SparklyPaper)
    public static boolean hopperThrottlingEnabled = true;
    public static int hopperThrottlingTicks = 40;

    // Freedom Chat Settings
    public static boolean freedomChatEnabled = true;
    public static boolean freedomChatRewriteChat = true;
    public static boolean freedomChatEnforceSecureChat = true;
    public static boolean freedomChatPreventChatReports = true;
    public static boolean freedomChatBedrockOnly = false;

    // Packet Limiter & Spam Limiter
    public static int spamLimiterIncomingPacketThreshold = 300;

    // === RPG OPTIMIZATIONS (Typewriter) ===
    public static boolean rpgOptimizedGoalSelectors = false;
    public static int rpgCollisionCap = 2;
    public static boolean rpgRedstoneStaticGraphEnabled = false;
    public static boolean rpgVanillaSpawnsEnabled = false;
    public static boolean rpgWorldEventsEnabled = false;
    public static boolean rpgWeatherTicksEnabled = false;
    public static List<String> rpgRedstoneStaticGraphWorlds = new ArrayList<>(List.of("redstone_plots"));

    /**
     * Checks if the Static Redstone Graph optimization is enabled for a specific world.
     * Supports wildcards (*), prefix matching, and regex (regex:^pattern$).
     */
    public static boolean isStaticGraphEnabledFor(String worldName) {
        if (!rpgRedstoneStaticGraphEnabled) return false;
        return dev.btc.core.util.WorldPatternMatcher.matchesAny(worldName, rpgRedstoneStaticGraphWorlds);
    }
    
    public static double packetLimiterAllPacketsMaxRate = 500.0;
    public static double packetLimiterAllPacketsInterval = 7.0;
    public static String packetLimiterKickMessage = "<red>Exceeded packet rate";
    public static int cascadingEntitySpawnLimit = 50;

    // Monitoring
    public static String sentryDsn = "";
    public static boolean disableMethodProfiler = true;
    public static String flareUrl = "https://flare.airplane.gg";
    public static String flareToken = "";

    // === PURPUR FEATURES ===
    // AFK
    public static int afkIdleTimeout = 300;
    public static boolean afkKickEnabled = false;
    public static String afkKickMessage = "<red>You have been kicked for idling.";
    public static boolean afkBroadcastEnabled = false;
    public static String afkBroadcastMessage = "<yellow>%s is now AFK";
    public static String afkReturnMessage = "<yellow>%s is no longer AFK";

    // Commands
    public static boolean commandPingEnabled = true;
    public static boolean commandUptimeEnabled = true;

    // Gameplay
    public static String allowedUsernameCharacters = "[a-zA-Z0-9_.]";
    public static boolean endCrystalRespawn = true;
    public static boolean disableFallDamage = false; // Simplified for now
    public static java.util.Map<String, Double> blastResistanceOverrides = new java.util.HashMap<>();



    // Minecarts
    public static boolean controllableMinecarts = false;
    public static double controllableMinecartStepHeight = 1.0;
    public static double controllableMinecartHopBoost = 0.5;

    // Elytra
    public static int elytraDamagePerSecond = 1;
    public static int elytraDamagePerBoost = 5;
    public static boolean elytraIgnoreUnbreaking = false;

    // Mobs/Spawners
    public static boolean rideableMobs = false;
    public static boolean silkTouchSpawners = false;

    // Inventory
    public static int barrelRows = 3;
    public static int enderChestRows = 3;

    // === CANVAS SETTINGS ===
    // Networking
    public static boolean filterClientboundSetEntityMotionPacket = false;
    public static boolean reduceUselessMovePackets = false;
    public static String networkIoModel = "NIO";

    // Combat
    public static boolean disableAttackHitDelay = false;
    public static int invulnerabilityTicks = 10;

    // Chunks
    public static boolean useEuclideanDistanceSquared = true;

    // === BTCCore PERFORMANCE ===
    // Entity Collision Throttle
    public static boolean collisionThrottleEnabled = true;
    public static int collisionThrottleMaxEntities = 10;

    // Particle/Sound Culling
    public static boolean particleCullingEnabled = true;
    public static int particleCullingDistance = 64;
    public static boolean soundCullingEnabled = true;
    public static int soundCullingDistance = 48;

    // BetterHUD Culling
    public static boolean betterHudCullingEnabled = true;
    public static int betterHudCullingDistance = 48;

    // Light Throttling
    public static boolean lightThrottleEnabled = true;
    public static int lightThrottleMaxPerTick = 500;

    // Redstone Throttling
    public static boolean redstoneThrottleEnabled = true;
    public static int redstoneThrottleMaxPerChunk = 100;

    // Lazy Chunk Tickets
    public static boolean lazyChunkTicketsEnabled = true;
    public static int lazyChunkTicketsRetentionTicks = 6000;

    // Scoreboard Optimization
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

    // === ZERO FEATURES (Minestom Ports) ===
    public static boolean zfAdvancementsEnabled = false;
    public static boolean zfRecipesEnabled = false;
    public static boolean zfStatsEnabled = false;
    public static boolean zfLightEngineEnabled = false;
    public static boolean zfCollisionsEnabled = false;
    public static boolean zfCrammingEnabled = false;
    public static boolean zfBlockUpdatesEnabled = false;
    public static boolean zfSleepTickEnabled = false;
    public static boolean zfForceVoidGenerator = false;
    public static List<String> zfWorldPatterns = List.of("zero_.*");

    /**
     * Centralized gateway for Zero Features short-circuits.
     * Checks if a feature is enabled globally and if the world matches the target pattern.
     *
     * @param feature   The feature key (e.g., "light_engine", "collisions", "stats")
     * @param worldName The name of the world being checked
     * @return true if the feature is ENABLED in config (meaning the vanilla mechanic should be BYPASSED)
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

        // Pattern matching for world-specific features
        // Advancements and Recipes are global, so we return global status directly if no world name is provided
        if (worldName == null || feature.equals("advancements") || feature.equals("recipes")) {
            return globalEnabled;
        }

        return dev.btc.core.util.WorldPatternMatcher.matchesAny(worldName, zfWorldPatterns);
    }
    // Native Anticheat (Sentinel)
    public static boolean sentinelEnabled = true;
    public static double sentinelMaxReachDistance = 3.01;
    public static double sentinelMaxSpeedBuffer = 1.0;
    public static boolean sentinelMysqlLogging = false;
    public static boolean sentinelAutoNotifyAdmins = true;
    // Combat Log Prevention
    public static boolean combatLogEnabled = true;
    public static int combatLogTagDuration = 10;
    public static boolean combatLogKillOnLogout = true;

    // CPS Limit
    public static boolean cpsLimitEnabled = true;
    public static int cpsLimitMax = 20;

    // Reach Validation
    public static boolean reachValidationEnabled = true;

    // Flight Detection Enhancement
    public static boolean flightDetectionEnabled = true;

    // Exploit Logging
    public static boolean exploitLoggingEnabled = true;

    // === QUALITY OF LIFE ===
    // Async Tab Complete
    public static boolean asyncTabCompleteEnabled = true;

    // Join Queue
    public static boolean joinQueueEnabled = false;
    public static int joinQueueMaxSize = 50;

    // Vanish Levels
    public static boolean vanishLevelsEnabled = true;

    // Teleport Warmup
    public static int teleportWarmupTicks = 60;

    // Maintenance Mode
    public static boolean maintenanceModeEnabled = false;
    public static String maintenanceModeMessage = "<red>Server is under maintenance.";

    // Player Data Backup
    public static boolean playerDataBackupEnabled = true;
    public static int playerDataBackupIntervalTicks = 6000;


    public static void init(File configFile) {
        if (configFile == null) {
            configFile = new File("btccore.yml");
        }
        BTCCoreConfig.configFile = configFile;
        config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException e) {
            // Config doesn't exist yet
        } catch (InvalidConfigurationException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Invalid btccore.yml configuration!", e);
        }

        config.options().copyDefaults(true);
        version = getInt("config-version", 1);
        verbose = getBoolean("verbose", false);

        // World Settings
        parallelWorldTicking = getBoolean("world.parallel-ticking", false);

        // Slime World Settings  
        asyncChunkLoading = getBoolean("slime-world.async-chunk-loading", true);
        maxAsyncChunkLoadThreads = getInt("slime-world.max-async-threads", 2);

        // Entity Settings
        entityTrackingRange = getInt("entity.tracking-range", 64);
        optimizeEntityTracking = getBoolean("entity.optimize-tracking", true);

        // Chunk Settings
        maxChunkLoadDistance = getInt("chunk.max-load-distance", 10);
        preventChunkLoadOnMove = getBoolean("chunk.prevent-load-on-move", false);

        // Performance Settings
        ticksPerAutoSave = getInt("performance.ticks-per-autosave", 6000);
        disableDebugScreen = getBoolean("performance.disable-debug-screen", false);

        // Sign
        signRightClickEdit = getBoolean("sign.right-click-edit", signRightClickEdit);
        signAllowColors = getBoolean("sign.allow-colors", signAllowColors);

        // Optimization
        asyncPlayerSave = getBoolean("experimental.async-player-save", asyncPlayerSave);

        // === ASYNC PROCESSING (Leaf Port) ===
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
        cascadingEntitySpawnLimit = getInt("cascading-entity-spawn-limit", cascadingEntitySpawnLimit);
        
        // Monitoring
        sentryDsn = getString("monitoring.sentry-dsn", sentryDsn);
        disableMethodProfiler = getBoolean("monitoring.disable-method-profiler", true);
        flareUrl = getString("flare.url", flareUrl);
        flareToken = getString("flare.token", flareToken);

        // === PURPUR FEATURES ===
        initPurpurFeatures();

        // === CANVAS FEATURES ===
        initCanvasSettings();

        // === BTCCore FEATURES ===
        initOptimizationFeatures();

        // === RPG OPTIMIZATIONS ===
        initRpgOptimizations();

        // === ZERO FEATURES ===
        initZeroFeatures();


        save();
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

    private static void applyToPaper() {
        GlobalConfiguration global = GlobalConfiguration.get();
        if (global == null)
            return;

        global.spamLimiter.incomingPacketThreshold = new IntOr.Disabled(OptionalInt.of(spamLimiterIncomingPacketThreshold));

        // Global Packet Limiter
        global.packetLimiter.allPackets = new PacketLimit(
                packetLimiterAllPacketsInterval,
                packetLimiterAllPacketsMaxRate,
                ViolateAction.KICK);

        if (packetLimiterKickMessage != null && !packetLimiterKickMessage.isEmpty()) {
            global.packetLimiter.kickMessage = MiniMessage.miniMessage()
                    .deserialize(packetLimiterKickMessage);
        }
    }

    private static void initAsyncProcessing() {
        final int availableProcessors = Runtime.getRuntime().availableProcessors();

        // Async Entity Tracker
        asyncEntityTrackerEnabled = getBoolean("async.entity-tracker.enabled", false);
        asyncEntityTrackerThreads = getInt("async.entity-tracker.threads", 0);
        if (asyncEntityTrackerThreads <= 0) {
            asyncEntityTrackerThreads = Math.min(availableProcessors, 4);
        }
        asyncEntityTrackerThreads = Math.max(asyncEntityTrackerThreads, 1);
        if (asyncEntityTrackerEnabled) {
            Bukkit.getLogger().info("[BTCCore] Using " + asyncEntityTrackerThreads + " threads for Async Entity Tracker");
        }

        // Async Pathfinding
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

        // Async Mob Spawning
        asyncMobSpawningEnabled = getBoolean("async.mob-spawning.enabled", true);
        if (asyncMobSpawningEnabled) {
            Bukkit.getLogger().info("[BTCCore] Async Mob Spawning enabled (requires per-player-mob-spawns in Paper)");
        }

        // === ENTITY OPTIMIZATIONS (Pufferfish Port) ===
        initEntityOptimizations();
    }

    private static void initEntityOptimizations() {
        // Dynamic Entity Activation of Brain (DAB)
        dearEnabled = getBoolean("dab.enabled", false);
        dearStartDistance = getInt("dab.start-distance", 12);
        dearStartDistanceSquared = dearStartDistance * dearStartDistance;
        dearMaxTickFreq = getInt("dab.max-tick-freq", 20);
        dearActivationDistMod = getInt("dab.activation-dist-mod", 8);
        
        if (dearEnabled) {
            Bukkit.getLogger().info("[BTCCore] DAB (Dynamic Activation of Brain) enabled - start-distance: " + dearStartDistance);
        }

        // Suffocation Optimization
        suffocationOptimization = getBoolean("performance.suffocation-optimization", true);
        
        // Inactive Goal Selector Throttle
        inactiveGoalSelectorThrottle = getBoolean("performance.inactive-goal-selector-throttle", true);
        
        // Projectile Chunk Loading Limits
        projectileMaxLoadsPerTick = getInt("performance.projectile.max-loads-per-tick", 10);
        projectileMaxLoadsPerProjectile = getInt("performance.projectile.max-loads-per-projectile", 10);
        
        // Hopper Throttling
        hopperThrottlingEnabled = getBoolean("performance.hopper.throttling", true);
        hopperThrottlingTicks = getInt("performance.hopper.throttling-ticks", 40);
    }


    private static void initPurpurFeatures() {
        // AFK
        afkIdleTimeout = getInt("gameplay.afk.idle-timeout", 300);
        afkKickEnabled = getBoolean("gameplay.afk.kick-enabled", false);
        afkKickMessage = getString("gameplay.afk.kick-message", afkKickMessage);
        afkBroadcastEnabled = getBoolean("gameplay.afk.broadcast-enabled", false);
        afkBroadcastMessage = getString("gameplay.afk.broadcast-message", afkBroadcastMessage);
        afkReturnMessage = getString("gameplay.afk.return-message", afkReturnMessage);

        // Commands
        commandPingEnabled = getBoolean("command.ping.enabled", true);
        commandUptimeEnabled = getBoolean("command.uptime.enabled", true);

        // Gameplay
        allowedUsernameCharacters = getString("settings.allowed-username-characters", allowedUsernameCharacters);
        endCrystalRespawn = getBoolean("gameplay.end-crystal-respawn", true);
        disableFallDamage = getBoolean("gameplay.disable-fall-damage", disableFallDamage);

        // Blast Resistance Overrides
        blastResistanceOverrides.clear();
        String path = "gameplay.blast-resistance-overrides";
        if (config.isConfigurationSection(path)) {
            org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection(path);
            for (String key : section.getKeys(false)) {
                blastResistanceOverrides.put(key, section.getDouble(key));
            }
        }

        // Minecarts
        controllableMinecarts = getBoolean("minecart.controllable.enabled", false);
        controllableMinecartStepHeight = getDouble("minecart.controllable.step-height", 1.0);
        controllableMinecartHopBoost = getDouble("minecart.controllable.hop-boost", 0.5);

        // Elytra
        elytraDamagePerSecond = getInt("elytra.damage-per-second", 1);
        elytraDamagePerBoost = getInt("elytra.damage-per-boost", 5);
        elytraIgnoreUnbreaking = getBoolean("elytra.ignore-unbreaking", false);

        // Mobs/Spawners
        rideableMobs = getBoolean("gameplay.rideable-mobs.enabled", false);
        silkTouchSpawners = getBoolean("gameplay.silk-touch-spawners", false);

        // Inventory
        barrelRows = getInt("inventory.barrel-rows", 3);
        enderChestRows = getInt("inventory.ender-chest-rows", 3);
    }


    private static void initCanvasSettings() {
        // Networking
        filterClientboundSetEntityMotionPacket = getBoolean("canvas.networking.filter-entity-motion-packet", false);
        reduceUselessMovePackets = getBoolean("canvas.networking.reduce-useless-move-packets", false);
        networkIoModel = getString("canvas.networking.network-io-model", "NIO");

        // Combat
        disableAttackHitDelay = getBoolean("canvas.combat.disable-attack-hit-delay", false);
        invulnerabilityTicks = getInt("canvas.combat.invulnerability-ticks", 10);

        // Chunks
        useEuclideanDistanceSquared = getBoolean("canvas.chunks.use-euclidean-distance-squared", true);
    }

    private static void initOptimizationFeatures() {
        // === Performance Optimizations ===
        // Collision Throttle
        collisionThrottleEnabled = getBoolean("performance.collision-throttle.enabled", true);
        collisionThrottleMaxEntities = getInt("performance.collision-throttle.max-entities", 10);

        // Particle Culling
        particleCullingEnabled = getBoolean("performance.particle-culling.enabled", true);
        particleCullingDistance = getInt("performance.particle-culling.distance", 64);

        // Sound Culling
        soundCullingEnabled = getBoolean("performance.sound-culling.enabled", true);
        soundCullingDistance = getInt("performance.sound-culling.distance", 48);

        // BetterHUD Culling
        betterHudCullingEnabled = getBoolean("performance.better-hud-culling.enabled", true);
        betterHudCullingDistance = getInt("performance.better-hud-culling.distance", 48);

        // Scoreboard Optimization
        scoreboardOptimization = getBoolean("performance.scoreboard-optimization", true);

        // Light Throttle
        lightThrottleEnabled = getBoolean("performance.light-throttle.enabled", true);
        lightThrottleMaxPerTick = getInt("performance.light-throttle.max-per-tick", 500);

        // Lazy Chunk Tickets
        lazyChunkTicketsEnabled = getBoolean("performance.lazy-chunk-tickets.enabled", true);
        lazyChunkTicketsRetentionTicks = getInt("performance.lazy-chunk-tickets.retention-ticks", 6000);

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

        // Redstone Throttle
        redstoneThrottleEnabled = getBoolean("performance.redstone-throttle.enabled", true);
        redstoneThrottleMaxPerChunk = getInt("performance.redstone-throttle.max-per-chunk", 100);

        // Redstone Throttle
        redstoneThrottleEnabled = getBoolean("performance.redstone-throttle.enabled", true);
        redstoneThrottleMaxPerChunk = getInt("performance.redstone-throttle.max-per-chunk", 100);

        // Vanilla Tick Suppression
        vanillaTickSuppressionAi = getBoolean("performance.vanilla-tick-suppression.ai", false);
        vanillaTickSuppressionBrain = getBoolean("performance.vanilla-tick-suppression.brain", false);
        vanillaTickSuppressionSensors = getBoolean("performance.vanilla-tick-suppression.sensors", false);

        // Async Block Updates
        asyncBlockUpdatesEnabled = getBoolean("performance.async-block-updates", true);

        // === Security ===
        // Combat Log Prevention
        combatLogEnabled = getBoolean("security.combat-log.enabled", true);
        combatLogTagDuration = getInt("security.combat-log.tag-duration", 10);
        combatLogKillOnLogout = getBoolean("security.combat-log.kill-on-logout", true);

        // CPS Limit
        cpsLimitEnabled = getBoolean("security.cps-limit.enabled", true);
        cpsLimitMax = getInt("security.cps-limit.max", 20);

        // Reach Validation
        reachValidationEnabled = getBoolean("security.reach-validation", true);

        // Flight Detection Enhancement
        flightDetectionEnabled = getBoolean("security.flight-detection", true);

        // Exploit Logging
        exploitLoggingEnabled = getBoolean("security.exploit-logging", true);

        // === Quality of Life ===
        // Async Tab Complete
        asyncTabCompleteEnabled = getBoolean("qol.async-tab-complete", true);

        // Join Queue
        joinQueueEnabled = getBoolean("join-queue.enabled", false);
        joinQueueMaxSize = getInt("join-queue.max-size", 50);

        // Vanish Levels
        vanishLevelsEnabled = getBoolean("qol.vanish-levels", true);

        // Teleport Warmup
        teleportWarmupTicks = getInt("teleport-warmup-ticks", 60);

        // Maintenance Mode
        maintenanceModeEnabled = getBoolean("maintenance-mode.enabled", false);
        maintenanceModeMessage = getString("maintenance-mode.message", maintenanceModeMessage);

        // Player Data Backup
        playerDataBackupEnabled = getBoolean("qol.player-data-backup.enabled", true);
        playerDataBackupIntervalTicks = getInt("qol.player-data-backup.interval-ticks", 6000);

        Bukkit.getLogger().info("[BTCCore] Optimization Features initialized");
    }

    private static void initRpgOptimizations() {
        // Spawns & Events
        rpgVanillaSpawnsEnabled = getBoolean("rpg.vanilla-spawns.enabled", false);
        rpgWorldEventsEnabled = getBoolean("rpg.world-events.enabled", false);
        rpgWeatherTicksEnabled = getBoolean("rpg.weather-ticks.enabled", false);

        // Goal Selectors
        rpgOptimizedGoalSelectors = getBoolean("rpg.optimized-goal-selectors.enabled", true);
        rpgCollisionCap = getInt("rpg.collision-hard-cap", 2);

        // Native Anticheat (Sentinel)
        config.setComments("security.sentinel", java.util.List.of(
            "#######################################################################",
            "# NATIVE SENTINEL ENGINE (Replaces LightningGrim / PacketEvents)",
            "# Native NMS hit-box & latency Ghost caching for zero-overhead performance.",
            "#######################################################################"
        ));
        sentinelEnabled = getBoolean("security.sentinel.enabled", true);
        config.setComments("security.sentinel.max-reach-distance", java.util.List.of("Maximum allowed block distance for Reach hits before they trigger Sentinel violation buffers."));
        sentinelMaxReachDistance = getDouble("security.sentinel.max-reach-distance", 3.01);
        config.setComments("security.sentinel.max-speed-buffer", java.util.List.of("Velocity/Speed leeway allowance before hard setbacks trigger."));
        sentinelMaxSpeedBuffer = getDouble("security.sentinel.max-speed-buffer", 1.0);
        config.setComments("security.sentinel.mysql-logging.enabled", java.util.List.of("If enabled, requires valid SQL connection details. Pushes all Sentinel hits asynchronously to sentinel_violations table."));
        sentinelMysqlLogging = getBoolean("security.sentinel.mysql-logging.enabled", false);
        config.setComments("security.sentinel.auto-notify-admins", java.util.List.of("Broadcasts real-time chat alerts if a player violates checks. Staff need 'sentinel.admin' perm."));
        sentinelAutoNotifyAdmins = getBoolean("security.sentinel.auto-notify-admins", true);
        
        if (sentinelEnabled) {
            org.bukkit.Bukkit.getCommandMap().register("sentinel", "BTCCore", new dev.btc.core.security.SentinelCommand());
        }
        rpgRedstoneStaticGraphEnabled = getBoolean("rpg.redstone.static-graph.enabled", true);
        
        String redstoneWhitelistPath = "rpg.redstone.static-graph.whitelisted-worlds";
        config.setComments(redstoneWhitelistPath, java.util.List.of(
            "List of worlds where the Static Redstone Graph optimization is active.",
            "Supports wildcard '*' for prefix matching (e.g., 'plot_*' matches all plot worlds)."
        ));
        if (config.contains(redstoneWhitelistPath)) {
            rpgRedstoneStaticGraphWorlds = config.getStringList(redstoneWhitelistPath);
        } else {
            java.util.List<String> defaultWorlds = java.util.Arrays.asList("world_island", "redstone_plots");
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

    private static List<String> getList(String path, List<String> def) {
        config.addDefault(path, def);
        return config.getStringList(path);
    }
}

