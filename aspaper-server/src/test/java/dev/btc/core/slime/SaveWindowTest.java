package dev.btc.core.slime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code slime-world.save.parallel-shutdown}: saves side by side, a bounded number of them, all waited for. */
class SaveWindowTest {

    private static final int WINDOW = 4;
    private static final int WORLDS = 12;

    private final Map<String, Throwable> failures = new HashMap<>();
    private final List<CompletableFuture<Void>> started = new ArrayList<>();

    /**
     * A save that ends when it is waited for, and not before: no clock, no thread, the test decides
     * nothing by timing. It outlasts any wait that has an end, as a save longer than a lease does.
     */
    private static final class EndsWhenAwaited extends CompletableFuture<Void> {
        @Override
        public Void get() {
            complete(null);
            return null;
        }

        @Override
        public Void get(long timeout, TimeUnit unit) throws TimeoutException {
            throw new TimeoutException("still saving after " + timeout + " " + unit);
        }
    }

    private CompletableFuture<Void> save() {
        CompletableFuture<Void> save = new EndsWhenAwaited();
        this.started.add(save);
        return save;
    }

    private long unfinished() {
        return this.started.stream().filter(save -> !save.isDone()).count();
    }

    @Test
    @DisplayName("as many saves as the window holds run side by side, and never one more")
    void theWindowBoundsTheSavesInFlight() {
        SaveWindow<String> saves = new SaveWindow<>(WINDOW, this.failures::put);
        long most = 0;
        for (int world = 0; world < WORLDS; world++) {
            saves.start("isle_" + world, this::save);
            most = Math.max(most, unfinished());
        }
        assertEquals(WINDOW, most, "most saves unfinished at once");
        assertEquals(Map.of(), this.failures);
    }

    @Test
    @DisplayName("every save started is finished when the wait returns, however long it takes: none is given up on")
    void everySaveIsWaitedFor() {
        SaveWindow<String> saves = new SaveWindow<>(WINDOW, this.failures::put);
        for (int world = 0; world < WINDOW; world++) {
            saves.start("isle_" + world, this::save);
        }
        assertEquals(WINDOW, unfinished(), "saves running before the wait");
        saves.awaitAll();
        assertEquals(0, unfinished(), "saves still running after the wait");
        assertEquals(Map.of(), this.failures, "saves given up on");
    }

    @Test
    @DisplayName("an interrupted wait still waits for every running save, and hands the interruption back at the end")
    void anInterruptedWaitStillWaitsForEverySave() {
        // Like a real future: get() on an interrupted thread throws and clears the flag, unless it is done
        List<CompletableFuture<Void>> running = new ArrayList<>();
        SaveWindow<String> saves = new SaveWindow<>(WINDOW, this.failures::put);
        for (int world = 0; world < WINDOW; world++) {
            CompletableFuture<Void> save = new CompletableFuture<>() {
                @Override
                public Void get() throws InterruptedException {
                    if (Thread.interrupted()) throw new InterruptedException("interrupted while saving");
                    complete(null);
                    return null;
                }
            };
            running.add(save);
            saves.start("isle_" + world, () -> save);
        }
        Thread.currentThread().interrupt();
        try {
            saves.awaitAll();
            assertTrue(running.stream().allMatch(CompletableFuture::isDone), "saves still running after an interrupted wait");
            assertEquals(Map.of(), this.failures, "an interruption is not a failed save");
            assertTrue(Thread.currentThread().isInterrupted(), "the interruption handed back");
        } finally {
            Thread.interrupted(); // the flag must not leak into the next test
        }
    }

    @Test
    @DisplayName("a save that fails and one that cannot start are each reported, and the others are still saved")
    void failuresAreReportedOneByOne() {
        SaveWindow<String> saves = new SaveWindow<>(WINDOW, this.failures::put);
        IOException refused = new IOException("the source refused it");
        IllegalStateException notALevel = new IllegalStateException("not a Slime level");

        saves.start("refused", () -> CompletableFuture.failedFuture(refused));
        saves.start("not_started", () -> {
            throw notALevel;
        });
        CompletableFuture<Void> healthy = save();
        saves.start("healthy", () -> healthy);
        saves.awaitAll();

        assertSame(refused, this.failures.get("refused"));
        assertSame(notALevel, this.failures.get("not_started"));
        assertEquals(2, this.failures.size(), "worlds reported: " + this.failures.keySet());
        assertTrue(healthy.isDone(), "the save after the failures");
    }
}
