/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.Nullable;

/**
 * JSON codec for {@link BridgeMessage} records.
 *
 * <p>Encodes by serializing the record to JSON and injecting the {@code "type"} discriminator.
 * Decodes by reading the type and dispatching to the correct record class.</p>
 */
public final class BridgeCodec {

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

  private BridgeCodec() {
  }

  /**
   * Encodes a bridge message into a UTF-8 byte array.
   *
   * @param message the message to encode
   * @return the JSON bytes
   */
  public static byte[] encode(final BridgeMessage message) {
    final JsonObject json = GSON.toJsonTree(message).getAsJsonObject();
    json.addProperty("type", message.type());
    return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Decodes a UTF-8 byte array into a bridge message.
   *
   * @param data the JSON bytes
   * @return the decoded message, or {@code null} if the type is unrecognized
   */
  public static @Nullable BridgeMessage decode(final byte[] data) {
    final String json = new String(data, StandardCharsets.UTF_8);
    final JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
    final String type = obj.get("type").getAsString();
    return switch (type) {
      case "queue_join" -> GSON.fromJson(obj, BridgeMessage.QueueJoin.class);
      case "queue_leave" -> GSON.fromJson(obj, BridgeMessage.QueueLeave.class);
      case "request_status" -> GSON.fromJson(obj, BridgeMessage.RequestStatus.class);
      case "world_preload" -> GSON.fromJson(obj, BridgeMessage.WorldPreload.class);
      case "health" -> GSON.fromJson(obj, BridgeMessage.Health.class);
      case "world_loaded" -> GSON.fromJson(obj, BridgeMessage.WorldLoaded.class);
      case "world_unloaded" -> GSON.fromJson(obj, BridgeMessage.WorldUnloaded.class);
      case "queue_status_response" -> GSON.fromJson(obj, BridgeMessage.QueueStatusResponse.class);
      default -> null;
    };
  }
}
