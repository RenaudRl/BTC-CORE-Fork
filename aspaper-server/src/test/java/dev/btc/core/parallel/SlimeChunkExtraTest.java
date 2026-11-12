package dev.btc.core.parallel;

import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import com.infernalsuite.asp.level.SlimeInMemoryWorld;
import com.infernalsuite.asp.level.chunk.NMSSlimeChunk;
import com.infernalsuite.asp.skeleton.SkeletonCloning;
import com.infernalsuite.asp.skeleton.SkeletonSlimeWorld;
import com.infernalsuite.asp.skeleton.SlimeChunkSkeleton;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

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

    private static final long POSITION = 7L;
    private static final long PRUNED_POSITION = 8L;

    @Test
    @DisplayName("the save copy of an unloaded chunk carries its extra data as it was: a write made while the save is serialized is not in it")
    void save_copy_of_a_stored_chunk_does_not_see_a_later_write() {
        SlimeChunk stored = stored(new HashMap<>(Map.of("a", BLOCKS)));
        Long2ObjectMap<SlimeChunk> storage = new Long2ObjectOpenHashMap<>(Map.of(POSITION, stored));

        Long2ObjectMap<SlimeChunk> saved = SlimeInMemoryWorld.savedChunks(storage, loaded -> fail("no chunk is loaded"));
        stored.getExtraData().put("craftengine", BLOCKS); // the plugin goes on writing the chunk the world hands out

        assertEquals(Map.of("a", BLOCKS), saved.get(POSITION).getExtraData());
        assertSame(BLOCKS, storage.get(POSITION).getExtraData().get("craftengine")); // and the world keeps what was written
    }

    @Test
    @DisplayName("the save copy takes a loaded chunk as its serialization gives it, and leaves a pruned one out")
    void save_copy_serializes_loaded_chunks_and_leaves_pruned_ones_out() {
        NMSSlimeChunk kept = new NMSSlimeChunk(null, stored(new HashMap<>()));
        NMSSlimeChunk pruned = new NMSSlimeChunk(null, stored(new HashMap<>()));
        SlimeChunk serialized = stored(new HashMap<>());
        Long2ObjectMap<SlimeChunk> storage = new Long2ObjectOpenHashMap<>(Map.of(POSITION, kept, PRUNED_POSITION, pruned));

        Long2ObjectMap<SlimeChunk> saved = SlimeInMemoryWorld.savedChunks(storage, loaded -> loaded == kept ? serialized : null);

        assertSame(serialized, saved.get(POSITION));
        assertFalse(saved.containsKey(PRUNED_POSITION));
    }

    @Test
    @DisplayName("a world cloned from a model has its own extra data, world and chunks: what the clone writes is not in the model")
    void full_clone_does_not_share_extra_data_with_its_model() {
        SlimeChunk modelChunk = new SlimeChunkSkeleton(0, 0, new SlimeChunkSection[0], null, List.of(), List.of(),
            new HashMap<>(Map.of("a", BLOCKS)), null, null, null, null);
        SlimeWorld model = new SkeletonSlimeWorld("model", null, true, new Long2ObjectOpenHashMap<>(Map.of(0L, modelChunk)),
            new ConcurrentHashMap<>(Map.of("a", BLOCKS)), new SlimePropertyMap(), 0);

        SlimeWorld island = SkeletonCloning.fullClone("island", model, null, false);
        island.getChunk(0, 0).getExtraData().put("craftengine", BLOCKS);
        island.getExtraData().put("craftengine", BLOCKS);

        assertEquals(Map.of("a", BLOCKS), modelChunk.getExtraData());
        assertEquals(Map.of("a", BLOCKS), model.getExtraData());
        assertSame(BLOCKS, island.getChunk(0, 0).getExtraData().get("a")); // the clone starts from what the model holds
    }
}
