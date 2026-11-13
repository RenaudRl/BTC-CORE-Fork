package dev.btc.core.config;

import dev.btc.core.playerdata.SaveAlignment;
import dev.btc.core.security.admin.OpLock;
import dev.btc.core.security.exploit.InteractBurst;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * Native Async Anticheat Configuration Manager
 * Handles the configuration for the high-performance async packet validation system.
 */
public final class AnticheatConfig {

    private static File configFile;
    private static YamlConfiguration config;

    public static int version;
    public static boolean verbose;

    // Sentinel engine master switch and staff alerting
    public static boolean sentinelEnabled = true;
    public static boolean autoNotifyAdmins = true;

    // File journal (logs/btccore/exploits.log)
    public static boolean fileLoggingEnabled = true;

    // Async Network Engine
    public static boolean asyncPacketValidationEnabled = true;
    public static int asyncValidationThreads = 2; // Default to 2 dedicated checking threads

    // Reach Validation
    public static boolean reachCheckEnabled = true;
    public static double reachMaxDistanceSurvival = 3.0; // Standard Vanilla
    public static double reachMaxDistanceCreative = 5.0; 
    public static double reachViolationBufferLimit = 3.0; // Fail 3 reach checks in a row to start blocking
    public static boolean reachStrictHitboxMath = true;
    public static boolean reachRaytraceEnabled = true;
    public static double reachRaytraceLeniency = 0.1;
    public static int reachViolationAction = 0; // 0 = Cancel, 1 = Cancel + Log, 2 = Kick

    // Violation storage (PostgreSQL — the platform's persistence, see storage.* below)
    public static boolean storageEnabled = false;
    public static String storageHost = "localhost";
    public static int storagePort = 5432;
    public static String storageDatabase = "btccore";
    public static String storageUsername = "btccore";
    public static String storagePassword = "";
    public static String storageTablePrefix = "btc_";

    /**
     * Every table name is built as {@code prefix + name} and spliced into SQL text, which JDBC cannot
     * parameterise. The prefix is therefore restricted to a harmless alphabet, not escaped.
     */
    private static final java.util.regex.Pattern TABLE_PREFIX = java.util.regex.Pattern.compile("^([a-z][a-z0-9_]{0,31})?$");

    // Moderation texts (MiniMessage). Placeholders are tags: <target> <type> <account> <reason> <count> <accounts>
    // <lifted> <total> <failed> <error> <remaining> <duration>.
    public static String msgBannedViaLinkedAccount =
        "<red>You cannot join: a sanction applies to an account linked to yours.";
    public static String msgMutedViaLinkedAccount =
        "<red>You cannot speak right now: a mute applies to an account linked to yours.";
    public static String msgKickedViaLinkedAccount =
        "<red>You were kicked: a kick was issued against an account linked to yours.";
    public static String msgWarnedViaLinkedAccount =
        "<yellow>A warning was issued against an account linked to yours.";
    public static String msgBanned = "<red>You are banned (<remaining>)\n<reason>";
    public static String msgMuted = "<red>You are muted for <remaining>: <reason>";
    public static String msgMutedSilent = "<red>You cannot speak right now.";
    public static String msgKicked = "<red>Kicked: <reason>";
    public static String msgWarned = "<yellow>Warning: <reason>";
    public static String msgRecorded = "<green><type> recorded for <target> (<duration>): <reason>";
    public static String msgLifted = "<green><type> lifted for <target>: <reason>";
    public static String msgAlreadyLifted = "<red>That sanction was already lifted.";
    public static String msgNothingToLift = "<red><target> has no standing <type> to lift.";
    public static String msgLiftBlockedByLinked = "<yellow><target> is still blocked by a <type> on linked "
        + "account <account> (<reason>). Nothing was lifted for it; re-run with --linked to lift it too.";
    public static String msgLiftStillBlockedByLinked = "<yellow><target> is still blocked by a <type> on linked "
        + "account <account> (<reason>); re-run with --linked to lift it too.";
    public static String msgLiftedLinked =
        "<green><type> lifted for <target> and its linked accounts (<count>: <accounts>): <reason>";
    public static String msgLiftPartial = "<red><lifted> of <total> <type> sanctions lifted for <target>; "
        + "<failed> could NOT be lifted (see the console). Run the command again to retry.";
    public static String msgLiftFailed = "<red>The <type> could NOT be lifted for <target>: <error>";
    public static String msgHistoryFailed = "<red>The history of <target> could not be read: <error>";
    public static String msgHistoryAliases = "<gray>Linked accounts: <accounts>";
    public static String msgHistoryViaAlias = " <dark_gray>[linked account <account>]";

