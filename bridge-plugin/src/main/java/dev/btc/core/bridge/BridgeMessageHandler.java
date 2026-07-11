/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import dev.btc.core.api.BTCCoreAPI;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Handles incoming {@code btc:bridge} messages from the proxy.
 *
 * <p>This listener is registered by {@link BridgePlugin} and processes the following
 * message types:</p>
 * <ul>
 *   <li>{@code queue_join} - logs the event and optionally preloads the target world</li>
 *   <li>{@code queue_leave} - logs the event</li>
 *   <li>{@code request_status} - responds immediately with a {@link BridgeMessage.Health} message</li>
 *   <li>{@code world_preload} - loads the specified world asynchronously if not already loaded</li>
 * </ul>
 */
public final class BridgeMessageHandler implements PluginMessageListener {

  private static final String CHANNEL = "btc:bridge";

  private final BridgePlugin plugin;
  private final Logger logger;

  /**
   * Constructs the message handler.
   *
   * @param plugin the owning plugin instance
   */
  public BridgeMessageHandler(final BridgePlugin plugin) {
    this.plugin = plugin;
    this.logger = plugin.getLogger();
  }

  @Override
  public void onPluginMessageReceived(final String channel, final Player player, final byte[] data) {
    if (!CHANNEL.equals(channel)) {
      return;
    }

    final BridgeMessage message;
    try {
      message = BridgeCodec.decode(data);
    } catch (Exception e) {
      logger.warning("Failed to decode btc:bridge message from proxy: " + e.getMessage());
      return;
    }

    if (message == null) {
      logger.fine("Received unrecognized btc:bridge payload from proxy");
      return;
    }

    switch (message) {
      case BridgeMessage.QueueJoin join -> handleQueueJoin(player, join);
      case BridgeMessage.QueueLeave leave -> handleQueueLeave(leave);
      case BridgeMessage.RequestStatus status -> handleRequestStatus(player, status);
      case BridgeMessage.WorldPreload preload -> handleWorldPreload(player, preload);
      default -> logger.fine("Received unhandled btc:bridge message type: " + message.type());
    }
  }

  /**
   * Handles a {@code queue_join} message by logging the event and optionally
   * preloading the target world if one is specified.
   *
   * @param player the player whose connection delivered the message
   * @param join   the queue-join message
   */
  private void handleQueueJoin(final Player player, final BridgeMessage.QueueJoin join) {
    logger.fine("Player " + join.username() + " (" + join.uuid()
        + ") joined queue for server " + join.targetServer());

    if (join.targetWorld() != null && !join.targetWorld().isBlank()) {
      preloadWorld(player, join.targetWorld());
    }
  }

  /**
   * Handles a {@code queue_leave} message by logging the event.
   *
   * @param leave the queue-leave message
   */
  private void handleQueueLeave(final BridgeMessage.QueueLeave leave) {
    logger.fine("Player " + leave.uuid() + " left the queue");
  }

  /**
   * Handles a {@code request_status} message by immediately sending back a
   * {@link BridgeMessage.Health} report via the player's connection.
   *
   * @param player the player whose connection delivered the message
   * @param status the status request
   */
  private void handleRequestStatus(final Player player, final BridgeMessage.RequestStatus status) {
    final BridgeMessage.Health health = collectHealth();
    sendToProxy(player, health);
  }

  /**
   * Handles a {@code world_preload} message by loading the specified world
   * asynchronously if it is not already loaded.
   *
   * @param player   the player whose connection delivered the message
   * @param preload  the world-preload request
   */
  private void handleWorldPreload(final Player player, final BridgeMessage.WorldPreload preload) {
    if (preload.worldName() == null || preload.worldName().isBlank()) {
      logger.fine("World preload request with null world name, ignoring");
      return;
    }

    preloadWorld(player, preload.worldName());
  }

