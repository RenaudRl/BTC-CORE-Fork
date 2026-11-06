/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Binds each joining player's platform from this backend's own session (Floodgate), not from the
 * proxy and never from the shape of the UUID.
 *
 * <p>Independent of the bridge: a bridge that fails to start must not cut the binding. Removal at
 * departure already exists elsewhere and is not repeated here.
 *
 * <p>Folia: the event runs on the player's region thread; the sink writes to a concurrent map, so
 * no scheduler is involved.
 */
public final class SessionPlatformBinder implements Listener {

  private final PlatformBinding binding;

  public SessionPlatformBinder(final PlatformBinding binding) {
    this.binding = binding;
  }

  // LOWEST: the platform is known before any other join handler reads it.
  @EventHandler(priority = EventPriority.LOWEST)
  public void onJoin(final PlayerJoinEvent event) {
    binding.bind(event.getPlayer().getUniqueId(), event.getPlayer().getName());
  }
}
