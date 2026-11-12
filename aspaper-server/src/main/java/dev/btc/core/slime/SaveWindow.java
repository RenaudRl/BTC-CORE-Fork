package dev.btc.core.slime;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Saves started one after the other by one thread and finished by others, never more than
 * {@code size} unfinished at a time ({@code slime-world.save.parallel-shutdown}). What a save
 * holds until it is written is the copy of its world: started all at once, fifty worlds would
 * hold fifty copies. A save that fails, is late or cannot be started goes to {@code failed};
 * nothing is thrown, the next world is still saved.
 *
 * <p>Not thread-safe: {@link #start} and {@link #awaitAll} belong to the thread that starts the saves.
 */
public final class SaveWindow<K> {

    private record Started<K>(K key, Future<?> save, Duration patience) {
    }

    private final int size;
    private final BiConsumer<K, Throwable> failed;
    private final Deque<Started<K>> unfinished = new ArrayDeque<>();

    public SaveWindow(int size, BiConsumer<K, Throwable> failed) {
        if (size < 1) throw new IllegalArgumentException("A save window holds at least one save, not " + size);
        this.size = size;
        this.failed = failed;
    }

    /**
     * Starts the save of {@code key} once fewer than {@code size} are unfinished, waiting for the
     * oldest if need be. {@code patience} is the longest this save is waited for, once its turn comes.
     */
    public void start(K key, Supplier<? extends Future<?>> save, Duration patience) {
        this.unfinished.removeIf(started -> started.save().isDone() && this.settle(started));
        while (this.unfinished.size() >= this.size) {
            this.settle(this.unfinished.removeFirst());
        }
        try {
            this.unfinished.addLast(new Started<>(key, save.get(), patience));
        } catch (RuntimeException notStarted) {
            this.failed.accept(key, notStarted);
        }
    }

    /** Waits for every save still unfinished, the oldest first. */
    public void awaitAll() {
        while (!this.unfinished.isEmpty()) {
            this.settle(this.unfinished.removeFirst());
        }
    }

    /** Waits for one save and reports what went wrong with it; always true, so it can sit in a filter. */
    private boolean settle(Started<K> started) {
        try {
            started.save().get(started.patience().toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failure) {
            this.failed.accept(started.key(), failure.getCause());
        } catch (TimeoutException late) {
            this.failed.accept(started.key(), new IOException("not saved within " + started.patience(), late));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            this.failed.accept(started.key(), interrupted);
        } catch (RuntimeException failure) { // a cancelled save
            this.failed.accept(started.key(), failure);
        }
        return true;
    }
}
