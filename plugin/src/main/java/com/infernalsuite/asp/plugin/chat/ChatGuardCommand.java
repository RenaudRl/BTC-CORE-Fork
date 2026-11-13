package com.infernalsuite.asp.plugin.chat;

import dev.btc.core.integrity.chat.ChatFinding;
import dev.btc.core.integrity.chat.ChatGuard;
import dev.btc.core.integrity.chat.ChatGuardConfig;
import dev.btc.core.integrity.chat.ChatGuardFiles;
import dev.btc.core.integrity.chat.ChatRuleId;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /chatguard reload | status | test <text>}, for staff with {@value #PERMISSION}.
 *
 * <p>Every reply is staff output and is in English like the other admin commands of this plugin
 * ({@code /btccore}): it is not a {@code PlayerText}, which is for what players read.
 *
 * <p>Lives in the plugin module for the classloader reason given on {@code BTCCoreDebugCommand}: a
 * {@link BasicCommand} registered from {@code SWPlugin} must be owned by the plugin classloader.
 *
 * <p>Folia: {@code reload} reads files, so it runs on the async scheduler and replies from there; the
 * other two only read memory.
 */
final class ChatGuardCommand implements BasicCommand {

    static final String PERMISSION = "btc.chatguard.admin";

    private static final List<String> SUBCOMMANDS = List.of("reload", "status", "test");

    /** The "player" a console test is run for: no history, so the rate and similarity rules start fresh. */
    private static final UUID CONSOLE = new UUID(0L, 0L);

    private final Plugin plugin;
    private final ChatGuardService service;

    ChatGuardCommand(final Plugin plugin, final ChatGuardService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public @NotNull String permission() {
        return PERMISSION;
    }

    @Override
    public void execute(final @NotNull CommandSourceStack source, final @NotNull String[] args) {
        final CommandSender sender = source.getSender();
        final String subcommand = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "reload" -> reload(sender);
            case "status" -> status(sender);
            case "test" -> test(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> sender.sendMessage(Component.text("Usage: /chatguard <reload|status|test <text>>", NamedTextColor.YELLOW));
        }
    }

    @Override
    public @NotNull Collection<String> suggest(final @NotNull CommandSourceStack source, final @NotNull String[] args) {
        if (args.length <= 1) {
            final String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(typed)).toList();
        }
        return List.of();
    }

    private void reload(final CommandSender sender) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            final ChatGuardService.Reload outcome = service.reload();
            final NamedTextColor color = outcome.applied() ? NamedTextColor.GREEN : NamedTextColor.RED;
            outcome.lines().forEach(line -> sender.sendMessage(Component.text("[ChatGuard] " + line, color)));
        });
    }

    private void status(final CommandSender sender) {
        final ChatGuardFiles.Loaded loaded = service.loaded();
        final ChatGuardConfig config = service.guard().config();
        sender.sendMessage(Component.text("[ChatGuard] mode=" + config.mode() + ", " + service.guard().tracked()
            + " player(s) tracked, " + loaded.words().entryCount() + " word entries in " + loaded.words().languages()
            + ", " + loaded.tlds().size() + " TLDs.", NamedTextColor.GOLD));
        for (final ChatRuleId rule : ChatRuleId.values()) {
            sender.sendMessage(Component.text("  " + rule.key() + ": " + config.modeOf(rule), NamedTextColor.GRAY));
        }
    }

    /** What every rule thinks of the text, as the next message of the sender; nothing is remembered. */
    private void test(final CommandSender sender, final String[] words) {
        if (words.length == 0) {
            sender.sendMessage(Component.text("Usage: /chatguard test <text>", NamedTextColor.YELLOW));
            return;
        }
        final UUID who = sender instanceof Player player ? player.getUniqueId() : CONSOLE;
        for (final ChatGuard.Opinion opinion : service.guard().explain(who, String.join(" ", words), service.now())) {
            final String verdict = opinion.finding().map(ChatFinding::detail).map(detail -> "FOUND (" + detail + ")").orElse("pass");
            sender.sendMessage(Component.text("  " + opinion.rule().key() + " [" + opinion.mode() + "]: " + verdict,
                opinion.finding().isPresent() ? NamedTextColor.RED : NamedTextColor.GREEN));
        }
    }
}
