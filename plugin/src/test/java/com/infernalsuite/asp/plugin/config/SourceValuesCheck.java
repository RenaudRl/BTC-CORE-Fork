package com.infernalsuite.asp.plugin.config;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Dependency-free checks for {@link SourceValues} (AD-096), in this module's idiom
 * ({@code main} + assertions).
 *
 * <p>At stake: a CloudNet template carries no secret, so the islands' database only opens if
 * {@code sources.yml} reads its credentials from the environment — and a refusal must say which
 * variable is missing without ever printing a password.
 */
public final class SourceValuesCheck {

    private static final String SECRET = "s3cr3t-never-printed";

    private static final String BAD_HOST = "evil.host/other?sslfactory=x";

    private static final UnaryOperator<String> ENVIRONMENT = Map.of(
            "BTC_SLIME_PG_HOST", "pg.internal",
            "BTC_SLIME_PG_BAD_HOST", BAD_HOST,
            "BTC_SLIME_PG_PORT", "5434",
            "BTC_SLIME_PG_PASSWORD", SECRET,
            "BTC_SLIME_PG_BAD_PORT", SECRET,
            "BTC_SLIME_PG_EMPTY", "")::get;

    private static final SourceValues VALUES = new SourceValues(ENVIRONMENT);

    private SourceValuesCheck() {
    }

    public static void main(final String[] args) {
        check("pg.internal".equals(VALUES.text("postgresql.host", "${env:BTC_SLIME_PG_HOST}")),
                "a value that is entirely a reference is read from the environment");
        check("127.0.0.1".equals(VALUES.text("postgresql.host", "127.0.0.1")),
                "a literal value is kept as written");
        check("".equals(VALUES.text("postgresql.password", "")),
                "an empty literal stays allowed (no password)");
        check(5434 == VALUES.port("postgresql.port", "${env:BTC_SLIME_PG_PORT}"),
                "a port is read from the environment and typed");
        check(5432 == VALUES.port("postgresql.port", "5432"),
                "a literal port still reads as before");

        final String unset = refusal(() -> VALUES.text("postgresql.password", "${env:BTC_SLIME_PG_ABSENT}"));
        check(unset.contains("BTC_SLIME_PG_ABSENT") && unset.contains("postgresql.password"),
                "an unset variable is refused, naming the variable and the key: " + unset);

        final String empty = refusal(() -> VALUES.text("postgresql.username", "${env:BTC_SLIME_PG_EMPTY}"));
        check(empty.contains("BTC_SLIME_PG_EMPTY"), "an empty variable counts as unset: " + empty);

        final String badPort = refusal(() -> VALUES.port("postgresql.port", "${env:BTC_SLIME_PG_BAD_PORT}"));
        check(badPort.contains("BTC_SLIME_PG_BAD_PORT") && !badPort.contains(SECRET),
                "a malformed port names its variable and never echoes the value: " + badPort);
        check(!refusal(() -> VALUES.port("postgresql.port", "70000")).contains("70000"),
                "an out-of-range port is refused without echoing it");

        check(Duration.ofSeconds(60).equals(VALUES.seconds("postgresql.lock-lease-seconds", 60)),
                "a lease length reads as seconds");
        check(refusal(() -> VALUES.seconds("postgresql.lock-renew-seconds", 0)).contains("lock-renew-seconds"),
                "a zero renewal period is refused, naming the key");
        check(Duration.ofSeconds(30).equals(VALUES.renewal("postgresql.lock-renew-seconds", 30,
                        "postgresql.lock-lease-seconds", Duration.ofSeconds(60))),
                "a renewal of exactly half the lease is accepted");
        final String tooSlow = refusal(() -> VALUES.renewal("postgresql.lock-renew-seconds", 31,
                "postgresql.lock-lease-seconds", Duration.ofSeconds(60)));
        check(tooSlow.contains("lock-renew-seconds") && tooSlow.contains("lock-lease-seconds"),
                "a renewal longer than half the lease is refused, naming both keys: " + tooSlow);

        check("pg.internal".equals(VALUES.urlSafe("postgresql.host", "${env:BTC_SLIME_PG_HOST}")),
                "a plain host name is accepted");
        final String badHost = refusal(() -> VALUES.urlSafe("postgresql.host", "${env:BTC_SLIME_PG_BAD_HOST}"));
        check(badHost.contains("postgresql.host") && badHost.contains("BTC_SLIME_PG_BAD_HOST") && !badHost.contains(BAD_HOST),
                "a host that would alter the JDBC URL is refused, naming the key, never the value: " + badHost);
        check(!refusal(() -> VALUES.urlSafe("postgresql.database", "db?ssl=false")).contains("db?ssl"),
                "a database name with URL syntax is refused without echoing it");

        System.out.println("SourceValuesCheck: all checks passed");
    }

    private static String refusal(final Supplier<?> read) {
        try {
            read.get();
        } catch (final SourceConfigurationException refused) {
            return refused.getMessage();
        }
        throw new AssertionError("expected a refusal");
    }

    private static void check(final boolean condition, final String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
