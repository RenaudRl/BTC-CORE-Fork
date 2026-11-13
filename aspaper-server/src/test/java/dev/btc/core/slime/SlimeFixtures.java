package dev.btc.core.slime;

import com.infernalsuite.asp.api.exceptions.CorruptedWorldException;
import com.infernalsuite.asp.api.exceptions.NewerFormatException;
import com.infernalsuite.asp.api.utils.NibbleArray;
import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import com.infernalsuite.asp.serialization.slime.SlimeSerializer;
import com.infernalsuite.asp.serialization.slime.reader.SlimeWorldReaderRegistry;
import com.infernalsuite.asp.skeleton.SkeletonSlimeWorld;
import com.infernalsuite.asp.skeleton.SlimeChunkSectionSkeleton;
import com.infernalsuite.asp.skeleton.SlimeChunkSkeleton;
import com.infernalsuite.asp.util.Util;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Slime worlds built by hand for the format tests: no server, no NMS, nothing read from a disk. */
final class SlimeFixtures {

    static final String WORLD = "btcsky_isle_fixture";
    static final int DATA_VERSION = 4671;
    static final int LIGHT_BYTES = 2048;
    static final String PLAINS = "minecraft:plains";
    static final String STONE = "minecraft:stone";
    static final String AIR = "minecraft:air";

    private static final int PACKED_LONGS = 256; // 4096 blocks of a section at 4 bits each
    private static final long PACKED_STEP = 0x9E3779B97F4A7C15L;
    private static final int LIGHT_STEP = 31;

    private SlimeFixtures() {
    }

    /** Nothing is left out of the file: no pruning, POI and scheduled ticks saved. */
    static SlimePropertyMap keepEverything() {
        SlimePropertyMap properties = new SlimePropertyMap();
        properties.setValue(SlimeProperties.CHUNK_PRUNING, "never");
        properties.setValue(SlimeProperties.SAVE_POI, true);
        properties.setValue(SlimeProperties.SAVE_BLOCK_TICKS, true);
        properties.setValue(SlimeProperties.SAVE_FLUID_TICKS, true);
        return properties;
    }

    /** A block state container as Minecraft writes it: one palette entry alone, or a palette and its packed indices. */
    static CompoundBinaryTag blockStates(String... names) {
        ListBinaryTag.Builder<CompoundBinaryTag> palette = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (String name : names) {
            palette.add(CompoundBinaryTag.builder().putString("Name", name).build());
        }
        return paletted(palette.build(), names.length);
    }

    static CompoundBinaryTag biomes(String... names) {
        ListBinaryTag.Builder<StringBinaryTag> palette = ListBinaryTag.builder(BinaryTagTypes.STRING);
        for (String name : names) {
            palette.add(StringBinaryTag.stringBinaryTag(name));
        }
        return paletted(palette.build(), names.length);
    }

    private static CompoundBinaryTag paletted(ListBinaryTag palette, int entries) {
        CompoundBinaryTag.Builder tag = CompoundBinaryTag.builder().put("palette", palette);
        if (entries > 1) {
            long[] data = new long[PACKED_LONGS]; // opaque to the format: it only has to come back
            for (int i = 0; i < data.length; i++) {
                data[i] = (i + entries) * PACKED_STEP;
            }
            tag.putLongArray("data", data);
        }
        return tag.build();
    }

    static NibbleArray light(int seed) {
        byte[] backing = new byte[LIGHT_BYTES];
        for (int i = 0; i < backing.length; i++) {
            backing[i] = (byte) (seed + i * LIGHT_STEP);
        }
        return new NibbleArray(backing);
    }

    static SlimeChunkSection section(CompoundBinaryTag blockStates, CompoundBinaryTag biomes, NibbleArray blockLight, NibbleArray skyLight) {
        return new SlimeChunkSectionSkeleton(blockStates, biomes, blockLight, skyLight);
    }

    static SlimeChunkSection stoneSection() {
        return section(blockStates(STONE), biomes(PLAINS), null, null);
    }

    static CompoundBinaryTag blockEntity(String id, int x, int y, int z) {
        return CompoundBinaryTag.builder().putString("id", id).putInt("x", x).putInt("y", y).putInt("z", z).build();
    }

    static CompoundBinaryTag entity(String id, double health) {
        return CompoundBinaryTag.builder().putString("id", id).putDouble("Health", health).build();
    }

    static ListBinaryTag ticks(String block, int x) {
        return ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
            .add(CompoundBinaryTag.builder().putString("i", block).putInt("x", x).putInt("t", 3).build())
            .build();
    }

