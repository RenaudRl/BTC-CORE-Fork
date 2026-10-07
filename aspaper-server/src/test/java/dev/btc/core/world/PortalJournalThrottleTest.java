package dev.btc.core.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An entity waiting in a portal raises the same event every few seconds. The throttle is what keeps
 * that from becoming a journal line each time, without ever hiding a different entity.
 */
class PortalJournalThrottleTest {

    private static final long W = PortalJournalThrottle.WINDOW_MILLIS;

    @Test
    @DisplayName("the first sighting of an entity is logged, a repeat inside the window is not")
    void repeatInsideWindowIsDropped() {
        PortalJournalThrottle throttle = new PortalJournalThrottle();
        UUID player = UUID.randomUUID();

        assertTrue(throttle.shouldLog(player, 1_000));
        assertFalse(throttle.shouldLog(player, 1_000 + 4_000), "four seconds later is the same wait");
        assertFalse(throttle.shouldLog(player, 1_000 + W - 1));
    }

    @Test
    @DisplayName("the window runs from the last logged line, so a long wait logs once per window")
    void windowReopens() {
        PortalJournalThrottle throttle = new PortalJournalThrottle();
        UUID player = UUID.randomUUID();

        assertTrue(throttle.shouldLog(player, 0));
        assertFalse(throttle.shouldLog(player, W - 1));
        assertTrue(throttle.shouldLog(player, W));
        assertFalse(throttle.shouldLog(player, W + 1), "a dropped call must not push the window");
    }

    @Test
    @DisplayName("another entity is never hidden by the first one")
    void entitiesAreIndependent() {
        PortalJournalThrottle throttle = new PortalJournalThrottle();

        assertTrue(throttle.shouldLog(UUID.randomUUID(), 0));
        assertTrue(throttle.shouldLog(UUID.randomUUID(), 1));
    }

    @Test
    @DisplayName("memory stays bounded and expired entries are reclaimed")
    void boundedMemory() {
        PortalJournalThrottle throttle = new PortalJournalThrottle();
        for (int i = 0; i < PortalJournalThrottle.MAX_TRACKED; i++) {
            throttle.shouldLog(UUID.randomUUID(), 0);
        }
        assertEquals(PortalJournalThrottle.MAX_TRACKED, throttle.tracked());

        // Full of live entries: an unknown entity is let through, unrecorded.
        assertTrue(throttle.shouldLog(UUID.randomUUID(), 1));
        assertEquals(PortalJournalThrottle.MAX_TRACKED, throttle.tracked());

        // Once the window has passed, the table empties itself and records again.
        assertTrue(throttle.shouldLog(UUID.randomUUID(), W));
        assertEquals(1, throttle.tracked());
    }
}
