package dev.btc.core.integrity.chat;

import java.util.Optional;

/**
 * The same character {@code max-repeats} times in a row, or the same word. Digits, spaces and
 * private-use code points (pack glyphs) never count: "100000" and a row of emoji are not spam.
 */
public final class RepetitionRule implements ChatRule {

    private final int maxRepeats;

    public RepetitionRule(ChatGuardConfig.Repetition settings) {
        this.maxRepeats = settings.maxRepeats();
    }

    @Override
    public ChatRuleId id() {
        return ChatRuleId.REPETITION;
    }

    @Override
    public Optional<ChatFinding> check(ChatText text, PlayerChatHistory history, long nowMillis) {
        if (sameCharacterRun(text.raw()) >= maxRepeats) {
            return Optional.of(ChatFinding.of(ChatRuleId.REPETITION, "character"));
        }
        if (sameWordRun(text.normalized()) >= maxRepeats) {
            return Optional.of(ChatFinding.of(ChatRuleId.REPETITION, "word"));
        }
        return Optional.empty();
    }

    private static int sameCharacterRun(String raw) {
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        int best = 0;
        int run = 0;
        int previous = -1;
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isDigit(cp) || Character.isWhitespace(cp) || Character.getType(cp) == Character.PRIVATE_USE) {
                previous = -1;
                run = 0;
                continue;
            }
            run = cp == previous ? run + 1 : 1;
            previous = cp;
            best = Math.max(best, run);
        }
        return best;
    }

    private static int sameWordRun(String normalized) {
        int best = 0;
        int run = 0;
        String previous = null;
        for (String word : normalized.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            run = word.equals(previous) ? run + 1 : 1;
            previous = word;
            best = Math.max(best, run);
        }
        return best;
    }
}
