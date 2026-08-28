/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Strict, bounded JSON codec for the version 2 bridge envelope. */
public final class BridgeCodec {

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
  private static final Set<String> ENVELOPE_FIELDS = Set.of(
      "version", "messageId", "type", "sourceBackend", "targetBackend", "issuedAt",
      "expiresAt", "payload");
  private static final Set<String> KINDS = Set.of(
      "queue_join", "queue_leave", "request_status", "world_preload", "health",
      "world_loaded", "world_load_failed", "world_unloaded", "queue_status_response",
      "connect_request", "party_warp", "ack", "nack");

  private BridgeCodec() {
  }

  /** Operational limits applied to both encoding and decoding. */
  public record Limits(int maxPayloadBytes, int maxStringLength, int maxWorldNameLength,
                       int maxUsernameLength, int maxPartyMembers, int maxLoadedWorlds,
                       long maxMessageLifetimeMillis, long maxClockSkewMillis) {
    public Limits {
      if (maxPayloadBytes <= 0 || maxStringLength <= 0 || maxWorldNameLength <= 0
          || maxUsernameLength <= 0 || maxPartyMembers <= 0 || maxLoadedWorlds <= 0
          || maxMessageLifetimeMillis <= 0 || maxClockSkewMillis < 0) {
        throw new IllegalArgumentException("bridge limits must be positive");
      }
    }

    public static Limits defaults() {
      return new Limits(32 * 1024, 128, 64, 32, 64, 32, 300_000L, 30_000L);
    }
  }

  public enum DecodeError {
    NONE,
    EMPTY,
    PAYLOAD_TOO_LARGE,
    INVALID_UTF8,
    MALFORMED,
    UNSUPPORTED_VERSION,
    EXPIRED,
    NOT_YET_VALID,
    UNKNOWN_KIND,
    UNKNOWN_FIELD,
    INVALID_FIELD
  }

  public record DecodeResult(@Nullable BridgeMessage message, @Nullable UUID messageId,
                             DecodeError error) {
    public boolean accepted() {
      return error == DecodeError.NONE && message != null;
    }
  }

  public static byte[] encode(final BridgeMessage message) {
    return encode(message, Limits.defaults());
  }

  public static byte[] encode(final BridgeMessage message, final Limits limits) {
    if (message == null || limits == null) {
      throw new IllegalArgumentException("message and limits are required");
    }
    validateMessage(message, limits);
    final JsonObject root = new JsonObject();
    addEnvelope(root, message.envelope());
    root.add("payload", encodePayload(message));
    final byte[] bytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    if (bytes.length > limits.maxPayloadBytes()) {
      throw new IllegalArgumentException("bridge payload exceeds configured limit");
    }
    return bytes;
  }

  /** Compatibility decoder. Invalid input is represented by {@code null}. */
  public static @Nullable BridgeMessage decode(final byte[] data) {
    final DecodeResult result = decodeResult(data, System.currentTimeMillis(), Limits.defaults());
    return result.message();
  }

  public static DecodeResult decodeResult(final byte[] data, final long nowMillis,
                                          final Limits limits) {
    if (data == null || data.length == 0) {
      return result(null, DecodeError.EMPTY);
    }
    if (limits == null || data.length > limits.maxPayloadBytes()) {
      return result(null, DecodeError.PAYLOAD_TOO_LARGE);
    }

    final String json;
    try {
      final CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(data));
      json = chars.toString();
    } catch (CharacterCodingException exception) {
      return result(null, DecodeError.INVALID_UTF8);
    }

