package dev.btc.core.playerdata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Which players the save of ONE Slime world should be aligned with (task 5.1). One instance lives in
 * each world, so what it remembers goes when the world does.
 *
 * <p>A world and the players who handled its items are written to two files at two moments: an abrupt
 * stop between the two rolls one back and not the other, a loss or a duplicate. Both cannot be made
 * atomic; what can be done is to take their snapshots at the same tick. This class decides <em>who</em>:
 * the players in the world when it is saved, and those who left it since its previous save. It saves
 * nothing and touches no world: in {@link Mode#OBSERVE} the caller only logs the plan, which makes an
 * incident readable (which gap was open) without changing the game.
 *
 * <p>Thread-safe: a world is saved, and a player changes world, on different threads.
 */
public final class SaveAlignment {

    /** {@code mechanics.save-alignment.mode}. Saving the players in the same pass (enforce) is not part of this class. */
    public enum Mode {
        /** Nothing is observed. */
        OFF,
        /** The plan of each world save is computed and logged; nothing is saved or changed. */
        OBSERVE;

        /** The configured value, case-insensitively; empty when it is neither. */
        public static Optional<Mode> parse(final String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "off" -> Optional.of(OFF);
                case "observe" -> Optional.of(OBSERVE);
                default -> Optional.empty();
            };
        }
    }

    /** What the caller knows about a player, asked on the thread that saves the world. */
    public interface Probe {
        /** Not a player NPC: only a persistent player is written to disk. */
        boolean persistent(UUID player);

        /** Still on the server: someone who left has been saved by the disconnection. */
        boolean online(UUID player);

        /** The calling thread owns this player: only then may it be saved at once. */
        boolean ownedHere(UUID player);
    }

    /** The players of one world save, split by what the calling thread may do with them. */
    public record Plan(List<UUID> inline, List<UUID> scheduled) {

        public int size() {
            return this.inline.size() + this.scheduled.size();
        }

        /** The line the caller logs: how many players are to be aligned, and when. */
        public String describe(final String world) {
            return this.size() + " player(s) to align with the save of world " + world + ": "
                + this.inline.size() + " now, " + this.scheduled.size() + " scheduled";
        }
    }

    private static final long NEVER = Long.MIN_VALUE;

    private final LongSupplier nanoClock;
    private final Set<UUID> leavers = ConcurrentHashMap.newKeySet();
    private volatile long lastSaveNanos = NEVER;

    public SaveAlignment(final LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** A player left this world for another one: its next save should be aligned with them. */
    public void onLeft(final UUID player) {
        this.leavers.add(player);
    }

    /**
     * The world is being saved: who is to be aligned with it. The leavers are consumed, so that a
     * player is not selected again by every later save.
     *
     * @param presents the players in the world right now
     */
    public Plan onWorldSaved(final Collection<UUID> presents, final Probe probe) {
        this.lastSaveNanos = this.nanoClock.getAsLong();
        final Set<UUID> selected = new LinkedHashSet<>(presents);
        for (final UUID gone : List.copyOf(this.leavers)) {
            if (this.leavers.remove(gone)) {
                selected.add(gone); // a set: someone who left and came back is one player
            }
        }
        final List<UUID> inline = new ArrayList<>();
        final List<UUID> scheduled = new ArrayList<>();
        for (final UUID player : selected) {
            if (!probe.online(player) || !probe.persistent(player)) {
                continue; // offline: saved when they disconnected. Not persistent: never written.
            }
            (probe.ownedHere(player) ? inline : scheduled).add(player);
        }
        return new Plan(List.copyOf(inline), List.copyOf(scheduled));
    }

    /**
     * The players whose state would be rolled back against the world if it were dropped unsaved now:
     * those in it, and those who left since its last save. Reads, consumes nothing.
     */
    public Set<UUID> exposedBy(final Collection<UUID> presents) {
        final Set<UUID> exposed = new LinkedHashSet<>(presents);
        exposed.addAll(this.leavers);
        return exposed;
    }

    /** Whole seconds since the last save this instance saw; empty when it saw none. */
    public OptionalLong secondsSinceLastSave() {
        final long at = this.lastSaveNanos;
        return at == NEVER ? OptionalLong.empty()
            : OptionalLong.of(TimeUnit.NANOSECONDS.toSeconds(this.nanoClock.getAsLong() - at));
    }
}
