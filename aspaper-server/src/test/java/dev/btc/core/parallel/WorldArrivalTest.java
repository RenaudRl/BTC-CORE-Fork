package dev.btc.core.parallel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The menu of a player that just entered a world: test T14 of the port map ({@code pwt-carte-conflits.md}, 5). */
class WorldArrivalTest {

    @Test
    @DisplayName("T14 - a menu is put off from the moment a player enters a world until its first tick there, at login and at every change of world; with the option off it never is")
    void open_menu_refused_until_first_tick_in_new_world() {
        WorldArrival arrival = new WorldArrival();
        assertFalse(arrival.mayOpenMenu(true), "a player that logged in has not ticked yet");

        arrival.ticked();
        assertTrue(arrival.mayOpenMenu(true));

        arrival.arrived();
        assertFalse(arrival.mayOpenMenu(true), "it changed world");
        assertTrue(arrival.mayOpenMenu(false), "option off: as before");

        arrival.ticked();
        assertTrue(arrival.mayOpenMenu(true), "the tick after");
    }
}
