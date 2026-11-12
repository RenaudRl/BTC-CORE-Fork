package dev.btc.core.slime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code slime-world.save.parallel-shutdown}: saves side by side, a bounded number of them, all waited for. */
class SaveWindowTest {

    private static final int WINDOW = 4;
    private static final int WORLDS = 12;
    private static final Duration PATIENCE = Duration.ofSeconds(10);
    private static final Duration SHORT_PATIENCE = Duration.ofMillis(60);

    private final Map<String, Throwable> failures = new HashMap<>();
    private final List<CompletableFuture<Void>> started = new ArrayList<>();

    /** A save that ends when it is waited for, and not before: no clock, no thread, the test decides nothing by timing. */
    private static final class EndsWhenAwaited extends CompletableFuture<Void> {
        @Override
        public Void get(long timeout, TimeUnit unit) {
            complete(null);
            return null;
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
            saves.start("isle_" + world, this::save, PATIENCE);
            most = Math.max(most, unfinished());
        }
        assertEquals(WINDOW, most, "most saves unfinished at once");
        assertEquals(Map.of(), this.failures);
    }

    @Test
    @DisplayName("every save started is finished when the wait returns")
    void everySaveIsWaitedFor() {
        SaveWindow<String> saves = new SaveWindow<>(WINDOW, this.failures::put);
        for (int world = 0; world < WINDOW; world++) {
            saves.start("isle_" + world, this::save, PATIENCE);
        }
        assertEquals(WINDOW, unfinished(), "saves running before the wait");
        saves.awaitAll();
        assertEquals(0, unfinished(), "saves still running after the wait");
    }

    @Test
    @DisplayName("a save that fails, one that is late and one that cannot start are each reported, and the others are still saved")
    void failuresAreReportedOneByOne() {
        SaveWindow<String> saves = new SaveWindow<>(WINDOW, this.failures::put);
        IOException refused = new IOException("the source refused it");
        IllegalStateException notALevel = new IllegalStateException("not a Slime level");

        saves.start("refused", () -> CompletableFuture.failedFuture(refused), PATIENCE);
        saves.start("late", CompletableFuture::new, SHORT_PATIENCE);
        saves.start("not_started", () -> {
            throw notALevel;
        }, PATIENCE);
        CompletableFuture<Void> healthy = save();
        saves.start("healthy", () -> healthy, PATIENCE);
        saves.awaitAll();

        assertSame(refused, this.failures.get("refused"));
        assertInstanceOf(IOException.class, this.failures.get("late"));
        assertSame(notALevel, this.failures.get("not_started"));
        assertEquals(3, this.failures.size(), "worlds reported: " + this.failures.keySet());
        assertTrue(healthy.isDone(), "the save after the failures");
    }
}
