package dev.btc.core.slime;

import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import com.infernalsuite.asp.serialization.slime.SlimeSerializer;
import com.infernalsuite.asp.skeleton.SkeletonSlimeWorld;
import com.infernalsuite.asp.skeleton.SlimeChunkSkeleton;
import com.infernalsuite.asp.util.Util;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

/**
 * {@code slime-world.save.reuse-encoded-sections}: a save that writes again what an earlier save
 * encoded gives the file a save that encodes everything gives, byte for byte, whatever was
 * written to the world in between.
 */
class SlimeEncodedSectionReuseTest {

    private static final String DIRT = "minecraft:dirt";
    private static final String DESERT = "minecraft:desert";

    private static byte[] save(SlimeWorld world, boolean reuse) {
        SlimeSerializer.reuseEncodedSections = reuse;
        try {
            return SlimeSerializer.serialize(world);
        } finally {
            SlimeSerializer.reuseEncodedSections = false;
        }
    }

    /** The file of {@code world}, which a save with the reuse and a save without must agree on. */
    private static byte[] sameFileEitherWay(SlimeWorld world, String after) {
        byte[] encodedAnew = save(world, false);
        assertArrayEquals(encodedAnew, save(world, false), "two saves without the reuse differ: the comparison below would mean nothing");
        assertArrayEquals(encodedAnew, save(world, true), "with the reuse, after " + after);
        assertArrayEquals(encodedAnew, save(world, true), "with the reuse a second time, after " + after);
        return encodedAnew;
    }

    @Test
    @DisplayName("a world saved with the reuse is the file a plain save writes, the first time and every time after")
    void sameFileWithAndWithoutReuse() {
        SlimeWorld island = world(keepEverything(),
            chunk(-1, 2).sections(
                    section(blockStates(STONE, DIRT), biomes(PLAINS, DESERT), light(3), light(5)),
                    section(blockStates(DIRT, STONE, "minecraft:oak_log"), biomes(DESERT), null, light(7)),
                    stoneSection())
                .heightMaps(CompoundBinaryTag.builder().putLongArray("MOTION_BLOCKING", new long[]{1L, -2L}).build())
                .blockEntities(blockEntity("minecraft:chest", -16, 64, 32))
                .entities(entity("minecraft:cow", 10.0))
                .poi(CompoundBinaryTag.builder().putBoolean("Valid", true).build())
                .blockTicks(ticks("minecraft:repeater", 4))
                .fluidTicks(ticks("minecraft:water", 9))
                .build(),
            chunk(0, 0).sections(stoneSection(), section(blockStates(STONE, DIRT), biomes(PLAINS), light(11), null)).build(),
            chunk(5, -9).sections().blockEntities(blockEntity("minecraft:sign", 80, 64, -144)).build());
        sameFileEitherWay(island, "nothing");

        SlimeWorld pruned = world(new SlimePropertyMap(),
            chunk(0, 0).sections(section(blockStates(SlimeFixtures.AIR), biomes(PLAINS), null, null)).build(),
            chunk(1, 0).sections(section(blockStates(SlimeFixtures.AIR), biomes(PLAINS), null, null), stoneSection()).build());
        sameFileEitherWay(pruned, "nothing, with aggressive pruning");
    }

