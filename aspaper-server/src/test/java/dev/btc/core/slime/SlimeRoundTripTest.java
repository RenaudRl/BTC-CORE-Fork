package dev.btc.core.slime;

import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static dev.btc.core.slime.SlimeFixtures.AIR;
import static dev.btc.core.slime.SlimeFixtures.DATA_VERSION;
import static dev.btc.core.slime.SlimeFixtures.PLAINS;
import static dev.btc.core.slime.SlimeFixtures.STONE;
import static dev.btc.core.slime.SlimeFixtures.biomes;
import static dev.btc.core.slime.SlimeFixtures.blockEntity;
import static dev.btc.core.slime.SlimeFixtures.blockStates;
import static dev.btc.core.slime.SlimeFixtures.chunk;
import static dev.btc.core.slime.SlimeFixtures.entity;
import static dev.btc.core.slime.SlimeFixtures.keepEverything;
import static dev.btc.core.slime.SlimeFixtures.light;
import static dev.btc.core.slime.SlimeFixtures.saveAndRead;
import static dev.btc.core.slime.SlimeFixtures.section;
import static dev.btc.core.slime.SlimeFixtures.stoneSection;
import static dev.btc.core.slime.SlimeFixtures.ticks;
import static dev.btc.core.slime.SlimeFixtures.world;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a Slime world holds is what its file gives back (format v13), one kind of data per test. */
class SlimeRoundTripTest {

    private static final String DIRT = "minecraft:dirt";
    private static final String DESERT = "minecraft:desert";

    private static SlimeChunk onlyChunk(SlimeWorld world) {
        assertEquals(1, world.getChunkStorage().size(), "chunks in the world read back");
        return world.getChunkStorage().iterator().next();
    }

    private static SlimeChunk savedAlone(SlimeFixtures.ChunkBuilder chunk) throws Exception {
        return onlyChunk(saveAndRead(world(keepEverything(), chunk.build())));
    }

    @Test
    @DisplayName("block states come back for a one-entry palette, a palette of several and an empty section")
    void blockStatesComeBack() throws Exception {
        SlimeChunkSection[] sections = {
            section(blockStates(STONE), biomes(PLAINS), null, null),
            section(blockStates(STONE, DIRT, "minecraft:oak_log"), biomes(PLAINS), null, null),
            section(CompoundBinaryTag.empty(), biomes(PLAINS), null, null),
        };
        SlimeChunkSection[] read = savedAlone(chunk(0, 0).sections(sections)).getSections();
        assertEquals(sections.length, read.length);
        for (int i = 0; i < sections.length; i++) {
            assertEquals(sections[i].getBlockStatesTag(), read[i].getBlockStatesTag(), "section " + i);
        }
    }

    @Test
    @DisplayName("biomes come back for a one-entry palette, a palette of several and an empty section")
    void biomesComeBack() throws Exception {
        SlimeChunkSection[] sections = {
            section(blockStates(STONE), biomes(PLAINS), null, null),
            section(blockStates(STONE), biomes(PLAINS, DESERT, "minecraft:cherry_grove"), null, null),
            section(blockStates(STONE), CompoundBinaryTag.empty(), null, null),
        };
        SlimeChunkSection[] read = savedAlone(chunk(0, 0).sections(sections)).getSections();
        for (int i = 0; i < sections.length; i++) {
            assertEquals(sections[i].getBiomeTag(), read[i].getBiomeTag(), "section " + i);
        }
    }

    @Test
    @DisplayName("block light and sky light come back byte for byte, each in its own place")
    void lightComesBack() throws Exception {
        SlimeChunkSection[] sections = {
            section(blockStates(STONE), biomes(PLAINS), light(3), null),
            section(blockStates(STONE), biomes(PLAINS), null, light(5)),
            section(blockStates(STONE), biomes(PLAINS), light(7), light(11)),
        };
        SlimeChunkSection[] read = savedAlone(chunk(0, 0).sections(sections)).getSections();
        assertArrayEquals(sections[0].getBlockLight().getBacking(), read[0].getBlockLight().getBacking(), "block light alone");
        assertArrayEquals(sections[1].getSkyLight().getBacking(), read[1].getSkyLight().getBacking(), "sky light alone");
        assertArrayEquals(sections[2].getBlockLight().getBacking(), read[2].getBlockLight().getBacking(), "block light of both");
        assertArrayEquals(sections[2].getSkyLight().getBacking(), read[2].getSkyLight().getBacking(), "sky light of both");
    }

