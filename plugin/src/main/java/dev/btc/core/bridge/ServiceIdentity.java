/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * How a backend is named on the bridge, and what an allow-list entry is allowed to mean.
 *
 * <p>Two rules, and they are not the same rule.
 *
 * <p><b>Identity is a service.</b> Under an orchestrator a backend is one instance among several of
 * the same kind, and the proxy registers each of them separately — {@code Lobby-1}, {@code Lobby-2}.
 * That registered name is the only one the proxy can route to, so it is the only one worth putting on
 * the wire. Naming a backend after its task instead would let two live servers introduce themselves
 * under one name and share a slot in the proxy's health registry, which is the failure this class
 * exists to close.
 *
 * <p><b>Authorisation is a task.</b> A service name is born and dies with its service; nobody can
 * write {@code Lobby-7} into a template before CloudNet has decided there will be a seventh. An
 * allow-list therefore names <em>tasks</em> — what kind of server may talk to us, and what kind we
 * may send players to — and this class maps a concrete service back to the task it belongs to. An
 * exact service name is still accepted, for a static server that has no task.
 *
 * <p>Pure: no Bukkit, no CloudNet, no file. Where the name comes from is
 * {@link CloudNetServiceIdentity}'s problem.
 */
final class ServiceIdentity {

    private ServiceIdentity() {
    }

    /**
     * The task a service belongs to, or {@code null} when the name is not a service name.
     *
     * <p>Split on the <em>last</em> separator, not the first: {@code skyadventure-1-2} belongs to the
     * task {@code skyadventure-1}, which itself contains the separator. Splitting on the first would
     * quietly authorise every task whose name starts with {@code skyadventure}.
     *
     * <p>The suffix must be a number. Without that, {@code btc-proxy} would read as service 'proxy'
     * of a task named {@code btc}, and an allow-list naming {@code btc} would let the proxy in under
     * a name nobody granted.
     */
    static @Nullable String taskOf(final @Nullable String serviceName, final String separator) {
        if (serviceName == null || separator.isEmpty()) return null;
        final int cut = serviceName.lastIndexOf(separator);
        if (cut <= 0) return null;
        final String suffix = serviceName.substring(cut + separator.length());
        if (suffix.isEmpty() || !suffix.chars().allMatch(Character::isDigit)) return null;
        return serviceName.substring(0, cut);
    }

    /** The service name CloudNet composes for the {@code id}-th service of {@code task}. */
    static String serviceName(final String task, final String separator, final int id) {
        return task + separator + id;
    }

    /**
     * Whether {@code name} is covered by {@code allowed}, by exact name or by the task it belongs to.
     *
     * <p>Only these two. No prefix, no wildcard: a rule that matches by shape rather than by name is
     * a rule nobody can read back and be sure of, and this one decides who may move players.
     */
    static boolean covers(final Set<String> allowed, final @Nullable String name,
                          final String separator) {
        if (name == null) return false;
        if (allowed.contains(name)) return true;
        final String task = taskOf(name, separator);
        return task != null && allowed.contains(task);
    }
}
