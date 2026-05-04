package dev.btc.core.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import java.text.DecimalFormat;
import java.util.Arrays;

public class BTCCoreMSPTCommand {
    private static final DecimalFormat DF = new DecimalFormat("###.00");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("btc-mspt")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .executes(context -> {
                MinecraftServer server = context.getSource().getServer();
                double[] tps = server.getTPS();
                double tps1m = tps[0];
                ca.spottedleaf.moonrise.common.time.TickData.MSPTData data = server.getMSPTData5s();
                double mspt = data != null ? data.avg() : 0.0;
                
                context.getSource().sendSuccess(() -> Component.literal(
                    String.format("BTCCore Status:\nTPS (1m): %s\nMSPT (Average): %s ms", 
                    DF.format(tps1m), DF.format(mspt))), false);
                return 1;
            })
        );
    }
}

