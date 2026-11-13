package dev.btc.core.text;

import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.config.BTCCoreConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Every text BTC-CORE may ask a {@link dev.btc.core.api.text.PlayerTextProvider} for: the key, the
 * English MiniMessage template shown when nobody provides the text, and the variables it may use.
 *
 * <p>The keys are frozen: a translation file written by hand against one of them is lost when the key
 * is renamed. Add keys, never rename them. A text an admin may reword in {@code btccore.yml} or
 * {@code anticheat.yml} reads that value ({@link #template()}); the default of that setting is
 * {@link #defaultTemplate()}, so the English text lives once.
 *
 * <p>Variables are tags in the template ({@code <reason>}) and named values for a provider
 * ({@code {reason}} in a translation file). A text about a linked account declares none: it is never
 * given a reason nor a duration (AD-091).
 *
 * <p>Keys are never built from user input: a sanction type or a duration unit picks one of these
 * constants, nothing typed by a person reaches {@link #key()}.
 */
public enum PlayerText {

    // ---- sanctions shown to the sanctioned player (anticheat.yml: sanctions.messages.*)
    SANCTION_BANNED("btccore.sanction.banned", "<red>You are banned (<remaining>)\n<reason>",
        () -> AnticheatConfig.msgBanned, "remaining", "reason"),
    SANCTION_BANNED_LINKED("btccore.sanction.banned_linked",
        "<red>You cannot join: a sanction applies to an account linked to yours.",
        () -> AnticheatConfig.msgBannedViaLinkedAccount),
    SANCTION_MUTED("btccore.sanction.muted", "<red>You are muted for <remaining>: <reason>",
        () -> AnticheatConfig.msgMuted, "remaining", "reason"),
    SANCTION_MUTED_SILENT("btccore.sanction.muted_silent", "<red>You cannot speak right now.",
        () -> AnticheatConfig.msgMutedSilent),
    SANCTION_MUTED_LINKED("btccore.sanction.muted_linked",
        "<red>You cannot speak right now: a mute applies to an account linked to yours.",
        () -> AnticheatConfig.msgMutedViaLinkedAccount),
    SANCTION_KICKED("btccore.sanction.kicked", "<red>Kicked: <reason>",
        () -> AnticheatConfig.msgKicked, "reason"),
    SANCTION_KICKED_LINKED("btccore.sanction.kicked_linked",
        "<red>You were kicked: a kick was issued against an account linked to yours.",
        () -> AnticheatConfig.msgKickedViaLinkedAccount),
    SANCTION_WARNED("btccore.sanction.warned", "<yellow>Warning: <reason>",
        () -> AnticheatConfig.msgWarned, "reason"),
    SANCTION_WARNED_LINKED("btccore.sanction.warned_linked",
        "<yellow>A warning was issued against an account linked to yours.",
        () -> AnticheatConfig.msgWarnedViaLinkedAccount),

    // ---- sanctions shown to staff (anticheat.yml: sanctions.messages.*)
    SANCTION_RECORDED("btccore.sanction.recorded", "<green><type> recorded for <target> (<duration>): <reason>",
        () -> AnticheatConfig.msgRecorded, "type", "target", "duration", "reason"),
    SANCTION_LIFTED("btccore.sanction.lifted", "<green><type> lifted for <target>: <reason>",
        () -> AnticheatConfig.msgLifted, "type", "target", "reason"),
    SANCTION_ALREADY_LIFTED("btccore.sanction.already_lifted", "<red>That sanction was already lifted.",
        () -> AnticheatConfig.msgAlreadyLifted),
    SANCTION_NOTHING_TO_LIFT("btccore.sanction.nothing_to_lift", "<red><target> has no standing <type> to lift.",
        () -> AnticheatConfig.msgNothingToLift, "target", "type"),
    SANCTION_LIFT_BLOCKED_BY_LINKED("btccore.sanction.lift_blocked_by_linked",
        "<yellow><target> is still blocked by a <type> on linked account <account> (<reason>). "
            + "Nothing was lifted for it; re-run with --linked to lift it too.",
        () -> AnticheatConfig.msgLiftBlockedByLinked, "target", "type", "account", "reason"),
    SANCTION_LIFT_STILL_BLOCKED_BY_LINKED("btccore.sanction.lift_still_blocked_by_linked",
        "<yellow><target> is still blocked by a <type> on linked account <account> (<reason>); "
            + "re-run with --linked to lift it too.",
        () -> AnticheatConfig.msgLiftStillBlockedByLinked, "target", "type", "account", "reason"),
    SANCTION_LIFTED_LINKED("btccore.sanction.lifted_linked",
        "<green><type> lifted for <target> and its linked accounts (<count>: <accounts>): <reason>",
        () -> AnticheatConfig.msgLiftedLinked, "type", "target", "count", "accounts", "reason"),
    SANCTION_LIFT_PARTIAL("btccore.sanction.lift_partial",
        "<red><lifted> of <total> <type> sanctions lifted for <target>; "
            + "<failed> could NOT be lifted (see the console). Run the command again to retry.",
        () -> AnticheatConfig.msgLiftPartial, "lifted", "total", "type", "target", "failed"),
    SANCTION_LIFT_FAILED("btccore.sanction.lift_failed", "<red>The <type> could NOT be lifted for <target>: <error>",
        () -> AnticheatConfig.msgLiftFailed, "type", "target", "error"),
    SANCTION_HISTORY_FAILED("btccore.sanction.history_failed", "<red>The history of <target> could not be read: <error>",
        () -> AnticheatConfig.msgHistoryFailed, "target", "error"),
    SANCTION_HISTORY_ALIASES("btccore.sanction.history_aliases", "<gray>Linked accounts: <accounts>",
        () -> AnticheatConfig.msgHistoryAliases, "accounts"),
    SANCTION_HISTORY_VIA_ALIAS("btccore.sanction.history_via_alias", " <dark_gray>[linked account <account>]",
        () -> AnticheatConfig.msgHistoryViaAlias, "account"),

    // ---- the moderation commands themselves (staff)
    SANCTION_PREFIX("btccore.sanction.prefix", "<dark_red>[Sentinel] "),
    SANCTION_ERROR_USAGE("btccore.sanction.error.usage", "Usage: <usage>", "usage"),
    SANCTION_ERROR_STORE_UNAVAILABLE_ISSUE("btccore.sanction.error.store_unavailable_issue",
        "The sanction store is unavailable; nothing was recorded. Check storage.* in anticheat.yml and the console."),
    SANCTION_ERROR_STORE_UNAVAILABLE_LIFT("btccore.sanction.error.store_unavailable_lift",
        "The sanction store is unavailable; nothing was lifted."),
    SANCTION_ERROR_UNKNOWN_PLAYER_ISSUE("btccore.sanction.error.unknown_player_issue",
        "Unknown player '<player>'. They must have connected at least once before being sanctioned.", "player"),
    SANCTION_ERROR_UNKNOWN_PLAYER("btccore.sanction.error.unknown_player", "Unknown player '<player>'.", "player"),
    SANCTION_ERROR_REASON_REQUIRED("btccore.sanction.error.reason_required",
        "A reason is required. Usage: <usage>", "usage"),
    SANCTION_ERROR_LIFT_REASON_REQUIRED("btccore.sanction.error.lift_reason_required",
        "Lifting a sanction requires a reason."),
    SANCTION_ERROR_NOT_RECORDED("btccore.sanction.error.not_recorded",
        "The sanction could NOT be recorded: <error>", "error"),
    SANCTION_ERROR_HOST_ABSENT("btccore.sanction.error.host_absent",
        "Recorded, but the effect could not be applied now: the host plugin is absent."),
    SANCTION_HISTORY_EMPTY("btccore.sanction.history.empty", "<green><target> has no moderation history.", "target"),
    SANCTION_HISTORY_HEADER("btccore.sanction.history.header", "<gold>── History: <target> (<count>) ──",
        "target", "count"),
    SANCTION_HISTORY_LIFTED("btccore.sanction.history.lifted", "lifted"),
    SANCTION_HISTORY_BY_STAFF("btccore.sanction.history.by_staff", " — by staff"),
    SANCTION_HISTORY_AUTOMATIC("btccore.sanction.history.automatic", " — automatic"),

    // ---- the sanction types, as a word inside another text
    SANCTION_TYPE_NOTE("btccore.sanction.type.note", "note"),
    SANCTION_TYPE_WARN("btccore.sanction.type.warn", "warn"),
    SANCTION_TYPE_MUTE("btccore.sanction.type.mute", "mute"),
    SANCTION_TYPE_RESTRICT("btccore.sanction.type.restrict", "restrict"),
    SANCTION_TYPE_QUARANTINE("btccore.sanction.type.quarantine", "quarantine"),
    SANCTION_TYPE_KICK("btccore.sanction.type.kick", "kick"),
    SANCTION_TYPE_BAN("btccore.sanction.type.ban", "ban"),

    // ---- time left on a sanction, as a phrase inside another text
    DURATION_PERMANENT("btccore.duration.permanent", "permanent"),
    DURATION_EXPIRED("btccore.duration.expired", "expired"),
    DURATION_DAYS_HOURS("btccore.duration.days_hours", "<days>d <hours>h", "days", "hours"),
    DURATION_HOURS_MINUTES("btccore.duration.hours_minutes", "<hours>h <minutes>m", "hours", "minutes"),
    DURATION_MINUTES("btccore.duration.minutes", "<minutes>m", "minutes"),
    DURATION_SECONDS("btccore.duration.seconds", "<seconds>s", "seconds"),

    // ---- refusals of a duration typed by staff
    DURATION_ERROR_MISSING("btccore.duration.error.missing", "no duration given; use 10m, 2h, 7d, or perm"),
    DURATION_ERROR_NO_UNIT("btccore.duration.error.no_unit",
        "'<input>' has no unit; use s, m, h, d, w (as in 10m or 7d), or perm", "input"),
    DURATION_ERROR_NO_AMOUNT("btccore.duration.error.no_amount", "'<input>' has no amount; try 10m or 7d", "input"),
    DURATION_ERROR_NOT_A_DURATION("btccore.duration.error.not_a_duration",
        "'<input>' is not a duration; use 10m, 2h, 7d, or perm", "input"),
    DURATION_ERROR_NOT_POSITIVE("btccore.duration.error.not_positive", "a duration must be positive"),
    DURATION_ERROR_UNKNOWN_UNIT("btccore.duration.error.unknown_unit",
        "unknown unit '<unit>'; use s, m, h, d, w, or perm", "unit"),
    DURATION_ERROR_TOO_LONG("btccore.duration.error.too_long",
        "that is longer than ten years; say 'perm' if you mean permanent"),

    // ---- refusals at login (btccore.yml: maintenance-mode.message) and the rest of what players see
    LOGIN_MAINTENANCE("btccore.login.maintenance", "<red>Server is under maintenance.",
        () -> BTCCoreConfig.maintenanceModeMessage),
    LOGIN_QUEUE_FULL("btccore.login.queue_full", "Server is full. Queue is full."),
    TELEPORT_CANCELLED("btccore.teleport.cancelled", "<red>Teleport cancelled"),
    TELEPORT_WARMUP("btccore.teleport.warmup", "<yellow>Teleporting in <seconds>s...", "seconds"),
    CLICK_CPS_LIMIT("btccore.click.cps_limit", "<red>Slow down! CPS limit: <limit>", "limit"),
    COMMAND_PING("btccore.command.ping", "<name>'s Ping: <ping>ms", "name", "ping"),
    COMMAND_PING_PLAYERS_ONLY("btccore.command.ping_players_only",
        "Only players can use this command without arguments."),
    // The default is French, as it was before the key existed: sources.yml files are already in place.
    WORLD_LEASE_LOST("btccore.world.lease_lost",
        "<red>Ce monde n'est plus disponible sur ce serveur. Reconnecte-toi pour le retrouver.</red>"),
    ADMIN_OP_REFUSED("btccore.admin.op_refused", "<red>You cannot use this command.",
        () -> AnticheatConfig.opLockRefusedMessage);

    private static final Set<String> KEYS =
        Arrays.stream(values()).map(PlayerText::key).collect(Collectors.toUnmodifiableSet());

    private final String key;
    private final String defaultTemplate;
    private final Supplier<String> configured;
    private final List<String> variables;

    PlayerText(String key, String defaultTemplate, String... variables) {
        this(key, defaultTemplate, null, variables);
    }

    PlayerText(String key, String defaultTemplate, Supplier<String> configured, String... variables) {
        this.key = key;
        this.defaultTemplate = defaultTemplate;
        this.configured = configured;
        this.variables = List.of(variables);
    }

    /** The key a provider is asked for: {@code btccore.<zone>.<meaning>}. */
    public String key() {
        return key;
    }

    /** The English MiniMessage text, as BTC-CORE ships it. */
    public String defaultTemplate() {
        return defaultTemplate;
    }

    /** The text to show without a provider: the admin's setting when the text has one, else the default. */
    public String template() {
        if (configured == null) {
            return defaultTemplate;
        }
        String value = configured.get();
        return value == null || value.isEmpty() ? defaultTemplate : value;
    }

    /** The names a text may use, in the order of the template: the only arguments it is ever given. */
    public List<String> variables() {
        return variables;
    }

    /** Every key, for {@code BTCCoreAPI#playerTextKeys()}. */
    public static Set<String> keys() {
        return KEYS;
    }
}
