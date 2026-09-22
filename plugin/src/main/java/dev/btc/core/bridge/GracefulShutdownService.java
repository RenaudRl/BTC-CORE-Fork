/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
  private final PlatformSource platforms;

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
    this(plugin, handler, settings, deadlineMillis, FloodgatePlatformSource.createIfPresent());
  }

  /**
   * Creates the service with an explicit platform source, so the decision can be exercised without
   * a server or a Floodgate installation.
   *
   * @param platforms where the platform of a connected player is measured
   */
  public GracefulShutdownService(final JavaPlugin plugin, final BridgeMessageHandler handler,
                                 final BridgeSettings settings, final long deadlineMillis,
                                 final PlatformSource platforms) {
    this.plugin = plugin;
    this.handler = handler;
    this.settings = settings;
    this.deadlineMillis = deadlineMillis;
    this.platforms = platforms;
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
    final String fallback = settings.fallbackTarget();
    final GracefulShutdown pending = new GracefulShutdown(deadlineMillis, fallback != null);
    final Map<UUID, UUID> asked = new LinkedHashMap<>();
    for (final Player player : Bukkit.getOnlinePlayers()) {
      final UUID messageId = askProxyToMove(player, destination);
      if (messageId != null) {
        asked.put(player.getUniqueId(), messageId);
      }
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
        + " player(s) to " + destination + ", waiting up to " + deadlineMillis + " ms"
        + (fallback == null ? " (no fallback declared)" : ", fallback " + fallback));
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
        final UUID retry = current.refused(nack.messageId());
        if (retry != null) {
          askAgainTowardsFallback(current, retry, nack.error());
        }
      }
      default -> {
        // Not an answer to us.
      }
    }
  }

  /**
   * Asks the proxy a second time, towards the declared fallback.
   *
   * <p>A refusal is about the destination, not about the person: full, unknown to the proxy, or
   * refusing this platform. The player is still here — that is what "refused" means — so they can
   * carry their own second request.
   */
  private void askAgainTowardsFallback(final GracefulShutdown current, final UUID player,
                                       final BridgeMessage.ErrorCode error) {
    final String fallback = settings.fallbackTarget();
    if (fallback == null) {
      // Unreachable: the wait only offers a retry when a fallback was declared. Kept so a future
      // change cannot turn "no fallback" into a player silently held in the wait until the deadline.
      current.fallbackImpossible(player);
      return;
    }
    final Player carrier = Bukkit.getPlayer(player);
    if (carrier == null) {
      current.fallbackImpossible(player);
      return;
    }
    final UUID messageId = askProxyToMove(carrier, fallback);
    if (messageId == null) {
      current.fallbackImpossible(player);
      return;
    }
    current.fallbackAsked(player, messageId);
    plugin.getLogger().info(() -> "Graceful stop: " + carrier.getName() + " was refused ("
        + error.name() + "); asking again towards the fallback " + fallback);
  }

  /**
   * Asks the proxy to move one player, and says which message carries the request.
   *
   * @return the id of the request, or {@code null} when the player cannot carry one
   */
  private @Nullable UUID askProxyToMove(final Player player, final String target) {
    if (!player.isOnline()) {
      return null;
    }
    final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(BridgeMessage.VERSION,
        UUID.randomUUID(), "connect_request", settings.backendId(), "proxy",
        System.currentTimeMillis(),
        System.currentTimeMillis() + BridgeService.responseLifetimeMillis());
    // State the platform when this backend can measure it, and nothing when it cannot: a proxy
    // able to check refuses a request that stays silent, and one unable to check refuses a value
    // it cannot confirm. Both refusals are loud; a guess would be silent.
    final BridgeMessage.ConnectRequest request = new BridgeMessage.ConnectRequest(envelope,
        player.getUniqueId(), target, platforms.platformOf(player.getUniqueId()).orElse(null));
    handler.sendToProxy(player, request);
    return envelope.messageId();
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
    final int attempts;
    synchronized (this) {
      final GracefulShutdown current = wait;
      if (current == null) {
        return;
      }
      verdict = current.poll(System.currentTimeMillis());
      if (verdict == null) {
        return;
      }
      attempts = current.fallbackAttempts();
      wait = null;
    }
    HandlerList.unregisterAll(this);
    switch (verdict.outcome()) {
      case NOTHING_TO_DO, DRAINED ->
          plugin.getLogger().info("Graceful stop: every player left, ending the server");
      // Counts say how bad it was; names say who to go and look for. A shutdown that leaves people
      // behind is exactly the moment an operator needs the second, and it is the moment the
      // information is about to disappear with the server.
      case REFUSED -> plugin.getLogger().warning("Graceful stop: the proxy refused to move "
          + verdict.refused().size() + " player(s), before the deadline; ending the server anyway."
          + " Left behind: " + name(verdict.refused()) + attemptsSuffix(attempts));
      case DEADLINE -> plugin.getLogger().warning("Graceful stop: deadline reached with "
          + verdict.remaining().size() + " player(s) still here and "
          + verdict.refused().size() + " refused by the proxy; ending the server anyway."
          + " Still here: " + name(verdict.remaining())
          + "; refused: " + name(verdict.refused()) + attemptsSuffix(attempts));
    }
    endServer();
  }

  private static String attemptsSuffix(final int attempts) {
    return attempts == 0 ? "" : " (" + attempts + " fallback attempt(s) made)";
  }

  /**
   * Names players, falling back to their id.
   *
   * <p>A player left behind is still connected, so the name is normally available. When it is not,
   * the id is printed rather than nothing: an operator can act on an id, not on an empty list.
   */
  private static String name(final Set<UUID> players) {
    if (players.isEmpty()) {
      return "nobody";
    }
    return players.stream()
        .map(id -> {
          final Player player = Bukkit.getPlayer(id);
          return player == null ? id.toString() : player.getName();
        })
        .collect(Collectors.joining(", "));
  }

  private void endServer() {
    plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> Bukkit.shutdown());
  }
}
