package dev.btc.core.config;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

import dev.btc.core.security.exploit.PacketLimitOverrides;
import dev.btc.core.world.PortalPolicy;
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
    // At a stop, the last saves of the leased Slime worlds written side by side by the save pool.
    public static boolean slimeParallelShutdownSave = false;

    // === ASYNC PROCESSING ===
    public static boolean asyncMobSpawningEnabled = true;
    // Parallel entity tracker (DivineMC port): read-only visibility scan on a pool, joined inside the tick.
    public static boolean parallelTrackerEnabled = false;
    public static int parallelTrackerThreads = 0; // 0 = auto
    // Async pathfinding (Petal/Kaiiju/Leaf port): A* on a pool, request and result on the owner thread.
    public static boolean asyncPathfindingEnabled = false;
    public static int asyncPathfindingThreads = 0; // 0 = auto
    public static boolean asyncPlayerDataSaveEnabled = false;
    // Parallel world ticking (DivineMC port): one tick thread per world behind a barrier. Read once at startup.
    public static boolean parallelWorldTickingEnabled = false;
    public static int parallelWorldTickingThreads = 0; // 0 = auto
    // World quarantine: a Slime world whose tick threw is unloaded instead of stopping the server.
    public static boolean worldQuarantineEnabled = false;
    public static boolean worldQuarantineSaveBeforeUnload = true;
    public static int worldQuarantineMaxPerMinute = 3;
    // World metrics: the tick time of each world over a window, for /btccore worlds. Read when a world is created.
    public static boolean worldMetricsEnabled = false;
    public static int worldMetricsTop = 10;
    public static String worldMetricsDisabledMessage = "<yellow>world.metrics.enabled is false: no world is measured.";
    public static String worldMetricsHeaderMessage = "<gold>Worlds by tick cost <gray>(average and worst of the last <window> ticks)";
    public static String worldMetricsLineMessage = "<yellow><world><gray>: <white><average> ms <gray>avg, <white><worst> ms <gray>worst, <white><chunks> <gray>chunks, <white><entities> <gray>entities, <white><players> <gray>players";
    // World leak diagnostic: the worlds that left the server and are still in memory, for /btccore leaks.
    public static boolean worldLeakDiagnosticEnabled = false;
    public static int worldLeakDiagnosticCheckAfterSeconds = 300;
    public static String worldLeakDiagnosticDisabledMessage = "<yellow>world.leak-diagnostic.enabled is false: no unloaded world is followed.";
    public static String worldLeakDiagnosticNoneMessage = "<green>No unloaded world is still in memory.";
    public static String worldLeakDiagnosticLineMessage = "<yellow><world><gray>: unloaded <white><age> s <gray>ago, <white><collections> <gray>garbage collection(s) since, still in memory";
    public static String worldLeakDiagnosticMoreMessage = "<gray>and <white><more> <gray>more recent unload(s) not listed";
    // Entity census: the entities of each world counted as they come and go, and the caps of BTCCoreAPI#setEntityCap. Read when a world is created.
    public static boolean worldEntityCensusEnabled = false;
    // Freeze at rest: a world with no player, no ticket and no chunk loaded stops ticking. Read once at startup: restart to change.
    public static boolean worldFreezeAtRestEnabled = false;
    // Warm: a world with no player, not the main world, ticks once every tick-interval ticks; the tick after makes up
    // for at most max-catch-up-ticks of the skipped ones (random ticks, lit furnaces). Read once at startup: restart to change.
    public static boolean worldWarmEnabled = false;
    public static int worldWarmTickInterval = 20;
    public static int worldWarmMaxCatchUpTicks = 0;
    /** Highest tick-interval and max-catch-up-ticks of a warm world: one minute. */
    public static final int WORLD_WARM_MAX_TICKS = 1200;
    /** {@code stats-sql}, checked; null while vanilla statistics stay on their files. Read once by the server at startup. */
    public static dev.btc.core.stats.sql.@org.jspecify.annotations.Nullable StatsSqlConfig statsSql;

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
    /**
     * Hoppers that found nothing to move sleep until something they depend on changes
     * ({@link dev.btc.core.performance.HopperSleep}). Independent of the throttle above, which still applies.
     */
    public static boolean sleepingBlockEntitiesEnabled = false;
    /** The safety net: a sleeping hopper retries at the latest every this many ticks. */
    public static int sleepingBlockEntitiesWakeIntervalTicks = 200;

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
    /** Off: Paper's per-packet limits are left as they are. See {@link PacketLimitOverrides}. */
    public static boolean packetLimiterOverridesEnabled = false;

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
    // max-entities is a LOCAL density: how many pushable entities an entity may have around it
    // before its collision scan starts being spread over several ticks. Below that it scans every
    // tick. It is not a server-wide or per-world entity count.
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

    // Batched Inventory Updates
    // Coalesces slot updates per (container, slot) for one player tick, so a menu that rewrites
    // its whole layout several times in a tick costs one packet per slot instead of one per
    // rewrite. Still opt-in: the previous implementation dropped every slot packet outright, so
    // this one stays off until it has been validated in game.
    public static boolean batchedInventoryUpdates = false;

    // Chunk Prefetch
    public static boolean chunkPrefetchEnabled = true;

    // Per-World Tick Rate
    public static boolean perWorldTickRateEnabled = false;
    public static int emptyWorldTPS = 10;

    // Per-World View / Simulation Distance
    public static boolean perWorldDistancesEnabled = false;
    public static List<WorldDistanceRule> perWorldDistanceRules = List.of();

    /**
     * One {@code performance.per-world-distances.rules} entry.
     *
     * <p>A distance of {@code -1} means "leave the world alone on that axis", so a rule can lower
     * the simulation distance of island worlds without touching how far their owners can see.
     */
    public record WorldDistanceRule(List<String> worlds, int viewDistance, int simulationDistance) {}

    // Projectile Pooling
    public static boolean projectilePoolingEnabled = true;

    // Per-entity tracking range (idea of SparklyPaper, rewritten): BTCCoreAPI#setEntityTrackingRange.
    public static boolean perEntityTrackingRangeEnabled = false;

    // AI sensors scanned with plain loops in place of streams (Leaf): same result, same order.
    public static boolean aiSensorLoopsEnabled = false;

    // Crops and farmland reading one block of soil per tick (idea of SparklyPaper, rewritten). Changes gameplay.
    public static boolean simpleFarmChecksEnabled = false;
    public static float simpleFarmChecksMoistGrowthSpeed = dev.btc.core.performance.SimpleFarmChecks.FIELD_MOIST_GROWTH_SPEED;
    public static float simpleFarmChecksDryGrowthSpeed = dev.btc.core.performance.SimpleFarmChecks.FIELD_DRY_GROWTH_SPEED;

    // The 18 collision shapes of a retracting piston built once (Lithium, via Leaf): same shapes.
    public static boolean pistonShapeCacheEnabled = false;

    // Network lot: one key per element, all off until measured (performance.network).
    public static boolean networkLazyNonFlushPackets = false;
    public static boolean networkFastVarInt = false;
    public static boolean networkSkipUnchangedMotion = false;
    public static boolean networkSkipEmptyEntityMoves = false;
    public static boolean networkSpreadLatencyUpdates = false;
    public static boolean networkSharedChunkPackets = false;
    public static boolean networkFilterEntityMotion = false;
    public static List<String> networkFilterEntityMotionAlwaysSend = List.of(
        "minecraft:eye_of_ender", "minecraft:squid", "minecraft:glow_squid", "minecraft:shulker_bullet", "minecraft:fishing_bobber");

    // Raytrace culling: entities behind opaque blocks are not tracked (performance.raytrace-culling).
    public static boolean raytraceCullingEnabled = false;
    public static int raytraceCullingRange = 64;
    public static int raytraceCullingIntervalTicks = 2;
    public static int raytraceCullingSectionsPerTick = 16;
    public static int raytraceCullingThreads = 1;
    public static List<String> raytraceCullingAlwaysVisible = List.of(
        "minecraft:player", "minecraft:ender_dragon", "minecraft:wither", "minecraft:warden", "minecraft:end_crystal",
        "minecraft:item_display", "minecraft:block_display", "minecraft:text_display");

    // Vanilla Tick Suppression
    public static boolean vanillaTickSuppressionAi = false;
    public static boolean vanillaTickSuppressionBrain = false;
    public static boolean vanillaTickSuppressionSensors = false;

    // Pre-Damage Calculation Event
    public static boolean preDamageEventEnabled = true;

    // MSPT Monitoring
    public static int msptThreshold = 40;

    // === BREAK SPEED ===
    // Off by default: a server that has not bound a statistics provider and does not want the tool
    // affinities gains nothing from paying a packet per block entered.
    public static boolean breakSpeedEnabled = false;
    public static boolean breakSpeedAffinityEnabled = true;
    public static double breakSpeedOffDomainFactor = 0.4;
    public static boolean breakSpeedHardnessCorrectionsEnabled = true;
    /**
     * Kill switch of the server-driven digging mode (hardness-0 blocks timed by the server). Off by
     * default: with it off, a {@code ServerDriven} answer is a decline and no client view is changed.
     */
    public static boolean serverDrivenEnabled = false;
    /**
     * @deprecated The tier curve now lives in the bound {@code BreakSpeedProvider} (RPGCore, editable
     *             from the web editor); {@code onStartDestroy} never read this value. Still parsed
     *             so an existing {@code mining.break-speed.tier-curve} key stays readable, and only
     *             {@code BTCCoreAPI#breakSpeedForTier} still serves it.
     */
    @Deprecated(since = "26.3")
    public static dev.btc.core.mining.TierCurve breakSpeedTierCurve =
        new dev.btc.core.mining.TierCurve(dev.btc.core.mining.TierCurve.DEFAULT);

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

    // === PORTAL POLICY ===
    // One immutable snapshot, replaced whole at load: a region thread never sees half a policy.
    private static volatile PortalPolicy portalPolicy = PortalPolicy.DEFAULT;

    /** The portal policy read from {@code portals.*}; observing, and refusing nothing, by default. */
    public static PortalPolicy portals() {
        return portalPolicy;
    }

    // Native Anticheat (Sentinel) — its settings live in anticheat.yml, see AnticheatConfig.
    // Only what is NOT a detection setting stays here.

    // Combat Log — a gameplay rule (kill on logout), deliberately not an integrity setting.
    public static boolean combatLogEnabled = true;
    public static int combatLogTagDuration = 10;
    public static boolean combatLogKillOnLogout = true;

    // CPS Limit — read from an NMS patch (minecraft-patches/features/0003-BTC-CORE-hooks.patch).
    // Moves to anticheat.yml with the phase 3 patch work, so patch and config are verified together.
    public static boolean cpsLimitEnabled = true;
    public static int cpsLimitMax = 20;
    // Ceiling for the arm swings of a dig (a digging client swings once per tick, 20/s, the normal
    // ceiling): higher than cpsLimitMax so jitter is not punished, but finite so a flood is.
    public static int cpsLimitMiningMax = 30;

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
    /** Copies kept for each player; 0 (the default) keeps them all. */
    public static int playerDataBackupKeepLast = 0;

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
        // Empty world: tick at reduced rate.
        // The phase is offset per world name. A bare "currentTick % tickInterval" would line every
        // empty world up on the same due tick, so a server holding fifty empty island worlds would
        // tick all fifty together and nothing in between — the same total work, delivered as a
        // spike. Hashing the world name spreads them across the interval.
        // Floored at 1: an empty-world-tps above 20 would otherwise make this interval 0 and the
        // modulo below throw.
        int tickInterval = Math.max(1, 20 / Math.max(1, emptyWorldTPS));
        return Math.floorMod(currentTick + worldName.hashCode(), tickInterval) == 0;
    }

    /**
     * First {@code performance.per-world-distances} rule matching this world.
     *
     * <p>Rules are evaluated in config order, first match wins, so a specific world can be carved
     * out by putting it above a wildcard rule.
     *
     * @return the matching rule, or {@code null} when the feature is off or no rule covers the world
     */
    public static WorldDistanceRule distancesFor(String worldName) {
        if (!perWorldDistancesEnabled || worldName == null) return null;
        for (WorldDistanceRule rule : perWorldDistanceRules) {
            if (dev.btc.core.util.WorldPatternMatcher.matchesAny(worldName, rule.worlds())) return rule;
        }
        return null;
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
        // Kept by the serializer itself, which lives in the core module and reads it at each save.
        com.infernalsuite.asp.serialization.slime.SlimeSerializer.reuseEncodedSections =
                getBoolean("slime-world.save.reuse-encoded-sections", false);
        slimeParallelShutdownSave = getBoolean("slime-world.save.parallel-shutdown", false);
        com.infernalsuite.asp.skeleton.SkeletonCloning.shareSections = getBoolean("slime-world.clone.share-sections", false);

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
        packetLimiterOverridesEnabled = getBoolean("packet-limiter.overrides-enabled", false);
        if (!config.isConfigurationSection("packet-limiter.overrides")) {
            config.createSection("packet-limiter.overrides", PacketLimitOverrides.STARTING_VALUES);
        }

        // World / dimension gating
        overworldOnly = getBoolean("world.overworld-only", false);
        worldQuarantineEnabled = getBoolean("world.quarantine.enabled", false);
        worldQuarantineSaveBeforeUnload = getBoolean("world.quarantine.save-before-unload", worldQuarantineSaveBeforeUnload);
        worldQuarantineMaxPerMinute = getInt("world.quarantine.max-per-minute", worldQuarantineMaxPerMinute);
        if (worldQuarantineEnabled) {
            LOGGER.info("[BTCCore] World quarantine enabled (a Slime world whose tick throws is unloaded, "
                    + (worldQuarantineSaveBeforeUnload ? "after a save" : "without saving") + "; the server stops past "
                    + worldQuarantineMaxPerMinute + " per minute)");
        }
        worldMetricsEnabled = getBoolean("world.metrics.enabled", false);
        worldMetricsTop = getInt("world.metrics.top", worldMetricsTop);
        worldMetricsDisabledMessage = getString("world.metrics.messages.disabled", worldMetricsDisabledMessage);
        worldMetricsHeaderMessage = getString("world.metrics.messages.header", worldMetricsHeaderMessage);
        worldMetricsLineMessage = getString("world.metrics.messages.line", worldMetricsLineMessage);
        worldLeakDiagnosticEnabled = getBoolean("world.leak-diagnostic.enabled", false);
        worldLeakDiagnosticCheckAfterSeconds = Math.max(1, getInt("world.leak-diagnostic.check-after-seconds", worldLeakDiagnosticCheckAfterSeconds));
        worldLeakDiagnosticDisabledMessage = getString("world.leak-diagnostic.messages.disabled", worldLeakDiagnosticDisabledMessage);
        worldLeakDiagnosticNoneMessage = getString("world.leak-diagnostic.messages.none", worldLeakDiagnosticNoneMessage);
        worldLeakDiagnosticLineMessage = getString("world.leak-diagnostic.messages.line", worldLeakDiagnosticLineMessage);
        worldLeakDiagnosticMoreMessage = getString("world.leak-diagnostic.messages.more", worldLeakDiagnosticMoreMessage);
        worldEntityCensusEnabled = getBoolean("world.entity-census.enabled", false);
        worldFreezeAtRestEnabled = getBoolean("world.freeze-at-rest.enabled", false);
        worldWarmTickInterval = Math.clamp(getInt("world.freeze-at-rest.warm.tick-interval", 20), 1, WORLD_WARM_MAX_TICKS);
        worldWarmMaxCatchUpTicks = Math.clamp(getInt("world.freeze-at-rest.warm.max-catch-up-ticks", 0), 0, WORLD_WARM_MAX_TICKS);
        worldWarmEnabled = getBoolean("world.freeze-at-rest.warm.enabled", false);
        initPortals();

        // Performance & Security & QoL
        initOptimizationFeatures();
        initRpgOptimizations();
        initZeroFeatures();
        getBoolean("stats-sql.enabled", false); // written to a file that predates the option; the section is read below
        statsSql = dev.btc.core.stats.sql.StatsSql.configure(config, System::getenv, zfStatsEnabled);

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

        // AnticheatConfig.init() runs before this method (see SWPlugin#onLoad); reversing that order
        // would make this read a default instead of the operator's value.
        if (AnticheatConfig.sentinelEnabled) {
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

    /**
     * Integrity settings that moved to {@code anticheat.yml}, mapped to where they now live.
     *
     * <p>A move without a warning is a setting that silently stops applying — the failure this
     * platform exists to stop repeating. Each entry stays here until operators have migrated.
     */
    private static final java.util.Map<String, String> MOVED_INTEGRITY_KEYS = java.util.Map.of(
        "security.sentinel.enabled", "anticheat.yml -> sentinel.enabled",
        "security.sentinel.auto-notify-admins", "anticheat.yml -> sentinel.auto-notify-admins",
        "security.sentinel.mysql-logging", "anticheat.yml -> storage.* (PostgreSQL, no longer MySQL)",
        "security.exploit-logging", "anticheat.yml -> sentinel.file-logging",
        "security.reach-validation", "anticheat.yml -> checks.reach.enabled",
        "security.flight-detection", "removed: no flight check exists yet"
    );

    /** Warns, key by key, when a server still carries settings that have moved out of this file. */
    private static void warnIfLegacyIntegritySettings() {
        MOVED_INTEGRITY_KEYS.forEach((oldPath, newPath) -> {
            if (config.contains(oldPath)) {
                LOGGER.warn("[BTCCore] btccore.yml: '" + oldPath + "' is no longer read. Now: "
                        + newPath + ". Until it is migrated, that setting has no effect.");
            }
        });
    }

    /**
     * Settings deleted outright, mapped to why. Same rule as {@link #MOVED_INTEGRITY_KEYS}: a key
     * that stops applying is said out loud once, then dropped from the file by
     * {@link #warnRemovedKeys}.
     */
    private static final java.util.Map<String, String> REMOVED_KEYS = java.util.Map.of(
        "async.entity-tracker", "its hook ticked the entity tracker off the main thread and was removed",
        "async.pathfinding", "its hook ran mob navigation off the owning thread and was removed",
        "performance.scoreboard-optimization", "it was never wired to any hook",
        "performance.nbt-compression-cache", "it was never wired to any hook"
    );

    /**
     * One warning per removed setting the given file still carries, each naming its key.
     *
     * <p>Pure, and public only so the migration can be tested without a server.
     */
    public static List<String> removedKeyWarnings(org.bukkit.configuration.ConfigurationSection source) {
        List<String> warnings = new ArrayList<>();
        REMOVED_KEYS.forEach((path, why) -> {
            // Defaults ignored: only what the file itself carries counts as "still there".
            if (source.contains(path, true)) {
                warnings.add("[BTCCore] btccore.yml: '" + path + "' is no longer read: " + why
                        + ". The setting is ignored and has been removed from the file.");
            }
        });
        return warnings;
    }

    /**
     * Warns about every removed setting the file still carries, then deletes it from the loaded
     * config (and any parent section it leaves empty) so the next {@code save()} persists the
     * cleanup and the warning does not come back at every startup.
     *
     * <p>Public only so the migration can be tested without a server.
     */
    public static void warnRemovedKeys(org.bukkit.configuration.ConfigurationSection source,
                                       java.util.function.Consumer<String> sink) {
        removedKeyWarnings(source).forEach(sink);
        REMOVED_KEYS.keySet().forEach(path -> {
            if (source.contains(path, true)) {
                source.set(path, null);
                pruneEmptyParents(source, path);
            }
        });
    }

    private static void pruneEmptyParents(org.bukkit.configuration.ConfigurationSection root, String path) {
        int dot = path.lastIndexOf('.');
        while (dot > 0) {
            String parent = path.substring(0, dot);
            org.bukkit.configuration.ConfigurationSection section = root.getConfigurationSection(parent);
            if (section == null || !section.getKeys(false).isEmpty()) return;
            root.set(parent, null);
            dot = parent.lastIndexOf('.');
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

        // KICK because that is what Paper does with this limit; it never reads the action here.
        global.packetLimiter.allPackets = new PacketLimit(
                packetLimiterAllPacketsInterval,
                packetLimiterAllPacketsMaxRate,
                ViolateAction.KICK);

        if (packetLimiterKickMessage != null && !packetLimiterKickMessage.isEmpty()) {
            global.packetLimiter.kickMessage = MiniMessage.miniMessage()
                    .deserialize(packetLimiterKickMessage);
        }

        LOGGER.info("[BTCCore] " + PacketLimitOverrides.allPacketsLine(
                packetLimiterAllPacketsMaxRate, packetLimiterAllPacketsInterval));
        applyPacketLimitOverrides(global);
    }

    private static void applyPacketLimitOverrides(GlobalConfiguration global) {
        if (!packetLimiterOverridesEnabled) {
            LOGGER.info("[BTCCore] Packet limiter overrides are off (packet-limiter.overrides-enabled): "
                    + "Paper's own per-packet limits apply");
            return;
        }
        PacketLimitOverrides.Parsed effective = PacketLimitOverrides.effective(true, global.packetLimiter.overrides,
                config.getConfigurationSection("packet-limiter.overrides"), PacketLimitOverrides.ClassResolver.standard());
        effective.warnings().forEach(warning -> LOGGER.warn("[BTCCore] packet-limiter.overrides." + warning));
        // Replaced in one piece, before any connection; the map is immutable so Netty threads read it freely.
        global.packetLimiter.overrides = effective.limits();
        effective.limits().entrySet().stream()
                .sorted(java.util.Comparator.comparing(entry -> entry.getKey().getSimpleName()))
                .forEach(entry -> LOGGER.info("[BTCCore] " + PacketLimitOverrides.describe(entry.getKey(), entry.getValue())));
    }

    private static void initAsyncProcessing() {
        warnRemovedKeys(config, LOGGER::warn);

        asyncMobSpawningEnabled = getBoolean("async.mob-spawning.enabled", true);
        if (asyncMobSpawningEnabled) {
            LOGGER.info("[BTCCore] Async Mob Spawning enabled (requires per-player-mob-spawns in Paper)");
        }

        parallelTrackerEnabled = getBoolean("async.parallel-tracker.enabled", false);
        parallelTrackerThreads = getInt("async.parallel-tracker.threads", 0);
        if (parallelTrackerEnabled) {
            LOGGER.info("[BTCCore] Parallel entity tracker enabled (read-only scan on a pool, changes on the tick thread)");
        }

        asyncPathfindingEnabled = getBoolean("async.async-pathfinding.enabled", false);
        asyncPathfindingThreads = getInt("async.async-pathfinding.threads", 0);
        if (asyncPathfindingEnabled) {
            LOGGER.info("[BTCCore] Async pathfinding enabled (A* on a pool, paths applied on the tick thread)");
        }

        asyncPlayerDataSaveEnabled = getBoolean("async.player-data-save.enabled", false);
        if (asyncPlayerDataSaveEnabled) {
            LOGGER.info("[BTCCore] Async player data save enabled (encoded on the saving thread, written on a pool, in order per player)");
        }

        parallelWorldTickingEnabled = getBoolean("parallel-world-ticking.enabled", false);
        parallelWorldTickingThreads = getInt("parallel-world-ticking.threads", 0);
        if (parallelWorldTickingEnabled) {
            LOGGER.warn("[BTCCore] Parallel world ticking enabled - EXPERIMENTAL (one tick thread per world, the main thread waits for all of them)");
        }

        initEntityOptimizations();
        initBreakSpeed();
    }

    /**
     * Reads {@code mining.break-speed}.
     *
     * <p>The two table switches are separate from the master one because affinities and hardness
     * corrections are useful on a server that has no progression system at all. The progression
     * curve itself is no longer configured here: it belongs to the bound
     * {@code BreakSpeedProvider}, whose factor replaces the tables.
     *
     * <p>{@code mining.break-speed.tier-curve} is deprecated but still read, so a file that carries
     * it keeps loading and {@code BTCCoreAPI#breakSpeedForTier} keeps answering. It is not
     * removed: a plugin compiled against the previous API may still call that method.
     */
    @SuppressWarnings("deprecation") // reads the deprecated tier-curve key on purpose
    private static void initBreakSpeed() {
        breakSpeedEnabled = getBoolean("mining.break-speed.enabled", false);
        breakSpeedAffinityEnabled = getBoolean("mining.break-speed.tool-affinity.enabled", true);
        breakSpeedOffDomainFactor = getDouble("mining.break-speed.tool-affinity.off-domain-factor", 0.4);
        breakSpeedHardnessCorrectionsEnabled = getBoolean("mining.break-speed.hardness-corrections", true);
        breakSpeedTierCurve = readTierCurve();
        serverDrivenEnabled = getBoolean("mining.server-driven.enabled", false);

        if (serverDrivenEnabled && !breakSpeedEnabled) {
            LOGGER.warn("[BTCCore] mining.server-driven.enabled is on but mining.break-speed.enabled is off:"
                + " the server-driven mode stays inactive.");
        }
        if (breakSpeedEnabled) {
            LOGGER.info("[BTCCore] Break speed enabled - off-domain factor: " + breakSpeedOffDomainFactor
                + ". mining.break-speed.tier-curve is deprecated and ignored by the dig hook: the curve is"
                + " carried by the bound break-speed provider.");
        }
    }

    /** @deprecated see {@link #breakSpeedTierCurve}. */
    @Deprecated(since = "26.3")
    private static dev.btc.core.mining.TierCurve readTierCurve() {
        String path = "mining.break-speed.tier-curve";
        config.addDefault(path, dev.btc.core.mining.TierCurve.DEFAULT);
        List<Double> steps = config.getDoubleList(path);
        if (steps.isEmpty()) {
            // An empty list is not "no curve", it is a curve nobody can read. Saying so beats
            // answering 1.0 for every tier and letting a progression system look inert.
            LOGGER.warn("[BTCCore] " + path + " is empty - falling back to the default curve");
            return new dev.btc.core.mining.TierCurve(dev.btc.core.mining.TierCurve.DEFAULT);
        }
        return new dev.btc.core.mining.TierCurve(steps);
    }

    private static void initEntityOptimizations() {
        dearEnabled = getBoolean("dab.enabled", false);
        dearStartDistance = getInt("dab.start-distance", 12);
        dearStartDistanceSquared = dearStartDistance * dearStartDistance;
        // Said once at startup by BTCCoreListener when DAB is inert; "enabled" here would be false news.
        if (dearEnabled && dev.btc.core.performance.DABManager.isActive()) {
            LOGGER.info("[BTCCore] DAB (Dynamic Activation of Brain) enabled - start-distance: " + dearStartDistance);
        }

        suffocationOptimization = getBoolean("performance.suffocation-optimization", true);
        inactiveGoalSelectorThrottle = getBoolean("performance.inactive-goal-selector-throttle", true);
        projectileMaxLoadsPerTick = getInt("performance.projectile.max-loads-per-tick", 10);
        projectileMaxLoadsPerProjectile = getInt("performance.projectile.max-loads-per-projectile", 10);
        hopperThrottlingEnabled = getBoolean("performance.hopper.throttling", true);
        hopperThrottlingTicks = Math.max(1, getInt("performance.hopper.throttling-ticks", 40)); // a divisor of every hopper tick: 0 would throw there
        sleepingBlockEntitiesEnabled = getBoolean("performance.sleeping-block-entities.enabled", false);
        sleepingBlockEntitiesWakeIntervalTicks = Math.clamp(getInt("performance.sleeping-block-entities.wake-interval-ticks", 200), 20, 1200);
    }

    /** Distance meaning "do not touch this axis" in a {@link WorldDistanceRule}. */
    public static final int DISTANCE_UNSET = -1;

    /**
     * Reads {@code performance.per-world-distances.rules}.
     *
     * <p>Paper accepts {@code [2, 32]} on both axes; anything else is dropped with a warning rather
     * than clamped, because a silently corrected distance would make a load measurement lie.
     */
    private static List<WorldDistanceRule> readWorldDistanceRules() {
        String path = "performance.per-world-distances.rules";
        if (!config.contains(path)) {
            config.set(path, List.of(new java.util.LinkedHashMap<>(java.util.Map.of(
                    "worlds", List.of("btcsky_*"),
                    "view-distance", 8,
                    "simulation-distance", 4))));
        }

        List<WorldDistanceRule> rules = new java.util.ArrayList<>();
        for (java.util.Map<?, ?> raw : config.getMapList(path)) {
            List<String> worlds = new java.util.ArrayList<>();
            Object rawWorlds = raw.get("worlds");
            if (rawWorlds instanceof Collection<?> collection) {
                for (Object world : collection) {
                    if (world != null) worlds.add(String.valueOf(world));
                }
            } else if (rawWorlds != null) {
                worlds.add(String.valueOf(rawWorlds));
            }
            if (worlds.isEmpty()) {
                LOGGER.warn("[BTCCore] per-world-distances: rule without any 'worlds' pattern, ignored");
                continue;
            }

            int view = readDistance(raw.get("view-distance"), worlds, "view-distance");
            int simulation = readDistance(raw.get("simulation-distance"), worlds, "simulation-distance");
            if (view == DISTANCE_UNSET && simulation == DISTANCE_UNSET) continue;
            rules.add(new WorldDistanceRule(List.copyOf(worlds), view, simulation));
        }
        return List.copyOf(rules);
    }

    private static int readDistance(Object raw, List<String> worlds, String key) {
        if (raw == null) return DISTANCE_UNSET;
        int value;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else {
            try {
                value = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("[BTCCore] per-world-distances {}: '{}' is not a number, ignored for {}", key, raw, worlds);
                return DISTANCE_UNSET;
            }
        }
        if (value == DISTANCE_UNSET) return DISTANCE_UNSET;
        if (value < 2 || value > 32) {
            LOGGER.warn("[BTCCore] per-world-distances {}: {} is outside [2, 32], ignored for {}", key, value, worlds);
            return DISTANCE_UNSET;
        }
        return value;
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

        // Batched Inventory Updates
        batchedInventoryUpdates = getBoolean("performance.batched-inventory-updates", false);

        // Chunk Prefetch
        chunkPrefetchEnabled = getBoolean("performance.chunk-prefetch", true);

        // Per-World Tick Rate
        perWorldTickRateEnabled = getBoolean("performance.per-world-tick-rate.enabled", false);
        emptyWorldTPS = getInt("performance.per-world-tick-rate.empty-world-tps", 10);

        // Per-World View / Simulation Distance
        perWorldDistancesEnabled = getBoolean("performance.per-world-distances.enabled", false);
        perWorldDistanceRules = readWorldDistanceRules();

        // Projectile Pooling
        projectilePoolingEnabled = getBoolean("performance.projectile-pooling", true);

        perEntityTrackingRangeEnabled = getBoolean("performance.per-entity-tracking-range.enabled", false);
        aiSensorLoopsEnabled = getBoolean("performance.ai-sensor-loops.enabled", false);
        simpleFarmChecksEnabled = getBoolean("performance.simple-farm-checks.enabled", false);
        simpleFarmChecksMoistGrowthSpeed = dev.btc.core.performance.SimpleFarmChecks.boundedSpeed(getDouble(
            "performance.simple-farm-checks.moist-growth-speed", dev.btc.core.performance.SimpleFarmChecks.FIELD_MOIST_GROWTH_SPEED));
        simpleFarmChecksDryGrowthSpeed = dev.btc.core.performance.SimpleFarmChecks.boundedSpeed(getDouble(
            "performance.simple-farm-checks.dry-growth-speed", dev.btc.core.performance.SimpleFarmChecks.FIELD_DRY_GROWTH_SPEED));

        pistonShapeCacheEnabled = getBoolean("performance.piston-shape-cache.enabled", false);

        networkLazyNonFlushPackets = getBoolean("performance.network.lazy-non-flush-packets", false);
        networkFastVarInt = getBoolean("performance.network.fast-varint", false);
        networkSkipUnchangedMotion = getBoolean("performance.network.skip-unchanged-motion", false);
        networkSkipEmptyEntityMoves = getBoolean("performance.network.skip-empty-entity-moves", false);
        networkSpreadLatencyUpdates = getBoolean("performance.network.spread-latency-updates", false);
        networkSharedChunkPackets = getBoolean("performance.network.shared-chunk-packets", false);
        networkFilterEntityMotion = getBoolean("performance.network.filter-entity-motion.enabled", false);
        networkFilterEntityMotionAlwaysSend = List.copyOf(getList(
            "performance.network.filter-entity-motion.always-send", networkFilterEntityMotionAlwaysSend));

        raytraceCullingEnabled = getBoolean("performance.raytrace-culling.enabled", false);
        raytraceCullingRange = dev.btc.core.performance.OcclusionScan.boundedRange(
            getInt("performance.raytrace-culling.range", raytraceCullingRange));
        raytraceCullingIntervalTicks = Math.max(1, getInt("performance.raytrace-culling.interval-ticks", raytraceCullingIntervalTicks));
        raytraceCullingSectionsPerTick = Math.max(1, getInt("performance.raytrace-culling.sections-per-tick", raytraceCullingSectionsPerTick));
        raytraceCullingThreads = Math.max(1, getInt("performance.raytrace-culling.threads", raytraceCullingThreads));
        raytraceCullingAlwaysVisible = List.copyOf(getList(
            "performance.raytrace-culling.always-visible", raytraceCullingAlwaysVisible));

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
        cpsLimitMiningMax = getInt("security.cps-limit.mining-max", 30);

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
        playerDataBackupKeepLast = Math.max(0, getInt("qol.player-data-backup.keep-last", 0));

        LOGGER.info("[BTCCore] Optimization Features initialized");
    }

    /**
     * Reads {@code portals.*} into one immutable {@link PortalPolicy}.
     *
     * <p>Nothing here can refuse a portal unless {@code portals.mode} reads {@code enforce}: a
     * missing or unreadable mode is {@code observe}, so the wrong spelling blocks nothing.
     */
    private static void initPortals() {
        String rawMode = getString("portals.mode", "observe");
        PortalPolicy.Mode mode = PortalPolicy.Mode.parse(rawMode, PortalPolicy.Mode.OBSERVE);
        if (PortalPolicy.Mode.parse(rawMode, null) == null) {
            LOGGER.warn("[BTCCore] portals.mode '{}' is neither 'observe' nor 'enforce' - observing", rawMode);
        }

        List<String> allowedWorlds = readListOrScalar("portals.allowed-worlds", List.of());
        List<String> configuredReasons = readListOrScalar("portals.exempt-reasons", List.of(PortalPolicy.END_PLATFORM));
        Set<String> exemptReasons = Set.copyOf(configuredReasons);
        boolean denyTravel = getBoolean("portals.travel.deny-outside-allowed-worlds", false);

        List<String> unknownReasons = PortalPolicy.unknownReasons(exemptReasons);
        if (!unknownReasons.isEmpty()) {
            LOGGER.warn("[BTCCore] portals.exempt-reasons: unknown reason(s) {} (known: {})",
                    unknownReasons, PortalPolicy.KNOWN_REASONS);
        }

        if (configuredReasons.stream().noneMatch(PortalPolicy.END_PLATFORM::equalsIgnoreCase)) {
            LOGGER.info("[BTCCore] portals.exempt-reasons: {} is always exempt, listed or not", PortalPolicy.END_PLATFORM);
        }

        portalPolicy = new PortalPolicy(mode, allowedWorlds, exemptReasons, denyTravel);
        // The effective values, so a measurement (Sentinel 11.0) can quote them.
        LOGGER.info("[BTCCore] Portal policy: mode={} allowed-worlds={} exempt-reasons={} deny-travel-outside-allowed-worlds={}",
                mode.name().toLowerCase(Locale.ROOT), portalPolicy.allowedWorlds(),
                portalPolicy.exemptReasons(), denyTravel);
    }

    /**
     * A string list that an operator may also write as a lone scalar ({@code allowed-worlds: td_*}).
     * Bukkit's list getters turn a scalar into an empty list, which for an allow-list would silently
     * mean "no world is allowed"; here it is one element, and the reading is said out loud.
     */
    private static List<String> readListOrScalar(String path, List<String> def) {
        config.addDefault(path, def);
        Object raw = config.get(path);
        if (raw != null && !(raw instanceof Collection<?>)) {
            LOGGER.warn("[BTCCore] {}: '{}' is a single value, read as a one-element list", path, raw);
        }
        return PortalPolicy.asList(raw);
    }

    private static void initRpgOptimizations() {
        rpgVanillaSpawnsEnabled = getBoolean("rpg.vanilla-spawns.enabled", false);
        rpgWeatherTicksEnabled = getBoolean("rpg.weather-ticks.enabled", false);

        // Sentinel settings live in anticheat.yml; only migration warnings remain here.
        warnIfLegacyIntegritySettings();

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
