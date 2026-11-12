package dev.btc.core.parallel;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * What the thread of a world may not do itself under {@code parallel-world-ticking}, because it
 * reaches into another world: a teleport to it, a portal, the waypoints of every world. It is put
 * here, and the main thread runs it once every world has ticked - the same server tick, when no
 * world thread is running any more.
 *
 * <p>Nothing here waits: a world thread that waited for another world would wait for a thread that
 * may be waiting for it.
 */
public final class CrossWorldQueue {

    /** What to do with a request that reaches into several worlds. */
    public enum Route {
        /** Run it now, on the calling thread. */
        INLINE,
        /** Hand it to the main thread, which runs it once every world has ticked. */
        DEFERRED
    }

    private final Queue<Runnable> deferred = new ConcurrentLinkedQueue<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * @param parallel    {@code parallel-world-ticking} is on
     * @param worldThread the caller is the thread of a world
     * @param ownsAll     the caller is ticking every world the request touches
     */
    public static Route route(boolean parallel, boolean worldThread, boolean ownsAll) {
        // The main thread owns every world; a thread that ticks nothing is left to the checks it always had.
        return parallel && worldThread && !ownsAll ? Route.DEFERRED : Route.INLINE;
    }

    /**
     * Why a journey asked earlier in the tick is not made after all, null when it still can be.
     *
     * @param travellerGone   the entity was removed meanwhile: it died, left the server, already travelled
     * @param targetUnloaded  the target world is no longer on the server
     * @param targetSuspended the target world no longer ticks: it is quarantined, on its way out
     */
    public static String staleReason(boolean travellerGone, boolean targetUnloaded, boolean targetSuspended) {
        if (travellerGone) return "the entity is gone";
        if (targetUnloaded) return "the target world was unloaded";
        if (targetSuspended) return "the target world is quarantined";
        return null;
    }

    /** Any thread. */
    public void defer(Runnable task) {
        this.deferred.add(task);
    }

    /**
     * Runs everything deferred so far, in the order asked, on the calling thread - the main one,
     * after the barrier. What a task defers in turn runs in the same call.
     *
     * @param onFailure told what a task threw; the tasks after it still run. An {@link Error} is not
     *                  caught: it stops the server, as it would have where the task was asked
     * @return how many tasks ran
     */
    public int drain(Consumer<? super Exception> onFailure) {
        int ran = 0;
        for (Runnable task; (task = this.deferred.poll()) != null; ran++) {
            try {
                task.run();
            } catch (Exception failure) {
                onFailure.accept(failure);
            }
        }
        return ran;
    }

    /** True the first time a call site is seen: one warning per site, however often it runs. */
    public boolean firstTime(String site) {
        return this.warned.add(site);
    }
}
