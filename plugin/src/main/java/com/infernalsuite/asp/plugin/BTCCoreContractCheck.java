package com.infernalsuite.asp.plugin;

import org.slf4j.Logger;

/**
 * BTCCore: refuses to run against a server jar this plugin was not built for.
 *
 * <p>See {@code dev.btc.core.BTCCoreBuild} for why this exists. Run this before anything else
 * touches {@code dev.btc.core.*}, so a mismatched pair is reported as a deployment error at load
 * instead of surfacing later as a {@link NoSuchMethodError} in the middle of a tick.
 */
final class BTCCoreContractCheck {

    /**
     * The contract this plugin was compiled against. Kept as a literal on purpose: reading it from
     * {@code BTCCoreBuild} would report the running server's value on both sides of the comparison
     * and never detect anything.
     *
     * <p>Bump together with {@code dev.btc.core.BTCCoreBuild#CONTRACT}.
     */
    private static final int EXPECTED_CONTRACT = 2;

    private BTCCoreContractCheck() {}

    /**
     * Verifies the running server exposes the {@code dev.btc.core.*} contract this plugin expects.
     *
     * @throws IllegalStateException when the two artifacts do not match, so the plugin fails to
     *         load loudly rather than half-working
     */
    static void verify(final Logger logger) {
        final int actual;
        try {
            actual = dev.btc.core.BTCCoreBuild.contract();
        } catch (final NoClassDefFoundError | NoSuchMethodError missing) {
            // No BTCCoreBuild#contract() at all: the server jar predates this check entirely.
            fail(logger, "the server jar does not declare a BTCCore contract at all (pre-contract build)");
            return;
        }
        if (actual != EXPECTED_CONTRACT) {
            fail(logger, "server declares contract " + actual + ", this plugin was built for " + EXPECTED_CONTRACT);
        }
    }

    private static void fail(final Logger logger, final String detail) {
        logger.error("======================================================================");
        logger.error("BTCCore: server jar and plugin jar do not come from compatible builds.");
        logger.error("  {}", detail);
        logger.error("  BTCCore ships as a PAIR. Redeploy both, never one of the two:");
        logger.error("    purpur.jar                  <- aspaper-server/build/libs/aspaper-paperclip-*.jar");
        logger.error("    plugins/btccore-plugin-*.jar <- plugin/build/libs/btccore-plugin-*.jar");
        logger.error("  Also remove any leftover plugins/asp-plugin-*.jar: the artifact was renamed,");
        logger.error("  so the old jar is not overwritten by the new one and both would load.");
        logger.error("======================================================================");
        throw new IllegalStateException("BTCCore artifact pair mismatch — see the log above");
    }
}
