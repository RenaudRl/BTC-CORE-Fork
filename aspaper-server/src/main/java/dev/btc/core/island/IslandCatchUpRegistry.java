package dev.btc.core.island;

import dev.btc.core.api.island.CatchUpContext;
import dev.btc.core.api.island.CatchUpHandler;
import dev.btc.core.api.island.CatchUpJournal;
import dev.btc.core.api.island.CatchUpRejection;
import dev.btc.core.api.island.CatchUpResult;
import dev.btc.core.api.island.IslandKey;
import dev.btc.core.api.island.IslandLease;
import dev.btc.core.api.island.IslandOwnershipSource;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds the registered catch-up handlers and runs them for one validated window.
 *
 * <p>The registry owns the schedule and the refusals; handlers own the gameplay. Nothing here ticks:
 * a handler runs when an island is activated or a chunk resumes, and at no other time.
 *
 * <p>Refusals are named and logged once per operation rather than per handler. A catch-up that
 * silently does nothing and one that ran and found nothing to do are different situations calling
 * for different fixes, so the two are never reported the same way.
 */
public final class IslandCatchUpRegistry {

    private static final Logger LOGGER = Logger.getLogger("BTCCore/IslandCatchUp");

    /** Ceiling on accounted absence. Beyond this, the window is clamped and the excess is lost. */
    private static final long MAX_ELAPSED_MILLIS = java.util.concurrent.TimeUnit.DAYS.toMillis(7);

    /** Shared per-island operation budget, so one greedy system cannot spend the whole activation. */
    private static final int MAX_OPERATIONS_PER_ACTIVATION = 4096;

    /**
     * Tolerance on a timestamp that sits ahead of the server clock.
     *
     * <p>A few seconds of drift between a backend and the database is normal and must not read as
     * tampering. Anything beyond it is refused outright rather than clamped to zero: clamping would
     * hide a clock problem that will keep producing wrong windows.
     */
    private static final long CLOCK_SKEW_TOLERANCE_MILLIS = 5_000L;

    private static final Map<String, Registration> HANDLERS = new ConcurrentHashMap<>();

    private static volatile IslandOwnershipSource ownershipSource;
    private static volatile CatchUpJournal journal;
    private static volatile String backendId = "unnamed-backend";

    private IslandCatchUpRegistry() {
    }

    private record Registration(Plugin owner, String systemKey, int schemaVersion, CatchUpHandler handler) {
    }

    private record PreparedActivation(IslandOwnershipSource source, Activation activation,
                                      AtomicInteger budget, UUID operationId) {
    }

    // ==================== wiring ====================

    /**
     * Binds the canonical ownership store.
     *
     * <p>Until one is bound, every catch-up is refused with {@link CatchUpRejection#UNKNOWN_WORLD}.
     * That is deliberate: a backend that cannot reach the authority must not advance an island it
     * may not own.
     *
     * @param source the store, or {@code null} to unbind
     */
    public static void bindOwnershipSource(IslandOwnershipSource source) {
        ownershipSource = source;
        LOGGER.info(() -> source == null
            ? "Island ownership source unbound; catch-up is refused until one is bound"
            : "Island ownership source bound: " + source.getClass().getName());
    }

    /**
     * Sets the identifier this backend claims leases under.
     *
     * @param id a stable identifier, unique across the backends sharing the canonical store
     */
    public static void setBackendId(String id) {
        if (id != null && !id.isBlank()) {
            backendId = id;
        }
    }

    public static Optional<IslandOwnershipSource> ownershipSource() {
        return Optional.ofNullable(ownershipSource);
    }

    /**
     * Binds the operation journal.
     *
     * <p>Optional, unlike the ownership source: catch-up runs without one. What is lost is the
     * record of what ran, so a crash mid-activation can no longer be told apart from an activation
     * that never happened. Bind one on any backend sharing an island with another.
     *
     * @param value the journal, or {@code null} to unbind
     */
    public static void bindJournal(CatchUpJournal value) {
        journal = value;
        LOGGER.info(() -> value == null
            ? "Island catch-up journal unbound; operations will not be recorded"
            : "Island catch-up journal bound: " + value.getClass().getName());
    }

