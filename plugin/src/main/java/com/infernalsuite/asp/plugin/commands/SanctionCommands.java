package com.infernalsuite.asp.plugin.commands;

import dev.btc.core.BTCCoreBuild;
import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.integrity.sanction.ActorKind;
import dev.btc.core.integrity.sanction.LiftPlan;
import dev.btc.core.integrity.sanction.Sanction;
import dev.btc.core.integrity.sanction.SanctionDuration;
import dev.btc.core.integrity.sanction.SanctionMessages;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

        org.bukkit.plugin.Plugin host = Bukkit.getPluginManager().getPlugin(BTCCoreBuild.HOST_PLUGIN);
        if (host == null) {
            // Cannot schedule onto the player's region without a host plugin. The sanction is already
            // recorded, so say plainly that only its immediate effect was skipped.
            error(sender, "Recorded, but the effect could not be applied now: the host plugin is absent.");
            return;
        }
        // Every account of the person (AD-090): a ban on the Bedrock uuid must reach them while they
        // play here under the linked Java one. Bukkit.getPlayer reads the online map, safe off-thread.
        stored.playerUuid().ifPresent(target -> SanctionService.aliasesOf(target)
            .exceptionally(failure -> Set.of(target))
            .thenAccept(aliases -> aliases.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .forEach(player -> player.getScheduler().run(
                    host, task -> applyTo(player, stored, duration), null))));
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

            // --linked is the moderator's explicit choice to lift the linked accounts' sanctions too.
            List<String> rest = new ArrayList<>(Arrays.asList(args));
            boolean includeLinked = rest.removeIf(argument -> argument.equalsIgnoreCase("--linked"));

            if (rest.size() < 2) {
                error(sender, "Usage: /" + verb + " <player> <reason...> [--linked]");
                return;
            }
            if (!SanctionService.isReady()) {
                error(sender, "The sanction store is unavailable; nothing was lifted.");
                return;
            }

            String targetName = rest.get(0);
            Optional<UUID> resolved = resolve(targetName);
            if (resolved.isEmpty()) {
                error(sender, "Unknown player '" + targetName + "'.");
                return;
            }
            UUID target = resolved.get();
            String reason = String.join(" ", rest.subList(1, rest.size())).trim();
            if (reason.isEmpty()) {
                error(sender, "Lifting a sanction requires a reason.");
                return;
            }

            UUID actor = sender instanceof Player player ? player.getUniqueId() : CONSOLE_ACTOR;

            SanctionService.activeFromDatabase(target).thenAccept(active -> {
                LiftPlan plan = LiftPlan.of(target, type, active, includeLinked);

                if (plan.toLift().isEmpty()) {
                    if (plan.stillBlocking().isEmpty()) {
                        error(sender, targetName + " has no standing "
                            + type.name().toLowerCase(Locale.ROOT) + " to lift.");
                    } else {
                        reportStillBlocking(sender, targetName, plan);
                    }
                    return;
                }

                // Each sanction is revoked against the account it was issued to, so that the cache
                // refresh and the bus invalidation reach the right person.
                List<CompletableFuture<Boolean>> revocations = plan.toLift().stream()
                    .map(standing -> SanctionService.revoke(
                        standing.id().orElseThrow(), standing.playerUuid().orElse(target), actor, reason))
                    .toList();
                CompletableFuture.allOf(revocations.toArray(CompletableFuture[]::new)).thenRun(() -> {
                    int lifted = (int) revocations.stream().filter(CompletableFuture::join).count();
                    if (lifted == 0) {
                        // Someone else lifted it between the read and the write.
                        error(sender, "That sanction was already lifted.");
                        return;
                    }
                    if (includeLinked) {
                        sender.sendMessage(PREFIX.append(
                            SanctionMessages.liftedLinked(type, targetName, lifted, reason)));
                    } else {
                        sender.sendMessage(PREFIX.append(Component.text(
                            verb.substring(2) + " lifted for " + targetName + ": " + reason,
                            NamedTextColor.GREEN)));
                    }
                    SanctionService.load(target);
                    reportStillBlocking(sender, targetName, plan);
                }).exceptionally(failure -> {
                    error(sender, "The sanction could NOT be lifted: " + rootMessage(failure));
                    return null;
                });
            });
        }

        /** Names, for each sanction this lift leaves in force on a linked account, where it comes from. */
        private static void reportStillBlocking(CommandSender sender, String targetName, LiftPlan plan) {
            for (Sanction blocker : plan.stillBlocking()) {
                sender.sendMessage(PREFIX.append(SanctionMessages.liftBlockedByLinked(targetName, blocker)));
            }
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
            UUID id = target.get();
            SanctionService.aliasesOf(id)
                .exceptionally(failure -> Set.of(id))
                .thenCombine(SanctionService.history(id, SanctionService.DEFAULT_HISTORY_LIMIT),
                    (aliases, entries) -> {
                        Set<UUID> others = new java.util.TreeSet<>(aliases);
                        others.remove(id);
                        if (!others.isEmpty()) {
                            sender.sendMessage(PREFIX.append(SanctionMessages.historyAliases(others)));
                        }
                        if (entries.isEmpty()) {
                            sender.sendMessage(PREFIX.append(Component.text(
                                targetName + " has no moderation history.", NamedTextColor.GREEN)));
                            return null;
                        }
                        sender.sendMessage(Component.text(
                            "── History: " + targetName + " (" + entries.size() + ") ──",
                            NamedTextColor.GOLD));
                        long now = System.currentTimeMillis();
                        for (Sanction entry : entries) {
                            sender.sendMessage(describe(entry, id, now));
                        }
                        return null;
                    });
        }
    }

    /**
     * One history line: what, how long is left, and whether it still stands. A sanction issued against
     * another account of the same person says so, with that account's short id.
     */
    private static Component describe(Sanction entry, UUID viewed, long now) {
        String state = entry.revokedAtMillis().isPresent()
            ? "lifted"
            : SanctionDuration.describeRemaining(now, entry.expiresAtMillis());
        NamedTextColor colour = entry.activeAt(now) ? NamedTextColor.RED : NamedTextColor.GRAY;

        Component line = Component.text("• " + entry.type().name() + " [" + state + "] ", colour)
            .append(Component.text(entry.reason(), NamedTextColor.WHITE))
            .append(Component.text(
                entry.decidedByHuman() ? " — by staff" : " — automatic", NamedTextColor.DARK_GRAY));
        return SanctionMessages.isOnAlias(entry, viewed)
            ? line.append(SanctionMessages.historyViaAlias(entry.playerUuid().orElseThrow()))
            : line;
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
