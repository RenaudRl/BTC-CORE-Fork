package dev.btc.core.integrity.engine;

import java.util.concurrent.TimeUnit;

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

    /**
     * What the time a client spends talking refills, at most: the tick end of the next tick arriving with this one
     * is network jitter. Only a silence refills the rest of the burst.
     */
    static final int ACTIVE_CREDITS = 2;

    private boolean bucketStarted;
    private long credits;
    private long refilledAt;

    /**
     * The shortest gap with no packet at all that counts as a silence. Shorter gaps credit nothing: a client sending
     * at a steady pace with its tick ends held back must not add up its small gaps into a burst.
     */
    static final long SILENCE_MIN_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    private boolean heard;
    private long lastPacketAt;
    /** Silences not yet turned into credits: the whole length of each gap of at least {@link #SILENCE_MIN_NANOS}. */
    private long silentNanos;

    /**
     * Any packet of the client, tick ends included. A gap of at least {@link #SILENCE_MIN_NANOS} is a silence: a
     * frozen client, region or network sends nothing, and its backlog is what the burst is for.
     * shortcut: "any packet" is any packet the engine is told about (moves, actions, clicks, closes, slot changes,
     * tick ends); a client sending only chat or keep-alives while holding its tick ends back counts as silent.
     */
    void packetSeen(final long nowNanos) {
        final long gap = nowNanos - lastPacketAt;
        if (heard && gap >= SILENCE_MIN_NANOS) {
            silentNanos += gap;
        }
        heard = true;
        lastPacketAt = nowNanos;
    }

    /**
     * The client's tick ended: what follows belongs to the next one. When {@code bounded}, each tick end
     * spends a credit from a bucket that holds {@code burst}. Time spent sending packets refills it by one per
     * server tick ({@code tickNanos}, which follows {@code /tick rate}) up to {@link #ACTIVE_CREDITS}; a silence
     * refills it up to {@code burst}. A client back from a freeze spends what it saved on its backlog; one that
     * holds its tick ends back while it keeps talking, or sends them faster than it ticks, to make each attack its
     * own tick, runs dry, and its excess ends no tick. Unbounded, every one ends a tick.
     *
     * @return whether this tick end ended a tick
     */
    boolean tickEnded(final long nowNanos, final long tickNanos, final int burst, final boolean bounded) {
        armed = true;
        packetSeen(nowNanos);
        if (bounded && !spendCredit(nowNanos, tickNanos, burst)) {
            return false;
        }
        tick++;
        return true;
    }

    private boolean spendCredit(final long nowNanos, final long tickNanos, final int burst) {
        if (!bucketStarted) {
            bucketStarted = true;
            credits = burst;
            refilledAt = nowNanos;
            silentNanos = 0;
        } else {
            final long gained = (nowNanos - refilledAt) / tickNanos;
            if (gained > 0) {
                credits = Math.max(credits, Math.min(Math.min(burst, ACTIVE_CREDITS), credits + gained));
                refilledAt += gained * tickNanos;
            }
            final long silent = silentNanos / tickNanos;
            if (silent > 0) {
                credits = Math.min(burst, credits + silent);
                silentNanos -= silent * tickNanos;
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