    @Test
    @DisplayName("a light that was absent stays absent: it is not read back as darkness")
    void absentLightStaysAbsent() throws Exception {
        SlimeChunkSection[] sections = {
            section(blockStates(STONE), biomes(PLAINS), null, null),
            section(blockStates(STONE), biomes(PLAINS), light(3), null),
            section(blockStates(STONE), biomes(PLAINS), null, light(5)),
        };
        SlimeChunkSection[] read = savedAlone(chunk(0, 0).sections(sections)).getSections();
        assertNull(read[0].getBlockLight(), "no light at all: block");
        assertNull(read[0].getSkyLight(), "no light at all: sky");
        assertNull(read[1].getSkyLight(), "block light alone: sky");
        assertNull(read[2].getBlockLight(), "sky light alone: block");
    }

    @Test
    @DisplayName("heightmaps come back")
    void heightMapsComeBack() throws Exception {
        CompoundBinaryTag heightMaps = CompoundBinaryTag.builder()
            .putLongArray("MOTION_BLOCKING", new long[]{1L, -2L, Long.MAX_VALUE})
            .putLongArray("WORLD_SURFACE", new long[]{7L})
            .build();
        assertEquals(heightMaps, savedAlone(chunk(0, 0).heightMaps(heightMaps)).getHeightMaps());
    }

    @Test
    @DisplayName("block entities come back, in their order")
    void blockEntitiesComeBack() throws Exception {
        CompoundBinaryTag chest = blockEntity("minecraft:chest", 3, 64, -5);
        CompoundBinaryTag hopper = blockEntity("minecraft:hopper", 3, 63, -5);
        assertEquals(List.of(chest, hopper), savedAlone(chunk(0, -1).blockEntities(chest, hopper)).getTileEntities());
    }

    @Test
    @DisplayName("entities come back, in their order")
    void entitiesComeBack() throws Exception {
        CompoundBinaryTag cow = entity("minecraft:cow", 10.0);
        CompoundBinaryTag zombie = entity("minecraft:zombie", 20.0);
        assertEquals(List.of(cow, zombie), savedAlone(chunk(0, 0).entities(cow, zombie)).getEntities());
    }

    @Test
    @DisplayName("the extra data of a chunk comes back, in a concurrent map that takes no null")
    void chunkExtraComesBackInAConcurrentMap() throws Exception {
        Map<String, BinaryTag> extra = new HashMap<>();
        extra.put("ChunkBukkitValues", CompoundBinaryTag.builder().putString("btc:owner", "renaud").build());
        extra.put("craftengine", IntBinaryTag.intBinaryTag(42));

        Map<String, BinaryTag> read = savedAlone(chunk(2, 2).extra(extra)).getExtraData();
        assertEquals(extra, read);
        assertInstanceOf(ConcurrentMap.class, read, "written by plugins while a save walks it");
        assertThrows(NullPointerException.class, () -> read.put("craftengine", null));
    }

    @Test
    @DisplayName("a chunk that holds no extra data at all is saved with none, the world is still written")
    void chunkWithoutExtraIsSaved() throws Exception {
        SlimeChunk read = savedAlone(chunk(0, 0).extra(null).blockEntities(blockEntity("minecraft:chest", 3, 64, 5)));
        assertTrue(read.getExtraData().isEmpty());
        assertEquals(1, read.getTileEntities().size());
    }

    @Test
    @DisplayName("POI sections come back when the world saves them")
    void poiComesBack() throws Exception {
        CompoundBinaryTag poi = CompoundBinaryTag.builder()
            .put("4", CompoundBinaryTag.builder().putBoolean("Valid", true).build())
            .build();
        assertEquals(poi, savedAlone(chunk(0, 0).poi(poi)).getPoiChunkSections());
    }

    @Test
    @DisplayName("scheduled block ticks come back when the world saves them")
    void blockTicksComeBack() throws Exception {
        ListBinaryTag blockTicks = ticks("minecraft:repeater", 4);
        SlimeChunk read = savedAlone(chunk(0, 0).blockTicks(blockTicks).fluidTicks(ticks("minecraft:water", 9)));
        assertEquals(blockTicks, read.getBlockTicks());
    }

    @Test
    @DisplayName("scheduled fluid ticks come back when the world saves them")
    void fluidTicksComeBack() throws Exception {
        ListBinaryTag fluidTicks = ticks("minecraft:water", 9);
        SlimeChunk read = savedAlone(chunk(0, 0).blockTicks(ticks("minecraft:repeater", 4)).fluidTicks(fluidTicks));
        assertEquals(fluidTicks, read.getFluidTicks());
    }

