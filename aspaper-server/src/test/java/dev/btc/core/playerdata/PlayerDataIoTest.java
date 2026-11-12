package dev.btc.core.playerdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataIoTest {

    private static final int WRITES = 200;

    @Test
    @DisplayName("writes of one player run in the order they were handed in, off the calling thread")
    void workOfOnePlayerKeepsItsOrder() {
        PlayerDataIo io = new PlayerDataIo();
        UUID player = UUID.randomUUID();
        List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < WRITES; i++) {
            int write = i;
            io.submit(player, () -> {
                if (write % 2 == 0) {
                    Thread.yield(); // gives a later write the chance to overtake, if anything let it
                }
                ran.add(write);
                threads.add(Thread.currentThread());
            });
        }

        assertTrue(io.shutdown(10, TimeUnit.SECONDS));
        assertEquals(IntStream.range(0, WRITES).boxed().toList(), ran);
        threads.forEach(thread -> assertNotEquals(Thread.currentThread(), thread));
    }

    @Test
    @DisplayName("one player's slow write does not hold another player's back")
    void playersDoNotQueueBehindEachOther() throws InterruptedException {
        PlayerDataIo io = new PlayerDataIo();
        CountDownLatch slow = new CountDownLatch(1);
        CountDownLatch other = new CountDownLatch(1);
        io.submit(UUID.randomUUID(), () -> await(slow));

        io.submit(UUID.randomUUID(), other::countDown);

        assertTrue(other.await(5, TimeUnit.SECONDS));
        slow.countDown();
        assertTrue(io.shutdown(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("a reader waits for the player's queued write, and for nothing when there is none")
    void awaitBlocksUntilThePlayersQueueIsEmpty() throws InterruptedException {
        PlayerDataIo io = new PlayerDataIo();
        UUID player = UUID.randomUUID();
        CountDownLatch write = new CountDownLatch(1);
        CountDownLatch read = new CountDownLatch(1);
        io.submit(player, () -> await(write));

        io.await(UUID.randomUUID()); // nothing queued for that one: returns
        Thread reader = new Thread(() -> {
            io.await(player);
            read.countDown();
        });
        reader.start();

        assertFalse(read.await(300, TimeUnit.MILLISECONDS), "the read went ahead of the queued write");
        write.countDown();
        assertTrue(read.await(5, TimeUnit.SECONDS));
        assertTrue(io.shutdown(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("shutdown returns only once every queued write has run")
    void shutdownDrainsEverythingQueued() {
        PlayerDataIo io = new PlayerDataIo();
        AtomicInteger written = new AtomicInteger();
        for (int i = 0; i < WRITES; i++) {
            io.submit(UUID.randomUUID(), () -> {
                sleep(1);
                written.incrementAndGet();
            });
        }

        assertTrue(io.shutdown(30, TimeUnit.SECONDS));

        assertEquals(WRITES, written.get());
    }

    @Test
    @DisplayName("shutdown says so when the queue did not empty in time")
    void shutdownReportsWhatItCouldNotDrain() {
        PlayerDataIo io = new PlayerDataIo();
        CountDownLatch stuck = new CountDownLatch(1);
        io.submit(UUID.randomUUID(), () -> await(stuck));

        assertFalse(io.shutdown(50, TimeUnit.MILLISECONDS));
        stuck.countDown();
    }

    @Test
    @DisplayName("work handed in after shutdown still runs, on the calling thread")
    void lateWorkIsNotDropped() {
        PlayerDataIo io = new PlayerDataIo();
        io.shutdown(1, TimeUnit.SECONDS);
        AtomicInteger written = new AtomicInteger();

        io.submit(UUID.randomUUID(), written::incrementAndGet);

        assertEquals(1, written.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
