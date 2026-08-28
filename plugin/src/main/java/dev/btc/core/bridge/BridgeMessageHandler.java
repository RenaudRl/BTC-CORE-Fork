/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import com.infernalsuite.asp.plugin.SWPlugin;
import com.infernalsuite.asp.plugin.config.ConfigManager;
import com.infernalsuite.asp.plugin.config.WorldData;
import com.infernalsuite.asp.plugin.loader.LoaderManager;
import dev.btc.core.api.BTCCoreAPI;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.Nullable;

/** Authenticates and dispatches version 2 backend bridge messages. */
public final class BridgeMessageHandler implements PluginMessageListener {

  private final JavaPlugin plugin;
  private final BridgeSettings settings;
  private final Logger logger;
  private final Map<UUID, Long> processedMessages = new LinkedHashMap<>();

  BridgeMessageHandler(final JavaPlugin plugin, final BridgeSettings settings) {
    this.plugin = plugin;
    this.settings = settings;
    this.logger = plugin.getLogger();
  }

  /** Kept package-visible for small adapter tests and security checks. */
  static boolean isClientOrigin(@Nullable final Player player) {
    return player != null;
  }

  @Override
  public void onPluginMessageReceived(final String channel, @Nullable final Player player,
                                      final byte[] data) {
    if (!BridgeService.CHANNEL.equals(channel)) return;
    final BridgeCodec.DecodeResult decoded = BridgeCodec.decodeResult(
        data, System.currentTimeMillis(), settings.limits());
    if (!decoded.accepted()) {
      logger.fine("Rejected bridge payload: " + decoded.error().name());
      return;
    }

    final BridgeMessage message = decoded.message();
    if (!isControlMessage(message)) return;
    if (isClientOrigin(player)) {
      logger.warning("Rejected client-originated bridge control: CLIENT_ORIGIN");
      return;
    }

    final BridgeMessage.ErrorCode authorizationError = authorizationError(message);
    if (authorizationError != null) {
      sendNack(null, message, authorizationError);
      return;
    }
    if (alreadyProcessed(message.messageId())) {
      sendAck(null, message, true);
      return;
    }

    switch (message) {
      case BridgeMessage.QueueJoin join -> {
        logger.fine("Queue join accepted for " + join.username());
        sendAck(null, message, false);
        if (join.targetWorld() != null && !join.targetWorld().isBlank()) {
          preloadWorld(null, message, join.targetWorld());
        }
      }
      case BridgeMessage.QueueLeave ignored -> sendAck(null, message, false);
      case BridgeMessage.RequestStatus status -> {
        sendAck(null, message, false);
        sendHealth(null, status);
      }
      case BridgeMessage.WorldPreload preload -> {
        sendAck(null, message, false);
        preloadWorld(null, message, preload.worldName());
      }
      case BridgeMessage.ConnectRequest ignored -> sendNack(null, message,
          BridgeMessage.ErrorCode.UNSUPPORTED);
      case BridgeMessage.PartyWarp ignored -> sendNack(null, message,
          BridgeMessage.ErrorCode.UNSUPPORTED);
      default -> sendNack(null, message, BridgeMessage.ErrorCode.UNSUPPORTED);
    }
  }

  private boolean isControlMessage(final BridgeMessage message) {
    return switch (message) {
      case BridgeMessage.QueueJoin ignored -> true;
      case BridgeMessage.QueueLeave ignored -> true;
      case BridgeMessage.RequestStatus ignored -> true;
      case BridgeMessage.WorldPreload ignored -> true;
      case BridgeMessage.ConnectRequest ignored -> true;
      case BridgeMessage.PartyWarp ignored -> true;
      default -> false;
    };
  }

  private @Nullable BridgeMessage.ErrorCode authorizationError(final BridgeMessage message) {
    if (!settings.allowedBackends().contains(message.sourceBackend())) {
      return BridgeMessage.ErrorCode.BACKEND_NOT_ALLOWED;
    }
    if (!settings.allowedTargets().contains(message.targetBackend())
        || !settings.backendId().equals(message.targetBackend())) {
      return BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED;
    }
    return payloadAllowed(message);
  }

  private @Nullable BridgeMessage.ErrorCode payloadAllowed(final BridgeMessage message) {
    return switch (message) {
      case BridgeMessage.QueueJoin value -> {
        if (!settings.allowedTargets().contains(value.targetServer())) {
          yield BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED;
        }
        if (value.targetWorld() != null && !settings.allowedWorlds().contains(value.targetWorld())) {
          yield BridgeMessage.ErrorCode.WORLD_NOT_ALLOWED;
        }
        yield null;
      }
      case BridgeMessage.RequestStatus value -> settings.allowedTargets().contains(value.serverName())
          ? null : BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED;
      case BridgeMessage.WorldPreload value -> {
        if (!settings.allowedTargets().contains(value.serverName())) {
          yield BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED;
        }
        if (value.worldName() == null || !settings.allowedWorlds().contains(value.worldName())) {
          yield BridgeMessage.ErrorCode.WORLD_NOT_ALLOWED;
        }
        yield null;
      }
      case BridgeMessage.ConnectRequest value -> settings.allowedTargets().contains(value.targetServer())
          ? null : BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED;
      case BridgeMessage.PartyWarp value -> settings.allowedTargets().contains(value.targetServer())
          ? null : BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED;
      default -> null;
    };
  }

