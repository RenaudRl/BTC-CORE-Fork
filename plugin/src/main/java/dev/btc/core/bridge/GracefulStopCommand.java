/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The operational entry point of a clean stop: {@code /btcgracefulstop <destination>}.
 *
 * <p>An orchestrator ends a service by sending it {@code stop}, and by then it is far too late to
 * move anyone: the players are thrown onto the fallback. A clean stop therefore has to be asked for
 * <em>before</em> that, and asking is what this command is for — it is what the panel, a deployment
 * script or an operator calls, and {@code stop} follows on its own once the server has ended.
 *
 * <p>Registered through Paper's command lifecycle rather than through the world plugin's command
 * manager: this belongs to the bridge, it must live and die with it, and folding it into another
 * subsystem's registry would tie two lifecycles together for nothing.
 */
final class GracefulStopCommand {

  /** Who may end a server. Not a gameplay permission: the default is operators only. */
  private static final String PERMISSION = "btc.bridge.gracefulstop";

  private GracefulStopCommand() {
  }

  /**
   * Registers the command against the plugin's lifecycle.
   *
   * @param plugin  the owning plugin
   * @param service the bridge service that performs the stop
   */
  static void register(final JavaPlugin plugin, final BridgeService service) {
    plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
        event.registrar().register(
            Commands.literal("btcgracefulstop")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.argument("destination", StringArgumentType.word())
                    .executes(context -> {
                      final String destination =
                          StringArgumentType.getString(context, "destination");
                      final boolean started = service.gracefulStop(destination);
                      context.getSource().getSender().sendMessage(started
                          ? Component.text("Graceful stop started towards " + destination
                              + "; the server ends once every player has left or the deadline "
                              + "passes.", NamedTextColor.YELLOW)
                          : Component.text("Refused: the bridge is not running, or a stop is "
                              + "already under way.", NamedTextColor.RED));
                      return started ? 1 : 0;
                    }))
                .build(),
            "Transfers every player away, then ends this server"));
  }
}
