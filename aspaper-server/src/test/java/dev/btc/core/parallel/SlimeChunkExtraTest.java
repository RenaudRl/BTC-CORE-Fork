package dev.btc.core.parallel;

import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.level.chunk.NMSSlimeChunk;
import com.infernalsuite.asp.skeleton.SlimeChunkSkeleton;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The extra data of a Slime chunk, written by plugins that got the chunk from the world (CraftEngine
 * keeps its custom blocks there) while the chunk goes from stored to loaded and back.
 */
class SlimeChunkExtraTest {

    private static final BinaryTag BLOCKS = StringBinaryTag.stringBinaryTag("custom blocks");

    private static SlimeChunk stored(Map<String, BinaryTag> extra) {
        return new SlimeChunkSkeleton(0, 0, null, null, null, null, extra, null, null, null, null);
    }

    @Test
    @DisplayName("what a plugin writes in the stored chunk after the loading chunk was built, before it is promoted, is in the loaded chunk")
    void write_between_load_and_promotion_reaches_the_loaded_chunk() {
        SlimeChunk stored = stored(new HashMap<>(Map.of("ChunkBukkitValues", StringBinaryTag.stringBinaryTag("pdc"))));
        NMSSlimeChunk loading = new NMSSlimeChunk(null, stored); // built when the chunk is created, promoted at its load callback

        stored.getExtraData().put("craftengine", BLOCKS); // the world still hands out the stored chunk

        assertSame(BLOCKS, loading.getExtraData().get("craftengine"));
    }

    @Test
    @DisplayName("the chunk stored at unload carries the map of the loaded one: a write through either is in both")
    void unload_keeps_the_same_map() {
        NMSSlimeChunk loaded = new NMSSlimeChunk(null, stored(new HashMap<>()));
        SlimeChunk unloaded = stored(loaded.getExtraData());

        loaded.getExtraData().put("craftengine", BLOCKS); // a writer that still holds the chunk it got while it was loaded

        assertSame(BLOCKS, unloaded.getExtraData().get("craftengine"));
    }

    @Test
    @DisplayName("a save walking the extra data of a stored chunk is not broken by a write made meanwhile")
    void stored_extra_can_be_walked_while_written() {
        SlimeChunk stored = stored(new HashMap<>(Map.of("a", BLOCKS, "b", BLOCKS)));
        Iterator<Map.Entry<String, BinaryTag>> save = stored.getExtraData().entrySet().iterator();
        save.next();

        stored.getExtraData().put("craftengine", BLOCKS);

        assertDoesNotThrow(() -> save.forEachRemaining(entry -> { })); // a HashMap throws ConcurrentModificationException here
        assertEquals(3, stored.getExtraData().size());
    }
}
