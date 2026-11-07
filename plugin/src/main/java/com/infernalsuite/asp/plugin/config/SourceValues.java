package com.infernalsuite.asp.plugin.config;

import dev.btc.core.bridge.BridgeEnvironment;

import java.time.Duration;
import java.util.function.UnaryOperator;

/**
 * Typed reading of {@code sources.yml} values, resolving {@code ${env:NAME}} (AD-096).
 *
 * <p>A CloudNet template is shared by every service and carries no secret, so database hosts and
 * credentials come from the environment the orchestrator gives the service. The rule is the one
 * {@link BridgeEnvironment} already applies to the bridge: a value that is <em>exactly</em>
 * {@code ${env:NAME}} is read from the environment, and a variable that is unset or empty counts
 * as absent.
 *
 * <p>Resolution happens when a source is opened, never in {@link DatasourcesConfig} itself:
 * {@link ConfigManager} writes that object back to {@code sources.yml}, and a resolved password
 * would land on disk.
 *
 * <p>Every refusal is an {@link IllegalArgumentException} whose message names the key and, for a
 * reference, the variable — never the value, which may be a secret.
 */
public final class SourceValues {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;

    private final UnaryOperator<String> environment;

    public SourceValues(final UnaryOperator<String> environment) {
        this.environment = environment;
    }

    /** Reads against the real process environment. */
    public static SourceValues fromProcessEnvironment() {
        return new SourceValues(System::getenv);
    }

    /** {@code raw}, resolved. An empty literal stays allowed (an empty password, for instance). */
    public String text(final String key, final String raw) {
        final String resolved = BridgeEnvironment.resolve(raw, environment);
        if (resolved != null) return resolved;

        final String variable = BridgeEnvironment.referencedVariable(raw);
        throw new IllegalArgumentException(variable == null
                ? "sources.yml: '" + key + "' is missing"
                : "sources.yml: '" + key + "' refers to the environment variable " + variable + ", which is not set or empty");
    }

    /** A TCP port, resolved then range-checked. */
    public int port(final String key, final String raw) {
        final String text = text(key, raw).trim();
        try {
            final int port = Integer.parseInt(text);
            if (port >= MIN_PORT && port <= MAX_PORT) return port;
        } catch (final NumberFormatException notANumber) {
            // Reported below, without echoing the value.
        }
        throw new IllegalArgumentException("sources.yml: '" + key + "' is not a port between " + MIN_PORT + " and "
                + MAX_PORT + describeOrigin(raw));
    }

    /** A strictly positive number of seconds. */
    public Duration seconds(final String key, final int seconds) {
        if (seconds > 0) return Duration.ofSeconds(seconds);
        throw new IllegalArgumentException("sources.yml: '" + key + "' must be a positive number of seconds");
    }

    private static String describeOrigin(final String raw) {
        final String variable = BridgeEnvironment.referencedVariable(raw);
        return variable == null ? "" : " (read from the environment variable " + variable + ")";
    }
}
