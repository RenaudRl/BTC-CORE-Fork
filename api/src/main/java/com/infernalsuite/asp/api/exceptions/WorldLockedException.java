package com.infernalsuite.asp.api.exceptions;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;

/**
 * Thrown when a world may not be written by this server because this server does not hold its
 * lease.
 *
 * <p>BTC-CORE (AD-096): raised by a {@link com.infernalsuite.asp.api.loaders.LockableSlimeLoader}
 * when a world is read for writing while another server holds its lease, and whenever a save or a
 * delete is attempted without the lease — including after the lease was lost. It extends
 * {@link IOException} rather than {@link SlimeException} on purpose: {@code readWorld} and
 * {@code saveWorld} already declare {@code IOException}, so every existing caller handles the
 * refusal without a signature change, and the message says what happened.
 *
 * <p>The message names the world (and the end of the lease when known), never the holder's
 * identity.
 */
public class WorldLockedException extends IOException {

    private final String worldName;
    private final @Nullable Instant lockedUntil;

    /** Another server holds the lease on {@code worldName} until {@code lockedUntil}. */
    public WorldLockedException(String worldName, Instant lockedUntil) {
        super("World " + worldName + " is locked by another server until " + lockedUntil
                + "; it can be loaded for writing once that server unloads it or its lease expires.");
        this.worldName = worldName;
        this.lockedUntil = lockedUntil;
    }

    /** This server does not hold the lease on {@code worldName}: never taken, released, or lost. */
    public WorldLockedException(String worldName) {
        super("This server does not hold the lease on world " + worldName
                + "; the write is refused so that it cannot overwrite a newer holder's data.");
        this.worldName = worldName;
        this.lockedUntil = null;
    }

    /** This server may not write {@code worldName}, for the {@code reason} given (it names the world). */
    public WorldLockedException(String worldName, String reason) {
        super(reason);
        this.worldName = worldName;
        this.lockedUntil = null;
    }

    public String getWorldName() {
        return worldName;
    }

    /**
     * When the current lease ends if its holder stops renewing it (for example after a crash), or
     * {@code null} when the refusal is that this server itself holds no lease.
     */
    public @Nullable Instant getLockedUntil() {
        return lockedUntil;
    }
}
