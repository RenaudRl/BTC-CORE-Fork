package dev.btc.core.parallel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests T7 and T8 of the port map ({@code pwt-carte-conflits.md} §5). */
class WorldOwnershipTest {

    private static final Object WORLD_A = "a";
    private static final Object WORLD_B = "b";

    @Test
    @DisplayName("T7 - main thread: any world; world thread: its own world only; any other thread: none")
    void ownership_matrix() {
        // the main thread: a tick thread bound to no world
        assertTrue(WorldOwnership.ownsWorld(true, false, null, WORLD_A));
        assertTrue(WorldOwnership.ownsWorld(true, false, null, WORLD_B));
        assertTrue(WorldOwnership.isMainOnly(true, false));
        assertTrue(WorldOwnership.ownsWorldOrIsAsync(true, false, null, WORLD_A));

        // the thread of world A
        assertTrue(WorldOwnership.ownsWorld(true, true, WORLD_A, WORLD_A));
        assertFalse(WorldOwnership.ownsWorld(true, true, WORLD_A, WORLD_B));
        assertFalse(WorldOwnership.ownsWorld(true, true, null, WORLD_A), "a world thread between two ticks owns nothing");
        assertFalse(WorldOwnership.isMainOnly(true, true));
        assertTrue(WorldOwnership.ownsWorldOrIsAsync(true, true, WORLD_A, WORLD_A));
        assertFalse(WorldOwnership.ownsWorldOrIsAsync(true, true, WORLD_A, WORLD_B));

        // any other thread
        assertFalse(WorldOwnership.ownsWorld(false, false, null, WORLD_A));
        assertFalse(WorldOwnership.isMainOnly(false, false));
        assertTrue(WorldOwnership.ownsWorldOrIsAsync(false, false, null, WORLD_A));
    }

    @Test
    @DisplayName("saveWorld, which reports the outcome, is refused on the thread of another world with the scheduler to use - and nowhere else")
    void save_and_wait_is_refused_on_the_thread_of_another_world_only() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> WorldOwnership.ensureMaySaveAndWait(true, true, false, "dungeon_1"));
        assertTrue(refused.getMessage().contains("dungeon_1"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Bukkit.getGlobalRegionScheduler().execute"), refused.getMessage());

        assertDoesNotThrow(() -> WorldOwnership.ensureMaySaveAndWait(true, true, true, "dungeon_1"), "the thread of the world itself");
        assertDoesNotThrow(() -> WorldOwnership.ensureMaySaveAndWait(true, false, false, "dungeon_1"),
            "the main thread, or an async thread that hops to it");
        assertDoesNotThrow(() -> WorldOwnership.ensureMaySaveAndWait(false, true, false, "dungeon_1"), "option off: as before");
    }

    @Test
    @DisplayName("T8 - the guards throw unless the system property says exactly true")
    void hard_throw_can_only_be_disabled_by_system_property() {
        assertTrue(WorldOwnership.hardThrow(null), "property absent");
        assertTrue(WorldOwnership.hardThrow(""));
        assertTrue(WorldOwnership.hardThrow("false"));
        assertTrue(WorldOwnership.hardThrow("1"), "only the word true disables it");
        assertFalse(WorldOwnership.hardThrow("true"));
    }
}
