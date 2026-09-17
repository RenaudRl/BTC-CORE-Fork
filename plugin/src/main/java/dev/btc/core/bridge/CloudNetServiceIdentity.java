/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads this service's own name from what CloudNet leaves in its working directory.
 *
 * <p>Why not compile against CloudNet's API, which is the house rule: <b>there is nothing to compile
 * against</b>. Measured on 17/09/2026 — CloudNet distributes itself through GitHub releases
 * ({@code cloudnet.updateRepo CloudNetService/launchermeta}), publishes no Maven artifact for the
 * driver, and ships its classes inside the wrapper jar. The wrapper is on the system classpath, so
 * the classes are technically reachable, but reaching them without a compile-time type means
 * reflection, which the house refuses. Reading three primitives out of a file CloudNet itself writes
 * to boot the service is the smaller commitment: it is data, not an API, and a shape that changes
 * produces a named refusal rather than a wrong name.
 *
 * <p>Nothing here guesses. If the file is absent, unreadable or shaped differently, this source says
 * it cannot answer, and the bridge refuses to start rather than inventing an identity — the same
 * conduct as {@code PlatformSource} when Floodgate is missing.
 */
final class CloudNetServiceIdentity {

    /** CloudNet writes this before starting the service; it is how the wrapper finds its node. */
    private static final String WRAPPER_FILE = ".wrapper/wrapper.json";

    private CloudNetServiceIdentity() {
    }

    /** This service's name and the separator its task uses, or {@code null} when unknown. */
    record Resolved(String serviceName, String separator, String task) {
    }

    /**
     * Resolves the identity from {@code serverDirectory}, the server's working directory.
     *
     * <p>{@code null} is an accepted argument, and it means the same as every other unknown here.
     * The caller derives the directory by walking up from the plugin's data folder, and that walk
     * runs out when the path is relative — which is exactly what Paper hands us under CloudNet,
     * where the server is started with its own directory as the working directory. Measured on
     * 17/09/2026: the first deployment threw an NPE here and took the whole of BTCCore down with it.
     *
     * @return the identity, or {@code null} when this server is not run by CloudNet, or when the
     *     file does not say what it used to
     */
    static @Nullable Resolved from(final @Nullable Path serverDirectory) {
        if (serverDirectory == null) {
            return null;
        }
        final Path wrapper = serverDirectory.resolve(WRAPPER_FILE);
        if (!Files.isRegularFile(wrapper)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(wrapper, StandardCharsets.UTF_8)) {
            final JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            final JsonObject serviceId = root.getAsJsonObject("serviceConfiguration")
                .getAsJsonObject("serviceId");
            final String task = serviceId.get("taskName").getAsString();
            final String separator = serviceId.get("nameSplitter").getAsString();
            final int id = serviceId.get("taskServiceId").getAsInt();
            if (task.isBlank() || separator.isEmpty() || id < 0) {
                return null;
            }
            return new Resolved(ServiceIdentity.serviceName(task, separator, id), separator, task);
        } catch (Exception exception) {
            // Deliberately broad: a missing field, a renamed one and a truncated file all mean the
            // same thing here — we do not know this service's name — and each of them must lead to a
            // refusal that says so, never to a default.
            return null;
        }
    }
}
