package dev.btc.core.slime;

import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.serialization.slime.SlimeSerializer;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Cloning a world that is not loaded ({@code SkeletonCloning#fullClone}): the sections of each chunk
 * are asked for once, and a clone saves the bytes of its source.
 */
class SlimeCloneSharingTest {

    private static final String DIRT = "minecraft:dirt";
    private static final String CLONE = "dungeon_instance_1";

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
        return source.clone(CLONE);
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
