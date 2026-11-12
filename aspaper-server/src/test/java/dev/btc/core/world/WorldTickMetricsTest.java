package dev.btc.core.world;

import dev.btc.core.world.WorldTickMetrics.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a world costs ({@code world.metrics}): the window of its tick times and the order of the command. */
class WorldTickMetricsTest {

    private static final String WORLD = "island_1";

    private static void tick(WorldTickMetrics metrics, long start, long took) {
        metrics.begin(start);
        metrics.end(start + took, 0, 0, 0);
    }

    @Test
    @DisplayName("before the window is full the average is taken over the ticks made so far, and the worst is the longest of them")
    void average_and_worst_over_the_ticks_made_so_far() {
        WorldTickMetrics metrics = new WorldTickMetrics(10);
        tick(metrics, 1_000, 10);
        tick(metrics, 2_000, 30);
        tick(metrics, 3_000, 20);

        Row row = metrics.row(WORLD);
        assertEquals(20, row.averageNanos());
        assertEquals(30, row.worstNanos());
    }

    @Test
    @DisplayName("a tick that leaves the window no longer counts: neither in the average nor as the worst")
    void a_tick_out_of_the_window_no_longer_counts() {
        WorldTickMetrics metrics = new WorldTickMetrics(3);
        tick(metrics, 1_000, 100);
        tick(metrics, 2_000, 10);
        tick(metrics, 3_000, 40);
        assertEquals(50, metrics.row(WORLD).averageNanos());
        assertEquals(100, metrics.row(WORLD).worstNanos());

        tick(metrics, 4_000, 10); // the tick of 100 leaves

        assertEquals(20, metrics.row(WORLD).averageNanos());
        assertEquals(40, metrics.row(WORLD).worstNanos());
    }

    @Test
    @DisplayName("a row carries the name asked for and what the world held at its last tick")
    void a_row_carries_what_the_world_held_at_its_last_tick() {
        WorldTickMetrics metrics = new WorldTickMetrics(3);
        metrics.begin(0);
        metrics.end(5, 100, 200, 3);
        metrics.begin(10);
        metrics.end(15, 81, 42, 2);

        assertEquals(new Row(WORLD, 5, 5, 81, 42, 2), metrics.row(WORLD));
    }

    @Test
    @DisplayName("the command lists the costliest world first, and no more worlds than asked")
    void costliest_first_and_no_more_than_asked() {
        Row cheap = new Row("cheap", 10, 900, 0, 0, 0);
        Row costly = new Row("costly", 300, 310, 0, 0, 0);
        Row middle = new Row("middle", 50, 60, 0, 0, 0);

        assertEquals(List.of(costly, middle), WorldTickMetrics.costliest(List.of(cheap, costly, middle), 2));
        assertEquals(List.of(costly, middle, cheap), WorldTickMetrics.costliest(List.of(cheap, costly, middle), 10));
        assertEquals(List.of(), WorldTickMetrics.costliest(List.of(cheap), 0));
    }
}
