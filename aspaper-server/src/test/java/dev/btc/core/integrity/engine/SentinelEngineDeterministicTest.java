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
        assertEquals(CheckGroup.INTERACTION, checks.find(DeterministicChecks.TICK_END_FLOOD).orElseThrow().definition().group());
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
        for (int tick = 0; tick < 39; tick++) { // the 39 credits left after join: within the tick-end burst
            SentinelHooks.attack(PLAYER, 100 + tick);
            SentinelHooks.attack(PLAYER, 200 + tick);
            endTick();
            advance(20); // 50 client ticks a second, under a second in all
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
        open(id);
        click(id);
        closeContainer(id, byClient);
        server.openContainer = 0;
    }

    /** The server opens container {@code id}: the seam in {@code initMenu}, and it is the open one from now on. */
    private void open(int id) {
        server.openContainer = id;
        SentinelHooks.containerOpen(PLAYER, id);
    }

    /** What the seam sees of a close: the client's packet (when the client closed), with the server's open id, then the close event. */
    private void closeContainer(int packetId, boolean byClient) {
        if (byClient) {
            SentinelHooks.containerClose(PLAYER, packetId, server.openContainer);
        }
        SentinelEngine.containerClosed(PLAYER, byClient);
    }

    @Test
    void theContainerTheClientClosedIsTheOneItsPacketNamesNotTheOneTheServerHasOpenNow() {
        // The client closes menu 7; its packet is in flight while the server already shows menu 8. The close
        // event reports the server's open id (8), but 8 is not what the client closed. The server replaced 7 by 8
        // a moment ago on the server's clock: the client cannot have seen 8, the race holds.
        install(observing());
        join();
        open(7);
        click(7);
        SentinelEngine.containerClosed(PLAYER, false); // OPEN_NEW: the server closes 7 to open 8
        open(8);
        closeContainer(7, true);
        server.openContainer = 9;
        click(8);
        assertTrue(server.violations.isEmpty(), () -> "8 was closed by the server, a click in flight is a race: " + server.violations);

        advance(REPORT_INTERVAL_MILLIS + 1);
        click(7);
        assertEquals(List.of(DeterministicChecks.CLOSED_WINDOW), journalled(), () -> server.journal.toString());
    }

    @Test
    void aClosePacketNamingAnotherIdDoesNotHideTheContainerTheServerClosedForIt() {
        // The server closes whatever it has open, whatever id the packet names: naming 99 must not leave 7 clickable.
        install(observing());
        join();
        open(7);
        click(7);
        closeContainer(99, true);
        server.openContainer = 0;
        advance(REPORT_INTERVAL_MILLIS + 1);
        click(7);

        assertEquals(List.of(DeterministicChecks.CLOSED_WINDOW, DeterministicChecks.CLOSED_WINDOW), journalled(),
            () -> server.journal.toString());
        String mismatch = server.violations.get(0).verboseDetail();
        for (String part : List.of("packet=ServerboundContainerClosePacket", "names container=99", "open=7")) {
            assertTrue(mismatch.contains(part), part + " in " + mismatch);
        }
    }

    /** The client answers the engine's first ping {@code millis} after it left: the session's round trip. */
    private void answerTheFirstPingAfter(long millis) {
        int id = server.pings.get(0);
        advance(millis);
        SentinelHooks.pong(PLAYER, id);
    }

    @Test
    void aClosePacketNamingTheInventoryLongAfterTheMenuOpenedDoesNotHideIt() {
        // Contre-revue 14/11: withholding its tick ends must not keep the excuse alive. The window is the server's
        // clock since the open, at most 300 ms: 1.5 s later the menu has reached the client.
        install(observing());
        join();
        open(7);
        advance(1_500);
        closeContainer(0, true);
        server.openContainer = 0;
        advance(REPORT_INTERVAL_MILLIS + 1);
        click(7);

        assertEquals(List.of(DeterministicChecks.CLOSED_WINDOW, DeterministicChecks.CLOSED_WINDOW), journalled(),
            () -> server.journal.toString());
    }

    @Test
    void aClosePacketNamingTheInventoryWhileTheServerJustOpenedAMenuIsARace() {
        // The client closed its own inventory while the server was opening menu 7 it has not shown yet.
        install(observing());
        join();
        open(7);
        closeContainer(0, true);
        server.openContainer = 0;
        click(7);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void aRaceAt150MillisOfRoundTripIsExcusedWithinTheRoundTripAndAMargin() {
        // The close left the client before the open reached it: it arrives within a round trip and a margin.
        install(observing());
        join();
        answerTheFirstPingAfter(150);
        open(7);
        advance(240);
        closeContainer(0, true);
        server.openContainer = 0;
        click(7);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void aCloseArrivingBeyondTheRoundTripAndTheMarginIsNotARace() {
        install(observing());
        join();
        answerTheFirstPingAfter(150);
        open(7);
        advance(300);
        closeContainer(0, true);

        assertEquals(List.of(DeterministicChecks.CLOSED_WINDOW), journalled(), () -> server.journal.toString());
    }

    @Test
    void aRoundTripInflatedByTheClientDoesNotWidenTheWindowBeyondItsCap() {
        // A client answering its pings 900 ms late: the window stays at 300 ms.
        install(observing());
        join();
        answerTheFirstPingAfter(900);
        open(7);
        advance(500);
        closeContainer(0, true);

        assertEquals(List.of(DeterministicChecks.CLOSED_WINDOW), journalled(), () -> server.journal.toString());
    }

    @Test
    void aTickEndAfterTheOpenEndsTheExcuseWhateverTheRoundTrip() {
        install(observing());
        join();
        answerTheFirstPingAfter(150);
        open(7);
        endTick();
        closeContainer(0, true);

        assertEquals(List.of(DeterministicChecks.CLOSED_WINDOW), journalled(), () -> server.journal.toString());
    }

    @Test
    void aClosePacketNamingAnIdTheServerCannotHandOutIsNotRemembered() {
        install(observing());
        join();
        SentinelHooks.containerClose(PLAYER, Integer.MAX_VALUE, 0);
        SentinelHooks.containerClose(PLAYER, 101, 0);
        click(101);
        click(Integer.MAX_VALUE);

        assertEquals(List.of(DeterministicChecks.UNKNOWN_WINDOW), journalled(), "unknown, not closed: it was never shown");
    }

    // ------------------------------------------------------------------ session binding (forgetSession)

    @Test
    void aLateQuitOfAnOldSessionDoesNotEraseTheStateOfTheFastReconnection() {
        install(observing());
        SentinelEngine.bindSession(PLAYER, 1);
        join();
        SentinelEngine.bindSession(PLAYER, 2); // the player came back before the old quit was handled
        join();
        SentinelEngine.forgetSession(PLAYER, 1); // the old session's quit, late
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 18);
        assertEquals(List.of(DeterministicChecks.MULTI_TARGET), journalled(), "the new session is still armed");

        SentinelEngine.forgetSession(PLAYER, 2);
        advance(REPORT_INTERVAL_MILLIS + 1);
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, false);
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 18);
        assertEquals(1, server.violations.size(), "the quit of the current session forgets it");
    }

    // ------------------------------------------------------------------ tick-end bound

    private static Settings withTickEnds(Mode mode, int burst) {
        return new Settings(Mode.OBSERVE, 1, Mode.OBSERVE, Mode.OBSERVE, Mode.OBSERVE, Mode.OBSERVE,
            100, REPORT_INTERVAL_MILLIS, mode, burst);
    }

    @Test
    void tickEndsBeyondTheBurstDoNotEndATickAndAreJournalled() {
        // A hostile client sends a tick end between two attacks so that each is "its own tick".
        install(withTickEnds(Mode.OBSERVE, 3));
        join();                      // tick end 1
        SentinelHooks.attack(PLAYER, 17);
        endTick();                   // 2
        SentinelHooks.attack(PLAYER, 18);
        endTick();                   // 3: the burst is spent
        SentinelHooks.attack(PLAYER, 19);
        endTick();                   // 4: no credit, not a tick end
        SentinelHooks.attack(PLAYER, 20);

        assertEquals(List.of(DeterministicChecks.TICK_END_FLOOD, DeterministicChecks.MULTI_TARGET), journalled(),
            () -> server.journal.toString());
        String detail = server.violations.get(0).verboseDetail();
        for (String part : List.of("[origin JAVA]", "burst=3")) {
            assertTrue(detail.contains(part), part + " in " + detail);
        }
    }

    @Test
    void theBacklogOfAFreezeIsAbsorbedAndEveryTickEndEndsATick() {
        // Review 14/11: a region or network freeze of 0.5 to 2 s delivers its tick ends all at once.
        install(observing());
        join();
        advance(2_000);
        for (int tick = 0; tick < 40; tick++) { // two seconds of an honest client's ticks
            SentinelHooks.attack(PLAYER, 100 + tick);
            endTick();
        }
        SentinelHooks.attack(PLAYER, 1);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void aSustainedFloodFasterThanTheServerTicksRunsDryAndIsJournalled() {
        install(observing());
        join();
        for (int tick = 0; tick < 200; tick++) {
            SentinelHooks.attack(PLAYER, 100 + tick);
            endTick();
            advance(5); // 200 tick ends a second, ten times an honest client
        }

        assertEquals(List.of(DeterministicChecks.TICK_END_FLOOD, DeterministicChecks.MULTI_TARGET), journalled(),
            () -> server.journal.toString());
    }

    @Test
    void withholdingOnlyTheTickEndsWhileStillTalkingEarnsNoBurst() {
        // Contre-revue 14/11 (N3): a client that keeps sending packets but holds its tick ends back is not frozen,
        // only a silence (no packet at all) refills the burst.
        install(observing());
        join();
        for (int tick = 0; tick < 40; tick++) { // an honest client: the saved-up burst goes back to the jitter allowance
            advance(50);
            SentinelHooks.useItem(PLAYER, false);
            endTick();
        }
        for (int held = 0; held < 20; held++) { // one second of packets with the tick ends held back
            advance(50);
            SentinelHooks.useItem(PLAYER, false);
        }
        for (int tick = 0; tick < 6; tick++) { // then released between attacks, one target per "tick"
            SentinelHooks.attack(PLAYER, 100 + tick);
            endTick();
        }
        SentinelHooks.attack(PLAYER, 1);

        assertTrue(journalled().contains(DeterministicChecks.TICK_END_FLOOD), () -> server.journal.toString());
    }

    @Test
    void theRefillFollowsTheServerTickRate() {
        // N6: at /tick rate 40 an honest client ends 40 ticks a second; the bucket must follow the server's rate.
        install(observing());
        server.tickNanos = TimeUnit.MILLISECONDS.toNanos(25);
        join();
        for (int tick = 0; tick < 200; tick++) {
            advance(25);
            SentinelHooks.attack(PLAYER, 100 + tick);
            endTick();
        }

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void tickEndsSpreadOverServerTicksAreNeverTooMany() {
        install(withTickEnds(Mode.OBSERVE, 2));
        join();
        for (int i = 0; i < 20; i++) {
            advance(50);
            endTick();
            advance(50);
            endTick();
        }
        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
    }

    @Test
    void withTheFloodCheckOffEveryTickEndEndsATick() {
        // Off means no effect at all: a bound nobody watches would only turn lag into false multi-targets.
        install(withTickEnds(Mode.OFF, 3));
        join();
        SentinelHooks.attack(PLAYER, 17);
        endTick();
        endTick();
        endTick();
        SentinelHooks.attack(PLAYER, 18);
        endTick();
        SentinelHooks.attack(PLAYER, 19);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
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
        SentinelEngine.forgetSession(PLAYER, 1);
        server.openContainer = 0;
        click(7);

        assertEquals(List.of(DeterministicChecks.UNKNOWN_WINDOW), journalled(), "new session: 7 was never shown to it");
    }

    // ------------------------------------------------------------------ wall-hit

    private static final double EYE_Y = 1.62;

    private static ReachCheck.Box boxAt(double minX) {
        return new ReachCheck.Box(minX, 0, -0.3, minX + 0.6, 1.8, 0.3);
    }

    /** An attacker at the origin whose target occupied the given boxes, the current one first. */
    private ReachCheck.ReachContext reachOver(boolean targetIgnored, ReachCheck.Box... boxes) {
        return new ReachCheck.ReachContext(0, EYE_Y, 0, List.of(boxes), 3.0, targetIgnored,
            ClientPlatform.JAVA, 50);
    }

    private void attackWith(ReachCheck.ReachContext context) {
        server.reach = java.util.Optional.of(context);
        SentinelHooks.attack(PLAYER, 17);
    }

    @Test
    void anAttackWhoseEveryLineIsBlockedIsJournalled() {
        install(observing());
        join();
        server.blocked = ray -> true;
        attackWith(reachOver(false, boxAt(2.0), boxAt(1.9)));

        assertEquals(List.of(DeterministicChecks.WALL_HIT), journalled(), () -> server.journal.toString());
        assertTrue(server.journal.get(0).startsWith("sentinel:wall-hit "), server.journal.get(0));
        String detail = server.violations.get(0).verboseDetail();
        for (String part : List.of("[origin JAVA]", "packet=ServerboundAttackPacket", "target=17",
            "blocked on 2 of 2 target boxes")) {
            assertTrue(detail.contains(part), part + " in " + detail);
        }
        assertEquals(2, server.rays.size(), "one ray per box, no more");
    }

    @Test
    void aWallHitLineCarriesTheGeometryOfTheCurrentBoxAndWhatTheServerSawOnItsRay() {
        // 14/11 bench: 27 lines "blocked on all boxes" in an open spawn, and nothing in the line said what was hit.
        install(observing());
        join();
        server.blocked = ray -> true;
        server.wallEvidence = "hit=Block{minecraft:stone} at 2 1 0";
        attackWith(reachOver(false, boxAt(2.0), boxAt(1.9)));

        String detail = server.violations.get(0).verboseDetail();
        for (String part : List.of("eye=(0.00 1.62 0.00)", "ray-end=(2.02 1.62 0.00)",
            "box=(2.00 0.00 -0.30)-(2.60 1.80 0.30)", "hit=Block{minecraft:stone} at 2 1 0")) {
            assertTrue(detail.contains(part), part + " in " + detail);
        }
        // The journal keeps 255 characters: what the world showed goes first, the geometry after it.
        assertTrue(detail.indexOf("hit=Block{minecraft:stone}") < detail.indexOf("eye="), detail);
        assertEquals(1, server.evidenceRays.size(), "one extra look, only for a line that is journalled");
        assertEquals(2.0 + WallHitCheck.END_INSET, server.evidenceRays.get(0)[3], 1e-9, "the ray of the current box");
        assertEquals(17, server.evidenceTargets.get(0));
    }

    @Test
    void aClearLineAsksTheServerForNoEvidence() {
        install(observing());
        join();
        attackWith(reachOver(false, boxAt(2.0)));

        assertTrue(server.evidenceRays.isEmpty());
    }

    @Test
    void anAttackWithAClearLineIsNotAViolationAndCostsOneRay() {
        install(observing());
        join();
        attackWith(reachOver(false, boxAt(2.0), boxAt(1.9), boxAt(1.8)));

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
        assertEquals(1, server.rays.size(), "the first clear line ends the judgement");
    }

    @Test
    void aTargetThatStoodInTheOpenDuringTheRoundTripIsNotBehindAWall() {
        // Now behind the corner, a tick ago in the open: the attacker's client was still showing the old place.
        install(observing());
        join();
        server.blocked = ray -> ray[3] > 2.0;
        attackWith(reachOver(false, boxAt(2.1), boxAt(1.9)));

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
        assertEquals(2, server.rays.size());
    }

    @Test
    void theLineGoesFromTheEyeToTheNearestPointOfTheBoxJustInsideIt() {
        install(observing());
        join();
        attackWith(reachOver(false, boxAt(2.0)));

        double[] ray = server.rays.get(0);
        assertEquals(0, ray[0], 1e-9);
        assertEquals(EYE_Y, ray[1], 1e-9);
        assertEquals(0, ray[2], 1e-9);
        assertEquals(2.0 + WallHitCheck.END_INSET, ray[3], 1e-9, "the near face, a hair inside");
        assertEquals(EYE_Y, ray[4], 1e-9, "the eye is level with the box, so the line stays level");
        assertEquals(0, ray[5], 1e-9);
    }

    @Test
    void anAttackBeyondTheReachGrantedIsLeftToTheReachCheckAndCastsNoRay() {
        install(observing());
        join();
        server.blocked = ray -> true;
        attackWith(reachOver(false, boxAt(8.0)));

        assertEquals(List.of(SentinelEngine.REACH), journalled());
        assertTrue(server.rays.isEmpty());
    }

    @Test
    void aTargetTheEngineIgnoresAndAnEyeInsideTheBoxCastNoRay() {
        install(observing());
        join();
        server.blocked = ray -> true;
        attackWith(reachOver(true, boxAt(2.0)));
        advance(1_000);
        attackWith(reachOver(false, boxAt(-0.3)));

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
        assertTrue(server.rays.isEmpty());
    }

    @Test
    void aFloodOfAttacksCastsOneSetOfRaysPerMinimumInterval() {
        install(observing());
        join();
        for (int i = 0; i < 50; i++) {
            attackWith(reachOver(false, boxAt(2.0)));
        }
        assertEquals(1, server.rays.size(), "100 ms have not passed");

        advance(101);
        attackWith(reachOver(false, boxAt(2.0)));
        assertEquals(2, server.rays.size());
    }

    @Test
    void aFloodThroughAWallIsOneLinePerReportInterval() {
        install(observing());
        join();
        server.blocked = ray -> true;
        for (int i = 0; i < 40; i++) {
            attackWith(reachOver(false, boxAt(2.0)));
            advance(150);
        }

        // 40 attacks over 6 s, a line at most every second.
        assertTrue(server.journal.size() >= 5 && server.journal.size() <= 7, () -> server.journal.size() + " lines");
    }

    @Test
    void wallHitOffCastsNoRay() {
        install(new Settings(Mode.OBSERVE, 1, Mode.OBSERVE, Mode.OBSERVE, Mode.OBSERVE, Mode.OFF,
            100, REPORT_INTERVAL_MILLIS));
        join();
        server.blocked = ray -> true;
        attackWith(reachOver(false, boxAt(2.0)));

        assertTrue(server.violations.isEmpty());
        assertTrue(server.rays.isEmpty(), "off costs nothing");
    }

    @Test
    void wallHitIsSilencedByItsGroupExemptionAndByCreativeOrSpectatorBeforeAnyRay() {
        install(observing());
        join();
        server.blocked = ray -> true;
        exemptions.grant(owner, PLAYER, new ExemptionScope(CheckGroup.COMBAT, ExemptionReason.CINEMATIC),
            Duration.ofSeconds(60));
        attackWith(reachOver(false, boxAt(2.0)));
        exemptions.clearPlayer(PLAYER);
        server.exemptGameMode = true;
        advance(1_000);
        attackWith(reachOver(false, boxAt(2.0)));

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
        assertTrue(server.rays.isEmpty(), "an exempt player costs no ray");
    }

    @Test
    void theOriginOfABedrockSessionIsStampedOnAWallHit() {
        install(observing());
        join();
        server.blocked = ray -> true;
        server.reach = java.util.Optional.of(new ReachCheck.ReachContext(0, EYE_Y, 0, List.of(boxAt(2.0)), 3.0,
            false, ClientPlatform.BEDROCK, 50));
        SentinelHooks.attack(PLAYER, 17);

        assertTrue(server.violations.get(0).verboseDetail().startsWith("[origin BEDROCK] "),
            server.violations.get(0).verboseDetail());
    }

    @Test
    void aSessionThatLeftStartsOverUnarmed() {
        install(observing());
        join();
        SentinelEngine.forgetSession(PLAYER, 1);
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, false);
        SentinelHooks.attack(PLAYER, 17);
        SentinelHooks.attack(PLAYER, 18);

        assertTrue(server.violations.isEmpty(), () -> "the new session has not ended a tick yet: " + server.violations);
    }
}