    // ==================== registration ====================

    public static void register(Plugin owner, String systemKey, int schemaVersion, CatchUpHandler handler) {
        if (owner == null || handler == null || systemKey == null || systemKey.isBlank()) {
            throw new IllegalArgumentException("owner, systemKey and handler are required");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion starts at 1");
        }
        Registration previous = HANDLERS.put(systemKey, new Registration(owner, systemKey, schemaVersion, handler));
        if (previous != null) {
            LOGGER.info(() -> "Catch-up handler '" + systemKey + "' replaced (was " + previous.owner().getName()
                + ", now " + owner.getName() + ')');
        }
    }

    public static void unregisterAll(Plugin owner) {
        HANDLERS.values().removeIf(registration -> registration.owner().equals(owner));
    }

    // ==================== dispatch ====================

    /**
     * Runs the world-scoped handlers for an island that has just loaded.
     *
     * <p>Call on the global context. Returns the lease and window when the activation was accepted,
     * so the caller can fire {@link dev.btc.core.api.island.IslandActivationEvent}; returns empty
     * when the activation was refused, in which case nothing ran and the reason has been logged.
     *
     * @param world the freshly loaded world
     * @return the accepted activation, or empty when refused
     */
    public static Optional<Activation> activate(World world) {
        IslandOwnershipSource source = ownershipSource;
        if (source == null) {
            refuse(world.getName(), CatchUpRejection.UNKNOWN_WORLD, "no ownership source bound");
            return Optional.empty();
        }
        if (!applicable(true).isEmpty()) {
            LOGGER.info(() -> "Synchronous island activation refused for '" + world.getName()
                + "': chunk-scoped handlers require activateIslandAsync");
            return Optional.empty();
        }

        Optional<IslandKey> resolved = source.resolve(world.getName());
        if (resolved.isEmpty()) {
            refuse(world.getName(), CatchUpRejection.UNKNOWN_WORLD, "no canonical row for this world name");
            return Optional.empty();
        }
        IslandKey island = resolved.get();

        Optional<IslandLease> claimed = source.claim(island, backendId);
        if (claimed.isEmpty()) {
            refuse(world.getName(), CatchUpRejection.ISLAND_NOT_OWNED, "another backend holds a live claim");
            return Optional.empty();
        }
        IslandLease lease = claimed.get();

        long now = System.currentTimeMillis();
        if (lease.isExpired(Instant.ofEpochMilli(now))) {
            source.abandon(island, lease);
            refuse(world.getName(), CatchUpRejection.LEASE_EXPIRED, "lease lapsed before the window opened");
            return Optional.empty();
        }

        long from = source.lastCommittedEpochMillis(island).orElse(now);
        if (from > now + CLOCK_SKEW_TOLERANCE_MILLIS) {
            source.abandon(island, lease);
            refuse(world.getName(), CatchUpRejection.FUTURE_TIMESTAMP,
                "last commit is " + (from - now) + " ms ahead of this backend's clock");
            return Optional.empty();
        }

        long realElapsed = Math.max(0L, now - from);
        boolean clamped = realElapsed > MAX_ELAPSED_MILLIS;
        long bounded = clamped ? MAX_ELAPSED_MILLIS : realElapsed;

        AtomicInteger budget = new AtomicInteger(MAX_OPERATIONS_PER_ACTIVATION);
        UUID operationId = UUID.randomUUID();
        boolean allCommitted = run(island, lease, world, null, from, now, bounded, clamped, budget, operationId, false);

        if (allCommitted) {
            if (!source.commit(island, lease, now)) {
                refuse(world.getName(), CatchUpRejection.STALE_FENCING_TOKEN,
                    "commit refused; a newer backend owns this island");
                return Optional.empty();
            }
        } else {
            // The window stays open on purpose: a handler that could not finish must be replayed
            // from the same `from`, not from a timestamp that pretends the work happened.
            source.abandon(island, lease);
        }
        return Optional.of(new Activation(island, lease, from, now, clamped));
    }

