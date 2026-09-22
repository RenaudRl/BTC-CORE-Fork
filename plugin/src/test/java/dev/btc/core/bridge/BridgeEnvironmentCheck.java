/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Dependency-free checks for {@link BridgeEnvironment}, in this module's idiom
 * ({@code main} + assertions).
 *
 * <p>What is at stake is not a convenience. One CloudNet template is shared by every task, and a
 * service is handed no name of its own, so {@code bridge.backend-id} can only come from the
 * orchestrator. If a reference silently stayed unresolved, the backend would introduce itself to the
 * proxy as the literal string {@code ${env:...}} — a name the proxy has never heard of, refused at
 * the far end, with nothing on this side pointing at the cause.
 */
public final class BridgeEnvironmentCheck {

  private static final UnaryOperator<String> ENVIRONMENT = Map.of(
      "BTC_BRIDGE_BACKEND_ID", "Lobby",
      "BTC_BRIDGE_PROXY_ID", "btc-proxy",
      "BTC_BRIDGE_EMPTY", "")::get;

  private BridgeEnvironmentCheck() {
  }

  public static void main(final String[] args) {
    check("Lobby".equals(BridgeEnvironment.resolve("${env:BTC_BRIDGE_BACKEND_ID}", ENVIRONMENT)),
        "a value that is entirely a reference is read from the environment");

    check("btc".equals(BridgeEnvironment.resolve("btc", ENVIRONMENT)),
        "an ordinary value is rendered untouched");

    // A reference buried in a larger value would be a partial substitution: half a name is harder
    // to read at the far end than a name that is missing.
    check("lobby-${env:BTC_BRIDGE_BACKEND_ID}"
            .equals(BridgeEnvironment.resolve("lobby-${env:BTC_BRIDGE_BACKEND_ID}", ENVIRONMENT)),
        "a reference buried in a larger value is not a reference");

    check(BridgeEnvironment.resolve("${env:BTC_BRIDGE_ABSENT}", ENVIRONMENT) == null,
        "an unset variable resolves to nothing, never to the literal reference");
    check(BridgeEnvironment.resolve("${env:BTC_BRIDGE_EMPTY}", ENVIRONMENT) == null,
        "a variable defined but empty counts as unset");

    // Otherwise a value coming from the environment could steer which other variable gets read.
    final UnaryOperator<String> chained =
        name -> "FIRST".equals(name) ? "${env:SECOND}" : "reached";
    check("${env:SECOND}".equals(BridgeEnvironment.resolve("${env:FIRST}", chained)),
        "a resolved value is never resolved again");

    final List<String> resolved = BridgeEnvironment.resolveAll(
        List.of("${env:BTC_BRIDGE_PROXY_ID}", "btc-proxy-de-secours", "${env:BTC_BRIDGE_ABSENT}"),
        ENVIRONMENT);
    check(resolved.equals(List.of("btc-proxy", "btc-proxy-de-secours")),
        "an allow-list drops the entries whose variable is unset, and keeps the others");
    check(BridgeEnvironment.resolveAll(List.of("${env:BTC_BRIDGE_ABSENT}"), ENVIRONMENT).isEmpty(),
        "an allow-list nothing resolves in comes back empty, which REFUSES rather than lets through");

    check("BTC_BRIDGE_ABSENT".equals(BridgeEnvironment.referencedVariable("${env:BTC_BRIDGE_ABSENT}")),
        "the refusal can name the variable that was asked for");
    check(BridgeEnvironment.referencedVariable("btc") == null,
        "and says nothing about a value that never named one");

    System.out.println("BridgeEnvironmentCheck: all checks passed");
  }

  private static void check(final boolean condition, final String what) {
    if (!condition) {
      throw new AssertionError(what);
    }
  }
}
