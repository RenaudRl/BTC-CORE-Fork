package dev.btc.core.integrity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@code ${env:NAME}} resolution of {@code storage.*}.
 *
 * <p>The failure guarded here is the silent one: a production template that says
 * {@code password: ${env:BTC_TW_DB_PASSWORD}} on a service where the variable is missing must not
 * connect with the literal text {@code ${env:BTC_TW_DB_PASSWORD}} as a password. It must refuse, and
 * name the variable.
 */
class StorageCredentialsTest {

    private static UnaryOperator<String> env(Map<String, String> values) {
        return values::get;
    }

    @Test
    @DisplayName("plain values pass through untouched")
    void plainValuesPassThrough() {
        var resolved = StorageCredentials.resolve("127.0.0.1", "s4_btcdatas", "btc", "hunter2", env(Map.of()));

        assertTrue(resolved.usable());
        assertEquals("127.0.0.1", resolved.host());
        assertEquals("s4_btcdatas", resolved.database());
        assertEquals("btc", resolved.username());
        assertEquals("hunter2", resolved.password());
    }

    @Test
    @DisplayName("a reference filling the whole value is read from the environment")
    void referencesResolve() {
        var resolved = StorageCredentials.resolve("db", "s4", "${env:BTC_TW_DB_USER}", " ${env:BTC_TW_DB_PASSWORD} ",
            env(Map.of("BTC_TW_DB_USER", "tw", "BTC_TW_DB_PASSWORD", "s3cret")));

        assertTrue(resolved.usable());
        assertEquals("tw", resolved.username());
        assertEquals("s3cret", resolved.password());
    }

    @Test
    @DisplayName("a missing variable refuses and names it, never the literal reference")
    void missingVariableRefuses() {
        var resolved = StorageCredentials.resolve("db", "s4", "btc", "${env:BTC_TW_DB_PASSWORD}", env(Map.of()));

        assertFalse(resolved.usable());
        assertNull(resolved.password());
        assertEquals("storage.password -> ${env:BTC_TW_DB_PASSWORD} is not set", resolved.problem());
    }

    @Test
    @DisplayName("an empty variable counts as missing")
    void emptyVariableIsMissing() {
        var resolved = StorageCredentials.resolve("db", "s4", "${env:BTC_TW_DB_USER}", "pw",
            env(Map.of("BTC_TW_DB_USER", "")));

        assertFalse(resolved.usable());
        assertEquals("storage.username -> ${env:BTC_TW_DB_USER} is not set", resolved.problem());
    }

    @Test
    @DisplayName("a reference inside a larger string is not substituted")
    void partialReferenceIsLiteral() {
        var resolved = StorageCredentials.resolve("db", "s4", "btc", "pre${env:X}post", env(Map.of("X", "leak")));

        assertTrue(resolved.usable());
        assertEquals("pre${env:X}post", resolved.password());
    }

    @Test
    @DisplayName("a value read from the environment is never resolved a second time")
    void resolutionIsNotRecursive() {
        var resolved = StorageCredentials.resolve("db", "s4", "btc", "${env:A}",
            env(Map.of("A", "${env:B}", "B", "other")));

        assertEquals("${env:B}", resolved.password());
    }

    @Test
    @DisplayName("host and database accept references too")
    void hostAndDatabaseResolve() {
        var resolved = StorageCredentials.resolve("${env:DB_HOST}", "${env:DB_NAME}", "btc", "pw",
            env(Map.of("DB_HOST", "postgres", "DB_NAME", "btc_prod")));

        assertEquals("postgres", resolved.host());
        assertEquals("btc_prod", resolved.database());
    }

    @Test
    @DisplayName("the description never carries the password")
    void toStringHidesThePassword() {
        var resolved = StorageCredentials.resolve("db", "s4", "btc", "hunter2", env(Map.of()));

        assertFalse(resolved.toString().contains("hunter2"));
    }
}
