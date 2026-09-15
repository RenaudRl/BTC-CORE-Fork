package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckGroup;
import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionReason;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionScope;
import dev.btc.core.api.integrity.IntegrityAPI.TeleportKind;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationEvent;
import dev.btc.core.integrity.CheckRegistry;
import dev.btc.core.integrity.ExemptionRegistry;
import dev.btc.core.integrity.SentinelHooks;
import org.bukkit.plugin.Plugin;
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

    /** Records what the engine would have told the server. */
    private static final class Recorder implements ServerAdapter {
        final List<ViolationEvent> violations = new ArrayList<>();
        final List<String> journal = new ArrayList<>();
        final List<String> verbose = new ArrayList<>();
        Optional<TeleportKind> declared = Optional.empty();

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
