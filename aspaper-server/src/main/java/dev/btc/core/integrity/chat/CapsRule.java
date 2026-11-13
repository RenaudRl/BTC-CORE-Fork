package dev.btc.core.integrity.chat;

import java.util.Optional;

/** More than {@code ratio} of the letters in capitals, once there are {@code min-letters} letters: a short shout passes. */
public final class CapsRule implements ChatRule {

    private final double ratio;
    private final int minLetters;

    public CapsRule(ChatGuardConfig.Caps settings) {
        this.ratio = settings.ratio();
        this.minLetters = settings.minLetters();
    }

    @Override
    public ChatRuleId id() {
        return ChatRuleId.CAPS;
    }

    @Override
    public Optional<ChatFinding> check(ChatText text, PlayerChatHistory history, long nowMillis) {
        if (text.letters() < minLetters) {
            return Optional.empty();
        }
        if ((double) text.upperCase() / text.letters() > ratio) {
            return Optional.of(ChatFinding.of(ChatRuleId.CAPS, "capitals"));
        }
        return Optional.empty();
    }
}
