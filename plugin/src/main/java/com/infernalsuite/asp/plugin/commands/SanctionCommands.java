package com.infernalsuite.asp.plugin.commands;

import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.integrity.sanction.ActorKind;
import dev.btc.core.integrity.sanction.Sanction;
import dev.btc.core.integrity.sanction.SanctionDuration;
import dev.btc.core.integrity.sanction.SanctionDuration.Parsed;
import dev.btc.core.integrity.sanction.SanctionScope;
import dev.btc.core.integrity.sanction.SanctionService;
import dev.btc.core.integrity.sanction.SanctionType;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The moderation commands: {@code /warn}, {@code /note}, {@code /mute}, {@code /unmute},
 * {@code /kick}, {@code /ban}, {@code /unban} and {@code /history}.
 *
 * <p>They live in the plugin module for the same classloader reason as
 * {@link BTCCoreDebugCommand}: a {@link BasicCommand} implementation registered from {@code SWPlugin}
 * must be owned by the plugin classloader, or registration fails with an
 * {@code IncompatibleClassChangeError}.
 *
 * <p>Three rules are enforced here rather than left to habit.
 *
 * <ol>
 *   <li><b>A reason is mandatory.</b> Not defaulted, not optional. A moderation history whose entries
 *       say nothing cannot be reviewed, and an appeal against "no reason given" cannot be answered.</li>
 *   <li><b>Every command is a human decision.</b> These carry {@link ActorKind#HUMAN}; the anticheat
 *       has no path to them. That is what makes "the anticheat never bans" checkable rather than
 *       promised.</li>
 *   <li><b>Failure is reported.</b> When the store is unavailable the moderator is told the sanction
 *       was <em>not</em> recorded. Silently accepting a ban that no database ever saw is how a player
 *       comes back an hour later and nobody understands why.</li>
 * </ol>
 */
public final class SanctionCommands {

    /**
     * Identity recorded when the console decides.
     *
     * <p>The schema requires a human decision to name its author, and the console has no account. A
     * fixed, documented identity is honest about that: the row says "the console did this", rather
     * than borrowing a player's uuid or weakening the constraint for everyone.
     */
    public static final UUID CONSOLE_ACTOR = UUID.fromString("00000000-0000-0000-0000-0000c0f501e0");

    private static final Component PREFIX =
        Component.text("[Sentinel] ", NamedTextColor.DARK_RED);

    private SanctionCommands() {}

    /** Every command this class provides, ready to register. */
    public static List<Registration> all() {
        return List.of(
            new Registration("warn", "btccore.moderation.warn",
                new IssueCommand(SanctionType.WARN, false, "btccore.moderation.warn")),
            new Registration("note", "btccore.moderation.note",
                new IssueCommand(SanctionType.NOTE, false, "btccore.moderation.note")),
            new Registration("mute", "btccore.moderation.mute",
                new IssueCommand(SanctionType.MUTE, true, "btccore.moderation.mute")),
            new Registration("kick", "btccore.moderation.kick",
                new IssueCommand(SanctionType.KICK, false, "btccore.moderation.kick")),
            new Registration("ban", "btccore.moderation.ban",
                new IssueCommand(SanctionType.BAN, true, "btccore.moderation.ban")),
            new Registration("unmute", "btccore.moderation.mute",
                new LiftCommand(SanctionType.MUTE, "btccore.moderation.mute")),
            new Registration("unban", "btccore.moderation.ban",
                new LiftCommand(SanctionType.BAN, "btccore.moderation.ban")),
            new Registration("history", "btccore.moderation.history", new HistoryCommand()));
    }

    /** One command, its label and the permission that guards it. */
    public record Registration(String label, String permission, BasicCommand command) {}

    // ---------------------------------------------------------------- issuing

    /** Issues a sanction of one type. */
    private static final class IssueCommand implements BasicCommand {

        private final SanctionType type;
        private final boolean timed;
        private final String permission;

        IssueCommand(SanctionType type, boolean timed, String permission) {
            this.type = type;
            this.timed = timed;
            this.permission = permission;
        }

        @Override
        public @NotNull String permission() {
            return permission;
        }

        @Override
        public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
            CommandSender sender = source.getSender();
            String verb = type.name().toLowerCase(Locale.ROOT);
            String usage = timed
                ? "/" + verb + " <player> <duration|perm> <reason...> [-s]"
                : "/" + verb + " <player> <reason...> [-s]";

            if (args.length < (timed ? 3 : 2)) {
                error(sender, "Usage: " + usage);
                return;
            }
            if (!SanctionService.isReady()) {
                error(sender, "The sanction store is unavailable; nothing was recorded. "
                    + "Check storage.* in anticheat.yml and the console.");
                return;
            }

            Optional<UUID> target = resolve(args[0]);
            if (target.isEmpty()) {
                error(sender, "Unknown player '" + args[0]
                    + "'. They must have connected at least once before being sanctioned.");
                return;
            }

            List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
            boolean silent = rest.removeIf(argument -> argument.equalsIgnoreCase("-s"));

            Optional<Long> expiresAt = Optional.empty();
            if (timed) {
                if (rest.isEmpty()) {
                    error(sender, "Usage: " + usage);
                    return;
                }
                Parsed parsed = SanctionDuration.parse(rest.remove(0));
                if (parsed instanceof Parsed.Invalid invalid) {
                    error(sender, invalid.reason());
                    return;
                }
                expiresAt = SanctionDuration.expiryFrom(System.currentTimeMillis(), parsed);
            }

            String reason = String.join(" ", rest).trim();
            if (reason.isEmpty()) {
                error(sender, "A reason is required. Usage: " + usage);
                return;
            }

            UUID actor = sender instanceof Player player ? player.getUniqueId() : CONSOLE_ACTOR;
            Sanction sanction = new Sanction(
                Optional.empty(),
                target,
                Optional.empty(),
                type,
                SanctionScope.SERVER,
                AnticheatConfig.networkId,
                Optional.of(AnticheatConfig.serverId),
                reason,
                silent,
                Optional.of(actor),
                ActorKind.HUMAN,
                Optional.empty(),
                System.currentTimeMillis(),
                expiresAt,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

            String targetName = args[0];
            Optional<Long> expiry = expiresAt;
            SanctionService.issue(sanction)
                .thenAccept(stored -> announce(sender, targetName, stored, expiry))
                .exceptionally(failure -> {
                    error(sender, "The sanction could NOT be recorded: " + rootMessage(failure));
                    return null;
                });
        }
    }

    /**
     * Applies the visible effect and reports the outcome.
     *
     * <p>Runs on the database thread, so anything touching a player is handed back to that player's
     * region scheduler first.
     */
    private static void announce(CommandSender sender, String targetName, Sanction stored,
                                 Optional<Long> expiresAt) {
        String duration = SanctionDuration.describeRemaining(System.currentTimeMillis(), expiresAt);
        sender.sendMessage(PREFIX.append(Component.text(
            stored.type().name().toLowerCase(Locale.ROOT) + " recorded for " + targetName
                + " (" + duration + "): " + stored.reason(),
            NamedTextColor.GREEN)));

        org.bukkit.plugin.Plugin host = Bukkit.getPluginManager().getPlugin("ASPaper");
        if (host == null) {
            // Cannot schedule onto the player's region without a host plugin. The sanction is already
            // recorded, so say plainly that only its immediate effect was skipped.
            error(sender, "Recorded, but the effect could not be applied now: the host plugin is absent.");
            return;
        }
        stored.playerUuid()
            .map(Bukkit::getPlayer)
            .ifPresent(player -> player.getScheduler().run(
                host, task -> applyTo(player, stored, duration), null));
    }

    /** The effect a freshly issued sanction has on a connected player. */
    private static void applyTo(Player player, Sanction stored, String duration) {
        switch (stored.type()) {
            case KICK -> player.kick(Component.text("Kicked: " + stored.reason(), NamedTextColor.RED));
            case BAN -> player.kick(Component.text(
                "Banned (" + duration + "): " + stored.reason(), NamedTextColor.RED));
            case WARN -> player.sendMessage(PREFIX.append(
                Component.text("Warning: " + stored.reason(), NamedTextColor.YELLOW)));
            case MUTE -> {
                if (!stored.silent()) {
                    player.sendMessage(PREFIX.append(Component.text(
                        "You are muted for " + duration + ": " + stored.reason(), NamedTextColor.RED)));
                }
                SanctionService.load(player.getUniqueId());
            }
            // A note is a staff-side record; the player is deliberately not told.
            case NOTE, RESTRICT, QUARANTINE -> SanctionService.load(player.getUniqueId());
        }
    }

    // ---------------------------------------------------------------- lifting

    /** Lifts the standing sanction of one type. */
    private static final class LiftCommand implements BasicCommand {

        private final SanctionType type;
        private final String permission;

        LiftCommand(SanctionType type, String permission) {
            this.type = type;
            this.permission = permission;
        }

        @Override
        public @NotNull String permission() {
            return permission;
        }

        @Override
        public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
            CommandSender sender = source.getSender();
            String verb = "un" + type.name().toLowerCase(Locale.ROOT);

            if (args.length < 2) {
                error(sender, "Usage: /" + verb + " <player> <reason...>");
                return;
            }
            if (!SanctionService.isReady()) {
                error(sender, "The sanction store is unavailable; nothing was lifted.");
                return;
            }

            Optional<UUID> target = resolve(args[0]);
            if (target.isEmpty()) {
                error(sender, "Unknown player '" + args[0] + "'.");
                return;
            }
            String reason = String.join(" ", Arrays.asList(args).subList(1, args.length)).trim();
            if (reason.isEmpty()) {
                error(sender, "Lifting a sanction requires a reason.");
                return;
            }

            UUID actor = sender instanceof Player player ? player.getUniqueId() : CONSOLE_ACTOR;
            String targetName = args[0];

            SanctionService.activeFromDatabase(target.get()).thenAccept(active -> {
                Optional<Sanction> standing = active.stream()
                    .filter(sanction -> sanction.type() == type)
                    .findFirst();

                if (standing.isEmpty()) {
                    error(sender, targetName + " has no standing "
                        + type.name().toLowerCase(Locale.ROOT) + " to lift.");
                    return;
                }
                long id = standing.get().id().orElseThrow();
                SanctionService.revoke(id, target.get(), actor, reason).thenAccept(lifted -> {
                    if (lifted) {
                        sender.sendMessage(PREFIX.append(Component.text(
                            verb.substring(2) + " lifted for " + targetName + ": " + reason,
                            NamedTextColor.GREEN)));
                        target.ifPresent(SanctionService::load);
                    } else {
                        // Someone else lifted it between the read and the write.
                        error(sender, "That sanction was already lifted.");
                    }
                }).exceptionally(failure -> {
                    error(sender, "The sanction could NOT be lifted: " + rootMessage(failure));
                    return null;
                });
            });
        }
    }

    // ---------------------------------------------------------------- history

    /** Shows a player's moderation history, revoked entries included. */
    private static final class HistoryCommand implements BasicCommand {

        @Override
        public @NotNull String permission() {
            return "btccore.moderation.history";
        }

        @Override
        public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
            CommandSender sender = source.getSender();
            if (args.length < 1) {
                error(sender, "Usage: /history <player>");
                return;
            }
            Optional<UUID> target = resolve(args[0]);
            if (target.isEmpty()) {
                error(sender, "Unknown player '" + args[0] + "'.");
                return;
            }

            String targetName = args[0];
            SanctionService.history(target.get(), SanctionService.DEFAULT_HISTORY_LIMIT)
                .thenAccept(entries -> {
                    if (entries.isEmpty()) {
                        sender.sendMessage(PREFIX.append(Component.text(
                            targetName + " has no moderation history.", NamedTextColor.GREEN)));
                        return;
                    }
                    sender.sendMessage(Component.text(
                        "── History: " + targetName + " (" + entries.size() + ") ──",
                        NamedTextColor.GOLD));
                    long now = System.currentTimeMillis();
                    for (Sanction entry : entries) {
                        sender.sendMessage(describe(entry, now));
                    }
                });
        }
    }

    /** One history line: what, how long is left, and whether it still stands. */
    private static Component describe(Sanction entry, long now) {
        String state = entry.revokedAtMillis().isPresent()
            ? "lifted"
            : SanctionDuration.describeRemaining(now, entry.expiresAtMillis());
        NamedTextColor colour = entry.activeAt(now) ? NamedTextColor.RED : NamedTextColor.GRAY;

        return Component.text("• " + entry.type().name() + " [" + state + "] ", colour)
            .append(Component.text(entry.reason(), NamedTextColor.WHITE))
            .append(Component.text(
                entry.decidedByHuman() ? " — by staff" : " — automatic", NamedTextColor.DARK_GRAY));
    }

    // ---------------------------------------------------------------- shared

    /**
     * Resolves a name to a uuid without ever contacting Mojang.
     *
     * <p>{@code getOfflinePlayer(String)} performs a blocking web lookup when the name is not cached,
     * on whichever thread called it. A moderation command must not be able to freeze a tick, so an
     * unknown name is refused instead — with a message saying why.
     */
    private static Optional<UUID> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? Optional.empty() : Optional.of(cached.getUniqueId());
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.RED)));
    }

    /** The message of the actual failure, not of the {@code CompletionException} wrapping it. */
    private static String rootMessage(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
