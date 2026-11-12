package dev.btc.core.parallel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What {@code WorldTickLoop} does with a turn and with the worlds that threw, without a server: which
 * failure may end in a quarantine, which one stops the server, and with what.
 */
class WorldTickStepsTest {

    private static final Runnable NOTHING = () -> { };

    private final List<String> decided = new ArrayList<>();
    private final List<String> told = new ArrayList<>();

    /** The crash of the vanilla loop, for every world whose tick threw. */
    private final BiFunction<String, Throwable, Throwable> crash = (world, thrown) -> {
        this.decided.add(world);
        return new IllegalStateException("Exception ticking world " + world, thrown);
    };
    private final BiConsumer<String, Throwable> tell = (world, thrown) -> this.told.add(world + ": " + thrown.getMessage());

    /** What a turn hands to the coordinator when one of its steps throws. */
    private static Throwable thrownBy(Runnable before, Runnable tick, Runnable after) {
        return assertThrows(Throwable.class, () -> WorldTickSteps.run(before, tick, after));
    }

    private static Runnable throwing(RuntimeException failure) {
        return () -> {
            throw failure;
        };
    }

    private static Map<String, Throwable> failures(Object... worldThenFailure) {
        Map<String, Throwable> failures = new LinkedHashMap<>();
        for (int i = 0; i < worldThenFailure.length; i += 2) failures.put((String) worldThenFailure[i], (Throwable) worldThenFailure[i + 1]);
        return failures;
    }

    @Test
    @DisplayName("what the tick of a world throws is a failing tick: its crash is what stops the server, and nothing runs after it")
    void what_the_tick_throws_is_a_failing_tick() {
        RuntimeException boom = new IllegalArgumentException("boom");
        List<String> ran = new ArrayList<>();
        Throwable handedBack = thrownBy(() -> ran.add("before"), throwing(boom), () -> ran.add("after"));

        IllegalStateException stopped = assertThrows(IllegalStateException.class,
            () -> WorldTickSteps.settle(failures("bad", handedBack), world -> false, this.tell, this.crash));

        assertEquals(List.of("before"), ran);
        assertEquals("Exception ticking world bad", stopped.getMessage());
        assertSame(boom, stopped.getCause(), "the decision is taken on what the tick threw, not on its carrier");
        assertEquals(List.of("bad"), this.decided);
    }

    @Test
    @DisplayName("what is thrown before or after the tick is never a failing tick: it stops the server as it is")
    void what_is_thrown_around_the_tick_is_thrown_as_it_is() {
        RuntimeException early = new IllegalStateException("before the tick");
        RuntimeException late = new IllegalStateException("after the tick");

        assertSame(early, assertThrows(RuntimeException.class, () -> WorldTickSteps.settle(
            failures("w", thrownBy(throwing(early), NOTHING, NOTHING)), world -> false, this.tell, this.crash)));
        assertSame(late, assertThrows(RuntimeException.class, () -> WorldTickSteps.settle(
            failures("w", thrownBy(NOTHING, NOTHING, throwing(late))), world -> false, this.tell, this.crash)));

        assertEquals(List.of(), this.decided, "never offered to the quarantine");
    }

    @Test
    @DisplayName("a world that could not be handed to its thread stops the server with what was thrown, even a checked exception")
    void a_failed_hand_over_is_thrown_as_it_is() {
        Error noThread = new OutOfMemoryError("unable to create native thread");
        WorldTickCoordinator<String> coordinator = new WorldTickCoordinator<>(true, 1, (world, task) -> {
            throw noThread;
        });
        try {
            Map<String, Throwable> handedBack = coordinator.tickAll(List.of("starved"), world -> WorldTickSteps.run(NOTHING, NOTHING, NOTHING));
            assertSame(noThread, assertThrows(Error.class,
                () -> WorldTickSteps.settle(handedBack, coordinator::isReleased, this.tell, this.crash)));
        } finally {
            coordinator.shutdown();
        }

        Exception checked = new IOException("sneaked out of a tick");
        assertSame(checked, assertThrows(IOException.class,
            () -> WorldTickSteps.settle(failures("w", checked), world -> false, this.tell, this.crash)));
        assertEquals(List.of(), this.decided);
    }

    @Test
    @DisplayName("a world taken care of stops nothing; the crash of another one still does")
    void a_world_taken_care_of_stops_nothing() {
        Throwable quarantinable = thrownBy(NOTHING, throwing(new IllegalStateException("island")), NOTHING);
        Throwable fatal = thrownBy(NOTHING, throwing(new IllegalStateException("hub")), NOTHING);
        BiFunction<String, Throwable, Throwable> quarantineIslands = (world, thrown) -> world.equals("hub") ? this.crash.apply(world, thrown) : null;

        assertDoesNotThrow(() -> WorldTickSteps.settle(failures("island_1", quarantinable), world -> false, this.tell, quarantineIslands));

        IllegalStateException stopped = assertThrows(IllegalStateException.class, () -> WorldTickSteps.settle(
            failures("island_1", quarantinable, "hub", fatal, "island_2", quarantinable), world -> false, this.tell, quarantineIslands));
        assertEquals("Exception ticking world hub", stopped.getMessage());
        assertEquals(0, stopped.getSuppressed().length);
    }

    @Test
    @DisplayName("every crash of the phase is in the one thrown: the first one, the others suppressed in it, in order")
    void every_crash_is_in_the_one_thrown() {
        Throwable first = thrownBy(NOTHING, throwing(new IllegalStateException("a")), NOTHING);
        RuntimeException around = new IllegalStateException("around the tick of b");
        Throwable third = thrownBy(NOTHING, throwing(new IllegalStateException("c")), NOTHING);

        IllegalStateException stopped = assertThrows(IllegalStateException.class, () -> WorldTickSteps.settle(
            failures("a", first, "b", around, "c", third), world -> false, this.tell, this.crash));

        assertEquals("Exception ticking world a", stopped.getMessage());
        assertEquals(2, stopped.getSuppressed().length);
        assertSame(around, stopped.getSuppressed()[0]);
        assertEquals("Exception ticking world c", stopped.getSuppressed()[1].getMessage());
    }

    @Test
    @DisplayName("the same error handed back by two worlds is thrown once, not suppressed in itself")
    void the_same_error_from_two_worlds_is_thrown_once() {
        Error shared = new OutOfMemoryError("shared");

        assertSame(shared, assertThrows(Error.class,
            () -> WorldTickSteps.settle(failures("a", shared, "b", shared), world -> false, this.tell, this.crash)));
        assertArrayEquals(new Throwable[0], shared.getSuppressed());
    }

    @Test
    @DisplayName("what a world unloaded during the phase threw is told and nothing else: no crash, no quarantine")
    void a_released_world_is_only_told() {
        Throwable ofTheTick = thrownBy(NOTHING, throwing(new IllegalStateException("closed under my feet")), NOTHING);
        RuntimeException around = new IllegalStateException("gone before its tick events");

        assertDoesNotThrow(() -> WorldTickSteps.settle(
            failures("left_1", ofTheTick, "left_2", around), world -> world.startsWith("left"), this.tell, this.crash));

        assertEquals(List.of("left_1: closed under my feet", "left_2: gone before its tick events"), this.told);
        assertEquals(List.of(), this.decided);
    }
}
