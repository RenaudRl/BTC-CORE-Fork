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
 * <p>The tick end being the client's to send, {@link #tickEnded} bounds them with a token bucket: a burst for the
 * backlog of a freeze, a jitter allowance of {@link #ACTIVE_CREDITS} refilled 10 % faster than the server ticks
 * while the client talks. Only a client sending clearly more tick ends than it lives runs dry.
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
     * What the time a client spends talking refills, at most: the network jitter an honest stream may be behind by,
     * 10 ticks (500 ms at 20 TPS). Its tick ends arrive late, then in lots of 2 to 8 handled at the same instant by the
     * region thread; a depth of 2 lost a credit at each lot and ran dry about once a second (bench 14/11). Only a
     * silence refills the rest of the burst.
     */
    static final int ACTIVE_CREDITS = 10;

    /**
     * Talking refills one credit every 9/10 of a server tick, 10 % faster than an honest client ticks: what a full
     * bucket throws away while the stream is late is won back, and a clock a little off is not a flood. A client
     * sending more than 1.11 tick ends per server tick still runs dry.
     */
    private static final long ACTIVE_REFILL_NUMERATOR = 9;
    private static final long ACTIVE_REFILL_DENOMINATOR = 10;

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
            // The gap is credited as silence: it must not also count as time spent talking (the remainder kept).
            if (bucketStarted) {
                refilledAt += gap;
            }
        }
        heard = true;
        lastPacketAt = nowNanos;
    }

    /**
     * The client's tick ended: what follows belongs to the next one. When {@code bounded}, each tick end
     * spends a credit from a bucket that holds {@code burst}. Time spent sending packets refills it by one per
     * 9/10 of a server tick ({@code tickNanos}, which follows {@code /tick rate}) up to {@link #ACTIVE_CREDITS}, the
     * jitter allowance; a silence refills it by one per server tick up to {@code burst}. A client back from a freeze
     * spends what it saved on its backlog; one that holds back more tick ends than the jitter allowance while it keeps
     * talking, or sends them faster than the refill, to make each attack its own tick, runs dry, and its excess ends
     * no tick. Unbounded, every one ends a tick.
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
            final long refillNanos = Math.max(1, tickNanos * ACTIVE_REFILL_NUMERATOR / ACTIVE_REFILL_DENOMINATOR);
            final long gained = (nowNanos - refilledAt) / refillNanos;
            if (gained > 0) {
                credits = Math.max(credits, Math.min(Math.min(burst, ACTIVE_CREDITS), credits + gained));
                refilledAt += gained * refillNanos;
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
