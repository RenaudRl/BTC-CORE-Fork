package dev.btc.core.integrity;

import dev.btc.core.api.integrity.IntegrityAPI.CheckGroup;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionHandle;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionReason;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionScope;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds live exemptions, reference-counted per {@code (player, scope)} and bounded by a TTL.
 *
 * <p>Three invariants are enforced here rather than trusted to callers, because each one corresponds
 * to a failure that anticheat APIs are documented to have shipped:
 *
 * <ol>
 *   <li><b>Ownership.</b> A handle is the only way to release its own contribution. NoCheatPlus warns
 *       in its own source that a shared {@code unexempt(uuid, check)} makes plugins undo each other's
 *       exemptions; there is no such call here.</li>
 *   <li><b>Reference counting.</b> Two extensions relaxing the same scope both have to finish before
 *       the relaxation ends. Otherwise the first one to close blinds the second one's mechanic.</li>
 *   <li><b>Expiry.</b> Every entry carries a deadline. An extension that dies between acquiring and
 *       releasing leaves a hole that closes by itself, rather than one that lasts until restart.</li>
 * </ol>
 *
 * <p>Thread-safety: every structure here is concurrent and nothing touches the world, so this class is
 * safe to consult from a Netty thread, a region thread or the scoring thread alike.
 */
public final class ExemptionRegistry {

    /** Upper bound on any single exemption. A mechanic that needs longer should re-declare. */
    private static final Duration MAX_TTL = Duration.ofMinutes(5);

    /** Identity of a relaxation: one player, one scope. */
    private record Key(UUID player, CheckGroup group, ExemptionReason reason) {}

    /**
     * One holder's contribution.
     *
     * <p>{@code released} makes {@link ExemptionHandle#close()} idempotent without locking: whoever
     * flips it first performs the removal, later calls do nothing.
     */
    private static final class Entry {
        private final Plugin owner;
        private final long deadlineNanos;
        private final AtomicBoolean released = new AtomicBoolean(false);

        Entry(Plugin owner, long deadlineNanos) {
            this.owner = owner;
            this.deadlineNanos = deadlineNanos;
        }

        boolean expired(long nowNanos) {
            return nowNanos - deadlineNanos >= 0;
        }

        boolean live(long nowNanos) {
            return !released.get() && !expired(nowNanos);
        }
    }

    private final Map<Key, CopyOnWriteArrayList<Entry>> entries = new ConcurrentHashMap<>();

    /**
     * Grants an exemption owned by {@code owner}.
     *
     * @throws IllegalArgumentException when the TTL is absent, non-positive, or beyond {@link #MAX_TTL}
     */
    public ExemptionHandle grant(Plugin owner, UUID player, ExemptionScope scope, Duration ttl) {
        if (owner == null) {
            throw new IllegalArgumentException("an exemption must be owned by a plugin");
        }
        if (player == null || scope == null) {
            throw new IllegalArgumentException("an exemption needs a player and a scope");
        }
        requireUsableTtl(ttl);

        Key key = new Key(player, scope.group(), scope.reason());
        Entry entry = new Entry(owner, System.nanoTime() + ttl.toNanos());
        entries.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(entry);
        return new Handle(key, entry);
    }

    /**
     * Grants one exemption spanning several groups, released as a unit.
     *
     * <p>A cinematic drops movement, combat, interaction and position together; requiring four handles
     * to be closed correctly is a leak waiting to happen.
     */
    public ExemptionHandle grantAll(Plugin owner, UUID player, Iterable<CheckGroup> groups,
                                    ExemptionReason reason, Duration ttl) {
        if (groups == null) {
            throw new IllegalArgumentException("a grouped exemption needs at least one group");
        }
        List<ExemptionHandle> handles = new java.util.ArrayList<>();
        for (CheckGroup group : groups) {
            handles.add(grant(owner, player, new ExemptionScope(group, reason), ttl));
        }
        if (handles.isEmpty()) {
            throw new IllegalArgumentException("a grouped exemption needs at least one group");
        }
        return new CompositeHandle(List.copyOf(handles));
    }

