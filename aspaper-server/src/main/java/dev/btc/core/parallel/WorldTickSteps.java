package dev.btc.core.parallel;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * What surrounds the tick of one world, and what becomes of the worlds that threw: the part of
 * {@code WorldTickLoop} that needs no server.
 *
 * <p>Only what the tick of a world itself throws is a failing tick, which {@code world.quarantine}
 * may take out of the server. Anything thrown around it - the preparation of the tick, the tick
 * events, a world that could not be handed to its thread - stops the server as it always did.
 */
public final class WorldTickSteps {

    private WorldTickSteps() {
    }

    /** Carries what the tick of a world itself threw, apart from what is thrown around it. */
    private static final class TickFailure extends RuntimeException {
        TickFailure(Throwable cause) {
            super(null, cause, false, false); // a carrier: no message, no stack trace of its own
        }
    }

    /**
     * The turn of one world: {@code before}, its tick, {@code after}. Nothing runs after a step
     * that throws.
     */
    public static void run(Runnable before, Runnable tick, Runnable after) {
        before.run();
        try {
            tick.run();
        } catch (Throwable failure) {
            throw new TickFailure(failure);
        }
        after.run();
    }

    /**
     * Decides on what the worlds of one phase threw, once all of them are done.
     *
     * @param failures     what {@link WorldTickCoordinator#tickAll} handed back, each turn run by {@link #run}
     * @param released     whether a world left the server during the phase: what it threw is then neither
     *                     a crash nor a failing tick
     * @param afterRelease told what a released world threw
     * @param failingTick  deals with a world whose tick threw: null once the world is taken care of
     *                     (quarantined), else the crash that stops the server
     * @throws RuntimeException the first crash, with every other one {@linkplain Throwable#addSuppressed
     *                          suppressed} in it; what was thrown around a tick is thrown as it is,
     *                          whatever its type
     */
    public static <W> void settle(Map<W, Throwable> failures, Predicate<? super W> released,
                                  BiConsumer<? super W, ? super Throwable> afterRelease,
                                  BiFunction<? super W, ? super Throwable, ? extends Throwable> failingTick) {
        Throwable crash = null;
        for (Map.Entry<W, Throwable> entry : failures.entrySet()) {
            W world = entry.getKey();
            boolean ofTheTick = entry.getValue() instanceof TickFailure;
            Throwable thrown = ofTheTick ? entry.getValue().getCause() : entry.getValue();
            if (released.test(world)) {
                afterRelease.accept(world, thrown);
                continue;
            }
            Throwable fatal = ofTheTick ? failingTick.apply(world, thrown) : thrown;
            if (fatal == null) continue;
            if (crash == null) crash = fatal;
            else if (fatal != crash) crash.addSuppressed(fatal); // two worlds can hand back the same Error
        }
        if (crash != null) WorldTickSteps.<RuntimeException>rethrow(crash);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
