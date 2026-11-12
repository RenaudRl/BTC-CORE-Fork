package dev.btc.core.performance;

import java.util.function.IntConsumer;

/**
 * The latencies of the player list sent to a few players per tick instead of to all of them in one
 * tick ({@code performance.network.spread-latency-updates}).
 *
 * <p>Vanilla sends every player the latency of every player it can see once per {@link #PERIOD}
 * ticks, all in the same tick: players times players entries at once. Here the tick of a period is
 * a slot, and a player is served in the slot of its place in the list: the same packet, as often,
 * one player per tick up to {@link #PERIOD} players. A player whose place moves within a period -
 * someone before it left - may be served twice or wait one period more.
 *
 * <p>From Gale "Spread out sending all player info" (Martijn Muijsers, GPL-3.0-only), based on
 * Purpur "Spread out and optimise player list ticks" (James Lyne, MIT), as carried by Leaf. Gale
 * keeps buckets of players; the place in the list does the same with nothing to keep.
 */
public final class LatencySpread {

    /** Ticks between two latency updates of one player: vanilla sends when its counter passes 600. */
    public static final int PERIOD = 601;

    private LatencySpread() {
    }

    /** The slot of the next tick. The counter of vanilla is the slot: it stays within the period either way. */
    public static int nextSlot(int slot) {
        return slot + 1 >= PERIOD ? 0 : slot + 1;
    }

    /** The places in a list of {@code players} that are served in this slot. */
    public static void forEachDue(int slot, int players, IntConsumer served) {
        for (int place = slot; place < players; place += PERIOD) {
            served.accept(place);
        }
    }
}
