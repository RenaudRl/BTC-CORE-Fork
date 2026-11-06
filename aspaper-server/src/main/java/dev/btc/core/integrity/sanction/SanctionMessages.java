package dev.btc.core.integrity.sanction;

import dev.btc.core.config.AnticheatConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The texts the linked-account behaviour (AD-090) shows to players and staff, rendered from the
 * MiniMessage templates of {@code anticheat.yml} ({@code sanctions.messages.*}).
 *
 * <p>Values written by staff (names, reasons) go in as {@link Placeholder#unparsed unparsed} text: a
 * reason containing {@code <click:...>} must stay text, not become a tag. A full account UUID is never
 * shown nor logged: {@link #shortId} keeps eight characters (AD-091).
 */
public final class SanctionMessages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

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
    public static Component bannedViaLinkedAccount() {
        return MINI.deserialize(AnticheatConfig.msgBannedViaLinkedAccount);
    }

    /** Shown to a player whose messages are stopped by a mute on a linked account: no reason, no duration. */
    public static Component mutedViaLinkedAccount() {
        return MINI.deserialize(AnticheatConfig.msgMutedViaLinkedAccount);
    }

    /**
     * What a banned player sees, whether kicked now or refused at the door. A ban issued against another
     * account of the person is not detailed: its reason and duration were written about that account,
     * and showing them would disclose the other account's case.
     *
     * @param viewer the uuid the server knows the player by
     */
    public static Component banMessage(Sanction ban, UUID viewer) {
        if (isOnAlias(ban, viewer)) {
            return bannedViaLinkedAccount();
        }
        String remaining =
            SanctionDuration.describeRemaining(System.currentTimeMillis(), ban.expiresAtMillis());
        return Component.text("You are banned (" + remaining + ")\n" + ban.reason(), NamedTextColor.RED);
    }

    /**
     * What a muted player is told when a message is stopped, or when the mute is issued. Same rule as
     * {@link #banMessage}: a mute written against another account is never detailed.
     *
     * @param remaining the human-readable time left, as {@link SanctionDuration#describeRemaining} gives it
     */
    public static Component muteNotice(Sanction mute, UUID viewer, String remaining) {
        if (isOnAlias(mute, viewer)) {
            return mutedViaLinkedAccount();
        }
        return mute.silent()
            ? Component.text("You cannot speak right now.", NamedTextColor.RED)
            : Component.text("You are muted for " + remaining + ": " + mute.reason(), NamedTextColor.RED);
    }

    /** Tells a moderator that a linked account's sanction still blocks the target. */
    public static Component liftBlockedByLinked(String target, Sanction blocker) {
        return MINI.deserialize(AnticheatConfig.msgLiftBlockedByLinked, TagResolver.resolver(
            Placeholder.unparsed("target", target),
            Placeholder.unparsed("type", blocker.type().name().toLowerCase(Locale.ROOT)),
            Placeholder.unparsed("account", blocker.playerUuid().map(SanctionMessages::shortId).orElse("?")),
            Placeholder.unparsed("reason", blocker.reason())));
    }

    /** Confirms a {@code --linked} lift. */
    public static Component liftedLinked(SanctionType type, String target, int count, String reason) {
        return MINI.deserialize(AnticheatConfig.msgLiftedLinked, TagResolver.resolver(
            Placeholder.unparsed("type", type.name().toLowerCase(Locale.ROOT)),
            Placeholder.unparsed("target", target),
            Placeholder.unparsed("count", Integer.toString(count)),
            Placeholder.unparsed("reason", reason)));
    }

    /** The linked accounts line of {@code /history}. */
    public static Component historyAliases(Collection<UUID> aliases) {
        return MINI.deserialize(AnticheatConfig.msgHistoryAliases, Placeholder.unparsed("accounts",
            aliases.stream().map(SanctionMessages::shortId).sorted().collect(Collectors.joining(", "))));
    }

    /** Suffix of a {@code /history} line whose sanction was issued against a linked account. */
    public static Component historyViaAlias(UUID account) {
        return MINI.deserialize(AnticheatConfig.msgHistoryViaAlias,
            Placeholder.unparsed("account", shortId(account)));
    }
}
