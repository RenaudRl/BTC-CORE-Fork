package dev.btc.core.slime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * The saves of ONE Slime world, bounded: at most one is being written and one is waiting (AD-096,
 * task 5.5). A save starts writing only once the previous one has ended, whatever the outcome, so an
 * older copy never lands after a newer one on another thread of a shared pool. Two chains share
 * nothing: the saves of two worlds run side by side.
 *
 * <p>A save asked while another is being written waits, and a save asked while one is already waiting
 * <em>replaces</em> it: the last request carries the newest copy of the world, which is the one worth
 * writing, and the copy it replaced is dropped unwritten. The callers of every request that was folded
 * into the waiting one share its future, completed by the write of the newest copy: none is told its
 * state is saved before a copy at least as recent has been written. So a world whose saves are asked
 * faster than they are written costs two copies in memory and two writes, not a queue.
 *
 * <p>A failed (or refused) write fails the future of its callers and nothing else: the next save is a
 * newer copy and is written all the same.
 */
public final class SaveChain {

    /** The write waiting for its turn; its callers hold {@link #done}. Mutated only while it is {@link #waiting}. */
    private static final class Request {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable write;
        Executor executor;

        Request(Runnable write, Executor executor) {
            this.write = write;
            this.executor = executor;
        }
    }

    private final Object lock = new Object();
    /** A save has been handed to its executor and has not ended. Guarded by {@link #lock}. */
    private boolean running;
    /** Guarded by {@link #lock}. */
    private Request waiting;
    private volatile CompletableFuture<?> last = CompletableFuture.completedFuture(null);

    /**
     * Asks for a save: {@code write} runs on {@code executor} once the save in progress, if any, has
     * ended, unless a later request replaced it before it started. Never blocks.
     *
     * @return the end of this save, or of the later one that replaced it
     */
    public CompletableFuture<Void> then(Runnable write, Executor executor) {
        Request start = null;
        CompletableFuture<Void> future;
        synchronized (this.lock) {
            if (!this.running) {
                this.running = true;
                start = new Request(write, executor);
                future = start.done;
            } else {
                if (this.waiting == null) {
                    this.waiting = new Request(write, executor);
                } else {
                    this.waiting.write = write;
                    this.waiting.executor = executor;
                }
                future = this.waiting.done;
            }
            this.last = future;
        }
        // Outside the lock: an executor that runs the task inline must not hold it.
        if (start != null) this.dispatch(start);
        return future;
    }

    /** The last save asked, which ends after every earlier one; an already completed future before the first. */
    public CompletableFuture<?> last() {
        return this.last;
    }

    /** Hands {@code request} to its executor; one the executor refuses fails, and the one waiting behind it goes next. */
    private void dispatch(Request request) {
        while (request != null) {
            Request current = request;
            try {
                current.executor.execute(() -> this.run(current));
                return;
            } catch (Throwable refused) { // RejectedExecutionException: the pool is shut down
                current.done.completeExceptionally(wrapped(refused));
                request = this.next();
            }
        }
    }

    private void run(Request request) {
        Throwable failure = null;
        try {
            request.write.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        if (failure == null) request.done.complete(null);
        else request.done.completeExceptionally(wrapped(failure));
        this.dispatch(this.next());
    }

    /** The request that was waiting, now the one to write; the chain is idle when there is none. */
    private Request next() {
        synchronized (this.lock) {
            Request next = this.waiting;
            this.waiting = null;
            if (next == null) this.running = false;
            return next;
        }
    }

    private static Throwable wrapped(Throwable failure) {
        return failure instanceof CompletionException ? failure : new CompletionException(failure);
    }
}
