package dev.btc.core.slime;

import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.skeleton.SkeletonCloning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

import static dev.btc.core.slime.SlimeFixtures.PLAINS;
import static dev.btc.core.slime.SlimeFixtures.STONE;
import static dev.btc.core.slime.SlimeFixtures.biomes;
import static dev.btc.core.slime.SlimeFixtures.blockStates;
import static dev.btc.core.slime.SlimeFixtures.chunk;
import static dev.btc.core.slime.SlimeFixtures.keepEverything;
import static dev.btc.core.slime.SlimeFixtures.light;
import static dev.btc.core.slime.SlimeFixtures.section;
import static dev.btc.core.slime.SlimeFixtures.world;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code slime-world.clone.share-sections} saves on the clone of a dungeon-sized template: the
 * bytes one clone allocates, off and on. Replaces a long bench; only the allocation is asserted, the
 * time is printed.
 */
class SkeletonCloneCostTest {

    private static final int SIDE = 20; // 20 x 20 = 400 chunks
    private static final int SECTIONS = 24;
    private static final int FILLED_SECTIONS = 4;
    private static final int WARM_UP = 5;
    private static final int RUNS = 11;
    private static final double MAX_RATIO = 0.1;
    private static final double MB = 1024.0 * 1024.0;

    @AfterEach
    void restore() {
        SkeletonCloning.shareSections = false;
    }

    private static SlimeWorld dungeon() {
        SlimeChunk[] chunks = new SlimeChunk[SIDE * SIDE];
        int seed = 0;
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                SlimeChunkSection[] sections = new SlimeChunkSection[SECTIONS];
                for (int y = 0; y < SECTIONS; y++) {
                    String[] blocks = y < FILLED_SECTIONS ? new String[]{STONE, "minecraft:deepslate", "minecraft:torch"} : new String[]{SlimeFixtures.AIR};
                    sections[y] = section(blockStates(blocks), biomes(PLAINS), light(seed++), light(seed++));
                }
                chunks[x * SIDE + z] = chunk(x, z).sections(sections).build();
            }
        }
        return world(keepEverything(), chunks);
    }

    /** Median bytes and nanoseconds of one clone. */
    private static long[] measure(SlimeWorld source, boolean share) {
        SkeletonCloning.shareSections = share;
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (int i = 0; i < WARM_UP; i++) source.clone("warm" + i);
        long[] bytes = new long[RUNS];
        long[] nanos = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long allocated = threads.getCurrentThreadAllocatedBytes();
            long start = System.nanoTime();
            source.clone("c" + i);
            nanos[i] = System.nanoTime() - start;
            bytes[i] = threads.getCurrentThreadAllocatedBytes() - allocated;
        }
        Arrays.sort(bytes);
        Arrays.sort(nanos);
        return new long[]{bytes[RUNS / 2], nanos[RUNS / 2]};
    }

    @Test
    @DisplayName("sharing the sections allocates less than a tenth of copying them, on a 400-chunk template")
    void sharingAllocatesATenthOrLess() {
        SlimeWorld dungeon = dungeon();
        long[] off = measure(dungeon, false);
        long[] on = measure(dungeon, true);

        System.out.printf("clone %d chunks : off %.1f Mo %.1f ms | on %.2f Mo %.1f ms%n",
            SIDE * SIDE, off[0] / MB, off[1] / 1e6, on[0] / MB, on[1] / 1e6);
        assertTrue(on[0] < MAX_RATIO * off[0], "on " + on[0] + " B, off " + off[0] + " B");
    }
}
