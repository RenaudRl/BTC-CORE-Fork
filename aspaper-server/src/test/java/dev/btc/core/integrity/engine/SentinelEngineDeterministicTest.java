package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckGroup;
import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionReason;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionScope;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationEvent;
import dev.btc.core.integrity.CheckRegistry;
import dev.btc.core.integrity.ExemptionRegistry;
import dev.btc.core.integrity.SentinelHooks;
import dev.btc.core.integrity.engine.SentinelEngineTest.Recorder;
import dev.btc.core.security.exploit.DeterministicChecks;
import dev.btc.core.security.exploit.DeterministicChecks.Settings;
import dev.btc.core.security.exploit.InteractBurst;
import dev.btc.core.security.exploit.InteractBurst.Mode;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 16.7: the deterministic checks, driven through the seam and the engine's feeders with a recorder
 * where the server would be and a clock the test moves. Observation only: a line is journalled and
 * published, and nothing else exists for them to do.
 */
class SentinelEngineDeterministicTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final long REPORT_INTERVAL_MILLIS = 1_000;

    private final CheckRegistry checks = new CheckRegistry();
    private final ExemptionRegistry exemptions = new ExemptionRegistry();
    private final Recorder server = new Recorder();
    private final Plugin owner = Mockito.mock(Plugin.class);
    private final AtomicLong clock = new AtomicLong(1_000);
    private AutoCloseable installation;

    private static Settings observing() {
        return new Settings(Mode.OBSERVE, 1, Mode.OBSERVE, Mode.OBSERVE, Mode.OBSERVE, Mode.OBSERVE,
            100, REPORT_INTERVAL_MILLIS);
    }

    private void install(Settings settings) {
        Mockito.when(owner.getName()).thenReturn("test");
        installation = SentinelEngine.install(owner, checks, exemptions, server,
            new InteractBurst.Settings(Mode.OFF, 1000, 100), settings, clock::get);
    }

    @AfterEach
    void uninstall() throws Exception {
        installation.close();
    }

    private List<CheckId> journalled() {
        return server.violations.stream().map(ViolationEvent::check).toList();
    }

    /** The client ends a tick; the first one is what arms the tick-based checks for a session. */
    private static void endTick() {
        SentinelEngine.clientTickEnd(PLAYER);
    }

    /** A session exists from its first movement packet, as in the game. */
    private static void join() {
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, false);
        endTick();
    }

    private void advance(long millis) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    // ------------------------------------------------------------------ registration

    @Test
    void theFiveChecksAreRegisteredInTheirGroups() {
        install(observing());

        assertEquals(CheckGroup.COMBAT, checks.find(DeterministicChecks.MULTI_TARGET).orElseThrow().definition().group());
        assertEquals(CheckGroup.INTERACTION, checks.find(DeterministicChecks.SLOT_SWAP).orElseThrow().definition().group());
        assertEquals(CheckGroup.INTERACTION, checks.find(DeterministicChecks.UNKNOWN_WINDOW).orElseThrow().definition().group());
        assertEquals(CheckGroup.INTERACTION, checks.find(DeterministicChecks.CLOSED_WINDOW).orElseThrow().definition().group());
        assertEquals(CheckGroup.COMBAT, checks.find(DeterministicChecks.WALL_HIT).orElseThrow().definition().group());
    }

    // ------------------------------------------------------------------ multi-target

    @Test
    void twoTargetsAttackedInOneClientTickAreJournalled() {
        install(observing());
        join();
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 18);

        assertEquals(List.of(DeterministicChecks.MULTI_TARGET), journalled(), () -> server.journal.toString());
        assertTrue(server.journal.get(0).startsWith("sentinel:multi-target "), server.journal.get(0));
        String detail = server.violations.get(0).verboseDetail();
        for (String part : List.of("[origin JAVA]", "packet=ServerboundAttackPacket", "targets=2", "allowed=1", "latest=18")) {
            assertTrue(detail.contains(part), part + " in " + detail);
        }
    }

    @Test
    void theSameTargetAttackedTwiceInOneClientTickIsNotAViolation() {
        install(observing());
        join();
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 17);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void differentTargetsInTwoClientTicksAreNotAViolationHoweverManyReachTheServerTogether() {
        // Jitter or a lag spike delivers two client ticks inside one server tick: only the client's own
        // end-of-tick packet tells them apart, and it sits between the two attacks.
        install(observing());
        join();
        SentinelHooks.attack(PLAYER, 17);
        endTick();
        SentinelHooks.attack(PLAYER, 18);
        endTick();
        SentinelHooks.attack(PLAYER, 19);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void theAllowedNumberOfTargetsIsTheConfiguredOne() {
        install(new Settings(Mode.OBSERVE, 2, Mode.OBSERVE, Mode.OBSERVE, Mode.OBSERVE, Mode.OBSERVE,
            100, REPORT_INTERVAL_MILLIS));
        join();
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 18);
        assertTrue(server.violations.isEmpty(), "two targets are allowed here");

        SentinelHooks.attack(PLAYER, 19);
        assertEquals(List.of(DeterministicChecks.MULTI_TARGET), journalled());
        assertTrue(server.violations.get(0).verboseDetail().contains("targets=3 allowed=2"),
            server.violations.get(0).verboseDetail());
    }

    @Test
    void aClientThatNeverEndsATickIsNotJudgedByTick() {
        // Without its end-of-tick packet there is no client tick: every attack would be "the same tick".
        install(observing());
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, false);
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 18);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void multiTargetOffJournalsNothing() {
        install(new Settings(Mode.OFF, 1, Mode.OBSERVE, Mode.OBSERVE, Mode.OBSERVE, Mode.OBSERVE,
            100, REPORT_INTERVAL_MILLIS));
        join();
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 18);

        assertTrue(server.violations.isEmpty());
    }

    @Test
    void aFloodOfTargetsIsOneLinePerReportIntervalThenAnotherAfterIt() {
        install(observing());
        join();
        for (int tick = 0; tick < 50; tick++) {
            SentinelHooks.attack(PLAYER, 100 + tick);
            SentinelHooks.attack(PLAYER, 200 + tick);
            endTick();
        }
        assertEquals(1, server.journal.size(), () -> server.journal.toString());

        advance(REPORT_INTERVAL_MILLIS + 1);
        SentinelHooks.attack(PLAYER, 1);
        SentinelHooks.attack(PLAYER, 2);
        assertEquals(2, server.journal.size(), () -> server.journal.toString());
    }

    @Test
    void anExemptedCombatGroupSilencesTheLineButAnotherGroupDoesNot() {
        install(observing());
        join();
        exemptions.grant(owner, PLAYER, new ExemptionScope(CheckGroup.INTERACTION, ExemptionReason.CINEMATIC),
            Duration.ofSeconds(60));
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 18);
        assertEquals(1, server.violations.size(), "an interaction exemption does not cover combat");

        advance(REPORT_INTERVAL_MILLIS + 1);
        exemptions.grant(owner, PLAYER, new ExemptionScope(CheckGroup.COMBAT, ExemptionReason.CINEMATIC),
            Duration.ofSeconds(60));
        endTick();
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 18);
        assertEquals(1, server.violations.size(), () -> server.violations.toString());
    }

    @Test
    void creativeAndSpectatorPlayersAreNotJudged() {
        install(observing());
        join();
        server.exemptGameMode = true;
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 18);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void theOriginOfABedrockSessionIsStampedOnTheLine() {
        server.platform = ClientPlatform.BEDROCK;
        install(observing());
        join();
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 18);

        assertTrue(server.violations.get(0).verboseDetail().startsWith("[origin BEDROCK] "),
            server.violations.get(0).verboseDetail());
    }

    // ------------------------------------------------------------------ slot-swap

    @Test
    void aSlotChangeAfterAnAttackInTheSameClientTickIsJournalled() {
        install(observing());
        join();
        SentinelHooks.attack(PLAYER, 17);
        SentinelEngine.heldSlotChanged(PLAYER);

        assertEquals(List.of(DeterministicChecks.SLOT_SWAP), journalled(), () -> server.journal.toString());
        assertTrue(server.journal.get(0).startsWith("sentinel:slot-swap "), server.journal.get(0));
        String detail = server.violations.get(0).verboseDetail();
        assertTrue(detail.startsWith("[origin JAVA] "), detail);
        assertTrue(detail.contains("after an attack"), detail);
    }

    @Test
    void aSlotChangeAfterAnItemUseOrAnEntityInteractionIsJournalledToo() {
        install(observing());
        join();
        SentinelHooks.useItem(PLAYER, false);
        SentinelEngine.heldSlotChanged(PLAYER);
        advance(REPORT_INTERVAL_MILLIS + 1);
        endTick();
        SentinelHooks.interactEntity(PLAYER, 17, 0, 0, 0, false);
        SentinelEngine.heldSlotChanged(PLAYER);

        assertEquals(List.of(DeterministicChecks.SLOT_SWAP, DeterministicChecks.SLOT_SWAP), journalled());
        assertTrue(server.violations.get(0).verboseDetail().contains("after an item use"));
        assertTrue(server.violations.get(1).verboseDetail().contains("after an entity interaction"));
    }

    @Test
    void aSlotChangeBeforeTheActionIsHowTheClientPlaysAndIsNotAViolation() {
        install(observing());
        join();
        SentinelEngine.heldSlotChanged(PLAYER);
        SentinelHooks.attack(PLAYER, 17);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void aSlotChangeInTheClientTickAfterTheActionIsNotAViolationEvenInTheSameServerTick() {
        // Hit, then press a hotbar key a tick later: the two packets can reach the server together.
        install(observing());
        join();
        SentinelHooks.attack(PLAYER, 17);
        endTick();
        SentinelEngine.heldSlotChanged(PLAYER);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void aSlotChangeWithNoActionInTheTickIsNotAViolation() {
        install(observing());
        join();
        SentinelEngine.heldSlotChanged(PLAYER);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void slotSwapIsSilentForAClientThatNeverEndsATickAndForAPlayerWithNoSession() {
        install(observing());
        SentinelEngine.heldSlotChanged(PLAYER);
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, false);
        SentinelHooks.attack(PLAYER, 17);
        SentinelEngine.heldSlotChanged(PLAYER);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void slotSwapOffJournalsNothing() {
        install(new Settings(Mode.OBSERVE, 1, Mode.OFF, Mode.OBSERVE, Mode.OBSERVE, Mode.OBSERVE,
            100, REPORT_INTERVAL_MILLIS));
        join();
        SentinelHooks.attack(PLAYER, 17);
        SentinelEngine.heldSlotChanged(PLAYER);
        assertTrue(server.violations.isEmpty(), "off");
    }

    @Test
    void slotSwapIsSilencedByItsGroupExemptionAndByCreativeOrSpectator() {
        install(observing());
        join();
        exemptions.grant(owner, PLAYER, new ExemptionScope(CheckGroup.INTERACTION, ExemptionReason.CINEMATIC),
            Duration.ofSeconds(60));
        SentinelHooks.attack(PLAYER, 17);
        SentinelEngine.heldSlotChanged(PLAYER);
        assertTrue(server.violations.isEmpty(), "exempted group");

        exemptions.clearPlayer(PLAYER);
        server.exemptGameMode = true;
        endTick();
        SentinelHooks.attack(PLAYER, 17);
        SentinelEngine.heldSlotChanged(PLAYER);
        assertTrue(server.violations.isEmpty(), "creative or spectator");
    }

    // ------------------------------------------------------------------ unknown-window

    private static void click(int containerId) {
        SentinelHooks.containerClick(PLAYER, containerId, 3, 0, 0);
    }

    @Test
    void aClickInAContainerTheServerNeverHadOpenIsJournalled() {
        install(observing());
        join();
        server.openContainer = 0;
        click(7);

        assertEquals(List.of(DeterministicChecks.UNKNOWN_WINDOW), journalled(), () -> server.journal.toString());
        assertTrue(server.journal.get(0).startsWith("sentinel:unknown-window "), server.journal.get(0));
        String detail = server.violations.get(0).verboseDetail();
        for (String part : List.of("[origin JAVA]", "packet=ServerboundContainerClickPacket", "container=7 never opened", "open=0")) {
            assertTrue(detail.contains(part), part + " in " + detail);
        }
    }

    @Test
    void aClickInTheContainerTheServerHasOpenIsNotAViolation() {
        install(observing());
        join();
        server.openContainer = 7;
        click(7);
        click(7);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void aClickInThePlayersOwnInventoryIsNeverJudged() {
        install(observing());
        join();
        server.openContainer = 7;
        click(0);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void aClickInAnIdTheServerCannotHandOutIsJournalledWithoutAnyHistory() {
        install(observing());
        join();
        for (int id : new int[] {-1, 101, Integer.MAX_VALUE}) {
            click(id);
            advance(REPORT_INTERVAL_MILLIS + 1);
        }

        assertEquals(3, server.violations.size(), () -> server.journal.toString());
    }

    @Test
    void aClickInFlightWhenTheServerClosedTheContainerIsNotAViolation() {
        // The server opened container 5 and replaced it before the client's click arrived. The close event
        // is how the engine learns that 5 was real, so the late click is a race, not an invention.
        install(observing());
        join();
        server.openContainer = 5;
        SentinelEngine.containerClosed(PLAYER, false);
        server.openContainer = 6;
        click(5);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void anIdThatWasNeverOpenStaysUnknownAfterOtherContainersCameAndWent() {
        install(observing());
        join();
        server.openContainer = 5;
        click(5);
        SentinelEngine.containerClosed(PLAYER, false);
        server.openContainer = 0;
        click(9);

        assertEquals(List.of(DeterministicChecks.UNKNOWN_WINDOW), journalled(), () -> server.journal.toString());
    }

    @Test
    void aFloodOfInventedIdsIsOneLinePerReportInterval() {
        install(observing());
        join();
        for (int id = 1; id <= 100; id++) {
            click(id);
        }

        assertEquals(1, server.journal.size(), () -> server.journal.toString());
    }

    @Test
    void unknownWindowOffJournalsNothing() {
        install(new Settings(Mode.OBSERVE, 1, Mode.OBSERVE, Mode.OFF, Mode.OBSERVE, Mode.OBSERVE,
            100, REPORT_INTERVAL_MILLIS));
        join();
        click(7);
        assertTrue(server.violations.isEmpty(), "off");
    }

    @Test
    void unknownWindowIsSilencedByItsGroupExemptionAndByCreativeOrSpectator() {
        install(observing());
        join();
        exemptions.grant(owner, PLAYER, new ExemptionScope(CheckGroup.INTERACTION, ExemptionReason.CINEMATIC),
            Duration.ofSeconds(60));
        click(7);
        assertTrue(server.violations.isEmpty(), "exempted group");

        exemptions.clearPlayer(PLAYER);
        server.exemptGameMode = true;
        click(7);
        assertTrue(server.violations.isEmpty(), "creative or spectator");

        server.exemptGameMode = false;
        server.platform = ClientPlatform.BEDROCK;
        click(7);
        assertEquals(1, server.violations.size());
        assertTrue(server.violations.get(0).verboseDetail().startsWith("[origin BEDROCK] "));
    }

    // ------------------------------------------------------------------ closed-window

    /** The player opened container {@code id}, clicked in it, then closed it: by the client or by the server. */
    private void openClickAndClose(int id, boolean byClient) {
        server.openContainer = id;
        click(id);
        SentinelEngine.containerClosed(PLAYER, byClient);
        server.openContainer = 0;
    }

    @Test
    void aClickInAContainerTheClientClosedIsJournalledAsClosedNotAsUnknown() {
        install(observing());
        join();
        openClickAndClose(7, true);
        click(7);

        assertEquals(List.of(DeterministicChecks.CLOSED_WINDOW), journalled(), () -> server.journal.toString());
        assertTrue(server.journal.get(0).startsWith("sentinel:closed-window "), server.journal.get(0));
        String detail = server.violations.get(0).verboseDetail();
        for (String part : List.of("[origin JAVA]", "packet=ServerboundContainerClickPacket", "container=7 closed by the client", "open=0")) {
            assertTrue(detail.contains(part), part + " in " + detail);
        }
    }

    @Test
    void aClickInAContainerTheServerClosedIsARaceNotAViolation() {
        install(observing());
        join();
        openClickAndClose(7, false);
        click(7);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void aContainerTheServerOpensAgainUnderTheSameIdIsANewContainer() {
        // The server's counter wraps after 100 opens: an id the client closed long ago can name a new one.
        install(observing());
        join();
        openClickAndClose(7, true);
        server.openContainer = 7;
        click(7);
        SentinelEngine.containerClosed(PLAYER, false);
        server.openContainer = 0;
        click(7);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void closingTheInventoryAndClickingItIsNotAViolation() {
        install(observing());
        join();
        server.openContainer = 0;
        SentinelEngine.containerClosed(PLAYER, true);
        click(0);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void closedWindowOffJournalsNothingButUnknownWindowStillWorks() {
        install(new Settings(Mode.OBSERVE, 1, Mode.OBSERVE, Mode.OBSERVE, Mode.OFF, Mode.OBSERVE,
            100, REPORT_INTERVAL_MILLIS));
        join();
        openClickAndClose(7, true);
        click(7);
        assertTrue(server.violations.isEmpty(), "a closed container is not an unknown one: " + server.violations);

        click(9);
        assertEquals(List.of(DeterministicChecks.UNKNOWN_WINDOW), journalled());
    }

    @Test
    void closedWindowStillWorksWhenUnknownWindowIsOff() {
        install(new Settings(Mode.OBSERVE, 1, Mode.OBSERVE, Mode.OFF, Mode.OBSERVE, Mode.OBSERVE,
            100, REPORT_INTERVAL_MILLIS));
        join();
        openClickAndClose(7, true);
        click(7);
        click(9);

        assertEquals(List.of(DeterministicChecks.CLOSED_WINDOW), journalled(), () -> server.journal.toString());
    }

    @Test
    void closedWindowIsSilencedByItsGroupExemptionAndByCreativeOrSpectator() {
        install(observing());
        join();
        openClickAndClose(7, true);
        exemptions.grant(owner, PLAYER, new ExemptionScope(CheckGroup.INTERACTION, ExemptionReason.CINEMATIC),
            Duration.ofSeconds(60));
        click(7);
        assertTrue(server.violations.isEmpty(), "exempted group");

        exemptions.clearPlayer(PLAYER);
        server.exemptGameMode = true;
        click(7);
        assertTrue(server.violations.isEmpty(), "creative or spectator");

        server.exemptGameMode = false;
        click(7);
        assertEquals(List.of(DeterministicChecks.CLOSED_WINDOW), journalled());
    }

    @Test
    void aSessionThatLeftForgetsWhatItClosed() {
        install(observing());
        join();
        openClickAndClose(7, true);
        SentinelEngine.forgetSession(PLAYER);
        server.openContainer = 0;
        click(7);

        assertEquals(List.of(DeterministicChecks.UNKNOWN_WINDOW), journalled(), "new session: 7 was never shown to it");
    }

    @Test
    void aSessionThatLeftStartsOverUnarmed() {
        install(observing());
        join();
        SentinelEngine.forgetSession(PLAYER);
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, false);
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 18);

        assertTrue(server.violations.isEmpty(), () -> "the new session has not ended a tick yet: " + server.violations);
    }
}
