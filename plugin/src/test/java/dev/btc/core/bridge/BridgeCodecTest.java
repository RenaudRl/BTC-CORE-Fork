/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Dependency-free targeted checks for the bridge contract.
 *
 * <p>The module intentionally has no test framework dependency. Gradle compiles this class and
 * the verification command runs its {@code main} method with assertions enabled.</p>
 */
public final class BridgeCodecTest {
  private static final long NOW = 1_000_000L;
  private static final BridgeCodec.Limits LIMITS = new BridgeCodec.Limits(
      4 * 1024, 128, 64, 32, 1, 2, 10_000L, 0L);

  private BridgeCodecTest() {
  }

  public static void main(final String[] args) {
    roundTripTypedPayload();
    rejectsOversizedWirePayload();
    rejectsOversizedParty();
    rejectsUnknownEnvelopeField();
    rejectsLegacyEnvelopeAliases();
    rejectsExpiredMessage();
    preservesAckCorrelationId();
    roundTripsTypedWorldLoadFailure();
    preservesBackendOriginFields();
    System.out.println("BridgeCodecTest: all targeted checks passed");
  }

  private static void roundTripTypedPayload() {
    final UUID messageId = UUID.randomUUID();
    final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(
        2, messageId, "queue_join", "proxy-a", "backend-a", NOW - 100, NOW + 500);
    final BridgeMessage.QueueJoin original = new BridgeMessage.QueueJoin(
        envelope, UUID.randomUUID(), "PlayerOne", "backend-a", "arena");
    final BridgeCodec.DecodeResult result = BridgeCodec.decodeResult(
        BridgeCodec.encode(original, LIMITS), NOW, LIMITS);
    check(result.accepted(), "valid V2 envelope must decode");
    check(result.message() instanceof BridgeMessage.QueueJoin, "payload type must be preserved");
    check(messageId.equals(result.messageId()), "messageId must survive the round trip");
  }

  private static void rejectsOversizedWirePayload() {
    final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(
        2, UUID.randomUUID(), "queue_join", "proxy-a", "backend-a", NOW, NOW + 500);
    final BridgeMessage.QueueJoin message = new BridgeMessage.QueueJoin(
        envelope, UUID.randomUUID(), "PlayerOne", "backend-a", "x".repeat(80));
    boolean rejected = false;
    try {
      BridgeCodec.encode(message, new BridgeCodec.Limits(1024, 128, 64, 32, 2, 2, 10_000L, 0L));
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected, "oversized payload must be rejected before transport");
    final BridgeCodec.DecodeResult wireResult = BridgeCodec.decodeResult(
        new byte[1025], NOW, new BridgeCodec.Limits(1024, 128, 64, 32, 2, 2, 10_000L, 0L));
    check(wireResult.error() == BridgeCodec.DecodeError.PAYLOAD_TOO_LARGE,
        "oversized wire input must be rejected before parsing");
  }

  private static void rejectsOversizedParty() {
    final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(
        2, UUID.randomUUID(), "party_warp", "proxy-a", "backend-a", NOW, NOW + 500);
    final BridgeMessage.PartyWarp party = new BridgeMessage.PartyWarp(envelope,
        List.of(UUID.randomUUID(), UUID.randomUUID()), "backend-a");
    boolean rejected = false;
    try {
      BridgeCodec.encode(party, LIMITS);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected, "party member count must be bounded");
  }

  private static void rejectsUnknownEnvelopeField() {
    final BridgeMessage message = new BridgeMessage.QueueLeave(
        new BridgeMessage.Envelope(2, UUID.randomUUID(), "queue_leave", "proxy-a", "backend-a",
            NOW, NOW + 500), UUID.randomUUID());
    final String json = new String(BridgeCodec.encode(message, LIMITS), StandardCharsets.UTF_8);
    final byte[] withUnknownField = json.replace("{\"version\"", "{\"unexpected\":true,\"version\"")
        .getBytes(StandardCharsets.UTF_8);
    final BridgeCodec.DecodeResult result = BridgeCodec.decodeResult(withUnknownField, NOW, LIMITS);
    check(result.error() == BridgeCodec.DecodeError.UNKNOWN_FIELD,
        "unknown envelope fields must be rejected stably");
  }