    /** How long an IP address may be kept. Personal data; the sanction itself is never purged. */
    public static int addressRetentionDays = 180;

    /** How long a violation journal line may be kept. Rows are deleted outright, unlike sanctions. */
    public static int violationRetentionDays = 90;

    // Identity: which network this server belongs to, and which server it is within it.
    // A network-scoped sanction is meaningless without the first; a server-scoped one without the second.
    public static String networkId = "borntocraft";
    public static String serverId = "unknown";

    // Network bus: Redis-protocol (Valkey, Dragonfly or Redis). Carries invalidations, never state.
    public static boolean busEnabled = false;
    public static String busUri = "valkey://localhost:6379";

    // Velocity / Movement Validation
    public static boolean velocityCheckEnabled = true;
    public static double velocityMaxDeltaXZ = 0.8;
    public static double velocityMaxDeltaY = 1.2;
    public static int velocityViolationAction = 0; // 0 = Teleport back, 1 = Teleport back + Log, 2 = Kick

    // Entity-interaction bursts (task 10.14): counted and journalled, never blocked.
    public static InteractBurst.Mode interactBurstMode = InteractBurst.Mode.OBSERVE;
    public static long interactBurstWindowMillis = 3000;
    public static int interactBurstThreshold = 75;

    // Op lock (task 12.6): ops are kept a subset of the allowed list. Off until the operator turns it on.
    public static OpLock.Mode opLockMode = OpLock.Mode.OFF;
    public static java.util.Set<java.util.UUID> opLockAllowed = java.util.Set.of();
    public static java.util.List<String> opLockInvalidEntries = java.util.List.of();
    public static int opLockSweepIntervalTicks = 20;
    public static String opLockRefusedMessage = "<red>You cannot use this command.";

    // Save alignment (task 5.1): the save of a Slime world is logged with the players it should be aligned with.
    public static SaveAlignment.Mode saveAlignmentMode = SaveAlignment.Mode.OBSERVE;
    /** Read and kept for the enforce mode, which this release does not have: it changes nothing today. */
    public static java.util.List<String> saveAlignmentWorldsOnQuit = java.util.List.of();

    public static void init(File file) {
        if (file == null) {
            file = new File("anticheat.yml");
            Bukkit.getLogger().info("[BTCCore] No anticheat.yml specified, using default path");
        }
        AnticheatConfig.configFile = file;
        AnticheatConfig.config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException ignore) {
        } catch (InvalidConfigurationException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not load anticheat.yml, please correct your syntax errors", ex);
            throw new RuntimeException(ex);
        }
        // Refused before any setting is assigned: a bad prefix leaves every value exactly as it was
        // (defaults at startup, the running values on a reload) instead of a half-loaded mix.
        // Read without registering a default, so the keys keep their documented order in the file.
        validateTablePrefix(config.getString("storage.table-prefix", "btc_"));
        config.options().copyDefaults(true);

        version = getInt("config-version", 1);
        set("config-version", 1);

        verbose = getBoolean("verbose", false);

        // Load Settings
        loadSentinelSettings();
        loadAsyncSettings();
        loadReachSettings();
        loadVelocitySettings();
        loadExploitSettings();
        loadAdminSettings();
        loadMechanicsSettings();
        loadStorageSettings();
        loadIdentitySettings();
        loadMessageSettings();

        warnOnUnrecognisedKeys();

