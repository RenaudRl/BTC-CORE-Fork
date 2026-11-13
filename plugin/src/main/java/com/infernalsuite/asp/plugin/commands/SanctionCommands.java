package com.infernalsuite.asp.plugin.commands;

import dev.btc.core.BTCCoreBuild;
import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.integrity.sanction.ActorKind;
import dev.btc.core.integrity.sanction.LiftArguments;
import dev.btc.core.integrity.sanction.LiftPlan;
import dev.btc.core.integrity.sanction.Sanction;
import dev.btc.core.integrity.sanction.SanctionDuration;
import dev.btc.core.integrity.sanction.SanctionMessages;
import dev.btc.core.integrity.sanction.SanctionDuration.Parsed;
import dev.btc.core.integrity.sanction.SanctionScope;
import dev.btc.core.integrity.sanction.SanctionService;
import dev.btc.core.integrity.sanction.SanctionType;
import dev.btc.core.security.CombatLogManager;
import dev.btc.core.text.PlayerText;
import com.infernalsuite.asp.plugin.sanction.SanctionEnforcement;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;

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
                error(sender, PlayerText.SANCTION_ERROR_USAGE, Map.of("usage", Component.text(usage)));
                return;
            }
            if (!SanctionService.isReady()) {
                error(sender, PlayerText.SANCTION_ERROR_STORE_UNAVAILABLE_ISSUE);
                return;
            }

            Optional<UUID> target = resolve(args[0]);
            if (target.isEmpty()) {
                error(sender, PlayerText.SANCTION_ERROR_UNKNOWN_PLAYER_ISSUE, Map.of("player", Component.text(args[0])));
                return;
            }

            List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
            boolean silent = rest.removeIf(argument -> argument.equalsIgnoreCase("-s"));

            Optional<Long> expiresAt = Optional.empty();
            if (timed) {
                if (rest.isEmpty()) {
                    error(sender, PlayerText.SANCTION_ERROR_USAGE, Map.of("usage", Component.text(usage)));
                    return;
                }
                Parsed parsed = SanctionDuration.parse(rest.remove(0));
                if (parsed instanceof Parsed.Invalid invalid) {
                    error(sender, SanctionMessages.render(viewerOf(sender), invalid.text(), invalid.arguments()));
                    return;
                }
                expiresAt = SanctionDuration.expiryFrom(System.currentTimeMillis(), parsed);
            }

            String reason = String.join(" ", rest).trim();
            if (reason.isEmpty()) {
                error(sender, PlayerText.SANCTION_ERROR_REASON_REQUIRED, Map.of("usage", Component.text(usage)));
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
                    error(sender, PlayerText.SANCTION_ERROR_NOT_RECORDED, Map.of("error", Component.text(rootMessage(failure))));
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
        SanctionDuration.Remaining duration = SanctionDuration.remaining(System.currentTimeMillis(), expiresAt);
        tell(sender, SanctionMessages.recorded(viewerOf(sender), stored.type(), targetName, duration, stored.reason()));

        org.bukkit.plugin.Plugin host = Bukkit.getPluginManager().getPlugin(BTCCoreBuild.HOST_PLUGIN);
        if (host == null) {
            // Cannot schedule onto the player's region without a host plugin. The sanction is already
            // recorded, so say plainly that only its immediate effect was skipped.
            error(sender, PlayerText.SANCTION_ERROR_HOST_ABSENT);
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
    private static void applyTo(Player player, Sanction stored, SanctionDuration.Remaining duration) {
        // The online player may be an alias of the sanctioned account: noticeFor hides the reason then.
        // A note is a staff-side record and a silent mute is silent: noticeFor says nothing for those.
        Optional<Component> notice = SanctionMessages.noticeFor(stored, player.getUniqueId(), duration);
        switch (stored.type()) {
            case KICK, BAN -> notice.ifPresent(text -> {
                CombatLogManager.departureDecidedByServer(player);
                player.kick(text);
            });
            case WARN -> notice.ifPresent(text -> tell(player, text));
            case MUTE -> {
                notice.ifPresent(text -> tell(player, text));
                SanctionService.load(player.getUniqueId());
            }
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

            // --linked, as the last word, is the moderator's explicit choice to lift the linked accounts too.
            LiftArguments parsed = LiftArguments.parse(args);
            List<String> rest = parsed.words();
            boolean includeLinked = parsed.includeLinked();

            if (rest.size() < 2) {
                error(sender, PlayerText.SANCTION_ERROR_USAGE,
                    Map.of("usage", Component.text("/" + verb + " <player> <reason...> [--linked]")));
                return;
            }
            if (!SanctionService.isReady()) {
                error(sender, PlayerText.SANCTION_ERROR_STORE_UNAVAILABLE_LIFT);
                return;
            }

            String targetName = rest.get(0);
            Optional<UUID> resolved = resolve(targetName);
            if (resolved.isEmpty()) {
                error(sender, PlayerText.SANCTION_ERROR_UNKNOWN_PLAYER, Map.of("player", Component.text(targetName)));
                return;
            }
            UUID target = resolved.get();
            String reason = String.join(" ", rest.subList(1, rest.size())).trim();
            if (reason.isEmpty()) {
                error(sender, PlayerText.SANCTION_ERROR_LIFT_REASON_REQUIRED);
                return;
            }

            UUID actor = sender instanceof Player player ? player.getUniqueId() : CONSOLE_ACTOR;
            UUID viewer = viewerOf(sender);

            // Terminal handler of both stages below: a failure that nobody reports is a lift the
            // moderator believes happened.
            Function<Throwable, Void> failed = failure -> {
                Bukkit.getLogger().log(Level.WARNING, "[Sentinel] /" + verb + " failed for " + targetName, failure);
                tell(sender, SanctionMessages.liftFailed(viewer, type, targetName, rootMessage(failure)));
                return null;
            };

            SanctionService.activeFromDatabase(target).thenAccept(active -> {
                LiftPlan plan = LiftPlan.of(target, type, active, includeLinked);

                if (plan.toLift().isEmpty()) {
                    if (plan.stillBlocking().isEmpty()) {
                        tell(sender, SanctionMessages.nothingToLift(viewer, type, targetName));
                    } else {
                        reportStillBlocking(sender, targetName, plan, false);
                    }
                    return;
                }

                // Each sanction is revoked against the account it was issued to, so that the cache
                // refresh and the bus invalidation reach the right person. A failed revocation becomes
                // null so that the others are still counted instead of hiding behind allOf's failure.
                List<CompletableFuture<Boolean>> revocations = plan.toLift().stream()
                    .map(standing -> SanctionService.revoke(
                            standing.id().orElseThrow(), standing.playerUuid().orElse(target), actor, reason)
                        .exceptionally(failure -> {
                            Bukkit.getLogger().log(Level.WARNING, "[Sentinel] could not revoke a "
                                + type.name().toLowerCase(Locale.ROOT) + " of " + targetName, failure);
                            return null;
                        }))
                    .toList();
                CompletableFuture.allOf(revocations.toArray(CompletableFuture[]::new)).thenRun(() -> {
                    int lifted = 0;
                    int notLifted = 0;
                    Set<UUID> liftedAccounts = new java.util.TreeSet<>();
                    for (int i = 0; i < revocations.size(); i++) {
                        Boolean revoked = revocations.get(i).join();
                        if (revoked == null) {
                            notLifted++;
                        } else if (revoked) {
                            lifted++;
                            liftedAccounts.add(plan.toLift().get(i).playerUuid().orElse(target));
                        }
                    }
                    if (notLifted > 0) {
                        tell(sender, SanctionMessages.liftPartial(
                            viewer, type, targetName, lifted, revocations.size(), notLifted));
                    }
                    if (lifted == 0) {
                        if (notLifted == 0) {
                            // Someone else lifted it between the read and the write.
                            tell(sender, SanctionMessages.alreadyLifted(viewer));
                        }
                        return;
                    }
                    if (includeLinked) {
                        tell(sender, SanctionMessages.liftedLinked(viewer, type, targetName, liftedAccounts, reason));
                    } else {
                        tell(sender, SanctionMessages.lifted(viewer, type, targetName, reason));
                    }
                    SanctionService.load(target);
                    reportStillBlocking(sender, targetName, plan, true);
                }).exceptionally(failed);
            }).exceptionally(failed);
        }

        /**
         * Names, for each sanction this lift leaves in force on a linked account, where it comes from.
         *
         * @param liftedSomething whether the target's own sanction was lifted by this very command
         */
        private static void reportStillBlocking(CommandSender sender, String targetName, LiftPlan plan,
                                                boolean liftedSomething) {
            UUID viewer = viewerOf(sender);
            for (Sanction blocker : plan.stillBlocking()) {
                tell(sender, liftedSomething
                    ? SanctionMessages.liftStillBlockedByLinked(viewer, targetName, blocker)
                    : SanctionMessages.liftBlockedByLinked(viewer, targetName, blocker));
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
                error(sender, PlayerText.SANCTION_ERROR_USAGE, Map.of("usage", Component.text("/history <player>")));
                return;
            }
            Optional<UUID> target = resolve(args[0]);
            if (target.isEmpty()) {
                error(sender, PlayerText.SANCTION_ERROR_UNKNOWN_PLAYER, Map.of("player", Component.text(args[0])));
                return;
            }

            String targetName = args[0];
            UUID viewer = viewerOf(sender);
            UUID id = target.get();
            SanctionService.aliasesOf(id)
                .exceptionally(failure -> Set.of(id))
                .thenCombine(SanctionService.history(id, SanctionService.DEFAULT_HISTORY_LIMIT),
                    (aliases, entries) -> {
                        Set<UUID> others = new java.util.TreeSet<>(aliases);
                        others.remove(id);
                        if (!others.isEmpty()) {
                            tell(sender, SanctionMessages.historyAliases(viewer, others));
                        }
                        if (entries.isEmpty()) {
                            tell(sender, SanctionMessages.render(viewer, PlayerText.SANCTION_HISTORY_EMPTY,
                                Map.of("target", Component.text(targetName))));
                            return null;
                        }
                        sender.sendMessage(SanctionMessages.render(viewer, PlayerText.SANCTION_HISTORY_HEADER, Map.of(
                            "target", Component.text(targetName),
                            "count", Component.text(entries.size()))));
                        long now = System.currentTimeMillis();
                        for (Sanction entry : entries) {
                            sender.sendMessage(describe(viewer, entry, id, now));
                        }
                        return null;
                    })
                .exceptionally(failure -> {
                    Bukkit.getLogger().log(Level.WARNING, "[Sentinel] /history failed for " + targetName, failure);
                    tell(sender, SanctionMessages.historyFailed(viewer, targetName, rootMessage(failure)));
                    return null;
                });
        }
    }

    /**
     * One history line: what, how long is left, and whether it still stands. A sanction issued against
     * another account of the same person says so, with that account's short id.
     */
    private static Component describe(@Nullable UUID viewer, Sanction entry, UUID viewed, long now) {
        Component state = entry.revokedAtMillis().isPresent()
            ? SanctionMessages.render(viewer, PlayerText.SANCTION_HISTORY_LIFTED, Map.of())
            : SanctionMessages.duration(viewer, SanctionDuration.remaining(now, entry.expiresAtMillis()));
        Component origin = SanctionMessages.render(viewer,
            entry.decidedByHuman() ? PlayerText.SANCTION_HISTORY_BY_STAFF : PlayerText.SANCTION_HISTORY_AUTOMATIC,
            Map.of());
        NamedTextColor colour = entry.activeAt(now) ? NamedTextColor.RED : NamedTextColor.GRAY;

        Component line = Component.text().color(colour)
            .append(Component.text("• "))
            .append(SanctionMessages.typeName(viewer, entry.type()))
            .append(Component.text(" ["))
            .append(state)
            .append(Component.text("] "))
            .append(Component.text(entry.reason(), NamedTextColor.WHITE))
            .append(origin.colorIfAbsent(NamedTextColor.DARK_GRAY))
            .build();
        return SanctionMessages.isOnAlias(entry, viewed)
            ? line.append(SanctionMessages.historyViaAlias(viewer, entry.playerUuid().orElseThrow()))
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

    /** The player a text is rendered for; {@code null} for the console, which is never asked of a provider. */
    private static @Nullable UUID viewerOf(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    /** Sends {@code message} behind the prefix of the moderation commands, in the receiver's language. */
    private static void tell(CommandSender receiver, Component message) {
        Component prefix = SanctionMessages.render(viewerOf(receiver), PlayerText.SANCTION_PREFIX, Map.of());
        receiver.sendMessage(Component.textOfChildren(prefix, message));
    }

    /** An error is red unless its text says otherwise; the colour belongs to the channel, not to each wording. */
    private static void error(CommandSender sender, Component message) {
        tell(sender, Component.text().color(NamedTextColor.RED).append(message).build());
    }

    private static void error(CommandSender sender, PlayerText text, Map<String, Component> arguments) {
        error(sender, SanctionMessages.render(viewerOf(sender), text, arguments));
    }

    private static void error(CommandSender sender, PlayerText text) {
        error(sender, text, Map.of());
    }

    /** The message of the actual failure, not of the {@code CompletionException} wrapping it. */
    private static String rootMessage(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
