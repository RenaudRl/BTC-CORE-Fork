package dev.btc.core.security.admin;

import dev.btc.core.security.admin.ObserveThrottle.Decision;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObserveThrottleTest {

    private static final long WINDOW = 60_000_000_000L;
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final AtomicLong clock = new AtomicLong(1_000);
    private final ObserveThrottle throttle = new ObserveThrottle(WINDOW, clock::get);

    @Test
    void firstCommandIsReportedWithNothingSuppressed() {
        assertEquals(new Decision(true, 0), throttle.onCommand(ALICE));
    }

    @Test
    void commandsInsideTheWindowAreSilenced() {
        throttle.onCommand(ALICE);
        clock.addAndGet(WINDOW - 1);
        assertEquals(new Decision(false, 0), throttle.onCommand(ALICE));
        assertEquals(new Decision(false, 0), throttle.onCommand(ALICE));
    }

    @Test
    void nextLineAfterTheWindowCarriesTheSilencedCount() {
        throttle.onCommand(ALICE);
        throttle.onCommand(ALICE);
        throttle.onCommand(ALICE);
        clock.addAndGet(WINDOW);
        assertEquals(new Decision(true, 2), throttle.onCommand(ALICE));
        // The count restarts: the following line reports only what came after the previous one.
        clock.addAndGet(WINDOW);
        assertEquals(new Decision(true, 0), throttle.onCommand(ALICE));
    }

    @Test
    void windowRunsFromTheLastReportedLineNotFromTheLastCommand() {
        throttle.onCommand(ALICE);
        clock.addAndGet(WINDOW / 2);
        throttle.onCommand(ALICE);
        clock.addAndGet(WINDOW / 2);
        assertEquals(new Decision(true, 1), throttle.onCommand(ALICE));
    }

    @Test
    void playersAreIndependent() {
        throttle.onCommand(ALICE);
        assertEquals(new Decision(true, 0), throttle.onCommand(BOB));
    }

    @Test
    void forgetDropsTheEntry() {
        throttle.onCommand(ALICE);
        throttle.onCommand(BOB);
        throttle.forget(ALICE);
        assertEquals(1, throttle.tracked());
        assertEquals(new Decision(true, 0), throttle.onCommand(ALICE));
    }
}
