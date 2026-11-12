package dev.btc.core.parallel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HopperFlagsTest {

    @Test // T10
    void hopper_flags_are_per_world() {
        HopperFlags island = HopperFlags.forWorld(true);
        HopperFlags dungeon = HopperFlags.forWorld(true);

        island.skipHopperEvents = true;
        island.skipPushModeEventFire = true;
        dungeon.skipPullModeEventFire = true;

        assertFalse(dungeon.skipHopperEvents);
        assertFalse(dungeon.skipPushModeEventFire);
        assertFalse(island.skipPullModeEventFire);
        assertTrue(island.skipHopperEvents);
        assertFalse(HopperFlags.SHARED.skipHopperEvents);
    }

    @Test
    void without_parallel_ticking_every_world_has_the_same_flags_as_before() {
        assertSame(HopperFlags.SHARED, HopperFlags.forWorld(false));
        assertSame(HopperFlags.forWorld(false), HopperFlags.forWorld(false));
    }
}
