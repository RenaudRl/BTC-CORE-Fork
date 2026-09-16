package dev.btc.core.integrity.engine;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-trip time of one session, measured by the engine's own pings (3.5).
 *
 * <p>Independent of {@code Player#getPing()} on purpose: that figure averages keep-alives sent
 * every fifteen seconds, and a check that compensates a hit by it compensates it by a value that
 * may be a quarter of a minute old. Here the engine sends {@code ClientboundPingPacket} with an id
 * it chose, the client answers with the same id as soon as it reads it, and the round trip is the
 * clock difference — what the client's view of the world actually lags by, right now.
 *
 * <p>Two threads touch this: the region thread that sends, the network thread that receives the
 * answer. Every structure is concurrent; nothing else is shared with the session state.
 *
 * <p>Pure: the caller passes the clock, so a test can drive a ping and its answer at chosen instants.
 */
final class RoundTripMeter {

    /**
     * Ids below this are the engine's. Nothing in vanilla sends pings, but plugins that use the
     * same packet for their own transactions conventionally use small negative ids; a range this
     * far down does not collide with them.
     */
    static final int FIRST_ID = -2_000_000_000;

    /** A ping unanswered for this long is forgotten: the answer will not come, and the map must not grow. */
    static final long UNANSWERED_TTL = TimeUnit.SECONDS.toNanos(30);

    private static final AtomicInteger NEXT_ID = new AtomicInteger(FIRST_ID);

    private final Map<Integer, Long> inFlight = new ConcurrentHashMap<>(4);

    /** The last round trip measured, or {@code -1} before the first answer. */
    private volatile long lastRoundTripNanos = -1;

    /** A fresh id for a ping about to be sent. Unique across every session of this server. */
    static int nextId() {
        return NEXT_ID.getAndIncrement();
    }

    /** Records that {@code id} left at {@code nowNanos}, and forgets pings too old to be answered. */
    void sent(final int id, final long nowNanos) {
        inFlight.values().removeIf(sentAt -> nowNanos - sentAt > UNANSWERED_TTL);
        inFlight.put(id, nowNanos);
    }

    /**
     * Records that {@code id} was answered at {@code nowNanos}.
     *
     * @return the round trip, or empty when this id was not one of ours or was already answered —
     *     an answer to nothing measures nothing
     */
    OptionalLong received(final int id, final long nowNanos) {
        final Long sentAt = inFlight.remove(id);
        if (sentAt == null) {
            return OptionalLong.empty();
        }
        final long roundTrip = Math.max(0, nowNanos - sentAt);
        lastRoundTripNanos = roundTrip;
        return OptionalLong.of(roundTrip);
    }

    /** The last round trip measured, or empty before the first answer. */
    OptionalLong lastRoundTripNanos() {
        final long value = lastRoundTripNanos;
        return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
    }

    /** Pings sent and not yet answered. Diagnostics and tests. */
    int inFlight() {
        return inFlight.size();
    }
}
