/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Immutable bridge security and resource settings, read once at start.
 *
 * <p>Every numeric value is clamped rather than rejected: an operator typing a payload cap of
 * 10 bytes should get the smallest supported cap and a working bridge, not a backend that refuses
 * to start. The identity and the allow-lists are the exception — those decide who may make this
 * server load a world, so a malformed one fails the start.
 *
 * <p>Was read from the {@code BTCBridge} plugin's own config; now read from BTCCore's
 * {@code config.yml} under the same {@code bridge.*} keys, so an existing configuration transfers
 * by moving the section rather than by rewriting it.
 */
record BridgeSettings(String backendId, Set<String> allowedBackends, Set<String> allowedTargets,
                      Set<String> allowedWorlds, BridgeCodec.Limits limits,
                      long deduplicationWindowMillis, int maxDeduplicationEntries,
                      @Nullable String fallbackTarget, String nameSeparator,
                      String identitySource) {

    /**
     * Whether {@code name} may send us control messages.
     *
     * <p>Goes through {@link ServiceIdentity#covers} rather than {@code Set.contains}: an allow-list
     * written in a shared template can only name <em>tasks</em>, because no one can write
     * {@code Lobby-7} before CloudNet has decided there will be a seventh.
     */
    boolean allowsBackend(final @Nullable String name) {
        return ServiceIdentity.covers(allowedBackends, name, nameSeparator);
    }

    /** Whether we accept {@code name} as a destination, by exact name or by task. */
    boolean allowsTarget(final @Nullable String name) {
        return ServiceIdentity.covers(allowedTargets, name, nameSeparator);
    }

    static BridgeSettings from(final JavaPlugin plugin) {
        return from(plugin, System::getenv);
    }

    /**
     * Same reading, with the environment lookup injected.
     *
     * <p>A JVM cannot change its own environment: without this entry point, the {@code ${env:NAME}}
     * references could only be exercised on a real server.
     */
    static BridgeSettings from(final JavaPlugin plugin, final UnaryOperator<String> environment) {
        // The server's own directory: plugins/BTCCore -> plugins -> here. Deterministic, unlike the
        // world container, which an operator may point elsewhere.
        final CloudNetServiceIdentity.Resolved orchestrated = CloudNetServiceIdentity.from(
            plugin.getDataFolder().toPath().getParent().getParent());
        final String configuredId = plugin.getConfig().getString("bridge.backend-id");
        final String declared = normalize(BridgeEnvironment.resolve(configuredId, environment));

        // What the operator wrote wins, always: an orchestrator is a convenience, not an authority,
        // and a server moved out of CloudNet must keep the name the rest of the network knows.
        final String backendId = declared != null ? declared
            : orchestrated != null ? orchestrated.serviceName() : null;
        final String identitySource = declared != null ? "config.yml"
            : orchestrated != null ? "CloudNet (" + orchestrated.task() + ")" : "none";
        final String separator = orchestrated != null ? orchestrated.separator() : DEFAULT_SEPARATOR;
        if (backendId == null || backendId.length() > 128) {
            throw new IllegalStateException(describeMissingIdentity(configuredId));
        }
        final Set<String> allowedBackends = configuredSet(plugin, "bridge.allowed-backends", Set.of(), environment);
        final Set<String> configuredTargets = configuredSet(plugin, "bridge.allowed-targets", Set.of(), environment);
        final Set<String> allowedTargets = configuredTargets.isEmpty() ? Set.of(backendId) : configuredTargets;
        final Set<String> allowedWorlds = configuredSet(plugin, "bridge.allowed-worlds", Set.of(), environment);
        final BridgeCodec.Limits limits = new BridgeCodec.Limits(
            boundedInt(plugin.getConfig().getInt("bridge.max-payload-bytes", 32 * 1024), 1024,
                1024 * 1024),
            boundedInt(plugin.getConfig().getInt("bridge.max-string-length", 128), 16, 4096),
            boundedInt(plugin.getConfig().getInt("bridge.max-world-name-length", 64), 16, 256),
            boundedInt(plugin.getConfig().getInt("bridge.max-username-length", 32), 1, 128),
            boundedInt(plugin.getConfig().getInt("bridge.max-party-members", 64), 1, 256),
            boundedInt(plugin.getConfig().getInt("bridge.max-loaded-worlds", 32), 1, 256),
            boundedLong(plugin.getConfig().getLong("bridge.max-message-lifetime-ms", 300_000L),
                1_000L, 3_600_000L),
            boundedLong(plugin.getConfig().getLong("bridge.max-clock-skew-ms", 30_000L), 0L, 300_000L));
        // Absent means "no second chance", not "retry somewhere sensible": a fallback nobody
        // declared is a destination nobody checked, and sending refused players to it would be a
        // guess made at the worst possible moment — while the server is ending.
        final String fallbackTarget = normalize(
            BridgeEnvironment.resolve(plugin.getConfig().getString("bridge.fallback-target"), environment));
        return new BridgeSettings(backendId, allowedBackends, allowedTargets, allowedWorlds, limits,
            boundedLong(plugin.getConfig().getLong("bridge.dedup-window-ms", 120_000L), 1_000L,
                900_000L),
            boundedInt(plugin.getConfig().getInt("bridge.max-dedup-entries", 1024), 16, 8192),
            fallbackTarget == null || fallbackTarget.length() > 128 ? null : fallbackTarget,
            separator, identitySource);
    }

    /** CloudNet's own default, used when nothing orchestrates this server. */
    private static final String DEFAULT_SEPARATOR = "-";

    /**
     * What to call this backend when the bridge is not running.
     *
     * <p>Deliberately <em>not</em> an identity. {@link Bukkit#getName()} returns the name of the
     * server <em>implementation</em> — {@code BTC-CORE} here — so it is the same string on every
     * backend of the network. It used to serve as the default for {@code bridge.backend-id}, which
     * meant two servers could introduce themselves under one name and silently share a slot in the
     * proxy's health registry. It is now only ever shown to a human, never put on the wire.
     */
    static String fallbackBackendId() {
        final String name = Bukkit.getName();
        return name == null || name.isBlank() || "Unknown Server".equals(name) ? "backend" : name;
    }

    /**
     * Says why there is no identity, naming the variable when one was asked for.
     *
     * <p>An unresolved reference left as written reads like a deliberate name, and the refusal
     * would then accuse {@code bridge.backend-id} for a variable the orchestrator forgot to pass.
     */
    private static String describeMissingIdentity(final @Nullable String configured) {
        final String variable = BridgeEnvironment.referencedVariable(configured);
        if (variable != null) {
            return "bridge.backend-id names environment variable " + variable + ", which is not set";
        }
        if (normalize(configured) == null) {
            // No invented default: an identity shared by every backend is worse than no bridge,
            // because the proxy cannot tell two servers apart and says nothing about it.
            return "bridge.backend-id is not set and no orchestrator names this service; "
                + "the bridge needs a name unique to this server";
        }
        return "bridge.backend-id is invalid";
    }

    private static Set<String> configuredSet(final JavaPlugin plugin, final String key,
                                             final Set<String> fallback,
                                             final UnaryOperator<String> environment) {
        final List<String> raw = plugin.getConfig().getStringList(key);
        if (raw.isEmpty()) return fallback;
        final List<String> values = BridgeEnvironment.resolveAll(raw, environment);
        if (values.isEmpty()) return fallback;
        final LinkedHashSet<String> normalized = values.stream().map(BridgeSettings::normalize)
            .filter(value -> value != null && value.length() <= 128)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(normalized);
    }

    private static String normalize(final String value) {
        if (value == null) return null;
        final String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static int boundedInt(final int value, final int minimum, final int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long boundedLong(final long value, final long minimum, final long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
