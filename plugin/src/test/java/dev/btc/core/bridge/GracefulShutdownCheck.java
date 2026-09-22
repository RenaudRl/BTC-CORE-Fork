/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import dev.btc.core.bridge.GracefulShutdown.Outcome;
import dev.btc.core.bridge.GracefulShutdown.Verdict;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dependency-free targeted checks for the graceful shutdown decision.
 *
 * <p>Same shape as {@link BridgeCodecTest}, and for the same reason: this module carries no test
 * framework on purpose. Gradle compiles this class and the verification command runs its
 * {@code main} with assertions enabled.</p>
 *
 * <p>The property every check circles: <b>the wait always ends</b>. A proxy that never answers, a
 * proxy that answers without moving anyone, a player who will not leave — none of them may hold a
 * shutdown open.</p>
 */
public final class GracefulShutdownCheck {

  private static final long DEADLINE = 15_000L;
  private static final long T0 = 1_000_000L;

  private static final UUID ALICE = UUID.randomUUID();
  private static final UUID BOB = UUID.randomUUID();
  private static final UUID ASK_ALICE = UUID.randomUUID();
  private static final UUID ASK_BOB = UUID.randomUUID();

  private GracefulShutdownCheck() {
  }

  public static void main(final String[] args) {
    anEmptyServerStopsImmediately();
    theWaitEndsWhenTheLastPlayerLeaves();
    anAcknowledgementDoesNotEndTheWait();
    theWaitEndsAtTheDeadlineEvenWithoutAnyAnswer();
    aRefusedPlayerStopsBeingAwaitedAndIsReported();
    aWaitEmptiedByRefusalsEndsBeforeTheDeadlineAndSaysWhy();
    aRefusalForAnUnknownMessageChangesNothing();
    aFirstRefusalBuysASecondRequestTowardsTheFallback();
    aPlayerRefusedTwiceIsLeftBehindAndNamed();
    withoutAFallbackTheFirstRefusalIsFinal();
    aFallbackThatCannotBeSentDoesNotHoldTheWaitOpen();
    aPlayerWhoLeavesTwiceIsNotCountedTwice();
    theWaitRefusesToBeOpenedTwiceOrPolledBeforeItOpens();
    theDeadlineMustBePositive();
    System.out.println("GracefulShutdownCheck: all targeted checks passed");
  }

  private static Map<UUID, UUID> twoPlayers() {
    final Map<UUID, UUID> players = new LinkedHashMap<>();
    players.put(ALICE, ASK_ALICE);
    players.put(BOB, ASK_BOB);
    return players;
  }

  private static void anEmptyServerStopsImmediately() {
    final Verdict verdict = new GracefulShutdown(DEADLINE).begin(Map.of(), T0);
    check(verdict != null && verdict.outcome() == Outcome.NOTHING_TO_DO,
        "an empty server stops at once");
    check(verdict.clean(), "and that is a clean stop");
  }

  private static void theWaitEndsWhenTheLastPlayerLeaves() {
    final GracefulShutdown wait = new GracefulShutdown(DEADLINE);
    check(wait.begin(twoPlayers(), T0) == null, "two players: the wait opens");

    wait.playerGone(ALICE);
    check(wait.poll(T0 + 1_000L) == null, "one player is still here");

    wait.playerGone(BOB);
    final Verdict verdict = wait.poll(T0 + 2_000L);
    check(verdict != null && verdict.outcome() == Outcome.DRAINED, "everyone left: drained");
    check(verdict.remaining().isEmpty() && verdict.clean(), "nobody was left behind");
  }

  /**
   * The proof is the player leaving, never the acknowledgement: a proxy that acknowledges and moves
   * nobody must not be able to shorten the wait. That is the failure this class exists to survive.
   */
  private static void anAcknowledgementDoesNotEndTheWait() {
    final GracefulShutdown wait = new GracefulShutdown(DEADLINE);
    check(wait.begin(twoPlayers(), T0) == null, "the wait opens");

    wait.acknowledged(ASK_ALICE);
    wait.acknowledged(ASK_BOB);

    check(wait.poll(T0 + 1_000L) == null, "both acknowledged, both still here: keep waiting");
    check(wait.remaining() == 2, "nobody was dropped from the wait by an acknowledgement");
    check(wait.acknowledgedCount() == 2, "the acknowledgements are counted, for the log");
  }