  private synchronized boolean alreadyProcessed(final UUID messageId) {
    final long now = System.currentTimeMillis();
    final Iterator<Map.Entry<UUID, Long>> iterator = processedMessages.entrySet().iterator();
    while (iterator.hasNext()) {
      if (now - iterator.next().getValue() > settings.deduplicationWindowMillis()) {
        iterator.remove();
      }
    }
    if (processedMessages.containsKey(messageId)) return true;
    while (processedMessages.size() >= settings.maxDeduplicationEntries()) {
      processedMessages.remove(processedMessages.keySet().iterator().next());
    }
    processedMessages.put(messageId, now);
    return false;
  }

  private void sendAck(@Nullable final Player carrier, final BridgeMessage request,
                       final boolean duplicate) {
    sendToProxy(carrier, new BridgeMessage.Ack(responseEnvelope(request, "ack"), duplicate));
  }

  private void sendNack(@Nullable final Player carrier, final BridgeMessage request,
                        final BridgeMessage.ErrorCode error) {
    sendToProxy(carrier, new BridgeMessage.Nack(responseEnvelope(request, "nack"), error));
  }

  private void sendHealth(@Nullable final Player carrier, final BridgeMessage request) {
    final BridgeMessage.Health health = collectHealth(responseEnvelope(request, "health"));
    sendToProxy(carrier, health);
  }

  private BridgeMessage.Envelope responseEnvelope(final BridgeMessage request, final String kind) {
    final long issuedAt = System.currentTimeMillis();
    final long expiresAt;
    try {
      expiresAt = Math.addExact(issuedAt, BridgeService.responseLifetimeMillis());
    } catch (ArithmeticException exception) {
      return new BridgeMessage.Envelope(BridgeMessage.VERSION, request.messageId(), kind,
          settings.backendId(), request.sourceBackend(), issuedAt, Long.MAX_VALUE);
    }
    return new BridgeMessage.Envelope(BridgeMessage.VERSION, request.messageId(), kind,
        settings.backendId(), request.sourceBackend(), issuedAt, expiresAt);
  }

