package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckGroup;
import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionReason;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionScope;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationEvent;
import dev.btc.core.integrity.CheckRegistry;
import dev.btc.core.integrity.ExemptionRegistry;
import dev.btc.core.integrity.SentinelHooks;
import dev.btc.core.integrity.engine.SentinelEngineTest.Recorder;
import dev.btc.core.security.exploit.InteractBurst;
import dev.btc.core.security.exploit.InteractBurst.Mode;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 10.14: entity interactions in a burst, counted by the engine and journalled once per window. */
class SentinelEngineInteractBurstTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final int TARGET = 17;
    private static final int THRESHOLD = 5;
    private static final long WINDOW_MILLIS = 60_000;

    private final CheckRegistry checks = new CheckRegistry();
    private final ExemptionRegistry exemptions = new ExemptionRegistry();
    private final Recorder server = new Recorder();
    private final Plugin owner = Mockito.mock(Plugin.class);
    private AutoCloseable installation;

    private void install(Mode mode) {
        install(mode, WINDOW_MILLIS);
    }

    private void install(Mode mode, long windowMillis) {
        Mockito.when(owner.getName()).thenReturn("test");
        installation = SentinelEngine.install(owner, checks, exemptions, server,
            new InteractBurst.Settings(mode, windowMillis, THRESHOLD));
    }

    @AfterEach
    void uninstall() throws Exception {
        installation.close();
    }

    private static void interact(int times) {
        for (int i = 0; i < times; i++) {
            SentinelHooks.interactEntity(PLAYER, TARGET, 0.1, 0.2, 0.3, false);
        }
    }

    @Test
    void interactBurstIsJournalledUnderInteractionGroup() {
        install(Mode.OBSERVE);
        interact(THRESHOLD);

        assertEquals(List.of(InteractBurst.CHECK), server.violations.stream().map(ViolationEvent::check).toList());
        assertEquals(1, server.journal.size());
        assertTrue(server.journal.get(0).startsWith("sentinel:interact-burst "), server.journal.get(0));
    }

    @Test
    void theCheckIsRegisteredInTheInteractionGroup() {
        install(Mode.OBSERVE);

        var registered = checks.find(InteractBurst.CHECK).orElseThrow();
        assertEquals(CheckGroup.INTERACTION, registered.definition().group());
    }

    @Test
    void anExemptionOnAnotherGroupDoesNotSilenceIt() {
        install(Mode.OBSERVE);
        exemptions.grant(owner, PLAYER,
            new ExemptionScope(CheckGroup.MOVEMENT, ExemptionReason.CINEMATIC), Duration.ofSeconds(60));
        interact(THRESHOLD);

        assertEquals(1, server.violations.size(), () -> server.violations.toString());
    }

    @Test
    void interactBelowThresholdIsSilent() {
        install(Mode.OBSERVE);
        interact(THRESHOLD - 1);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
        assertTrue(server.journal.isEmpty());
    }

    @Test
    void aFloodIsOneLineNotOnePerPacket() {
        install(Mode.OBSERVE);
        interact(500);

        assertEquals(1, server.journal.size(), () -> server.journal.toString());
    }

    @Test
    void offModeCountsNothing() {
        install(Mode.OFF);
        interact(500);

        assertTrue(server.violations.isEmpty());
        assertTrue(server.journal.isEmpty());
    }

    @Test
    void exemptedPlayerIsNotReported() {
        install(Mode.OBSERVE);
        exemptions.grant(owner, PLAYER,
            new ExemptionScope(CheckGroup.INTERACTION, ExemptionReason.CINEMATIC), Duration.ofSeconds(60));
        interact(THRESHOLD * 3);

        assertTrue(server.violations.isEmpty(), () -> server.violations.toString());
        assertTrue(server.journal.isEmpty());
    }

    @Test
    void theCountIsTheInteractionsSeenNotTheThreshold() throws InterruptedException {
        install(Mode.OBSERVE, 200);
        interact(1);
        // Longer than the window, with margin: that first interaction is no part of the burst, but it was seen.
        Thread.sleep(400);
        interact(THRESHOLD);

        String detail = server.violations.get(0).verboseDetail();
        assertTrue(detail.contains("count=" + (THRESHOLD + 1) + " "), detail);
        assertTrue(detail.contains("threshold=" + THRESHOLD), detail);
    }

    @Test
    void burstDetailCarriesPacketCountWindowAndOrigin() {
        server.platform = ClientPlatform.BEDROCK;
        install(Mode.OBSERVE);
        interact(THRESHOLD);

        String detail = server.violations.get(0).verboseDetail();
        assertTrue(detail.startsWith("[origin BEDROCK] "), detail);
        for (String part : List.of("packet=ServerboundInteractPacket", "count=5", "window=60000ms", "threshold=5", "target=17")) {
            assertTrue(detail.contains(part), part + " in " + detail);
        }
    }
}