    /**
     * Runs the chunk-scoped handlers for a resumed chunk.
     *
     * <p>Call on the region thread that owns the chunk. Refuses when the chunk is unloaded or
     * outside the island's owned perimeter — a handler is never shown a chunk it does not own.
     *
     * @param island the island the chunk belongs to
     * @param lease  the lease held over the island
     * @param chunk  the resumed chunk
     * @return {@code true} when every chunk-scoped handler committed
     */
    public static boolean resumeChunk(IslandKey island, IslandLease lease, Chunk chunk) {
        IslandOwnershipSource source = ownershipSource;
        if (source == null) {
            refuse(island.worldName(), CatchUpRejection.UNKNOWN_WORLD, "no ownership source bound");
            return false;
        }
        if (!chunk.isLoaded() || !source.ownsChunk(island, chunk.getX(), chunk.getZ())) {
            refuse(island.worldName(), CatchUpRejection.CHUNK_NOT_OWNED,
                "chunk " + chunk.getX() + ',' + chunk.getZ() + " is unloaded or outside the perimeter");
            return false;
        }

        long now = System.currentTimeMillis();
        long from = source.lastCommittedEpochMillis(island).orElse(now);
        long realElapsed = Math.max(0L, now - from);
        boolean clamped = realElapsed > MAX_ELAPSED_MILLIS;

        AtomicInteger budget = new AtomicInteger(MAX_OPERATIONS_PER_ACTIVATION);
        return run(island, lease, chunk.getWorld(), new CatchUpContext.ChunkPosition(chunk.getX(), chunk.getZ()),
            from, now, clamped ? MAX_ELAPSED_MILLIS : realElapsed, clamped, budget, UUID.randomUUID(), true);
    }

    private static boolean run(IslandKey island, IslandLease lease, World world,
                               CatchUpContext.ChunkPosition chunk, long from, long to, long bounded,
                               boolean clamped, AtomicInteger budget, UUID operationId, boolean chunkScope) {
        List<Registration> applicable = applicable(chunkScope);

        boolean allCommitted = true;
        for (Registration registration : applicable) {
            CatchUpContext context = new ImmutableCatchUpContext(
                island, lease, operationId, from, to, bounded, clamped, world, chunk, budget);

            CatchUpResult result;
            try {
                result = registration.handler().apply(context);
            } catch (Throwable throwable) {
                // A handler that threw did not report; treat it as retryable so the window stays
                // open rather than committing on top of an unknown state.
                LOGGER.log(Level.SEVERE, throwable,
                    () -> "Catch-up handler '" + registration.systemKey() + "' threw for " + island);
                journal(registration, island, lease, operationId, from, to,
                    CatchUpResult.Status.FAILED_RETRYABLE, 0);
                allCommitted = false;
                continue;
            }

            if (result == null) {
                LOGGER.warning(() -> "Catch-up handler '" + registration.systemKey() + "' returned null for " + island);
                journal(registration, island, lease, operationId, from, to,
                    CatchUpResult.Status.FAILED_RETRYABLE, 0);
                allCommitted = false;
                continue;
            }

            switch (result.status()) {
                case COMMITTED -> budget.addAndGet(-result.operations());
                case NO_WORK -> {
                }
                case FAILED_RETRYABLE -> {
                    LOGGER.warning(() -> "Catch-up handler '" + registration.systemKey() + "' deferred for "
                        + island + ": " + result.diagnostic());
                    allCommitted = false;
                }
                case REJECTED -> LOGGER.info(() -> "Catch-up handler '" + registration.systemKey() + "' refused for "
                    + island + ": " + result.rejection() + " (" + result.diagnostic() + ')');
            }
            journal(registration, island, lease, operationId, from, to, result.status(), result.operations());
        }
        return allCommitted;
    }

