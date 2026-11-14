package dev.btc.core.integrity.chat;

import java.util.Optional;

/**
 * More than {@code ratio} of the letters in capitals, once the message has {@code min-words} words of three
 * letters or more: a short shout ("BONNE ANNEE", "GG WP EZ ALL") passes.
 * shortcut: one long word in capitals counts as one word and passes; set min-words to 1 to judge it.
 */
public final class CapsRule implements ChatRule {

    /** A word shorter than this ("GG", "WP", "EZ") is jargon, not shouting, and does not count toward the minimum. */
    private static final int MIN_WORD_LETTERS = 3;

    private final double ratio;
    private final int minWords;

    public CapsRule(ChatGuardConfig.Caps settings) {
        this.ratio = settings.ratio();
        this.minWords = settings.minWords();
    }

    @Override
    public ChatRuleId id() {
        return ChatRuleId.CAPS;
    }

    @Override
    public Optional<ChatFinding> check(ChatText text, PlayerChatHistory history, long nowMillis) {
        if (text.letters() == 0 || longWords(text.raw()) < minWords) {
            return Optional.empty();
        }
        if ((double) text.upperCase() / text.letters() > ratio) {
            return Optional.of(ChatFinding.of(ChatRuleId.CAPS, "capitals"));
        }
        return Optional.empty();
    }

    private static int longWords(String raw) {
        int words = 0;
        int run = 0;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                run++;
                if (run == MIN_WORD_LETTERS) {
                    words++;
                }
            } else {
                run = 0;
            }
        }
        return words;
    }
}
