package dev.btc.core.integrity;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * Suspends every automatic response while detection and journalling keep running.
 *
 * <p>This exists for one situation, and it is not hypothetical: a new mechanic ships, a check starts
 * setting players back on legitimate play, and staff need it to stop <em>now</em> — before anyone has
 * time to find which check, edit a file and reload. Every reference anticheat that lacks this switch
 * gets disabled wholesale instead, and a disabled anticheat sees nothing.
 *
 * <p>The distinction is the whole point. Under panic, checks still run, violations are still recorded,
 * staff alerts still fire. Only acting is suspended. So when the switch is lifted, the record of what
 * happened during the incident is intact — which is exactly what is needed to decide whether the check
 * was wrong or the players were.
 *
 * <p>Deliberately in memory only. A panic switch that survives a restart is a panic switch someone
 * forgets is on, and a silently unarmed anticheat is worse than a noisy one.
 */
public final class PanicSwitch {

    /**
     * Deliberately not {@code Bukkit.getLogger()}.
     *
     * <p>That call needs a running server, which would make this switch impossible to exercise in a
     * test. A safety mechanism that cannot be tested is one nobody can be sure still works.
     */
    private static final Logger LOG = Logger.getLogger("Sentinel");

    private static volatile boolean engaged;
    private static volatile String engagedBy;
    private static volatile String reason;
    private static volatile long engagedAtMillis;

    private PanicSwitch() {}

    /** Whether automatic responses are currently suspended. */
    public static boolean engaged() {
        return engaged;
    }

    /**
     * Suspends automatic responses.
     *
     * @param by who engaged it, for the console record and for staff asking why nothing is happening
     * @param why what prompted it; not enforced, but a panic with no stated cause is one nobody dares lift
     */
    public static void engage(String by, String why) {
        engaged = true;
        engagedBy = by;
        reason = why;
        engagedAtMillis = System.currentTimeMillis();
        LOG.warning("[Sentinel] PANIC ENGAGED by " + by + ": " + why
            + " — checks keep running and violations keep being recorded, but nothing acts.");
    }

    /** Resumes automatic responses. */
    public static void release(String by) {
        if (!engaged) {
            return;
        }
        engaged = false;
        LOG.warning("[Sentinel] panic released by " + by + " after "
            + ((System.currentTimeMillis() - engagedAtMillis) / 1000) + "s; responses are armed again.");
        engagedBy = null;
        reason = null;
    }

    /** A line describing the current state, for a staff command. */
    public static Optional<String> describe() {
        if (!engaged) {
            return Optional.empty();
        }
        long seconds = (System.currentTimeMillis() - engagedAtMillis) / 1000;
        return Optional.of("panic engaged by " + engagedBy + " " + seconds + "s ago: " + reason);
    }
}
