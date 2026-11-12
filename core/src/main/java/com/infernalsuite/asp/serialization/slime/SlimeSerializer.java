package com.infernalsuite.asp.serialization.slime;

import com.github.luben.zstd.ZstdOutputStream;
import com.infernalsuite.asp.api.utils.SlimeFormat;
import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import com.infernalsuite.asp.serialization.slime.reader.impl.v13.v13AdditionalWorldData;
import com.infernalsuite.asp.skeleton.SlimeChunkSectionSkeleton;
import com.infernalsuite.asp.util.ThrowingConsumer;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

public class SlimeSerializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlimeSerializer.class);

    /**
     * BTC-CORE ({@code slime-world.save.reuse-encoded-sections}, set by BTCCoreConfig, read by each
     * save): the block states and biomes of a stored section are encoded by the first save that
     * writes them and their bytes written again by the next ones, and the sections of a loaded
     * chunk are encoded once a save instead of once for the pruning test and once for the file.
     * The file is the same, byte for byte. Off: every save encodes everything, as it always did.
     */
    public static boolean reuseEncodedSections;

    public static byte[] serialize(SlimeWorld world) {
        Map<String, BinaryTag> extraData = world.getExtraData();
        SlimePropertyMap propertyMap = world.getPropertyMap();

        // Store world properties
        if (!extraData.containsKey("properties")) {
            extraData.putIfAbsent("properties", propertyMap.toCompound());
        } else {
            extraData.replace("properties", propertyMap.toCompound());
        }

        ByteArrayOutputStream outByteStream = new ByteArrayOutputStream();
        DataOutputStream outStream = new DataOutputStream(outByteStream);

        try {
            // File Header and Slime version
            outStream.write(SlimeFormat.SLIME_HEADER);
            outStream.writeByte(SlimeFormat.SLIME_VERSION);

            // World version
            outStream.writeInt(world.getDataVersion());

            EnumSet<v13AdditionalWorldData> additionalWorldData = EnumSet.noneOf(v13AdditionalWorldData.class);
            if (world.getPropertyMap().getValue(SlimeProperties.SAVE_POI)) {
                additionalWorldData.add(v13AdditionalWorldData.POI_CHUNKS);
            }
            if (world.getPropertyMap().getValue(SlimeProperties.SAVE_BLOCK_TICKS)) {
                additionalWorldData.add(v13AdditionalWorldData.BLOCK_TICKS);
            }
            if (world.getPropertyMap().getValue(SlimeProperties.SAVE_FLUID_TICKS)) {
                additionalWorldData.add(v13AdditionalWorldData.FLUID_TICKS);
            }
            outStream.writeByte(v13AdditionalWorldData.fromSet(additionalWorldData));

            // Chunks

            writeCompressed(outStream, value -> serializeChunks(value, world, world.getChunkStorage(), additionalWorldData));

            writeCompressed(outStream, value -> {
                //Avoid a buffered output stream by casting to DataOutput. Buffered Output Streams make the memory usage explode
                BinaryTagIO.writer().write(CompoundBinaryTag.builder().put(extraData).build(), (DataOutput) new DataOutputStream(value));
            });

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return outByteStream.toByteArray();
    }

    /** BTC-CORE: what a null section is written as - no light, no block states, no biomes; the loader reads it as it reads null. */
    private static final SlimeChunkSection ABSENT_SECTION = new SlimeChunkSectionSkeleton(CompoundBinaryTag.empty(), CompoundBinaryTag.empty(), null, null);

    static void serializeChunks(DataOutputStream outStream, SlimeWorld world, Collection<SlimeChunk> chunks, EnumSet<v13AdditionalWorldData> data) throws IOException {

        // BTC-CORE: with the reuse on, the sections each chunk gave to the pruning test, until they are written.
        // By identity: a chunk is a record, and its equality would walk all it holds.
        Map<SlimeChunk, SlimeChunkSection[]> encodedOnce = reuseEncodedSections ? new IdentityHashMap<>() : null;

        // Prune chunks
        List<SlimeChunk> chunksToSave = chunks.stream()
                .filter(chunk -> !(encodedOnce == null ? ChunkPruner.canBePruned(world, chunk)
                        : ChunkPruner.canBePruned(world, chunk, () -> encodedOnce.computeIfAbsent(chunk, SlimeChunk::getSections))))
                .toList();

        outStream.writeInt(chunksToSave.size());
        for (SlimeChunk chunk : chunksToSave) {
            outStream.writeInt(chunk.getX());
            outStream.writeInt(chunk.getZ());

            // Chunk sections
            SlimeChunkSection[] given = encodedOnce == null ? null : encodedOnce.remove(chunk);
            SlimeChunkSection[] sections = given == null ? chunk.getSections() : given;

            outStream.writeInt(sections.length);
            for (SlimeChunkSection section : sections) {
                // BTC-CORE: a section left null (an Anvil chunk that lacks one, a chunk built through the API) used to
                // fail the save of the whole world; it is written as the empty section it stands for
                SlimeChunkSection slimeChunkSection = section == null ? ABSENT_SECTION : section;
                byte sectionFlags = 0;

                boolean hasBlockLight = slimeChunkSection.getBlockLight() != null;
                boolean hasSkyLight = slimeChunkSection.getSkyLight() != null;

                if(hasBlockLight) {
                    sectionFlags = (byte) (sectionFlags | 1);
                }
                if(hasSkyLight) {
                    sectionFlags = (byte) (sectionFlags | (1 << 1));
                }
                outStream.write(sectionFlags);

                // Block Light
                if (hasBlockLight) {
                    outStream.write(slimeChunkSection.getBlockLight().getBacking());
                }

                // Sky Light
                if (hasSkyLight) {
                    outStream.write(slimeChunkSection.getSkyLight().getBacking());
                }

                // Block Data
                SlimeChunkSectionSkeleton stored = encodedOnce != null && slimeChunkSection instanceof SlimeChunkSectionSkeleton skeleton ? skeleton : null;
                byte[] serializedBlockStates = stored != null ? stored.encodedBlockStates(SlimeSerializer::serializeCompoundTag)
                        : serializeCompoundTag(slimeChunkSection.getBlockStatesTag());
                outStream.writeInt(serializedBlockStates.length);
                outStream.write(serializedBlockStates);

                byte[] serializedBiomes = stored != null ? stored.encodedBiome(SlimeSerializer::serializeCompoundTag)
                        : serializeCompoundTag(slimeChunkSection.getBiomeTag());
                outStream.writeInt(serializedBiomes.length);
                outStream.write(serializedBiomes);
            }

            // Height Maps
            byte[] heightMaps = serializeCompoundTag(chunk.getHeightMaps());
            outStream.writeInt(heightMaps.length);
            outStream.write(heightMaps);

            if (data.contains(v13AdditionalWorldData.POI_CHUNKS)) {
                byte[] poiData = serializeCompoundTag(chunk.getPoiChunkSections());
                outStream.writeInt(poiData.length);
                outStream.write(poiData);
            }

            if (data.contains(v13AdditionalWorldData.BLOCK_TICKS)) {
                byte[] blockTicksData = serializeCompoundTag(wrap("block_ticks", chunk.getBlockTicks()));
                outStream.writeInt(blockTicksData.length);
                outStream.write(blockTicksData);
            }

            if (data.contains(v13AdditionalWorldData.FLUID_TICKS)) {
                byte[] fluidTicksData = serializeCompoundTag(wrap("fluid_ticks", chunk.getFluidTicks()));
                outStream.writeInt(fluidTicksData.length);
                outStream.write(fluidTicksData);
            }

            // Tile entities
            ListBinaryTag tileEntitiesNbtList = ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, yayGenerics(chunk.getTileEntities()));
            CompoundBinaryTag tileEntitiesCompound = CompoundBinaryTag.builder().put("tileEntities", tileEntitiesNbtList).build();
            byte[] tileEntitiesData = serializeCompoundTag(tileEntitiesCompound);

            outStream.writeInt(tileEntitiesData.length);
            outStream.write(tileEntitiesData);

            // Entities
            ListBinaryTag entitiesNbtList = ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, yayGenerics(chunk.getEntities()));
            CompoundBinaryTag entitiesCompound = CompoundBinaryTag.builder().put("entities", entitiesNbtList).build();
            byte[] entitiesData = serializeCompoundTag(entitiesCompound);

            outStream.writeInt(entitiesData.length);
            outStream.write(entitiesData);

            // Extra Tag
            if (chunk.getExtraData() == null) {
                LOGGER.warn("Chunk at {}, {} from world {} has no extra data! When deserialized, this chunk will have an empty extra data tag!", chunk.getX(), chunk.getZ(), world.getName());
            }
            byte[] extra = serializeCompoundTag(chunk.getExtraData() == null ? null : CompoundBinaryTag.from(chunk.getExtraData())); // BTC-CORE: the empty tag the warning announces, not a save that fails

            outStream.writeInt(extra.length);
            outStream.write(extra);
        }
    }

    private static void writeCompressed(DataOutputStream out, ThrowingConsumer<DataOutputStream> writer) throws Exception {
        ByteArrayOutputStream compressedOut = new ByteArrayOutputStream();
        ZstdOutputStream zstd = new ZstdOutputStream(compressedOut);
        DataOutputStream dataOut = new DataOutputStream(zstd);

        // write uncompressed data into zstd stream
        writer.accept(dataOut);

        dataOut.flush();
        zstd.close();

        byte[] compressed = compressedOut.toByteArray();

        out.writeInt(compressed.length);
        out.writeInt(dataOut.size());
        out.write(compressed);
    }

    private static CompoundBinaryTag wrap(String key, ListBinaryTag list) {
        if(list == null || list.isEmpty()) {
            return null;
        }
        return CompoundBinaryTag.builder().put(key, list).build();
    }

    protected static byte[] serializeCompoundTag(CompoundBinaryTag tag) throws IOException {
        if (tag == null || tag.isEmpty()) return new byte[0];

        ByteArrayOutputStream outByteStream = new ByteArrayOutputStream();
        //Avoid a buffered output stream by casting to DataOutput. Buffered Output Streams make the memory usage explode
        BinaryTagIO.writer().write(tag, (DataOutput) new DataOutputStream(outByteStream));

        return outByteStream.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static List<BinaryTag> yayGenerics(final List<? extends BinaryTag> tags) {
        return (List<BinaryTag>) tags;
    }

}
