package com.infernalsuite.asp.api.exceptions;

import java.io.IOException;
import java.time.Instant;

/**
 * Thrown when another server holds the lease on a world.
 *
 * <p>BTC-CORE: raised by a {@link com.infernalsuite.asp.api.loaders.LockableSlimeLoader} when a
 * world is read for writing, or saved, while another server holds its lease. It extends
 * {@link IOException} rather than {@link SlimeException} on purpose: {@code readWorld} and
 * {@code saveWorld} already declare {@code IOException}, so every existing caller handles the
 * refusal without a signature change, and the message says what happened.
 *
 * <p>The message names the world and the end of the lease, never the holder's identity.
 */
public class WorldLockedException extends IOException {

    private final String worldName;
    private final Instant lockedUntil;

    public WorldLockedException(String worldName, Instant lockedUntil) {
        super("World " + worldName + " is locked by another server until " + lockedUntil
                + "; it can be loaded for writing once that server unloads it or its lease expires.");
        this.worldName = worldName;
        this.lockedUntil = lockedUntil;
    }

    public String getWorldName() {
        return worldName;
    }

    /** When the current lease ends if its holder stops renewing it (for example after a crash). */
    public Instant getLockedUntil() {
        return lockedUntil;
    }
}
