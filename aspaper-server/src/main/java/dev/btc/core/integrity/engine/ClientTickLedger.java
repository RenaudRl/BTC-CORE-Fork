package dev.btc.core.integrity.engine;

/**
 * What one session did inside the current client tick.
 *
 * <p>A client tick ends with its {@code client_tick_end} packet (Paper's {@code ClientTickEndEvent}):
 * every packet between two of them was sent by the same client tick, however late the network delivers
 * them and however many arrive inside one server tick. That is the whole reason for counting client
 * ticks rather than server ticks: a server tick that receives the packets of two client ticks, which a
 * jitter or a lag spike does all the time, would read an honest "attack, then switch slot" as one tick.
 *
 * <p>Owned by the region thread that owns the player, like {@link SessionState}.
 */
final class ClientTickLedger {

    /** What the last action of the tick was; only for the journal. */
    enum Action {
        ATTACK("an attack"),
        INTERACTION("an entity interaction"),
        ITEM_USE("an item use");

        private final String label;

        Action(final String label) {
            this.label = label;
        }

        /** Journal wording: developer-facing, not shown to a player. */
        String label() {
            return label;
        }
    }

    /** Ticks completed so far. */
    private long tick;

    /** Whether a tick end was ever seen: a client that sends none has no tick to judge by. */
    private boolean armed;

    private final int[] targets;
    private int targetCount;
    private long targetsTick = -1;

    private Action lastAction;
    private long actionTick = -1;

    /** @param maxTargets distinct targets a tick may attack before {@link #attacked} reports more */
    ClientTickLedger(final int maxTargets) {
        // One more than allowed: knowing that the limit was passed does not need to know by how much.
        this.targets = new int[Math.max(1, maxTargets) + 1];
    }

    /** A server tick, nominally: the bucket of {@link #tickEnded} gains one credit per this much time. */
    static final long SERVER_TICK_NANOS = 50_000_000L;

    private boolean bucketStarted;
    private long credits;
    private long refilledAt;

    /**
     * The client's tick ended: what follows belongs to the next one. When {@code bounded}, each tick end
     * spends a credit from a bucket that holds {@code burst} and gains one per server tick. A client back
     * from a freeze spends what it saved on its backlog; one that sends tick ends faster than it ticks, to
     * make each attack its own tick, runs dry, and its excess ends no tick. Unbounded, every one ends a tick.
     *
     * @return whether this tick end ended a tick
     */
    boolean tickEnded(final long nowNanos, final int burst, final boolean bounded) {
        armed = true;
        if (bounded && !spendCredit(nowNanos, burst)) {
            return false;
        }
        tick++;
        return true;
    }

    private boolean spendCredit(final long nowNanos, final int burst) {
        if (!bucketStarted) {
            bucketStarted = true;
            credits = burst;
            refilledAt = nowNanos;
        } else {
            final long gained = (nowNanos - refilledAt) / SERVER_TICK_NANOS;
            if (gained > 0) {
                credits = Math.min(burst, credits + gained);
                refilledAt += gained * SERVER_TICK_NANOS;
            }
        }
        if (credits <= 0) {
            return false;
        }
        credits--;
        return true;
    }

    /** Whether the client has shown it ends its ticks. Before that, nothing is judged by tick. */
    boolean armed() {
        return armed;
    }

    /** Records an action of this tick. */
    void acted(final Action action) {
        lastAction = action;
        actionTick = tick;
    }

    /** The action done earlier in this client tick, or {@code null} when none was. */
    Action actionThisTick() {
        return actionTick == tick ? lastAction : null;
    }

    /**
     * Records an attack on {@code target} and returns how many different targets this tick has attacked,
     * counted up to one more than the allowed number.
     */
    int attacked(final int target) {
        acted(Action.ATTACK);
        if (targetsTick != tick) {
            targetsTick = tick;
            targetCount = 0;
        }
        for (int i = 0; i < targetCount; i++) {
            if (targets[i] == target) {
                return targetCount;
            }
        }
        if (targetCount < targets.length) {
            targets[targetCount++] = target;
        }
        return targetCount;
    }
}
