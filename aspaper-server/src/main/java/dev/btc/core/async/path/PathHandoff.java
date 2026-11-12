package dev.btc.core.async.path;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The one place a path search result crosses threads. BTC-CORE addition to the Petal port: the
 * upstream {@code AsyncPath} carries this state inline (ret / ready / postProcessing); it is split
 * out, unchanged in behaviour, so the rule can be tested without a server.
 *
 * <p>The rule: a pool thread may only {@link #publish}. Applying the result, and running whatever
 * was waiting for it, happens in {@link #applyIfPublished}, which only the thread that owns the
 * entity calls. A result nobody asks for again is never applied.
 */
public final class PathHandoff<T> {

    private final AtomicReference<@Nullable T> published = new AtomicReference<>();

    // Owner thread only from here on.
    private boolean applied;
    private @Nullable List<Runnable> waiting;

    /** Any thread. Stores the result and touches nothing else; the first result wins. */
    public void publish(final T result) {
        this.published.compareAndSet(null, result);
    }

    public @Nullable T published() {
        return this.published.get();
    }

    public boolean isApplied() {
        return this.applied;
    }

    /**
     * Owner thread. Applies a published result once, then runs what was waiting for it.
     *
     * @return whether the result is applied by the time this returns
     */
    public boolean applyIfPublished(final Consumer<? super T> apply) {
        if (this.applied) {
            return true;
        }
        final T result = this.published.get();
        if (result == null) {
            return false;
        }
        apply.accept(result);
        this.applied = true;

        final List<Runnable> ready = this.waiting;
        this.waiting = null;
        if (ready != null) {
            for (final Runnable action : ready) {
                action.run();
            }
        }
        return true;
    }

    /**
     * Owner thread. Runs {@code action} once the result is applied, unless the request it belongs to
     * has been replaced by then: {@code stillCurrent} is asked at that moment, not now.
     */
    public void whenApplied(final BooleanSupplier stillCurrent, final Runnable action) {
        final Runnable guarded = () -> {
            if (stillCurrent.getAsBoolean()) {
                action.run();
            }
        };
        if (this.applied) {
            guarded.run();
            return;
        }
        if (this.waiting == null) {
            this.waiting = new ArrayList<>(1);
        }
        this.waiting.add(guarded);
    }
}
