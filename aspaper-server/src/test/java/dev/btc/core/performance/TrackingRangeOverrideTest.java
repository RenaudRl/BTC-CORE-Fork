package dev.btc.core.performance;

import dev.btc.core.api.BTCCoreAPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackingRangeOverrideTest {

    private static final int TYPE_RANGE = 80;

    @Test
    @DisplayName("an entity without an override is tracked at the range of its type")
    void noOverrideKeepsTheTypeRange() {
        assertEquals(TYPE_RANGE, TrackingRangeOverride.effective(TYPE_RANGE, BTCCoreAPI.TRACKING_RANGE_DEFAULT));
    }

    @Test
    @DisplayName("an override replaces the range of the type, whether it is wider or narrower")
    void anOverrideReplacesTheTypeRange() {
        assertEquals(300, TrackingRangeOverride.effective(TYPE_RANGE, 300));
        assertEquals(16, TrackingRangeOverride.effective(TYPE_RANGE, 16));
    }

    @Test
    @DisplayName("a range asked below 0 clears the override")
    void aNegativeRangeClears() {
        assertEquals(BTCCoreAPI.TRACKING_RANGE_DEFAULT, TrackingRangeOverride.bounded(-1));
        assertEquals(BTCCoreAPI.TRACKING_RANGE_DEFAULT, TrackingRangeOverride.bounded(Integer.MIN_VALUE));
    }

    @Test
    @DisplayName("a range asked outside the bounds is brought to the nearest one, never to 'no override'")
    void aRangeIsBounded() {
        assertEquals(BTCCoreAPI.TRACKING_RANGE_MIN, TrackingRangeOverride.bounded(0));
        assertEquals(BTCCoreAPI.TRACKING_RANGE_MAX, TrackingRangeOverride.bounded(BTCCoreAPI.TRACKING_RANGE_MAX + 1));
        assertEquals(BTCCoreAPI.TRACKING_RANGE_MAX, TrackingRangeOverride.bounded(Integer.MAX_VALUE));
        assertEquals(200, TrackingRangeOverride.bounded(200));
    }
}
