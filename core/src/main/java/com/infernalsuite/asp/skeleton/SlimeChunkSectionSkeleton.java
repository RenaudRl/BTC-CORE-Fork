package com.infernalsuite.asp.skeleton;

import com.infernalsuite.asp.api.utils.NibbleArray;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/**
 * BTC-CORE: a class and no longer a record, to hold what a save wrote for its two tags
 * ({@code slime-world.save.reuse-encoded-sections}). Constructor, accessors, equality and text
 * are those the record had.
 */
public final class SlimeChunkSectionSkeleton implements SlimeChunkSection {

    /** How a save turns a tag into the bytes of the file. */
    @FunctionalInterface
    public interface TagEncoder {
        byte[] encode(CompoundBinaryTag tag) throws IOException;
    }

    private final CompoundBinaryTag blockStates;
    private final CompoundBinaryTag biome;
    private final NibbleArray block;
    private final NibbleArray light;
    // The bytes of the two tags, encoded by the first save that asks and handed to the next ones.
    // A tag never changes and this section never takes another, so the bytes cannot go stale; two
    // saves racing here write the same bytes. The light is not kept: its array can be written in place.
    private volatile byte[] encodedBlockStates;
    private volatile byte[] encodedBiome;

    public SlimeChunkSectionSkeleton(CompoundBinaryTag blockStates, CompoundBinaryTag biome, NibbleArray block, NibbleArray light) {
        this.blockStates = blockStates;
        this.biome = biome;
        this.block = block;
        this.light = light;
    }

    public CompoundBinaryTag blockStates() {
        return this.blockStates;
    }

    public CompoundBinaryTag biome() {
        return this.biome;
    }

    public NibbleArray block() {
        return this.block;
    }

    public NibbleArray light() {
        return this.light;
    }

    @Override
    public CompoundBinaryTag getBlockStatesTag() {
        return this.blockStates;
    }

    @Override
    public CompoundBinaryTag getBiomeTag() {
        return this.biome;
    }

    @Override
    public @Nullable NibbleArray getBlockLight() {
        return this.block;
    }

    @Override
    public NibbleArray getSkyLight() {
        return this.light;
    }

    /** The block states as {@code encoder} writes them, encoded once for the life of this section. Never write the array. */
    public byte[] encodedBlockStates(TagEncoder encoder) throws IOException {
        byte[] bytes = this.encodedBlockStates;
        if (bytes == null) this.encodedBlockStates = bytes = encoder.encode(this.blockStates);
        return bytes;
    }

    /** The biomes as {@code encoder} writes them, encoded once for the life of this section. Never write the array. */
    public byte[] encodedBiome(TagEncoder encoder) throws IOException {
        byte[] bytes = this.encodedBiome;
        if (bytes == null) this.encodedBiome = bytes = encoder.encode(this.biome);
        return bytes;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SlimeChunkSectionSkeleton section
                && Objects.equals(this.blockStates, section.blockStates) && Objects.equals(this.biome, section.biome)
                && Objects.equals(this.block, section.block) && Objects.equals(this.light, section.light);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.blockStates, this.biome, this.block, this.light);
    }

    @Override
    public String toString() {
        return "SlimeChunkSectionSkeleton[blockStates=" + this.blockStates + ", biome=" + this.biome
                + ", block=" + this.block + ", light=" + this.light + "]";
    }
}
