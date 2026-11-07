package com.infernalsuite.asp.api.loaders;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.exceptions.WorldAlreadyExistsException;
import com.infernalsuite.asp.api.exceptions.WorldLockedException;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * A loader shared by several servers, which leases a world to one of them at a time.
 *
 * <p>BTC-CORE (AD-096). Without it, two servers that load the same world from a shared source
 * both write it back, and the last writer silently wins. A loader that implements this interface
 * gives each world a <em>lease</em>: a holder (this server, identified once per JVM), an expiry,
 * and a <em>generation</em> — a fencing token drawn anew at every acquisition and never reused.
 * Every write carries the generation it was made under, and the source refuses it unless that
 * generation is still the current one and still held by this server. A server whose lease ran
 * out, was taken over, or was released therefore can never write again under it, whatever
 * happened to the world in between.
 *
 * <p>Nothing has to call these methods by hand —
 * {@link com.infernalsuite.asp.api.AdvancedSlimePaperAPI#readWorld} takes the lease of every world
 * it reads for writing, keeps it renewed while the world is loaded, and releases it after the
 * world's last save once it is unloaded, or when the server stops. A loader that does not
 * implement this interface keeps the old behaviour: no lease, last writer wins.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>{@link #acquireLock} is atomic: of servers racing for a world, exactly one wins. It
 *       succeeds when the world is free, held by this server, or held by a lease that has
 *       expired (what frees a world after its holder crashed), and always draws a new
 *       generation.</li>
 *   <li>{@link #saveWorld(String, byte[], long)}, {@link #renewLocks}, {@link #releaseLock} and
 *       {@link #deleteWorld} are conditioned, in the same statement as the write, on this server
 *       still holding that generation. {@link #renewLocks} never takes a lease back.</li>
 *   <li>{@link SlimeLoader#saveWorld(String, byte[])} always throws {@link WorldLockedException}:
 *       it carries no generation, so it could write a stale copy under a newer lease, or insert a
 *       world deleted meanwhile. Saves go through {@link #saveWorld(String, byte[], long)}, new
 *       worlds through {@link #createWorld}.</li>
 *   <li>{@link #createWorld} inserts a world that does not exist yet, already leased to this
 *       server, in one statement; an existing world is refused with
 *       {@link WorldAlreadyExistsException}.</li>
 *   <li>{@link SlimeLoader#deleteWorld} deletes only a world whose lease this server holds, or
 *       that nobody holds (free or expired); otherwise it throws {@link WorldLockedException}.</li>
 *   <li>Every method but {@link #heldLocks} and {@link #lockRenewInterval} performs blocking I/O:
 *       never call one from the main thread or a region thread.</li>
 * </ul>
 */
public interface LockableSlimeLoader extends SlimeLoader {

    /**
     * Takes this server's lease on {@code worldName}, with a new generation.
     *
     * @return the generation of the new lease, to pass to every write made under it
     * @throws UnknownWorldException if the world does not exist in this source
     * @throws WorldLockedException  if another server holds an unexpired lease on it
     * @throws IOException           if the source could not be reached
     */
    long acquireLock(String worldName) throws UnknownWorldException, WorldLockedException, IOException;

    /**
     * Inserts {@code worldName}, which must not exist yet, leased to this server with a new
     * generation, in one statement.
     *
     * @return the generation of the new lease
     * @throws WorldAlreadyExistsException if a world of that name exists in this source
     * @throws IOException                 if the source could not be reached
     */
    long createWorld(String worldName, byte[] serializedWorld) throws WorldAlreadyExistsException, IOException;

    /**
     * The leases this server holds through this loader, world name to generation, as last known
     * (a lost lease leaves it at the next {@link #renewLocks}). A live, unmodifiable view; no I/O.
     */
    Map<String, Long> heldLocks();

    /**
     * Saves {@code worldName} if this server still holds lease {@code generation} on it.
     *
     * @throws WorldLockedException if it does not — the lease expired and was taken, was released,
     *                              or the world was deleted
     */
    void saveWorld(String worldName, byte[] serializedWorld, long generation) throws IOException;

    /**
     * Extends every lease of {@link #heldLocks}, in one round trip. A lease that is no longer this
     * server's (taken over after it expired, released, deleted) is never taken back: it leaves
     * {@link #heldLocks} and is reported.
     *
     * @return the worlds whose lease was lost
     * @throws IOException if the source could not be reached; the leases are left as they were
     */
    Set<String> renewLocks() throws IOException;

    /**
     * Gives up lease {@code generation} on {@code worldName}. Does nothing when this server no
     * longer holds that generation, so it never releases a newer lease.
     *
     * @throws IOException if the source could not be reached; the lease then simply expires
     */
    void releaseLock(String worldName, long generation) throws IOException;

    /**
     * Gives up every lease this server holds in this source, in one statement. Meant for the end
     * of a clean stop, after the final saves.
     *
     * @throws IOException if the source could not be reached; the leases then simply expire
     */
    void releaseAllLocks() throws IOException;

    /**
     * How often a held lease must be renewed. At most half the lease itself, so that one missed
     * renewal does not lose it.
     */
    Duration lockRenewInterval();

    /**
     * How long a lease lasts without renewal. A server that could not renew its leases for that
     * long must consider them lost: another server may take them from then on.
     */
    Duration lockLease();
}
