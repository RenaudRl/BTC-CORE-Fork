package dev.btc.core.parallel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A thread that is not the main one waits for something it handed to the main thread, without ever
 * waiting for good: a server that stops no longer runs what it was handed.
 *
 * <p>It cannot tell a main thread that is late from one that is itself waiting for the caller - a
 * deadlock. That one ends when the watchdog stops the server; until then {@code stillWaiting} says
 * what is going on.
 */
public final class MainThreadHandOff {

    /** How long one wait lasts before the stop of the server is looked at again. */
    public static final long SLICE_MILLIS = 1_000L;
    /** After this many slices the wait is reported, once: long enough not to be a slow tick. */
    public static final int REPORT_AFTER_SLICES = 10;

    private MainThreadHandOff() {
    }

    /**
     * The task the main thread runs for {@code handed}. A caller that gave up ({@link #await} left
     * with an error and cancelled it) is no longer there to take the result: the work is not done.
     */
    public static <T> void fulfil(CompletableFuture<T> handed, Supplier<T> work) {
        if (handed.isDone()) return;
        try {
            handed.complete(work.get());
        } catch (Throwable failure) {
            handed.completeExceptionally(failure);
        }
    }

    /**
     * Leaving with an error cancels {@code handed}, so that {@link #fulfil} skips the work.
     *
     * @param handed       what the main thread was given to do
     * @param sliceMillis  how long to wait between two looks at {@code stopping}
     * @param stopping     the server stops: what was handed will not run any more
     * @param stillWaiting told once, with the time waited in milliseconds, when the main thread is late
     * @param what         what is waited for, for the messages: "copy world dungeon_1"
     * @throws IllegalStateException the server stopped first, or the caller was interrupted
     */
    public static <T> T await(Future<T> handed, long sliceMillis, BooleanSupplier stopping, Consumer<Long> stillWaiting, String what) {
        for (int slices = 1; ; slices++) {
            try {
                return handed.get(sliceMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException slice) {
                if (stopping.getAsBoolean()) {
                    handed.cancel(false);
                    throw new IllegalStateException("Cannot " + what + ": the server stops, and the main thread no longer "
                        + "runs what it was handed");
                }
                if (slices == REPORT_AFTER_SLICES) stillWaiting.accept(slices * sliceMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                handed.cancel(false);
                throw new IllegalStateException("Interrupted while waiting for the main thread to " + what, interrupted);
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof RuntimeException thrown) throw thrown;
                if (failed.getCause() instanceof Error thrown) throw thrown;
                throw new IllegalStateException("The main thread failed to " + what, failed.getCause());
            }
        }
    }
}
