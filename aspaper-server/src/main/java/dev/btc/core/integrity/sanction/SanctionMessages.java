package dev.btc.core.integrity.sanction;

import dev.btc.core.text.PlayerText;
import dev.btc.core.text.PlayerTexts;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The texts the linked-account behaviour (AD-090) shows to players and staff. Each one is a
 * {@link PlayerText}: the language of the network's text provider when there is one, the MiniMessage
 * template of {@code anticheat.yml} ({@code sanctions.messages.*}) when there is not.
 *
 * <p>Everything a person wrote (names, reasons, errors) goes in as {@link Component#text(String)}: a
 * reason containing {@code <click:...>} stays text, here and in the provider. A full account UUID is
 * never shown nor logged: {@link #shortId} keeps eight characters (AD-091).
 *
 * <p>The texts a player sees about a linked account (AD-091) are given no argument at all: a
 * translation cannot show a reason or a duration it is never handed.
 *
 * <p>{@code viewer} is the player the text is for, {@code null} for the console, which never reaches
 * the provider. The types and the durations inside a text are rendered for that same viewer.
 */
public final class SanctionMessages {

    private SanctionMessages() {}

    /** First eight characters of a UUID: enough to tell accounts apart, not enough to identify one. */
    public static String shortId(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }

    /** Whether {@code sanction} was issued against an account other than {@code viewer}. */
    public static boolean isOnAlias(Sanction sanction, UUID viewer) {
        return sanction.playerUuid().filter(account -> !account.equals(viewer)).isPresent();
    }

    /** Shown to a player refused or kicked because of a ban on a linked account: no reason, no author. */
    public static Component bannedViaLinkedAccount(@Nullable UUID viewer) {
        return render(viewer, PlayerText.SANCTION_BANNED_LINKED, Map.of());
    }

    /** Shown to a player whose messages are stopped by a mute on a linked account: no reason, no duration. */
    public static Component mutedViaLinkedAccount(@Nullable UUID viewer) {
        return render(viewer, PlayerText.SANCTION_MUTED_LINKED, Map.of());
    }

    /**
     * What a banned player sees, whether kicked now or refused at the door. A ban issued against another
     * account of the person is not detailed: its reason and duration were written about that account,
     * and showing them would disclose the other account's case.
     *
     * @param viewer the uuid the server knows the player by
     */
    public static Component banMessage(Sanction ban, UUID viewer) {
        try {
            if (isOnAlias(ban, viewer)) {
                return bannedViaLinkedAccount(viewer);
            }
            SanctionDuration.Remaining remaining =
                SanctionDuration.remaining(System.currentTimeMillis(), ban.expiresAtMillis());
            return render(viewer, PlayerText.SANCTION_BANNED, Map.of(
                "remaining", duration(viewer, remaining),
                "reason", Component.text(ban.reason())));
        } catch (Throwable failure) {
            // The refusal must never depend on the wording: a ban is applied whatever the text did.
            warnBuiltIn(failure);
            if (isOnAlias(ban, viewer)) {
                return PlayerTexts.builtIn(PlayerText.SANCTION_BANNED_LINKED, Map.of());
            }
            return PlayerTexts.builtIn(PlayerText.SANCTION_BANNED, Map.of(
                "remaining", Component.text(SanctionDuration.describeRemaining(System.currentTimeMillis(), ban.expiresAtMillis())),
                "reason", Component.text(String.valueOf(ban.reason()))));
        }
    }

    private static void warnBuiltIn(Throwable failure) {
        org.apache.logging.log4j.LogManager.getLogger("BTCCore").warn(
            "[BTCCore] a sanction text could not be rendered; the built-in text is shown so the sanction still applies.", failure);
    }

    /**
     * The one answer to "what is the online player told about this fresh sanction", for the issuing
     * command. Empty when the player is told nothing (a note, a silent mute, a restriction).
     *
     * <p>A kick and a warning obey the same alias rule as a ban: the reason was written about another
     * account, so the alias gets a generic text.
     */
    public static Optional<Component> noticeFor(Sanction sanction, UUID viewer,
                                                SanctionDuration.Remaining remaining) {
        return switch (sanction.type()) {
            case BAN -> Optional.of(banMessage(sanction, viewer));
            case KICK -> Optional.of(isOnAlias(sanction, viewer)
                ? render(viewer, PlayerText.SANCTION_KICKED_LINKED, Map.of())
                : render(viewer, PlayerText.SANCTION_KICKED, Map.of("reason", Component.text(sanction.reason()))));
            case WARN -> Optional.of(isOnAlias(sanction, viewer)
                ? render(viewer, PlayerText.SANCTION_WARNED_LINKED, Map.of())
                : render(viewer, PlayerText.SANCTION_WARNED, Map.of("reason", Component.text(sanction.reason()))));
            case MUTE -> sanction.silent()
                ? Optional.empty()
                : Optional.of(muteNotice(sanction, viewer, remaining));
            case NOTE, RESTRICT, QUARANTINE -> Optional.empty();
        };
    }

    /** Tells a moderator a sanction was recorded. */
    public static Component recorded(@Nullable UUID viewer, SanctionType type, String target,
                                     SanctionDuration.Remaining duration, String reason) {
        return render(viewer, PlayerText.SANCTION_RECORDED, Map.of(
            "type", typeName(viewer, type),
            "target", Component.text(target),
            "duration", duration(viewer, duration),
            "reason", Component.text(reason)));
    }

    /** Confirms a plain (not {@code --linked}) lift. */
    public static Component lifted(@Nullable UUID viewer, SanctionType type, String target, String reason) {
        return render(viewer, PlayerText.SANCTION_LIFTED, Map.of(
            "type", typeName(viewer, type),
            "target", Component.text(target),
            "reason", Component.text(reason)));
    }

    /** Someone else lifted the sanction between the read and the write. */
    public static Component alreadyLifted(@Nullable UUID viewer) {
        return render(viewer, PlayerText.SANCTION_ALREADY_LIFTED, Map.of());
    }

    /** The target has nothing of this type to lift, not even through a linked account. */
    public static Component nothingToLift(@Nullable UUID viewer, SanctionType type, String target) {
        return render(viewer, PlayerText.SANCTION_NOTHING_TO_LIFT, Map.of(
            "type", typeName(viewer, type),
            "target", Component.text(target)));
    }

    /**
     * What a muted player is told when a message is stopped, or when the mute is issued. Same rule as
     * {@link #banMessage}: a mute written against another account is never detailed.
     *
     * @param remaining the time left, as {@link SanctionDuration#remaining} gives it
     */
    public static Component muteNotice(Sanction mute, UUID viewer, SanctionDuration.Remaining remaining) {
        try {
            if (isOnAlias(mute, viewer)) {
                return mutedViaLinkedAccount(viewer);
            }
            return mute.silent()
                ? render(viewer, PlayerText.SANCTION_MUTED_SILENT, Map.of())
                : render(viewer, PlayerText.SANCTION_MUTED, Map.of(
                    "remaining", duration(viewer, remaining),
                    "reason", Component.text(mute.reason())));
        } catch (Throwable failure) {
            // Same duty as banMessage: the message that goes with a refusal never replaces the refusal.
            warnBuiltIn(failure);
            if (isOnAlias(mute, viewer)) {
                return PlayerTexts.builtIn(PlayerText.SANCTION_MUTED_LINKED, Map.of());
            }
            return mute.silent()
                ? PlayerTexts.builtIn(PlayerText.SANCTION_MUTED_SILENT, Map.of())
                : PlayerTexts.builtIn(PlayerText.SANCTION_MUTED, Map.of(
                    "remaining", Component.text(PlayerTexts.plainDefault(remaining.text(), remaining.arguments())),
                    "reason", Component.text(String.valueOf(mute.reason()))));
        }
    }

    /** Tells a moderator that a linked account's sanction still blocks the target. */
    public static Component liftBlockedByLinked(@Nullable UUID viewer, String target, Sanction blocker) {
        return render(viewer, PlayerText.SANCTION_LIFT_BLOCKED_BY_LINKED, blockerArguments(viewer, target, blocker));
    }

    /**
     * Tells a moderator that a lift left a linked account's sanction in force, once the target's own
     * was lifted: the wording of {@link #liftBlockedByLinked} ("nothing was lifted") would be false.
     */
    public static Component liftStillBlockedByLinked(@Nullable UUID viewer, String target, Sanction blocker) {
        return render(viewer, PlayerText.SANCTION_LIFT_STILL_BLOCKED_BY_LINKED,
            blockerArguments(viewer, target, blocker));
    }

    /** Confirms a {@code --linked} lift, naming the accounts it reached by their first eight characters. */
    public static Component liftedLinked(@Nullable UUID viewer, SanctionType type, String target,
                                         Collection<UUID> accounts, String reason) {
        return render(viewer, PlayerText.SANCTION_LIFTED_LINKED, Map.of(
            "type", typeName(viewer, type),
            "target", Component.text(target),
            "count", Component.text(accounts.size()),
            "accounts", Component.text(shortIds(accounts)),
            "reason", Component.text(reason)));
    }

    /** Some of the sanctions of a lift were revoked and some were not. */
    public static Component liftPartial(@Nullable UUID viewer, SanctionType type, String target,
                                        int lifted, int total, int failed) {
        return render(viewer, PlayerText.SANCTION_LIFT_PARTIAL, Map.of(
            "type", typeName(viewer, type),
            "target", Component.text(target),
            "lifted", Component.text(lifted),
            "total", Component.text(total),
            "failed", Component.text(failed)));
    }

    /** A lift that failed outright. */
    public static Component liftFailed(@Nullable UUID viewer, SanctionType type, String target, String error) {
        return render(viewer, PlayerText.SANCTION_LIFT_FAILED, Map.of(
            "type", typeName(viewer, type),
            "target", Component.text(target),
            "error", Component.text(error)));
    }

    /** A history that could not be read. */
    public static Component historyFailed(@Nullable UUID viewer, String target, String error) {
        return render(viewer, PlayerText.SANCTION_HISTORY_FAILED, Map.of(
            "target", Component.text(target),
            "error", Component.text(error)));
    }

    /** The linked accounts line of {@code /history}. */
    public static Component historyAliases(@Nullable UUID viewer, Collection<UUID> aliases) {
        return render(viewer, PlayerText.SANCTION_HISTORY_ALIASES,
            Map.of("accounts", Component.text(shortIds(aliases))));
    }

    /** Suffix of a {@code /history} line whose sanction was issued against a linked account. */
    public static Component historyViaAlias(@Nullable UUID viewer, UUID account) {
        return render(viewer, PlayerText.SANCTION_HISTORY_VIA_ALIAS, Map.of("account", Component.text(shortId(account))));
    }

    /** A sanction type as a word inside another text, in the viewer's language. */
    public static Component typeName(@Nullable UUID viewer, SanctionType type) {
        return render(viewer, textOf(type), Map.of());
    }

    /** The time left on a sanction as a phrase inside another text, in the viewer's language. */
    public static Component duration(@Nullable UUID viewer, SanctionDuration.Remaining remaining) {
        return render(viewer, remaining.text(), remaining.arguments());
    }

    /**
     * {@code message} behind {@code prefix}. A message that sets no colour or decoration takes the one the
     * prefix ends with, as when it was appended to a coloured prefix; whatever it sets itself is kept.
     * The prefix comes from a template or a provider, so its last styled part is looked up, not assumed.
     */
    public static Component prefixed(Component prefix, Component message) {
        return Component.textOfChildren(prefix, message.applyFallbackStyle(trailingStyle(prefix)));
    }

    private static net.kyori.adventure.text.format.Style trailingStyle(Component component) {
        List<Component> children = component.children();
        return children.isEmpty()
            ? component.style()
            : component.style().merge(trailingStyle(children.get(children.size() - 1)));
    }

    /** One of the texts of the moderation commands, for the same viewer. */
    public static Component render(@Nullable UUID viewer, PlayerText text, Map<String, Component> arguments) {
        return PlayerTexts.shared().render(viewer, text, arguments);
    }

    // Exhaustive on purpose: a new sanction type fails to compile here until it has its text.
    private static PlayerText textOf(SanctionType type) {
        return switch (type) {
            case NOTE -> PlayerText.SANCTION_TYPE_NOTE;
            case WARN -> PlayerText.SANCTION_TYPE_WARN;
            case MUTE -> PlayerText.SANCTION_TYPE_MUTE;
            case RESTRICT -> PlayerText.SANCTION_TYPE_RESTRICT;
            case QUARANTINE -> PlayerText.SANCTION_TYPE_QUARANTINE;
            case KICK -> PlayerText.SANCTION_TYPE_KICK;
            case BAN -> PlayerText.SANCTION_TYPE_BAN;
        };
    }

    private static String shortIds(Collection<UUID> accounts) {
        return accounts.stream().map(SanctionMessages::shortId).sorted().collect(Collectors.joining(", "));
    }

    private static Map<String, Component> blockerArguments(@Nullable UUID viewer, String target, Sanction blocker) {
        return Map.of(
            "target", Component.text(target),
            "type", typeName(viewer, blocker.type()),
            "account", Component.text(blocker.playerUuid().map(SanctionMessages::shortId).orElse("?")),
            "reason", Component.text(blocker.reason()));
    }
}
