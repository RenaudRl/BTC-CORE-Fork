/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Dependency-free checks for {@link BridgeFrame}, in this module's idiom ({@code main} + assertions).
 *
 * <p>The vector below is the one BTCVelocity's {@code BridgeFrameTest} pins, computed with .NET's
 * HMACSHA256 rather than with either copy of the class. If this copy and the proxy's ever disagree on
 * a single byte of the derivation or the layout, one of the two builds fails â€” instead of every bridge
 * message being refused on a live server.</p>
 */
public final class BridgeFrameCheck {

  private static final byte[] SECRET =
      "btc-bridge-test-vector-secret-0001".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] PAYLOAD =
      "{\"version\":2,\"type\":\"health\"}".getBytes(StandardCharsets.UTF_8);
  private static final String VECTOR_MAC =
      "04d188162874c1861801f58a2992beac6f357bd5e35101b72e4d8f9030eac305";

  private BridgeFrameCheck() {
  }

  public static void main(final String[] args) {
    final BridgeFrame frame = BridgeFrame.fromSharedSecret(SECRET);

    final byte[] sealed = frame.seal(PAYLOAD);
    check(sealed[0] == BridgeFrame.MAGIC && sealed[1] == BridgeFrame.VERSION, "header");
    check(VECTOR_MAC.equals(HexFormat.of().formatHex(Arrays.copyOfRange(sealed, 2, 34))),
        "the tag matches the independent vector shared with BTCVelocity");

    final BridgeFrame.Opened opened = frame.open(sealed);
    check(opened.accepted() && Arrays.equals(PAYLOAD, opened.payload()), "round trip");

    check(frame.open(PAYLOAD).rejection() == BridgeFrame.Rejection.UNSIGNED,
        "a plain payload is refused: no unsigned fallback");
    check(frame.open(PAYLOAD).payload() == null, "and a refusal carries nothing");

    final byte[] forged = BridgeFrame.fromSharedSecret(
        "not-the-secret".getBytes(StandardCharsets.US_ASCII)).seal(PAYLOAD);
    check(frame.open(forged).rejection() == BridgeFrame.Rejection.BAD_MAC,
        "a frame signed without the secret is refused");

    for (int i = 2; i < sealed.length; i++) {
      final byte[] altered = sealed.clone();
      altered[i] ^= 0x01;
      check(frame.open(altered).rejection() == BridgeFrame.Rejection.BAD_MAC,
          "a flipped bit at byte " + i + " is refused");
    }

    final byte[] relabelled = sealed.clone();
    relabelled[1] = 2;
    check(frame.open(relabelled).rejection() == BridgeFrame.Rejection.UNKNOWN_VERSION,
        "the version cannot be relabelled");
    check(frame.open(Arrays.copyOf(sealed, 10)).rejection() == BridgeFrame.Rejection.TRUNCATED,
        "a truncated frame is refused");

    boolean refusedEmptySecret = false;
    try {
      BridgeFrame.fromSharedSecret(new byte[0]);
    } catch (IllegalArgumentException expected) {
      refusedEmptySecret = true;
    }
    check(refusedEmptySecret, "an empty secret refuses to build a bridge");

    System.out.println("BridgeFrameCheck: all targeted checks passed");
  }

  private static void check(final boolean condition, final String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
