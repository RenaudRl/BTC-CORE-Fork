package dev.btc.core.integrity.chat;

import java.util.Optional;

/**
 * Token bucket: {@code burst} messages at once, then one more every {@code interval-ms}. Counted in
 * integer milliseconds of credit, so the refill at exactly one interval is exact, not a float guess.
 * A refused message costs nothing: a player who keeps typing is not locked out for longer.
 */
public final class RateRule implements ChatRule {

    private final long intervalMillis;
    private final long capacity;

    public RateRule(ChatGuardConfig.Rate settings) {
        this.intervalMillis = settings.intervalMillis();
        this.capacity = settings.intervalMillis() * settings.burst();
    }

    @Override
    public ChatRuleId id() {
        return ChatRuleId.RATE;
    }

    @Override
    public Optional<ChatFinding> check(ChatText text, PlayerChatHistory history, long nowMillis) {
        long credit = history.creditAt(nowMillis, capacity);
        if (credit >= intervalMillis) {
            return Optional.empty();
        }
        return Optional.of(new ChatFinding(ChatRuleId.RATE, intervalMillis - credit, "rate"));
    }

    @Override
    public void accepted(ChatText text, PlayerChatHistory history, long nowMillis) {
        history.spend(nowMillis, capacity, intervalMillis);
    }
}