    /** Whether this scope is relaxed for this player right now. */
    public boolean isExempted(UUID player, ExemptionScope scope) {
        return activeReason(player, scope.group()) != null
            && isActive(new Key(player, scope.group(), scope.reason()));
    }

    /**
     * The reason currently relaxing this group, or {@code null} when it is armed.
     *
     * <p>Returns a reason rather than a boolean because the engine relaxes a specific part of its
     * evaluation depending on why: a launch widens the vertical tolerance, a cinematic does not.
     */
    public ExemptionReason activeReason(UUID player, CheckGroup group) {
        for (ExemptionReason reason : ExemptionReason.values()) {
            if (isActive(new Key(player, group, reason))) {
                return reason;
            }
        }
        return null;
    }

    /** Convenience for callers that want the reason as an {@link Optional}. */
    public Optional<ExemptionReason> reasonFor(UUID player, CheckGroup group) {
        return Optional.ofNullable(activeReason(player, group));
    }

    /** Drops everything held for a player. Called when they leave; never a public API operation. */
    public void clearPlayer(UUID player) {
        entries.keySet().removeIf(key -> key.player().equals(player));
    }

    /**
     * Drops everything held by a plugin, for use when it is disabled.
     *
     * <p>A disabled plugin cannot close its handles, and its exemptions must not outlive it.
     */
    public void clearOwner(Plugin owner) {
        for (Map.Entry<Key, CopyOnWriteArrayList<Entry>> bucket : entries.entrySet()) {
            bucket.getValue().removeIf(entry -> entry.owner.equals(owner));
            if (bucket.getValue().isEmpty()) {
                entries.remove(bucket.getKey(), bucket.getValue());
            }
        }
    }

    /** Number of live holders for a scope. Exposed for diagnostics and tests, not for decisions. */
    public int holderCount(UUID player, ExemptionScope scope) {
        CopyOnWriteArrayList<Entry> bucket = entries.get(new Key(player, scope.group(), scope.reason()));
        if (bucket == null) {
            return 0;
        }
        long now = System.nanoTime();
        return (int) bucket.stream().filter(entry -> entry.live(now)).count();
    }

    private boolean isActive(Key key) {
        CopyOnWriteArrayList<Entry> bucket = entries.get(key);
        if (bucket == null) {
            return false;
        }
        long now = System.nanoTime();
        // Prune while reading: expiry has no scheduler of its own, so the read path is what makes
        // a leaked handle disappear.
        bucket.removeIf(entry -> entry.released.get() || entry.expired(now));
        if (bucket.isEmpty()) {
            entries.remove(key, bucket);
            return false;
        }
        return true;
    }

    private static void requireUsableTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("an exemption requires a positive TTL");
        }
        if (ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException(
                "an exemption may not exceed " + MAX_TTL.toMinutes() + " minutes; re-declare instead");
        }
    }

    /** Handle over a single entry. */
    private final class Handle implements ExemptionHandle {
        private final Key key;
        private final Entry entry;

        Handle(Key key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        @Override
        public boolean isActive() {
            return entry.live(System.nanoTime());
        }

        @Override
        public Duration remaining() {
            long left = entry.deadlineNanos - System.nanoTime();
            return (entry.released.get() || left <= 0) ? Duration.ZERO : Duration.ofNanos(left);
        }

        @Override
        public void close() {
            if (!entry.released.compareAndSet(false, true)) {
                return;
            }
            CopyOnWriteArrayList<Entry> bucket = entries.get(key);
            if (bucket != null) {
                bucket.remove(entry);
                if (bucket.isEmpty()) {
                    entries.remove(key, bucket);
                }
            }
        }
    }

    /** Handle over several entries released together. */
    private static final class CompositeHandle implements ExemptionHandle {
        private final List<ExemptionHandle> parts;

        CompositeHandle(List<ExemptionHandle> parts) {
            this.parts = parts;
        }

        @Override
        public boolean isActive() {
            return parts.stream().anyMatch(ExemptionHandle::isActive);
        }

        @Override
        public Duration remaining() {
            return parts.stream()
                .map(ExemptionHandle::remaining)
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
        }

        @Override
        public void close() {
            parts.forEach(ExemptionHandle::close);
        }
    }
}