    try {
      final JsonElement parsed = JsonParser.parseString(json);
      if (!parsed.isJsonObject()) {
        return result(null, DecodeError.MALFORMED);
      }
      final JsonObject root = parsed.getAsJsonObject();
      if (!hasExactly(root, ENVELOPE_FIELDS)) {
        return result(null, DecodeError.UNKNOWN_FIELD);
      }
      final UUID messageId = uuid(root, "messageId");
      final int version = integer(root, "version");
      if (version != BridgeMessage.VERSION) {
        return new DecodeResult(null, messageId, DecodeError.UNSUPPORTED_VERSION);
      }
      final String kind = string(root, "type", limits.maxStringLength(), false);
      if (!KINDS.contains(kind)) {
        return new DecodeResult(null, messageId, DecodeError.UNKNOWN_KIND);
      }
      final String source = string(root, "sourceBackend", limits.maxStringLength(), false);
      final String target = string(root, "targetBackend", limits.maxStringLength(), false);
      final long issuedAt = nonNegativeLong(root, "issuedAt");
      final long expiresAt = nonNegativeLong(root, "expiresAt");
      if (expiresAt <= issuedAt || lifetimeTooLong(issuedAt, expiresAt,
          limits.maxMessageLifetimeMillis())) {
        return new DecodeResult(null, messageId, DecodeError.INVALID_FIELD);
      }
      if (issuedAt > safeAdd(nowMillis, limits.maxClockSkewMillis())) {
        return new DecodeResult(null, messageId, DecodeError.NOT_YET_VALID);
      }
      if (expiresAt < nowMillis) {
        return new DecodeResult(null, messageId, DecodeError.EXPIRED);
      }
      final JsonElement payloadElement = root.get("payload");
      if (payloadElement == null || !payloadElement.isJsonObject()) {
        return new DecodeResult(null, messageId, DecodeError.INVALID_FIELD);
      }
      final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(version, messageId, kind,
          source, target, issuedAt, expiresAt);
      final BridgeMessage message = decodePayload(kind, envelope, payloadElement.getAsJsonObject(),
          limits);
      return new DecodeResult(message, messageId, DecodeError.NONE);
    } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException
             exception) {
      return result(null, DecodeError.INVALID_FIELD);
    } catch (RuntimeException exception) {
      return result(null, DecodeError.MALFORMED);
    }
  }

  private static DecodeResult result(final @Nullable BridgeMessage message,
                                     final DecodeError error) {
    return new DecodeResult(message, message == null ? null : message.messageId(), error);
  }

  private static void addEnvelope(final JsonObject root, final BridgeMessage.Envelope envelope) {
    root.addProperty("version", envelope.version());
    root.addProperty("messageId", envelope.messageId().toString());
    root.addProperty("type", envelope.type());
    root.addProperty("sourceBackend", envelope.sourceBackend());
    root.addProperty("targetBackend", envelope.targetBackend());
    root.addProperty("issuedAt", envelope.issuedAt());
    root.addProperty("expiresAt", envelope.expiresAt());
  }

  private static JsonObject encodePayload(final BridgeMessage message) {
    final JsonObject payload = new JsonObject();
    switch (message) {
      case BridgeMessage.QueueJoin value -> {
        payload.addProperty("uuid", value.uuid().toString());
        payload.addProperty("username", value.username());
        payload.addProperty("targetServer", value.targetServer());
        addNullable(payload, "targetWorld", value.targetWorld());
      }
      case BridgeMessage.QueueLeave value -> payload.addProperty("uuid", value.uuid().toString());
      case BridgeMessage.RequestStatus value -> payload.addProperty("serverName", value.serverName());
      case BridgeMessage.WorldPreload value -> {
        payload.addProperty("serverName", value.serverName());
        addNullable(payload, "worldName", value.worldName());
      }
      case BridgeMessage.Health value -> {
        payload.addProperty("serverName", value.serverName());
        payload.addProperty("mspt", value.mspt());
        payload.addProperty("tps", value.tps());
        payload.addProperty("playerCount", value.playerCount());
        final JsonArray worlds = new JsonArray();
        value.loadedWorlds().forEach(worlds::add);
        payload.add("loadedWorlds", worlds);
      }
      case BridgeMessage.WorldLoaded value -> {
        payload.addProperty("serverName", value.serverName());
        payload.addProperty("worldName", value.worldName());
        payload.addProperty("loadTimeMs", value.loadTimeMs());
      }
      case BridgeMessage.WorldLoadFailed value -> {
        payload.addProperty("serverName", value.serverName());
        payload.addProperty("worldName", value.worldName());
        payload.addProperty("reason", value.reason());
        payload.addProperty("loadTimeMs", value.loadTimeMs());
      }
      case BridgeMessage.WorldUnloaded value -> {
        payload.addProperty("serverName", value.serverName());
        payload.addProperty("worldName", value.worldName());
      }
      case BridgeMessage.QueueStatusResponse value -> {
        payload.addProperty("serverName", value.serverName());
        payload.addProperty("backendQueueSize", value.backendQueueSize());
      }
      case BridgeMessage.ConnectRequest value -> {
        payload.addProperty("uuid", value.uuid().toString());
        payload.addProperty("targetServer", value.targetServer());
      }
      case BridgeMessage.PartyWarp value -> {
        final JsonArray members = new JsonArray();
        value.members().forEach(member -> members.add(member.toString()));
        payload.add("members", members);
        payload.addProperty("targetServer", value.targetServer());
      }
      case BridgeMessage.Ack value -> payload.addProperty("duplicate", value.duplicate());
      case BridgeMessage.Nack value -> payload.addProperty("error", value.error().name());
    }
    return payload;
  }

  private static BridgeMessage decodePayload(final String kind,
                                             final BridgeMessage.Envelope envelope,
                                             final JsonObject payload, final Limits limits) {
    return switch (kind) {
      case "queue_join" -> {
        exact(payload, "uuid", "username", "targetServer", "targetWorld");
        yield new BridgeMessage.QueueJoin(envelope, uuid(payload, "uuid"),
            string(payload, "username", limits.maxUsernameLength(), false),
            string(payload, "targetServer", limits.maxStringLength(), false),
            nullableString(payload, "targetWorld", limits.maxWorldNameLength()));
      }
      case "queue_leave" -> {
        exact(payload, "uuid");
        yield new BridgeMessage.QueueLeave(envelope, uuid(payload, "uuid"));
      }
      case "request_status" -> {
        exact(payload, "serverName");
        yield new BridgeMessage.RequestStatus(envelope,
            string(payload, "serverName", limits.maxStringLength(), false));
      }
      case "world_preload" -> {
        exact(payload, "serverName", "worldName");
        yield new BridgeMessage.WorldPreload(envelope,
            string(payload, "serverName", limits.maxStringLength(), false),
            nullableString(payload, "worldName", limits.maxWorldNameLength()));
      }
      case "health" -> {
        exact(payload, "serverName", "mspt", "tps", "playerCount", "loadedWorlds");
        yield new BridgeMessage.Health(envelope,
            string(payload, "serverName", limits.maxStringLength(), false),
            finiteDouble(payload, "mspt"), finiteDouble(payload, "tps"),
            nonNegativeInt(payload, "playerCount"),
            strings(payload, "loadedWorlds", limits.maxWorldNameLength(), limits.maxLoadedWorlds()));
      }
      case "world_loaded" -> {
        exact(payload, "serverName", "worldName", "loadTimeMs");
        yield new BridgeMessage.WorldLoaded(envelope,
            string(payload, "serverName", limits.maxStringLength(), false),
            string(payload, "worldName", limits.maxWorldNameLength(), false),
            nonNegativeLong(payload, "loadTimeMs"));
      }
      case "world_load_failed" -> {
        exact(payload, "serverName", "worldName", "reason", "loadTimeMs");
        yield new BridgeMessage.WorldLoadFailed(envelope,
            string(payload, "serverName", limits.maxStringLength(), false),
            string(payload, "worldName", limits.maxWorldNameLength(), false),
            string(payload, "reason", limits.maxStringLength(), false),
            nonNegativeLong(payload, "loadTimeMs"));
      }
      case "world_unloaded" -> {
        exact(payload, "serverName", "worldName");
        yield new BridgeMessage.WorldUnloaded(envelope,
            string(payload, "serverName", limits.maxStringLength(), false),
            string(payload, "worldName", limits.maxWorldNameLength(), false));
      }
      case "queue_status_response" -> {
        exact(payload, "serverName", "backendQueueSize");
        yield new BridgeMessage.QueueStatusResponse(envelope,
            string(payload, "serverName", limits.maxStringLength(), false),
            nonNegativeInt(payload, "backendQueueSize"));
      }
      case "connect_request" -> {
        exact(payload, "uuid", "targetServer");
        yield new BridgeMessage.ConnectRequest(envelope, uuid(payload, "uuid"),
            string(payload, "targetServer", limits.maxStringLength(), false));
      }
      case "party_warp" -> {
        exact(payload, "members", "targetServer");
        yield new BridgeMessage.PartyWarp(envelope,
            uuids(payload, "members", limits.maxPartyMembers()),
            string(payload, "targetServer", limits.maxStringLength(), false));
      }
      case "ack" -> {
        exact(payload, "duplicate");
        yield new BridgeMessage.Ack(envelope, bool(payload, "duplicate"));
      }
      case "nack" -> {
        exact(payload, "error");
        final String error = string(payload, "error", limits.maxStringLength(), false);
        yield new BridgeMessage.Nack(envelope, BridgeMessage.ErrorCode.valueOf(error));
      }
      default -> throw new IllegalArgumentException("unknown kind");
    };
  }

  private static void validateMessage(final BridgeMessage message, final Limits limits) {
    final BridgeMessage.Envelope envelope = message.envelope();
    if (envelope.version() != BridgeMessage.VERSION || !KINDS.contains(envelope.type())
        || envelope.expiresAt() <= envelope.issuedAt()
        || lifetimeTooLong(envelope.issuedAt(), envelope.expiresAt(),
        limits.maxMessageLifetimeMillis())) {
      throw new IllegalArgumentException("invalid bridge envelope");
    }
    bounded(envelope.type(), limits.maxStringLength(), false);
    bounded(envelope.sourceBackend(), limits.maxStringLength(), false);
    bounded(envelope.targetBackend(), limits.maxStringLength(), false);
    switch (message) {
      case BridgeMessage.QueueJoin value -> {
        bounded(value.username(), limits.maxUsernameLength(), false);
        bounded(value.targetServer(), limits.maxStringLength(), false);
        nullableBounded(value.targetWorld(), limits.maxWorldNameLength());
      }
      case BridgeMessage.QueueLeave ignored -> { }
      case BridgeMessage.RequestStatus value -> bounded(value.serverName(), limits.maxStringLength(), false);
      case BridgeMessage.WorldPreload value -> {
        bounded(value.serverName(), limits.maxStringLength(), false);
        nullableBounded(value.worldName(), limits.maxWorldNameLength());
      }
      case BridgeMessage.Health value -> {
        bounded(value.serverName(), limits.maxStringLength(), false);
        if (!Double.isFinite(value.mspt()) || value.mspt() < 0 || !Double.isFinite(value.tps())
            || value.tps() < 0 || value.playerCount() < 0
            || value.loadedWorlds().size() > limits.maxLoadedWorlds()) {
          throw new IllegalArgumentException("invalid health payload");
        }
        value.loadedWorlds().forEach(world -> bounded(world, limits.maxWorldNameLength(), false));
      }
      case BridgeMessage.WorldLoaded value -> {
        bounded(value.serverName(), limits.maxStringLength(), false);
        bounded(value.worldName(), limits.maxWorldNameLength(), false);
        if (value.loadTimeMs() < 0) throw new IllegalArgumentException("invalid load time");
      }
      case BridgeMessage.WorldLoadFailed value -> {
        bounded(value.serverName(), limits.maxStringLength(), false);
        bounded(value.worldName(), limits.maxWorldNameLength(), false);
        bounded(value.reason(), limits.maxStringLength(), false);
        if (value.loadTimeMs() < 0) throw new IllegalArgumentException("invalid load time");
      }
      case BridgeMessage.WorldUnloaded value -> {
        bounded(value.serverName(), limits.maxStringLength(), false);
        bounded(value.worldName(), limits.maxWorldNameLength(), false);
      }
      case BridgeMessage.QueueStatusResponse value -> {
        bounded(value.serverName(), limits.maxStringLength(), false);
        if (value.backendQueueSize() < 0) throw new IllegalArgumentException("invalid queue size");
      }
      case BridgeMessage.ConnectRequest value -> bounded(value.targetServer(), limits.maxStringLength(), false);
      case BridgeMessage.PartyWarp value -> {
        bounded(value.targetServer(), limits.maxStringLength(), false);
        if (value.members().size() > limits.maxPartyMembers() || value.members().stream().anyMatch(v -> v == null)) {
          throw new IllegalArgumentException("invalid party payload");
        }
      }
      case BridgeMessage.Ack ignored -> { }
      case BridgeMessage.Nack value -> { }
    }
  }

  private static void exact(final JsonObject object, final String... fields) {
    final Set<String> expected = Set.of(fields);
    if (!hasExactly(object, expected)) throw new IllegalArgumentException("unknown payload field");
  }

  private static boolean hasExactly(final JsonObject object, final Set<String> fields) {
    return object.keySet().equals(fields);
  }

  private static String string(final JsonObject object, final String key, final int max,
                               final boolean allowBlank) {
    final JsonElement element = required(object, key);
    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException("field is not a string: " + key);
    }
    final String value = element.getAsString();
    bounded(value, max, allowBlank);
    return value;
  }

  private static @Nullable String nullableString(final JsonObject object, final String key,
                                                 final int max) {
    final JsonElement element = required(object, key);
    return element.isJsonNull() ? null : string(object, key, max, true);
  }

  private static void nullableBounded(@Nullable final String value, final int max) {
    if (value != null) bounded(value, max, true);
  }

  private static void bounded(final String value, final int max, final boolean allowBlank) {
    if (value == null || value.length() > max || (!allowBlank && value.isBlank())) {
      throw new IllegalArgumentException("invalid bounded string");
    }
  }

  private static JsonElement required(final JsonObject object, final String key) {
    final JsonElement element = object.get(key);
    if (element == null) throw new IllegalArgumentException("missing field: " + key);
    return element;
  }

  private static UUID uuid(final JsonObject object, final String key) {
    return UUID.fromString(string(object, key, 36, false));
  }

  private static int integer(final JsonObject object, final String key) {
    final JsonElement value = required(object, key);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw new IllegalArgumentException("field is not an integer");
    }
    return Integer.parseInt(value.getAsString());
  }

  private static int nonNegativeInt(final JsonObject object, final String key) {
    final int value = integer(object, key);
    if (value < 0) throw new IllegalArgumentException("negative integer");
    return value;
  }

  private static long nonNegativeLong(final JsonObject object, final String key) {
    final long value = longValue(object, key);
    if (value < 0) throw new IllegalArgumentException("negative long");
    return value;
  }

  private static long longValue(final JsonObject object, final String key) {
    final JsonElement value = required(object, key);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw new IllegalArgumentException("field is not a number");
    }
    return Long.parseLong(value.getAsString());
  }

  private static double finiteDouble(final JsonObject object, final String key) {
    final JsonElement value = required(object, key);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw new IllegalArgumentException("field is not a number");
    }
    final double result = value.getAsDouble();
    if (!Double.isFinite(result) || result < 0) throw new IllegalArgumentException("invalid metric");
    return result;
  }

  private static boolean bool(final JsonObject object, final String key) {
    final JsonElement value = required(object, key);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
      throw new IllegalArgumentException("field is not boolean");
    }
    return value.getAsBoolean();
  }

  private static List<String> strings(final JsonObject object, final String key, final int max,
                                      final int maxItems) {
    final JsonElement value = required(object, key);
    if (!value.isJsonArray() || value.getAsJsonArray().size() > maxItems) {
      throw new IllegalArgumentException("invalid string array");
    }
    final List<String> result = value.getAsJsonArray().asList().stream()
        .map(element -> {
          if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("array item is not a string");
          }
          final String item = element.getAsString();
          bounded(item, max, false);
          return item;
        }).toList();
    return List.copyOf(result);
  }

  private static List<UUID> uuids(final JsonObject object, final String key, final int maxItems) {
    final JsonElement value = required(object, key);
    if (!value.isJsonArray() || value.getAsJsonArray().size() > maxItems) {
      throw new IllegalArgumentException("invalid uuid array");
    }
    return value.getAsJsonArray().asList().stream()
        .map(element -> {
          if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("array item is not a UUID");
          }
          return UUID.fromString(element.getAsString());
        }).toList();
  }

  private static void addNullable(final JsonObject object, final String key,
                                  @Nullable final String value) {
    if (value == null) object.add(key, com.google.gson.JsonNull.INSTANCE);
    else object.addProperty(key, value);
  }

  private static boolean lifetimeTooLong(final long issuedAt, final long expiresAt,
                                         final long maximum) {
    try {
      return Math.subtractExact(expiresAt, issuedAt) > maximum;
    } catch (ArithmeticException exception) {
      return true;
    }
  }

  private static long safeAdd(final long left, final long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException exception) {
      return Long.MAX_VALUE;
    }
  }
}