    @Test
    @DisplayName("a write of each kind made after a save is in the next save, with the reuse as without")
    void everyKindOfWriteIsSaved() {
        SlimeChunkSection[] sections = {
            section(blockStates(STONE, DIRT), biomes(PLAINS, DESERT), light(3), light(5)),
            section(blockStates(DIRT, STONE), biomes(PLAINS), light(7), light(11)),
        };
        List<CompoundBinaryTag> blockEntities = new ArrayList<>(List.of(blockEntity("minecraft:chest", 0, 64, 0)));
        List<CompoundBinaryTag> entities = new ArrayList<>(List.of(entity("minecraft:cow", 10.0)));
        Map<String, BinaryTag> extra = new ConcurrentHashMap<>();
        StoredChunk stored = new StoredChunk(sections, blockEntities, entities, extra);
        byte[] file = sameFileEitherWay(stored.world(), "the first save");

        // Each step is the smallest write of its kind; the objects the save kept bytes for stay in place around it.
        sections[0] = section(blockStates(STONE, DIRT, "minecraft:oak_log"), sections[0].getBiomeTag(), sections[0].getBlockLight(), sections[0].getSkyLight());
        file = written(file, stored, "block states of a section replaced");
        sections[1] = section(sections[1].getBlockStatesTag(), biomes(DESERT, PLAINS), sections[1].getBlockLight(), sections[1].getSkyLight());
        file = written(file, stored, "biomes of a section replaced");
        sections[0].getBlockLight().getBacking()[17] ^= 0x7F;
        file = written(file, stored, "block light written in place");
        sections[1].getSkyLight().getBacking()[2047] ^= 0x7F;
        file = written(file, stored, "sky light written in place");
        sections[1] = section(sections[1].getBlockStatesTag(), sections[1].getBiomeTag(), light(13), null);
        file = written(file, stored, "light arrays replaced, one of them gone");
        stored.heightMaps = CompoundBinaryTag.builder().putLongArray("WORLD_SURFACE", new long[]{9L}).build();
        file = written(file, stored, "heightmaps");
        blockEntities.add(blockEntity("minecraft:hopper", 0, 63, 0));
        file = written(file, stored, "a block entity added in place");
        entities.set(0, entity("minecraft:cow", 4.0));
        file = written(file, stored, "an entity changed in place");
        extra.put("craftengine", IntBinaryTag.intBinaryTag(42));
        file = written(file, stored, "extra data put in place");
        extra.put("ChunkBukkitValues", CompoundBinaryTag.builder().putInt("btc:owner", 7).build());
        file = written(file, stored, "a persistent data value of the chunk");
        stored.poi = CompoundBinaryTag.builder().putBoolean("Valid", true).build();
        file = written(file, stored, "POI");
        stored.blockTicks = ticks("minecraft:repeater", 4);
        file = written(file, stored, "a scheduled block tick");
        stored.fluidTicks = ticks("minecraft:water", 9);
        written(file, stored, "a scheduled fluid tick");
    }

    private static byte[] written(byte[] before, StoredChunk stored, String write) {
        byte[] after = sameFileEitherWay(stored.world(), write);
        assertFalse(Arrays.equals(before, after), write + ": the file did not change, the step tests nothing");
        return after;
    }

    @Test
    @DisplayName("a world read back from its file saves the same either way: no byte of the file read is reused")
    void aWorldReadBackSavesTheSameEitherWay() throws Exception {
        SlimeWorld island = world(keepEverything(),
            chunk(2, 3).sections(section(blockStates(STONE, DIRT), biomes(PLAINS, DESERT), light(3), light(5)), stoneSection())
                .blockEntities(blockEntity("minecraft:chest", 32, 64, 48))
                .entities(entity("minecraft:cow", 10.0))
                .build());
        // The file of the world read back is not the file read, byte for byte (key order): only what this JVM encoded may be kept.
        sameFileEitherWay(SlimeFixtures.read(save(island, false)), "a read");
    }

    @Test
    @DisplayName("what the world saves next to the sections may change between two saves: POI, ticks, pruning, data version")
    void worldSettingsMayChangeBetweenSaves() {
        SlimeChunk[] chunks = {
            chunk(0, 0).sections(section(blockStates(STONE, DIRT), biomes(PLAINS, DESERT), light(3), light(5)), stoneSection())
                .poi(CompoundBinaryTag.builder().putBoolean("Valid", true).build())
                .blockTicks(ticks("minecraft:repeater", 4))
                .fluidTicks(ticks("minecraft:water", 9))
                .build(),
            chunk(1, 0).sections(section(blockStates(SlimeFixtures.AIR), biomes(DESERT), null, null)).build(),
        };
        byte[] everything = sameFileEitherWay(world(keepEverything(), chunks), "the first save");

        byte[] optedOut = sameFileEitherWay(world(new SlimePropertyMap(), chunks), "POI and ticks no longer saved, air pruned");
        assertFalse(Arrays.equals(everything, optedOut), "the settings changed nothing in the file");

        Long2ObjectMap<SlimeChunk> storage = new Long2ObjectOpenHashMap<>();
        for (SlimeChunk stored : chunks) storage.put(Util.chunkPosition(stored.getX(), stored.getZ()), stored);
        SlimeWorld upgraded = new SkeletonSlimeWorld(SlimeFixtures.WORLD, null, false, storage, new ConcurrentHashMap<>(),
            keepEverything(), SlimeFixtures.DATA_VERSION + 1);
        byte[] newer = sameFileEitherWay(upgraded, "another data version");
        assertFalse(Arrays.equals(everything, newer), "the data version changed nothing in the file");
    }

