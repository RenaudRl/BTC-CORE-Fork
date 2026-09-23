package dev.btc.core.integrity;

import org.jetbrains.annotations.Nullable;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code storage.*} values of {@code anticheat.yml}, with {@code ${env:NAME}} references resolved.
 *
 * <p>A production template is shared by every CloudNet service and no secret is ever written into
 * one, so the password has to come from the environment. The syntax is the one the rest of the
 * platform already speaks — {@code server.properties} (patch 0006), the bridge's {@code config.yml},
 * {@code mysql/config.yml} and {@code permissions.yml} of the Typewriter extensions.
 *
 * <p>Resolution happens here, when the database is configured, and never in {@code AnticheatConfig}:
 * the configuration file keeps the reference, so a resolved secret is never written back to disk.
 *
 * <p>A reference to a variable that is not set makes the whole set unusable. Connecting with the
 * literal text {@code ${env:NAME}} as a password would fail later, against the database, under a
 * message that names neither the key nor the variable.
 *
 * @param problem why the set is unusable, naming the key and the variable; {@code null} when usable
 */
public record StorageCredentials(@Nullable String host, @Nullable String database, @Nullable String username,
                                 @Nullable String password, @Nullable String problem) {

    /**
     * A reference only counts when it fills the <em>whole</em> value, as everywhere else on the
     * platform: a partial substitution would be a silent special case.
     */
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");

    /**
     * Resolves every value against {@code lookup}.
     *
     * <p>A value read from the environment is never resolved again, otherwise it could steer which
     * other variable gets read. A defined but empty variable counts as missing.
     */
    public static StorageCredentials resolve(String host, String database, String username, String password,
                                             UnaryOperator<String> lookup) {
        String[] keys = {"host", "database", "username", "password"};
        String[] values = {host, database, username, password};
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) continue;
            Matcher matcher = REFERENCE.matcher(values[i].trim());
            if (!matcher.matches()) continue;
            String found = lookup.apply(matcher.group(1));
            if (found == null || found.isEmpty()) {
                return new StorageCredentials(null, null, null, null,
                    "storage." + keys[i] + " -> ${env:" + matcher.group(1) + "} is not set");
            }
            values[i] = found;
        }
        return new StorageCredentials(values[0], values[1], values[2], values[3], null);
    }

    /** Resolves against the real process environment. */
    public static StorageCredentials resolve(String host, String database, String username, String password) {
        return resolve(host, database, username, password, System::getenv);
    }

    public boolean usable() {
        return problem == null;
    }

    /** Never prints the password: this record ends up in log lines. */
    @Override
    public String toString() {
        return "StorageCredentials[host=" + host + ", database=" + database + ", username=" + username
            + ", password=" + (password == null ? "<none>" : "<hidden>") + ", problem=" + problem + "]";
    }
}
