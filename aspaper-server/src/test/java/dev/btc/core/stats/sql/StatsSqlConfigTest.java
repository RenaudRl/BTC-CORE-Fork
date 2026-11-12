package dev.btc.core.stats.sql;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsSqlConfigTest {

    private static final String SECRET = "s3cr3t-Pa55";
    private static final UnaryOperator<String> NEVER_READ = name -> {
        throw new AssertionError("the environment was read: " + name);
    };

    private final Map<String, String> environment = new HashMap<>(Map.of(
        "BTC_STATS_DB_HOST", "db.internal", "BTC_STATS_DB_PORT", "5432", "BTC_STATS_DB_NAME", "btc",
        "BTC_STATS_DB_USER", "stats_user", "BTC_STATS_DB_PASSWORD", SECRET));

    @Test
    void disabledByDefaultAndNeverResolvesEnvironment() {
        assertNull(StatsSql.configure(new YamlConfiguration(), NEVER_READ, false));
        assertNull(StatsSql.configure(yaml("enabled", false), NEVER_READ, false));
    }

    @Test
    void zeroFeaturesStatsKeepsTheLayerClosedWithoutReadingAnything() {
        assertNull(StatsSql.configure(yaml("enabled", true), NEVER_READ, true));
    }

    @Test
    void enabledWithEveryVariableSetOpens() {
        StatsSqlConfig config = StatsSql.configure(yaml("enabled", true), environment::get, false);

        assertNotNull(config);
        assertEquals("db.internal", config.host());
        assertEquals(5432, config.port());
        assertEquals("btc", config.database());
        assertEquals("stats_user", config.username());
        assertEquals(SECRET, config.password());
        assertEquals("btc_", config.tablePrefix());
        assertTrue(config.handoffTakeover());
        assertTrue(config.mirrorJson());
    }

    @Test
    void missingEnvironmentVariableNamesKeyAndVariableButNeverValue() {
        environment.remove("BTC_STATS_DB_PASSWORD");

        String message = problem(yaml("enabled", true));

        assertEquals("stats-sql.password -> ${env:BTC_STATS_DB_PASSWORD} is not set", message);
        assertNull(StatsSql.configure(yaml("enabled", true), environment::get, false), "an unusable configuration keeps the layer closed");
    }

    @Test
    void emptyEnvironmentVariableIsTreatedAsMissing() {
        environment.put("BTC_STATS_DB_PORT", "");

        assertEquals("stats-sql.port -> ${env:BTC_STATS_DB_PORT} is not set", problem(yaml("enabled", true)));
    }

    @Test
    void resolvedValueIsNeverResolvedTwice() {
        environment.put("BTC_STATS_DB_PASSWORD", "${env:OTHER}");
        environment.put("OTHER", "must-not-be-read");

        assertEquals("${env:OTHER}", read(yaml("enabled", true)).password());
    }

    @Test
    void literalValueIsAcceptedUnchanged() {
        StatsSqlConfig config = StatsSqlConfig.read(
            yaml("host", "localhost", "port", "6543", "database", "stats", "username", "u", "password", "p")
                .getConfigurationSection("stats-sql"), NEVER_READ);

        assertEquals("localhost", config.host());
        assertEquals(6543, config.port());
        assertEquals("p", config.password());
    }

    @Test
    void resolvedSecretNeverAppearsInAnyMessage() {
        assertFalse(read(yaml()).toString().contains(SECRET));
        assertFalse(read(yaml()).toString().contains("stats_user"));

        environment.put("BTC_STATS_DB_HOST", SECRET + "@evil/");
        assertFalse(problem(yaml()).contains(SECRET), "a rejected value read from the environment is not repeated");
        environment.put("BTC_STATS_DB_HOST", "db.internal");
        environment.put("BTC_STATS_DB_PORT", SECRET);
        assertFalse(problem(yaml()).contains(SECRET));
    }

    @Test
    void hostAndDatabaseCannotChangeTheUrl() {
        assertTrue(problem(yaml("host", "db/other?user=x")).startsWith("stats-sql.host "));
        assertTrue(problem(yaml("database", "btc?ssl=false")).startsWith("stats-sql.database "));
        assertTrue(problem(yaml("port", "70000")).startsWith("stats-sql.port "));
    }

    @Test
    void tablePrefixInjectionIsRejectedAndNotAssigned() {
        assertTrue(problem(yaml("table-prefix", "x; DROP TABLE y;--")).startsWith("stats-sql.table-prefix "));
        assertNull(StatsSql.configure(yaml("enabled", true, "table-prefix", "x; DROP TABLE y;--"), environment::get, false));
    }

    @Test
    void emptyTablePrefixIsAccepted() {
        assertEquals("", read(yaml("table-prefix", "")).tablePrefix());
    }

    @Test
    void renewalNotBelowThirdOfLeaseIsRejected() {
        String message = problem(yaml("lease-seconds", 60, "lease-renew-seconds", 21));

        assertTrue(message.contains("stats-sql.lease-renew-seconds") && message.contains("stats-sql.lease-seconds"), message);
        assertEquals(20, read(yaml("lease-seconds", 60, "lease-renew-seconds", 20)).leaseRenewSeconds());
    }

    @Test
    void handoffWaitNotBelowLoadTimeoutIsRejected() {
        String message = problem(yaml("handoff-wait-ms", 5000, "load-timeout-ms", 5000));

        assertTrue(message.contains("stats-sql.handoff-wait-ms") && message.contains("stats-sql.load-timeout-ms"), message);
    }

    private StatsSqlConfig read(YamlConfiguration root) {
        return StatsSqlConfig.read(root.getConfigurationSection("stats-sql"), environment::get);
    }

    private String problem(YamlConfiguration root) {
        return assertThrows(IllegalArgumentException.class, () -> read(root)).getMessage();
    }

    /** A btccore.yml whose stats-sql section holds the given keys, everything else at its default. */
    private static YamlConfiguration yaml(Object... keysAndValues) {
        YamlConfiguration root = new YamlConfiguration();
        root.createSection("stats-sql");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            root.set("stats-sql." + keysAndValues[i], keysAndValues[i + 1]);
        }
        return root;
    }
}
