package dev.btc.core.parallel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * One tick of every world: in order on the calling thread, or each world on its own thread behind a
 * barrier ({@code parallel-world-ticking}).
 *
 * <p>In parallel mode the caller takes a permit before it hands a world to that world's thread, and
 * the world gives it back when its tick ends; {@link #tickAll} returns once every permit is back.
 * The caller is parked for the whole phase: it runs no task of its own, which is what lets code
 * deferred to the main thread touch any world afterwards.
 *
 * <p>A tick that throws does not stop the others: every world is waited for, and the failures are
 * handed back for the caller to decide on, on its own thread.
 *
 * <p>Scheduling ported from DivineMC "Parallel world ticking" (NONPLAYT, BX Team, GPL-3.0), itself
 * from SparklyPaper (SparklyPower). Differences: the executors and the permits live here instead of
 * in {@code ServerLevel} and {@code DedicatedServer}, and the barrier collects every failure where
 * DivineMC rethrows the first one without waiting for the rest.
 *
 * @param <W> the world type; {@code ServerLevel} on the server, anything in a test
 */
public final class WorldTickCoordinator<W> {

    private final boolean parallel;
    private final int permits;
    private final Semaphore ticking;
    private final BiFunction<W, Runnable, Thread> threads;
    private final Map<W, ExecutorService> executors = new ConcurrentHashMap<>();
    private final Set<W> suspended = ConcurrentHashMap.newKeySet();
    /** The worlds released since the current phase began: gone, not failed. Emptied when the next phase begins. */
    private final Set<W> released = ConcurrentHashMap.newKeySet();
    /** False until the first phase: with the options off none ever runs, and nothing would ever empty {@link #released}. */
    private volatile boolean phaseBegun;

    /**
     * @param parallel false = every tick runs on the calling thread, in order, and no thread is created
     * @param permits  how many worlds may tick at the same time
     * @param threads  builds the thread of a world; called once per world, when it first ticks
     */
    public WorldTickCoordinator(boolean parallel, int permits, BiFunction<W, Runnable, Thread> threads) {
        if (permits < 1) throw new IllegalArgumentException("permits must be at least 1, got " + permits);
        this.parallel = parallel;
        this.permits = permits;
        this.ticking = new Semaphore(permits);
        this.threads = threads;
    }

    /**
     * Ticks every world once and returns when all of them are done.
     *
     * <p>The worlds are read once, up front: one added while the phase runs is first ticked by the
     * next call. {@linkplain #suspend Suspended} worlds are left out, and so is a world
     * {@linkplain #release released} before its turn came.
     *
     * @return the worlds whose tick threw, with what they threw, in the order given; empty when none
     *         did. A world released while it ticked may be among them: see {@link #isReleased}
     */
    public Map<W, Throwable> tickAll(Iterable<? extends W> worlds, Consumer<? super W> tick) {
        this.phaseBegun = true;
        this.released.clear();
        List<W> snapshot = new ArrayList<>();
        for (W world : worlds) {
            if (!this.suspended.contains(world)) snapshot.add(world);
        }
        Throwable[] failures = new Throwable[snapshot.size()];

        try {
            for (int i = 0; i < failures.length; i++) {
                W world = snapshot.get(i);
                if (!this.parallel) {
                    failures[i] = attempt(world, tick);
                    continue;
                }
                int slot = i;
                this.ticking.acquireUninterruptibly();
                boolean handedOver = false;
                try {
                    ExecutorService executor = executorOf(world);
                    if (executor != null) {
                        executor.execute(() -> {
                            try {
                                failures[slot] = attempt(world, tick);
                            } finally {
                                this.ticking.release();
                            }
                        });
                        handedOver = true;
                    }
                } catch (Throwable notHandedOver) {
                    // The world was never given to its thread. Released meanwhile (its executor is
                    // shut down): it is gone, not failed. Otherwise no thread could be created for
                    // it (an Error then: out of memory) and the failure is that world's.
                    if (!this.released.contains(world)) failures[slot] = notHandedOver;
                } finally {
                    if (!handedOver) this.ticking.release(); // its permit goes back, the others still tick
                }
            }
        } finally {
            if (this.parallel) {
                // The barrier: all permits back means no world is still ticking. Uninterruptible,
                // and waited for whatever went wrong above - returning early would let the caller
                // run beside the world threads.
                this.ticking.acquireUninterruptibly(this.permits);
                this.ticking.release(this.permits);
            }
        }

        Map<W, Throwable> failed = new LinkedHashMap<>();
        for (int i = 0; i < failures.length; i++) {
            if (failures[i] != null) failed.put(snapshot.get(i), failures[i]);
        }
        return failed;
    }

    /**
     * Leaves a world out of every tick from now on, until it is {@linkplain #release released}: a
     * world whose tick threw and that is on its way out of the server (quarantine).
     */
    public void suspend(W world) {
        if (!this.released.contains(world)) this.suspended.add(world); // already gone: nothing to leave out, and nothing must keep it
    }

    /** Stops the thread of a world that left the server. Its running tick, if any, finishes first. */
    public void release(W world) {
        if (this.phaseBegun) this.released.add(world); // first: from here on no thread is created for it
        this.suspended.remove(world);
        ExecutorService executor = this.executors.remove(world);
        if (executor != null) executor.shutdown();
    }

    /** Stops every world thread; for the end of the server's stop sequence. */
    public void shutdown() {
        this.executors.values().forEach(ExecutorService::shutdown);
        this.executors.clear();
        this.suspended.clear();
        this.released.clear();
    }

    /**
     * Whether the world was {@linkplain #release released} since the last phase began - by another
     * world's thread while that phase ran, or since it ended. What such a world threw is the end of
     * a world that was leaving, not a failing tick.
     */
    public boolean isReleased(W world) {
        return this.released.contains(world);
    }

    /** Whether the world is {@linkplain #suspend suspended}: left out of every tick, on its way out of the server. */
    public boolean isSuspended(W world) {
        return this.suspended.contains(world);
    }

    /** The executor of a world, created when it first ticks; null for a world released during this phase. */
    private ExecutorService executorOf(W world) {
        return this.executors.computeIfAbsent(world, key -> this.released.contains(key)
            ? null // checked under the lock of the key that release() takes to remove: no executor outlives its world
            : Executors.newSingleThreadExecutor(task -> this.threads.apply(key, task)));
    }

    private static <W> Throwable attempt(W world, Consumer<? super W> tick) {
        try {
            tick.accept(world);
            return null;
        } catch (Throwable failure) { // an Error too: it goes back to the caller, which decides
            return failure;
        }
    }
}
