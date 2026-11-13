package dev.btc.core.security.admin;

import dev.btc.core.security.admin.OpLock.Allowed;
import dev.btc.core.security.admin.OpLock.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpLockTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID MALLORY = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void offModeRevokesNothing() {
        assertEquals(Set.of(), OpLock.revocations(Set.of(ALICE, MALLORY), Set.of(ALICE), Mode.OFF));
        assertEquals(Set.of(), OpLock.outsideTheList(Set.of(ALICE, MALLORY), Set.of(ALICE), Mode.OFF));
    }

    @Test
    void observeReportsWithoutRevoking() {
        Set<UUID> ops = Set.of(ALICE, MALLORY);

        assertEquals(Set.of(MALLORY), OpLock.outsideTheList(ops, Set.of(ALICE), Mode.OBSERVE));
        assertEquals(Set.of(), OpLock.revocations(ops, Set.of(ALICE), Mode.OBSERVE));
    }

    @Test
    void enforceRevokesOpsOutsideTheAllowList() {
        assertEquals(Set.of(MALLORY), OpLock.revocations(Set.of(ALICE, MALLORY), Set.of(ALICE, BOB), Mode.ENFORCE));
    }

    @Test
    void allowListNeverGrantsOp() {
        // BOB is allowed but is not an op: the lock must not turn him into one, or into anything.
        Set<UUID> ops = Set.of(ALICE);
        Set<UUID> allowed = Set.of(ALICE, BOB);

        for (Mode mode : Mode.values()) {
            assertTrue(ops.containsAll(OpLock.revocations(ops, allowed, mode)), "revocations only ever remove: " + mode);
            assertTrue(ops.containsAll(OpLock.outsideTheList(ops, allowed, mode)), mode.toString());
        }
        assertEquals(Set.of(), OpLock.revocations(ops, allowed, Mode.ENFORCE));
    }

    @Test
    void emptyAllowListInEnforceRevokesEveryOp() {
        assertEquals(Set.of(ALICE, MALLORY), OpLock.revocations(Set.of(ALICE, MALLORY), Set.of(), Mode.ENFORCE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/op x", "/OP x", "op x", "  /op   x", "/minecraft:op x", "/bukkit:deop x", "/Deop x", "/op"})
    void opCommandVariantsAreRecognised(String line) {
        assertTrue(OpLock.isOpCommand(line), line);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/opinion", "/top", "/oper", "/op2 x", "/say op x", "/", "", "/minecraft:", "/essentials:optimize", "//op x"})
    void lookalikeCommandsAreNotOpCommands(String line) {
        assertFalse(OpLock.isOpCommand(line), line);
    }

    @Test
    void consoleAndRconSendersAreNeverBlocked() {
        for (Mode mode : Mode.values()) {
            assertFalse(OpLock.refusesCommand(mode, false, "/op mallory"), "console, " + mode);
            assertFalse(OpLock.reportsCommand(mode, false, "/op mallory"), "console, " + mode);
        }
    }

    @Test
    void aPlayersOpCommandIsRefusedOnlyWhenEnforcing() {
        assertTrue(OpLock.refusesCommand(Mode.ENFORCE, true, "/op mallory"));
        assertFalse(OpLock.refusesCommand(Mode.OBSERVE, true, "/op mallory"));
        assertFalse(OpLock.refusesCommand(Mode.OFF, true, "/op mallory"));
        assertTrue(OpLock.reportsCommand(Mode.OBSERVE, true, "/op mallory"));
        assertFalse(OpLock.reportsCommand(Mode.OFF, true, "/op mallory"));
        assertFalse(OpLock.refusesCommand(Mode.ENFORCE, true, "/opinion"));
    }

    @Test
    void malformedUuidIsReportedAndIgnored() {
        Allowed allowed = OpLock.parseAllowed(List.of(
            " 00000000-0000-0000-0009-01234567890a ", "not-a-uuid", "1-1-1-1-1", "", "00000000-0000-0000-0000-00000000000a"));

        assertEquals(Set.of(UUID.fromString("00000000-0000-0000-0009-01234567890a"), ALICE), allowed.uuids());
        assertEquals(List.of("not-a-uuid", "1-1-1-1-1", ""), allowed.invalid());
    }

    @Test
    void modeIsReadCaseInsensitivelyAndATypoIsNotAMode() {
        assertEquals(Optional.of(Mode.ENFORCE), Mode.parse(" Enforce "));
        assertEquals(Optional.of(Mode.OFF), Mode.parse("off"));
        assertEquals(Optional.empty(), Mode.parse("enforec"));
        assertEquals(Optional.empty(), Mode.parse(null));
    }
}
