package dev.btc.core.slime;

import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.serialization.anvil.AnvilImportData;
import com.infernalsuite.asp.serialization.anvil.AnvilWorldReader;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The Anvil import prunes a chunk of air only once it knows the chunk holds no entity. */
class AnvilImportPruningTest {

    private static final int SECTOR = 4096;
    private static final int HEADER_SECTORS = 2;
    private static final int ZLIB = 2;
    private static final int LOWEST_SECTION = -4;
    private static final int HIGHEST_SECTION = 19;

    @TempDir
    Path worldDir;

    @Test
    @DisplayName("a chunk of air that holds an entity in the entity files is imported with its entity, a bare one is pruned")
    void anAirChunkKeepsTheEntitiesOfTheEntityFiles() throws IOException {
        BinaryTagIO.writer().write(CompoundBinaryTag.builder().put("Data", CompoundBinaryTag.builder()
            .putInt("DataVersion", SlimeFixtures.DATA_VERSION).build()).build(), this.worldDir.resolve("level.dat"), BinaryTagIO.Compression.GZIP);
        region("region", airChunk(0, 0), airChunk(1, 0), stoneChunk(2, 0));
        region("entities", CompoundBinaryTag.builder()
            .putIntArray("Position", new int[]{0, 0})
            .putInt("DataVersion", SlimeFixtures.DATA_VERSION)
            .put("Entities", ListBinaryTag.from(List.of(SlimeFixtures.entity("minecraft:item_display", 1.0))))
            .build());

        SlimeWorld world = AnvilWorldReader.INSTANCE_NO_CONVERSION.readFromData(new AnvilImportData(this.worldDir, "imported", null));

        SlimeChunk furnished = world.getChunk(0, 0);
        assertNotNull(furnished, "the chunk of air that holds a display");
        assertEquals(List.of(SlimeFixtures.entity("minecraft:item_display", 1.0)), furnished.getEntities());
        assertNull(world.getChunk(1, 0), "the chunk of air that holds nothing");
        assertNotNull(world.getChunk(2, 0), "the chunk of stone");
    }

    private static CompoundBinaryTag airChunk(int x, int z) {
        return chunk(x, z, SlimeFixtures.AIR);
    }

    private static CompoundBinaryTag stoneChunk(int x, int z) {
        return chunk(x, z, SlimeFixtures.STONE);
    }

    private static CompoundBinaryTag chunk(int x, int z, String block) {
        ListBinaryTag.Builder<CompoundBinaryTag> sections = ListBinaryTag.builder(net.kyori.adventure.nbt.BinaryTagTypes.COMPOUND);
        for (int y = LOWEST_SECTION; y <= HIGHEST_SECTION; y++) {
            sections.add(CompoundBinaryTag.builder()
                .putByte("Y", (byte) y)
                .put("block_states", CompoundBinaryTag.builder().put("palette", ListBinaryTag.from(List.of(
                    CompoundBinaryTag.builder().putString("Name", block).build()))).build())
                .put("biomes", CompoundBinaryTag.builder().put("palette", ListBinaryTag.from(List.of(
                    StringBinaryTag.stringBinaryTag(SlimeFixtures.PLAINS)))).build())
                .build());
        }
        return CompoundBinaryTag.builder()
            .putInt("xPos", x).putInt("zPos", z)
            .putInt("DataVersion", SlimeFixtures.DATA_VERSION)
            .putString("Status", "minecraft:full")
            .put("sections", sections.build())
            .build();
    }

    /** A region file r.0.0.mca of {@code folder}: the header, then one sector per compound in the order given. */
    private void region(String folder, CompoundBinaryTag... compounds) throws IOException {
        ByteBuffer file = ByteBuffer.allocate((HEADER_SECTORS + compounds.length) * SECTOR);
        for (int index = 0; index < compounds.length; index++) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            BinaryTagIO.writer().write(compounds[index], compressed, BinaryTagIO.Compression.ZLIB);
            int sector = HEADER_SECTORS + index;
            file.putInt(index * Integer.BYTES, sector << 8 | 1);
            file.position(sector * SECTOR);
            file.putInt(compressed.size() + 1).put((byte) ZLIB).put(compressed.toByteArray());
        }
        Path directory = Files.createDirectories(this.worldDir.resolve(folder));
        Files.write(directory.resolve("r.0.0.mca"), file.array());
    }
}
