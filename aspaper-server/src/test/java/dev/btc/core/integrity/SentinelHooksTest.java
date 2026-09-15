package dev.btc.core.integrity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam the fork's packet patches call into.
 *
 * <p>Three claims are worth a test, and each of them is the reason the seam exists. An unarmed
 * server must pay nothing and observe nothing. A packet must reach the engine with the client's
 * own claim intact — in particular a rotation-only packet must not read as a position claim, which
 * is where movement checks invent movement that never happened. And a check that throws must cost
 * observation and never the player's session.
 */
class SentinelHooksTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private RecordingObserver recorder;

    @BeforeEach
    void setUp() {
        SentinelHooks.uninstall();
        recorder = new RecordingObserver();
    }

    @AfterEach
    void tearDown() {
        // Static seam: a test that left an observer installed would arm the next one.
        SentinelHooks.uninstall();
    }

    @Test
    @DisplayName("with nothing installed, every hook is inert and observes nothing")
    void unarmedObservesNothing() {
        assertFalse(SentinelHooks.observing());

        SentinelHooks.move(PLAYER, 1.0, 2.0, 3.0, 4.0f, 5.0f, true, true, true);
        SentinelHooks.attack(PLAYER, 42);
        SentinelHooks.interactEntity(PLAYER, 42, 0.1, 0.2, 0.3, false);
        SentinelHooks.useItem(PLAYER, true);
        SentinelHooks.containerClick(PLAYER, 1, 2, 3, 4);
        SentinelHooks.teleportExpected(PLAYER, 1.0, 2.0, 3.0);
        SentinelHooks.teleportAcknowledged(PLAYER);

        assertEquals(List.of(), recorder.seen, "an uninstalled observer cannot have been called");
    }

    @Test
    @DisplayName("a rotation-only packet reaches the engine as a rotation, not as a position claim")
    void movementCarriesWhatTheClientActuallySent() {
        SentinelHooks.install(recorder);

        // The handler defaults the unsent fields to the player's current values; the flags are the
        // only thing that says which ones the client really claimed.
        SentinelHooks.move(PLAYER, 10.0, 64.0, -3.0, 90.0f, 0.0f, true, false, true);

        assertEquals(List.of("move 10.0 64.0 -3.0 90.0 0.0 ground=true pos=false rot=true"),
            recorder.seen);
    }

    @Test
    @DisplayName("each hook reaches its own observer method with its own payload")
    void everyHookIsWired() {
        SentinelHooks.install(recorder);

        SentinelHooks.attack(PLAYER, 42);
        SentinelHooks.interactEntity(PLAYER, 7, 0.5, 1.5, 2.5, true);
        SentinelHooks.useItem(PLAYER, false);
        SentinelHooks.containerClick(PLAYER, 3, 11, 1, 2);

        assertEquals(List.of(
            "attack 42",
            "interact 7 0.5 1.5 2.5 secondary=true",
            "use againstBlock=false",
            "click container=3 slot=11 button=1 type=2"), recorder.seen);
    }

    @Test
    @DisplayName("une teleportation serveur s'annonce a l'armement et se clot a l'acquittement")
    void teleportIsFramedByTheForkNotInferred() {
        SentinelHooks.install(recorder);

        // L'ordre est la propriete utile : entre les deux, les positions que le client envoie
        // parlent encore de l'ancien endroit. Un check qui l'ignore signale chaque teleport.
        SentinelHooks.teleportExpected(PLAYER, 128.5, 70.0, -64.5);
        SentinelHooks.teleportAcknowledged(PLAYER);

        assertEquals(List.of("teleport attendu 128.5 70.0 -64.5", "teleport acquitte"),
            recorder.seen);
    }

    @Test
    @DisplayName("an observer that throws is dropped, and the hooks keep returning normally")
    void athrowingObserverIsDisarmedRatherThanPropagated() {
        SentinelHooks.install(new SessionObserverStub() {
            @Override
            public void onAttack(final UUID player, final int targetEntityId) {
                throw new IllegalStateException("a check with a bug");
            }
        });

        // Must not throw: the packet handler that called this is mid-tick on a live session.
        SentinelHooks.attack(PLAYER, 42);

        assertFalse(SentinelHooks.observing(),
            "a check that throws once will throw on the next thousand packets; the seam drops it");

        // And the seam stays usable — the next call is simply inert.
        SentinelHooks.attack(PLAYER, 43);
    }

    @Test
    @DisplayName("re-arming is explicit, and a stale handle cannot uninstall the new observer")
    void reinstallIsExplicitAndHandlesAreScoped() throws Exception {
        final AutoCloseable first = SentinelHooks.install(recorder);
        final RecordingObserver second = new RecordingObserver();
        SentinelHooks.install(second);

        first.close();

        assertTrue(SentinelHooks.observing(),
            "closing the handle of a replaced observer must not disarm the one in place");

        SentinelHooks.attack(PLAYER, 1);
        assertEquals(List.of("attack 1"), second.seen);
        assertEquals(List.of(), recorder.seen);
    }

    @Test
    @DisplayName("installing nothing is refused: uninstalling has its own name")
    void installRefusesNull() {
        assertThrows(IllegalArgumentException.class, () -> SentinelHooks.install(null));
    }

    /** Records what each hook delivered, in order. */
    private static final class RecordingObserver extends SessionObserverStub {
        private final List<String> seen = new ArrayList<>();

        @Override
        public void onMove(final UUID player, final double x, final double y, final double z,
                           final float yRot, final float xRot, final boolean onGround,
                           final boolean hasPosition, final boolean hasRotation) {
            seen.add("move " + x + " " + y + " " + z + " " + yRot + " " + xRot
                + " ground=" + onGround + " pos=" + hasPosition + " rot=" + hasRotation);
        }

        @Override
        public void onAttack(final UUID player, final int targetEntityId) {
            seen.add("attack " + targetEntityId);
        }

        @Override
        public void onInteractEntity(final UUID player, final int targetEntityId, final double hitX,
                                     final double hitY, final double hitZ,
                                     final boolean secondaryAction) {
            seen.add("interact " + targetEntityId + " " + hitX + " " + hitY + " " + hitZ
                + " secondary=" + secondaryAction);
        }

        @Override
        public void onUseItem(final UUID player, final boolean againstBlock) {
            seen.add("use againstBlock=" + againstBlock);
        }

        @Override
        public void onContainerClick(final UUID player, final int containerId, final int slot,
                                     final int button, final int clickTypeOrdinal) {
            seen.add("click container=" + containerId + " slot=" + slot + " button=" + button
                + " type=" + clickTypeOrdinal);
        }

        @Override
        public void onTeleportExpected(final UUID player, final double x, final double y,
                                       final double z) {
            seen.add("teleport attendu " + x + " " + y + " " + z);
        }

        @Override
        public void onTeleportAcknowledged(final UUID player) {
            seen.add("teleport acquitte");
        }
    }

    /** Does nothing, so each test overrides only the hook it is about. */
    private static class SessionObserverStub implements SentinelHooks.SessionObserver {
        @Override
        public void onMove(final UUID player, final double x, final double y, final double z,
                           final float yRot, final float xRot, final boolean onGround,
                           final boolean hasPosition, final boolean hasRotation) {
        }

        @Override
        public void onAttack(final UUID player, final int targetEntityId) {
        }

        @Override
        public void onInteractEntity(final UUID player, final int targetEntityId, final double hitX,
                                     final double hitY, final double hitZ,
                                     final boolean secondaryAction) {
        }

        @Override
        public void onUseItem(final UUID player, final boolean againstBlock) {
        }

        @Override
        public void onContainerClick(final UUID player, final int containerId, final int slot,
                                     final int button, final int clickTypeOrdinal) {
        }

        @Override
        public void onTeleportExpected(final UUID player, final double x, final double y,
                                       final double z) {
        }

        @Override
        public void onTeleportAcknowledged(final UUID player) {
        }
    }
}
