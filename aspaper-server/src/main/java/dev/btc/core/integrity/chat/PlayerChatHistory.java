package dev.btc.core.integrity.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * What the guard remembers about one player: a refill account for the rate rule, the last normalized
 * messages, and the clocks of the staff alert and of the journal.
 *
 * <p>Not thread-safe by itself. Public chat runs on an asynchronous thread and a private message on the
 * player's region thread, and both land here, so {@link ChatGuard} holds this object's monitor around
 * every call, a check and its commit included. Times are the epoch milliseconds the caller passes in.
 */
public final class PlayerChatHistory {

    /** A remembered message, already normalized and bounded. */
    public record Entry(String text, long atMillis) {}

    /** Longest remembered message: bounds the comparison cost whatever the chat limit is. */
    static final int MAX_REMEMBERED_CHARS = 256;

    private final ArrayDeque<Entry> recent = new ArrayDeque<>();

    // Rate: credit counted in milliseconds. A message costs one interval; the account holds burst intervals.
    private boolean accountOpened;
    private long credit;
    private long creditUpdatedAt;

    private final ArrayDeque<Long> hits = new ArrayDeque<>();
    private boolean alerted;
    private long alertedAt;

    private boolean journaled;
    private long journaledAt;
    private int journalSuppressed;

    /** The remembered messages, oldest first. */
    List<Entry> recent() {
        return new ArrayList<>(recent);
    }

    void remember(String normalized, long now, int limit) {
        String bounded = normalized.length() > MAX_REMEMBERED_CHARS
            ? normalized.substring(0, MAX_REMEMBERED_CHARS) : normalized;
        recent.addLast(new Entry(bounded, now));
        while (recent.size() > limit) {
            recent.removeFirst();
        }
    }

    /** Milliseconds of credit at {@code now}: a new player starts with a full account. */
    long creditAt(long now, long capacity) {
        if (!accountOpened) {
            return capacity;
        }
        return Math.min(capacity, credit + Math.max(0L, now - creditUpdatedAt));
    }

    void spend(long now, long capacity, long cost) {
        credit = Math.max(0L, creditAt(now, capacity) - cost);
        creditUpdatedAt = now;
        accountOpened = true;
    }

    /** Records one finding and returns how many fall inside the window ending now. */
    int recordHit(long now, long windowMillis) {
        hits.addLast(now);
        while (!hits.isEmpty() && now - hits.peekFirst() > windowMillis) {
            hits.removeFirst();
        }
        return hits.size();
    }

    /** Whether an alert may go out now; when it may, the cooldown starts. */
    boolean takeAlert(long now, long cooldownMillis) {
        if (alerted && now - alertedAt < cooldownMillis) {
            return false;
        }
        alerted = true;
        alertedAt = now;
        return true;
    }

    /**
     * @return how many lines were held back since the last one when a line may be written now, or -1
     *         when this one is held back too
     */
    int takeJournalSlot(long now, long minIntervalMillis) {
        if (journaled && now - journaledAt < minIntervalMillis) {
            journalSuppressed++;
            return -1;
        }
        int held = journalSuppressed;
        journaled = true;
        journaledAt = now;
        journalSuppressed = 0;
        return held;
    }
}
