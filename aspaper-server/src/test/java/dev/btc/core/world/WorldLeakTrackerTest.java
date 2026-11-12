package dev.btc.core.world;

import dev.btc.core.world.WorldLeakTracker.Retained;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The unloaded worlds still in memory ({@code world.leak-diagnostic}). */
class WorldLeakTrackerTest {

    private static final long GRACE = 300;
    private static final long UNLOADED_AT = 1_000;
    private static final long COLLECTIONS_THEN = 5;

    @Test
    @DisplayName("a world still in memory is reported once the grace has passed, once, with its age and the collections run since its unload")
    void a_retained_world_is_reported_once_after_the_grace() {
        WorldLeakTracker tracker = new WorldLeakTracker();
        Object level = new Object(); // held: the world something still references
        tracker.unloaded("dungeon_1", level, UNLOADED_AT, COLLECTIONS_THEN);

        assertEquals(List.of(), tracker.overdue(UNLOADED_AT + GRACE - 1, GRACE, COLLECTIONS_THEN + 1), "too early to tell");
        assertEquals(List.of(new Retained("dungeon_1", GRACE, 2)), tracker.overdue(UNLOADED_AT + GRACE, GRACE, COLLECTIONS_THEN + 2));
        assertEquals(List.of(), tracker.overdue(UNLOADED_AT + 2 * GRACE, GRACE, COLLECTIONS_THEN + 9), "said once");
        assertEquals(List.of(new Retained("dungeon_1", 2 * GRACE, 9)), tracker.retained(UNLOADED_AT + 2 * GRACE, COLLECTIONS_THEN + 9),
            "the command still lists it");
    }

    @Test
    @DisplayName("a world the collector took is never reported and is no longer followed")
    void a_collected_world_is_forgotten() {
        WorldLeakTracker tracker = new WorldLeakTracker();
        Object kept = new Object();
        Reference<Object> collected = new WeakReference<>(new Object());
        tracker.follow("gone", collected, UNLOADED_AT, COLLECTIONS_THEN);
        tracker.unloaded("kept", kept, UNLOADED_AT, COLLECTIONS_THEN);
        collected.clear(); // what a collection does

        assertEquals(List.of(new Retained("kept", GRACE, 1)), tracker.overdue(UNLOADED_AT + GRACE, GRACE, COLLECTIONS_THEN + 1));
        assertEquals(List.of(new Retained("kept", GRACE, 1)), tracker.retained(UNLOADED_AT + GRACE, COLLECTIONS_THEN + 1));
    }

    @Test
    @DisplayName("past the cap the oldest unload is no longer followed: the diagnostic itself keeps a bounded list")
    void the_list_is_bounded() {
        WorldLeakTracker tracker = new WorldLeakTracker();
        Object level = new Object();
        for (int world = 0; world <= WorldLeakTracker.MAX_TRACKED; world++) {
            tracker.unloaded("world_" + world, level, UNLOADED_AT, COLLECTIONS_THEN);
        }

        List<Retained> retained = tracker.retained(UNLOADED_AT, COLLECTIONS_THEN);
        assertEquals(WorldLeakTracker.MAX_TRACKED, retained.size());
        assertEquals("world_1", retained.getFirst().world());
    }
}
