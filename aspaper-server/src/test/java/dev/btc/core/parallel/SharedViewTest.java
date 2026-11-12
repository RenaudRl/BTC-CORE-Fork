package dev.btc.core.parallel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** The objectives, teams and team members of the scoreboard, read while another world changes them. */
class SharedViewTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final long STILL_BLOCKED_MILLIS = 200L;

    @Test
    @DisplayName("option off: the reader gets the live collection, as vanilla hands it out")
    void off_hands_out_the_live_collection() {
        List<String> teams = new ArrayList<>(List.of("red"));

        assertSame(teams, SharedView.of(false, new Object(), teams));
    }

    @Test
    @DisplayName("option on: the reader gets a copy that a later change does not reach, so its walk cannot break")
    void on_hands_out_a_copy() {
        List<String> teams = new ArrayList<>(List.of("red"));

        Collection<String> view = SharedView.of(true, new Object(), teams);
        teams.add("blue");

        assertEquals(List.of("red"), List.copyOf(view));
    }

    @Test
    @DisplayName("option on: the thread that holds the lock - the scoreboard changing a team - still gets the collection itself")
    void lock_holder_gets_the_live_collection() {
        List<String> members = new ArrayList<>(List.of("zombie"));
        Object scoreboard = new Object();

        synchronized (scoreboard) {
            assertSame(members, SharedView.of(true, scoreboard, members));
        }
    }

    @Test
    @DisplayName("option on: the copy is taken under the lock, never while another thread is in the middle of a change")
    void copy_waits_for_the_change_in_progress() throws Exception {
        List<String> teams = new ArrayList<>(List.of("red"));
        Object scoreboard = new Object();
        CountDownLatch changing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Thread world = new Thread(() -> {
            synchronized (scoreboard) {
                changing.countDown();
                try {
                    finish.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                teams.add("blue");
            }
        }, "test world changing the scoreboard");
        world.setDaemon(true);
        world.start();
        changing.await();

        CompletableFuture<Collection<String>> view = CompletableFuture.supplyAsync(() -> SharedView.of(true, scoreboard, teams));
        try {
            view.get(STILL_BLOCKED_MILLIS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException expected) {
            // the reader is waiting for the change, as it must
        }
        assertFalse(view.isDone(), "the reader copied in the middle of a change");
        finish.countDown();

        assertEquals(List.of("red", "blue"), List.copyOf(assertTimeoutPreemptively(TIMEOUT, () -> view.get())));
    }
}