  private static void rejectsExpiredMessage() {
    final BridgeMessage message = new BridgeMessage.QueueLeave(
        new BridgeMessage.Envelope(2, UUID.randomUUID(), "queue_leave", "proxy-a", "backend-a",
            NOW - 500, NOW - 1), UUID.randomUUID());
    final BridgeCodec.DecodeResult result = BridgeCodec.decodeResult(
        BridgeCodec.encode(message, LIMITS), NOW, LIMITS);
    check(result.error() == BridgeCodec.DecodeError.EXPIRED, "expired messages must not dispatch");
  }

  private static void rejectsLegacyEnvelopeAliases() {
    final BridgeMessage message = new BridgeMessage.QueueLeave(
        new BridgeMessage.Envelope(2, UUID.randomUUID(), "queue_leave", "proxy-a", "backend-a",
            NOW, NOW + 500), UUID.randomUUID());
    final String json = new String(BridgeCodec.encode(message, LIMITS), StandardCharsets.UTF_8);
    final byte[] withKindAlias = json.replace("\"type\"", "\"kind\"")
        .getBytes(StandardCharsets.UTF_8);
    final BridgeCodec.DecodeResult kindResult = BridgeCodec.decodeResult(
        withKindAlias, NOW, LIMITS);
    check(kindResult.error() == BridgeCodec.DecodeError.UNKNOWN_FIELD,
        "kind must not be accepted as a wire alias for type");
    final byte[] withCorrelationAlias = json.replace("\"payload\"", "\"correlationId\":true,\"payload\"")
        .getBytes(StandardCharsets.UTF_8);
    final BridgeCodec.DecodeResult correlationResult = BridgeCodec.decodeResult(
        withCorrelationAlias, NOW, LIMITS);
    check(correlationResult.error() == BridgeCodec.DecodeError.UNKNOWN_FIELD,
        "correlationId must not be accepted as a wire alias");
  }

  private static void preservesAckCorrelationId() {
    final UUID requestId = UUID.randomUUID();
    final BridgeMessage.Ack ack = new BridgeMessage.Ack(new BridgeMessage.Envelope(
        2, requestId, "ack", "backend-a", "proxy-a", NOW, NOW + 500), true);
    final BridgeCodec.DecodeResult result = BridgeCodec.decodeResult(
        BridgeCodec.encode(ack, LIMITS), NOW, LIMITS);
    final BridgeMessage decoded = result.message();
    check(result.accepted() && decoded instanceof BridgeMessage.Ack
            && requestId.equals(decoded.messageId()),
        "ACK must carry the original messageId");
  }

  private static void roundTripsTypedWorldLoadFailure() {
    final BridgeMessage failure = new BridgeMessage.WorldLoadFailed(
        new BridgeMessage.Envelope(2, UUID.randomUUID(), "world_load_failed", "backend-a", "proxy-a",
            NOW, NOW + 500), "backend-a", "arena", "loader_unavailable", 12L);
    final BridgeCodec.DecodeResult result = BridgeCodec.decodeResult(
        BridgeCodec.encode(failure, LIMITS), NOW, LIMITS);
    check(result.accepted() && result.message() instanceof BridgeMessage.WorldLoadFailed,
        "preload failure must be represented by a typed response");
  }

  private static void preservesBackendOriginFields() {
    final BridgeMessage message = new BridgeMessage.QueueLeave(
        new BridgeMessage.Envelope(2, UUID.randomUUID(), "queue_leave", "proxy-a", "backend-a",
            NOW, NOW + 500), UUID.randomUUID());
    final BridgeMessage decoded = BridgeCodec.decodeResult(
        BridgeCodec.encode(message, LIMITS), NOW, LIMITS).message();
    check("proxy-a".equals(decoded.sourceBackend()) && "backend-a".equals(decoded.targetBackend()),
        "V2 origin and target fields must be preserved for handler authorization");
  }

  private static void check(final boolean condition, final String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
