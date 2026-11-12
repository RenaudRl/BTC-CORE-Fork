package dev.btc.core.parallel;

import java.util.function.Supplier;

/**
 * Who may create, register, unload or close a world under {@code parallel-world-ticking}: the main
 * thread, and nobody else.
 *
 * <p>While the worlds tick the main thread is parked, so whatever it runs, it runs between two
 * phases: no world is being ticked when the set of worlds changes. A world thread that loaded or
 * unloaded a world would change that set under the other threads, and close a world that is in the
 * middle of its tick. It is refused at once, with the scheduler to go through instead - not queued:
 * a load that "succeeded" but has not happened yet would break its caller.
 *
 * <p>With the option off the gate lets everything through, as before.
 */
public final class WorldLifecycleGate {

    /** The kind of thread asking, worked out by the server from its thread classes. */
    public enum Caller {
        /** A tick thread that is not the thread of a world: the server thread, the watchdog. */
        MAIN,
        /** The thread of a world. */
        WORLD_THREAD,
        /** Any other thread. */
        OTHER
    }

    private WorldLifecycleGate() {
    }

    /**
     * @param operation what is asked, for the message: "unload world dungeon_1"
     * @throws IllegalStateException if {@code pwtEnabled} and the caller is not the main thread
     */
    public static void check(boolean pwtEnabled, Caller caller, String operation) {
        if (!pwtEnabled || caller == Caller.MAIN) return;
        throw new IllegalStateException("Cannot " + operation + " from "
            + (caller == Caller.WORLD_THREAD ? "the thread of a world" : "a thread that is not the main thread")
            + " (" + Thread.currentThread().getName() + "): under parallel world ticking a world is loaded and unloaded "
            + "on the main thread only. Run it from Bukkit.getGlobalRegionScheduler().execute(plugin, task)");
    }

    /** {@link #check}, then the action on the calling thread: no queue, the caller gets its result at once. */
    public static <T> T run(boolean pwtEnabled, Caller caller, String operation, Supplier<T> action) {
        check(pwtEnabled, caller, operation);
        return action.get();
    }
}
