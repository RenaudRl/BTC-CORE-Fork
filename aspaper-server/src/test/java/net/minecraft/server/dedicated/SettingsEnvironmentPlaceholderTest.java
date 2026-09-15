package net.minecraft.server.dedicated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading a server property through an environment variable.
 *
 * <p>This exists so an orchestrator can hand a running server its rcon password or its
 * management-server secret without the secret ever living in a service template. The part that has
 * to hold is not only that the value is read: it is that the resolved value never travels back to
 * disk, because {@code server.properties} is rewritten on every start.
 *
 * <p>The environment cannot be set from inside a JVM, so the resolution case borrows a variable the
 * process already has, and the failure case uses a name nothing would define.
 */
class SettingsEnvironmentPlaceholderTest {

    /** Names a variable can carry and still be matched by the placeholder pattern. */
    private static final Pattern USABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final String MISSING_VARIABLE = "BTC_VARIABLE_THAT_IS_NEVER_DEFINED";

    private static final class TestSettings extends Settings<TestSettings> {

        private TestSettings(final Properties properties) {
            super(properties, null);
        }

        @Override
        protected TestSettings reload(
            final RegistryAccess registryAccess, final Properties properties, final joptsimple.OptionSet options
        ) {
            return new TestSettings(properties);
        }

        private String read(final String key, final String defaultValue) {
            return this.get(key, defaultValue);
        }
    }

    private static Map.Entry<String, String> anyVariableOfThisProcess() {
        return System.getenv().entrySet().stream()
            .filter(entry -> USABLE_NAME.matcher(entry.getKey()).matches())
            .filter(entry -> !entry.getValue().isEmpty())
            .findFirst()
            .orElse(null);
    }

    @Test
    @DisplayName("a property written as ${env:NAME} is read from the environment")
    void readsThePlaceholderFromTheEnvironment() {
        Map.Entry<String, String> variable = anyVariableOfThisProcess();
        Assumptions.assumeTrue(variable != null, "this process has no usable environment variable");

        Properties properties = new Properties();
        properties.setProperty("rcon.password", "${env:" + variable.getKey() + "}");
        TestSettings settings = new TestSettings(properties);

        assertEquals(variable.getValue(), settings.read("rcon.password", ""));
    }

    @Test
    @DisplayName("the resolved value never reaches the properties destined for disk")
    void keepsThePlaceholderInThePropertiesWrittenBack() {
        Map.Entry<String, String> variable = anyVariableOfThisProcess();
        Assumptions.assumeTrue(variable != null, "this process has no usable environment variable");

        String placeholder = "${env:" + variable.getKey() + "}";
        Properties properties = new Properties();
        properties.setProperty("rcon.password", placeholder);
        TestSettings settings = new TestSettings(properties);

        settings.read("rcon.password", "");

        assertEquals(placeholder, settings.properties.getProperty("rcon.password"));
        assertNotEquals(variable.getValue(), settings.properties.getProperty("rcon.password"));
    }

    @Test
    @DisplayName("an unset variable makes the property absent, never the literal placeholder")
    void treatsAnUnsetVariableAsAbsent() {
        Properties properties = new Properties();
        properties.setProperty("rcon.password", "${env:" + MISSING_VARIABLE + "}");
        TestSettings settings = new TestSettings(properties);

        // The default wins, so rcon stays disabled instead of running with a password nobody knows.
        assertEquals("", settings.read("rcon.password", ""));
        assertNull(settings.getStringRaw("rcon.password"));
        // And the placeholder survives, so the next start can still resolve it.
        assertEquals("${env:" + MISSING_VARIABLE + "}", settings.properties.getProperty("rcon.password"));
    }

    @Test
    @DisplayName("an ordinary value is left exactly as it was")
    void leavesAnOrdinaryValueUntouched() {
        Properties properties = new Properties();
        properties.setProperty("level-name", "world");
        properties.setProperty("motd", "a ${env in prose, not a placeholder");
        TestSettings settings = new TestSettings(properties);

        assertEquals("world", settings.read("level-name", "other"));
        assertEquals("a ${env in prose, not a placeholder", settings.read("motd", "other"));
        assertEquals("world", settings.properties.getProperty("level-name"));
    }
}
