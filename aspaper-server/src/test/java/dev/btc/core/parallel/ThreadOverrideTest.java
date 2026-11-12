package dev.btc.core.parallel;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ThreadOverrideTest {

    private static final boolean PARALLEL = true;
    private static final boolean ONE_THREAD = false;

    @Test // T11
    void thread_local_overrides_do_not_leak_between_threads() throws InterruptedException {
        ThreadOverride<String> treeType = new ThreadOverride<>(null);          // sapling
        ThreadOverride<String> sourceBlock = new ThreadOverride<>(null);       // sculk
        ThreadOverride<Boolean> shouldSignal = new ThreadOverride<>(true);     // redstone

        treeType.set(PARALLEL, "BIRCH");
        sourceBlock.set(PARALLEL, "1,2,3");
        shouldSignal.set(PARALLEL, false);

        assertNull(onAnotherThread(() -> treeType.get(PARALLEL)));
        assertNull(onAnotherThread(() -> sourceBlock.get(PARALLEL)));
        assertEquals(true, onAnotherThread(() -> shouldSignal.get(PARALLEL)));

        onAnotherThread(() -> {
            treeType.set(PARALLEL, "OAK");
            sourceBlock.set(PARALLEL, "4,5,6");
            shouldSignal.set(PARALLEL, true);
            return null;
        });

        assertEquals("BIRCH", treeType.get(PARALLEL));
        assertEquals("1,2,3", sourceBlock.get(PARALLEL));
        assertEquals(false, shouldSignal.get(PARALLEL));
    }

    @Test
    void without_parallel_ticking_the_override_is_the_one_slot_it_always_was() throws InterruptedException {
        ThreadOverride<String> treeType = new ThreadOverride<>(null);
        ThreadOverride<Boolean> shouldSignal = new ThreadOverride<>(true);

        assertEquals(true, shouldSignal.get(ONE_THREAD));
        treeType.set(ONE_THREAD, "BIRCH");

        assertEquals("BIRCH", onAnotherThread(() -> treeType.get(ONE_THREAD)));
        onAnotherThread(() -> {
            shouldSignal.set(ONE_THREAD, false);
            return null;
        });
        assertEquals(false, shouldSignal.get(ONE_THREAD));
    }

    private static <T> T onAnotherThread(Supplier<T> action) throws InterruptedException {
        AtomicReference<T> result = new AtomicReference<>();
        Thread thread = new Thread(() -> result.set(action.get()));
        thread.start();
        thread.join();
        return result.get();
    }
}
