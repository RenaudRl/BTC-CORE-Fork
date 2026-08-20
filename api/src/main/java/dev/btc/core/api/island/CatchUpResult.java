package dev.btc.core.api.island;

import java.util.Objects;

/**
 * What a {@link CatchUpHandler} did with one operation.
 *
 * <p>The distinction that matters is between {@link Status#FAILED_RETRYABLE} and
 * {@link Status#REJECTED}. A retryable failure leaves the operation open: a later backend will pick
 * it up from the same {@code from} timestamp, so the handler must not have applied a partial
 * mutation it cannot repeat. A rejection closes it: the platform records the reason and does not
 * come back.
 *
 * <p>There is deliberately no "succeeded, probably" state. A handler that cannot tell whether its
 * mutation reached durable storage must report {@link Status#FAILED_RETRYABLE} and be written so
 * that repeating it is harmless.
 *
 * @param status     what happened
 * @param operations how many bounded operations were applied, for the platform's own accounting
 * @param diagnostic a short human-readable note, or {@code null}
 * @param rejection  why it was rejected, non-{@code null} exactly when {@code status} is
 *                   {@link Status#REJECTED}
 */
public record CatchUpResult(Status status, int operations, String diagnostic, CatchUpRejection rejection) {

    public enum Status {
        /** The mutation reached durable storage; the operation may be committed. */
        COMMITTED,
        /** Nothing needed doing — no elapsed time, no eligible state. */
        NO_WORK,
        /** The mutation did not complete; the operation stays open for a later attempt. */
        FAILED_RETRYABLE,
        /** The operation must not be attempted again. */
        REJECTED
    }

    public CatchUpResult {
        Objects.requireNonNull(status, "status");
        if (operations < 0) {
            throw new IllegalArgumentException("operations must not be negative");
        }
        if ((status == Status.REJECTED) != (rejection != null)) {
            throw new IllegalArgumentException("rejection must be set exactly when status is REJECTED");
        }
    }

    public static CatchUpResult committed(int operations) {
        return new CatchUpResult(Status.COMMITTED, operations, null, null);
    }

    public static CatchUpResult noWork() {
        return new CatchUpResult(Status.NO_WORK, 0, null, null);
    }

    public static CatchUpResult retryable(String diagnostic) {
        return new CatchUpResult(Status.FAILED_RETRYABLE, 0, diagnostic, null);
    }

    public static CatchUpResult rejected(CatchUpRejection rejection, String diagnostic) {
        return new CatchUpResult(Status.REJECTED, 0, diagnostic, Objects.requireNonNull(rejection, "rejection"));
    }
}
