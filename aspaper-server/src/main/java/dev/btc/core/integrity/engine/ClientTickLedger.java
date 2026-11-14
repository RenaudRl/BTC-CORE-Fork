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

    /** A server tick, nominally: the window in which {@link #tickEnded} bounds the tick ends it counts. */
    static final long SERVER_TICK_NANOS = 50_000_000L;

    private long windowStart;
    private boolean windowOpen;
    private int windowCount;

    /**
     * The client's tick ended: what follows belongs to the next one, unless the client already ended
     * {@code maxPerWindow} ticks inside this server tick: the tick end is the client's to send, and a client
     * that sends one between two attacks would make each its own tick. The excess ends no tick.
     *
     * @return how many tick ends the current window has received, this one included
     */
    int tickEnded(final long nowNanos, final int maxPerWindow) {
        if (!windowOpen || nowNanos - windowStart >= SERVER_TICK_NANOS) {
            windowOpen = true;
            windowStart = nowNanos;
            windowCount = 0;
        }
        windowCount++;
        armed = true;
        if (windowCount <= maxPerWindow) {
            tick++;
        }
        return windowCount;
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
