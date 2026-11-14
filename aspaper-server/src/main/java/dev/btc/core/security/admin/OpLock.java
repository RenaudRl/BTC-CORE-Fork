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
 * player command event, which is the only one {@link #commandVerdict} decides on. What they grant
 * outside the list is removed by the next sweep, which is the point of an invariant.
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

    /** The same 128 bits without dashes, as Mojang's profile API and many tools print them. */
    private static final java.util.regex.Pattern UNDASHED_UUID = java.util.regex.Pattern.compile("[0-9a-fA-F]{32}");

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
            final String text = entry == null ? "" : entry.trim();
            if (CANONICAL_UUID.matcher(text).matches()) {
                uuids.add(UUID.fromString(text));
            } else if (UNDASHED_UUID.matcher(text).matches()) {
                uuids.add(UUID.fromString(text.replaceFirst(
                    "(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5")));
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
     * Whether a command line typed by a player reaches {@code /op} or {@code /deop}, as the server's command tree
     * reads it: every redirect is followed ({@code /execute ... run}, {@code /return run}, a namespaced alias), and
     * a command reached that way is an op command when its literal is {@code op} or {@code deop} under any
     * namespace. Empty when the tree knows nothing of the line (no node matched its first word): the caller then
     * falls back on {@link #isOpCommand(String)}.
     */
    public static <S> Optional<Boolean> resolvedOpCommand(final com.mojang.brigadier.CommandDispatcher<S> dispatcher,
                                                          final S source, final String rawMessage) {
        final com.mojang.brigadier.ParseResults<S> parse = dispatcher.parse(withoutSlash(rawMessage.strip()), source);
        com.mojang.brigadier.context.CommandContextBuilder<S> context = parse.getContext();
        if (context.getNodes().isEmpty()) {
            return Optional.empty();
        }
        for (; context != null; context = context.getChild()) {
            if (!context.getNodes().isEmpty()
                && context.getNodes().getFirst().getNode() instanceof com.mojang.brigadier.tree.LiteralCommandNode<S> literal
                && isOpLabel(literal.getLiteral())) {
                return Optional.of(true);
            }
        }
        return Optional.of(false);
    }

    /**
     * Whether a command line typed by a player is {@code /op} or {@code /deop}, with or without a
     * namespace ({@code /minecraft:op}, {@code /bukkit:deop}), in any case, or reached through
     * {@code /execute ... run op} or {@code /return run op}. {@code /opinion}, {@code /top} and {@code /oper} are
     * other commands. Read from the text alone: {@link #resolvedOpCommand} is the reading of the command tree.
     */
    public static boolean isOpCommand(final String rawMessage) {
        final String line = withoutSlash(rawMessage.strip());
        final String[] words = line.split("\\s+");
        if (isOpLabel(words[0])) {
            return true;
        }
        // "/execute ... run op x": any word "run" followed by op or deop, whatever the clauses before it.
        // shortcut: "/execute run say run op" is read as an op command too; the command tree reading has no such case.
        final String base = baseName(words[0]);
        if (!base.equals("execute") && !base.equals("return")) {
            return false;
        }
        for (int i = 1; i + 1 < words.length; i++) {
            if (words[i].equals("run") && isOpLabel(words[i + 1])) {
                return true;
            }
        }
        return false;
    }

    /** The longest label {@link #commandLabel} returns: a log cadence, not a gameplay value. */
    public static final int MAX_LABEL_LENGTH = 64;

    /**
     * The command label of a typed line, without its slash or any argument, capped at {@link #MAX_LABEL_LENGTH}:
     * what a log may say about a line that can carry a password ({@code /login}, {@code /register}).
     */
    public static String commandLabel(final String rawMessage) {
        final String label = withoutSlash(rawMessage.strip()).split("\\s+", 2)[0];
        return label.length() > MAX_LABEL_LENGTH ? label.substring(0, MAX_LABEL_LENGTH) : label;
    }

    private static String withoutSlash(final String line) {
        return line.startsWith("/") ? line.substring(1) : line;
    }

    private static String baseName(final String label) {
        return label.substring(label.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isOpLabel(final String label) {
        final String name = baseName(label);
        return name.equals("op") || name.equals("deop");
    }

    /** Why a command typed by a player is reported. */
    public enum Reason {
        /** Nothing to report. */
        NONE,
        /** The player is an op and is not in the allowed list: whatever they type, the invariant is broken. */
        OP_OUTSIDE_LIST,
        /** The player typed {@code /op} or {@code /deop}. */
        OP_COMMAND
    }

    /**
     * What to do about one command line: report it for {@code reason}, cancel it, and remove the player's op.
     * {@code opCommand}: the line reaches {@code /op} or {@code /deop}, whatever the reason reported.
     */
    public record CommandVerdict(Reason reason, boolean cancel, boolean revokeOp, boolean opCommand) {
        static final CommandVerdict NOTHING = new CommandVerdict(Reason.NONE, false, false, false);

        public boolean journals() {
            return reason != Reason.NONE;
        }
    }

    /**
     * The decision for a command line typed by a player. It only exists for a player: the console and
     * RCON do not raise the player command event, so they are never refused (what they grant outside the
     * list is removed by the next sweep).
     *
     * <p>An op outside the list gets their op removed at once, whatever they type: waiting for the next
     * sweep leaves a window in which the command that follows runs with the op. Only {@link Mode#ENFORCE}
     * cancels, removes, or refuses {@code /op}/{@code /deop}; {@link Mode#OBSERVE} only reports.
     *
     * <p>{@code /op} and {@code /deop} are only reported or refused for a player who could run them: an op,
     * or one holding {@code minecraft.command.op} or {@code minecraft.command.deop}
     * ({@code mayRunOpCommands}). The server already refuses everyone else, and a line per refused
     * attempt would bury the real ones.
     */
    public static CommandVerdict commandVerdict(final Mode mode, final boolean isOp, final boolean mayRunOpCommands,
                                                final UUID player, final Set<UUID> allowed, final String rawMessage) {
        return commandVerdict(mode, isOp, mayRunOpCommands, player, allowed, isOpCommand(rawMessage));
    }

    /** As above, with whether the line reaches {@code /op} or {@code /deop} already known ({@link #resolvedOpCommand}). */
    public static CommandVerdict commandVerdict(final Mode mode, final boolean isOp, final boolean mayRunOpCommands,
                                                final UUID player, final Set<UUID> allowed, final boolean opCommand) {
        if (mode == Mode.OFF) {
            return CommandVerdict.NOTHING;
        }
        final boolean enforce = mode == Mode.ENFORCE;
        if (isOp && !allowed.contains(player)) {
            return new CommandVerdict(Reason.OP_OUTSIDE_LIST, enforce, enforce, opCommand);
        }
        if (mayRunOpCommands && opCommand) {
            return new CommandVerdict(Reason.OP_COMMAND, enforce, false, true);
        }
        return CommandVerdict.NOTHING;
    }
}
