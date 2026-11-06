/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import org.jetbrains.annotations.Nullable;

/**
 * Reads this service's own name from the CloudNet wrapper that started it.
 *
 * <p>Since 4.0.0-RC17 the wrapper API is on Maven Central, so the identity comes from
 * {@code ServiceInfoHolder.serviceInfo().serviceId()} instead of the {@code .wrapper/wrapper.json}
 * file this class used to parse (measured absent on 17/09/2026, which is why the file was read at
 * all). The API is {@code compileOnly}: the wrapper provides it at runtime, and a server run
 * outside CloudNet has no such class. The CloudNet types live in {@link CloudNetWrapperSource},
 * which is only touched once the wrapper's presence has been proven here.
 *
 * <p>Nothing here guesses. If the wrapper is absent, or answers with something unusable, this
 * source says it cannot answer, and the bridge refuses to start rather than inventing an identity —
 * the same conduct as {@code PlatformSource} when Floodgate is missing.
 */
final class CloudNetServiceIdentity {

    /** Present on every CloudNet service's classpath, absent everywhere else. */
    private static final String WRAPPER_MARKER = "eu.cloudnetservice.wrapper.holder.ServiceInfoHolder";

    private CloudNetServiceIdentity() {
    }

    /** This service's name and the separator its task uses. */
    record Resolved(String serviceName, String separator, String task) {
    }

    /**
     * @return the identity, or {@code null} when this server is not run by CloudNet, or when the
     *     wrapper cannot say yet
     */
    static @Nullable Resolved read() {
        if (!wrapperPresent()) {
            return null;
        }
        try {
            return CloudNetWrapperSource.read();
        } catch (RuntimeException | LinkageError exception) {
            // Deliberately broad: an injection layer not booted, a holder not set up and an API that
            // changed shape all mean the same thing here — we do not know this service's name — and
            // each of them must lead to a refusal that says so, never to a default.
            return null;
        }
    }

    /** Pure mapping of the wrapper's three primitives; {@code null} when any is unusable. */
    static @Nullable Resolved of(final @Nullable String task, final @Nullable String separator,
                                 final int id) {
        if (task == null || task.isBlank() || separator == null || separator.isEmpty() || id < 0) {
            return null;
        }
        return new Resolved(ServiceIdentity.serviceName(task, separator, id), separator, task);
    }

    private static boolean wrapperPresent() {
        try {
            Class.forName(WRAPPER_MARKER, false, CloudNetServiceIdentity.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError exception) {
            return false;
        }
    }
}
