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
    void theSweepNeitherGrantsNorRevokesAnAllowedPlayer() {
        // BOB is allowed but is not an op: the sweep does not turn him into one; ALICE is allowed and keeps hers.
        Set<UUID> ops = Set.of(ALICE);
        Set<UUID> allowed = Set.of(ALICE, BOB);

        assertEquals(Set.of(), OpLock.revocations(ops, allowed, Mode.ENFORCE));
        assertEquals(Set.of(), OpLock.outsideTheList(ops, allowed, Mode.ENFORCE));
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
    @ValueSource(strings = {
        "/execute run op x", "/execute as @a run op @s", "/EXECUTE as @s at @s run deop x", "/minecraft:execute run op x",
        "/execute as @a run minecraft:op @s", "/execute if entity @s run execute as @s run op x", "execute run op x",
        "/return run op x", "/return run deop owner", "/minecraft:return run op x"})
    void opCommandRunThroughExecuteIsRecognised(String line) {
        assertTrue(OpLock.isOpCommand(line), line);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/execute run say op x", "/execute as @a run give @s stone", "/execute run opinion", "/execute", "/execute run",
        "/say run op x"})
    void executeThatDoesNotRunOpIsNotAnOpCommand(String line) {
        assertFalse(OpLock.isOpCommand(line), line);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/opinion", "/top", "/oper", "/op2 x", "/say op x", "/", "", "/minecraft:", "/essentials:optimize", "//op x"})
    void lookalikeCommandsAreNotOpCommands(String line) {
        assertFalse(OpLock.isOpCommand(line), line);
    }

    /**
     * The shape of the vanilla tree where it matters: {@code execute ... run} and {@code return run} redirect to the
     * root, {@code execute as} back to {@code execute}, and {@code minecraft:op} is an alias that redirects to op.
     */
    private static com.mojang.brigadier.CommandDispatcher<Object> vanillaShapedTree() {
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<Object>();
        var op = dispatcher.register(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("op")
            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.argument("targets",
                com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> 1)));
        dispatcher.register(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("deop")
            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.argument("targets",
                com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> 1)));
        dispatcher.register(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("minecraft:op").redirect(op));
        dispatcher.register(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("say")
            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.argument("message",
                com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(c -> 1)));
        var execute = dispatcher.register(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("execute"));
        dispatcher.register(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("execute")
            .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("run").redirect(dispatcher.getRoot()))
            .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("as")
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.argument("targets",
                    com.mojang.brigadier.arguments.StringArgumentType.word()).redirect(execute))));
        dispatcher.register(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("return")
            .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("run").redirect(dispatcher.getRoot())));
        return dispatcher;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/return run op x", "/return run deop owner", "/execute run return run op x",
        "/execute as op run deop x", "/minecraft:op x", "/op x", "/execute as bob run execute run op x"})
    void theCommandTreeFollowsEveryRedirectToOp(String line) {
        assertEquals(Optional.of(true), OpLock.resolvedOpCommand(vanillaShapedTree(), new Object(), line), line);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/execute run say run op x", "/say run op x", "/execute as op run say op", "/return run say op"})
    void theCommandTreeSeesThatSayIsNotOp(String line) {
        assertEquals(Optional.of(false), OpLock.resolvedOpCommand(vanillaShapedTree(), new Object(), line), line);
        assertFalse(OpLock.commandVerdict(Mode.ENFORCE, false, true, BOB, Set.of(BOB), false).cancel());
    }

    @Test
    void aLineTheTreeDoesNotKnowIsLeftToTheText() {
        assertEquals(Optional.empty(), OpLock.resolvedOpCommand(vanillaShapedTree(), new Object(), "/myalias run op x"));
        assertEquals(Optional.empty(), OpLock.resolvedOpCommand(vanillaShapedTree(), new Object(), ""));
    }

    /** A player who could run /op and /deop: the case the verdicts below are about, unless a test says otherwise. */
    private static OpLock.CommandVerdict verdict(Mode mode, boolean isOp, UUID player, Set<UUID> allowed, String line) {
        return OpLock.commandVerdict(mode, isOp, true, player, allowed, line);
    }

    @Test
    void anOpCommandFromAPlayerWhoCouldNotRunItIsNeitherRefusedNorReported() {
        for (Mode mode : Mode.values()) {
            OpLock.CommandVerdict v = OpLock.commandVerdict(mode, false, false, BOB, Set.of(BOB), "/op bob");

            assertFalse(v.journals(), mode.toString());
            assertFalse(v.cancel(), mode.toString());
        }
    }

    @Test
    void anOpCommandFromAPlayerHoldingOnlyTheOpPermissionIsRefused() {
        assertTrue(OpLock.commandVerdict(Mode.ENFORCE, false, true, BOB, Set.of(BOB), "/deop alice").cancel());
    }

    @Test
    void anOpOutsideTheListIsActedOnEvenIfTheyHoldNoOpPermission() {
        // Permission plugins can strip minecraft.command.* from an op: the invariant is about isOp.
        OpLock.CommandVerdict v = OpLock.commandVerdict(Mode.ENFORCE, true, false, MALLORY, Set.of(ALICE), "/say hi");

        assertTrue(v.revokeOp());
    }

    @Test
    void anOpOutsideTheListLosesTheOpAndTheCommandInEnforceWhateverTheyType() {
        OpLock.CommandVerdict v = verdict(Mode.ENFORCE, true, MALLORY, Set.of(ALICE), "/gamemode creative");

        assertEquals(OpLock.Reason.OP_OUTSIDE_LIST, v.reason());
        assertTrue(v.cancel(), "the command is cancelled");
        assertTrue(v.revokeOp(), "the op goes now, not at the next sweep");
        assertTrue(v.journals());
    }

    @Test
    void anOpOutsideTheListIsOnlyReportedInObserve() {
        OpLock.CommandVerdict v = verdict(Mode.OBSERVE, true, MALLORY, Set.of(ALICE), "/gamemode creative");

        assertEquals(OpLock.Reason.OP_OUTSIDE_LIST, v.reason());
        assertFalse(v.cancel());
        assertFalse(v.revokeOp());
    }

    @Test
    void offDoesNothingEvenForAnOpOutsideTheListTypingOp() {
        assertEquals(OpLock.Reason.NONE, verdict(Mode.OFF, true, MALLORY, Set.of(ALICE), "/op mallory").reason());
        assertFalse(verdict(Mode.OFF, true, MALLORY, Set.of(ALICE), "/op mallory").journals());
    }

    @Test
    void anAllowedOpIsLeftAloneExceptForTheOpCommands() {
        assertFalse(verdict(Mode.ENFORCE, true, ALICE, Set.of(ALICE), "/gamemode creative").journals());

        OpLock.CommandVerdict v = verdict(Mode.ENFORCE, true, ALICE, Set.of(ALICE), "/op mallory");
        assertEquals(OpLock.Reason.OP_COMMAND, v.reason());
        assertTrue(v.cancel(), "even an allowed op does not grant ops from the keyboard");
        assertFalse(v.revokeOp(), "but an allowed op keeps theirs");
    }

    @Test
    void aPlayersOpCommandIsCancelledOnlyWhenEnforcing() {
        assertTrue(verdict(Mode.ENFORCE, false, BOB, Set.of(BOB), "/op mallory").cancel());
        assertFalse(verdict(Mode.OBSERVE, false, BOB, Set.of(BOB), "/op mallory").cancel());
        assertTrue(verdict(Mode.OBSERVE, false, BOB, Set.of(BOB), "/op mallory").journals());
        assertFalse(verdict(Mode.OFF, false, BOB, Set.of(BOB), "/op mallory").journals());
        assertFalse(verdict(Mode.ENFORCE, false, BOB, Set.of(BOB), "/opinion").journals());
    }

    @Test
    void theOpIsRemovedOnlyFromAnOpOutsideTheListNeverGrantedToAnyone() {
        for (Mode mode : Mode.values()) {
            for (boolean isOp : new boolean[] {true, false}) {
                for (UUID player : List.of(ALICE, MALLORY)) {
                    for (String line : List.of("/op x", "/deop x", "/gamemode creative", "hello")) {
                        OpLock.CommandVerdict v = verdict(mode, isOp, player, Set.of(ALICE), line);
                        String where = mode + " isOp=" + isOp + " " + player + " " + line;
                        assertTrue(!v.revokeOp() || (isOp && player.equals(MALLORY) && mode == Mode.ENFORCE), where);
                    }
                }
            }
        }
    }

    @Test
    void malformedUuidIsReportedAndIgnored() {
        Allowed allowed = OpLock.parseAllowed(List.of(
            " 00000000-0000-0000-0009-01234567890a ", "not-a-uuid", "1-1-1-1-1", "", "00000000-0000-0000-0000-00000000000a"));

        assertEquals(Set.of(UUID.fromString("00000000-0000-0000-0009-01234567890a"), ALICE), allowed.uuids());
        assertEquals(List.of("not-a-uuid", "1-1-1-1-1", ""), allowed.invalid());
    }

    @Test
    void aUuidWithoutDashesIsTheSameUuid() {
        Allowed allowed = OpLock.parseAllowed(List.of(
            "0000000000000000000000000000000A", " 00000000000000000009ABCDEF012345 ", "0000000000000000000000000000000", "00000000000000000000000000000000a"));

        assertEquals(Set.of(ALICE, UUID.fromString("00000000-0000-0000-0009-abcdef012345")), allowed.uuids());
        assertEquals(List.of("0000000000000000000000000000000", "00000000000000000000000000000000a"), allowed.invalid(),
            "31 and 33 digits are not a UUID");
    }

    @Test
    void modeIsReadCaseInsensitivelyAndATypoIsNotAMode() {
        assertEquals(Optional.of(Mode.ENFORCE), Mode.parse(" Enforce "));
        assertEquals(Optional.of(Mode.OFF), Mode.parse("off"));
        assertEquals(Optional.empty(), Mode.parse("enforec"));
        assertEquals(Optional.empty(), Mode.parse(null));
    }
}
