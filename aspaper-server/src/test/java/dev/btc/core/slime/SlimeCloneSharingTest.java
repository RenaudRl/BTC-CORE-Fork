package dev.btc.core.slime;

import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.serialization.slime.SlimeSerializer;
import com.infernalsuite.asp.skeleton.SkeletonCloning;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static dev.btc.core.slime.SlimeFixtures.PLAINS;
import static dev.btc.core.slime.SlimeFixtures.STONE;
import static dev.btc.core.slime.SlimeFixtures.biomes;
import static dev.btc.core.slime.SlimeFixtures.blockEntity;
import static dev.btc.core.slime.SlimeFixtures.blockStates;
import static dev.btc.core.slime.SlimeFixtures.chunk;
import static dev.btc.core.slime.SlimeFixtures.entity;
import static dev.btc.core.slime.SlimeFixtures.keepEverything;
import static dev.btc.core.slime.SlimeFixtures.light;
import static dev.btc.core.slime.SlimeFixtures.section;
import static dev.btc.core.slime.SlimeFixtures.stoneSection;
import static dev.btc.core.slime.SlimeFixtures.ticks;
import static dev.btc.core.slime.SlimeFixtures.world;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Cloning a world that is not loaded ({@code SkeletonCloning#fullClone}): the sections of each chunk
 * are asked for once, and {@code slime-world.clone.share-sections} keeps the stored sections of the
 * source without anything a clone writes reaching the source or the bytes a save writes.
 */
class SlimeCloneSharingTest {

    private static final String DIRT = "minecraft:dirt";
    private static final String CLONE = "dungeon_instance_1";

    @AfterEach
    void restore() {
        SkeletonCloning.shareSections = false;
        SlimeSerializer.reuseEncodedSections = false;
    }

    /** Two chunks with light on every section, block entities, entities, extra data, POI and ticks. */
    private static SlimeWorld template() {
        SlimeWorld world = world(keepEverything(),
            chunk(0, 0).sections(
                    section(blockStates(STONE, DIRT), biomes(PLAINS), light(3), light(5)),
                    section(blockStates(STONE), biomes(PLAINS), light(7), light(9)))
                .blockEntities(blockEntity("minecraft:chest", 0, 64, 0))
                .entities(entity("minecraft:zombie", 20.0))
                .extra(new java.util.concurrent.ConcurrentHashMap<>(java.util.Map.of("craftengine", IntBinaryTag.intBinaryTag(1))))
                .poi(CompoundBinaryTag.builder().putBoolean("Valid", true).build())
                .blockTicks(ticks("minecraft:repeater", 4))
                .build(),
            chunk(1, -1).sections(stoneSection(), section(blockStates(DIRT, STONE), biomes(PLAINS), light(11), null)).build());
        world.getExtraData().put("btc:template", IntBinaryTag.intBinaryTag(7));
        return world;
    }

    private static SlimeWorld cloneOf(SlimeWorld source, boolean share) {
        SkeletonCloning.shareSections = share;
        try {
            return source.clone(CLONE);
        } finally {
            SkeletonCloning.shareSections = false;
        }
    }

    // ==================== without the option ====================

    @Test
    @DisplayName("a clone asks each chunk for its sections once: a loaded-then-stored chunk encodes them all on each call")
    void cloneOfPartiallySerializedChunkCallsGetSectionsOnce() {
        SlimeChunk terrain = chunk(0, 0).sections(stoneSection(), section(blockStates(STONE, DIRT), biomes(PLAINS), light(3), light(5)), stoneSection()).build();
        AtomicInteger asked = new AtomicInteger();

        world(keepEverything(), new SlimeFixtures.CountingChunk(terrain, asked)).clone(CLONE);

        assertEquals(1, asked.get(), "times the chunk built its sections during one clone");
    }

    @Test
    @DisplayName("a clone saves the very bytes of its source")
    void cloneBytesEqualSourceBytes() {
        SlimeWorld source = template();
        assertArrayEquals(SlimeSerializer.serialize(source), SlimeSerializer.serialize(cloneOf(source, false)));
    }

    @Test
    @DisplayName("with the option off a clone copies the light: other arrays, same content")
    void cloneCopiesLightWhenDisabled() {
        SlimeWorld source = template();
        SlimeWorld clone = cloneOf(source, false);
        eachSection(source, clone, (original, copy) -> {
            assertNotSame(original, copy);
            if (original.getBlockLight() != null) {
                assertNotSame(original.getBlockLight(), copy.getBlockLight());
                assertArrayEquals(original.getBlockLight().getBacking(), copy.getBlockLight().getBacking());
            }
            if (original.getSkyLight() != null) {
                assertNotSame(original.getSkyLight(), copy.getSkyLight());
                assertArrayEquals(original.getSkyLight().getBacking(), copy.getSkyLight().getBacking());
            }
        });
    }

    // ==================== slime-world.clone.share-sections ====================

    @Test
    @DisplayName("with the option on a clone holds the very section objects of its source")
    void cloneSharesSectionObjectsWhenEnabled() {
        SlimeWorld source = template();
        SlimeWorld clone = cloneOf(source, true);
        eachSection(source, clone, (original, copy) -> assertSame(original, copy));
    }

    @Test
    @DisplayName("with the option on the extra data and the entity lists of a clone are still its own")
    void cloneKeepsExtraAndListsPrivate() {
        SlimeWorld source = template();
        SlimeWorld clone = cloneOf(source, true);

        assertNotSame(source.getExtraData(), clone.getExtraData());
        for (SlimeChunk original : source.getChunkStorage()) {
            SlimeChunk copy = clone.getChunk(original.getX(), original.getZ());
            assertNotSame(original, copy);
            assertNotSame(original.getExtraData(), copy.getExtraData());
            assertNotSame(original.getTileEntities(), copy.getTileEntities());
            assertNotSame(original.getEntities(), copy.getEntities());
        }
        clone.getChunk(0, 0).getExtraData().put("btc:instance", IntBinaryTag.intBinaryTag(1));
        assertFalse(source.getChunk(0, 0).getExtraData().containsKey("btc:instance"));
    }

    @Test
    @DisplayName("with the option on, writing to a clone leaves the bytes of its source as they were")
    void templateBytesUnchangedAfterCloningAndEditingClone() {
        SlimeWorld source = template();
        byte[] before = SlimeSerializer.serialize(source);

        SlimeWorld clone = cloneOf(source, true);
        SlimeChunk chunk = clone.getChunk(0, 0);
        chunk.getExtraData().put("ChunkBukkitValues", CompoundBinaryTag.builder().putInt("btc:owner", 7).build());
        clone.getExtraData().put("btc:instance", IntBinaryTag.intBinaryTag(2));
        chunk.getTileEntities().add(blockEntity("minecraft:hopper", 0, 63, 0));
        chunk.getEntities().add(entity("minecraft:skeleton", 20.0));
        assertFalse(java.util.Arrays.equals(before, SlimeSerializer.serialize(clone)), "the clone did not change: the test proves nothing");

        assertArrayEquals(before, SlimeSerializer.serialize(source));
    }

    @Test
    @DisplayName("a clone sharing the sections saves what a clone copying them saves, byte for byte")
    void sharedCloneSavesSameBytesAsCopiedClone() {
        SlimeWorld source = template();
        assertArrayEquals(SlimeSerializer.serialize(cloneOf(source, false)), SlimeSerializer.serialize(cloneOf(source, true)));
    }

    @Test
    @DisplayName("the same with slime-world.save.reuse-encoded-sections: the bytes kept on a shared section are right for every clone")
    void sharedCloneSavesSameBytesWithReuseEncodedSections() {
        SlimeWorld source = template();
        byte[] copied = SlimeSerializer.serialize(cloneOf(source, false));
        SlimeSerializer.reuseEncodedSections = true;

        SlimeWorld first = cloneOf(source, true);
        assertArrayEquals(copied, SlimeSerializer.serialize(first));
        SlimeWorld second = cloneOf(source, true);
        assertArrayEquals(copied, SlimeSerializer.serialize(second), "a second clone, saved after the first kept its bytes");
        assertArrayEquals(copied, SlimeSerializer.serialize(source), "the source, saved after both");
    }

    private static void eachSection(SlimeWorld source, SlimeWorld clone, java.util.function.BiConsumer<SlimeChunkSection, SlimeChunkSection> check) {
        int compared = 0;
        for (SlimeChunk original : source.getChunkStorage()) {
            SlimeChunkSection[] originals = original.getSections();
            SlimeChunkSection[] copies = clone.getChunk(original.getX(), original.getZ()).getSections();
            assertEquals(originals.length, copies.length);
            for (int i = 0; i < originals.length; i++) {
                if (originals[i] == null) continue;
                check.accept(originals[i], copies[i]);
                compared++;
            }
        }
        assertEquals(4, compared, "sections compared");
    }
}
