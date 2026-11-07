package com.infernalsuite.asp.plugin.config;

/**
 * A {@code sources.yml} value that cannot be used (AD-096). The message names the key and, for an
 * environment reference, the variable — never the value, which may be a secret — so it is logged
 * as is, without a stack trace.
 */
public final class SourceConfigurationException extends IllegalArgumentException {

    public SourceConfigurationException(final String message) {
        super(message);
    }
}
