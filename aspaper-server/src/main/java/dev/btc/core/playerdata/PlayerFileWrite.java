/*
 * Derived from "Nitori: Async playerdata saving" (Gensokyo Reimagined, Nitori; GPL-3.0-only), as
 * carried by Leaf (Dreeam-qwq/Leaf, patch 0149, GPL-3.0). The idea kept: the game thread encodes
 * the player, another thread lands the bytes. Changed for BTC-CORE: per-player order comes from
 * PlayerDataIo instead of a map of futures, the bytes are forced to disk before the rename, and
 * the temporary file never outlives a failed write. Leaf's async level.dat is not ported.
 */
package dev.btc.core.playerdata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Lands one already-encoded player file on disk ({@code async.player-data-save}).
 *
 * <p>The bytes go to a temporary file of the same folder, are forced to disk, and only then take
 * the place of {@code <id>.dat}, the previous one being kept as {@code <id>.dat_old}: the files the
 * server already writes, in the order it already writes them. Stopped anywhere before the rename,
 * the last good file is still there, whole.
 */
public final class PlayerFileWrite {

    private PlayerFileWrite() {}

    /** Puts {@code fresh} in the place of {@code real}, keeping the previous {@code real} as {@code old}. */
    @FunctionalInterface
    public interface Replacer {
        void replace(Path real, Path fresh, Path old) throws IOException;
    }

    public static void write(Path dir, String id, byte[] data, Replacer replacer) throws IOException {
        Path fresh = Files.createTempFile(dir, id + "-", ".dat");
        try {
            try (FileChannel channel = FileChannel.open(fresh, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true); // a power cut after the rename must not find an empty file under the real name
            }
            replacer.replace(dir.resolve(id + ".dat"), fresh, dir.resolve(id + ".dat_old"));
        } finally {
            Files.deleteIfExists(fresh); // gone already when the rename happened
        }
    }
}
