package dev.btc.core.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import java.lang.management.ManagementFactory;
import java.time.Duration;

public class BTCCoreUptimeCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("uptime")
            .executes(context -> {
                long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
                Duration duration = Duration.ofMillis(uptime);
                
                long days = duration.toDays();
                long hours = duration.toHours() % 24;
                long minutes = duration.toMinutes() % 60;
                long seconds = duration.getSeconds() % 60;
                
                String time = String.format("%dd %dh %dm %ds", days, hours, minutes, seconds);
                context.getSource().sendSuccess(() -> Component.literal("Server Uptime: " + time), false);
                return 1;
            })
        );
    }
}

