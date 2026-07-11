package com.infernalsuite.asp;

import com.infernalsuite.asp.api.SlimeDataConverter;
import com.infernalsuite.asp.level.chunk.SlimeChunkConverter;
import com.infernalsuite.asp.serialization.SlimeWorldReader;
import com.infernalsuite.asp.skeleton.SkeletonSlimeWorld;
import com.infernalsuite.asp.skeleton.SlimeChunkSectionSkeleton;
import com.infernalsuite.asp.skeleton.SlimeChunkSkeleton;
import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.mojang.datafixers.DSL.TypeReference;
import com.mojang.serialization.Dynamic;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

class SimpleDataFixerConverter implements SlimeWorldReader<SlimeWorld>, SlimeDataConverter {

    // ASP - Native DFU: use Mojang's DataFixerUpper instead of ca.spottedleaf.dataconverter,
    // which is no longer available as a published dependency. Behaviour mirrors the previous
    // implementation: NBT is upgraded forward from its stored data version to the target version.
    private static Tag update(TypeReference reference, Tag tag, int fromVersion, int toVersion) {
        return DataFixers.getDataFixer()
                .update(reference, new Dynamic<>(NbtOps.INSTANCE, tag), fromVersion, toVersion)
                .getValue();
    }

    @Override
    public SlimeWorld readFromData(SlimeWorld data) {
        int newVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        int currentVersion = data.getDataVersion();
        // Already fixed
        if (currentVersion == newVersion) {
            return data;
        }

        Long2ObjectMap<SlimeChunk> chunks = new Long2ObjectOpenHashMap<>();
        for (SlimeChunk chunk : data.getChunkStorage()) {
            List<CompoundBinaryTag> entities = new ArrayList<>();
            List<CompoundBinaryTag> blockEntities = new ArrayList<>();
            for (CompoundBinaryTag upgradeEntity : chunk.getTileEntities()) {
                blockEntities.add(
                        convertAndBack(upgradeEntity, (tag) -> update(References.BLOCK_ENTITY, tag, currentVersion, newVersion))
                );
            }
            for (CompoundBinaryTag upgradeEntity : chunk.getEntities()) {
                entities.add(
                        convertAndBack(upgradeEntity, (tag) -> update(References.ENTITY, tag, currentVersion, newVersion))
                );
            }
            long chunkPos = Util.chunkPosition(chunk.getX(), chunk.getZ());

            SlimeChunkSection[] sections = new SlimeChunkSection[chunk.getSections().length];
            for (int i = 0; i < sections.length; i++) {
                SlimeChunkSection dataSection = chunk.getSections()[i];
                if (dataSection == null) continue;

                CompoundBinaryTag blockStateTag = convertAndBack(dataSection.getBlockStatesTag(),
                        (tag) -> convertPalette(References.BLOCK_STATE, tag, currentVersion, newVersion));

                CompoundBinaryTag biomeTag = convertAndBack(dataSection.getBiomeTag(),
                        (tag) -> convertPalette(References.BIOME, tag, currentVersion, newVersion));

                sections[i] = new SlimeChunkSectionSkeleton(
                        blockStateTag,
                        biomeTag,
                        dataSection.getBlockLight(),
                        dataSection.getSkyLight()
                );
            }

            CompoundBinaryTag newPoi = chunk.getPoiChunkSections() != null ? convertPoiSections(chunk.getPoiChunkSections(), currentVersion, newVersion) : null;

            chunks.put(chunkPos, new SlimeChunkSkeleton(
                    chunk.getX(),
                    chunk.getZ(),
                    sections,
                    chunk.getHeightMaps(),
                    blockEntities,
                    entities,
                    chunk.getExtraData(),
                    chunk.getUpgradeData(),
                    newPoi,
                    chunk.getBlockTicks(),
                    chunk.getFluidTicks()
            ));

        }

        return new SkeletonSlimeWorld(
                data.getName(),
                data.getLoader(),
                data.isReadOnly(),
                chunks,
                data.getExtraData(),
                data.getPropertyMap(),
                newVersion
        );
    }

    private CompoundBinaryTag convertPoiSections(CompoundBinaryTag poiChunkSections, int currentVersion, int newVersion) {
        CompoundTag poiChunk = SlimeChunkConverter.createPoiChunkFromSlimeSections(poiChunkSections, currentVersion);
        CompoundTag fixed = (CompoundTag) update(References.ENTITY, poiChunk, currentVersion, newVersion);
        return SlimeChunkConverter.getSlimeSectionsFromPoiCompound(fixed);
    }

    @Override
    public SlimeWorld applyDataFixers(SlimeWorld world) {
        return readFromData(world);
    }

    private static CompoundBinaryTag convertAndBack(CompoundBinaryTag value, Function<CompoundTag, Tag> fixer) {
        if (value == null) return null;

        net.minecraft.nbt.CompoundTag converted = (net.minecraft.nbt.CompoundTag) Converter.convertTag(value);
        Tag fixed = fixer.apply(converted);

        return Converter.convertTag(fixed);
    }

    // ASP - Native DFU: upgrade every entry of a chunk-section "palette" list (block states are
    // compounds, biomes are strings), matching the previous WalkerUtils.convertList behaviour.
    private static Tag convertPalette(TypeReference reference, CompoundTag sectionTag, int fromVersion, int toVersion) {
        if (sectionTag.get("palette") instanceof ListTag palette) {
            ListTag newPalette = new ListTag();
            for (Tag entry : palette) {
                newPalette.addAndUnwrap(update(reference, entry, fromVersion, toVersion));
            }
            sectionTag.put("palette", newPalette);
        }
        return sectionTag;
    }

    @Override
    public CompoundBinaryTag convertChunkTo1_13(CompoundBinaryTag tag) {
        return convertChunk(tag, 1631);
    }

    @Override
    public CompoundBinaryTag convertChunk(CompoundBinaryTag globalTag, int to) {
        CompoundTag nmsTag = (CompoundTag) Converter.convertTag(globalTag);

        int version = nmsTag.getInt("DataVersion").orElseThrow();

        CompoundTag fixed = (CompoundTag) update(References.CHUNK, nmsTag, version, to);

        return Converter.convertTag(fixed);
    }

    @Override
    public List<CompoundBinaryTag> convertEntities(List<CompoundBinaryTag> input, int from, int to) {
        List<CompoundBinaryTag> entities = new ArrayList<>(input.size());

        for (CompoundBinaryTag upgradeEntity : input) {
            entities.add(
                    convertAndBack(upgradeEntity, (tag) -> update(References.ENTITY, tag, from, to))
            );
        }
        return entities;
    }

    @Override
    public List<CompoundBinaryTag> convertTileEntities(List<CompoundBinaryTag> input, int from, int to) {
        List<CompoundBinaryTag> blockEntities = new ArrayList<>(input.size());

        for (CompoundBinaryTag upgradeEntity : input) {
            blockEntities.add(
                    convertAndBack(upgradeEntity, (tag) -> update(References.BLOCK_ENTITY, tag, from, to))
            );
        }
        return blockEntities;
    }

    @Override
    public ListBinaryTag convertBlockPalette(ListBinaryTag input, int from, int to) {
        ListTag nbtList = (ListTag) Converter.convertTag(input);
        ListTag result = new ListTag();

        for (Tag entry : nbtList) {
            result.addAndUnwrap(update(References.BLOCK_STATE, entry, from, to));
        }

        return Converter.convertTag(result);
    }

    @Override
    public int getServerVersion() {
        return 0;
    }
}
