package dev.btc.core.performance;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Which blocks of one world hide what stands behind them, for the threads that may not read the
 * world ({@code performance.raytrace-culling}).
 *
 * <p>One per world. The thread of the world writes it: the sections it indexes, the blocks that
 * change. Any thread reads it. A section nobody indexed yet hides nothing, so a block the index
 * does not know never hides an entity; the miss is remembered and the world indexes the section on
 * a later tick.
 *
 * <p>Indexed sections are read again in turn, a few per tick: a section written without
 * {@code LevelChunk#setBlockState} (a schematic pasted section by section) or dropped with its
 * chunk is put right on the next pass instead of staying wrong.
 */
public final class OpacityIndex {

    private static final int SECTION_SHIFT = 4;
    private static final int SECTION_MASK = 15;
    private static final int WORD_SHIFT = 6;
    /** Longs holding one bit per block of a section. */
    public static final int WORDS = 1 << (3 * SECTION_SHIFT - WORD_SHIFT);

    private static final int XZ_BITS = 22;
    private static final int Y_BITS = 20;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;

    /** Share of {@link #due} left to the sections read again, the misses being served first. */
    private static final int REFRESH_SHARE = 4;

    /** A section without one opaque block. */
    private static final AtomicLongArray NOTHING_OPAQUE = new AtomicLongArray(0);

    private final ConcurrentHashMap<Long, AtomicLongArray> sections = new ConcurrentHashMap<>();
    private final Set<Long> missed = ConcurrentHashMap.newKeySet();
    private Iterator<Long> refresh = sections.keySet().iterator(); // thread of the world only

    public static long key(int sectionX, int sectionY, int sectionZ) {
        return (sectionX & XZ_MASK) << (XZ_BITS + Y_BITS) | (sectionZ & XZ_MASK) << Y_BITS | (sectionY & Y_MASK);
    }

    /** Key of the section holding a block. */
    public static long keyAt(int x, int y, int z) {
        return key(x >> SECTION_SHIFT, y >> SECTION_SHIFT, z >> SECTION_SHIFT);
    }

    public static int sectionX(long key) {
        return (int) (key >> (XZ_BITS + Y_BITS));
    }

    public static int sectionZ(long key) {
        return (int) (key << XZ_BITS >> (XZ_BITS + Y_BITS));
    }

    public static int sectionY(long key) {
        return (int) (key << (2 * XZ_BITS) >> (2 * XZ_BITS));
    }

    /** Bit of a block in its section; the coordinates may be those of the world. */
    public static int bit(int x, int y, int z) {
        return (y & SECTION_MASK) << (2 * SECTION_SHIFT) | (z & SECTION_MASK) << SECTION_SHIFT | (x & SECTION_MASK);
    }

    /** Marks a block opaque in bits being built for {@link #index}. */
    public static void mark(long[] opaqueBits, int x, int y, int z) {
        int bit = bit(x, y, z);
        opaqueBits[bit >> WORD_SHIFT] |= 1L << bit;
    }

    /** Any thread. */
    public boolean opaque(int x, int y, int z) {
        return opaque(this.section(keyAt(x, y, z)), x, y, z);
    }

    /** Any thread: the bits of a section, null (and remembered as missed) when it is not indexed. */
    @Nullable AtomicLongArray section(long key) {
        AtomicLongArray bits = this.sections.get(key);
        if (bits == null) {
            this.missed.add(key);
        }
        return bits;
    }

    static boolean opaque(@Nullable AtomicLongArray bits, int x, int y, int z) {
        if (bits == null || bits == NOTHING_OPAQUE) {
            return false;
        }
        int bit = bit(x, y, z);
        return (bits.get(bit >> WORD_SHIFT) & 1L << bit) != 0;
    }

    /** Thread of the world: the section as it stands, null bits when no block of it is opaque. */
    public void index(long key, long @Nullable [] opaqueBits) {
        this.sections.put(key, opaqueBits == null ? NOTHING_OPAQUE : new AtomicLongArray(opaqueBits));
    }

    /** Thread of the world: the section is not there any more (its chunk is unloaded). */
    public void forget(long key) {
        this.sections.remove(key);
    }

    /** Thread of the world. A section not indexed yet is left alone: it is read when it is asked for. */
    public void blockChanged(int x, int y, int z, boolean opaque) {
        long key = keyAt(x, y, z);
        AtomicLongArray bits = this.sections.get(key);
        if (bits == null || bits == NOTHING_OPAQUE && !opaque) {
            return;
        }
        int bit = bit(x, y, z);
        int word = bit >> WORD_SHIFT;
        long mask = 1L << bit;
        if (bits == NOTHING_OPAQUE) {
            bits = new AtomicLongArray(WORDS);
            bits.set(word, mask);
            this.sections.put(key, bits);
            return;
        }
        long old = bits.get(word); // one writer, the thread of the world
        bits.set(word, opaque ? old | mask : old & ~mask);
    }

    /**
     * Thread of the world: up to {@code max} sections to read now. The misses first; what they
     * leave, and one in {@value #REFRESH_SHARE} of {@code max} whatever they ask, goes to indexed
     * sections in turn.
     */
    public long[] due(int max) {
        long[] keys = new long[max];
        int count = 0;
        int forMisses = max - max / REFRESH_SHARE;
        for (Iterator<Long> it = this.missed.iterator(); count < forMisses && it.hasNext(); ) {
            keys[count++] = it.next();
            it.remove();
        }
        boolean restarted = false;
        while (count < max) {
            if (!this.refresh.hasNext()) {
                if (restarted) {
                    break; // nothing indexed
                }
                this.refresh = this.sections.keySet().iterator();
                restarted = true;
                continue;
            }
            keys[count++] = this.refresh.next();
        }
        return Arrays.copyOf(keys, count);
    }
}
