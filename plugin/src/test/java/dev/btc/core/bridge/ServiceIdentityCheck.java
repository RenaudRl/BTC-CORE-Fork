/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.util.Set;

/**
 * Dependency-free checks for {@link ServiceIdentity}, in this module's idiom
 * ({@code main} + assertions).
 *
 * <p>This class decides who may move players, so the cases that matter are the ones where a rule
 * would be <em>too generous</em>: a task name that swallows its neighbours, a proxy that reads as a
 * service of a task nobody granted.
 */
public final class ServiceIdentityCheck {

  private static final String SEP = "-";

  private ServiceIdentityCheck() {
  }

  public static void main(final String[] args) {
    check("Lobby".equals(ServiceIdentity.taskOf("Lobby-1", SEP)), "a service belongs to its task");
    check("Lobby".equals(ServiceIdentity.taskOf("Lobby-42", SEP)), "whatever its number");

    // Split on the LAST separator. On the first, `skyadventure-1-2` would read as task
    // `skyadventure`, and an allow-list naming `skyadventure-1` would also let `skyadventure-2` in.
    check("skyadventure-1".equals(ServiceIdentity.taskOf("skyadventure-1-2", SEP)),
        "a task name may itself contain the separator");
    check(!"skyadventure".equals(ServiceIdentity.taskOf("skyadventure-1-2", SEP)),
        "and is never shortened to the part before the first separator");

    // The suffix must be a number, or `btc-proxy` reads as service 'proxy' of a task `btc` — and an
    // allow-list naming `btc` would then admit the proxy under a name nobody granted.
    check(ServiceIdentity.taskOf("btc-proxy", SEP) == null,
        "a name whose suffix is not a number is not a service name");
    check(ServiceIdentity.taskOf("Lobby", SEP) == null, "a bare name has no task");
    check(ServiceIdentity.taskOf("-1", SEP) == null, "and neither has a nameless one");

    check("Lobby-1".equals(ServiceIdentity.serviceName("Lobby", SEP, 1)),
        "the service name is composed exactly as CloudNet composes it");

    final Set<String> parTache = Set.of("skyadventure-1", "Lobby");
    check(ServiceIdentity.covers(parTache, "skyadventure-1-2", SEP),
        "an allow-list naming a task covers that task's services");
    check(ServiceIdentity.covers(parTache, "Lobby", SEP),
        "and still covers an exact name, for a static server with no task");
    check(!ServiceIdentity.covers(parTache, "skyadventure-2-1", SEP),
        "a neighbouring task is NOT covered");
    check(!ServiceIdentity.covers(parTache, "Creatif-1", SEP), "nor an unnamed one");
    check(!ServiceIdentity.covers(parTache, null, SEP), "and nothing covers nothing");

    final Set<String> exact = Set.of("btc-proxy");
    check(ServiceIdentity.covers(exact, "btc-proxy", SEP), "the proxy is named exactly");
    check(!ServiceIdentity.covers(Set.of("btc"), "btc-proxy", SEP),
        "and granting the task `btc` does NOT grant `btc-proxy`");

    System.out.println("ServiceIdentityCheck: all checks passed");
  }

  private static void check(final boolean condition, final String what) {
    if (!condition) {
      throw new AssertionError(what);
    }
  }
}
