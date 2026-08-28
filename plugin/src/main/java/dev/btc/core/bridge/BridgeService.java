/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The backend side of the BTCVelocity bridge, run as a service of the BTCCore plugin.
 *
 * <p>Was a plugin of its own ({@code BTCBridge}) until the runtime was unified. It is a service and
 * not a second {@link JavaPlugin} because two plugins meant two lifecycles to keep in step, and the
 * bridge only ever made sense alongside the world runtime it reports on.
 *
 * <p>Off by default in practice: on {@code auto} the channel is opened only when this server really
 * is behind a Velocity proxy. A standalone backend therefore registers nothing and schedules
 * nothing — BTCCore must boot and run with no proxy in sight.
 */
public final class BridgeService {

    static final String CHANNEL = "btc:bridge";

    /**
     * How long a response stays valid on the wire.
     *
     * <p>Not configurable: it bounds our own replies, so a proxy and a backend disagreeing on it
     * would show up as messages silently expiring in flight.
     */
    private static final long RESPONSE_LIFETIME_MILLIS = 30_000L;

    /** Health reports every 10 s, after a 5 s delay so the first one sees a settled server. */
    private static final long HEALTH_DELAY_TICKS = 100L;
    private static final long HEALTH_PERIOD_TICKS = 200L;

    private final JavaPlugin host;

    private @Nullable BridgeSettings settings;
    private @Nullable BridgeMessageHandler handler;
    private @Nullable ScheduledTask healthTask;

    public BridgeService(final JavaPlugin host) {
        this.host = host;
    }

    /**
     * Opens the bridge when this server is configured for it.
     *
     * <p>A configuration error disables the bridge and leaves the rest of the plugin running: a bad
     * {@code backend-id} must not stop worlds from loading.
     *
     * @return {@code true} when the bridge was started
     */
    public boolean start() {
        host.saveDefaultConfig();
        warnAboutLegacyConfig();
        final String rawMode = host.getConfig().getString("bridge.mode", "auto");
        Mode mode = Mode.from(rawMode);
        if (mode == null) {
            host.getLogger().warning("Unknown bridge.mode '" + rawMode + "'; falling back to auto");
            mode = Mode.AUTO;
        }
        final Mode resolved = mode;
        if (!resolved.enables(this::velocityDetected)) {
            host.getLogger().info(() -> "Bridge disabled (mode "
                + resolved.name().toLowerCase(Locale.ROOT) + "); running standalone");
            return false;
        }

        try {
            settings = BridgeSettings.from(host);
        } catch (IllegalStateException exception) {
            host.getLogger().warning("Bridge not started: " + exception.getMessage());
            return false;
        }

        handler = new BridgeMessageHandler(host, settings);
        host.getServer().getMessenger().registerIncomingPluginChannel(host, CHANNEL, handler);
        host.getServer().getMessenger().registerOutgoingPluginChannel(host, CHANNEL);
        healthTask = host.getServer().getGlobalRegionScheduler().runAtFixedRate(
            host, task -> handler.sendHealthToProxy(), HEALTH_DELAY_TICKS, HEALTH_PERIOD_TICKS);

        final String backendId = settings.backendId();
        host.getLogger().info(() -> "Bridge enabled for backend " + backendId);
        if (settings.allowedBackends().isEmpty()) {
            host.getLogger().warning("Bridge has no allowed-backends: every control message will be "
                + "refused until config.yml lists the proxy");
        }
        return true;
    }

    /** Closes the bridge. Safe to call when it never started. */
    public void stop() {
        if (healthTask != null) {
            healthTask.cancel();
            healthTask = null;
        }
        if (handler != null) {
            host.getServer().getMessenger().unregisterIncomingPluginChannel(host, CHANNEL, handler);
            host.getServer().getMessenger().unregisterOutgoingPluginChannel(host, CHANNEL);
            handler = null;
        }
        settings = null;
    }

    /** The backend identity, or the server name when the bridge is not running. */
    public String backendId() {
        final BridgeSettings current = settings;
        return current == null ? BridgeSettings.fallbackBackendId() : current.backendId();
    }

    static long responseLifetimeMillis() {
        return RESPONSE_LIFETIME_MILLIS;
    }

    /**
     * Points at a leftover {@code BTCBridge} configuration.
     *
     * <p>Deliberately loud and deliberately inert. The keys are identical on both sides, so reading
     * the old file would "just work" — and that is exactly the failure we do not want: a server
     * would keep running on a configuration nobody can find, in a directory belonging to a plugin
     * that no longer exists. Moving it is the operator's call, and this makes it impossible to miss.
     */
    private void warnAboutLegacyConfig() {
        final Path legacy = host.getDataFolder().toPath().getParent().resolve("BTCBridge")
            .resolve("config.yml");
        if (!Files.isRegularFile(legacy)) {
            return;
        }
        // ASCII only: the server console mangles non-ASCII punctuation into '?', and a warning the
        // operator must act on should not be the place where that shows up.
        host.getLogger().warning("Found a leftover BTCBridge configuration at " + legacy
            + ". It is NOT read any more: move its bridge section into "
            + host.getDataFolder().toPath().resolve("config.yml")
            + " (the keys are unchanged), then delete the old directory.");
    }

    /**
     * Whether this server is behind a Velocity proxy.
     *
     * <p>Read straight off Paper's global configuration rather than by looking for a plugin or a
     * channel: modern forwarding is the thing that actually makes a proxy present, and a plugin
     * being installed proves nothing. Compiled against the fork's own configuration class, so a
     * field that moves breaks the build instead of silently reporting "no proxy".
     */
    private boolean velocityDetected() {
        try {
            return GlobalConfiguration.get().proxies.velocity.enabled;
        } catch (RuntimeException exception) {
            // Only reachable if the bridge is started before the server configuration is read,
            // which would be a wiring mistake on our side rather than an operator's.
            host.getLogger().warning("Could not read the proxy configuration; assuming no proxy");
            return false;
        }
    }

    /** When the bridge should open. */
    private enum Mode {
        AUTO, ON, OFF;

        /** @return the mode, or {@code null} when the value is not one we know */
        static @Nullable Mode from(final @Nullable String raw) {
            if (raw == null) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "on", "true", "always" -> ON;
                case "off", "false", "never" -> OFF;
                default -> null;
            };
        }

        boolean enables(final java.util.function.BooleanSupplier proxyDetected) {
            return switch (this) {
                case ON -> true;
                case OFF -> false;
                case AUTO -> proxyDetected.getAsBoolean();
            };
        }
    }
}
