package dev.btc.core.config;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
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

    /**
     * Log4j is used instead of {@link Bukkit#getLogger()} because {@link #load(File)} runs from
     * {@code net.minecraft.server.Main} â€” before the Bukkit server instance exists.
     */
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("BTCCore");

    private static File configFile;
    private static YamlConfiguration config;
    private static boolean loaded;

    /** Schema marker read from {@code config-version}; carried so future migrations can branch on it. */
    public static int version;

    // Slime World Optimization
    public static boolean asyncChunkLoading = true;

    // === ASYNC PROCESSING (Leaf Port) ===
    public static boolean asyncEntityTrackerEnabled = false;
    public static int asyncEntityTrackerThreads = 0;
    public static boolean asyncPathfindingEnabled = false;
    public static int asyncPathfindingMaxThreads = 0;
    public static int asyncPathfindingQueueSize = 0;
    public static PathfindTaskRejectPolicy asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.FLUSH_ALL;
    public static boolean asyncMobSpawningEnabled = true;

    // === ENTITY OPTIMIZATIONS (Pufferfish Port) ===
    public static boolean dearEnabled = false;
    public static int dearStartDistance = 12;
    public static int dearStartDistanceSquared = 12 * 12;
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

    // Packet Limiter & Spam Limiter
    public static int spamLimiterIncomingPacketThreshold = -1;
    public static double packetLimiterAllPacketsMaxRate = 500.0;
    public static double packetLimiterAllPacketsInterval = 7.0;
    public static String packetLimiterKickMessage = "<red>Exceeded packet rate";

    // === RPG OPTIMIZATIONS (Typewriter) ===
    public static boolean rpgVanillaSpawnsEnabled = false;
    public static boolean rpgWeatherTicksEnabled = false;

    // Redstone compiler
    public static boolean redstoneCompilerEnabled = false;
    public static List<String> redstoneCompilerWorlds = new ArrayList<>(List.of("redstone_plots"));
    public static int redstoneCompilerActivityThreshold = 32;
    public static int redstoneCompilerActivityWindowTicks = 20;
    public static int redstoneCompilerRecompileDelayTicks = 60;
    public static int redstoneCompilerMaxNodes = 16384;
    public static int redstoneCompilerMaxExtent = 128;

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

    // Pre-Damage Calculation Event
    public static boolean preDamageEventEnabled = true;

    // MSPT Monitoring
    public static int msptThreshold = 40;

    // === ZERO FEATURES ===
    // A zero-feature switches a whole subsystem off. For advancements and recipes that means
    // *no* advancement / *no* recipe is loaded at all, custom ones included. To only strip the
    // vanilla content while keeping the subsystem usable for custom content, use the
    // vanilla-content purge below instead.
    public static boolean zfAdvancementsEnabled = false;
    public static boolean zfRecipesEnabled = false;
    public static boolean zfStatsEnabled = false;
    public static boolean zfLightEngineEnabled = false;
    public static boolean zfCollisionsEnabled = false;
    public static boolean zfCrammingEnabled = false;
    public static boolean zfBlockUpdatesEnabled = false;
    public static boolean zfSleepTickEnabled = false;
    public static boolean zfForceVoidGenerator = false;
    /** Default world scope for every zero-feature; empty means "every world". */
    public static List<String> zfWorldPatterns = List.of();
    /** Per-feature world scope; when a feature has an entry here it wins over {@link #zfWorldPatterns}. */
    public static java.util.Map<String, List<String>> zfWorldOverrides = java.util.Map.of();

    // === VANILLA CONTENT PURGE ===
    // Independent from the zero-features: drops the content shipped in the "minecraft" namespace
    // at load time and keeps every other namespace (datapacks, plugins) untouched.
    public static boolean purgeVanillaAdvancements = false;
    public static boolean purgeVanillaRecipes = false;
    /**
     * Keeps the vanilla recipes whose behaviour lives in server code rather than in data.
     *
     * <p>These are {@code ComplexRecipe} implementations: dyeing leather, assembling fireworks,
     * repairing a pair of tools, duplicating a banner or a book. The Bukkit API exposes no
     * constructor for any of them, so once purged they cannot be recreated by a plugin — not as
     * data, not through {@code CraftingSection}. They are also generic behaviours rather than
     * progression items, so there is nothing to gate behind a level.
     */
    public static boolean preserveSpecialRecipes = true;
    /** Extra {@code minecraft:} recipe paths to spare, on top of {@link #SPECIAL_RECIPE_PATHS}. */
    public static java.util.Set<String> preservedRecipePaths = java.util.Set.of();

    /**
     * Special recipe paths to purge anyway, carved out of {@link #SPECIAL_RECIPE_PATHS}.
     *
     * {@link #preserveSpecialRecipes} is all-or-nothing, which forces a server wanting its own
     * fireworks to also give up dyeing, banner duplication and tool repair. This list drops single
     * paths out of the sparing set, so the other thirty keep working.
     */
    public static java.util.Set<String> purgedSpecialRecipePaths = java.util.Set.of();

    // Vanilla loot purge.
    //
    // Loot tables do not only produce drops. The same registry drives fishing, shearing, structure
    // chests, villager gifts, archaeology, brushing, spawner contents and mob equipment - fourteen
    // directories in 26.2, of which only two are drops. Purging the lot would leave a server that
    // cannot fish, cannot shear a sheep and generates empty dungeons, and nothing in the log would
    // say why.
    //
    // Hence the shape of this switch: it purges nothing by default, and even switched on it only
    // touches the prefixes listed below. Adding a category is a deliberate act, and a category that
    // a future Minecraft version invents cannot be swept away by accident.
    /** Master switch for dropping {@code minecraft:} loot tables at load time. */
    public static boolean purgeVanillaLoot = false;
    /**
     * The loot table directories the purge is allowed to touch.
     *
     * <p>Defaults to the two the BTC drop API covers: with a provider or a transformer registered,
     * blocks and entities get their drops from the plugin, so the vanilla tables behind them are
     * dead weight. Everything else keeps working.
     */
    public static java.util.List<String> purgedLootPrefixes = java.util.List.of("blocks/", "entities/");
    /** Exact {@code minecraft:} loot table paths to spare, whatever the prefixes say. */
    public static java.util.Set<String> preservedLootPaths = java.util.Set.of();

    // Workstation blocking.
    //
    // These four stations have their behaviour hard-coded in server code, so no recipe purge can
    // reach them: the grindstone, the loom and the cartography table build their result inside
    // their own menu, and the composter's table lives in ComposterBlock.COMPOSTABLES. Blocking
    // them at the block is the only way to cover every path, the hopper included.
    //
    // Every switch is off by default. A server running this fork keeps vanilla behaviour until it
    // asks for the block, so a different game mode - or a different server entirely - is never
    // forced into a progression system it does not use.
    /** Refuse to open the grindstone menu. */
    public static boolean blockGrindstone = false;
    /** Refuse to open the loom menu. */
    public static boolean blockLoom = false;
    /** Refuse to open the cartography table menu. */
    public static boolean blockCartographyTable = false;
    /** Refuse every composter fill, by player or by hopper. Emptying a full one still works. */
    public static boolean blockComposter = false;

    /**
     * The vanilla recipes that no plugin can rebuild, by path within the {@code minecraft} namespace.
     *
     * <p>Kept as literal identifiers rather than derived from the recipe type: the purge runs while
     * the datapacks are still being read, where only the identifier is cheaply available.
     */
    public static final java.util.Set<String> SPECIAL_RECIPE_PATHS = java.util.Set.of(
            // crafting_special_bannerduplicate (16)
            "black_banner_duplicate", "blue_banner_duplicate", "brown_banner_duplicate",
            "cyan_banner_duplicate", "gray_banner_duplicate", "green_banner_duplicate",
            "light_blue_banner_duplicate", "light_gray_banner_duplicate", "lime_banner_duplicate",
            "magenta_banner_duplicate", "orange_banner_duplicate", "pink_banner_duplicate",
            "purple_banner_duplicate", "red_banner_duplicate", "white_banner_duplicate",
            "yellow_banner_duplicate",
            // crafting_special_* (7)
            "book_cloning", "firework_rocket", "firework_star", "firework_star_fade",
            "map_extending", "repair_item", "shield_decoration",
            // crafting_dye (6) — new in 26.2
            "leather_boots_dyed", "leather_chestplate_dyed", "leather_helmet_dyed",
            "leather_horse_armor_dyed", "leather_leggings_dyed", "wolf_armor_dyed",
            // crafting_imbue (1) — new in 26.2
            "tipped_arrow",
            // crafting_decorated_pot (1)
            "decorated_pot");

    // === WORLD / DIMENSION GATING ===
    /** When true, no NETHER and no THE_END world is generated or loaded, whatever the other configs say. */
    public static boolean overworldOnly = false;

    // Native Anticheat (Sentinel)
    public static boolean sentinelEnabled = true;
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
     * Checks if the redstone compiler may compile circuits in a specific world.
     */
    public static boolean isRedstoneCompilerEnabledFor(String worldName) {
        if (!redstoneCompilerEnabled) return false;
        return dev.btc.core.util.WorldPatternMatcher.matchesAny(worldName, redstoneCompilerWorlds);
    }

    /**
     * Centralized gateway for Zero Features short-circuits.
     *
     * <p>Note: {@code "recipes"} and {@code "advancements"} act on a single server-wide registry
     * at load time, so they are enforced server-wide and ignore any world scope; this method still
     * answers for them so the public API stays uniform.
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
        if ("advancements".equals(feature) || "recipes".equals(feature)) return true;

        List<String> scope = zfWorldOverrides.getOrDefault(feature, zfWorldPatterns);
        if (scope.isEmpty()) return true;
        if (worldName == null) return true;
        return dev.btc.core.util.WorldPatternMatcher.matchesAny(worldName, scope);
    }

    /**
     * Decides whether an advancement must be dropped while the datapacks are being read.
     *
     * @param namespace namespace of the advancement identifier
     */
    public static boolean shouldDropAdvancement(String namespace) {
        if (zfAdvancementsEnabled) return true;
        return purgeVanillaAdvancements && "minecraft".equals(namespace);
    }

    /**
     * Decides whether a recipe must be dropped while the datapacks are being read.
     *
     * @param namespace namespace of the recipe identifier
     * @param path      path of the recipe identifier, used to spare the code-backed recipes
     */
    public static boolean shouldDropRecipe(String namespace, String path) {
        // The zero-feature kills the whole subsystem: nothing survives it, not even a whitelist.
        if (zfRecipesEnabled) return true;
        if (!purgeVanillaRecipes || !"minecraft".equals(namespace)) return false;
        if (preserveSpecialRecipes && SPECIAL_RECIPE_PATHS.contains(path) && !purgedSpecialRecipePaths.contains(path)) return false;
        return !preservedRecipePaths.contains(path);
    }

    /**
     * Decides whether a loot table must be dropped while the datapacks are being read.
     *
     * <p>Symmetrical to {@link #shouldDropRecipe}, with one difference that matters: there is no
     * zero-feature shortcut. Killing the loot registry outright would take fishing, shearing and
     * structure chests down with the drops, and no whitelist could bring them back.
     *
     * @param namespace namespace of the loot table identifier
     * @param path      path of the loot table identifier, for instance {@code blocks/stone}
     */
    public static boolean shouldDropLootTable(String namespace, String path) {
        if (!purgeVanillaLoot || !"minecraft".equals(namespace)) return false;
        if (preservedLootPaths.contains(path)) return false;
        for (String prefix : purgedLootPrefixes) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Whether this server drops recipes at load time, for any reason.
     *
     * <p>A saved player recipe book keeps the identifiers it knew at save time. Once we purge
     * recipes, a returning player's book is full of identifiers the registry no longer holds, and
     * {@code ServerRecipeBook} logs one error per entry on every join. Those entries are dropped
     * regardless, so the noise is the only problem â€” and it is expected, not exceptional.
     */
    public static boolean isDroppingRecipes() {
        return zfRecipesEnabled || purgeVanillaRecipes;
    }

    /**
     * Value advertised to the client in {@code ClientboundLoginPacket#enforcesSecureChat}.
     *
     * <p>A {@code false} here is what makes the vanilla client raise its "chat messages can't be
     * verified" warning. FreedomChat rewrites every player message into a disguised (unsigned)
     * message, so the client never has a signature to verify and the flag can safely be advertised
     * as enforced.
     *
     * @param serverEnforcesSecureProfile the value the server itself computed
     */
    public static boolean advertisesSecureChat(boolean serverEnforcesSecureProfile) {
        if (!freedomChatEnabled) return serverEnforcesSecureProfile;
        return freedomChatEnforceSecureChat;
    }

    /** True when a world of this Bukkit environment name must never be generated nor loaded. */
    public static boolean isDimensionBlocked(String environmentName) {
        if (!overworldOnly) return false;
        return "NETHER".equals(environmentName) || "THE_END".equals(environmentName);
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

    /**
     * Reads btccore.yml and fills every static field.
     *
     * <p>Called from {@code net.minecraft.server.Main} <em>before</em> {@code WorldLoader.load},
     * because datapack-driven content (advancements, recipes) is read at that point: a config
     * loaded any later would be seen as "all defaults" by those loaders. Nothing in here may touch
     * the Bukkit server instance â€” that part lives in {@link #applyServerBound()}.
     *
     * <p>Idempotent: a second call is ignored, so the bundled plugin can safely ask for it as a
     * fallback when the early hook did not run.
     *
     * @param file target config file, {@code null} for the default {@code btccore.yml}
     */
    public static void load(File file) {
        if (loaded) return;
        loaded = true;
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
                LOGGER.warn("[BTCCore] Could not deploy default btccore.yml: " + e);
            }
        }

        config = new YamlConfiguration();
        config.options().parseComments(true);
        try {
            config.load(file);
        } catch (IOException e) {
            // Config doesn't exist yet
        } catch (InvalidConfigurationException e) {
            LOGGER.error("Invalid btccore.yml configuration!", e);
        }

        config.options().copyDefaults(true);
        version = getInt("config-version", 1);

        // Slime World
        asyncChunkLoading = getBoolean("slime-world.async-chunk-loading", true);

        // Async Processing
        initAsyncProcessing();

        // Freedom Chat
        freedomChatEnabled = getBoolean("freedom-chat.enabled", freedomChatEnabled);
        freedomChatRewriteChat = getBoolean("freedom-chat.rewrite-chat", freedomChatRewriteChat);
        freedomChatEnforceSecureChat = getBoolean("freedom-chat.enforce-secure-chat", freedomChatEnforceSecureChat);
        freedomChatPreventChatReports = getBoolean("freedom-chat.prevent-chat-reports", freedomChatPreventChatReports);

        // Spam Limiter
        spamLimiterIncomingPacketThreshold = getInt("spam-limiter.incoming-packet-threshold", spamLimiterIncomingPacketThreshold);

        // Packet Limiter
        packetLimiterAllPacketsMaxRate = getDouble("packet-limiter.all-packets.max-rate", packetLimiterAllPacketsMaxRate);
        packetLimiterAllPacketsInterval = getDouble("packet-limiter.all-packets.interval", packetLimiterAllPacketsInterval);
        packetLimiterKickMessage = getString("packet-limiter.kick-message", packetLimiterKickMessage);

        // World / dimension gating
        overworldOnly = getBoolean("world.overworld-only", false);

        // Performance & Security & QoL
        initOptimizationFeatures();
        initRpgOptimizations();
        initZeroFeatures();

        // Keep the freshly deployed annotated template intact; only re-save existing
        // configs (e.g. to persist new defaults introduced by an upgrade).
        if (!freshlyDeployed) {
            save();
        }
    }

    /**
     * Applies the parts of the configuration that need a live Bukkit server: the Paper global
     * config bridge and the Sentinel command. Called from the bundled plugin's {@code onLoad}.
     */
    public static void applyServerBound() {
        load(null);

        if (asyncMobSpawningEnabled && GlobalConfiguration.get() != null) {
            // Paper handles async mob spawning when per-player-mob-spawns is enabled
            LOGGER.info("[BTCCore] Async mob spawning delegated to Paper's per-player-mob-spawn system");
        }

        if (sentinelEnabled) {
            Bukkit.getCommandMap().register("sentinel", "BTCCore", new dev.btc.core.security.SentinelCommand());
        }

        applyToPaper();
    }

    private static void save() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            LOGGER.error("Could not save btccore.yml", e);
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
            LOGGER.info("[BTCCore] Packet spam limiter disabled (incoming-packet-threshold < 0)");
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

        LOGGER.info("[BTCCore] Packet limiter: " + packetLimiterAllPacketsMaxRate
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
            LOGGER.info("[BTCCore] Using " + asyncEntityTrackerThreads + " threads for Async Entity Tracker");
        }

        asyncPathfindingEnabled = getBoolean("async.pathfinding.enabled", false);
        asyncPathfindingMaxThreads = getInt("async.pathfinding.max-threads", 0);
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
            LOGGER.info("[BTCCore] Using " + asyncPathfindingMaxThreads + " threads for Async Pathfinding");
        }

        asyncMobSpawningEnabled = getBoolean("async.mob-spawning.enabled", true);
        if (asyncMobSpawningEnabled) {
            LOGGER.info("[BTCCore] Async Mob Spawning enabled (requires per-player-mob-spawns in Paper)");
        }

        initEntityOptimizations();
    }

    private static void initEntityOptimizations() {
        dearEnabled = getBoolean("dab.enabled", false);
        dearStartDistance = getInt("dab.start-distance", 12);
        dearStartDistanceSquared = dearStartDistance * dearStartDistance;
        if (dearEnabled) {
            LOGGER.info("[BTCCore] DAB (Dynamic Activation of Brain) enabled - start-distance: " + dearStartDistance);
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

        // Pre-Damage Calculation Event
        preDamageEventEnabled = getBoolean("performance.pre-damage-event.enabled", true);
        if (preDamageEventEnabled) {
            LOGGER.info("[BTCCore] PreDamageCalculationEvent enabled â€” plugins can intercept damage before armor calculation");
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

        LOGGER.info("[BTCCore] Optimization Features initialized");
    }

    private static void initRpgOptimizations() {
        rpgVanillaSpawnsEnabled = getBoolean("rpg.vanilla-spawns.enabled", false);
        rpgWeatherTicksEnabled = getBoolean("rpg.weather-ticks.enabled", false);

        // Sentinel
        sentinelEnabled = getBoolean("security.sentinel.enabled", true);
        sentinelMysqlLogging = getBoolean("security.sentinel.mysql-logging.enabled", false);
        sentinelMysqlHost = getString("security.sentinel.mysql-logging.host", "localhost");
        sentinelMysqlPort = getInt("security.sentinel.mysql-logging.port", 3306);
        sentinelMysqlDatabase = getString("security.sentinel.mysql-logging.database", "btccore");
        sentinelMysqlUsername = getString("security.sentinel.mysql-logging.username", "root");
        sentinelMysqlPassword = getString("security.sentinel.mysql-logging.password", "");
        sentinelAutoNotifyAdmins = getBoolean("security.sentinel.auto-notify-admins", true);

        // Redstone compiler
        redstoneCompilerEnabled = getBoolean("rpg.redstone.compiler.enabled", true);
        String redstoneWhitelistPath = "rpg.redstone.compiler.whitelisted-worlds";
        if (config.contains(redstoneWhitelistPath)) {
            redstoneCompilerWorlds = config.getStringList(redstoneWhitelistPath);
        } else {
            List<String> defaultWorlds = List.of("world_island", "redstone_plots");
            config.set(redstoneWhitelistPath, defaultWorlds);
            redstoneCompilerWorlds = defaultWorlds;
        }
        redstoneCompilerActivityThreshold = getInt("rpg.redstone.compiler.activity-threshold", 32);
        redstoneCompilerActivityWindowTicks = getInt("rpg.redstone.compiler.activity-window-ticks", 20);
        redstoneCompilerRecompileDelayTicks = getInt("rpg.redstone.compiler.recompile-delay-ticks", 60);
        redstoneCompilerMaxNodes = getInt("rpg.redstone.compiler.max-nodes", 16384);
        redstoneCompilerMaxExtent = getInt("rpg.redstone.compiler.max-extent", 128);

        LOGGER.info("[BTCCore] RPG Optimizations initialized");
    }

    /** Zero-features that are evaluated per world, in config order. */
    private static final List<String> WORLD_SCOPED_FEATURES = List.of(
            "stats", "light_engine", "collisions", "cramming", "block_updates", "sleep_tick", "void_generator");

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
        zfWorldPatterns = getList("zero-features.worlds", List.of("zero_*"));

        // Optional per-feature scope: zero-features.worlds-per-feature.<feature>: ["*"] / ["palier1"]
        java.util.Map<String, List<String>> overrides = new java.util.HashMap<>();
        for (String feature : WORLD_SCOPED_FEATURES) {
            String path = "zero-features.worlds-per-feature." + feature.replace('_', '-');
            if (config.contains(path)) {
                overrides.put(feature, List.copyOf(config.getStringList(path)));
            }
        }
        zfWorldOverrides = java.util.Map.copyOf(overrides);

        // Vanilla content purge â€” independent from the zero-features above.
        purgeVanillaAdvancements = getBoolean("vanilla-content.purge-advancements", false);
        purgeVanillaRecipes = getBoolean("vanilla-content.purge-recipes", false);
        preserveSpecialRecipes = getBoolean("vanilla-content.preserve-special-recipes", true);
        preservedRecipePaths = java.util.Set.copyOf(config.getStringList("vanilla-content.preserve-recipes"));

        purgedSpecialRecipePaths = java.util.Set.copyOf(config.getStringList("vanilla-content.purge-special-recipes"));

        purgeVanillaLoot = getBoolean("vanilla-content.purge-loot", false);
        java.util.List<String> configuredLootPrefixes = config.getStringList("vanilla-content.purge-loot-prefixes");
        // An empty list in the file means "not configured", not "purge nothing" - a server that
        // switched the purge on and left the list alone still expects the documented default.
        if (!configuredLootPrefixes.isEmpty()) {
            purgedLootPrefixes = java.util.List.copyOf(configuredLootPrefixes);
        }
        preservedLootPaths = java.util.Set.copyOf(config.getStringList("vanilla-content.preserve-loot"));

        blockGrindstone = getBoolean("workstations.block-grindstone", false);
        blockLoom = getBoolean("workstations.block-loom", false);
        blockCartographyTable = getBoolean("workstations.block-cartography-table", false);
        blockComposter = getBoolean("workstations.block-composter", false);

        int spared = purgeVanillaRecipes
                ? (preserveSpecialRecipes ? SPECIAL_RECIPE_PATHS.size() : 0) + preservedRecipePaths.size()
                : 0;
        LOGGER.info("[BTCCore] Zero Features initialized"
                + " (vanilla purge: advancements=" + purgeVanillaAdvancements + ", recipes=" + purgeVanillaRecipes
                + ", recipes spared=" + spared
                + ", loot=" + purgeVanillaLoot + (purgeVanillaLoot ? " " + purgedLootPrefixes : "") + ")");
    }
}
