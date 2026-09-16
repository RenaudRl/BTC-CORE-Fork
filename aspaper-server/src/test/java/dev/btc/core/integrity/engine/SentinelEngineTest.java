package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckGroup;
import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.api.integrity.IntegrityAPI.CustomMechanic;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionReason;
import dev.btc.core.api.integrity.IntegrityAPI.MechanicType;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionScope;
import dev.btc.core.api.integrity.IntegrityAPI.TeleportKind;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationEvent;
import dev.btc.core.integrity.CheckRegistry;
import dev.btc.core.integrity.ExemptionRegistry;
import dev.btc.core.integrity.SentinelHooks;
import dev.btc.core.integrity.engine.ReachCheck.Box;
import dev.btc.core.integrity.engine.ReachCheck.ReachContext;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stage-1 engine driven through the seam, with a recorder where the server would be.
 *
 * <p>What is pinned: a teleport the fork armed is never a violation; the positions in flight during
 * it are ignored rather than judged; an arrival elsewhere is; an exemption on the group silences the
 * violation; and — the property that makes it safe to deploy — no level the engine can reach ever
 * produces a response, because the checks are registered as observing.
 */
class SentinelEngineTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final CheckRegistry checks = new CheckRegistry();
    private final ExemptionRegistry exemptions = new ExemptionRegistry();
    private final Recorder server = new Recorder();
    private final Plugin owner = Mockito.mock(Plugin.class);
    private AutoCloseable installation;

    @BeforeEach
    void install() {
        Mockito.when(owner.getName()).thenReturn("test");
        installation = SentinelEngine.install(owner, checks, exemptions, server);
    }

    @AfterEach
    void uninstall() throws Exception {
        installation.close();
        assertFalse(SentinelHooks.observing(), "closing the installation must leave the seam empty");
        assertTrue(checks.registered().isEmpty(), "closing the installation must remove its checks");
    }

    @Test
    void anExpectedTeleportConfirmedAndReachedIsNotAViolation() {
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, true);
        SentinelHooks.teleportExpected(PLAYER, 100.5, 70, -20.5);
        // The client keeps talking about the old place until it confirms.
        SentinelHooks.move(PLAYER, 0.3, 64, 0.1, 0f, 0f, true, true, true);
        SentinelHooks.move(PLAYER, 0.6, 64, 0.2, 0f, 0f, true, true, true);
        SentinelHooks.teleportAcknowledged(PLAYER);
        SentinelHooks.move(PLAYER, 100.5, 70, -20.5, 0f, 0f, false, true, true);

        assertTrue(server.violations.isEmpty(), () -> "unexpected: " + server.violations);
    }

    @Test
    void packetsInFlightDuringATeleportAreNeitherJudgedNorCounted() {
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, true);
        SentinelHooks.teleportExpected(PLAYER, 100, 70, 100);
        // A client that lags through a world change flushes everything at once when it catches up.
        // Charged to the cadence, that flush would be a Timer violation on every honest teleport.
        for (int packet = 0; packet < 100; packet++) {
            SentinelHooks.move(PLAYER, packet * 0.1, 64, 0, 0f, 0f, true, true, true);
        }
        SentinelHooks.teleportAcknowledged(PLAYER);
        SentinelHooks.move(PLAYER, 100, 70, 100, 0f, 0f, false, true, true);

        assertTrue(server.violations.isEmpty(), () -> "unexpected: " + server.violations);
    }

    @Test
    void aDeclaredTeleportAddsItsKindToTheVerboseAndNothingElse() {
        server.declared = Optional.of(TeleportKind.MOB_ABILITY);
        SentinelHooks.teleportExpected(PLAYER, 10, 65, 10);
        SentinelHooks.teleportAcknowledged(PLAYER);
        SentinelHooks.move(PLAYER, 10, 65, 10, 0f, 0f, false, true, true);

        assertTrue(server.violations.isEmpty());
        assertEquals(1, server.verbose.size());
        assertTrue(server.verbose.get(0).contains("MOB_ABILITY"), server.verbose.get(0));
    }

    @Test
    void arrivingSomewhereElseThanTheConfirmedDestinationIsAViolation() {
        SentinelHooks.teleportExpected(PLAYER, 10, 65, 10);
        SentinelHooks.teleportAcknowledged(PLAYER);
        SentinelHooks.move(PLAYER, 14, 65, 10, 0f, 0f, false, true, true);

        assertEquals(1, server.violations.size());
        ViolationEvent violation = server.violations.get(0);
        assertEquals(SentinelEngine.TELEPORT_ARRIVAL, violation.check());
        assertEquals(PLAYER, violation.playerId());
        assertTrue(violation.verboseDetail().contains("4.00 blocks"), violation.verboseDetail());
        assertEquals(1, server.journal.size(), "a violation is journalled");
        // The event carries the level at the instant of the violation; the registry has already
        // started decaying it by the time the assertion reads it.
        assertEquals(1.0, violation.violationLevel(), 1e-9);
    }

    @Test
    void aRotationOnlyPacketAfterTheAcknowledgementIsNotAnArrival() {
        SentinelHooks.teleportExpected(PLAYER, 10, 65, 10);
        SentinelHooks.teleportAcknowledged(PLAYER);
        // Rotation only: the position fields carry the server's current value, not a claim.
        SentinelHooks.move(PLAYER, 0, 64, 0, 90f, 0f, false, false, true);
        SentinelHooks.move(PLAYER, 10, 65, 10, 90f, 0f, false, true, true);

        assertTrue(server.violations.isEmpty(), () -> "unexpected: " + server.violations);
    }

    @Test
    void aPositionExemptionSilencesTheArrivalCheck() {
        exemptions.grant(owner, PLAYER,
            new ExemptionScope(CheckGroup.POSITION, ExemptionReason.SCRIPTED_TELEPORT), Duration.ofSeconds(5));
        SentinelHooks.teleportExpected(PLAYER, 10, 65, 10);
        SentinelHooks.teleportAcknowledged(PLAYER);
        SentinelHooks.move(PLAYER, 40, 65, 10, 0f, 0f, false, true, true);

        assertTrue(server.violations.isEmpty());
    }

    @Test
    void aBurstOfMovementPacketsIsATimerViolation() {
        // A hundred packets in a few microseconds: no honest client tick fits in there.
        for (int packet = 0; packet < 100; packet++) {
            SentinelHooks.move(PLAYER, packet * 0.1, 64, 0, 0f, 0f, true, true, false);
        }
        assertFalse(server.violations.isEmpty(), "a burst must be reported");
        assertTrue(server.violations.stream().allMatch(v -> v.check().equals(SentinelEngine.TIMER)));
        assertTrue(server.violations.get(0).verboseDetail().contains("ahead of real time"));
    }

    @Test
    void aMovementExemptionSilencesTheTimerButKeepsMeasuring() {
        exemptions.grant(owner, PLAYER,
            new ExemptionScope(CheckGroup.MOVEMENT, ExemptionReason.CINEMATIC), Duration.ofSeconds(5));
        for (int packet = 0; packet < 100; packet++) {
            SentinelHooks.move(PLAYER, packet * 0.1, 64, 0, 0f, 0f, true, true, false);
        }
        assertTrue(server.violations.isEmpty(), "exempted: no violation");
    }

    @Test
    void theObservingChecksNeverRespondWhateverTheLevel() {
        for (CheckId check : List.of(SentinelEngine.TIMER, SentinelEngine.TELEPORT_ARRIVAL)) {
            checks.addViolation(PLAYER, check, 1_000_000);
            assertEquals(CheckRegistry.Response.NONE, checks.responseFor(check, 1_000_000),
                check + " is registered to observe, never to act");
        }
    }

    @Test
    void forgettingAPlayerDropsTheirSession() {
        SentinelHooks.teleportExpected(PLAYER, 10, 65, 10);
        SentinelEngine.forgetSession(PLAYER);
        // A fresh session has no pending teleport: this position is judged as ordinary movement.
        SentinelHooks.teleportAcknowledged(PLAYER);
        SentinelHooks.move(PLAYER, 99, 65, 99, 0f, 0f, false, true, true);

        assertTrue(server.violations.isEmpty(), "no stale expectation survives a forget");
    }

    // ------------------------------------------------------------------ envelope (3.2, 3.4)

    @Test
    void aVanillaWalkOnTheGroundProducesNothing() {
        server.movement = Optional.of(MovementContext.grounded());
        for (int tick = 0; tick < 20; tick++) {
            SentinelHooks.move(PLAYER, tick * 0.1, 64, 0, 0f, 0f, true, true, false);
            sleepOneTick();
        }
        assertTrue(server.violations.isEmpty(), () -> "unexpected: " + server.violations);
    }

    @Test
    void anEnvelopeDivergenceIsJournalledWithTheSessionOrigin() {
        server.movement = Optional.of(MovementContext.grounded().withPlatform(ClientPlatform.BEDROCK));
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, false);
        sleepOneTick();
        SentinelHooks.move(PLAYER, 4, 64, 0, 0f, 0f, true, true, false);

        assertEquals(1, server.violations.size(), () -> server.violations.toString());
        ViolationEvent violation = server.violations.get(0);
        assertEquals(SentinelEngine.SPEED, violation.check());
        assertTrue(violation.verboseDetail().startsWith("[origin BEDROCK]"), violation.verboseDetail());
        assertEquals(1, server.journal.size());
        assertTrue(server.journal.get(0).contains("[origin BEDROCK]"), server.journal.get(0));
    }

    @Test
    void aMovementExemptionSilencesTheEnvelopeButTheReferenceKeepsMoving() {
        server.movement = Optional.of(MovementContext.grounded());
        exemptions.grant(owner, PLAYER,
            new ExemptionScope(CheckGroup.MOVEMENT, ExemptionReason.CINEMATIC), Duration.ofSeconds(5));
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, false);
        sleepOneTick();
        SentinelHooks.move(PLAYER, 40, 64, 0, 0f, 0f, true, true, false);
        assertTrue(server.violations.isEmpty(), "exempted: no violation");

        // The exemption ends; the next honest step is judged from where the player actually is.
        exemptions.clearOwner(owner);
        sleepOneTick();
        SentinelHooks.move(PLAYER, 40.2, 64, 0, 0f, 0f, true, true, false);
        assertTrue(server.violations.isEmpty(), () -> "the reference moved under the exemption: " + server.violations);
    }

    @Test
    void aVelocityTheServerSentIsFoldedIntoThePrediction() {
        server.movement = Optional.of(MovementContext.grounded());
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, false);
        sleepOneTick();
        SentinelEngine.serverVelocity(PLAYER, 1.0, 0.5, 0);
        SentinelHooks.move(PLAYER, 1.0, 64.5, 0, 0f, 0f, false, true, false);
        assertTrue(server.violations.isEmpty(), () -> "knockback is expected, not flagged: " + server.violations);
    }

    @Test
    void aLateDeclarationDoesNotRewriteTheVerdictAlreadyGiven() {
        // 9.1, "déclaration tardive": the launch is declared after the packet it explains arrived.
        // The packet was judged against what was known; the declaration covers the next one only.
        server.movement = Optional.of(MovementContext.airborne());
        SentinelHooks.move(PLAYER, 0, 70, 0, 0f, 0f, false, true, false);
        sleepOneTick();
        SentinelHooks.move(PLAYER, 1.2, 69.9, 0, 0f, 0f, false, true, false);
        assertEquals(1, server.violations.size(), "judged before the declaration: a divergence");

        server.movement = Optional.of(MovementContext.airborne().withMechanics(List.of(
            new CustomMechanic(MechanicType.LAUNCH, Optional.of(new Vector(1.2, 0, 0))))));
        sleepOneTick();
        SentinelHooks.move(PLAYER, 2.4, 69.7, 0, 0f, 0f, false, true, false);
        assertEquals(1, server.violations.size(), "declared in time for this one: nothing new");
    }

    @Test
    void withoutAContextTheNextPositionIsAReferenceNotAMove() {
        server.movement = Optional.empty();
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, false);
        sleepOneTick();
        server.movement = Optional.of(MovementContext.grounded());
        // First judged position after the gap: a reference. Only the one after is judged.
        SentinelHooks.move(PLAYER, 50, 64, 0, 0f, 0f, true, true, false);
        sleepOneTick();
        SentinelHooks.move(PLAYER, 50.1, 64, 0, 0f, 0f, true, true, false);
        assertTrue(server.violations.isEmpty(), () -> "unexpected: " + server.violations);
    }

    @Test
    void aRotationOnlyPacketDoesNotMoveTheReference() {
        server.movement = Optional.of(MovementContext.grounded());
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, false);
        sleepOneTick();
        // The handler defaults the position fields to the server's current value, which here is
        // wherever the mock says; the engine must not read them as a claim.
        SentinelHooks.move(PLAYER, 99, 64, 99, 90f, 0f, true, false, true);
        sleepOneTick();
        SentinelHooks.move(PLAYER, 0.1, 64, 0, 90f, 0f, true, true, true);
        assertTrue(server.violations.isEmpty(), () -> "unexpected: " + server.violations);
    }

    // ------------------------------------------------------------------ reach

    @Test
    void anAttackBeyondTheGrantedReachIsJournalledWithTheOrigin() {
        server.reach = Optional.of(new ReachContext(0, 0, 0, List.of(new Box(4, -0.5, -0.5, 5, 0.5, 0.5)),
            3.3, false, ClientPlatform.JAVA, -1));
        SentinelHooks.attack(PLAYER, 42);
        assertEquals(1, server.violations.size());
        assertEquals(SentinelEngine.REACH, server.violations.get(0).check());
        assertTrue(server.violations.get(0).verboseDetail().startsWith("[origin JAVA]"));
    }

    @Test
    void aCombatExemptionSilencesReach() {
        exemptions.grant(owner, PLAYER,
            new ExemptionScope(CheckGroup.COMBAT, ExemptionReason.CINEMATIC), Duration.ofSeconds(5));
        server.reach = Optional.of(new ReachContext(0, 0, 0, List.of(new Box(40, -0.5, -0.5, 41, 0.5, 0.5)),
            3.3, false, ClientPlatform.JAVA, -1));
        SentinelHooks.attack(PLAYER, 42);
        assertTrue(server.violations.isEmpty());
    }

    // ------------------------------------------------------------------ round trip (3.5)

    @Test
    void aMovingSessionIsPingedOncePerIntervalNotOncePerPacket() {
        for (int packet = 0; packet < 5; packet++) {
            SentinelHooks.move(PLAYER, packet * 0.1, 64, 0, 0f, 0f, true, true, false);
        }
        assertEquals(1, server.pings.size(), "five packets within the interval: one ping");
        assertTrue(server.pings.get(0) <= RoundTripMeter.FIRST_ID + 1_000_000
            && server.pings.get(0) >= RoundTripMeter.FIRST_ID, "an id from the engine's range");
    }

    @Test
    void theAnswerToOurPingIsTheRoundTripTheReachCheckCompensatesWith() {
        SentinelHooks.move(PLAYER, 0, 64, 0, 0f, 0f, true, true, false);
        int id = server.pings.get(0);
        server.reach = Optional.of(new ReachContext(0, 0, 0, List.of(new Box(1, -0.5, -0.5, 2, 0.5, 0.5)),
            3.3, false, ClientPlatform.JAVA, -1));

        SentinelHooks.attack(PLAYER, 42);
        assertEquals(-1L, server.roundTripsSeen.get(0), "before any answer, the reach check is told so");

        sleepOneTick();
        SentinelHooks.pong(PLAYER, id);
        SentinelHooks.attack(PLAYER, 42);
        long measured = server.roundTripsSeen.get(1);
        assertTrue(measured >= 50_000_000L, () -> "at least the tick slept: " + measured);

        SentinelHooks.pong(PLAYER, 12345);
        SentinelHooks.attack(PLAYER, 42);
        assertEquals(measured, server.roundTripsSeen.get(2), "an answer to a ping we did not send changes nothing");
    }

    @Test
    void allSevenChecksAreRegisteredObserving() {
        for (CheckId check : List.of(SentinelEngine.TIMER, SentinelEngine.TELEPORT_ARRIVAL,
            SentinelEngine.SPEED, SentinelEngine.FLY, SentinelEngine.FALL, SentinelEngine.PHASE,
            SentinelEngine.REACH)) {
            checks.addViolation(PLAYER, check, 1_000_000);
            assertEquals(CheckRegistry.Response.NONE, checks.responseFor(check, 1_000_000),
                check + " is registered to observe, never to act");
        }
        assertEquals(7, checks.registered().size());
    }

    /** One client tick of real time, so the timer check stays out of envelope tests. */
    private static void sleepOneTick() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Records what the engine would have told the server. */
    private static final class Recorder implements ServerAdapter {
        final List<ViolationEvent> violations = new ArrayList<>();
        final List<String> journal = new ArrayList<>();
        final List<String> verbose = new ArrayList<>();
        Optional<TeleportKind> declared = Optional.empty();
        Optional<MovementContext> movement = Optional.empty();
        Optional<ReachContext> reach = Optional.empty();
        final List<Integer> pings = new ArrayList<>();
        final List<Long> roundTripsSeen = new ArrayList<>();

        @Override
        public Optional<String> playerName(UUID player) {
            return Optional.of("Tester");
        }

        @Override
        public Optional<TeleportKind> consumeDeclaredTeleport(UUID player, double x, double y, double z) {
            Optional<TeleportKind> kind = declared;
            declared = Optional.empty();
            return kind;
        }

        @Override
        public Optional<MovementContext> movementContext(UUID player, double x, double y, double z) {
            return movement;
        }

        @Override
        public Optional<ReachContext> reachContext(UUID player, int targetEntityId, long roundTripNanos) {
            roundTripsSeen.add(roundTripNanos);
            return reach;
        }

        @Override
        public void ping(UUID player, int id) {
            pings.add(id);
        }

        @Override
        public void journal(UUID player, String playerName, CheckId check, String detail) {
            journal.add(check + " " + detail);
        }

        @Override
        public void publish(UUID player, ViolationEvent event) {
            violations.add(event);
        }

        @Override
        public void verbose(String line) {
            verbose.add(line);
        }
    }
}