    /**
     * Prepares an activation off the region thread and dispatches each owned chunk to its Folia
     * region. The canonical timestamp is committed only after every scheduled part has completed.
     *
     * <p>The original synchronous activation contract is kept for world-scoped handlers, but it
     * cannot safely invoke a handler that reads or mutates chunks. This path is the one used by
     * chunk-scoped systems such as crops.
     */
    public static CompletionStage<Optional<Activation>> activateAsync(World world) {
        java.util.Objects.requireNonNull(world, "world");
        CompletableFuture<Optional<Activation>> result = new CompletableFuture<>();
        Plugin scheduler = schedulerPlugin();
        if (scheduler == null) {
            refuse(world.getName(), CatchUpRejection.UNKNOWN_WORLD,
                "no enabled catch-up handler can provide a scheduler plugin");
            result.complete(Optional.empty());
            return result;
        }

        Bukkit.getAsyncScheduler().runNow(scheduler, ignored -> {
            try {
                Optional<PreparedActivation> prepared = prepare(world);
                if (prepared.isEmpty()) {
                    result.complete(Optional.empty());
                    return;
                }
                dispatchAsync(prepared.get(), scheduler, result);
            } catch (Throwable throwable) {
                LOGGER.log(Level.SEVERE, throwable,
                    () -> "Island catch-up preparation failed for " + world.getName());
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    /**
     * Resumes exactly one chunk, using the cursor persisted for that chunk.
     *
     * <p>This is intentionally separate from {@link #activateAsync(World)}. A world activation has
     * one dimension-wide cursor and is therefore suitable for world-scoped handlers; a low
     * simulation-distance island needs independent cursors or the first chunk to wake would close
     * the window for every other chunk.
     */
    public static CompletionStage<Boolean> resumeChunkAsync(World world, int chunkX, int chunkZ) {
        java.util.Objects.requireNonNull(world, "world");
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Plugin scheduler = schedulerPlugin();
        if (scheduler == null) {
            refuse(world.getName(), CatchUpRejection.UNKNOWN_WORLD,
                "no enabled catch-up handler can provide a scheduler plugin");
            result.complete(false);
            return result;
        }

        Bukkit.getAsyncScheduler().runNow(scheduler, ignored -> {
            IslandOwnershipSource source = ownershipSource;
            if (source == null) {
                refuse(world.getName(), CatchUpRejection.UNKNOWN_WORLD, "no ownership source bound");
                result.complete(false);
                return;
            }
            if (!source.supportsChunkProgress()) {
                refuse(world.getName(), CatchUpRejection.UNKNOWN_WORLD,
                    "ownership source has no per-chunk progress cursor");
                result.complete(false);
                return;
            }

            Optional<IslandKey> resolved = source.resolve(world.getName());
            if (resolved.isEmpty()) {
                refuse(world.getName(), CatchUpRejection.UNKNOWN_WORLD, "no canonical row for this world name");
                result.complete(false);
                return;
            }
            IslandKey island = resolved.get();
            if (!source.ownsChunk(island, chunkX, chunkZ)) {
                refuse(world.getName(), CatchUpRejection.CHUNK_NOT_OWNED,
                    "chunk " + chunkX + ',' + chunkZ + " is outside the perimeter");
                result.complete(false);
                return;
            }

            Optional<IslandLease> claimed = source.claim(island, backendId);
            if (claimed.isEmpty()) {
                refuse(world.getName(), CatchUpRejection.ISLAND_NOT_OWNED,
                    "another backend holds a live claim");
                result.complete(false);
                return;
            }
            IslandLease lease = claimed.get();
            long now = System.currentTimeMillis();
            if (lease.isExpired(Instant.ofEpochMilli(now))) {
                source.abandon(island, lease);
                refuse(world.getName(), CatchUpRejection.LEASE_EXPIRED,
                    "lease lapsed before the chunk window opened");
                result.complete(false);
                return;
            }

            // Empty per-chunk state is a compatible migration path from the old dimension cursor.
            // Once this chunk commits, its own cursor becomes authoritative.
            long from = source.lastCommittedEpochMillis(island, chunkX, chunkZ)
                .or(() -> source.lastCommittedEpochMillis(island))
                .orElse(now);
            if (from > now + CLOCK_SKEW_TOLERANCE_MILLIS) {
                source.abandon(island, lease);
                refuse(world.getName(), CatchUpRejection.FUTURE_TIMESTAMP,
                    "chunk cursor is " + (from - now) + " ms ahead of this backend's clock");
                result.complete(false);
                return;
            }

            boolean clamped = now - from > MAX_ELAPSED_MILLIS;
            long bounded = clamped ? MAX_ELAPSED_MILLIS : Math.max(0L, now - from);
            AtomicInteger budget = new AtomicInteger(MAX_OPERATIONS_PER_ACTIVATION);
            UUID operationId = UUID.randomUUID();
            Location location = new Location(world, (chunkX << 4) + 8.0, 0.0, (chunkZ << 4) + 8.0);
            try {
                Bukkit.getRegionScheduler().run(scheduler, location, ignoredRegion -> {
                    boolean completed = false;
                    try {
                        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                        if (!chunk.isLoaded() || !source.ownsChunk(island, chunkX, chunkZ)) {
                            refuse(world.getName(), CatchUpRejection.CHUNK_NOT_OWNED,
                                "chunk " + chunkX + ',' + chunkZ + " was not resumable");
                        } else {
                            completed = run(island, lease, world,
                                new CatchUpContext.ChunkPosition(chunkX, chunkZ), from, now, bounded,
                                clamped, budget, operationId, true);
                        }
                    } catch (Throwable throwable) {
                        LOGGER.log(Level.SEVERE, throwable,
                            () -> "Chunk catch-up failed for " + island + " at " + chunkX + ',' + chunkZ);
                    }

                    boolean ran = completed;
                    Bukkit.getAsyncScheduler().runNow(scheduler, ignoredAsync -> {
                        try {
                            if (ran && source.commit(island, lease, chunkX, chunkZ, now)) {
                                result.complete(true);
                            } else {
                                source.abandon(island, lease);
                                result.complete(false);
                            }
                        } catch (Throwable throwable) {
                            source.abandon(island, lease);
                            result.completeExceptionally(throwable);
                        }
                    });
                });
            } catch (Throwable throwable) {
                source.abandon(island, lease);
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    private static Optional<PreparedActivation> prepare(World world) {
        IslandOwnershipSource source = ownershipSource;
        if (source == null) {
            refuse(world.getName(), CatchUpRejection.UNKNOWN_WORLD, "no ownership source bound");
            return Optional.empty();
        }

        Optional<IslandKey> resolved = source.resolve(world.getName());
        if (resolved.isEmpty()) {
            refuse(world.getName(), CatchUpRejection.UNKNOWN_WORLD, "no canonical row for this world name");
            return Optional.empty();
        }
        IslandKey island = resolved.get();
        Optional<IslandLease> claimed = source.claim(island, backendId);
        if (claimed.isEmpty()) {
            refuse(world.getName(), CatchUpRejection.ISLAND_NOT_OWNED, "another backend holds a live claim");
            return Optional.empty();
        }
        IslandLease lease = claimed.get();
        long now = System.currentTimeMillis();
        if (lease.isExpired(Instant.ofEpochMilli(now))) {
            source.abandon(island, lease);
            refuse(world.getName(), CatchUpRejection.LEASE_EXPIRED, "lease lapsed before the window opened");
            return Optional.empty();
        }
        long from = source.lastCommittedEpochMillis(island).orElse(now);
        if (from > now + CLOCK_SKEW_TOLERANCE_MILLIS) {
            source.abandon(island, lease);
            refuse(world.getName(), CatchUpRejection.FUTURE_TIMESTAMP,
                "last commit is " + (from - now) + " ms ahead of this backend's clock");
            return Optional.empty();
        }
        long realElapsed = Math.max(0L, now - from);
        boolean clamped = realElapsed > MAX_ELAPSED_MILLIS;
        Activation activation = new Activation(island, lease, from, now, clamped);
        return Optional.of(new PreparedActivation(source, activation,
            new AtomicInteger(MAX_OPERATIONS_PER_ACTIVATION), UUID.randomUUID()));
    }

    private static void dispatchAsync(PreparedActivation prepared, Plugin scheduler,
                                      CompletableFuture<Optional<Activation>> result) {
        Activation activation = prepared.activation();
        IslandOwnershipSource source = prepared.source();
        List<Registration> worldHandlers = applicable(false);
        List<Registration> chunkHandlers = applicable(true);

        Collection<CatchUpContext.ChunkPosition> positions;
        try {
            positions = chunkHandlers.isEmpty() ? List.of() : source.ownedChunks(activation.island());
        } catch (Throwable throwable) {
            source.abandon(activation.island(), activation.lease());
            result.completeExceptionally(throwable);
            return;
        }

        if (!chunkHandlers.isEmpty() && positions.isEmpty()) {
            source.abandon(activation.island(), activation.lease());
            refuse(activation.island().worldName(), CatchUpRejection.UNKNOWN_WORLD,
                "ownership source exposed no perimeter for chunk-scoped handlers");
            result.complete(Optional.empty());
            return;
        }

        int parts = positions.size() + (worldHandlers.isEmpty() ? 0 : 1);
        if (parts == 0) {
            finishAsync(prepared, scheduler, result, new AtomicBoolean(true), new AtomicInteger(0));
            return;
        }

        AtomicBoolean allCommitted = new AtomicBoolean(true);
        AtomicInteger pending = new AtomicInteger(parts);
        Runnable partDone = () -> finishPart(prepared, scheduler, result, allCommitted, pending);

        if (!worldHandlers.isEmpty()) {
            try {
                Bukkit.getGlobalRegionScheduler().run(scheduler, ignored -> {
                    try {
                        if (!run(activation.island(), activation.lease(),
                            Bukkit.getWorld(activation.island().worldName()), null,
                            activation.from(), activation.to(),
                            boundedElapsed(activation), activation.clamped(),
                            prepared.budget(), prepared.operationId(), false)) {
                            allCommitted.set(false);
                        }
                    } catch (Throwable throwable) {
                        allCommitted.set(false);
                        LOGGER.log(Level.SEVERE, throwable,
                            () -> "World-scoped catch-up failed for " + activation.island());
                    } finally {
                        partDone.run();
                    }
                });
            } catch (Throwable throwable) {
                allCommitted.set(false);
                partDone.run();
            }
        }

        for (CatchUpContext.ChunkPosition position : positions) {
            Location location = new Location(Bukkit.getWorld(activation.island().worldName()),
                (position.x() << 4) + 8.0, 0.0, (position.z() << 4) + 8.0);
            try {
                Bukkit.getRegionScheduler().run(scheduler, location, ignored -> {
                    try {
                        World targetWorld = location.getWorld();
                        Chunk chunk = targetWorld.getChunkAt(position.x(), position.z());
                        if (!chunk.isLoaded() || !source.ownsChunk(activation.island(), position.x(), position.z())) {
                            allCommitted.set(false);
                            refuse(activation.island().worldName(), CatchUpRejection.CHUNK_NOT_OWNED,
                                "chunk " + position.x() + ',' + position.z() + " was not resumable");
                        } else if (!run(activation.island(), activation.lease(), targetWorld,
                            position, activation.from(), activation.to(), boundedElapsed(activation),
                            activation.clamped(), prepared.budget(),
                            chunkOperationId(prepared.operationId(), position), true)) {
                            allCommitted.set(false);
                        }
                    } catch (Throwable throwable) {
                        allCommitted.set(false);
                        LOGGER.log(Level.SEVERE, throwable,
                            () -> "Chunk catch-up failed for " + activation.island()
                                + " at " + position.x() + ',' + position.z());
                    } finally {
                        partDone.run();
                    }
                });
            } catch (Throwable throwable) {
                allCommitted.set(false);
                partDone.run();
            }
        }
    }

    private static long boundedElapsed(Activation activation) {
        return activation.clamped()
            ? MAX_ELAPSED_MILLIS
            : Math.max(0L, activation.to() - activation.from());
    }

    private static UUID chunkOperationId(UUID activationOperationId,
                                         CatchUpContext.ChunkPosition position) {
        String seed = activationOperationId + ":" + position.x() + ":" + position.z();
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static void finishPart(PreparedActivation prepared, Plugin scheduler,
                                   CompletableFuture<Optional<Activation>> result,
                                   AtomicBoolean allCommitted, AtomicInteger pending) {
        if (pending.decrementAndGet() == 0) {
            finishAsync(prepared, scheduler, result, allCommitted, pending);
        }
    }

    private static void finishAsync(PreparedActivation prepared, Plugin scheduler,
                                    CompletableFuture<Optional<Activation>> result,
                                    AtomicBoolean allCommitted, AtomicInteger ignoredPending) {
        Bukkit.getAsyncScheduler().runNow(scheduler, ignored -> {
            try {
                IslandOwnershipSource source = prepared.source();
                Activation activation = prepared.activation();
                if (allCommitted.get() && source.commit(activation.island(), activation.lease(), activation.to())) {
                    result.complete(Optional.of(activation));
                } else {
                    source.abandon(activation.island(), activation.lease());
                    result.complete(Optional.empty());
                }
            } catch (Throwable throwable) {
                prepared.source().abandon(prepared.activation().island(), prepared.activation().lease());
                result.completeExceptionally(throwable);
            }
        });
    }

    private static List<Registration> applicable(boolean chunkScope) {
        List<Registration> applicable = new ArrayList<>();
        for (Registration registration : HANDLERS.values()) {
            if (!registration.owner().isEnabled()) continue;
            if (chunkScope != registration.handler().wantsChunkScope()) continue;
            applicable.add(registration);
        }
        return applicable;
    }

    private static Plugin schedulerPlugin() {
        return HANDLERS.values().stream()
            .map(Registration::owner)
            .filter(Plugin::isEnabled)
            .findFirst()
            .orElseGet(() -> Bukkit.getPluginManager().getPlugin("BTCCore"));
    }

    /**
     * Writes one handler outcome to the journal, if one is bound.
     *
     * <p>A journal failure never fails the catch-up: the work is already done in the world, and
     * refusing to commit over a bookkeeping error would replay it. The loss is logged instead.
     */
    private static void journal(Registration registration, IslandKey island, IslandLease lease,
                                UUID operationId, long from, long to, CatchUpResult.Status status,
                                int operations) {
        CatchUpJournal target = journal;
        if (target == null) {
            return;
        }
        try {
            boolean fresh = target.record(new CatchUpJournal.Entry(operationId, registration.systemKey(),
                island, from, to, backendId, lease.fencingToken(), status, registration.schemaVersion(),
                operations, null));
            if (!fresh) {
                LOGGER.info(() -> "Catch-up operation " + operationId + '/' + registration.systemKey()
                    + " was already journalled; treating as a replay");
            }
        } catch (Throwable throwable) {
            LOGGER.log(Level.WARNING, throwable,
                () -> "Could not journal catch-up operation " + operationId + '/' + registration.systemKey());
        }
    }

    private static void refuse(String worldName, CatchUpRejection rejection, String detail) {
        LOGGER.info(() -> "Island catch-up refused for '" + worldName + "': " + rejection + " — " + detail);
    }

    /**
     * An accepted activation, ready to be published as an event.
     *
     * @param island  the island
     * @param lease   the lease taken for it
     * @param from    the window start, in epoch milliseconds
     * @param to      the window end, in epoch milliseconds
     * @param clamped whether real absence exceeded the ceiling
     */
    public record Activation(IslandKey island, IslandLease lease, long from, long to, boolean clamped) {
    }

    private record ImmutableCatchUpContext(IslandKey island, IslandLease lease, UUID operationId,
                                           long fromEpochMillis, long toEpochMillis, long boundedElapsedMillis,
                                           boolean clamped, World world, CatchUpContext.ChunkPosition chunkPosition,
                                           AtomicInteger budget) implements CatchUpContext {

        @Override
        public Optional<CatchUpContext.ChunkPosition> chunk() {
            return Optional.ofNullable(chunkPosition);
        }

        @Override
        public int remainingOperationBudget() {
            return Math.max(0, budget.get());
        }
    }
}
