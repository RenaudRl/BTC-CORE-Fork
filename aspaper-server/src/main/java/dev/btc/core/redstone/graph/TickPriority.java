package dev.btc.core.redstone.graph;

/**
 * Execution order for redstone ticks scheduled in the same game tick.
 *
 * <p>Vanilla resolves simultaneous redstone events through these four tiers, and reproducing them
 * exactly is what keeps a compiled circuit behaving like the uncompiled one. The order below is the
 * execution order: {@link #HIGHEST} runs first.
 */
public enum TickPriority {

    /** A diode pointing straight into another diode. */
    HIGHEST,

    /** A diode that is turning off. */
    HIGHER,

    /** General component-to-component interaction. */
    HIGH,

    /** Everything else. */
    NORMAL;

    public static final TickPriority[] ORDER = values();
}
