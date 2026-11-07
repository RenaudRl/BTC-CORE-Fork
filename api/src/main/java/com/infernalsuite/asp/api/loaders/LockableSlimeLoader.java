package com.infernalsuite.asp.api.loaders;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.exceptions.WorldLockedException;

import java.io.IOException;
import java.time.Duration;

/**
 * A loader shared by several servers, which leases a world to one of them at a time.
 *
 * <p>BTC-CORE (AD-096). Without it, two servers that load the same world from a shared source
 * both write it back, and the last writer silently wins. A loader that implements this interface
 * gives each world a <em>lease</em>: a holder (this server, identified once per JVM) and an
 * expiry. Nothing has to call these methods by hand —
 * {@link com.infernalsuite.asp.api.AdvancedSlimePaperAPI#readWorld} takes the lease of every world
 * it reads for writing, keeps it renewed while the world is loaded, and releases it after the
 * world is unloaded or when the JVM stops. A loader that does not implement this interface keeps
 * the old behaviour: no lease, last writer wins.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>{@link #acquireLock} is atomic: of two servers racing for a free world, exactly one wins.
 *       It succeeds when the world is free, already held by this server, or held by a lease that
 *       has expired — the last case is what frees a world after its holder crashed.</li>
 *   <li>{@link SlimeLoader#saveWorld} refuses, with {@link WorldLockedException}, to overwrite a
 *       world whose lease another server holds and has not let expire. A server whose lease was
 *       taken over therefore cannot clobber the new holder.</li>
 *   <li>Every method performs blocking I/O: never call one from the main thread or a region
 *       thread.</li>
 * </ul>
 */
public interface LockableSlimeLoader extends SlimeLoader {

    /**
     * Takes, or renews, this server's lease on {@code worldName}.
     *
     * @throws UnknownWorldException if the world does not exist in this source
     * @throws WorldLockedException  if another server holds an unexpired lease on it
     * @throws IOException           if the source could not be reached
     */
    void acquireLock(String worldName) throws UnknownWorldException, WorldLockedException, IOException;

    /**
     * Extends this server's lease on {@code worldName} by a full lease duration.
     *
     * @return {@code false} when this server no longer holds the lease — another server took it
     *         over after it expired, or the world was deleted. Saves will then be refused.
     * @throws IOException if the source could not be reached; the lease is left as it was
     */
    boolean renewLock(String worldName) throws IOException;

    /**
     * Gives up this server's lease on {@code worldName}. Does nothing when this server does not
     * hold it, so it is safe to call after the lease was lost.
     *
     * @throws IOException if the source could not be reached; the lease then simply expires
     */
    void releaseLock(String worldName) throws IOException;

    /**
     * How often a held lease must be renewed. Always strictly shorter than the lease itself, so
     * that one missed renewal does not lose it.
     */
    Duration lockRenewInterval();
}