        try {
            config.save(configFile);
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not save anticheat.yml", ex);
        }
    }

    /**
     * Roots of the schema that a resource file once documented but that no code ever read, mapped to
     * what actually governs the behaviour. An operator who edited those keys got no effect and no
     * message; naming them explicitly is the only way that silence ends.
     */
    private static final java.util.Map<String, String> ORPHANED_ROOTS = java.util.Map.of(
        "cps-limit", "btccore.yml -> security.cps-limit",
        "reach", "checks.reach",
        "movement", "checks.velocity",
        "combat", "btccore.yml -> security.combat-log",
        "exploit", "btccore.yml -> security.exploit-logging",
        "auto-ban", "not implemented: no automatic ban exists",
        "exempt", "not implemented: no per-player exemption list exists",
        "anticheat", "engine.*"
    );

    /**
     * Reports every key present in the file that no setting loader consulted.
     *
     * <p>Defaults are registered by {@code addDefault} as each setting is read, so after all loaders
     * have run the default set is exactly the set of recognised paths. Anything else in the file was
     * written by an operator and silently ignored — which is the failure this warning exists to end.
     */
    private static void warnOnUnrecognisedKeys() {
        org.bukkit.configuration.Configuration defaults = config.getDefaults();
        if (defaults == null) {
            return;
        }
        java.util.Set<String> recognised = defaults.getKeys(true);

        for (String path : config.getKeys(true)) {
            if (recognised.contains(path) || config.isConfigurationSection(path)) {
                continue;
            }
            String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
            String replacement = ORPHANED_ROOTS.get(root);
            if (replacement != null) {
                Bukkit.getLogger().warning("[BTCCore] anticheat.yml: '" + path
                    + "' belongs to a schema that was never read by any code. It has no effect. Use: "
                    + replacement);
            } else {
                Bukkit.getLogger().warning("[BTCCore] anticheat.yml: '" + path
                    + "' is not a recognised setting and has no effect.");
            }
        }
    }

    private static void loadSentinelSettings() {
        config.setComments("sentinel", java.util.List.of(
            "################################################################",
            "# SENTINEL — master switch and staff alerting",
            "#",
            "# This file is the single source of truth for integrity settings.",
            "# Two exceptions remain in btccore.yml, deliberately:",
            "#   security.combat-log.*  — a gameplay rule (kill on logout), not a detection setting",
            "#   security.cps-limit.*   — read from an NMS patch; it moves here with the phase 3",
            "#                            patch work, so both are verified in one pass",
            "################################################################"
        ));
        sentinelEnabled = getBoolean("sentinel.enabled", true);
        config.setComments("sentinel.auto-notify-admins", java.util.List.of(
            "Send violation alerts to players holding sentinel.admin."
        ));
        autoNotifyAdmins = getBoolean("sentinel.auto-notify-admins", true);
        config.setComments("sentinel.file-logging", java.util.List.of(
            "Write violations to logs/btccore/exploits.log. Independent of storage.* below:",
            "the file is for reading during an incident, the database is for history."
        ));
        fileLoggingEnabled = getBoolean("sentinel.file-logging", true);
    }

    private static void loadAsyncSettings() {
        config.setComments("engine.async-packet-validation", java.util.List.of(
            "################################################################",
            "# NATIVE ASYNCHRONOUS CHECKING ENGINE",
            "# This is the core performance driver of the Sentinel Anticheat.",
            "# Validations are offloaded to an asynchronous ForkJoin pool.",
            "################################################################"
        ));
        asyncPacketValidationEnabled = getBoolean("engine.async-packet-validation.enabled", true);
        config.setComments("engine.async-packet-validation.threads", java.util.List.of(
            "Number of dedicated async threads assigned strictly to parsing combat and movement network arrays.",
            "Default: 2 (Safe for most standard servers)"
        ));
        asyncValidationThreads = getInt("engine.async-packet-validation.threads", 2);
    }

    private static void loadReachSettings() {
        config.setComments("checks.reach", java.util.List.of(
            "################################################################",
            "# ASYNC REACH & COMBAT VALIDATOR",
            "# Powered by PlayerSimulationCache (AABB hitboxes)",
            "################################################################"
        ));
        reachCheckEnabled = getBoolean("checks.reach.enabled", true);
        config.setComments("checks.reach.max-distance-survival", java.util.List.of("Maximum allowable raytrace block hit distance for survival interactions."));
        reachMaxDistanceSurvival = getDouble("checks.reach.max-distance-survival", 3.0);
        config.setComments("checks.reach.max-distance-creative", java.util.List.of("Maximum allowable raytrace block hit distance for creative interactions."));
        reachMaxDistanceCreative = getDouble("checks.reach.max-distance-creative", 5.0);
        config.setComments("checks.reach.violation-buffer-limit", java.util.List.of("Number of violations allowed before synchronous attack blocking triggers. Default 3.0."));
        reachViolationBufferLimit = getDouble("checks.reach.violation-buffer-limit", 3.0);
        config.setComments("checks.reach.strict-hitbox-math", java.util.List.of("Enforces strictly mathematics tracing bounds without leniency margins. Requires perfect connection."));
        reachStrictHitboxMath = getBoolean("checks.reach.strict-hitbox-math", true);
        config.setComments("checks.reach.raytrace-enabled", java.util.List.of("Enables Line of Sight checks between attacker and target."));
        reachRaytraceEnabled = getBoolean("checks.reach.raytrace-enabled", true);
        reachRaytraceLeniency = getDouble("checks.reach.raytrace-leniency", 0.1);
        config.setComments("checks.reach.violation-action", java.util.List.of("Action thresholds upon detection. 0 = Cancel Hit, 1 = Cancel + Alert, 2 = Kick"));
        reachViolationAction = getInt("checks.reach.violation-action", 0);
    }

    private static void loadVelocitySettings() {
        config.setComments("checks.velocity", java.util.List.of(
            "################################################################",
            "# FAST-MATH VELOCITY VALIDATOR",
            "# Ensures motion values accurately align with server-side physics.",
            "################################################################"
        ));
        velocityCheckEnabled = getBoolean("checks.velocity.enabled", true);
        config.setComments("checks.velocity.max-delta-xz", java.util.List.of("Maximum XZ (horizontal) movement un-aided speed change per tick"));
        velocityMaxDeltaXZ = getDouble("checks.velocity.max-delta-xz", 0.8);
        config.setComments("checks.velocity.max-delta-y", java.util.List.of("Maximum Y (vertical) jump/fall un-aided speed change per tick"));
        velocityMaxDeltaY = getDouble("checks.velocity.max-delta-y", 1.2);
        config.setComments("checks.velocity.violation-action", java.util.List.of("Action thresholds upon detection. 0 = Silent Setback, 1 = Setback + Alert, 2 = Setback + Kick"));
        velocityViolationAction = getInt("checks.velocity.violation-action", 0);
    }

    private static void loadExploitSettings() {
        interactBurstMode = readMode("exploits.interact-burst.mode", "observe", InteractBurst.Mode::parse,
            InteractBurst.Mode.OBSERVE);
        interactBurstWindowMillis = Math.max(1, getInt("exploits.interact-burst.window-millis", 3000));
        interactBurstThreshold = getInt("exploits.interact-burst.threshold", 75);

        document("exploits.interact-burst.mode",
            "off | observe. observe journals one line per window when a player sends 'threshold'",
            "entity interactions inside 'window-millis' (check sentinel:interact-burst). Needs",
            "sentinel.enabled. Read it before turning the matching packet-limiter override on.");
        document("exploits.interact-burst.window-millis", "Length of the sliding window, in milliseconds.");
        document("exploits.interact-burst.threshold",
            "Interactions inside the window that make a burst. 75 over 3000 ms is 25 a second, the same",
            "rate as the starting ServerboundInteractPacket override. Clamped to 2..1024.");
        // After its keys: the section exists in the file only once they have been written.
        document("exploits",
            "################################################################",
            "# EXPLOIT COUNTERS",
            "# Observation only: the integrity seam cannot cancel a packet. Blocking a flood",
            "# is the per-packet-type limiter of btccore.yml (packet-limiter.overrides).",
            "################################################################");
    }

    private static void loadAdminSettings() {
        opLockMode = readMode("admin.op-lock.mode", "off", OpLock.Mode::parse, OpLock.Mode.OFF);
        OpLock.Allowed allowed = OpLock.parseAllowed(getStringList("admin.op-lock.allowed"));
        opLockAllowed = allowed.uuids();
        opLockInvalidEntries = allowed.invalid();
        opLockSweepIntervalTicks = Math.max(1, getInt("admin.op-lock.sweep-interval-ticks", 20));
        opLockRefusedMessage = getString("admin.op-lock.message", opLockRefusedMessage);

        document("admin.op-lock.mode",
            "Keeps the invariant 'every op is in allowed', whatever made them an op (/op, /execute,",
            "an alias, a plugin, an edited ops.json). It only ever REMOVES an op, never grants one.",
            "off     : nothing.",
            "observe : journals the ops outside the list and the /op or /deop a player typed; removes nothing.",
            "enforce : removes those ops (at startup, then every sweep-interval-ticks) and cancels /op and",
            "          /deop typed by a player. The console and RCON are never blocked, but what they grant",
            "          outside the list is removed by the next sweep.",
            "With enforce, an EMPTY allowed list means nobody may be op.",
            "Read at startup: changing it needs a restart.");
        document("admin.op-lock.allowed",
            "UUIDs allowed to be op: an upper bound, never a list of players to promote. Bedrock",
            "(Floodgate) accounts have UUIDs starting 00000000-0000-0000-0009-. A malformed entry is",
            "reported at startup and ignored (which can only remove an op).");
        document("admin.op-lock.sweep-interval-ticks",
            "How often the op list is checked against 'allowed', in ticks (20 = every second).");
        document("admin.op-lock.message", "Shown to a player whose /op or /deop is cancelled (MiniMessage).");
        // After its keys: the section exists in the file only once they have been written.
        document("admin",
            "################################################################",
            "# ADMIN LOCKS",
            "# Read when the server starts: changing them needs a restart.",
            "################################################################");
    }

    private static void loadMechanicsSettings() {
        saveAlignmentMode = readMode("mechanics.save-alignment.mode", "observe", SaveAlignment.Mode::parse,
            SaveAlignment.Mode.OBSERVE);
        saveAlignmentWorldsOnQuit = getStringList("mechanics.save-alignment.save-world-on-quit");

        document("mechanics.save-alignment.mode",
            "A Slime world and the players who handled its items are written to two files at two moments:",
            "an abrupt stop between the two rolls one back and not the other (a loss or a duplicate).",
            "off     : nothing.",
            "observe : at each save of a Slime world, a DEBUG line says how many players it should be aligned",
            "          with (those in it, and those who left it since its last save); a world quarantined",
            "          without a save says how many players are exposed. Nothing is saved or changed.",
            "'enforce' (saving those players in the same pass) is not part of this release.",
            "Read at startup: changing it needs a restart.");
        document("mechanics.save-alignment.save-world-on-quit",
            "World-name patterns (e.g. \"btc_skyblock_*\") whose world would be saved when a player quits.",
            "Reserved for the enforce mode: it has NO effect today.");
        // After its keys: the section exists in the file only once they have been written.
        document("mechanics",
            "################################################################",
            "# MECHANICS INTEGRITY",
            "# Read when the server starts: changing them needs a restart.",
            "################################################################");
    }

    /**
     * Attaches a comment to a key of the file. The value is written first when the file lacks it:
     * {@code setComments} ignores a path that is not in the file yet, so without this a new key would
     * only be documented from the second start on.
     */
    private static void document(String path, String... lines) {
        if (!config.contains(path, true)) {
            config.set(path, config.get(path));
        }
        config.setComments(path, java.util.List.of(lines));
    }

    /**
     * Reads an enumerated setting. An unreadable value falls back to the caller's default, with a
     * warning: this runs at startup and a typo must not stop the server.
     */
    private static <M> M readMode(String path, String defaultText, java.util.function.Function<String, java.util.Optional<M>> parser, M fallback) {
        String text = getString(path, defaultText);
        // An unquoted 'off' is a YAML boolean: the file says false, and 'off' is what was meant.
        String raw = config.get(path) instanceof Boolean flag ? (flag ? "on" : "off") : text;
        return parser.apply(raw).orElseGet(() -> {
            Bukkit.getLogger().warning("[BTCCore] anticheat.yml: '" + path + "' is '" + raw
                + "', which is not a valid mode; using '" + defaultText + "'.");
            return fallback;
        });
    }

    private static void loadStorageSettings() {
        config.setComments("storage", java.util.List.of(
            "################################################################",
            "# VIOLATION STORAGE (PostgreSQL)",
            "# Violations are journalled here so that staff can review a player's",
            "# history and so that confirmed verdicts accumulate over time.",
            "# PostgreSQL only: the rest of the BTC stack already runs on it, and a",
            "# third persistence technology in one project is not defensible.",
            "################################################################"
        ));
        // The prefix was already validated in init(), before any loader ran.
        storageEnabled = getBoolean("storage.enabled", false);
        storageHost = getString("storage.host", "localhost");
        storagePort = getInt("storage.port", 5432);
        storageDatabase = getString("storage.database", "btccore");
        storageUsername = getString("storage.username", "btccore");
        config.setComments("storage.password", java.util.List.of(
            "Leave empty here and inject the password at runtime. A secret committed to a",
            "repository is a secret that must be rotated."
        ));
        storagePassword = getString("storage.password", "");
        config.setComments("storage.table-prefix", java.util.List.of(
            "Prefix for every table this platform owns. Changing it after first start does not",
            "rename anything: the old tables stay and the platform starts again from empty."
        ));
        storageTablePrefix = getString("storage.table-prefix", "btc_");

        config.setComments("storage.address-retention-days", java.util.List.of(
            "How long an IP address is kept, in days. An address is personal data; French practice",
            "puts technical security logs between six months and one year, on a legitimate-interest",
            "basis that must be documented and limited to security.",
            "Only the address is erased. The sanction, its reason and its author are kept, so a",
            "moderation history stays readable without holding personal data indefinitely.",
            "Set to 0 to keep addresses indefinitely — a choice that has to be justifiable."
        ));
        addressRetentionDays = getInt("storage.address-retention-days", 180);

        config.setComments("storage.violation-retention-days", java.util.List.of(
            "How long a line of the violation journal is kept, in days.",
            "Unlike a sanction, a violation line is deleted outright: it records a suspicion, not a",
            "decision, and keeping suspicions about a named player indefinitely is not defensible.",
            "Shorter than the address horizon on purpose — this table grows with every flag, and its",
            "usefulness (spotting a pattern over recent weeks) does not extend past a few months.",
            "Set to 0 to keep the journal indefinitely."
        ));
        violationRetentionDays = getInt("storage.violation-retention-days", 90);
    }

    /**
     * Refuses a table prefix that is not empty and does not start with a lowercase letter followed by
     * at most 31 of {@code [a-z0-9_]} (an identifier may not start with a digit).
     *
     * @return the prefix, unchanged, when it is acceptable
     * @throws IllegalArgumentException with a message an operator can act on
     */
    public static String validateTablePrefix(String prefix) {
        if (prefix == null || !TABLE_PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("anticheat.yml: storage.table-prefix '" + prefix
                + "' is invalid; leave it empty or start with a lowercase letter, then use at most 31 "
                + "lowercase letters, digits or '_' (it is written into SQL table names). "
                + "Nothing from anticheat.yml was applied.");
        }
        return prefix;
    }

    private static void loadMessageSettings() {
        config.setComments("sanctions.messages", java.util.List.of(
            "Texts of the moderation commands and of the linked-account refusal, in MiniMessage.",
            "Placeholders are tags, e.g. <target>. Staff-written values (names, reasons) are inserted",
            "as plain text, never parsed."
        ));
        msgBannedViaLinkedAccount = getString("sanctions.messages.banned-via-linked-account", msgBannedViaLinkedAccount);
        msgMutedViaLinkedAccount = getString("sanctions.messages.muted-via-linked-account", msgMutedViaLinkedAccount);
        msgKickedViaLinkedAccount = getString("sanctions.messages.kicked-via-linked-account", msgKickedViaLinkedAccount);
        msgWarnedViaLinkedAccount = getString("sanctions.messages.warned-via-linked-account", msgWarnedViaLinkedAccount);
        msgBanned = getString("sanctions.messages.banned", msgBanned);
        msgMuted = getString("sanctions.messages.muted", msgMuted);
        msgMutedSilent = getString("sanctions.messages.muted-silent", msgMutedSilent);
        msgKicked = getString("sanctions.messages.kicked", msgKicked);
        msgWarned = getString("sanctions.messages.warned", msgWarned);
        msgRecorded = getString("sanctions.messages.recorded", msgRecorded);
        msgLifted = getString("sanctions.messages.lifted", msgLifted);
        msgAlreadyLifted = getString("sanctions.messages.already-lifted", msgAlreadyLifted);
        msgNothingToLift = getString("sanctions.messages.nothing-to-lift", msgNothingToLift);
        msgLiftBlockedByLinked = getString("sanctions.messages.lift-blocked-by-linked", msgLiftBlockedByLinked);
        msgLiftStillBlockedByLinked = getString(
            "sanctions.messages.lift-still-blocked-by-linked", msgLiftStillBlockedByLinked);
        msgLiftedLinked = getString("sanctions.messages.lifted-linked", msgLiftedLinked);
        msgLiftPartial = getString("sanctions.messages.lift-partial", msgLiftPartial);
        msgLiftFailed = getString("sanctions.messages.lift-failed", msgLiftFailed);
        msgHistoryFailed = getString("sanctions.messages.history-failed", msgHistoryFailed);
        msgHistoryAliases = getString("sanctions.messages.history-aliases", msgHistoryAliases);
        msgHistoryViaAlias = getString("sanctions.messages.history-via-alias", msgHistoryViaAlias);
    }

    /**
     * Reads who this server is.
     *
     * <p>A sanction carries the network it was issued on and, when it applies to one server only, which
     * server. Left at the default, {@code server-id} says {@code unknown} — which is exactly what a
     * server-scoped sanction would then be attributed to, so the value is worth setting before any
     * moderation happens.
     */
    private static void loadIdentitySettings() {
        config.setComments("identity", java.util.List.of(
            "################################################################",
            "# IDENTITY",
            "# Which network this server belongs to, and which server it is.",
            "# A network-wide sanction is shared across every server declaring the same",
            "# network-id. A server-scoped one names this server-id and applies here only.",
            "################################################################"
        ));
        networkId = getString("identity.network-id", "borntocraft");
        serverId = getString("identity.server-id", "unknown");

        config.setComments("identity.bus", java.util.List.of(
            "Cross-server propagation. Off for a single server: a node does not need to tell itself",
            "anything, and leaving this off is not a degraded mode.",
            "Any Redis-protocol store works — Valkey, Dragonfly or Redis all speak it, and the URI",
            "scheme is normalised, so valkey://, dragonfly:// and redis:// are interchangeable here",
            "(same handling as the SlimeWorld redis loader). Use the +ssl variants for TLS.",
            "The bus carries only 'this player changed'; every server then re-reads PostgreSQL, so",
            "the database stays the single source of truth even if a message is lost."
        ));
        busEnabled = getBoolean("identity.bus.enabled", false);
        busUri = getString("identity.bus.uri", "valkey://localhost:6379");

        if ("unknown".equals(serverId)) {
            Bukkit.getLogger().warning(
                "[Sentinel] identity.server-id is still 'unknown'; server-scoped sanctions issued here "
                    + "will be attributed to a server nobody can identify. Set it in anticheat.yml.");
        }
    }

    private static void set(String path, Object val) {
        config.addDefault(path, val);
        config.set(path, val);
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

    private static java.util.List<String> getStringList(String path) {
        config.addDefault(path, java.util.List.of());
        return config.getStringList(path);
    }

    private static String getString(String path, String def) {
        config.addDefault(path, def);
        return config.getString(path, config.getString(path));
    }
}

