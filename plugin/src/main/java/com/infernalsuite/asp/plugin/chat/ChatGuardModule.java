package com.infernalsuite.asp.plugin.chat;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Starts the chat guard (Sentinel 16.1): the one entry point {@code SWPlugin} calls from {@code onEnable},
 * inside its own fence. The listener is registered whatever the configured mode, so that a
 * {@code /chatguard reload} can switch the guard on without a restart; with {@code mode: off} a judgement
 * is a permission check and a return.
 */
public final class ChatGuardModule {

    private ChatGuardModule() {}

    public static void start(final JavaPlugin plugin) {
        final ChatGuardService service = ChatGuardService.start(plugin, plugin.getSLF4JLogger());
        plugin.getServer().getPluginManager().registerEvents(new ChatGuardListener(service), plugin);
        // Players already online when the plugin starts have no join event to come.
        plugin.getServer().getOnlinePlayers().forEach(online -> service.track(online.getUniqueId()));
        // Paper plugins cannot declare commands in paper-plugin.yml: registered through the Brigadier lifecycle.
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
            event -> event.registrar().register("chatguard", "Chat guard: reload, status, test",
                new ChatGuardCommand(plugin, service)));
    }
}
