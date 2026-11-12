package dev.btc.core.playerdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStampTest {

    @TempDir
    Path dir;

    @Test
    void anUntouchedFileKeepsItsStamp() throws IOException {
        Path file = Files.write(dir.resolve("a.dat"), new byte[] {1, 2, 3});

        FileStamp stamp = FileStamp.of(file);

        assertNotNull(stamp);
        assertTrue(stamp.stillDescribes(file));
    }

    @Test
    void aMissingFileHasNoStamp() throws IOException {
        assertNull(FileStamp.of(dir.resolve("absent.dat")));
        assertNull(FileStamp.of(Files.createDirectory(dir.resolve("folder.dat"))));
    }

    @Test
    void aSaveOfTheSameSizeIsSeen() throws IOException {
        Path file = Files.write(dir.resolve("a.dat"), new byte[] {1, 2, 3});
        FileTime written = Files.getLastModifiedTime(file);
        FileStamp stamp = FileStamp.of(file);

        // What the server does: a new file renamed over the old one, here one second later.
        Path next = Files.write(dir.resolve("a-tmp.dat"), new byte[] {4, 5, 6});
        Files.setLastModifiedTime(next, FileTime.fromMillis(written.toMillis() + 1000));
        Files.move(next, file, StandardCopyOption.REPLACE_EXISTING);

        assertFalse(stamp.stillDescribes(file));
    }

    @Test
    void aSaveInTheSameInstantIsSeenByItsSize() throws IOException {
        Path file = Files.write(dir.resolve("a.dat"), new byte[] {1, 2, 3});
        FileTime written = Files.getLastModifiedTime(file);
        FileStamp stamp = FileStamp.of(file);

        Files.write(file, new byte[] {1, 2, 3, 4});
        Files.setLastModifiedTime(file, written);

        assertFalse(stamp.stillDescribes(file));
    }

    @Test
    void aDeletedFileIsSeen() throws IOException {
        Path file = Files.write(dir.resolve("a.dat"), new byte[] {1, 2, 3});
        FileStamp stamp = FileStamp.of(file);

        Files.delete(file);

        assertFalse(stamp.stillDescribes(file));
    }
}
