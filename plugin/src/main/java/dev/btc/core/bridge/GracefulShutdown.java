/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Decides when a backend carrying players may stop.
 *
 * <p>Pure on purpose: no Bukkit, no scheduler, no clock of its own. Every rule below is decidable
 * in a unit test, because the thing being decided — whether it is safe to end the server — is
 * exactly the thing nobody wants to discover by trying it on production.
 *
 * <h2>What actually proves a player is safe</h2>
 *
 * <p>The proof is <b>the player is no longer here</b>, not the acknowledgement. An ACK says the
 * proxy accepted the intent; only the player leaving says the intent happened. So departures end
 * the wait, and the deadline bounds it. An ACK that never comes therefore costs nothing, and a
 * proxy that acknowledges without moving anyone cannot shorten the wait — which is the failure this
 * class exists to survive.
 *
 * <p>A NACK is different, and it is why refusals are tracked separately: it says the transfer
 * <em>will not</em> happen. Waiting out the full deadline for a player the proxy has already
 * refused would delay the shutdown for nothing, so a refused player stops being awaited — and the
 * shutdown reports it through {@link Verdict#refused()} and a non-clean outcome, because a player
 * left behind is a fact an operator must see, never a detail folded into "done".
 *
 * <p>A wait emptied by refusals ends with {@link Outcome#REFUSED}, not {@link Outcome#DEADLINE}.
 * They used to share the second: on the bench on 16/09 a shutdown whose only transfer the proxy had
 * refused, ended at once, logged "deadline reached" — a deadline that was nowhere near. An operator
 * reading that looks for a slow proxy; the actual fix is the destination.
 *
 * <h2>The second chance</h2>
 *
 * <p>A refusal is about <em>one destination</em>, not about the player: the target may be full,
 * unknown to the proxy, or refusing this platform. So when a fallback destination is declared, the
 * first refusal for a player buys a second request towards it rather than a seat on the list of
 * people left behind. Exactly one, and only when a fallback exists — a retry that can retry has no
 * reason to stop, and this class exists to guarantee the wait ends.
 *
 * <p>The player stays awaited while that second request is being made. That is deliberate: if the
 * caller never follows through, the deadline ends the wait, which is worse for the shutdown's
 * latency and better for the player — dropping them from the wait on a refusal we intend to retry
 * would let the server end while the second chance was still in flight.
 */
public final class GracefulShutdown {

  /** Why the wait ended. */
  public enum Outcome {
    /** Nobody was here to move. */
    NOTHING_TO_DO,
    /** Every player left. */
    DRAINED,
    /** Nobody is awaited any more, but the proxy refused to move at least one player. */
    REFUSED,
    /** The deadline was reached with players still here. */
    DEADLINE
  }

  /** The end of a wait: why it ended, and who was left behind. */
  public record Verdict(Outcome outcome, Set<UUID> remaining, Set<UUID> refused) {
    public Verdict {
      Objects.requireNonNull(outcome, "outcome");
      remaining = Set.copyOf(remaining);
      refused = Set.copyOf(refused);
    }

    /** Whether the server may be ended now. */
    public boolean mayStop() {
      return true;
    }

    /** Whether every player was seen leaving. */
    public boolean clean() {
      return outcome == Outcome.NOTHING_TO_DO || outcome == Outcome.DRAINED;
    }
  }

  private final long deadlineMillis;
  private final boolean fallbackDeclared;
  private final Map<UUID, UUID> awaitedByPlayer = new LinkedHashMap<>();
  private final Set<UUID> acknowledged = new LinkedHashSet<>();
  private final Set<UUID> refused = new LinkedHashSet<>();
  private final Set<UUID> fallbackUsed = new LinkedHashSet<>();
  private long startedAt = -1L;

  /**
   * Creates a wait with no fallback destination: a refusal is final.
   *
   * @param deadlineMillis how long players are given to leave, in milliseconds
   */
  public GracefulShutdown(final long deadlineMillis) {
    this(deadlineMillis, false);
  }

  /**
   * Creates a wait.
   *
   * @param deadlineMillis   how long players are given to leave, in milliseconds
   * @param fallbackDeclared whether a fallback destination exists, so a first refusal buys a
   *                         second request instead of leaving the player behind
   */
  public GracefulShutdown(final long deadlineMillis, final boolean fallbackDeclared) {
    if (deadlineMillis <= 0) {
      throw new IllegalArgumentException("deadline must be positive");
    }
    this.deadlineMillis = deadlineMillis;
    this.fallbackDeclared = fallbackDeclared;
  }

  /**
   * Opens the wait for the players present at that instant.
   *
   * @param players   the players to move, each mapped to the id of the message asking for it
   * @param nowMillis the current time, in milliseconds
   * @return the immediate verdict when nobody is here, or {@code null} when the wait is open
   */
  public @Nullable Verdict begin(final Map<UUID, UUID> players, final long nowMillis) {
    if (startedAt >= 0) {
      throw new IllegalStateException("the wait is already open");
    }
    startedAt = nowMillis;
    awaitedByPlayer.putAll(players);
    return awaitedByPlayer.isEmpty()
        ? new Verdict(Outcome.NOTHING_TO_DO, Set.of(), Set.of()) : null;
  }

  /**
   * Records that a player is no longer on this server. This, and only this, proves a transfer
   * happened.
   *
   * @param player the player who left
   */
  public void playerGone(final UUID player) {
    awaitedByPlayer.remove(player);
  }

  /**
   * Records the proxy's acknowledgement of a transfer request.
   *
   * <p>Observability only: an acknowledged player is still awaited until they actually leave.</p>
   *
   * @param messageId the id of the request being acknowledged
   */
  public void acknowledged(final UUID messageId) {
    acknowledged.add(messageId);
  }

  /**
   * Records the proxy's refusal of a transfer request.
   *
   * <p>When a fallback is declared and this player has not used theirs, the refusal buys a second
   * request instead of a seat on the list of people left behind: the player <b>stays awaited</b> and
   * their id is returned, so the caller can ask again towards the fallback and then call
   * {@link #fallbackAsked(UUID, UUID)}. Otherwise the player stops being awaited and is reported as
   * left behind.
   *
   * @param messageId the id of the refused request
   * @return the player to retry towards the fallback, or {@code null} when the refusal is final or
   *         concerns a request this wait knows nothing about
   */
  public @Nullable UUID refused(final UUID messageId) {
    final UUID player = playerOf(messageId);
    if (player == null) {
      return null;
    }
    if (fallbackDeclared && fallbackUsed.add(player)) {
      return player;
    }
    awaitedByPlayer.remove(player);
    refused.add(player);
    return null;
  }

  /**
   * Records the second request, the one towards the fallback destination.
   *
   * @param player    the player being asked again
   * @param messageId the id of the second request
   */
  public void fallbackAsked(final UUID player, final UUID messageId) {
    if (awaitedByPlayer.containsKey(player)) {
      awaitedByPlayer.put(player, messageId);
    }
  }

  /**
   * Records that the second request could not even be sent.
   *
   * <p>Without this the player would stay awaited until the deadline for a message that was never
   * on the wire — a wait held open by something that already failed.
   *
   * @param player the player whose fallback request could not be made
   */
  public void fallbackImpossible(final UUID player) {
    if (awaitedByPlayer.remove(player) != null) {
      refused.add(player);
    }
  }

  private @Nullable UUID playerOf(final UUID messageId) {
    for (final Map.Entry<UUID, UUID> entry : awaitedByPlayer.entrySet()) {
      if (entry.getValue().equals(messageId)) {
        return entry.getKey();
      }
    }
    return null;
  }

  /**
   * Asks whether the wait is over.
   *
   * @param nowMillis the current time, in milliseconds
   * @return the verdict, or {@code null} while the wait must continue
   */
  public @Nullable Verdict poll(final long nowMillis) {
    if (startedAt < 0) {
      throw new IllegalStateException("the wait was never opened");
    }
    if (awaitedByPlayer.isEmpty()) {
      return new Verdict(refused.isEmpty() ? Outcome.DRAINED : Outcome.REFUSED,
          Set.of(), refused);
    }
    if (nowMillis - startedAt >= deadlineMillis) {
      return new Verdict(Outcome.DEADLINE, awaitedByPlayer.keySet(), refused);
    }
    return null;
  }

  /** How many players are still awaited. */
  public int remaining() {
    return awaitedByPlayer.size();
  }

  /** How many requests the proxy acknowledged. */
  public int acknowledgedCount() {
    return acknowledged.size();
  }

  /** How many players were asked a second time, towards the fallback. */
  public int fallbackAttempts() {
    return fallbackUsed.size();
  }

  /** The message ids currently awaited, for logging. */
  public Collection<UUID> awaitedMessageIds() {
    return Set.copyOf(awaitedByPlayer.values());
  }
}
