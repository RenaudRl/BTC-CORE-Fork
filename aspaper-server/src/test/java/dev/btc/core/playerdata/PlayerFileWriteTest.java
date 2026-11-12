package dev.btc.core.playerdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerFileWriteTest {

    private static final int SAVES = 50;
    private static final byte[] GOOD = "the last good save".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEXT = "the save that never landed, long enough to be cut".getBytes(StandardCharsets.UTF_8);

    /** What the server's own replace does: the real file becomes the old one, the fresh one becomes the real one. */
    private static final PlayerFileWrite.Replacer RENAME = (real, fresh, old) -> {
        if (Files.exists(real)) {
            Files.move(real, old, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(fresh, real);
    };

    @TempDir
    Path dir;

    @Test
    @DisplayName("saves of one player land in the order they were made: the file is the last one, the old file the one before")
    void savesOfOnePlayerLandInOrder() throws IOException {
        PlayerDataIo io = new PlayerDataIo();
        UUID player = UUID.randomUUID();

        for (int save = 0; save < SAVES; save++) {
            queue(io, player, save);
        }

        assertTrue(io.shutdown(30, TimeUnit.SECONDS));
        assertArrayEquals(bytes(SAVES - 1), Files.readAllBytes(dir.resolve(player + ".dat")));
        assertArrayEquals(bytes(SAVES - 2), Files.readAllBytes(dir.resolve(player + ".dat_old")));
    }

    @Test
    @DisplayName("server stop: every save still queued is on disk once shutdown returns")
    void shutdownLandsEveryQueuedSave() throws IOException {
        PlayerDataIo io = new PlayerDataIo();
        List<UUID> players = Stream.generate(UUID::randomUUID).limit(40).toList();
        for (int save = 0; save < 3; save++) {
            for (UUID player : players) {
                queue(io, player, save);
            }
        }

        assertTrue(io.shutdown(30, TimeUnit.SECONDS));

        for (UUID player : players) {
            assertArrayEquals(bytes(2), Files.readAllBytes(dir.resolve(player + ".dat")));
        }
        assertEquals(players.size() * 2L, filesIn(dir), "one .dat and one .dat_old per player, no temporary file left");
    }

    @Test
    @DisplayName("witness: a write cut short leaves the last good file whole, and no truncated file")
    void aWriteCutShortLeavesTheLastGoodFileWhole() throws IOException {
        Files.write(dir.resolve("p.dat"), GOOD);

        Thread.currentThread().interrupt(); // the channel closes under the write, as when the thread is stopped mid-save
        try {
            assertThrows(IOException.class, () -> PlayerFileWrite.write(dir, "p", NEXT, RENAME));
        } finally {
            Thread.interrupted();
        }

        assertArrayEquals(GOOD, Files.readAllBytes(dir.resolve("p.dat")));
        assertEquals(1, filesIn(dir));
    }

    @Test
    @DisplayName("witness: stopped between the write and the rename, the last good file is still the file")
    void stoppedBeforeTheRenameLeavesTheLastGoodFileWhole() throws IOException {
        Files.write(dir.resolve("p.dat"), GOOD);

        assertThrows(IOException.class, () -> PlayerFileWrite.write(dir, "p", NEXT, (real, fresh, old) -> {
            assertArrayEquals(NEXT, Files.readAllBytes(fresh), "the new file is whole before it replaces anything");
            throw new IOException("stopped here");
        }));

        assertArrayEquals(GOOD, Files.readAllBytes(dir.resolve("p.dat")));
        assertEquals(1, filesIn(dir));
    }

    private void queue(PlayerDataIo io, UUID player, int save) {
        io.submit(player, () -> {
            try {
                PlayerFileWrite.write(dir, player.toString(), bytes(save), RENAME);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    private static byte[] bytes(int save) {
        return ("save " + save + " " + "x".repeat(save % 7)).getBytes(StandardCharsets.UTF_8);
    }

    private static long filesIn(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.count();
        }
    }
}