    @Test
    @DisplayName("POI and scheduled ticks are left out of the file unless the world asks for them")
    void poiAndTicksAreOptIn() throws Exception {
        SlimePropertyMap properties = new SlimePropertyMap();
        SlimeChunk chunk = chunk(0, 0)
            .poi(CompoundBinaryTag.builder().putBoolean("Valid", true).build())
            .blockTicks(ticks("minecraft:repeater", 4))
            .fluidTicks(ticks("minecraft:water", 9))
            .build();
        SlimeChunk read = onlyChunk(saveAndRead(world(properties, chunk)));
        assertNull(read.getPoiChunkSections(), "POI");
        assertNull(read.getBlockTicks(), "block ticks");
        assertNull(read.getFluidTicks(), "fluid ticks");
    }

    @Test
    @DisplayName("world properties come back")
    void worldPropertiesComeBack() throws Exception {
        SlimePropertyMap properties = keepEverything();
        properties.setValue(SlimeProperties.SPAWN_X, -120);
        properties.setValue(SlimeProperties.SPAWN_Y, 71);
        properties.setValue(SlimeProperties.DIFFICULTY, "hard");
        properties.setValue(SlimeProperties.ENVIRONMENT, "nether");
        properties.setValue(SlimeProperties.DEFAULT_BIOME, DESERT);
        properties.setValue(SlimeProperties.PVP, false);

        SlimePropertyMap read = saveAndRead(world(properties, chunk(0, 0).build())).getPropertyMap();
        assertEquals(properties.getProperties(), read.getProperties());
    }

    @Test
    @DisplayName("the data version of the world comes back")
    void dataVersionComesBack() throws Exception {
        assertEquals(DATA_VERSION, saveAndRead(world(keepEverything(), chunk(0, 0).build())).getDataVersion());
    }

    @Test
    @DisplayName("the extra data of the world comes back, next to its properties")
    void worldExtraComesBack() throws Exception {
        SlimeWorld world = world(keepEverything(), chunk(0, 0).build());
        CompoundBinaryTag bukkitValues = CompoundBinaryTag.builder().putString("btc:island", "0001").build();
        world.getExtraData().put("BukkitValues", bukkitValues);
        world.getExtraData().put("seed", StringBinaryTag.stringBinaryTag("AD-098"));

        Map<String, BinaryTag> read = saveAndRead(world).getExtraData();
        assertEquals(bukkitValues, read.get("BukkitValues"));
        assertEquals(StringBinaryTag.stringBinaryTag("AD-098"), read.get("seed"));
        assertEquals(world.getPropertyMap().toCompound(), read.get("properties"));
    }

    @Test
    @DisplayName("chunks at negative coordinates come back where they were")
    void negativeCoordinatesComeBack() throws Exception {
        int[][] positions = {{0, -1}, {-1, -1}, {-1, 0}, {5, -9}, {-32, 17}};
        SlimeChunk[] chunks = new SlimeChunk[positions.length];
        for (int i = 0; i < positions.length; i++) {
            chunks[i] = chunk(positions[i][0], positions[i][1])
                .blockEntities(blockEntity("minecraft:sign", positions[i][0], i, positions[i][1]))
                .build();
        }
        SlimeWorld read = saveAndRead(world(keepEverything(), chunks));
        assertEquals(positions.length, read.getChunkStorage().size());
        for (int i = 0; i < positions.length; i++) {
            SlimeChunk chunk = read.getChunk(positions[i][0], positions[i][1]);
            assertNotNull(chunk, "chunk " + positions[i][0] + ", " + positions[i][1]);
            assertEquals(positions[i][0], chunk.getX());
            assertEquals(positions[i][1], chunk.getZ());
            assertEquals(chunks[i].getTileEntities(), chunk.getTileEntities(), "content of chunk " + i);
        }
    }

    @Test
    @DisplayName("a world without chunks comes back without chunks, with its properties")
    void emptyWorldComesBack() throws Exception {
        SlimePropertyMap properties = new SlimePropertyMap();
        properties.setValue(SlimeProperties.SPAWN_Y, 100);
        SlimeWorld read = saveAndRead(world(properties));
        assertTrue(read.getChunkStorage().isEmpty());
        assertEquals(100, read.getPropertyMap().getValue(SlimeProperties.SPAWN_Y));
    }

    @Test
    @DisplayName("a chunk with nothing in it is kept when the world never prunes")
    void emptyChunkIsKeptWithoutPruning() throws Exception {
        SlimeChunk read = savedAlone(chunk(4, 4).sections());
        assertEquals(0, read.getSections().length);
        assertTrue(read.getTileEntities().isEmpty());
        assertTrue(read.getEntities().isEmpty());
    }

