/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.util.Optional;
import java.util.UUID;

/**
 * Where this backend learns which client platform a connected player uses, so that a message it
 * sends states a platform the proxy can confirm.
 *
 * <p>Two questions, not one: {@link #canResolve()} is about this deployment, {@link #platformOf}
 * is about one player. Merging them would make "Floodgate is not installed" look the same as
 * "that player is not here", and those call for opposite conduct.
 *
 * <p>When nothing can answer, a message states <em>nothing</em>. It never falls back on JAVA:
 * the proxy would accept a value nobody measured, and the mistake would be invisible precisely
 * where Bedrock players exist.
 */
public interface PlatformSource {

  /** A backend with nothing able to answer. It states no platform rather than guessing one. */
  PlatformSource UNAVAILABLE = new PlatformSource() {
    @Override
    public boolean canResolve() {
      return false;
    }

    @Override
    public Optional<BridgeMessage.Platform> platformOf(final UUID player) {
      return Optional.empty();
    }

    @Override
    public String describe() {
      return "unavailable (no platform source installed)";
    }
  };

  /** Whether this backend can resolve platforms at all. Stable for the life of the server. */
  boolean canResolve();

  /** The platform of a connected player, or empty when they are not here — or nothing can tell. */
  Optional<BridgeMessage.Platform> platformOf(UUID player);

  /** A short, secret-free description for the startup announcement. */
  String describe();
}
