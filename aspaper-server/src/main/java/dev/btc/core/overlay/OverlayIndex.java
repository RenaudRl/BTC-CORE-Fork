package dev.btc.core.overlay;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMaps;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The cells of one player's overlay, indexed by chunk section, with no Minecraft type in it so the
 * logic is tested without a server.
 *
 * <p>Sections are keyed by the same packing as {@code SectionPos.asLong}, cells by the same packing as
 * {@code SectionPos.sectionRelativePos}. Every write builds new section objects and publishes a new
 * map through one volatile field: readers on any thread see a complete snapshot without locking, and
 * a section test is one hash lookup. There must be a single writer (the player's owning thread).
 *
 * @param <S> the shown state type ({@code BlockState} on the server)
 */
public final class OverlayIndex<S> {

    /** One section's covered cells. Never mutated once published. */
    public static final class Section<S> {
        private final Short2ObjectOpenHashMap<S> states;
        private final ShortOpenHashSet solid;

        private Section(final Short2ObjectOpenHashMap<S> states, final ShortOpenHashSet solid) {
            this.states = states;
            this.solid = solid;
        }

        public @Nullable S get(final short local) {
            return this.states.get(local);
        }

        public boolean isSolid(final short local) {
            return this.solid.contains(local);
        }

        public int size() {
            return this.states.size();
        }

        /** @return a read-only view of the cells, local position to shown state */
        public Short2ObjectMap<S> states() {
            return Short2ObjectMaps.unmodifiable(this.states);
        }
    }

    private record Snapshot<S>(Long2ObjectOpenHashMap<Section<S>> sections, int size) {
    }

    private volatile Snapshot<S> snapshot = new Snapshot<>(new Long2ObjectOpenHashMap<>(), 0);

    public boolean isEmpty() {
        return this.snapshot.size() == 0;
    }

    public int size() {
        return this.snapshot.size();
    }

    public @Nullable Section<S> section(final long sectionKey) {
        return this.snapshot.sections().get(sectionKey);
    }

    public @Nullable S get(final long sectionKey, final short local) {
        final Section<S> section = this.section(sectionKey);
        return section == null ? null : section.get(local);
    }

    public boolean isSolid(final long sectionKey, final short local) {
        final Section<S> section = this.section(sectionKey);
        return section != null && section.isSolid(local);
    }

    /**
     * Covers cells with a state and a solid flag.
     *
     * @param cells section key to (local position to state)
     * @return per section, the cells whose shown state changed, with their new state; a cell whose
     *         state is unchanged is left out even when its solid flag changed
     */
    public Long2ObjectMap<Short2ObjectMap<S>> put(final Long2ObjectMap<? extends Short2ObjectMap<S>> cells,
                                                 final boolean solid) {
        final Snapshot<S> current = this.snapshot;
        final Long2ObjectOpenHashMap<Section<S>> next = new Long2ObjectOpenHashMap<>(current.sections());
        final Long2ObjectMap<Short2ObjectMap<S>> changed = new Long2ObjectOpenHashMap<>();
        int size = current.size();
        for (final Long2ObjectMap.Entry<? extends Short2ObjectMap<S>> entry : cells.long2ObjectEntrySet()) {
            final Section<S> old = next.get(entry.getLongKey());
            final Short2ObjectOpenHashMap<S> states = old == null ? new Short2ObjectOpenHashMap<>() : new Short2ObjectOpenHashMap<>(old.states);
            final ShortOpenHashSet solids = old == null ? new ShortOpenHashSet() : new ShortOpenHashSet(old.solid);
            final Short2ObjectOpenHashMap<S> sectionChanges = new Short2ObjectOpenHashMap<>();
            for (final Short2ObjectMap.Entry<S> cell : entry.getValue().short2ObjectEntrySet()) {
                final short local = cell.getShortKey();
                final S state = Objects.requireNonNull(cell.getValue(), "state");
                final S previous = states.put(local, state);
                if (previous == null) {
                    size++;
                }
                if (!state.equals(previous)) {
                    sectionChanges.put(local, state);
                }
                if (solid) {
                    solids.add(local);
                } else {
                    solids.remove(local);
                }
            }
            next.put(entry.getLongKey(), new Section<>(states, solids));
            if (!sectionChanges.isEmpty()) {
                changed.put(entry.getLongKey(), sectionChanges);
            }
        }
        this.snapshot = new Snapshot<>(next, size);
        return changed;
    }

    /**
     * Uncovers cells.
     *
     * @param cells section key to local positions
     * @return per section, the positions that were covered and no longer are
     */
    public Long2ObjectMap<ShortSet> remove(final Long2ObjectMap<? extends ShortSet> cells) {
        final Snapshot<S> current = this.snapshot;
        final Long2ObjectOpenHashMap<Section<S>> next = new Long2ObjectOpenHashMap<>(current.sections());
        final Long2ObjectMap<ShortSet> removed = new Long2ObjectOpenHashMap<>();
        int size = current.size();
        for (final Long2ObjectMap.Entry<? extends ShortSet> entry : cells.long2ObjectEntrySet()) {
            final Section<S> old = next.get(entry.getLongKey());
            if (old == null) {
                continue;
            }
            final Short2ObjectOpenHashMap<S> states = new Short2ObjectOpenHashMap<>(old.states);
            final ShortOpenHashSet solids = new ShortOpenHashSet(old.solid);
            final ShortOpenHashSet gone = new ShortOpenHashSet();
            for (final short local : entry.getValue()) {
                if (states.remove(local) != null) {
                    solids.remove(local);
                    gone.add(local);
                }
            }
            if (gone.isEmpty()) {
                continue;
            }
            size -= gone.size();
            removed.put(entry.getLongKey(), gone);
            if (states.isEmpty()) {
                next.remove(entry.getLongKey());
            } else {
                next.put(entry.getLongKey(), new Section<>(states, solids));
            }
        }
        this.snapshot = new Snapshot<>(next, size);
        return removed;
    }

    /** @return per section, every position that was covered */
    public Long2ObjectMap<ShortSet> clear() {
        final Snapshot<S> current = this.snapshot;
        final Long2ObjectMap<ShortSet> removed = new Long2ObjectOpenHashMap<>();
        for (final Long2ObjectMap.Entry<Section<S>> entry : current.sections().long2ObjectEntrySet()) {
            removed.put(entry.getLongKey(), new ShortOpenHashSet(entry.getValue().states.keySet()));
        }
        this.snapshot = new Snapshot<>(new Long2ObjectOpenHashMap<>(), 0);
        return removed;
    }

    // ------------------------------------------------------------------ keys

    /** Same packing as {@code SectionPos.asLong(int, int, int)}. */
    public static long sectionKey(final int sectionX, final int sectionY, final int sectionZ) {
        return ((sectionX & 4194303L) << 42) | (sectionY & 1048575L) | ((sectionZ & 4194303L) << 20);
    }

    public static long sectionKeyOfBlock(final int x, final int y, final int z) {
        return sectionKey(x >> 4, y >> 4, z >> 4);
    }

    public static int sectionX(final long key) {
        return (int) (key >> 42);
    }

    public static int sectionY(final long key) {
        return (int) (key << 44 >> 44);
    }

    public static int sectionZ(final long key) {
        return (int) (key << 22 >> 42);
    }

    /** Same packing as {@code SectionPos.sectionRelativePos(BlockPos)}. */
    public static short local(final int x, final int y, final int z) {
        return (short) ((x & 15) << 8 | (z & 15) << 4 | y & 15);
    }

    public static int blockX(final long key, final short local) {
        return (sectionX(key) << 4) + (local >>> 8 & 15);
    }

    public static int blockY(final long key, final short local) {
        return (sectionY(key) << 4) + (local & 15);
    }

    public static int blockZ(final long key, final short local) {
        return (sectionZ(key) << 4) + (local >>> 4 & 15);
    }

    /** Groups one cell into a section-keyed batch for {@link #put}. */
    public static <S> void addCell(final Long2ObjectMap<Short2ObjectMap<S>> batch,
                                   final int x, final int y, final int z, final S state) {
        batch.computeIfAbsent(sectionKeyOfBlock(x, y, z), key -> new Short2ObjectOpenHashMap<>())
            .put(local(x, y, z), state);
    }

    /** Groups one cell into a section-keyed batch for {@link #remove}. */
    public static void addPosition(final Long2ObjectMap<ShortSet> batch, final int x, final int y, final int z) {
        batch.computeIfAbsent(sectionKeyOfBlock(x, y, z), key -> new ShortOpenHashSet()).add(local(x, y, z));
    }
}
