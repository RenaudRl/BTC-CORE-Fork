package dev.btc.core.slime;

import com.infernalsuite.asp.api.exceptions.CorruptedWorldException;
import com.infernalsuite.asp.api.exceptions.NewerFormatException;
import com.infernalsuite.asp.api.utils.SlimeFormat;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.serialization.slime.SlimeSerializer;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static dev.btc.core.slime.SlimeFixtures.PLAINS;
import static dev.btc.core.slime.SlimeFixtures.STONE;
import static dev.btc.core.slime.SlimeFixtures.biomes;
import static dev.btc.core.slime.SlimeFixtures.blockEntity;
import static dev.btc.core.slime.SlimeFixtures.blockStates;
import static dev.btc.core.slime.SlimeFixtures.chunk;
import static dev.btc.core.slime.SlimeFixtures.entity;
import static dev.btc.core.slime.SlimeFixtures.keepEverything;
import static dev.btc.core.slime.SlimeFixtures.light;
import static dev.btc.core.slime.SlimeFixtures.read;
import static dev.btc.core.slime.SlimeFixtures.section;
import static dev.btc.core.slime.SlimeFixtures.ticks;
import static dev.btc.core.slime.SlimeFixtures.world;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A file that cannot be read is refused with a declared exception: never a world read by half. */
class SlimeReadFailureTest {

    private static final int VERSION_OFFSET = SlimeFormat.SLIME_HEADER.length;
    private static final int NEXT_VERSION = SlimeFormat.SLIME_VERSION + 1;
    private static final int OLDEST_READER = 10;

    private static byte[] file() {
        SlimeWorld world = world(keepEverything(),
            chunk(-1, 2).sections(section(blockStates(STONE, "minecraft:dirt"), biomes(PLAINS), light(3), light(5)))
                .blockEntities(blockEntity("minecraft:chest", -16, 64, 32))
                .entities(entity("minecraft:cow", 10.0))
                .poi(CompoundBinaryTag.builder().putBoolean("Valid", true).build())
                .blockTicks(ticks("minecraft:repeater", 4))
                .build(),
            chunk(0, 0).build());
        world.getExtraData().put("BukkitValues", CompoundBinaryTag.builder().putString("btc:island", "0001").build());
        return SlimeSerializer.serialize(world);
    }

    private static byte[] fileWithByte(int offset, int value) {
        byte[] file = file();
        file[offset] = (byte) value;
        return file;
    }

    @Test
    @DisplayName("a file cut short at any byte is refused with a declared exception")
    void everyTruncatedFileIsRefused() {
        byte[] file = file();
        for (int length = 0; length < file.length; length++) {
            byte[] cut = Arrays.copyOf(file, length);
            String where = "file cut at byte " + length + " of " + file.length;
            Throwable refusal = assertThrows(Throwable.class, () -> read(cut), where);
            assertTrue(refusal instanceof IOException || refusal instanceof CorruptedWorldException, where + ": " + refusal);
        }
    }

    @Test
    @DisplayName("a file of a newer format is refused as newer, whatever follows the version")
    void newerFormatIsRefused() {
        assertThrows(NewerFormatException.class, () -> read(fileWithByte(VERSION_OFFSET, NEXT_VERSION)));
        assertThrows(NewerFormatException.class, () -> read(fileWithByte(VERSION_OFFSET, Byte.MAX_VALUE)));
    }

    @Test
    @DisplayName("a file of a version this build has no reader for is refused as corrupted")
    void versionWithoutReaderIsRefused() {
        for (int version : new int[] {OLDEST_READER - 1, 0, -1}) {
            assertThrows(CorruptedWorldException.class, () -> read(fileWithByte(VERSION_OFFSET, version)), "version " + version);
        }
    }

    @Test
    @DisplayName("a file that does not start with the Slime header is refused as corrupted")
    void foreignHeaderIsRefused() {
        assertThrows(CorruptedWorldException.class, () -> read(fileWithByte(0, 0x1F)));
        assertThrows(CorruptedWorldException.class, () -> read(fileWithByte(1, 0x8B)));
    }
}
