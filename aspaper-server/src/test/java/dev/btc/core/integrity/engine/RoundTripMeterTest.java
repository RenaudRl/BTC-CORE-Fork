package dev.btc.core.integrity.engine;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The round trip is the clock between a ping we sent and its answer, and nothing else. */
class RoundTripMeterTest {

    private final RoundTripMeter meter = new RoundTripMeter();

    @Test
    void nothingIsKnownBeforeTheFirstAnswer() {
        assertEquals(OptionalLong.empty(), meter.lastRoundTripNanos());
        meter.sent(-7, 1_000);
        assertEquals(OptionalLong.empty(), meter.lastRoundTripNanos(), "sent is not answered");
    }

    @Test
    void anAnswerMeasuresTheClockSinceItsOwnPing() {
        meter.sent(-7, 1_000);
        meter.sent(-8, 5_000);
        assertEquals(OptionalLong.of(60_000), meter.received(-8, 65_000));
        assertEquals(OptionalLong.of(60_000), meter.lastRoundTripNanos());
        assertEquals(1, meter.inFlight(), "the older ping is still awaiting its answer");
    }

    @Test
    void anAnswerToNothingMeasuresNothing() {
        meter.sent(-7, 1_000);
        assertEquals(OptionalLong.empty(), meter.received(-99, 2_000), "not our id");
        meter.received(-7, 3_000);
        assertEquals(OptionalLong.empty(), meter.received(-7, 4_000), "already answered");
        assertEquals(OptionalLong.of(2_000), meter.lastRoundTripNanos(), "the bogus answers changed nothing");
    }

    @Test
    void aPingUnansweredForTooLongIsForgotten() {
        meter.sent(-7, 0);
        meter.sent(-8, RoundTripMeter.UNANSWERED_TTL + 1);
        assertEquals(1, meter.inFlight(), "the first ping is past its TTL and gone");
        assertEquals(OptionalLong.empty(), meter.received(-7, RoundTripMeter.UNANSWERED_TTL + 2));
    }

    @Test
    void idsAreUniqueAndInTheEnginesRange() {
        int first = RoundTripMeter.nextId();
        int second = RoundTripMeter.nextId();
        assertTrue(first != second);
        assertTrue(first >= RoundTripMeter.FIRST_ID && first < 0, "far below any plugin's small negative ids");
    }
}
