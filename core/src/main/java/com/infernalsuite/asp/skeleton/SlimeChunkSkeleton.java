package com.infernalsuite.asp.skeleton;

import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public record SlimeChunkSkeleton(int x, int z, SlimeChunkSection[] sections,
                                 CompoundBinaryTag heightMap,
                                 List<CompoundBinaryTag> blockEntities,
                                 List<CompoundBinaryTag> entities,
                                 Map<String, BinaryTag> extra,
                                 CompoundBinaryTag upgradeData,
                                 CompoundBinaryTag poiChunk,
                                 ListBinaryTag blockTicks,
                                 ListBinaryTag fluidTicks) implements SlimeChunk {

    /**
     * BTC-CORE: the extra data of a chunk is one concurrent map from the moment the chunk is stored.
     * Whoever holds it - a plugin that got the chunk from the world, the chunk loaded from this one,
     * the next stored chunk - writes the same map, so nothing written is left behind in a copy, and
     * a save that is walking it is neither broken nor fed a half-written entry.
     */
    public SlimeChunkSkeleton {
        // No map given is an empty one: the chunk loaded from this one and the loader read it without a null test
        if (extra == null) extra = new ConcurrentHashMap<>();
        else if (!(extra instanceof ConcurrentMap)) extra = new ConcurrentHashMap<>(extra);
    }

    /**
     * BTC-CORE: {@code chunk} as a save writes it - the same content with its own copy of the extra
     * data. The stored chunk keeps the live map, which plugins go on writing while the save is
     * serialized on another thread; the copy is that map as it was when the save was taken.
     */
    public static SlimeChunkSkeleton savedCopy(SlimeChunk chunk) {
        Map<String, BinaryTag> extra = chunk.getExtraData();
        return new SlimeChunkSkeleton(chunk.getX(), chunk.getZ(), chunk.getSections(), chunk.getHeightMaps(),
                chunk.getTileEntities(), chunk.getEntities(), extra == null ? null : new ConcurrentHashMap<>(extra),
                chunk.getUpgradeData(), chunk.getPoiChunkSections(), chunk.getBlockTicks(), chunk.getFluidTicks());
    }

    @Override
    public int getX() {
        return this.x;
    }

    @Override
    public int getZ() {
        return this.z;
    }

    @Override
    public SlimeChunkSection[] getSections() {
        return this.sections;
    }

    @Override
    public CompoundBinaryTag getHeightMaps() {
        return this.heightMap;
    }

    @Override
    public List<CompoundBinaryTag> getTileEntities() {
        return this.blockEntities;
    }

    @Override
    public List<CompoundBinaryTag> getEntities() {
        return this.entities;
    }

    @Override
    public Map<String, BinaryTag> getExtraData() {
        return this.extra;
    }

    @Override
    public CompoundBinaryTag getUpgradeData() {
        return this.upgradeData;
    }

    @Override
    public ListBinaryTag getBlockTicks() {
        return this.blockTicks;
    }

    @Override
    public ListBinaryTag getFluidTicks() {
        return this.fluidTicks;
    }

    @Override
    public CompoundBinaryTag getPoiChunkSections() {
        return this.poiChunk;
    }
}
