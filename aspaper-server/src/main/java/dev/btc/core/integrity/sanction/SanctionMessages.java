package dev.btc.core.integrity.sanction;

import dev.btc.core.config.AnticheatConfig;
import net.kyori.adventure.text.Component;
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
