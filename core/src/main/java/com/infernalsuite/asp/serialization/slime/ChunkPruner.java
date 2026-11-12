package com.infernalsuite.asp.serialization.slime;

import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import net.kyori.adventure.nbt.*;

import java.util.List;

public class ChunkPruner {

    private static final String CHUNK_PDC = "ChunkBukkitValues";

    public static boolean canBePruned(SlimeWorld world, SlimeChunk chunk) {
        return canBePruned(world, chunk, chunk::getSections);
    }

    /** BTC-CORE: the same test, reading the sections through {@code sections} - a save that already encoded them hands them over. */
    static boolean canBePruned(SlimeWorld world, SlimeChunk chunk, java.util.function.Supplier<SlimeChunkSection[]> sections) {
        SlimePropertyMap propertyMap = world.getPropertyMap();
        if (propertyMap.getValue(SlimeProperties.SHOULD_LIMIT_SAVE)) {
            int minX = propertyMap.getValue(SlimeProperties.SAVE_MIN_X);
            int maxX = propertyMap.getValue(SlimeProperties.SAVE_MAX_X);

            int minZ = propertyMap.getValue(SlimeProperties.SAVE_MIN_Z);
            int maxZ = propertyMap.getValue(SlimeProperties.SAVE_MAX_Z);

            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();

            if (chunkX < minX || chunkX > maxX) {
                return true;
            }

            if (chunkZ < minZ || chunkZ > maxZ) {
                return true;
            }
        }

        String pruningSetting = world.getPropertyMap().getValue(SlimeProperties.CHUNK_PRUNING);
        if (pruningSetting.equals("aggressive")) {
            return chunk.getTileEntities().isEmpty() && chunk.getEntities().isEmpty() && !holdsPluginData(chunk.getExtraData()) && areSectionsEmpty(sections.get());
        }

        return false;
    }

    public static boolean canBePruned(SlimeChunk chunk) {
        return chunk.getTileEntities().isEmpty() && chunk.getEntities().isEmpty() && !holdsPluginData(chunk.getExtraData()) && areSectionsEmpty(chunk.getSections());
    }

    /**
     * BTC-CORE: whether a plugin stored something on the chunk - any entry of {@code extra} but an
     * empty PDC. A chunk of air that holds some is content, and pruning it would lose it.
     */
    public static boolean holdsPluginData(java.util.Map<String, BinaryTag> extra) {
        if (extra == null) return false;
        for (java.util.Map.Entry<String, BinaryTag> entry : extra.entrySet()) {
            boolean emptyPdc = CHUNK_PDC.equals(entry.getKey()) && entry.getValue() instanceof CompoundBinaryTag pdc && pdc.isEmpty();
            if (!emptyPdc) return true;
        }
        return false;
    }

    //  TAG_List("palette"): 1 entries of type TAG_Compound
    //[13:15:06 INFO]:    {
    //[13:15:06 INFO]:       TAG_Compound: 1 entries
    //[13:15:06 INFO]:       {
    //[13:15:06 INFO]:          TAG_String("Name"): minecraft:air
    //[13:15:06 INFO]:       }
    private static boolean areSectionsEmpty(SlimeChunkSection[] sections) {
        for (SlimeChunkSection chunkSection : sections) {
            try {
                ListBinaryTag paletteTag = chunkSection.getBlockStatesTag().getList("palette");
                if (paletteTag.size() > 1) return false; // If there is more than one palette, the section is not empty

                if (!paletteTag.isEmpty()) {
                    // If the only palette entry is not air, the section is not empty
                    BinaryTag first = paletteTag.get(0);
                    if(first instanceof StringBinaryTag tag && !tag.value().equals("minecraft:air")) return false;
                    if(first instanceof CompoundBinaryTag tag && !tag.getString("Name").equals("minecraft:air")) return false; // BTC-CORE: a block state is named by "Name"; "id" never matched, so no section was ever empty
                }
            } catch (final Exception e) {
                return false;
            }
            // The section is empty, continue to the next one
        }
        // All sections are empty, we can omit this chunk
        return true;
    }

}
