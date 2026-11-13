package dev.btc.core.security.admin;

import dev.btc.core.security.admin.ObserveThrottle.Decision;
import dev.btc.core.security.admin.OpLock.CommandVerdict;
import dev.btc.core.security.admin.OpLock.Mode;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObserveThrottleTest {

    private static final long WINDOW = 60_000_000_000L;
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final AtomicLong clock = new AtomicLong(1_000);
    private final ObserveThrottle throttle = new ObserveThrottle(WINDOW, clock::get);

    /** What the listener asks about: an op outside the allowlist (the list is empty) typing {@code line}, observed. */
    private Decision standingOp(UUID player, String line) {
        CommandVerdict verdict = OpLock.commandVerdict(Mode.OBSERVE, true, true, player, Set.of(), line);
        return throttle.onCommand(player, verdict, line);
    }

    private Decision chat(UUID player) {
        return standingOp(player, "/gamemode creative");
    }

    @Test
    void firstCommandIsReportedWithNothingSuppressed() {
        assertEquals(new Decision(true, 0), chat(ALICE));
    }

    @Test
    void commandsInsideTheWindowAreSilenced() {
        chat(ALICE);
        clock.addAndGet(WINDOW - 1);
        assertEquals(new Decision(false, 0), chat(ALICE));
        assertEquals(new Decision(false, 0), chat(ALICE));
    }

    @Test
    void nextLineAfterTheWindowCarriesTheSilencedCount() {
        chat(ALICE);
        chat(ALICE);
        chat(ALICE);
        clock.addAndGet(WINDOW);
        assertEquals(new Decision(true, 2), chat(ALICE));
        // The count restarts: the following line reports only what came after the previous one.
        clock.addAndGet(WINDOW);
        assertEquals(new Decision(true, 0), chat(ALICE));
    }

    @Test
    void windowRunsFromTheLastReportedLineNotFromTheLastCommand() {
        chat(ALICE);
        clock.addAndGet(WINDOW / 2);
        chat(ALICE);
        clock.addAndGet(WINDOW / 2);
        assertEquals(new Decision(true, 1), chat(ALICE));
    }

    @Test
    void playersAreIndependent() {
        chat(ALICE);
        assertEquals(new Decision(true, 0), chat(BOB));
    }

    @Test
    void opCommandsAreNeverLimited() {
        // The same op outside the list, ten /op and /deop in one minute (namespaced forms included): ten lines.
        String[] lines = {"/op bob", "/deop bob", "/minecraft:op bob", "/bukkit:deop bob", "/OP bob",
            "/op bob", "/deop bob", "/minecraft:op bob", "/bukkit:deop bob", "/op bob"};
        for (String line : lines) {
            assertEquals(new Decision(true, 0), standingOp(ALICE, line), line);
        }
        assertEquals(0, throttle.tracked(), "an op command leaves no limiter state");
    }

    @Test
    void opCommandsDoNotCountAsSilencedAndDoNotConsumeTheWindow() {
        chat(ALICE);
        standingOp(ALICE, "/op bob");
        assertEquals(new Decision(false, 0), chat(ALICE));
        assertEquals(1, throttle.forget(ALICE));
    }

    @Test
    void anOpAllowedByTheListIsNeverLimitedOnItsOpCommands() {
        CommandVerdict verdict = OpLock.commandVerdict(Mode.OBSERVE, true, true, ALICE, Set.of(ALICE), "/op bob");
        for (int i = 0; i < 10; i++) {
            assertEquals(new Decision(true, 0), throttle.onCommand(ALICE, verdict, "/op bob"));
        }
    }

    @Test
    void enforceRefusalsAreNeverLimited() {
        CommandVerdict verdict = OpLock.commandVerdict(Mode.ENFORCE, true, true, ALICE, Set.of(), "/gamemode creative");
        for (int i = 0; i < 10; i++) {
            assertEquals(new Decision(true, 0), throttle.onCommand(ALICE, verdict, "/gamemode creative"));
        }
    }

    @Test
    void forgetReturnsTheSilencedCountAndDropsTheEntry() {
        chat(ALICE);
        chat(ALICE);
        chat(ALICE);
        chat(BOB);
        assertEquals(2, throttle.forget(ALICE));
        assertEquals(1, throttle.tracked());
        assertEquals(new Decision(true, 0), chat(ALICE));
    }

    @Test
    void forgetOfAPlayerWithNothingSilencedReturnsZero() {
        chat(ALICE);
        assertEquals(0, throttle.forget(ALICE));
        assertEquals(0, throttle.forget(BOB));
    }
}
