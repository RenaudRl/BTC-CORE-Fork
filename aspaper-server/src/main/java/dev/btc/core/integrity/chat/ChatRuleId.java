package dev.btc.core.integrity.chat;

import java.util.Locale;

/** The rules of the chat guard, in the order they run: the cheapest first. */
public enum ChatRuleId {
    RATE, REPETITION, CAPS, UNICODE, SIMILARITY, ADVERTISING, WORDS;

    /** The name used in {@code chat-guard.yml}, the journal and {@code /chatguard}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