    @Test
    @DisplayName("aggressive pruning leaves out a chunk without sections and keeps what holds a block, a block entity or an entity")
    void aggressivePruningDropsOnlyEmptyChunks() throws Exception {
        SlimeWorld read = saveAndRead(world(new SlimePropertyMap(),
            chunk(0, 0).sections().build(),
            chunk(1, 0).build(),
            chunk(2, 0).sections().blockEntities(blockEntity("minecraft:sign", 32, 64, 0)).build(),
            chunk(3, 0).sections().entities(entity("minecraft:armor_stand", 20.0)).build()));
        assertNull(read.getChunk(0, 0), "no section, no block entity, no entity");
        assertNotNull(read.getChunk(1, 0), "a stone section");
        assertNotNull(read.getChunk(2, 0), "a block entity");
        assertNotNull(read.getChunk(3, 0), "an entity");
    }

    @Test
    @DisplayName("aggressive pruning leaves out a chunk whose sections hold nothing but air, as it does for a loaded chunk")
    void aggressivePruningDropsAirOnlyChunks() throws Exception {
        SlimeChunkSection air = section(blockStates(AIR), biomes(PLAINS), null, null);
        SlimeWorld read = saveAndRead(world(new SlimePropertyMap(),
            chunk(0, 0).sections(air, air).build(),
            chunk(1, 0).sections(air, stoneSection()).build(),
            chunk(2, 0).sections(air).blockEntities(blockEntity("minecraft:sign", 32, 64, 0)).build()));
        assertNull(read.getChunk(0, 0), "air and nothing else");
        assertNotNull(read.getChunk(1, 0), "air below stone");
        assertNotNull(read.getChunk(2, 0), "air and a block entity");
    }

    @Test
    @DisplayName("a chunk with a section left null is saved, with or without the reuse: the section comes back empty and the others as they were")
    void nullSectionIsSavedAsAnEmptyOne() throws Exception {
        SlimeChunkSection stone = stoneSection();
        for (boolean reuse : new boolean[]{false, true}) {
            com.infernalsuite.asp.serialization.slime.SlimeSerializer.reuseEncodedSections = reuse;
            try {
                SlimeChunkSection[] read = savedAlone(chunk(0, 0).sections(null, stone, null)).getSections();
                assertEquals(3, read.length, "sections read back");
                for (int absent : new int[]{0, 2}) {
                    assertTrue(read[absent].getBlockStatesTag().isEmpty() && read[absent].getBiomeTag().isEmpty(), "section " + absent + " holds something");
                    assertNull(read[absent].getBlockLight());
                    assertNull(read[absent].getSkyLight());
                }
                assertEquals(stone.getBlockStatesTag(), read[1].getBlockStatesTag());
            } finally {
                com.infernalsuite.asp.serialization.slime.SlimeSerializer.reuseEncodedSections = false;
            }
        }
    }

    @Test
    @DisplayName("aggressive pruning keeps a chunk of air that holds plugin data: a PDC value or any other extra entry, but not an empty PDC")
    void aggressivePruningKeepsPluginData() throws Exception {
        SlimeChunkSection air = section(blockStates(AIR), biomes(PLAINS), null, null);
        CompoundBinaryTag owner = CompoundBinaryTag.builder().putInt("btc:owner", 7).build();
        SlimeWorld read = saveAndRead(world(new SlimePropertyMap(),
            chunk(0, 0).sections(air).extra(Map.of("ChunkBukkitValues", CompoundBinaryTag.empty())).build(),
            chunk(1, 0).sections(air).extra(Map.of("ChunkBukkitValues", owner)).build(),
            chunk(2, 0).sections(air).extra(Map.of("craftengine", IntBinaryTag.intBinaryTag(42))).build(),
            chunk(3, 0).sections(air).extra(null).build()));
        assertNull(read.getChunk(0, 0), "air and an empty PDC");
        assertNotNull(read.getChunk(1, 0), "air and a PDC value");
        assertEquals(owner, read.getChunk(1, 0).getExtraData().get("ChunkBukkitValues"));
        assertNotNull(read.getChunk(2, 0), "air and the data of a plugin");
        assertNull(read.getChunk(3, 0), "air and no extra data at all");
    }

    @Test
    @DisplayName("a world read back, saved again and read again is the same world: nothing drifts from save to save")
    void secondSaveGivesTheSameWorld() throws Exception {
        SlimeWorld world = world(keepEverything(),
            chunk(-1, 2).sections(section(blockStates(STONE, DIRT), biomes(PLAINS, DESERT), light(3), light(5)))
                .blockEntities(blockEntity("minecraft:chest", -16, 64, 32))
                .entities(entity("minecraft:cow", 10.0))
                .blockTicks(ticks("minecraft:repeater", 4))
                .build(),
            chunk(0, 0).sections(stoneSection()).build());
        SlimeWorld once = saveAndRead(world);
        SlimeWorld twice = saveAndRead(once);
        // not byte for byte: the order of the keys of a compound is free, and it changes the compressed size
        assertEquals(SlimeFixtures.content(once), SlimeFixtures.content(twice));
    }
}
