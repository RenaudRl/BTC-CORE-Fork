/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
                      long deduplicationWindowMillis, int maxDeduplicationEntries) {

    static BridgeSettings from(final JavaPlugin plugin) {
        final String fallbackId = fallbackBackendId();
        final String backendId = normalize(plugin.getConfig().getString("bridge.backend-id", fallbackId));
        if (backendId == null || backendId.length() > 128) {
            throw new IllegalStateException("bridge.backend-id is invalid");
        }
        final Set<String> allowedBackends = configuredSet(plugin, "bridge.allowed-backends", Set.of());
        final Set<String> configuredTargets = configuredSet(plugin, "bridge.allowed-targets", Set.of());
        final Set<String> allowedTargets = configuredTargets.isEmpty() ? Set.of(backendId) : configuredTargets;
        final Set<String> allowedWorlds = configuredSet(plugin, "bridge.allowed-worlds", Set.of());
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
        return new BridgeSettings(backendId, allowedBackends, allowedTargets, allowedWorlds, limits,
            boundedLong(plugin.getConfig().getLong("bridge.dedup-window-ms", 120_000L), 1_000L,
                900_000L),
            boundedInt(plugin.getConfig().getInt("bridge.max-dedup-entries", 1024), 16, 8192));
    }

    static String fallbackBackendId() {
        final String name = Bukkit.getName();
        return name == null || name.isBlank() || "Unknown Server".equals(name) ? "backend" : name;
    }

    private static Set<String> configuredSet(final JavaPlugin plugin, final String key,
                                             final Set<String> fallback) {
        final List<String> values = plugin.getConfig().getStringList(key);
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