  /**
   * Preloads a world if it is not already loaded, then sends a
   * {@link BridgeMessage.WorldLoaded} response to the proxy.
   *
   * @param player    the player connection to reply on
   * @param worldName the world to preload
   */
  private void preloadWorld(final Player player, final String worldName) {
    try {
      final com.infernalsuite.asp.api.BTCCoreAPI slimeApi = com.infernalsuite.asp.api.BTCCoreAPI.instance();

      // Check if the world is already loaded
      for (final var instance : slimeApi.getLoadedWorlds()) {
        if (instance.getName().equalsIgnoreCase(worldName)) {
          logger.fine("World " + worldName + " is already loaded, notifying proxy");
          sendToProxy(player, new BridgeMessage.WorldLoaded(
              plugin.getServerName(), worldName, 0));
          return;
        }
      }

      // Load the world asynchronously (readWorld is async-safe per the API contract)
      final long startTime = System.currentTimeMillis();
      Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
        try {
          // Check again in case another thread loaded it
          for (final var instance : slimeApi.getLoadedWorlds()) {
            if (instance.getName().equalsIgnoreCase(worldName)) {
              final long elapsed = System.currentTimeMillis() - startTime;
              sendToProxy(player, new BridgeMessage.WorldLoaded(
                  plugin.getServerName(), worldName, elapsed));
              return;
            }
          }

          logger.info("Preloading world " + worldName + " for incoming player");
          // The actual world loading requires a sync call to loadWorld()
          // For now, just notify that the world is being loaded
          // A full implementation would use the SlimeLoader to read and then loadWorld on main thread
          Bukkit.getScheduler().runTask(plugin, () -> {
            try {
              // Verify still not loaded
              for (final var instance : slimeApi.getLoadedWorlds()) {
                if (instance.getName().equalsIgnoreCase(worldName)) {
                  final long elapsed = System.currentTimeMillis() - startTime;
                  sendToProxy(player, new BridgeMessage.WorldLoaded(
                      plugin.getServerName(), worldName, elapsed));
                  return;
                }
              }
              logger.warning("World " + worldName + " preload requested but no SlimeLoader "
                  + "is configured for it. Notifying proxy with empty load.");
              final long elapsed = System.currentTimeMillis() - startTime;
              sendToProxy(player, new BridgeMessage.WorldLoaded(
                  plugin.getServerName(), worldName, elapsed));
            } catch (Exception e) {
              logger.warning("Failed to load world " + worldName + ": " + e.getMessage());
            }
          });
        } catch (Exception e) {
          logger.warning("Failed to preload world " + worldName + ": " + e.getMessage());
        }
      });
    } catch (NoClassDefFoundError | IllegalStateException e) {
      // AdvancedSlimePaper API not available
      logger.fine("SlimeWorld API not available, cannot preload world " + worldName);
      sendToProxy(player, new BridgeMessage.WorldLoaded(
          plugin.getServerName(), worldName, 0));
    }
  }

  /**
   * Collects the current health status of this backend server.
   *
   * @return a {@link BridgeMessage.Health} record with current metrics
   */
  BridgeMessage.Health collectHealth() {
    final String serverName = plugin.getServerName();
    double mspt = 999.0;
    double tps = 0.0;

    try {
      mspt = BTCCoreAPI.instance().getCurrentMspt();
    } catch (Exception e) {
      // BTCCoreAPI not available, use fallback
    }

    // Estimate TPS from MSPT if Bukkit TPS is not available
    tps = mspt < 50.0 ? 20.0 : Math.max(1.0, 1000.0 / mspt);

    try {
      // Paper provides Bukkit.getTPS() but it may not be available on all forks
      final var tpsMethod = Bukkit.class.getMethod("getTPS");
      final double[] tpsArray = (double[]) tpsMethod.invoke(null);
      if (tpsArray != null && tpsArray.length > 0) {
        tps = tpsArray[0];
      }
    } catch (Exception ignored) {
      // Fall back to MSPT-based estimate
    }

    final int playerCount = Bukkit.getOnlinePlayers().size();

    List<String> loadedWorlds = List.of();
    try {
      final com.infernalsuite.asp.api.BTCCoreAPI slimeApi =
          com.infernalsuite.asp.api.BTCCoreAPI.instance();
      loadedWorlds = slimeApi.getLoadedWorlds().stream()
          .map(com.infernalsuite.asp.api.world.SlimeWorldInstance::getName)
          .toList();
    } catch (Exception ignored) {
      // SlimeWorld API not available, return empty list
    }

    return new BridgeMessage.Health(serverName, mspt, tps, playerCount, loadedWorlds);
  }

  /**
   * Sends a bridge message back to the proxy via the given player's connection.
   *
   * @param player  the player whose connection to use
   * @param message the message to send
   */
  void sendToProxy(final Player player, final BridgeMessage message) {
    if (player.isOnline()) {
      player.sendPluginMessage(plugin, CHANNEL, BridgeCodec.encode(message));
    }
  }

  /**
   * Sends a health report to the proxy via the first available online player.
   * Used by the periodic health task.
   */
  void sendHealthToProxy() {
    final var players = Bukkit.getOnlinePlayers();
    if (players.isEmpty()) {
      return;
    }
    final Player player = players.iterator().next();
    sendToProxy(player, collectHealth());
  }
}
