/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Mirror of the proxy-side sealed message hierarchy for the {@code btc:bridge} channel.
 *
 * <p>These records must stay in sync with {@code com.btcvelocity.api.bridge.BridgeMessage}
 * on the proxy side. The JSON wire format is identical: a {@code "type"} discriminator
 * plus the record fields.</p>
 */
public sealed interface BridgeMessage {

  String type();

  record QueueJoin(UUID uuid, String username, String targetServer,
                   @Nullable String targetWorld) implements BridgeMessage {
    @Override
    public String type() {
      return "queue_join";
    }
  }

  record QueueLeave(UUID uuid) implements BridgeMessage {
    @Override
    public String type() {
      return "queue_leave";
    }
  }

  record RequestStatus(String serverName) implements BridgeMessage {
    @Override
    public String type() {
      return "request_status";
    }
  }

  record WorldPreload(String serverName, @Nullable String worldName) implements BridgeMessage {
    @Override
    public String type() {
      return "world_preload";
    }
  }

  record Health(String serverName, double mspt, double tps, int playerCount,
                List<String> loadedWorlds) implements BridgeMessage {
    @Override
    public String type() {
      return "health";
    }
  }

  record WorldLoaded(String serverName, String worldName, long loadTimeMs) implements BridgeMessage {
    @Override
    public String type() {
      return "world_loaded";
    }
  }

  record WorldUnloaded(String serverName, String worldName) implements BridgeMessage {
    @Override
    public String type() {
      return "world_unloaded";
    }
  }

  record QueueStatusResponse(String serverName, int backendQueueSize) implements BridgeMessage {
    @Override
    public String type() {
      return "queue_status_response";
    }
  }

  /**
   * Backend -&gt; Proxy: move a single player (by UUID) to another backend server.
   * Mirrors {@code com.btcvelocity.api.bridge.BridgeMessage.ConnectRequest}.
   */
  record ConnectRequest(UUID uuid, String targetServer) implements BridgeMessage {
    @Override
    public String type() {
      return "connect_request";
    }
  }

  /**
   * Backend -&gt; Proxy: move a group of players (e.g. a party) to a destination server.
   * Mirrors {@code com.btcvelocity.api.bridge.BridgeMessage.PartyWarp}.
   */
  record PartyWarp(List<UUID> members, String targetServer) implements BridgeMessage {
    @Override
    public String type() {
      return "party_warp";
    }
  }
}
