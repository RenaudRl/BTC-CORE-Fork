package dev.btc.core.stats.sql;

import dev.btc.core.integrity.StorageCredentials;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The {@code stats-sql} section of {@code btccore.yml}, checked and with its {@code ${env:NAME}}
 * references resolved.
 *
 * <p>Only built when the layer is enabled: a disabled layer never looks at the environment. The
 * configuration file keeps the references, so no resolved value is ever written back to disk.
 */
public record StatsSqlConfig(String host, int port, String database, String username, String password,
                             String sslMode, String sslRootCert, String tablePrefix, int connectTimeoutSeconds, int socketTimeoutSeconds,
                             long loadTimeoutMs, long handoffWaitMs, long handoffPollMs, boolean handoffTakeover,
                             int leaseSeconds, int leaseRenewSeconds, long batchIntervalMs, int batchMaxSize,
                             boolean mirrorJson, int failureThreshold, int probeIntervalSeconds,
                             int shutdownFlushTimeoutSeconds) {

    public static final String SECTION = "stats-sql";
    private static final String KEY = SECTION + ".";
    /** Same rule as the anticheat tables: the prefix is the only text ever concatenated into SQL. */
    private static final Pattern TABLE_PREFIX = Pattern.compile("^([a-z][a-z0-9_]{0,31})?$");
    /** Same rule as {@code SourceValues.urlSafe}: nothing that could change the meaning of a JDBC URL. */
    private static final Pattern URL_SAFE = Pattern.compile("[A-Za-z0-9._-]+");
    private static final int MAX_PORT = 65535;
    private static final String SSL_PREFER = "prefer";
    private static final String SSL_VERIFY_FULL = "verify-full";
    /** The values of the driver's {@code sslmode}, and nothing else: the value is handed to it as it is. */
    private static final List<String> SSL_MODES = List.of("disable", "allow", SSL_PREFER, "require", "verify-ca", SSL_VERIFY_FULL);
    /** A lease must survive two missed renewals. */
    private static final int RENEWALS_PER_LEASE = 3;

    /**
     * @throws IllegalArgumentException naming the key at fault (and the variable for an unset
     *                                  reference), never the content of any variable
     */
    public static StatsSqlConfig read(ConfigurationSection section, UnaryOperator<String> environment) {
        StorageCredentials credentials = StorageCredentials.resolve(KEY,
            section.getString("host", "${env:BTC_STATS_DB_HOST}"),
            section.getString("database", "${env:BTC_STATS_DB_NAME}"),
            section.getString("username", "${env:BTC_STATS_DB_USER}"),
            section.getString("password", "${env:BTC_STATS_DB_PASSWORD}"), environment);
        if (!credentials.usable()) {
            throw new IllegalArgumentException(credentials.problem());
        }
        String port = StorageCredentials.resolveValue(KEY + "port", section.getString("port", "${env:BTC_STATS_DB_PORT}"), environment);

        StatsSqlConfig config = new StatsSqlConfig(
            urlSafe("host", credentials.host()), port(port), urlSafe("database", credentials.database()),
            credentials.username(), credentials.password(), sslMode(section), sslRootCert(section.getString("ssl-root-cert", "")),
            tablePrefix(section.getString("table-prefix", "btc_")),
            positive(section, "connect-timeout-seconds", 10), positive(section, "socket-timeout-seconds", 10),
            positive(section, "load-timeout-ms", 5000), positive(section, "handoff-wait-ms", 3000),
            positive(section, "handoff-poll-ms", 100), section.getBoolean("handoff-takeover", true),
            positive(section, "lease-seconds", 60), positive(section, "lease-renew-seconds", 20),
            positive(section, "batch-interval-ms", 1000), positive(section, "batch-max-size", 200),
            section.getBoolean("mirror-json", true), positive(section, "failure-threshold", 3),
            positive(section, "probe-interval-seconds", 30), positive(section, "shutdown-flush-timeout-seconds", 10));
        // "At most", not "below": the documented defaults (60 and 20) are exactly a third.
        if (config.leaseRenewSeconds * RENEWALS_PER_LEASE > config.leaseSeconds) {
            throw new IllegalArgumentException(KEY + "lease-renew-seconds must be at most a third of " + KEY + "lease-seconds");
        }
        if (config.handoffWaitMs >= config.loadTimeoutMs) {
            throw new IllegalArgumentException(KEY + "handoff-wait-ms must be below " + KEY + "load-timeout-ms");
        }
        return config;
    }

    public long leaseMs() {
        return leaseSeconds * 1000L;
    }

    private static String urlSafe(String key, String value) {
        if (value == null || !URL_SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException(KEY + key + " may only contain letters, digits, '.', '_' or '-'");
        }
        return value;
    }

    private static int port(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port >= 1 && port <= MAX_PORT) return port;
        } catch (NumberFormatException | NullPointerException notANumber) {
            // same message below: the value may come from the environment and is not repeated
        }
        throw new IllegalArgumentException(KEY + "port must be a number between 1 and " + MAX_PORT);
    }

    /**
     * {@code use-ssl} is the older key. {@code true} was the driver's {@code ssl=true}, which checks
     * the certificate and the host name, and still means that; {@code false}, or nothing, was its
     * default, {@code prefer}. A file that sets both must not ask for less than {@code use-ssl} did.
     */
    private static String sslMode(ConfigurationSection section) {
        boolean useSsl = section.getBoolean("use-ssl", false);
        String mode = section.getString("ssl-mode");
        if (mode == null) {
            return useSsl ? SSL_VERIFY_FULL : SSL_PREFER;
        }
        if (!SSL_MODES.contains(mode)) {
            throw new IllegalArgumentException(KEY + "ssl-mode must be one of " + String.join(", ", SSL_MODES));
        }
        if (useSsl && !mode.equals(SSL_VERIFY_FULL)) {
            throw new IllegalArgumentException(KEY + "use-ssl: true means " + KEY + "ssl-mode: " + SSL_VERIFY_FULL + "; remove one of the two");
        }
        return mode;
    }

    /** @return the path as given; empty when the driver's own default certificate store is used */
    private static String sslRootCert(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            if (Files.isReadable(Path.of(value)) && Files.isRegularFile(Path.of(value))) return value;
        } catch (InvalidPathException notAPath) {
            // same message below
        }
        throw new IllegalArgumentException(KEY + "ssl-root-cert is not a readable file");
    }

    private static String tablePrefix(String value) {
        if (value == null || !TABLE_PREFIX.matcher(value).matches()) {
            throw new IllegalArgumentException(KEY + "table-prefix must match " + TABLE_PREFIX.pattern());
        }
        return value;
    }

    private static int positive(ConfigurationSection section, String key, int fallback) {
        int value = section.getInt(key, fallback);
        if (value <= 0) {
            throw new IllegalArgumentException(KEY + key + " must be above 0");
        }
        return value;
    }

    /** Never prints the user or the password: this record can end up in a log line. */
    @Override
    public String toString() {
        return "StatsSqlConfig[tablePrefix=" + tablePrefix + ", leaseSeconds=" + leaseSeconds + ", sslMode=" + sslMode + "]";
    }
}
