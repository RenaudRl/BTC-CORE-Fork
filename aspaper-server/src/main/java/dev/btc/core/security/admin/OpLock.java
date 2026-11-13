package dev.btc.core.security.admin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The decisions of the op lock. Pure: no server, no Bukkit type, no I/O.
 *
 * <p>The lock keeps one invariant, <em>ops are a subset of the allowed list</em>, whatever the path
 * that made someone an op: {@code /op}, {@code /execute run op}, a command alias, a plugin calling
 * {@code setOp}, an edited {@code ops.json}. It is not a command filter, which each of those paths
 * would get around. It can only ever <em>remove</em> an op: the allowed list is an upper bound, never a
 * list of players to promote.
 *
 * <p>The console and RCON are the authority and are never blocked: they do not pass through the
 * player command event, and {@link #refusesCommand} says so on its own. What they grant outside the
 * list is removed by the next sweep, which is the point of an invariant.
 *
 * <p>An empty allowed list in {@link Mode#ENFORCE} means <em>no op is allowed</em>. The other reading
 * ("empty = lock inactive") turns an accidentally emptied list into a silently disabled lock.
 */
public final class OpLock {

    /** What the lock does about an op outside the allowed list. */
    public enum Mode {
        /** Nothing: no sweep, no command check. The delivered default. */
        OFF,
        /** Reports an op outside the list and a refused command, removes and cancels nothing. */
        OBSERVE,
        /** Removes ops outside the list and cancels {@code /op} and {@code /deop} played by a player. */
        ENFORCE;

        /** The configured value, case-insensitively; empty when it is none of the three. */
        public static Optional<Mode> parse(final String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "off" -> Optional.of(OFF);
                case "observe" -> Optional.of(OBSERVE);
                case "enforce" -> Optional.of(ENFORCE);
                default -> Optional.empty();
            };
        }
    }

    /** The allowed list as read from configuration, and the entries that were not UUIDs. */
    public record Allowed(Set<UUID> uuids, List<String> invalid) {}

    private static final java.util.regex.Pattern CANONICAL_UUID =
        java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private OpLock() {}

    /**
     * Reads the configured UUIDs. A malformed entry is reported and ignored, never thrown: this runs at
     * start-up, and a typo must not stop the server. Ignoring it can only remove an op, which is the
     * safe direction of this lock.
     */
    public static Allowed parseAllowed(final List<String> raw) {
        final Set<UUID> uuids = new HashSet<>();
        final List<String> invalid = new ArrayList<>();
        for (final String entry : raw) {
            // UUID.fromString alone accepts shapes like "1-1-1-1-1": the canonical form is required.
            if (entry != null && CANONICAL_UUID.matcher(entry.trim()).matches()) {
                uuids.add(UUID.fromString(entry.trim()));
            } else {
                invalid.add(String.valueOf(entry));
            }
        }
        return new Allowed(Set.copyOf(uuids), List.copyOf(invalid));
    }

    /** The ops that are not in the allowed list: what {@link Mode#OBSERVE} reports. Empty when off. */
    public static Set<UUID> outsideTheList(final Set<UUID> ops, final Set<UUID> allowed, final Mode mode) {
        if (mode == Mode.OFF) {
            return Set.of();
        }
        final Set<UUID> outside = new HashSet<>(ops);
        outside.removeAll(allowed);
        return Set.copyOf(outside);
    }

    /**
     * The ops to remove. Only ever a subset of {@code ops}: nobody is promoted, and only
     * {@link Mode#ENFORCE} removes anyone.
     */
    public static Set<UUID> revocations(final Set<UUID> ops, final Set<UUID> allowed, final Mode mode) {
        return mode == Mode.ENFORCE ? outsideTheList(ops, allowed, mode) : Set.of();
    }

    /**
     * Whether a command line typed by a player is {@code /op} or {@code /deop}, with or without a
     * namespace ({@code /minecraft:op}, {@code /bukkit:deop}), in any case. {@code /opinion},
     * {@code /top} and {@code /oper} are other commands.
     */
    public static boolean isOpCommand(final String rawMessage) {
        String label = rawMessage.strip();
        if (label.startsWith("/")) {
            label = label.substring(1);
        }
        final int space = label.indexOf(' ');
        if (space >= 0) {
            label = label.substring(0, space);
        }
        label = label.substring(label.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
        return label.equals("op") || label.equals("deop");
    }

    /**
     * Whether a command line must be cancelled. Only a player's, only in {@link Mode#ENFORCE}: the
     * console and RCON are never refused.
     */
    public static boolean refusesCommand(final Mode mode, final boolean senderIsPlayer, final String rawMessage) {
        return mode == Mode.ENFORCE && senderIsPlayer && isOpCommand(rawMessage);
    }

    /** Whether a command line should be reported: a player's op command, in any mode but off. */
    public static boolean reportsCommand(final Mode mode, final boolean senderIsPlayer, final String rawMessage) {
        return mode != Mode.OFF && senderIsPlayer && isOpCommand(rawMessage);
    }
}
