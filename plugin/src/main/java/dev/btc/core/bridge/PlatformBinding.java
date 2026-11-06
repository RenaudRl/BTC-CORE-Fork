/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * The binding decision of {@link SessionPlatformBinder}, free of Bukkit so that it can be checked
 * with nothing but the JDK. What the source states is what gets bound: the UUID is never read.
 */
public final class PlatformBinding {

  /** Where a binding goes; production converts to the integrity platform's own enum. */
  @FunctionalInterface
  public interface Sink {
    void bind(UUID player, BridgeMessage.Platform platform, String source);
  }

  private final PlatformSource source;
  private final Sink sink;
  private final Logger logger;

  public PlatformBinding(final PlatformSource source, final Sink sink, final Logger logger) {
    this.source = source;
    this.sink = sink;
    this.logger = logger;
  }

  /** Binds when the source answers; a silent source binds nothing, it never defaults to JAVA. */
  public void bind(final UUID player, final String name) {
    source.platformOf(player).ifPresent(platform -> {
      sink.bind(player, platform, source.describe());
      // Name only: the UUID stays out of the log.
      logger.info(() -> "Platform of " + name + " bound: " + platform);
    });
  }
}
