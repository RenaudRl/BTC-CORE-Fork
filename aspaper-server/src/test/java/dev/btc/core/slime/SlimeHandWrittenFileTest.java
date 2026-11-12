package dev.btc.core.slime;

import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static dev.btc.core.slime.SlimeFixtures.DATA_VERSION;
import static dev.btc.core.slime.SlimeFixtures.PLAINS;
import static dev.btc.core.slime.SlimeFixtures.STONE;
import static dev.btc.core.slime.SlimeFixtures.biomes;
import static dev.btc.core.slime.SlimeFixtures.blockEntity;
import static dev.btc.core.slime.SlimeFixtures.blockStates;
import static dev.btc.core.slime.SlimeFixtures.entity;
import static dev.btc.core.slime.SlimeFixtures.light;
import static dev.btc.core.slime.SlimeFixtures.read;
import static dev.btc.core.slime.SlimeFixtures.ticks;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v13 layout of SLIME_FORMAT written out by hand, without the writer of the fork: a writer and a
 * reader that drift together still agree with each other, they no longer agree with these files.
 */
class SlimeHandWrittenFileTest {

    private static final byte[] SLIME_HEADER = {-79, 11};
    private static final int SLIME_V13 = 13;

    private static final int POI_CHUNKS = 1;
    private static final int BLOCK_TICKS = 1 << 1;
    private static final int FLUID_TICKS = 1 << 2;
    private static final int UNKNOWN_DATA = 1 << 5;

    private static final int HAS_BLOCK_LIGHT = 1;
    private static final int HAS_SKY_LIGHT = 1 << 1;

    private static final int ZSTD_MAGIC = 0xFD2FB528;
    private static final int ZSTD_SINGLE_SEGMENT_SIZE_ON_4_BYTES = 0xA0;
    private static final int ZSTD_FRAME_OVERHEAD = 12;
    private static final int ZSTD_LAST_RAW_BLOCK = 1;
    private static final int ZSTD_BLOCK_SIZE_SHIFT = 3;

