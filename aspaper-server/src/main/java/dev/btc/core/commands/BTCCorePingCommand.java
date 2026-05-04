package dev.btc.core.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

public class BTCCorePingCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ping")
            .executes(context -> {
                if (context.getSource().getEntity() instanceof ServerPlayer player) {
                     sendPing(context.getSource(), player);
                } else {
                     context.getSource().sendFailure(Component.literal("Only players can use this command without arguments."));
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
        source.sendSuccess(() -> Component.literal(player.getGameProfile().name() + "'s Ping: " + ping + "ms"), false);
    }
}

