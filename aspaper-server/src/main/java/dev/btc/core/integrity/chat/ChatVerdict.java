package dev.btc.core.integrity.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The guard's answer for one message.
 *
 * @param refusal         the finding of a rule in {@code enforce} mode: the message must not be sent
 * @param observed        findings of rules in {@code observe} mode: journalled, the message goes through
 * @param journalHeld     -1 when nothing is to be journalled now (no finding, or the player's journal
 *                        interval has not elapsed); otherwise how many lines were held back since the last one
 * @param staffAlertCount present when this message is the one that crosses the staff alert threshold:
 *                        how many findings the player has within the window
 */
public record ChatVerdict(Optional<ChatFinding> refusal, List<ChatFinding> observed, int journalHeld,
                          OptionalInt staffAlertCount) {

    /** Nothing found, or the guard did not look (off, or a bypassing player). */
    public static final ChatVerdict CLEAN = new ChatVerdict(Optional.empty(), List.of(), -1, OptionalInt.empty());

    public ChatVerdict {
        observed = List.copyOf(observed);
    }

    /** Whether the message must be cancelled. */
    public boolean cancels() {
        return refusal.isPresent();
    }

    /** Every finding, the refusal first. */
    public List<ChatFinding> findings() {
        List<ChatFinding> all = new ArrayList<>(observed.size() + 1);
        refusal.ifPresent(all::add);
        all.addAll(observed);
        return all;
    }

    public boolean journals() {
        return journalHeld >= 0 && (refusal.isPresent() || !observed.isEmpty());
    }
}
