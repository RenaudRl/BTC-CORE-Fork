/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Main plugin class for the BTCBridge backend-side bridge channel handler.
 *
 * <p>This plugin registers the {@code btc:bridge} plugin messaging channel and
 * listens for messages from the BTCVelocity proxy. It also sends periodic
 * health reports to the proxy every 10 seconds (200 ticks).</p>
 *
 * <p>The plugin is Folia-safe: it uses the standard Bukkit scheduler API which
 * Folia redirects to the appropriate region/global scheduler. No direct world
 * or entity access is performed on the main thread without scheduling.</p>
 */
public final class BridgePlugin extends JavaPlugin {

  private static final String CHANNEL = "btc:bridge";

  private BridgeMessageHandler handler;
  private BukkitTask healthTask;

  @Override
  public void onEnable() {
    // Register the bridge channel for incoming and outgoing messages
    Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL,
        handler = new BridgeMessageHandler(this));
    Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);

    // Start periodic health reporting (every 200 ticks = 10 seconds)
    healthTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this,
        () -> handler.sendHealthToProxy(), 100L, 200L);

    getLogger().info("BTCBridge enabled — listening on " + CHANNEL
        + " (server: " + getServerName() + ")");
  }

  @Override
  public void onDisable() {
    if (healthTask != null) {
      healthTask.cancel();
      healthTask = null;
    }
    Bukkit.getMessenger().unregisterIncomingPluginChannel(this, CHANNEL);
    Bukkit.getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
    getLogger().info("BTCBridge disabled");
  }

  /**
   * Returns the name used to identify this backend in bridge messages.
   *
   * <p>Uses the server name from {@code server.properties} ({@code server-name})
   * if set to a non-default value, otherwise falls back to the Bukkit server
   * name or the server port to ensure uniqueness.</p>
   *
   * @return the server identifier string
   */
  public String getServerName() {
    final String bukkitName = Bukkit.getName();
    if (bukkitName != null && !bukkitName.isBlank() && !bukkitName.equals("Unknown Server")) {
      return bukkitName;
    }
    return "backend-" + Bukkit.getPort();
  }
}
