package dev.btc.core.parallel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MidTickBudgetTest {

    private static final long GC_PAUSE_NANOS = 2_000_000_000L;

    @Test
    @DisplayName("a run that used exactly its allowance waits one gap")
    void a_run_on_budget_waits_one_gap() {
        assertEquals(MidTickBudget.GAP_NANOS, MidTickBudget.delayNanos(MidTickBudget.ALLOWANCE_NANOS, false));
    }

    @Test
    @DisplayName("a run twice over its allowance waits two gaps")
    void overuse_stretches_the_wait_in_proportion() {
        assertEquals(2 * MidTickBudget.GAP_NANOS, MidTickBudget.delayNanos(2 * MidTickBudget.ALLOWANCE_NANOS, false));
    }

    @Test
    @DisplayName("a GC pause in the middle of a run counts for ten milliseconds, not for its length")
    void overuse_is_capped() {
        long capped = MidTickBudget.delayNanos(MidTickBudget.ALLOWANCE_NANOS + MidTickBudget.MAX_OVERUSE_NANOS, false);

        assertEquals(capped, MidTickBudget.delayNanos(GC_PAUSE_NANOS, false));
    }

    @Test
    @DisplayName("an instant run with work left may run again at once; with an empty queue it backs off")
    void an_empty_queue_always_backs_off() {
        assertEquals(0L, MidTickBudget.delayNanos(0L, false));
        assertEquals(MidTickBudget.EMPTY_BACKOFF_NANOS, MidTickBudget.delayNanos(0L, true));
    }
}
