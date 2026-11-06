/*
 * Copyright (C) 2026 BTC Studio. All rights reserved.
 * Licensed under GPLv3.
 */

package dev.btc.core.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Dependency-free checks for {@link PlatformBinding}, in this module's idiom
 * ({@code main} + assertions). The binding must come from what the source states about the session,
 * never from the shape of the UUID.
 */
public final class SessionPlatformBinderCheck {

  private static final UUID FLOODGATE_SHAPED = UUID.fromString("00000000-0000-0000-0009-000000000001");

  private SessionPlatformBinderCheck() {
  }

  /** A source whose answer is fixed by the check. */
  private static PlatformSource source(final BridgeMessage.Platform answer) {
    return new PlatformSource() {
      @Override
      public boolean canResolve() {
        return true;
      }

      @Override
      public Optional<BridgeMessage.Platform> platformOf(final UUID player) {
        return Optional.ofNullable(answer);
      }

      @Override
      public String describe() {
        return "test-source";
      }
    };
  }

  private record Binding(UUID player, BridgeMessage.Platform platform, String source) {
  }

  public static void main(final String[] args) {
    final List<Binding> bound = new ArrayList<>();
    final Logger logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);

    new PlatformBinding(source(BridgeMessage.Platform.BEDROCK),
        (p, platform, s) -> bound.add(new Binding(p, platform, s)), logger)
        .bind(UUID.randomUUID(), "Alex");
    check(bound.size() == 1 && bound.get(0).platform() == BridgeMessage.Platform.BEDROCK
            && "test-source".equals(bound.get(0).source()),
        "a BEDROCK session is bound BEDROCK with the source description");

    bound.clear();
    new PlatformBinding(source(BridgeMessage.Platform.JAVA),
        (p, platform, s) -> bound.add(new Binding(p, platform, s)), logger)
        .bind(UUID.randomUUID(), "Steve");
    check(bound.size() == 1 && bound.get(0).platform() == BridgeMessage.Platform.JAVA
            && "test-source".equals(bound.get(0).source()),
        "a JAVA session is bound JAVA with the source description");

    bound.clear();
    new PlatformBinding(source(null),
        (p, platform, s) -> bound.add(new Binding(p, platform, s)), logger)
        .bind(UUID.randomUUID(), "Ghost");
    check(bound.isEmpty(), "a source with no answer binds nothing");

    new PlatformBinding(PlatformSource.UNAVAILABLE,
        (p, platform, s) -> bound.add(new Binding(p, platform, s)), logger)
        .bind(UUID.randomUUID(), "Nobody");
    check(bound.isEmpty(), "an unavailable source binds nothing, it never falls back on JAVA");

    // The source says JAVA about a Floodgate-shaped UUID: the binder must repeat it, not infer.
    new PlatformBinding(source(BridgeMessage.Platform.JAVA),
        (p, platform, s) -> bound.add(new Binding(p, platform, s)), logger)
        .bind(FLOODGATE_SHAPED, "Imitator");
    check(bound.size() == 1 && bound.get(0).platform() == BridgeMessage.Platform.JAVA,
        "the UUID shape is not read: what the source states is what gets bound");

    final List<Level> logged = new ArrayList<>();
    final Logger recording = Logger.getAnonymousLogger();
    recording.setUseParentHandlers(false);
    recording.addHandler(new Handler() {
      @Override
      public void publish(final LogRecord record) {
        logged.add(record.getLevel());
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    });

    // (a) declared + Floodgate absent: JAVA, with the operator description.
    bound.clear();
    new PlatformBinding(PlatformBinding.choose(true, PlatformSource.UNAVAILABLE, recording),
        (p, platform, s) -> bound.add(new Binding(p, platform, s)), logger)
        .bind(UUID.randomUUID(), "Steve");
    check(bound.size() == 1 && bound.get(0).platform() == BridgeMessage.Platform.JAVA
            && "operator: java-only-network".equals(bound.get(0).source()),
        "java-only declared without Floodgate binds JAVA with the operator source");
    check(!logged.contains(Level.WARNING), "an accepted declaration raises no WARN");

    // (b) declared + Floodgate present: refused, Floodgate decides, a BEDROCK stays BEDROCK.
    bound.clear();
    logged.clear();
    new PlatformBinding(PlatformBinding.choose(true, source(BridgeMessage.Platform.BEDROCK), recording),
        (p, platform, s) -> bound.add(new Binding(p, platform, s)), logger)
        .bind(UUID.randomUUID(), "Alex");
    check(bound.size() == 1 && bound.get(0).platform() == BridgeMessage.Platform.BEDROCK
            && "test-source".equals(bound.get(0).source()),
        "java-only declared next to Floodgate is refused: a BEDROCK session stays BEDROCK");
    check(logged.contains(Level.WARNING), "the refused declaration is announced as a WARN");

    // (c) not declared + Floodgate absent: nothing bound, as before.
    bound.clear();
    new PlatformBinding(PlatformBinding.choose(false, PlatformSource.UNAVAILABLE, recording),
        (p, platform, s) -> bound.add(new Binding(p, platform, s)), logger)
        .bind(UUID.randomUUID(), "Nobody");
    check(bound.isEmpty(), "no declaration and no Floodgate binds nothing");

    System.out.println("SessionPlatformBinderCheck: OK");
  }

  private static void check(final boolean condition, final String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