    @Test
    @DisplayName("with the reuse a chunk is asked for its sections once a save, and the file is the same")
    void sectionsOfAChunkAreEncodedOnceASave() {
        // No block entity, no entity, aggressive pruning: the pruning test reads the sections, as it does for a loaded chunk of terrain.
        SlimeChunk terrain = chunk(0, 0).sections(stoneSection(), section(blockStates(STONE, DIRT), biomes(PLAINS), light(3), null)).build();
        AtomicInteger asked = new AtomicInteger();
        SlimeWorld world = world(new SlimePropertyMap(), new CountingChunk(terrain, asked));

        byte[] encodedAnew = save(world, false);
        asked.set(0);
        assertArrayEquals(encodedAnew, save(world, true));
        assertEquals(1, asked.get(), "times the chunk built its sections during one save");
    }

    /** A chunk as the world stores it once unloaded, rebuilt around the same objects at each save. */
    private static final class StoredChunk {
        private final SlimeChunkSection[] sections;
        private final List<CompoundBinaryTag> blockEntities;
        private final List<CompoundBinaryTag> entities;
        private final Map<String, BinaryTag> extra;
        private CompoundBinaryTag heightMaps = CompoundBinaryTag.empty();
        private CompoundBinaryTag poi;
        private ListBinaryTag blockTicks;
        private ListBinaryTag fluidTicks;

        private StoredChunk(SlimeChunkSection[] sections, List<CompoundBinaryTag> blockEntities, List<CompoundBinaryTag> entities, Map<String, BinaryTag> extra) {
            this.sections = sections;
            this.blockEntities = blockEntities;
            this.entities = entities;
            this.extra = extra;
        }

        private SlimeWorld world() {
            return SlimeFixtures.world(keepEverything(), new SlimeChunkSkeleton(3, -4, this.sections, this.heightMaps, this.blockEntities,
                this.entities, this.extra, null, this.poi, this.blockTicks, this.fluidTicks));
        }
    }

    /** A chunk that builds its sections when asked, as a loaded chunk does, and counts how often. */
    private record CountingChunk(SlimeChunk chunk, AtomicInteger asked) implements SlimeChunk {
        @Override
        public int getX() {
            return this.chunk.getX();
        }

        @Override
        public int getZ() {
            return this.chunk.getZ();
        }

        @Override
        public SlimeChunkSection[] getSections() {
            this.asked.incrementAndGet();
            return this.chunk.getSections();
        }

        @Override
        public CompoundBinaryTag getHeightMaps() {
            return this.chunk.getHeightMaps();
        }

        @Override
        public List<CompoundBinaryTag> getTileEntities() {
            return this.chunk.getTileEntities();
        }

        @Override
        public List<CompoundBinaryTag> getEntities() {
            return this.chunk.getEntities();
        }

        @Override
        public Map<String, BinaryTag> getExtraData() {
            return this.chunk.getExtraData();
        }

        @Override
        public CompoundBinaryTag getUpgradeData() {
            return this.chunk.getUpgradeData();
        }

        @Override
        public ListBinaryTag getBlockTicks() {
            return this.chunk.getBlockTicks();
        }

        @Override
        public ListBinaryTag getFluidTicks() {
            return this.chunk.getFluidTicks();
        }

        @Override
        public CompoundBinaryTag getPoiChunkSections() {
            return this.chunk.getPoiChunkSections();
        }
    }
}