    static ChunkBuilder chunk(int x, int z) {
        return new ChunkBuilder(x, z);
    }

    static SkeletonSlimeWorld world(SlimePropertyMap properties, SlimeChunk... chunks) {
        Long2ObjectMap<SlimeChunk> storage = new Long2ObjectOpenHashMap<>();
        for (SlimeChunk chunk : chunks) {
            storage.put(Util.chunkPosition(chunk.getX(), chunk.getZ()), chunk);
        }
        return new SkeletonSlimeWorld(WORLD, null, false, storage, new ConcurrentHashMap<>(), properties, DATA_VERSION);
    }

    static SlimeWorld read(byte[] file) throws IOException, CorruptedWorldException, NewerFormatException {
        return SlimeWorldReaderRegistry.readWorld(null, WORLD, file, new SlimePropertyMap(), false);
    }

    static SlimeWorld saveAndRead(SlimeWorld world) throws IOException, CorruptedWorldException, NewerFormatException {
        return read(SlimeSerializer.serialize(world));
    }

    /** Everything a world holds, in a form two worlds can be compared by: chunks by position, light as text. */
    static List<Object> content(SlimeWorld world) {
        List<Object> content = new ArrayList<>(List.of(world.getDataVersion(), world.getPropertyMap().getProperties(), world.getExtraData()));
        world.getChunkStorage().stream()
            .sorted(Comparator.comparingLong(chunk -> Util.chunkPosition(chunk.getX(), chunk.getZ())))
            .forEach(chunk -> {
                content.add(Arrays.asList(chunk.getX(), chunk.getZ(), chunk.getHeightMaps(), chunk.getTileEntities(), chunk.getEntities(),
                    chunk.getExtraData(), chunk.getPoiChunkSections(), chunk.getBlockTicks(), chunk.getFluidTicks()));
                for (SlimeChunkSection section : chunk.getSections()) {
                    content.add(Arrays.asList(section.getBlockStatesTag(), section.getBiomeTag(), hex(section.getBlockLight()), hex(section.getSkyLight())));
                }
            });
        return content;
    }

    private static String hex(NibbleArray light) {
        return light == null ? null : HexFormat.of().formatHex(light.getBacking());
    }

    /** A chunk that builds its sections when asked, as a loaded chunk does, and counts how often. */
    record CountingChunk(SlimeChunk chunk, java.util.concurrent.atomic.AtomicInteger asked) implements SlimeChunk {
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

    /** One chunk, a stone section and nothing else unless told. */
    static final class ChunkBuilder {
        private final int x;
        private final int z;
        private SlimeChunkSection[] sections = {stoneSection()};
        private CompoundBinaryTag heightMaps = CompoundBinaryTag.empty();
        private List<CompoundBinaryTag> blockEntities = List.of();
        private List<CompoundBinaryTag> entities = List.of();
        private Map<String, BinaryTag> extra = new ConcurrentHashMap<>();
        private CompoundBinaryTag poi;
        private ListBinaryTag blockTicks;
        private ListBinaryTag fluidTicks;

        private ChunkBuilder(int x, int z) {
            this.x = x;
            this.z = z;
        }

        ChunkBuilder sections(SlimeChunkSection... sections) {
            this.sections = sections;
            return this;
        }

        ChunkBuilder heightMaps(CompoundBinaryTag heightMaps) {
            this.heightMaps = heightMaps;
            return this;
        }

        ChunkBuilder blockEntities(CompoundBinaryTag... blockEntities) {
            this.blockEntities = List.of(blockEntities);
            return this;
        }

        ChunkBuilder entities(CompoundBinaryTag... entities) {
            this.entities = List.of(entities);
            return this;
        }

        ChunkBuilder extra(Map<String, BinaryTag> extra) {
            this.extra = extra;
            return this;
        }

        ChunkBuilder poi(CompoundBinaryTag poi) {
            this.poi = poi;
            return this;
        }

        ChunkBuilder blockTicks(ListBinaryTag blockTicks) {
            this.blockTicks = blockTicks;
            return this;
        }

        ChunkBuilder fluidTicks(ListBinaryTag fluidTicks) {
            this.fluidTicks = fluidTicks;
            return this;
        }

        SlimeChunkSkeleton build() {
            return new SlimeChunkSkeleton(this.x, this.z, this.sections, this.heightMaps, this.blockEntities, this.entities,
                this.extra, null, this.poi, this.blockTicks, this.fluidTicks);
        }
    }
}