  private void preloadWorld(@Nullable final Player carrier, final BridgeMessage request,
                            final String worldName) {
    final long startedAt = System.currentTimeMillis();
    plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
      final AdvancedSlimePaperAPI api;
      try {
        api = AdvancedSlimePaperAPI.instance();
      } catch (RuntimeException exception) {
        sendWorldLoadFailed(carrier, request, worldName, "adapter_unavailable", startedAt);
        return;
      }

      final SlimeWorldInstance existing = api.getLoadedWorld(worldName);
      if (isAvailable(existing)) {
        sendWorldLoaded(carrier, request, worldName, startedAt);
        return;
      }

      final WorldPlan plan = resolveWorldPlan(worldName);
      if (plan == null) {
        sendWorldLoadFailed(carrier, request, worldName, "loader_unavailable", startedAt);
        return;
      }

      plugin.getServer().getAsyncScheduler().runNow(plugin, asyncTask -> {
        final SlimeWorld world;
        try {
          world = api.readWorld(plan.loader(), worldName, plan.readOnly(), plan.properties());
        } catch (Exception exception) {
          sendWorldLoadFailed(carrier, request, worldName, classifyLoadFailure(exception), startedAt);
          return;
        }
        plugin.getServer().getGlobalRegionScheduler().run(plugin, globalTask -> {
          try {
            final SlimeWorldInstance loaded = api.getLoadedWorld(worldName);
            if (!isAvailable(loaded)) api.loadWorld(world, true);
            if (isAvailable(api.getLoadedWorld(worldName))) {
              sendWorldLoaded(carrier, request, worldName, startedAt);
            } else {
              sendWorldLoadFailed(carrier, request, worldName, "world_not_available", startedAt);
            }
          } catch (RuntimeException exception) {
            sendWorldLoadFailed(carrier, request, worldName, "runtime_load_failed", startedAt);
          }
        });
      });
    });
  }

  private boolean isAvailable(@Nullable final SlimeWorldInstance world) {
    if (world == null) return false;
    try {
      return world.getBukkitWorld() != null;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private void sendWorldLoaded(@Nullable final Player carrier, final BridgeMessage request,
                               final String worldName, final long startedAt) {
    sendToProxy(carrier, new BridgeMessage.WorldLoaded(
        responseEnvelope(request, "world_loaded"), settings.backendId(), worldName,
        elapsedMillis(startedAt)));
  }

  private void sendWorldLoadFailed(@Nullable final Player carrier, final BridgeMessage request,
                                   final String worldName, final String reason,
                                   final long startedAt) {
    sendToProxy(carrier, new BridgeMessage.WorldLoadFailed(
        responseEnvelope(request, "world_load_failed"), settings.backendId(), worldName,
        reason, elapsedMillis(startedAt)));
  }

  private long elapsedMillis(final long startedAt) {
    return Math.max(0L, System.currentTimeMillis() - startedAt);
  }

  private String classifyLoadFailure(final Exception exception) {
    final String name = exception.getClass().getSimpleName();
    if (name.contains("UnknownWorld")) return "world_not_found";
    if (name.contains("Corrupt")) return "world_corrupt";
    if (name.contains("NewerFormat")) return "world_format_unsupported";
    return "world_read_failed";
  }

  /**
   * Looks up how a world should be read.
   *
   * <p>Used to go through reflection and a scan of the plugin list, because the bridge shipped as a
   * separate plugin and could not see the world runtime's classes. Both live in the same jar now, so
   * the call is direct: a missing world or loader is reported as such instead of being indistinguishable
   * from a signature that drifted.
   *
   * @return the plan, or {@code null} when the world is unknown or its data source has no loader
   */
  private @Nullable WorldPlan resolveWorldPlan(final String worldName) {
    final LoaderManager loaderManager = SWPlugin.getInstance().getLoaderManager();
    if (loaderManager == null) {
      return null;
    }
    final WorldData worldData = findWorldData(worldName);
    if (worldData == null) {
      return null;
    }
    final SlimeLoader loader = loaderManager.getLoader(worldData.getDataSource());
    return loader == null ? null
        : new WorldPlan(loader, worldData.isReadOnly(), worldData.toPropertyMap());
  }

  private @Nullable WorldData findWorldData(final String worldName) {
    final Map<String, WorldData> worlds = ConfigManager.getWorldConfig().getWorlds();
    final WorldData direct = worlds.get(worldName);
    if (direct != null) return direct;
    for (Map.Entry<String, WorldData> entry : worlds.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(worldName)) {
        return entry.getValue();
      }
    }
    return null;
  }

  void sendHealthToProxy() {
    final BridgeMessage.Health health = collectHealth(null);
    sendToProxy(null, health);
  }

  BridgeMessage.Health collectHealth() {
    return collectHealth(null);
  }

  private BridgeMessage.Health collectHealth(@Nullable final BridgeMessage.Envelope envelope) {
    double mspt = 0.0;
    double tps = 0.0;
    try {
      mspt = Math.max(0.0, BTCCoreAPI.instance().getCurrentMspt());
    } catch (RuntimeException ignored) {
      // The bridge remains useful when the optional metrics API is unavailable.
    }
    try {
      final Method method = Bukkit.class.getMethod("getTPS");
      final double[] values = (double[]) method.invoke(null);
      if (values != null && values.length > 0 && Double.isFinite(values[0])) {
        tps = Math.max(0.0, values[0]);
      }
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // Paper's TPS API is optional across supported server variants.
    }
    List<String> loadedWorlds = List.of();
    try {
      loadedWorlds = AdvancedSlimePaperAPI.instance().getLoadedWorlds().stream()
          .map(SlimeWorldInstance::getName)
          .limit(settings.limits().maxLoadedWorlds())
          .toList();
    } catch (RuntimeException exception) { }
    final BridgeMessage.Envelope healthEnvelope = envelope == null
        ? new BridgeMessage.Envelope(BridgeMessage.VERSION, UUID.randomUUID(), "health",
            settings.backendId(), "proxy", System.currentTimeMillis(),
            System.currentTimeMillis() + BridgeService.responseLifetimeMillis())
        : envelope;
    return new BridgeMessage.Health(healthEnvelope, settings.backendId(), mspt, tps,
        Bukkit.getOnlinePlayers().size(), loadedWorlds);
  }

  void sendToProxy(@Nullable final Player preferred, final BridgeMessage message) {
    final Player carrier = preferred != null && preferred.isOnline() ? preferred : firstOnlinePlayer();
    if (carrier == null) return;
    final byte[] bytes;
    try {
      bytes = BridgeCodec.encode(message, settings.limits());
    } catch (IllegalArgumentException exception) {
      logger.warning("Dropped oversized or invalid bridge response: INVALID_ENVELOPE");
      return;
    }
    carrier.getScheduler().run(plugin, task -> {
      if (carrier.isOnline()) carrier.sendPluginMessage(plugin, BridgeService.CHANNEL, bytes);
    }, () -> { });
  }

  private @Nullable Player firstOnlinePlayer() {
    final Iterator<? extends Player> iterator = Bukkit.getOnlinePlayers().iterator();
    return iterator.hasNext() ? iterator.next() : null;
  }

  private record WorldPlan(SlimeLoader loader, boolean readOnly, SlimePropertyMap properties) { }
}
