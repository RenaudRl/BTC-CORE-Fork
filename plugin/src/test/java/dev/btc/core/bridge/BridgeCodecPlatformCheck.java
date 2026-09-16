/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dependency-free checks for the origin platform on the wire, in this module's idiom.
 *
 * <p>The danger this guards against is not a wrong verdict, it is <em>divergence</em>: two codecs
 * that disagree about the shape of a payload produce a pair that compiles, starts, and then refuses
 * every message on a live server. The frame vector of {@link BridgeFrameCheck} guards the envelope
 * the same way; this guards the payload.
 *
 * <p>The literals below are pinned identically in BTCVelocity's {@code BridgeCodecTest}. If either
 * side ever changes the field name, the spelling of a value, or what it tolerates, one of the two
 * builds fails instead of a deployment going quiet.
 */
public final class BridgeCodecPlatformCheck {

  /** Fixed so the encoded form is comparable byte for byte; fictional, and shared on purpose. */
  private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000a11c");
  private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000b0b0");
  private static final long ISSUED = 1_600_000_000_000L;
  private static final long EXPIRES = ISSUED + 30_000L;

  private BridgeCodecPlatformCheck() {
  }

  public static void main(final String[] args) {
    final BridgeCodec.Limits limits = BridgeCodec.Limits.defaults();

    // 1. A stated platform is written under the agreed name, with the agreed spelling.
    final String encoded = new String(BridgeCodec.encode(new BridgeMessage.ConnectRequest(
        envelope("connect_request"), ALICE, "btc", BridgeMessage.Platform.BEDROCK), limits),
        StandardCharsets.UTF_8);
    check(encoded.contains("\"originPlatform\":\"BEDROCK\""),
        "the field name and value spelling match the proxy's");

    // 2. Round trip.
    final BridgeCodec.DecodeResult round = BridgeCodec.decodeResult(
        encoded.getBytes(StandardCharsets.UTF_8), ISSUED + 1, limits);
    check(round.accepted(), "round trip accepted");
    check(((BridgeMessage.ConnectRequest) round.message()).originPlatform()
        == BridgeMessage.Platform.BEDROCK, "round trip preserves the platform");

    // 3. The compatibility direction: a sender that predates the field stays understood.
    check(BridgeCodec.decodeResult(connectJson("").getBytes(StandardCharsets.UTF_8), ISSUED + 1,
        limits).accepted(), "a payload without the field is still accepted");

    // 4. A value outside the closed set is refused, never mapped onto a default.
    check(!BridgeCodec.decodeResult(
        connectJson(",\"originPlatform\":\"SWITCH\"").getBytes(StandardCharsets.UTF_8),
        ISSUED + 1, limits).accepted(), "a platform outside the closed set is refused");

    // 5. An explicit null is refused: writing the field is claiming to know.
    check(!BridgeCodec.decodeResult(
        connectJson(",\"originPlatform\":null").getBytes(StandardCharsets.UTF_8),
        ISSUED + 1, limits).accepted(), "an explicitly null platform is refused");

    // 6. A party states one platform per member.
    final byte[] party = BridgeCodec.encode(new BridgeMessage.PartyWarp(envelope("party_warp"),
        List.of(ALICE, BOB), "btc",
        Map.of(ALICE, BridgeMessage.Platform.JAVA, BOB, BridgeMessage.Platform.BEDROCK)), limits);
    final BridgeCodec.DecodeResult warp = BridgeCodec.decodeResult(party, ISSUED + 1, limits);
    check(warp.accepted(), "a party with stated platforms round trips");
    final Map<UUID, BridgeMessage.Platform> stated =
        ((BridgeMessage.PartyWarp) warp.message()).memberPlatforms();
    check(stated.get(ALICE) == BridgeMessage.Platform.JAVA
        && stated.get(BOB) == BridgeMessage.Platform.BEDROCK, "each member keeps its own platform");

    // 7. And a party from an older sender is still understood.
    check(BridgeCodec.decodeResult(BridgeCodec.encode(new BridgeMessage.PartyWarp(
        envelope("party_warp"), List.of(ALICE), "btc", null), limits), ISSUED + 1, limits)
        .accepted(), "a party without stated platforms is still accepted");

    System.out.println("BridgeCodecPlatformCheck: all targeted checks passed");
  }

  private static BridgeMessage.Envelope envelope(final String kind) {
    return new BridgeMessage.Envelope(BridgeMessage.VERSION, ALICE, kind, "btc", "btc-proxy",
        ISSUED, EXPIRES);
  }

  private static String connectJson(final String extraPayloadField) {
    return "{\"version\":2,\"messageId\":\"" + ALICE + "\",\"type\":\"connect_request\""
        + ",\"sourceBackend\":\"btc\",\"targetBackend\":\"btc-proxy\",\"issuedAt\":" + ISSUED
        + ",\"expiresAt\":" + EXPIRES + ",\"payload\":{\"uuid\":\"" + BOB
        + "\",\"targetServer\":\"btc\"" + extraPayloadField + "}}";
  }

  private static void check(final boolean condition, final String what) {
    if (!condition) {
      throw new AssertionError(what);
    }
  }
}
