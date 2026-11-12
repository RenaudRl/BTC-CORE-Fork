package dev.btc.core.parallel;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * What becomes of a world whose tick threw ({@code world.quarantine}): taken out of the server, or
 * the server stops as it always did.
 *
 * <p>Only a Slime world other than the default world can be taken out: the default world cannot be
 * unloaded and is where the players of a quarantined world go. A failure the JVM may not survive
 * (out of memory, internal error) stops the server, and so does one quarantine too many within a
 * minute - that many worlds failing is a fault of the server, typically a plugin, not of a world.
 *
 * <p>Not thread-safe: decisions are taken on the main thread, after the worlds have ticked.
 */
public final class WorldQuarantine {

    public enum Verdict {
        /** Players out, one save, unload. */
        SAVE_AND_UNLOAD,
        /** Players out, unload; the world comes back as it was last saved. */
        UNLOAD_ONLY,
        /** Not a case for quarantine: the server stops with a crash report. */
        CRASH
    }

    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final int maxPerMinute;
    private final LongSupplier nanoClock;
    private final ArrayDeque<Long> recent = new ArrayDeque<>();

    /**
     * @param maxPerMinute quarantines allowed within any one minute; the next failure stops the server
     * @param nanoClock    a monotonic clock, in nanoseconds
     */
    public WorldQuarantine(int maxPerMinute, LongSupplier nanoClock) {
        this.maxPerMinute = maxPerMinute;
        this.nanoClock = nanoClock;
    }

    /**
     * @param enabled          {@code world.quarantine.enabled}
     * @param slime            the world is a Slime world
     * @param defaultWorld     the world is the server's default world
     * @param saveBeforeUnload {@code world.quarantine.save-before-unload}
     * @param failure          what the world's tick threw
     */
    public Verdict decide(boolean enabled, boolean slime, boolean defaultWorld, boolean saveBeforeUnload, Throwable failure) {
        if (!enabled || !slime || defaultWorld) return Verdict.CRASH;

        boolean error = false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>()); // a cause chain may loop
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof VirtualMachineError && !(cause instanceof StackOverflowError)) return Verdict.CRASH;
            error |= cause instanceof Error;
        }

        long now = this.nanoClock.getAsLong();
        while (!this.recent.isEmpty() && now - this.recent.peekFirst() >= WINDOW_NANOS) this.recent.removeFirst();
        if (this.recent.size() >= this.maxPerMinute) return Verdict.CRASH;
        this.recent.addLast(now);

        // An Error (a stack overflow) may have left the world's structures half written: not saved.
        return error || !saveBeforeUnload ? Verdict.UNLOAD_ONLY : Verdict.SAVE_AND_UNLOAD;
    }
}
