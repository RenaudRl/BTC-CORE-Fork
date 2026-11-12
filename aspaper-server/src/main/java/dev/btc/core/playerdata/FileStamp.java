package dev.btc.core.playerdata;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * What tells a file apart from a later version of itself, without reading it.
 *
 * <p>The server never edits a player file in place: it writes a new one and renames it over the
 * old one, so a save always shows as a new modification time (and a new file key where the file
 * system has one).
 */
public record FileStamp(FileTime modified, long size, @Nullable Object key) {

    /** @return null when there is no such regular file */
    public static @Nullable FileStamp of(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return attributes.isRegularFile()
                ? new FileStamp(attributes.lastModifiedTime(), attributes.size(), attributes.fileKey())
                : null;
        } catch (IOException absent) {
            return null;
        }
    }

    /** @return true while {@code file} is still the version this stamp was taken from */
    public boolean stillDescribes(Path file) {
        return this.equals(of(file));
    }
}
