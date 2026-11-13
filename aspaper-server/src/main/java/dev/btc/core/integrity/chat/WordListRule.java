package dev.btc.core.integrity.chat;

import java.util.Optional;

/** A word of the language lists (see {@link WordLists}); the finding names the language, never the word. */
public final class WordListRule implements ChatRule {

    private final WordLists lists;

    public WordListRule(WordLists lists) {
        this.lists = lists;
    }

    @Override
    public ChatRuleId id() {
        return ChatRuleId.WORDS;
    }

    @Override
    public Optional<ChatFinding> check(ChatText text, PlayerChatHistory history, long nowMillis) {
        return lists.firstMatch(text).map(language -> ChatFinding.of(ChatRuleId.WORDS, language));
    }
}
