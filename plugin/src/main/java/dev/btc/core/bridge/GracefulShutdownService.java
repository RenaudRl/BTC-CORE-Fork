/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * Empties a backend of its players, then ends it.
 *
 * <p>The Bukkit half of {@link GracefulShutdown}: it asks the proxy to move each player, watches
 * them leave, and ends the server when the last one is gone or when the deadline is reached. The
 * decision itself lives in the pure class and is verified without a server.
 *
 * <h2>Folia</h2>
 *
 * <p>Nothing here blocks a thread. The player list is read on the thread that starts the drain
 * (global region), departures arrive on {@link PlayerQuitEvent}, the deadline is watched by the
 * async scheduler, and the shutdown itself is handed back to the global region scheduler. The state
 * is mutated from those three places, so every entry point is synchronized on this service — the
 * work under the lock is a map operation, never an I/O call.
 */
public final class GracefulShutdownService implements Listener {

  /** How long players are given to leave before the server ends anyway. */
  public static final long DEFAULT_DEADLINE_MILLIS = 20_000L;

  /** How often the deadline is checked. */
  private static final long POLL_MILLIS = 250L;

  private final JavaPlugin plugin;
  private final BridgeMessageHandler handler;
  private final BridgeSettings settings;
  private final long deadlineMillis;

  private @Nullable GracefulShutdown wait;

  /**
   * Creates the service.
   *
   * @param plugin         the owning plugin
   * @param handler        the bridge handler used to reach the proxy
   * @param settings       the bridge settings, for this backend's identity
   * @param deadlineMillis how long players are given to leave, in milliseconds
   */
  public GracefulShutdownService(final JavaPlugin plugin, final BridgeMessageHandler handler,
                                 final BridgeSettings settings, final long deadlineMillis) {
    this.plugin = plugin;
    this.handler = handler;
    this.settings = settings;
    this.deadlineMillis = deadlineMillis;
  }

  /** Whether a drain is under way. */
  public synchronized boolean draining() {
    return wait != null;
  }

  /**
   * Starts emptying this backend towards {@code destination}, then ending it.
   *
   * <p>Must be called from the global region thread: it reads the player list.</p>
   *
   * @param destination the server players are asked to move to
   * @return {@code false} when a drain was already under way
   */
  public synchronized boolean drainThenStop(final String destination) {
    if (wait != null) {
      return false;
    }
    final GracefulShutdown pending = new GracefulShutdown(deadlineMillis);
    final Map<UUID, UUID> asked = new LinkedHashMap<>();
    for (final Player player : Bukkit.getOnlinePlayers()) {
      final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(BridgeMessage.VERSION,
          UUID.randomUUID(), "connect_request", settings.backendId(), "proxy",
          System.currentTimeMillis(),
          System.currentTimeMillis() + BridgeService.responseLifetimeMillis());
      final BridgeMessage.ConnectRequest request =
          new BridgeMessage.ConnectRequest(envelope, player.getUniqueId(), destination);
      asked.put(player.getUniqueId(), envelope.messageId());
      handler.sendToProxy(player, request);
    }

    final GracefulShutdown.Verdict immediate = pending.begin(asked, System.currentTimeMillis());
    if (immediate != null) {
      plugin.getLogger().info("Graceful stop: nobody to move, ending now");
      endServer();
      return true;
    }

    wait = pending;
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    plugin.getLogger().info(() -> "Graceful stop: asked the proxy to move " + asked.size()
        + " player(s) to " + destination + ", waiting up to " + deadlineMillis + " ms");
    plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> tick(),
        POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
    return true;
  }

  /**
   * Feeds the proxy's answer into the wait.
   *
   * @param response the acknowledgement or refusal received from the proxy
   */
  public synchronized void onProxyResponse(final BridgeMessage response) {
    final GracefulShutdown current = wait;
    if (current == null) {
      return;
    }
    switch (response) {
      case BridgeMessage.Ack ack -> current.acknowledged(ack.messageId());
      case BridgeMessage.Nack nack -> {
        plugin.getLogger().warning("Graceful stop: the proxy refused a transfer: "
            + nack.error().name());
        current.refused(nack.messageId());
      }
      default -> {
        // Not an answer to us.
      }
    }
  }

  /** A player leaving is the only proof a transfer actually happened. */
  @EventHandler
  public void onQuit(final PlayerQuitEvent event) {
    synchronized (this) {
      if (wait != null) {
        wait.playerGone(event.getPlayer().getUniqueId());
      }
    }
  }

  private void tick() {
    final GracefulShutdown.Verdict verdict;
    synchronized (this) {
      final GracefulShutdown current = wait;
      if (current == null) {
        return;
      }
      verdict = current.poll(System.currentTimeMillis());
      if (verdict == null) {
        return;
      }
      wait = null;
    }
    HandlerList.unregisterAll(this);
    if (verdict.clean()) {
      plugin.getLogger().info("Graceful stop: every player left, ending the server");
    } else {
      plugin.getLogger().warning("Graceful stop: deadline reached with "
          + verdict.remaining().size() + " player(s) still here and "
          + verdict.refused().size() + " refused by the proxy; ending the server anyway");
    }
    endServer();
  }

  private void endServer() {
    plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> Bukkit.shutdown());
  }
}
