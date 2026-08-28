/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Versioned, typed messages exchanged by the BTC-CORE bridge. */
public sealed interface BridgeMessage permits BridgeMessage.QueueJoin, BridgeMessage.QueueLeave,
    BridgeMessage.RequestStatus, BridgeMessage.WorldPreload, BridgeMessage.Health,
    BridgeMessage.WorldLoaded, BridgeMessage.WorldLoadFailed, BridgeMessage.WorldUnloaded,
    BridgeMessage.QueueStatusResponse, BridgeMessage.ConnectRequest, BridgeMessage.PartyWarp,
    BridgeMessage.Ack, BridgeMessage.Nack {

  int VERSION = 2;

  Envelope envelope();

  default int version() {
    return envelope().version();
  }

  default UUID messageId() {
    return envelope().messageId();
  }

  default String kind() {
    return envelope().type();
  }

  /** Compatibility alias for code that used the pre-v2 discriminator. */
  default String type() {
    return envelope().type();
  }

  default String sourceBackend() {
    return envelope().sourceBackend();
  }

  default String targetBackend() {
    return envelope().targetBackend();
  }

  default long issuedAt() {
    return envelope().issuedAt();
  }

  default long expiresAt() {
    return envelope().expiresAt();
  }

  record Envelope(int version, UUID messageId, String type, String sourceBackend,
                  String targetBackend, long issuedAt, long expiresAt) {
    public Envelope {
      Objects.requireNonNull(messageId, "messageId");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(sourceBackend, "sourceBackend");
      Objects.requireNonNull(targetBackend, "targetBackend");
      if (version <= 0 || issuedAt < 0 || expiresAt < 0) {
        throw new IllegalArgumentException("invalid bridge envelope");
      }
    }

    public static Envelope now(final String type, final String sourceBackend,
                               final String targetBackend, final long lifetimeMillis) {
      final long issuedAt = System.currentTimeMillis();
      return new Envelope(VERSION, UUID.randomUUID(), type, sourceBackend, targetBackend,
          issuedAt, Math.addExact(issuedAt, lifetimeMillis));
    }
  }

  record QueueJoin(Envelope envelope, UUID uuid, String username, String targetServer,
                   @Nullable String targetWorld) implements BridgeMessage {
    public QueueJoin {
      requireKind(envelope, "queue_join");
      Objects.requireNonNull(uuid, "uuid");
      Objects.requireNonNull(username, "username");
      Objects.requireNonNull(targetServer, "targetServer");
    }

    public QueueJoin(final UUID uuid, final String username, final String targetServer,
                     @Nullable final String targetWorld) {
      this(Envelope.now("queue_join", "legacy", targetServer, 30_000L), uuid, username,
          targetServer, targetWorld);
    }
  }

  record QueueLeave(Envelope envelope, UUID uuid) implements BridgeMessage {
    public QueueLeave {
      requireKind(envelope, "queue_leave");
      Objects.requireNonNull(uuid, "uuid");
    }

    public QueueLeave(final UUID uuid) {
      this(Envelope.now("queue_leave", "legacy", "proxy", 30_000L), uuid);
    }
  }

  record RequestStatus(Envelope envelope, String serverName) implements BridgeMessage {
    public RequestStatus {
      requireKind(envelope, "request_status");
      Objects.requireNonNull(serverName, "serverName");
    }

    public RequestStatus(final String serverName) {
      this(Envelope.now("request_status", "legacy", serverName, 30_000L), serverName);
    }
  }

  record WorldPreload(Envelope envelope, String serverName, @Nullable String worldName)
      implements BridgeMessage {
    public WorldPreload {
      requireKind(envelope, "world_preload");
      Objects.requireNonNull(serverName, "serverName");
    }

    public WorldPreload(final String serverName, @Nullable final String worldName) {
      this(Envelope.now("world_preload", "legacy", serverName, 30_000L), serverName, worldName);
    }
  }

  record Health(Envelope envelope, String serverName, double mspt, double tps, int playerCount,
                List<String> loadedWorlds) implements BridgeMessage {
    public Health {
      requireKind(envelope, "health");
      Objects.requireNonNull(serverName, "serverName");
      Objects.requireNonNull(loadedWorlds, "loadedWorlds");
      loadedWorlds = List.copyOf(loadedWorlds);
    }

    public Health(final String serverName, final double mspt, final double tps,
                  final int playerCount, final List<String> loadedWorlds) {
      this(Envelope.now("health", serverName, "proxy", 30_000L), serverName, mspt, tps,
          playerCount, loadedWorlds);
    }
  }

  record WorldLoaded(Envelope envelope, String serverName, String worldName, long loadTimeMs)
      implements BridgeMessage {
    public WorldLoaded {
      requireKind(envelope, "world_loaded");
      Objects.requireNonNull(serverName, "serverName");
      Objects.requireNonNull(worldName, "worldName");
    }

    public WorldLoaded(final String serverName, final String worldName, final long loadTimeMs) {
      this(Envelope.now("world_loaded", serverName, "proxy", 30_000L), serverName, worldName,
          loadTimeMs);
    }
  }

  record WorldLoadFailed(Envelope envelope, String serverName, String worldName, String reason,
                         long loadTimeMs) implements BridgeMessage {
    public WorldLoadFailed {
      requireKind(envelope, "world_load_failed");
      Objects.requireNonNull(serverName, "serverName");
      Objects.requireNonNull(worldName, "worldName");
      Objects.requireNonNull(reason, "reason");
    }
  }

  record WorldUnloaded(Envelope envelope, String serverName, String worldName)
      implements BridgeMessage {
    public WorldUnloaded {
      requireKind(envelope, "world_unloaded");
      Objects.requireNonNull(serverName, "serverName");
      Objects.requireNonNull(worldName, "worldName");
    }

    public WorldUnloaded(final String serverName, final String worldName) {
      this(Envelope.now("world_unloaded", serverName, "proxy", 30_000L), serverName, worldName);
    }
  }

  record QueueStatusResponse(Envelope envelope, String serverName, int backendQueueSize)
      implements BridgeMessage {
    public QueueStatusResponse {
      requireKind(envelope, "queue_status_response");
      Objects.requireNonNull(serverName, "serverName");
    }

    public QueueStatusResponse(final String serverName, final int backendQueueSize) {
      this(Envelope.now("queue_status_response", serverName, "proxy", 30_000L), serverName,
          backendQueueSize);
    }
  }

  record ConnectRequest(Envelope envelope, UUID uuid, String targetServer)
      implements BridgeMessage {
    public ConnectRequest {
      requireKind(envelope, "connect_request");
      Objects.requireNonNull(uuid, "uuid");
      Objects.requireNonNull(targetServer, "targetServer");
    }

    public ConnectRequest(final UUID uuid, final String targetServer) {
      this(Envelope.now("connect_request", "legacy", targetServer, 30_000L), uuid, targetServer);
    }
  }

  record PartyWarp(Envelope envelope, List<UUID> members, String targetServer)
      implements BridgeMessage {
    public PartyWarp {
      requireKind(envelope, "party_warp");
      Objects.requireNonNull(members, "members");
      Objects.requireNonNull(targetServer, "targetServer");
      members = List.copyOf(members);
    }

    public PartyWarp(final List<UUID> members, final String targetServer) {
      this(Envelope.now("party_warp", "legacy", targetServer, 30_000L), members, targetServer);
    }
  }

  record Ack(Envelope envelope, boolean duplicate) implements BridgeMessage {
    public Ack {
      requireKind(envelope, "ack");
    }
  }

  record Nack(Envelope envelope, ErrorCode error) implements BridgeMessage {
    public Nack {
      requireKind(envelope, "nack");
      Objects.requireNonNull(error, "error");
    }
  }

  enum ErrorCode {
    INVALID_ENVELOPE,
    PAYLOAD_TOO_LARGE,
    EXPIRED,
    DUPLICATE,
    CLIENT_ORIGIN,
    BACKEND_NOT_ALLOWED,
    TARGET_NOT_ALLOWED,
    WORLD_NOT_ALLOWED,
    UNSUPPORTED,
    WORLD_LOAD_FAILED,
    INTERNAL_ERROR
  }

  private static void requireKind(final Envelope envelope, final String expected) {
    Objects.requireNonNull(envelope, "envelope");
    if (envelope.version() != VERSION || !expected.equals(envelope.type())) {
      throw new IllegalArgumentException("envelope does not describe " + expected);
    }
  }
}