  /** The guarantee: an acknowledgement that never comes costs nothing. Never indefinitely. */
  private static void theWaitEndsAtTheDeadlineEvenWithoutAnyAnswer() {
    final GracefulShutdown wait = new GracefulShutdown(DEADLINE);
    check(wait.begin(twoPlayers(), T0) == null, "the wait opens");

    check(wait.poll(T0 + DEADLINE - 1L) == null, "witness: one millisecond before, we still wait");

    final Verdict verdict = wait.poll(T0 + DEADLINE);
    check(verdict != null && verdict.outcome() == Outcome.DEADLINE, "the deadline ends the wait");
    check(verdict.remaining().size() == 2 && verdict.remaining().contains(ALICE),
        "and it names who is still here");
    check(verdict.mayStop(), "the shutdown concludes anyway");
    check(!verdict.clean(), "but it is not clean, and it says so");
  }

  private static void aRefusedPlayerStopsBeingAwaitedAndIsReported() {
    final GracefulShutdown wait = new GracefulShutdown(DEADLINE);
    check(wait.begin(twoPlayers(), T0) == null, "the wait opens");

    wait.refused(ASK_ALICE);
    check(wait.remaining() == 1, "a player the proxy refused is no longer awaited");

    wait.playerGone(BOB);
    final Verdict verdict = wait.poll(T0 + 1_000L);
    check(verdict != null && verdict.refused().contains(ALICE),
        "a player left behind is a visible fact");
    check(!verdict.clean(), "a refusal is never folded into a clean stop");
    check(verdict.outcome() == Outcome.REFUSED, "and it is called a refusal, not a deadline");
  }

  /**
   * Found on the bench on 16/09: a stop whose only transfer was refused ended at once but logged
   * "deadline reached". Witness pair: the same refusal, polled long before the deadline, must say
   * REFUSED; a player simply not leaving must still say DEADLINE once the deadline passes.
   */
  private static void aWaitEmptiedByRefusalsEndsBeforeTheDeadlineAndSaysWhy() {
    final GracefulShutdown refusedWait = new GracefulShutdown(DEADLINE);
    check(refusedWait.begin(Map.of(ALICE, ASK_ALICE), T0) == null, "the wait opens");
    refusedWait.refused(ASK_ALICE);
    final Verdict early = refusedWait.poll(T0 + 1L);
    check(early != null && early.outcome() == Outcome.REFUSED,
        "refused by the proxy: ends at once, as a refusal");
    check(early.remaining().isEmpty() && early.refused().contains(ALICE), "and names who");

    final GracefulShutdown stuckWait = new GracefulShutdown(DEADLINE);
    check(stuckWait.begin(Map.of(ALICE, ASK_ALICE), T0) == null, "the wait opens");
    stuckWait.acknowledged(ASK_ALICE);
    check(stuckWait.poll(T0 + 1L) == null, "witness: acknowledged but still here, no verdict yet");
    final Verdict late = stuckWait.poll(T0 + DEADLINE);
    check(late != null && late.outcome() == Outcome.DEADLINE,
        "witness: a player who does not leave still ends as a deadline");
  }

  /** Witness: a refusal about something else must not drain the wait. */
  private static void aRefusalForAnUnknownMessageChangesNothing() {
    final GracefulShutdown wait = new GracefulShutdown(DEADLINE);
    check(wait.begin(twoPlayers(), T0) == null, "the wait opens");

    check(wait.refused(UUID.randomUUID()) == null, "an unrelated refusal asks for no retry");
    check(wait.remaining() == 2, "an unrelated refusal changes nothing");
    check(wait.poll(T0 + 1_000L) == null, "and the wait goes on");
  }

  /**
   * A refusal is about the destination, not about the person: the target may be full, unknown, or
   * refusing this platform. With a fallback declared, that costs a second request, not a player.
   */
  private static void aFirstRefusalBuysASecondRequestTowardsTheFallback() {
    final GracefulShutdown wait = new GracefulShutdown(DEADLINE, true);
    check(wait.begin(twoPlayers(), T0) == null, "the wait opens");

    check(ALICE.equals(wait.refused(ASK_ALICE)), "the refusal names who must be asked again");
    check(wait.remaining() == 2, "and she is STILL awaited while that second request is made");
    check(wait.poll(T0 + 1L) == null, "so the wait does not end behind the retry's back");

    final UUID secondAsk = UUID.randomUUID();
    wait.fallbackAsked(ALICE, secondAsk);
    wait.playerGone(ALICE);
    wait.playerGone(BOB);

    final Verdict verdict = wait.poll(T0 + 2_000L);
    check(verdict != null && verdict.outcome() == Outcome.DRAINED,
        "the fallback moved her: the stop is drained");
    check(verdict.clean() && verdict.refused().isEmpty(),
        "and nobody is reported left behind, because nobody was");
    check(wait.fallbackAttempts() == 1, "one second chance was used, and it is counted");
  }

