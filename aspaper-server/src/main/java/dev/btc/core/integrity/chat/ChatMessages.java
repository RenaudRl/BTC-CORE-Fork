package dev.btc.core.integrity.chat;

import dev.btc.core.text.PlayerText;
import dev.btc.core.text.PlayerTexts;
import net.kyori.adventure.text.Component;

import java.util.Map;
import java.util.UUID;

/**
 * What a player is told when a message is refused, and what staff are told when one player keeps
 * tripping the filter. Both are {@link PlayerText}s: the language of the text provider when there is
 * one, else the template of {@code chat-guard.yml} {@code messages.<key>}, else the English default.
 * Names are given as plain text and never parsed as markup.
 */
public final class ChatMessages {

    private ChatMessages() {}

    /** The text that says why a message was refused, in the viewer's language. */
    public static Component refusal(UUID viewer, ChatFinding finding, Map<String, String> overrides) {
        PlayerText text = textOf(finding.rule());
        Map<String, Component> arguments = finding.rule() == ChatRuleId.RATE
            ? Map.of("seconds", Component.text(secondsToWait(finding.retryAfterMillis())))
            : Map.of();
        return PlayerTexts.shared().render(viewer, text, overrides.getOrDefault(text.key(), text.template()), arguments);
    }

    /** The alert staff receive; {@code viewer} is the staff member, {@code player} the one who tripped the filter. */
    public static Component staffAlert(UUID viewer, String player, ChatRuleId rule, int count, Map<String, String> overrides) {
        PlayerText text = PlayerText.CHAT_STAFF_ALERT;
        return PlayerTexts.shared().render(viewer, text, overrides.getOrDefault(text.key(), text.template()), Map.of(
            "player", Component.text(player),
            "rule", Component.text(rule.key()),
            "count", Component.text(count)));
    }

    /** Whole seconds, rounded up, never less than one: "wait 0s" would be an insult. */
    static long secondsToWait(long millis) {
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    // Exhaustive on purpose: a new rule fails to compile here until it has its text.
    static PlayerText textOf(ChatRuleId rule) {
        return switch (rule) {
            case RATE -> PlayerText.CHAT_REFUSED_RATE;
            case REPETITION -> PlayerText.CHAT_REFUSED_REPETITION;
            case CAPS -> PlayerText.CHAT_REFUSED_CAPS;
            case UNICODE -> PlayerText.CHAT_REFUSED_UNICODE;
            case SIMILARITY -> PlayerText.CHAT_REFUSED_SIMILARITY;
            case ADVERTISING -> PlayerText.CHAT_REFUSED_ADVERTISING;
            case WORDS -> PlayerText.CHAT_REFUSED_WORDS;
        };
    }
}
