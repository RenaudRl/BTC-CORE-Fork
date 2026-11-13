package dev.btc.core.integrity.chat;

import java.util.List;
import java.util.Optional;

/**
 * The {@code count}-th message within {@code window-seconds} that is at least {@code threshold} like the
 * ones before it is refused (similarity = 1 - Levenshtein / longer length, on the normalized texts). The
 * messages that went through are remembered; the refused ones are not, so a refused message never makes
 * the next one look repeated. Messages shorter than {@code min-length} are not compared.
 */
public final class SimilarityRule implements ChatRule {

    private final double threshold;
    private final int count;
    private final long windowMillis;
    private final int history;
    private final int minLength;

    public SimilarityRule(ChatGuardConfig.Similarity settings) {
        this.threshold = settings.threshold();
        this.count = settings.count();
        this.windowMillis = settings.windowSeconds() * 1000L;
        this.history = settings.history();
        this.minLength = settings.minLength();
    }

    @Override
    public ChatRuleId id() {
        return ChatRuleId.SIMILARITY;
    }

    @Override
    public Optional<ChatFinding> check(ChatText text, PlayerChatHistory history, long nowMillis) {
        if (text.normalized().length() < minLength) {
            return Optional.empty();
        }
        List<PlayerChatHistory.Entry> recent = history.recent();
        int similar = 0;
        for (PlayerChatHistory.Entry entry : recent) {
            if (nowMillis - entry.atMillis() <= windowMillis && similarity(text.normalized(), entry.text()) >= threshold) {
                similar++;
            }
        }
        // The message under judgement is the count-th when count-1 earlier ones are alike.
        return similar >= count - 1 ? Optional.of(ChatFinding.of(ChatRuleId.SIMILARITY, "similar")) : Optional.empty();
    }

    @Override
    public void accepted(ChatText text, PlayerChatHistory history, long nowMillis) {
        history.remember(text.normalized(), nowMillis, this.history);
    }

    /**
     * A longer text is compared by its first and by its last this many characters (the length of a
     * Minecraft chat message), and the closer of the two counts: a fresh opening line cannot hide a
     * repeated end, nor a fresh ending a repeated start. Measured: a whole 1024-character comparison
     * costs about 3 ms, 15 ms per message at history 5 and 150 ms at history 50; head and tail stay
     * near 0.4 ms.
     */
    static final int MAX_COMPARED_CHARS = 256;

    /** 1 for equal texts, 0 for nothing in common. */
    static double similarity(String first, String second) {
        double head = whole(head(first), head(second));
        if (first.length() <= MAX_COMPARED_CHARS && second.length() <= MAX_COMPARED_CHARS) {
            return head;
        }
        return Math.max(head, whole(tail(first), tail(second)));
    }

    private static String head(String text) {
        return text.length() > MAX_COMPARED_CHARS ? text.substring(0, MAX_COMPARED_CHARS) : text;
    }

    private static String tail(String text) {
        return text.length() > MAX_COMPARED_CHARS ? text.substring(text.length() - MAX_COMPARED_CHARS) : text;
    }

    private static double whole(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int longer = Math.max(a.length(), b.length());
        if (longer == 0) {
            return 1.0;
        }
        return 1.0 - (double) distance(a, b) / longer;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
