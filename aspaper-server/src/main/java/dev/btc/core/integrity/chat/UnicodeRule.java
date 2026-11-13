package dev.btc.core.integrity.chat;

import java.util.List;
import java.util.Optional;

/**
 * Characters used to disguise or crash a message: stacked combining marks (zalgo), private-use code
 * points outside the allowed ranges, and (when asked) a word mixing Latin with Cyrillic or Greek.
 *
 * <p>Accented Latin, Cyrillic, Greek and every other script pass on their own. The private-use ranges
 * hold the resource-pack glyphs of the server, so the shipped setting allows all of them: the admin
 * narrows it to the ranges the pack really uses. This rule never changes a character.
 */
public final class UnicodeRule implements ChatRule {

    private final int maxCombining;
    private final List<ChatGuardConfig.CodePointRange> allowedPrivateUse;
    private final boolean refuseMixedScripts;

    public UnicodeRule(ChatGuardConfig.Unicode settings) {
        this.maxCombining = settings.maxCombiningPerChar();
        this.allowedPrivateUse = settings.allowedPrivateUse();
        this.refuseMixedScripts = settings.refuseMixedScripts();
    }

    @Override
    public ChatRuleId id() {
        return ChatRuleId.UNICODE;
    }

    @Override
    public Optional<ChatFinding> check(ChatText text, PlayerChatHistory history, long nowMillis) {
        if (text.combiningMax() > maxCombining) {
            return Optional.of(ChatFinding.of(ChatRuleId.UNICODE, "combining"));
        }
        String raw = text.raw();
        if (hasForbiddenPrivateUse(raw)) {
            return Optional.of(ChatFinding.of(ChatRuleId.UNICODE, "private-use"));
        }
        if (refuseMixedScripts && hasMixedScriptWord(raw)) {
            return Optional.of(ChatFinding.of(ChatRuleId.UNICODE, "mixed-script"));
        }
        return Optional.empty();
    }

    private boolean hasForbiddenPrivateUse(String raw) {
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) == Character.PRIVATE_USE && allowedPrivateUse.stream().noneMatch(range -> range.contains(cp))) {
                return true;
            }
        }
        return false;
    }

    /** A word with letters of two of Latin, Cyrillic, Greek: the look-alike trick, not Japanese. */
    private static boolean hasMixedScriptWord(String raw) {
        boolean latin = false;
        boolean cyrillic = false;
        boolean greek = false;
        // A trailing space ends the last word, so the loop needs no second check after it.
        String text = raw + " ";
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                switch (Character.UnicodeScript.of(cp)) {
                    case LATIN -> latin = true;
                    case CYRILLIC -> cyrillic = true;
                    case GREEK -> greek = true;
                    default -> { }
                }
                continue;
            }
            if ((latin ? 1 : 0) + (cyrillic ? 1 : 0) + (greek ? 1 : 0) > 1) {
                return true;
            }
            latin = false;
            cyrillic = false;
            greek = false;
        }
        return false;
    }
}
