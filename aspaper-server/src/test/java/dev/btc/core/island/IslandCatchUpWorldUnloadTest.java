package dev.btc.core.island;

import dev.btc.core.api.island.IslandKey;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A chunk or world part of a catch-up runs a tick or more after it was scheduled; the island may have
 * unloaded in between (smoke test of 13/11: "Chunk system has shut down" thrown by World#getChunkAt one
 * second after the unload). Such a part is given up quietly, without touching the world.
 */
class IslandCatchUpWorldUnloadTest {

    private static final Logger LOGGER = Logger.getLogger("BTCCore/IslandCatchUp");
    private static final IslandKey ISLAND = new IslandKey("island_1", UUID.randomUUID());

    private final List<LogRecord> records = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private Level previous;

    @BeforeEach
    void listen() {
        previous = LOGGER.getLevel();
        LOGGER.setLevel(Level.ALL);
        LOGGER.addHandler(capture);
    }

    @AfterEach
    void stopListening() {
        LOGGER.removeHandler(capture);
        LOGGER.setLevel(previous);
    }

    @Test
    @DisplayName("a world unloaded since the part was scheduled: given up, the world never asked for a chunk")
    void unloadedWorldIsGivenUpWithoutTouchingIt() {
        World world = mock(World.class);

        assertFalse(IslandCatchUpRegistry.worldStillLoaded(world, null, ISLAND, "chunk 0,0 resumed"));

        verifyNoInteractions(world);
    }

    @Test
    @DisplayName("a world reloaded under the same id is another world: the old one is given up")
    void reloadedWorldIsAnotherWorld() {
        World before = mock(World.class);
        World after = mock(World.class);

        assertFalse(IslandCatchUpRegistry.worldStillLoaded(before, after, ISLAND, "chunk 0,0 resumed"));

        verifyNoInteractions(before, after);
    }

    @Test
    @DisplayName("giving up is a refusal, not a failure: nothing at WARNING or above")
    void givingUpLogsNoError() {
        IslandCatchUpRegistry.worldStillLoaded(null, null, ISLAND, "chunk 3,4 resumed");

        assertEquals(1, records.size());
        assertEquals(Level.FINE, records.get(0).getLevel());
        assertTrue(records.get(0).getMessage().contains("world unloaded before chunk 3,4 resumed"), records.get(0).getMessage());
    }

    @Test
    @DisplayName("the world still loaded: the part goes on")
    void loadedWorldGoesOn() {
        World world = mock(World.class);

        assertTrue(IslandCatchUpRegistry.worldStillLoaded(world, world, ISLAND, "chunk 0,0 resumed"));
        assertTrue(records.isEmpty());
    }
}
