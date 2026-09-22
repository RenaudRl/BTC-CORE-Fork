/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolution of a {@code ${env:NAME}} reference against the process environment.
 *
 * <p>A configuration value that is <em>exactly</em> {@code ${env:NAME}} is read from the
 * environment. The same convention already governs {@code server.properties} in this fork and
 * {@code mysql/config.yml} and {@code permissions.yml} in the Typewriter extensions: one syntax for
 * the whole platform, learnt once.
 *
 * <p>The bridge needs it for a reason the other three do not share. A CloudNet service copies its
 * template <em>as is</em>, and one template is shared by every task, so a backend identity written
 * into {@code config.yml} would be correct for exactly one server and wrong for all the others.
 * Measured on 17/09/2026: CloudNet hands the service process no name of its own — no environment
 * variable, no system property — so the orchestrator has to be the one that says who this backend is.
 *
 * <p>Nothing secret travels this way here: what the bridge resolves are names. The frame key is
 * still derived from the Velocity forwarding secret and never read from the configuration.
 *
 * <p>Pure: the lookup is injected, which makes it testable — a JVM cannot change its own environment.
 */
final class BridgeEnvironment {

    /**
     * A reference only counts when it fills the <em>whole</em> value. A reference buried in a larger
     * string would be a partial substitution, and therefore a silent special case the day someone
     * reads a name back and finds half of it unresolved.
     */
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");

    private BridgeEnvironment() {
    }

    /**
     * Resolves {@code value} when it is a reference, and returns it unchanged otherwise.
     *
     * <p>A variable that is defined but empty counts as missing: an empty identity would be refused
     * further down anyway, and refusing it here names the variable instead of the key.
     *
     * <p>The result is never resolved again — otherwise a value coming from the environment could
     * steer which other variable gets read.
     *
     * @return the resolved value, or {@code null} when the reference names a variable that is not set
     */
    static @Nullable String resolve(final @Nullable String value, final UnaryOperator<String> lookup) {
        if (value == null) return null;
        final Matcher matcher = REFERENCE.matcher(value.trim());
        if (!matcher.matches()) return value;
        final String found = lookup.apply(matcher.group(1));
        return found == null || found.isEmpty() ? null : found;
    }

    /** Resolves {@code value} against the real process environment. */
    static @Nullable String resolve(final @Nullable String value) {
        return resolve(value, System::getenv);
    }

    /**
     * Resolves every entry of a list, dropping the ones whose variable is not set.
     *
     * <p>Dropping rather than failing is deliberate and only safe because of what these lists are:
     * allow-lists. An entry that disappears can only ever <em>narrow</em> what the bridge accepts,
     * so an unset variable refuses traffic instead of letting it through. The caller still sees the
     * shrinkage — an empty allow-list is already announced at start.
     */
    static List<String> resolveAll(final List<String> values, final UnaryOperator<String> lookup) {
        return values.stream().map(value -> resolve(value, lookup)).filter(value -> value != null).toList();
    }

    /** The name of the variable a reference points at, or {@code null} when it is not a reference. */
    static @Nullable String referencedVariable(final @Nullable String value) {
        if (value == null) return null;
        final Matcher matcher = REFERENCE.matcher(value.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }
}