    /** One zstd frame that stores {@code content} as a single raw block: the frame is part of the layout. */
    private static byte[] zstdFrame(byte[] content) {
        ByteBuffer frame = ByteBuffer.allocate(ZSTD_FRAME_OVERHEAD + content.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(ZSTD_MAGIC).put((byte) ZSTD_SINGLE_SEGMENT_SIZE_ON_4_BYTES).putInt(content.length);
        int blockHeader = content.length << ZSTD_BLOCK_SIZE_SHIFT | ZSTD_LAST_RAW_BLOCK;
        frame.put((byte) blockHeader).put((byte) (blockHeader >> Byte.SIZE)).put((byte) (blockHeader >> 2 * Byte.SIZE));
        return frame.put(content).array();
    }

    private static byte[] nbt(CompoundBinaryTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(tag, (DataOutput) new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static void sized(DataOutputStream out, byte[] content) throws IOException {
        out.writeInt(content.length);
        out.write(content);
    }

    private static void compressed(DataOutputStream out, byte[] content) throws IOException {
        byte[] frame = zstdFrame(content);
        out.writeInt(frame.length);
        out.writeInt(content.length);
        out.write(frame);
    }

    private static byte[] file(int worldFlags, byte[] chunks, CompoundBinaryTag worldExtra) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(SLIME_HEADER);
        out.writeByte(SLIME_V13);
        out.writeInt(DATA_VERSION);
        out.writeByte(worldFlags);
        compressed(out, chunks);
        compressed(out, nbt(worldExtra));
        return bytes.toByteArray();
    }

    private static CompoundBinaryTag list(String key, CompoundBinaryTag... tags) {
        return CompoundBinaryTag.builder().put(key, ListBinaryTag.from(List.of(tags))).build();
    }

    private static CompoundBinaryTag properties(int spawnY) {
        SlimePropertyMap properties = new SlimePropertyMap();
        properties.setValue(SlimeProperties.SPAWN_Y, spawnY);
        return CompoundBinaryTag.builder().put("properties", properties.toCompound()).build();
    }

    @Test
    @DisplayName("a file laid out by hand as SLIME_FORMAT describes v13 is read field by field")
    void handWrittenFileIsRead() throws Exception {
        CompoundBinaryTag heightMaps = CompoundBinaryTag.builder().putLongArray("WORLD_SURFACE", new long[]{7L, 9L}).build();
        CompoundBinaryTag poi = CompoundBinaryTag.builder().putBoolean("Valid", true).build();
        CompoundBinaryTag chest = blockEntity("minecraft:chest", -40, 64, 115);
        CompoundBinaryTag cow = entity("minecraft:cow", 10.0);
        CompoundBinaryTag extra = CompoundBinaryTag.builder().putString("btc:owner", "renaud").build();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream chunks = new DataOutputStream(bytes);
        chunks.writeInt(1); // chunks
        chunks.writeInt(-3); // x
        chunks.writeInt(7); // z
        chunks.writeInt(3); // sections
        chunks.writeByte(HAS_BLOCK_LIGHT | HAS_SKY_LIGHT);
        chunks.write(light(3).getBacking()); // block light comes first
        chunks.write(light(5).getBacking());
        sized(chunks, nbt(blockStates(STONE, "minecraft:dirt")));
        sized(chunks, nbt(biomes(PLAINS, "minecraft:desert")));
        chunks.writeByte(HAS_SKY_LIGHT);
        chunks.write(light(7).getBacking());
        sized(chunks, nbt(blockStates(STONE)));
        sized(chunks, nbt(biomes(PLAINS)));
        chunks.writeByte(0); // no light
        sized(chunks, nbt(blockStates("minecraft:oak_log")));
        chunks.writeInt(0); // biomes: a length of zero is an empty compound
        sized(chunks, nbt(heightMaps));
        sized(chunks, nbt(poi));
        sized(chunks, nbt(CompoundBinaryTag.builder().put("block_ticks", ticks("minecraft:repeater", 4)).build()));
        sized(chunks, nbt(CompoundBinaryTag.builder().put("fluid_ticks", ticks("minecraft:water", 9)).build()));
        sized(chunks, nbt(list("tileEntities", chest)));
        sized(chunks, nbt(list("entities", cow)));
        sized(chunks, nbt(extra));

        SlimeWorld world = read(file(POI_CHUNKS | BLOCK_TICKS | FLUID_TICKS, bytes.toByteArray(),
            properties(90).put("seed", StringBinaryTag.stringBinaryTag("AD-098"))));

        assertEquals(DATA_VERSION, world.getDataVersion());
        assertEquals(90, world.getPropertyMap().getValue(SlimeProperties.SPAWN_Y));
        assertEquals(StringBinaryTag.stringBinaryTag("AD-098"), world.getExtraData().get("seed"));
        assertEquals(1, world.getChunkStorage().size());
        SlimeChunk chunk = world.getChunk(-3, 7);
        assertNotNull(chunk, "chunk -3, 7");

        SlimeChunkSection[] sections = chunk.getSections();
        assertEquals(3, sections.length);
        assertArrayEquals(light(3).getBacking(), sections[0].getBlockLight().getBacking(), "block light, section 0");
        assertArrayEquals(light(5).getBacking(), sections[0].getSkyLight().getBacking(), "sky light, section 0");
        assertEquals(blockStates(STONE, "minecraft:dirt"), sections[0].getBlockStatesTag());
        assertEquals(biomes(PLAINS, "minecraft:desert"), sections[0].getBiomeTag());
        assertNull(sections[1].getBlockLight(), "block light, section 1");
        assertArrayEquals(light(7).getBacking(), sections[1].getSkyLight().getBacking(), "sky light, section 1");
        assertEquals(blockStates(STONE), sections[1].getBlockStatesTag());
        assertNull(sections[2].getBlockLight(), "block light, section 2");
        assertNull(sections[2].getSkyLight(), "sky light, section 2");
        assertEquals(blockStates("minecraft:oak_log"), sections[2].getBlockStatesTag());
        assertEquals(CompoundBinaryTag.empty(), sections[2].getBiomeTag());

        assertEquals(heightMaps, chunk.getHeightMaps());
        assertEquals(poi, chunk.getPoiChunkSections());
        assertEquals(ticks("minecraft:repeater", 4), chunk.getBlockTicks());
        assertEquals(ticks("minecraft:water", 9), chunk.getFluidTicks());
        assertEquals(List.of(chest), chunk.getTileEntities());
        assertEquals(List.of(cow), chunk.getEntities());
        assertEquals(extra, CompoundBinaryTag.from(chunk.getExtraData()));
    }

    @Test
    @DisplayName("data of a kind this version does not know is stepped over, the chunks after it are read in place")
    void unknownAdditionalDataIsSteppedOver() throws Exception {
        CompoundBinaryTag sign = blockEntity("minecraft:sign", 16, 70, -16);
        byte[] unknown = {1, 2, 3, 4, 5, 6, 7};

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream chunks = new DataOutputStream(bytes);
        chunks.writeInt(2); // chunks
        for (int x = 0; x < 2; x++) {
            chunks.writeInt(x);
            chunks.writeInt(-1); // z
            chunks.writeInt(0); // sections
            chunks.writeInt(0); // height maps
            sized(chunks, unknown); // no POI, no ticks: the unknown kind comes right after the height maps
            sized(chunks, nbt(list("tileEntities", sign)));
            chunks.writeInt(0); // entities
            chunks.writeInt(0); // extra
        }

        SlimeWorld world = read(file(UNKNOWN_DATA, bytes.toByteArray(), properties(64)));

        assertEquals(2, world.getChunkStorage().size());
        for (int x = 0; x < 2; x++) {
            SlimeChunk chunk = world.getChunk(x, -1);
            assertNotNull(chunk, "chunk " + x + ", -1");
            assertEquals(List.of(sign), chunk.getTileEntities(), "chunk " + x);
            assertTrue(chunk.getEntities().isEmpty(), "chunk " + x);
            assertTrue(chunk.getExtraData().isEmpty(), "chunk " + x);
            assertNull(chunk.getPoiChunkSections(), "chunk " + x);
        }
    }
}
