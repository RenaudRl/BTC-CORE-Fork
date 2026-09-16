/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Resolves a connected player's platform from Bukkit plus Floodgate.
 *
 * <p>Both are needed. Bukkit knows who is on this server; Floodgate knows which of them arrived
 * through Bedrock. Asking Floodgate alone would make an absent player look exactly like a Java one
 * — both are simply "not a Floodgate player" — and this backend would then state JAVA about people
 * it cannot see.
 *
 * <p>{@code isFloodgateId(UUID)} is deliberately not used: it reads the shape of a UUID, which is
 * the inference the bridge contract forbids.
 *
 * <p>Read-only and thread-safe to call: {@code Bukkit.getPlayer(UUID)} consults the online player
 * map, which is why this never needs a region scheduler.
 */
public final class FloodgatePlatformSource implements PlatformSource {

  private static final String FLOODGATE_API = "org.geysermc.floodgate.api.FloodgateApi";

  private final FloodgateApi floodgate;

  private FloodgatePlatformSource(final FloodgateApi floodgate) {
    this.floodgate = floodgate;
  }

  /**
   * Returns a Floodgate-backed source, or {@link PlatformSource#UNAVAILABLE} when Floodgate is not
   * installed here — the production case today. Absence is a fact about the deployment, not an
   * error: the caller announces it at startup.
   */
  public static PlatformSource createIfPresent() {
    try {
      Class.forName(FLOODGATE_API, false, FloodgatePlatformSource.class.getClassLoader());
      final FloodgateApi api = FloodgateApi.getInstance();
      if (api == null) {
        // On the classpath but not started: claiming we can resolve would be worse than admitting
        // we cannot.
        return PlatformSource.UNAVAILABLE;
      }
      return new FloodgatePlatformSource(api);
    } catch (ClassNotFoundException | LinkageError absent) {
      return PlatformSource.UNAVAILABLE;
    }
  }

  @Override
  public boolean canResolve() {
    return true;
  }

  @Override
  public Optional<BridgeMessage.Platform> platformOf(final UUID player) {
    if (Bukkit.getPlayer(player) == null) {
      return Optional.empty();
    }
    return Optional.of(floodgate.isFloodgatePlayer(player)
        ? BridgeMessage.Platform.BEDROCK
        : BridgeMessage.Platform.JAVA);
  }

  @Override
  public String describe() {
    return "Floodgate";
  }
}
