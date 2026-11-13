package dev.btc.core.integrity.chat;

import java.util.Optional;

/**
 * One reason to refuse a message. A rule is built from its settings, holds no state of its own (a
 * player's state lives in {@link PlayerChatHistory}) and reads the clock only through its arguments.
 */
public interface ChatRule {

    ChatRuleId id();

    /**
     * Judges {@code text}. Changes nothing: a message that another rule refuses must leave no trace.
     * Called with the player's history locked.
     */
    Optional<ChatFinding> check(ChatText text, PlayerChatHistory history, long nowMillis);

    /**
     * The message went through (it was not refused, whatever the mode of the rules that merely observed):
     * the place for a rule to charge it to the player's state. Called with the history locked.
     */
    default void accepted(ChatText text, PlayerChatHistory history, long nowMillis) {}
}
