package dev.btc.core.config;

import net.minecraft.world.level.gamerules.GameRules;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * BTCCore: Configuration manager for SlimeWorld settings.
 */
public final class SlimeWorldConfig {

    private static final Logger LOGGER = LogManager.getLogger("SlimeWorldConfig");
    private static final Path CONFIG_PATH = Path.of("config", "BTCCore", "slimeworld-config.yml");
    private static SlimeWorldConfig instance;

    private final Map<String, String> defaultRules = new HashMap<>();
    private final Map<String, Map<String, String>> worldRules = new HashMap<>();
    private final Map<String, Map<String, String>> patternRules = new HashMap<>();

    private SlimeWorldConfig() {
        load();
    }

    public static synchronized SlimeWorldConfig getInstance() {
        if (instance == null) {
            instance = new SlimeWorldConfig();
        }
        return instance;
    }

    public static void reload() {
        instance = new SlimeWorldConfig();
    }

    private void load() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
        } catch (IOException e) {
            LOGGER.warn("Could not create directory for slimeworld config: {}", e.toString());
        }

        boolean fileExists = Files.exists(CONFIG_PATH);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(CONFIG_PATH.toFile());
        
        if (!fileExists) {
            LOGGER.info("[BTCCore] Generating default slimeworld-config.yml...");
            config.options().header("""
                    BTCCore - Default GameRules for Advanced Slime Worlds

                    Automatically applies GameRules whenever a Slime World is loaded.
                    Priority (later overrides earlier): default -> pattern -> exact world name.

                    Sections:
                      default:  rules applied to EVERY slime world
                      worlds:   rules per world name, or per pattern

                    World matching (case-insensitive):
                      my_world              exact world name
                      lobby*                prefix - worlds starting with 'lobby'
                      *_pvp                 suffix - worlds ending with '_pvp'
                      regex:^plot_[0-9]+$   full Java regex (prefix the pattern with 'regex:')

                    Values: boolean rules use true/false, numeric rules use a number.
                    Rule IDs are snake_case in this version (random_tick_speed, keep_inventory,
                    mob_griefing, spawn_mobs, advance_time, ...). Legacy camelCase names
                    (randomTickSpeed, keepInventory, ...) are also accepted for convenience,
                    but rules that were renamed must use the new ID:
                      doMobSpawning  -> spawn_mobs
                      doDaylightCycle -> advance_time
                      doWeatherCycle  -> advance_weather
                    BTC-specific rule:
                      copper_fade   0-100, how fast copper oxidises. 100 is exact vanilla
                                    behaviour (the default), 0 means copper never oxidises at
                                    all and therefore never needs waxing. Values in between
                                    scale the oxidation odds proportionally.

                    Example pattern entry (add under 'worlds:'):
                      "lobby*":
                        spawn_mobs: false
                        keep_inventory: true
                        copper_fade: 0
                    """);

            // Sensible defaults + one example world (edit or remove freely)
            config.set("default.random_tick_speed", 3);
            config.set("worlds.example_world.keep_inventory", true);
            config.set("worlds.example_world.advance_time", false);

            try {
                config.save(CONFIG_PATH.toFile());
                LOGGER.info("[BTCCore] Generated slimeworld-config.yml at {}", CONFIG_PATH.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.warn("Failed to save default slimeworld config file: {}", e.toString());
            }
        }

        // Load Defaults
        ConfigurationSection defaultSection = config.getConfigurationSection("default");
        if (defaultSection != null) {
            for (String key : defaultSection.getKeys(false)) {
                defaultRules.put(key, defaultSection.getString(key));
            }
        }

        // Load World Configs
        ConfigurationSection worldsSection = config.getConfigurationSection("worlds");
        if (worldsSection != null) {
            for (String key : worldsSection.getKeys(false)) {
                ConfigurationSection worldSection = worldsSection.getConfigurationSection(key);
                if (worldSection != null) {
                    Map<String, String> rules = new HashMap<>();
                    for (String ruleKey : worldSection.getKeys(false)) {
                        rules.put(ruleKey, worldSection.getString(ruleKey));
                    }

                    if (key.startsWith("regex:") || key.contains("*")) {
                        // Pattern Rule
                        patternRules.put(key, rules);
                    } else {
                        // Specific World Rule
                        worldRules.put(key, rules);
                    }
                }
            }
        }
    }

    public void applyRules(String worldName, GameRules gameRules) {
        // 1. Apply Defaults
        applyMap(gameRules, defaultRules);

        // 2. Apply Pattern Rules (if match)
        for (Map.Entry<String, Map<String, String>> entry : patternRules.entrySet()) {
            if (dev.btc.core.util.WorldPatternMatcher.matches(worldName, entry.getKey())) {
                applyMap(gameRules, entry.getValue());
            }
        }

        // 3. Apply Specific World Rules
        if (worldRules.containsKey(worldName)) {
            applyMap(gameRules, worldRules.get(worldName));
        }
    }

    private void applyMap(GameRules gameRules, Map<String, String> rules) {
        for (Map.Entry<String, String> entry : rules.entrySet()) {
            final String ruleName = entry.getKey();
            final String value = entry.getValue();
            final String normalized = normalize(ruleName);
            final boolean[] matched = {false};

            try {
                gameRules.visitGameRuleTypes(new net.minecraft.world.level.gamerules.GameRuleTypeVisitor() {
                    @Override
                    public void visitBoolean(net.minecraft.world.level.gamerules.GameRule<Boolean> rule) {
                        if (normalized.equals(normalize(rule.id()))) {
                            gameRules.set(rule, Boolean.parseBoolean(value), null);
                            matched[0] = true;
                        }
                    }

                    @Override
                    public void visitInteger(net.minecraft.world.level.gamerules.GameRule<Integer> rule) {
                        if (normalized.equals(normalize(rule.id()))) {
                            gameRules.set(rule, Integer.parseInt(value), null);
                            matched[0] = true;
                        }
                    }
                });
            } catch (Exception e) {
                LOGGER.warn("[BTCCore] Failed to set game rule '{}' to '{}': {}", ruleName, value, e.getMessage());
                continue;
            }

            if (!matched[0]) {
                LOGGER.warn("[BTCCore] Unknown game rule '{}' in slimeworld-config.yml: no gamerule matches it in this "
                        + "Minecraft version. Rule IDs are snake_case here (e.g. 'random_tick_speed', 'keep_inventory', "
                        + "'spawn_mobs', 'advance_time'). Skipped.", ruleName);
            }
        }
    }

    /**
     * Canonical form so that camelCase and snake_case rule names both match
     * (e.g. {@code randomTickSpeed} and {@code random_tick_speed} both resolve to
     * {@code randomtickspeed}). Note: this only reconciles naming convention — rules that
     * were semantically renamed (e.g. {@code doMobSpawning} -> {@code spawn_mobs}) must use
     * the new ID.
     */
    private static String normalize(String id) {
        return id.toLowerCase(java.util.Locale.ROOT).replace("_", "");
    }
}

