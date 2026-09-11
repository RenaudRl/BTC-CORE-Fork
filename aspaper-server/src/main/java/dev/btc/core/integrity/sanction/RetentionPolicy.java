package dev.btc.core.integrity.sanction;

import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.security.NativeAnticheatDB;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Enforces the retention horizons, on a schedule, without anyone having to remember.
 *
 * <p>A retention policy written in a document and applied by nobody is not a policy. This is the part
 * that makes it true, and it governs two different treatments that deliberately end differently:
 *
 * <ul>
 *   <li><b>Sanction addresses are erased, the sanction is kept.</b> A sanction is a decision, and a
 *       moderation history that can be quietly emptied cannot be reviewed or appealed. Only the IP
 *       address — the part that is personal data beyond the account itself — goes.</li>
 *   <li><b>Violation journal lines are deleted outright.</b> A violation is a suspicion, not a
 *       decision. Anonymising a suspicion leaves a row that helps nobody and still concerns someone;
 *       past its usefulness it should simply not exist.</li>
 * </ul>
 *
 * <p>Runs on its own scheduled executor rather than a Bukkit scheduler: it touches no world and no
 * entity, only the database, and it must keep running whatever region owns what.
 */
public final class RetentionPolicy {

    private static final Logger LOG = Logger.getLogger("Sentinel");

    /** How often the horizons are re-applied. Daily is ample for horizons measured in months. */
    private static final Duration INTERVAL = Duration.ofHours(24);

    /**
     * Delay before the first pass.
     *
     * <p>Not zero: startup is the busiest moment of a server's life, and a purge is never urgent
     * enough to compete with it.
     */
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(10);

    private static ScheduledExecutorService scheduler;

    private RetentionPolicy() {}

    /** Starts the daily pass. Horizons set to zero are skipped, loudly. */
    public static synchronized void start() {
        if (scheduler != null) {
            return;
        }
        int addressDays = AnticheatConfig.addressRetentionDays;
        int violationDays = AnticheatConfig.violationRetentionDays;

        if (addressDays <= 0) {
            LOG.warning("[Sentinel] storage.address-retention-days is 0: stored IP addresses are kept "
                + "indefinitely. Make sure that is a decision, not an oversight.");
        }
        if (violationDays <= 0) {
            LOG.warning("[Sentinel] storage.violation-retention-days is 0: the violation journal is "
                + "kept indefinitely and will grow without bound.");
        }
        if (addressDays <= 0 && violationDays <= 0) {
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Sentinel-Retention");
            thread.setDaemon(true);
            return thread;
        });

        scheduler.scheduleAtFixedRate(
            RetentionPolicy::runOnce,
            INITIAL_DELAY.toMillis(), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);

        LOG.info("[Sentinel] retention active — addresses: "
            + describe(addressDays) + ", violation journal: " + describe(violationDays) + ".");
    }

    /** Stops the schedule. The horizons resume where they left off at the next start. */
    public static synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * Applies both horizons now.
     *
     * <p>Exposed so an operator can prove the policy works rather than waiting a day and trusting it.
     */
    public static void runOnce() {
        int addressDays = AnticheatConfig.addressRetentionDays;
        if (addressDays > 0) {
            SanctionService.purgeAddresses(Duration.ofDays(addressDays).toMillis())
                .thenAccept(erased -> {
                    if (erased > 0) {
                        LOG.info("[Sentinel] retention: erased " + erased + " stored address(es).");
                    }
                });
        }

        int violationDays = AnticheatConfig.violationRetentionDays;
        if (violationDays > 0) {
            NativeAnticheatDB.purgeOlderThan(Duration.ofDays(violationDays).toMillis())
                .thenAccept(deleted -> {
                    if (deleted > 0) {
                        LOG.info("[Sentinel] retention: deleted " + deleted + " violation journal line(s).");
                    }
                });
        }
    }

    private static String describe(int days) {
        return days > 0 ? days + " days" : "unlimited";
    }
}
