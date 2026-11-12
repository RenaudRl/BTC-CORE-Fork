package dev.btc.core.performance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@code performance.network.shared-chunk-packets}: one packet per chunk within a window, nothing kept past it. */
class SharedChunkPacketsTest {

    private static final long CHUNK = 42L;
    private static final long OTHER_CHUNK = 43L;

    private final SharedChunkPackets<Object> packets = new SharedChunkPackets<>();

    private Object packet(long chunk) {
        return this.packets.share(chunk, key -> new Object());
    }

    @Test
    @DisplayName("within a window two players sent the same chunk get the same packet, and another chunk its own")
    void sharedWithinAWindow() {
        this.packets.open();
        Object first = packet(CHUNK);
        assertSame(first, packet(CHUNK));
        assertNotSame(first, packet(OTHER_CHUNK));
    }

    @Test
    @DisplayName("outside a window every packet is built for its player")
    void neverSharedOutsideAWindow() {
        assertNotSame(packet(CHUNK), packet(CHUNK), "before any window");
        this.packets.open();
        Object inside = packet(CHUNK);
        this.packets.close();
        Object after = packet(CHUNK);
        assertNotSame(inside, after, "the packet of the window, after it");
        assertNotSame(after, packet(CHUNK), "two packets after the window");
    }

    @Test
    @DisplayName("a packet does not outlive its window: the next window builds the chunk again, even after a window left open")
    void nothingKeptPastAWindow() {
        this.packets.open();
        Object first = packet(CHUNK);
        this.packets.close();
        this.packets.open();
        Object second = packet(CHUNK);
        assertNotSame(first, second, "closed then opened");
        this.packets.open();
        assertNotSame(second, packet(CHUNK), "opened again without a close");
    }

    @Test
    @DisplayName("a chunk that changes inside the window is built again for the next player, the other chunks stay shared")
    void aChangedChunkIsBuiltAgain() {
        this.packets.open();
        Object before = packet(CHUNK);
        Object other = packet(OTHER_CHUNK);

        this.packets.forget(CHUNK);

        Object after = packet(CHUNK);
        assertNotSame(before, after, "the packet built before the change");
        assertSame(after, packet(CHUNK), "the players after the change");
        assertSame(other, packet(OTHER_CHUNK), "a chunk that did not change");
    }

    @Test
    @DisplayName("another thread never shares the packets of a window it did not open, nor drops one")
    void windowBelongsToItsThread() throws InterruptedException {
        this.packets.open();
        Object mine = packet(CHUNK);
        AtomicReference<Object> theirs = new AtomicReference<>();
        AtomicReference<Boolean> openForThem = new AtomicReference<>();
        Thread other = new Thread(() -> {
            openForThem.set(this.packets.isOpen());
            theirs.set(packet(CHUNK));
            this.packets.forget(CHUNK);
        });
        other.start();
        other.join();
        assertFalse(openForThem.get(), "the window is open for a thread that did not open it");
        assertNotSame(mine, theirs.get());
        assertSame(mine, packet(CHUNK), "the window of its own thread was disturbed");
    }
}
