/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jetbrains.annotations.Nullable;

/**
 * Seals and opens {@code btc:bridge} payloads with an HMAC-SHA256, in both directions.
 *
 * <p>Why a signature at all: plugin messages travel through a player's connection, and Bukkit hands
 * {@link org.bukkit.plugin.messaging.PluginMessageListener} that player for every message —
 * including the ones the proxy sent. The check this replaces, "was there a player?", therefore
 * rejected everything the proxy ever sent (bench, 16/09: every ack and nack of the evening, refused as
 * CLIENT_ORIGIN within the second). What proves the origin is knowledge of a secret no client sees.
 *
 * <p>No new secret to distribute: both ends already hold Velocity's modern-forwarding secret. The
 * bridge key is <em>derived</em> from it, so one secret never directly serves two purposes.
 *
 * <p>Wire layout: {@code MAGIC (1) | VERSION (1) | MAC (32) | payload}; the MAC covers magic and
 * version too. No unsigned fallback: accepting a plain payload "for compatibility" would reopen
 * exactly the channel this closes.
 *
 * <p>Same algorithm, layout and derivation label as BTCVelocity's
 * {@code com.btcvelocity.api.bridge.BridgeFrame}. Both are pinned to one test vector computed with an
 * independent HMAC implementation, so the two copies cannot drift without failing a build.
 */
public final class BridgeFrame {

  /** First byte of every sealed frame. A JSON payload starts with an opening brace, never this. */
  static final byte MAGIC = (byte) 0xB7;
  /** Frame format version, covered by the MAC. */
  static final byte VERSION = 1;
  /** Length of an HMAC-SHA256 tag. */
  static final int MAC_LENGTH = 32;
  /** Bytes preceding the payload. */
  static final int HEADER_LENGTH = 2 + MAC_LENGTH;

  private static final String ALGORITHM = "HmacSHA256";
  private static final byte[] KEY_LABEL =
      "btc:bridge/v2/frame-mac".getBytes(StandardCharsets.US_ASCII);

  /** Why a frame was not opened. */
  enum Rejection {
    /** No bridge frame at all: a plain payload, as sent by a peer that does not sign. */
    UNSIGNED,
    /** A frame of a version this side does not speak. */
    UNKNOWN_VERSION,
    /** Too short to hold a header. */
    TRUNCATED,
    /** Signed with another key, or altered in flight. */
    BAD_MAC
  }

  /**
   * The result of opening a frame: either the payload, or why it was refused — never both.
   *
   * @param payload   the authenticated payload, or {@code null} when refused
   * @param rejection why the frame was refused, or {@code null} when accepted
   */
  record Opened(byte @Nullable [] payload, @Nullable Rejection rejection) {

    boolean accepted() {
      return rejection == null;
    }
  }

  private final SecretKeySpec key;

  private BridgeFrame(final byte[] derivedKey) {
    this.key = new SecretKeySpec(derivedKey, ALGORITHM);
  }

  /**
   * Derives the bridge key from the shared forwarding secret.
   *
   * @param sharedSecret the modern-forwarding secret both ends hold
   * @return a frame codec keyed for this deployment
   * @throws IllegalArgumentException when the secret is empty
   */
  static BridgeFrame fromSharedSecret(final byte[] sharedSecret) {
    Objects.requireNonNull(sharedSecret, "sharedSecret");
    if (sharedSecret.length == 0) {
      throw new IllegalArgumentException("the bridge needs a non-empty shared secret");
    }
    return new BridgeFrame(hmac(new SecretKeySpec(sharedSecret, ALGORITHM), KEY_LABEL));
  }

  /** Signs a payload. */
  byte[] seal(final byte[] payload) {
    Objects.requireNonNull(payload, "payload");
    final byte[] frame = new byte[HEADER_LENGTH + payload.length];
    frame[0] = MAGIC;
    frame[1] = VERSION;
    System.arraycopy(payload, 0, frame, HEADER_LENGTH, payload.length);
    final byte[] tag = hmac(key, signedPart(frame));
    System.arraycopy(tag, 0, frame, 2, MAC_LENGTH);
    return frame;
  }

  /** Authenticates a frame and extracts its payload; never {@code null}. */
  Opened open(final byte[] frame) {
    Objects.requireNonNull(frame, "frame");
    if (frame.length == 0 || frame[0] != MAGIC) {
      return new Opened(null, Rejection.UNSIGNED);
    }
    if (frame.length < HEADER_LENGTH) {
      return new Opened(null, Rejection.TRUNCATED);
    }
    if (frame[1] != VERSION) {
      return new Opened(null, Rejection.UNKNOWN_VERSION);
    }
    final byte[] expected = hmac(key, signedPart(frame));
    final byte[] received = Arrays.copyOfRange(frame, 2, HEADER_LENGTH);
    // Constant time: an early-exit comparison leaks how much of a forged tag was right.
    if (!MessageDigest.isEqual(expected, received)) {
      return new Opened(null, Rejection.BAD_MAC);
    }
    return new Opened(Arrays.copyOfRange(frame, HEADER_LENGTH, frame.length), null);
  }

  private static byte[] signedPart(final byte[] frame) {
    final byte[] signed = new byte[frame.length - MAC_LENGTH];
    signed[0] = frame[0];
    signed[1] = frame[1];
    System.arraycopy(frame, HEADER_LENGTH, signed, 2, frame.length - HEADER_LENGTH);
    return signed;
  }

  private static byte[] hmac(final SecretKeySpec key, final byte[] data) {
    try {
      // A fresh instance per call: Mac is not thread-safe, and messages arrive on player threads.
      final Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