  /** Exactly one retry: a refusal of the fallback itself is final, and the player is named. */
  private static void aPlayerRefusedTwiceIsLeftBehindAndNamed() {
    final GracefulShutdown wait = new GracefulShutdown(DEADLINE, true);
    check(wait.begin(twoPlayers(), T0) == null, "the wait opens");

    check(ALICE.equals(wait.refused(ASK_ALICE)), "first refusal: retry");
    final UUID secondAsk = UUID.randomUUID();
    wait.fallbackAsked(ALICE, secondAsk);

    check(wait.refused(secondAsk) == null, "witness: the second refusal buys nothing more");
    check(wait.remaining() == 1, "and she stops being awaited");

    wait.playerGone(BOB);
    final Verdict verdict = wait.poll(T0 + 1_000L);
    check(verdict != null && verdict.outcome() == Outcome.REFUSED, "refused, not a deadline");
    check(verdict.refused().contains(ALICE), "and she is named, so an operator can go and look");
    check(!verdict.clean(), "twice refused is never a clean stop");
  }

  /**
   * Witness in the other direction: the retry must come from a declared fallback, never from the
   * mere fact that a refusal happened. Sending refused players somewhere nobody checked, while the
   * server is ending, would be a guess made at the worst possible moment.
   */
  private static void withoutAFallbackTheFirstRefusalIsFinal() {
    final GracefulShutdown wait = new GracefulShutdown(DEADLINE, false);
    check(wait.begin(twoPlayers(), T0) == null, "the wait opens");

    check(wait.refused(ASK_ALICE) == null, "no fallback declared: no second chance is offered");
    check(wait.remaining() == 1, "and the refusal is final at once");
    check(wait.fallbackAttempts() == 0, "nothing was attempted");
  }

  /**
   * The second request may fail to leave at all — the player disconnected, no carrier. Without a way
   * to say so, they would stay awaited until the deadline for a message that never existed: a wait
   * held open by something that had already failed.
   */
  private static void aFallbackThatCannotBeSentDoesNotHoldTheWaitOpen() {
    final GracefulShutdown wait = new GracefulShutdown(DEADLINE, true);
    check(wait.begin(Map.of(ALICE, ASK_ALICE), T0) == null, "the wait opens");

    check(ALICE.equals(wait.refused(ASK_ALICE)), "the retry is offered");
    check(wait.poll(T0 + 1L) == null, "witness: while it is pending, the wait continues");

    wait.fallbackImpossible(ALICE);

    final Verdict verdict = wait.poll(T0 + 2L);
    check(verdict != null && verdict.outcome() == Outcome.REFUSED,
        "an unsendable fallback ends the wait as a refusal, long before the deadline");
    check(verdict.refused().contains(ALICE), "and names her");
  }

  private static void aPlayerWhoLeavesTwiceIsNotCountedTwice() {
    final GracefulShutdown wait = new GracefulShutdown(DEADLINE);
    check(wait.begin(twoPlayers(), T0) == null, "the wait opens");

    wait.playerGone(ALICE);
    wait.playerGone(ALICE);

    check(wait.remaining() == 1, "one departure, one player gone");
    check(wait.poll(T0 + 1_000L) == null, "the other is still awaited");
  }

  private static void theWaitRefusesToBeOpenedTwiceOrPolledBeforeItOpens() {
    final GracefulShutdown wait = new GracefulShutdown(DEADLINE);
    check(throwsIllegalState(() -> wait.poll(T0)), "polling before opening is a mistake, not a stop");
    check(wait.begin(twoPlayers(), T0) == null, "the wait opens");
    check(throwsIllegalState(() -> wait.begin(twoPlayers(), T0)), "opening twice is a mistake");
  }

  private static void theDeadlineMustBePositive() {
    check(throwsIllegalArgument(() -> new GracefulShutdown(0L)), "a deadline of zero is refused");
    check(throwsIllegalArgument(() -> new GracefulShutdown(-1L)), "a negative deadline is refused");
  }

  private static boolean throwsIllegalState(final Runnable action) {
    try {
      action.run();
      return false;
    } catch (IllegalStateException expected) {
      return true;
    }
  }

  private static boolean throwsIllegalArgument(final Runnable action) {
    try {
      action.run();
      return false;
    } catch (IllegalArgumentException expected) {
      return true;
    }
  }

  private static void check(final boolean condition, final String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
