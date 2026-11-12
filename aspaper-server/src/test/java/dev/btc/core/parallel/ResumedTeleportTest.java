package dev.btc.core.parallel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The teleport events of a journey a listener sent to another world are fired once, not again by the main thread. */
class ResumedTeleportTest {

    private static final Object PLAYER = "player";
    private static final Object PASSENGER = "passenger";

    @Test
    @DisplayName("a teleport that is not resumed fires its events")
    void teleport_outside_a_resume_fires_its_events() {
        assertFalse(new ResumedTeleport().take(PLAYER));
    }

    @Test
    @DisplayName("the teleport the main thread runs again skips its events, and a second teleport of the same entity made by the journey fires its own")
    void resumed_teleport_skips_its_events_once() {
        ResumedTeleport resumed = new ResumedTeleport();
        List<Boolean> skipped = new ArrayList<>();

        resumed.resume(PLAYER, () -> {
            skipped.add(resumed.take(PLAYER));
            skipped.add(resumed.take(PLAYER)); // what its post-teleport step asks
        });

        assertEquals(List.of(true, false), skipped);
    }

    @Test
    @DisplayName("the teleport of another entity made before the resumed one reaches its events fires its own, and leaves the resumed one its turn")
    void teleport_of_another_entity_fires_its_events() {
        ResumedTeleport resumed = new ResumedTeleport();
        List<Boolean> skipped = new ArrayList<>();

        resumed.resume(PLAYER, () -> {
            skipped.add(resumed.take(PASSENGER));
            skipped.add(resumed.take(PLAYER));
        });

        assertEquals(List.of(false, true), skipped);
    }

    @Test
    @DisplayName("a resumed teleport that gave up before its events, or threw, leaves nothing to the next teleport of that entity")
    void resume_that_gave_up_leaves_nothing() {
        ResumedTeleport resumed = new ResumedTeleport();

        resumed.resume(PLAYER, () -> { }); // refused: the target world left the server meanwhile
        assertFalse(resumed.take(PLAYER));

        assertThrows(IllegalStateException.class, () -> resumed.resume(PLAYER, () -> { throw new IllegalStateException("broken"); }));
        assertFalse(resumed.take(PLAYER));
    }
}
