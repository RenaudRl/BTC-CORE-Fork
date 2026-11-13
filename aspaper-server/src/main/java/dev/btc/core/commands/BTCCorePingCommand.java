package dev.btc.core.commands;

import com.mojang.brigadier.CommandDispatcher;
import dev.btc.core.text.PlayerText;
import dev.btc.core.text.PlayerTexts;
import io.papermc.paper.adventure.PaperAdventure;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.UUID;

public class BTCCorePingCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ping")
            .executes(context -> {
                if (context.getSource().getEntity() instanceof ServerPlayer player) {
                     sendPing(context.getSource(), player);
                } else {
                     context.getSource().sendFailure(text(context.getSource(), PlayerText.COMMAND_PING_PLAYERS_ONLY, Map.of()));
                }
                return 1;
            })
            .then(Commands.argument("target", EntityArgument.player())
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> {
                    ServerPlayer target = EntityArgument.getPlayer(context, "target");
                    sendPing(context.getSource(), target);
                    return 1;
                })
            )
        );
    }

    private static void sendPing(CommandSourceStack source, ServerPlayer player) {
        int ping = player.connection.latency();
        source.sendSuccess(() -> text(source, PlayerText.COMMAND_PING, Map.of(
            "name", net.kyori.adventure.text.Component.text(player.getGameProfile().name()),
            "ping", net.kyori.adventure.text.Component.text(ping))), false);
    }

    /** The text for whoever ran the command: their own language, the built-in text for the console. */
    private static Component text(CommandSourceStack source, PlayerText text,
                                  Map<String, net.kyori.adventure.text.Component> arguments) {
        UUID viewer = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        return PaperAdventure.asVanilla(PlayerTexts.shared().render(viewer, text, arguments));
    }
}

