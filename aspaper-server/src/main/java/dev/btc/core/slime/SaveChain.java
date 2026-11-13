package dev.btc.core.slime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The saves of ONE Slime world, one behind the other (AD-096): a save starts writing only once the
 * previous save of the same chain has ended, whatever the outcome, so an older copy never lands after
 * a newer one on another thread of a shared pool. Two chains share nothing: the saves of two worlds
 * run side by side.
 *
 * <p>A failed save does not stop the chain: the next one is the newer copy and is written all the same.
 */
public final class SaveChain {

    private volatile CompletableFuture<?> last = CompletableFuture.completedFuture(null);

    /**
     * Runs {@code write} on {@code executor} once the previous save has ended. The returned future is
     * the new end of the chain. Synchronized so that two callers cannot both chain behind the same save.
     */
    public synchronized CompletableFuture<Void> then(Runnable write, Executor executor) {
        CompletableFuture<Void> save = this.last.handle((ignored, previousFailure) -> null)
            .thenRunAsync(write, executor);
        this.last = save;
        return save;
    }

    /** The last save chained, or an already completed future before the first. */
    public CompletableFuture<?> last() {
        return this.last;
    }
}
