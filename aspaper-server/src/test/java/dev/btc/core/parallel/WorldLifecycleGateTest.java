package dev.btc.core.parallel;

import dev.btc.core.parallel.WorldLifecycleGate.Caller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may load or unload a world under {@code parallel-world-ticking}: tests T15 and T16 of the
 * port map ({@code conception-slime-sous-pwt.md}, 2.4).
 */
class WorldLifecycleGateTest {

    @Test
    @DisplayName("T15 - asked from the thread of a world, or from any thread but the main one: refused before anything runs, with the operation and the scheduler to use")
    void lifecycle_request_from_world_thread_is_rejected_with_guidance() {
        AtomicBoolean ran = new AtomicBoolean();

        IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
            WorldLifecycleGate.run(true, Caller.WORLD_THREAD, "unload world dungeon_1", () -> ran.getAndSet(true)));

        assertFalse(ran.get());
        assertTrue(refused.getMessage().contains("unload world dungeon_1"), refused.getMessage());
        assertTrue(refused.getMessage().contains("the thread of a world"), refused.getMessage());
        assertTrue(refused.getMessage().contains("getGlobalRegionScheduler"), refused.getMessage());

        IllegalStateException offMain = assertThrows(IllegalStateException.class, () ->
            WorldLifecycleGate.run(true, Caller.OTHER, "load world x", () -> ran.getAndSet(true)));
        assertFalse(ran.get());
        assertTrue(offMain.getMessage().contains("load world x"), offMain.getMessage());
        assertFalse(offMain.getMessage().contains("the thread of a world"), offMain.getMessage());
    }

    @Test
    @DisplayName("T16 - on the main thread it runs at once on the calling thread and hands its result back; with the option off every thread goes through")
    void lifecycle_on_main_outside_phase_runs_immediately() {
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        String result = WorldLifecycleGate.run(true, Caller.MAIN, "load world x", () -> {
            ranOn.set(Thread.currentThread());
            return "loaded";
        });

        assertEquals("loaded", result);
        assertSame(Thread.currentThread(), ranOn.get()); // in line, no queue
        assertEquals("loaded", WorldLifecycleGate.run(false, Caller.WORLD_THREAD, "load world x", () -> "loaded"));
        assertEquals("loaded", WorldLifecycleGate.run(false, Caller.OTHER, "load world x", () -> "loaded"));
    }
}
