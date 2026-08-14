package dev.btc.core.redstone;

import dev.btc.core.config.BTCCoreConfig;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Measures how much tick time redstone actually costs, on both paths, so a compiled circuit can be
 * compared with the same circuit left to Paper's {@code ALTERNATE_CURRENT}.
 *
 * <p>Two counters are accumulated per game tick:
 * <ul>
 *   <li>the <em>compiled</em> path — everything {@link RedstoneCompilerManager#tick()} does;</li>
 *   <li>the <em>vanilla</em> path — every {@code handleNeighborChanged} the world runs, which is
 *       where {@code ALTERNATE_CURRENT} spends its time.</li>
 * </ul>
 *
 * <p>Both are wall-clock nanoseconds on the thread that ticks the world, so they are directly
 * comparable and both convert to an MSPT contribution the same way. Sampling is off by default and
 * every probe is guarded by a single volatile read, so an idle server pays nothing measurable.
 *
 * <p>The benchmark itself is a state machine advanced once per world tick. It runs the circuit
 * compiled, flips the compiler off so the very same blocks fall back to vanilla redstone, runs it
 * again, and reports both figures. That ordering matters: the two phases must see the same circuit
 * in the same world, otherwise the comparison means nothing.
 *
 * <h2>Why updates are counted, not just timed</h2>
 *
 * <p>A duration of zero has two completely different meanings — "the work was too fast to time" and
 * "the work never happened" — and the first benchmarks could not tell them apart. So every probe
 * also counts the neighbour updates it saw, in the measured world <em>and</em> in every other one.
 * Zero updates in the measured world is then reported as an invalid run rather than as a speed-up,
 * and updates piling up in a different world say plainly that the wrong world was benchmarked.
 */
public final class RedstoneProfiler {

    /** Ticks discarded after a phase change, to let the circuit and the JIT reach steady state. */
    private static final int WARMUP_TICKS = 40;

    private RedstoneProfiler() {
    }

    /** Guards every probe. Volatile because the bench is started from the command thread. */
    private static volatile boolean sampling;

    /**
     * The world under measurement. Probes compare against it by identity, which costs a reference
     * comparison, so redstone running in other worlds is counted apart instead of polluting the
     * sample.
     */
    private static volatile net.minecraft.server.level.ServerLevel benchLevel;

    /** Kept separately: the report is written after {@link #benchLevel} has been released. */
    private static volatile String benchWorldName;

    private static long compiledNanos;
    private static long vanillaNanos;
    private static long vanillaUpdates;

    /** Neighbour updates seen in worlds other than the measured one, by world name. */
    private static final Map<String, LongAdder> foreignUpdates = new ConcurrentHashMap<>();

    // --- probes ------------------------------------------------------------------------------

    public static boolean sampling() {
        return sampling;
    }

    /** True when this world is the one being measured. */
    public static boolean samples(final net.minecraft.server.level.ServerLevel level) {
        return sampling && level == benchLevel;
    }

    /** Called by {@link RedstoneCompilerManager#tick()} with the time it just spent. */
    public static void recordCompiled(final long nanos) {
        if (sampling) {
            compiledNanos += nanos;
        }
    }

    /**
     * Called from the NMS neighbour-update funnel, for <em>every</em> world, with the time vanilla
     * redstone just spent. Updates outside the measured world are counted by name and never timed:
     * they are not part of the sample, they are the evidence that the sample may be empty for a
     * reason the operator can act on.
     */
    public static void recordVanilla(final net.minecraft.world.level.Level level, final long nanos) {
        if (!sampling) {
            return;
        }
        if (level == benchLevel) {
            vanillaNanos += nanos;
            vanillaUpdates++;
            return;
        }
        foreignUpdates.computeIfAbsent(level.getWorld().getName(), name -> new LongAdder()).increment();
    }

    private static int peakZones;
    private static int peakNodes;
    private static int attempts;
    private static int refusals;
    private static int created;
    private static int released;
    private static String lastRefusal;

    /**
     * Records how far the compiler actually got. Without this a benchmark cannot be read at all: a
     * circuit that was never compiled produces two vanilla measurements and a meaningless ratio.
     */
    public static void recordEngagement(final int zones, final int nodes,
                                        final int compileAttempts, final int compileRefusals,
                                        final int zonesCreated, final int zonesReleased,
                                        final String refusalReason) {
        if (!sampling) {
            return;
        }
        peakZones = Math.max(peakZones, zones);
        peakNodes = Math.max(peakNodes, nodes);
        attempts = compileAttempts;
        refusals = compileRefusals;
        created = zonesCreated;
        released = zonesReleased;
        lastRefusal = refusalReason;
    }

    // --- benchmark ---------------------------------------------------------------------------

    private enum Phase {
        WARMUP_COMPILED,
        SAMPLE_COMPILED,
        WARMUP_VANILLA,
        SAMPLE_VANILLA
    }

    private static Phase phase;
    private static int remaining;
    private static int sampleTicks;
    private static Consumer<String> reporter;

    private static long compiledPhaseNanos;
    private static long vanillaPhaseNanos;
    private static long compiledResidualNanos;
    private static long compiledPhaseUpdates;
    private static long vanillaPhaseUpdates;

    /** True while a benchmark is running; a second one is refused rather than queued. */
    public static boolean running() {
        return phase != null;
    }

    /**
     * Starts a benchmark.
     *
     * @param ticks  ticks sampled per phase
     * @param sink   receives the progress and result lines, on the world thread
     * @return false when a benchmark is already running
     */
    public static synchronized boolean start(final net.minecraft.server.level.ServerLevel level,
                                             final int ticks, final Consumer<String> sink) {
        if (phase != null) {
            return false;
        }
        if (!BTCCoreConfig.redstoneCompilerEnabled) {
            sink.accept("The redstone compiler is disabled; there is nothing to compare against.");
            return false;
        }
        final String worldName = level.getWorld().getName();
        if (!BTCCoreConfig.isRedstoneCompilerEnabledFor(worldName)) {
            sink.accept("World '" + worldName + "' is not whitelisted for the redstone compiler.");
            return false;
        }
        benchLevel = level;
        benchWorldName = worldName;
        sampleTicks = ticks;
        reporter = sink;
        compiledPhaseNanos = 0;
        vanillaPhaseNanos = 0;
        compiledResidualNanos = 0;
        compiledPhaseUpdates = 0;
        vanillaPhaseUpdates = 0;
        peakZones = 0;
        peakNodes = 0;
        attempts = 0;
        refusals = 0;
        created = 0;
        released = 0;
        lastRefusal = null;
        foreignUpdates.clear();
        enter(Phase.WARMUP_COMPILED, WARMUP_TICKS);
        sink.accept("Benchmark started in world '" + worldName + "': "
            + ticks + " ticks compiled, then " + ticks + " ticks vanilla.");
        return true;
    }

    private static void enter(final Phase next, final int ticks) {
        phase = next;
        remaining = ticks;
        compiledNanos = 0;
        vanillaNanos = 0;
        vanillaUpdates = 0;
        sampling = true;
    }

    /**
     * Closes the previous world tick and advances the benchmark.
     *
     * <p>Called at the <em>top</em> of the measured world's tick, which is the first moment where
     * both counters for the previous tick are complete: the compiled path runs at the head of the
     * world tick, the vanilla neighbour updates run throughout it.
     *
     * <p>Because this is the only thing that advances the state machine, and it is reached only from
     * the {@link RedstoneCompilerManager} owned by {@link #benchLevel}, a benchmark that reaches its
     * report has proven along the way that the level it was handed is the very object being ticked.
     */
    public static void endOfTick(final net.minecraft.server.level.ServerLevel level) {
        if (phase == null || level != benchLevel) {
            return;
        }
        final long compiled = compiledNanos;
        final long vanilla = vanillaNanos;
        final long updates = vanillaUpdates;
        compiledNanos = 0;
        vanillaNanos = 0;
        vanillaUpdates = 0;

        switch (phase) {
            case SAMPLE_COMPILED -> {
                compiledPhaseNanos += compiled;
                compiledResidualNanos += vanilla;
                compiledPhaseUpdates += updates;
            }
            case SAMPLE_VANILLA -> {
                vanillaPhaseNanos += vanilla;
                vanillaPhaseUpdates += updates;
            }
            default -> {
                // warm-up: measured and discarded on purpose
            }
        }

        if (--remaining > 0) {
            return;
        }

        switch (phase) {
            case WARMUP_COMPILED -> enter(Phase.SAMPLE_COMPILED, sampleTicks);
            case SAMPLE_COMPILED -> {
                // Turning the master switch off makes the manager release every zone on its next
                // tick, handing the exact same blocks back to vanilla redstone.
                BTCCoreConfig.redstoneCompilerEnabled = false;
                enter(Phase.WARMUP_VANILLA, WARMUP_TICKS);
            }
            case WARMUP_VANILLA -> enter(Phase.SAMPLE_VANILLA, sampleTicks);
            case SAMPLE_VANILLA -> finish();
        }
    }

    private static void finish() {
        BTCCoreConfig.redstoneCompilerEnabled = true;
        sampling = false;
        final String worldName = benchWorldName;
        // Read before the level is dropped, and read rather than assumed: naming the wrong engine in
        // the report is enough to credit a speed-up to the wrong baseline.
        final String vanillaEngine = benchLevel == null
            ? "unknown"
            : benchLevel.paperConfig().misc.redstoneImplementation.name();
        benchLevel = null;
        benchWorldName = null;
        phase = null;

        final Consumer<String> sink = reporter;
        reporter = null;
        if (sink == null) {
            return;
        }

        final double compiledMspt = toMspt(compiledPhaseNanos + compiledResidualNanos);
        final double vanillaMspt = toMspt(vanillaPhaseNanos);
        final double residualMspt = toMspt(compiledResidualNanos);

        sink.accept(String.format("Compiled: %.4f ms/tick (%.4f of it still spent in vanilla updates)",
                compiledMspt, residualMspt));
        sink.accept(String.format("Vanilla (%s): %.4f ms/tick", vanillaEngine, vanillaMspt));
        sink.accept(String.format("Engagement: %d zone(s), %d node(s); %d compile attempt(s), %d refused; "
                + "%d installed, %d released",
                peakZones, peakNodes, attempts, refusals, created, released));
        if (lastRefusal != null) {
            sink.accept("Last refusal: " + lastRefusal);
        }

        // The live config answer, not a cached flag: the manager only recomputes its own copy once a
        // circuit exists, so reporting that copy is how the first runs claimed a whitelisted world
        // was not whitelisted.
        sink.accept(String.format("World '%s': whitelisted %s (live config); "
                + "%d neighbour update(s) in the compiled phase, %d in the vanilla phase",
                worldName, BTCCoreConfig.isRedstoneCompilerEnabledFor(worldName) ? "yes" : "NO",
                compiledPhaseUpdates, vanillaPhaseUpdates));

        final StringBuilder elsewhere = new StringBuilder();
        for (final Map.Entry<String, LongAdder> entry : foreignUpdates.entrySet()) {
            final long count = entry.getValue().sum();
            if (count == 0L) {
                continue;
            }
            elsewhere.append(elsewhere.isEmpty() ? "" : ", ")
                .append('\'').append(entry.getKey()).append("' ").append(count);
        }
        foreignUpdates.clear();
        if (!elsewhere.isEmpty()) {
            sink.accept("Neighbour updates in other worlds meanwhile: " + elsewhere);
        }

        // A world where nothing happened cannot be compared with anything. This is checked before
        // engagement, because zero updates also explains zero zones.
        if (compiledPhaseUpdates == 0L && vanillaPhaseUpdates == 0L) {
            sink.accept("INVALID: not one neighbour update reached world '" + worldName + "' in "
                    + (2 * sampleTicks) + " sampled ticks, so nothing was measured. Stand next to a "
                    + "circuit that is actually running, with its chunks loaded and ticking"
                    + (elsewhere.isEmpty() ? "." : " — redstone was running in another world instead."));
            return;
        }

        if (peakZones == 0) {
            if (attempts == 0) {
                sink.accept("INVALID: no circuit reached activity-threshold ("
                        + BTCCoreConfig.redstoneCompilerActivityThreshold + " updates per chunk per "
                        + BTCCoreConfig.redstoneCompilerActivityWindowTicks + " ticks), so compilation was never attempted.");
            } else if (created == 0) {
                sink.accept("INVALID: every compile attempt was refused. Use '/btccore redstone probe' "
                        + "while looking at the circuit to see exactly where it leaves the domain.");
            } else {
                // Peak zone count is sampled once per tick, so a zone installed and dropped in
                // between never shows up there. These two counters are the only witness.
                sink.accept("INVALID: the compiler engaged " + created + " time(s) and was thrown out "
                        + released + " time(s), never surviving to a sampled tick. The zone is being "
                        + "invalidated as soon as it is installed, so nothing ever ran compiled.");
            }
            sink.accept("Both phases ran on vanilla redstone; the ratio below would be noise, so it is omitted.");
            return;
        }

        if (residualMspt > compiledMspt * 0.5d) {
            sink.accept("WARNING: most of the compiled phase was still vanilla work — the measured circuit is "
                    + "probably outside the compiled zone.");
        }

        if (vanillaMspt <= 0.0d && compiledMspt <= 0.0d) {
            sink.accept("Both paths measured below the timer resolution despite real updates; sample for longer.");
            return;
        }
        if (compiledMspt <= 0.0d) {
            sink.accept(String.format("Compiled cost is below the timer resolution; vanilla cost %.4f ms/tick.",
                    vanillaMspt));
            return;
        }
        sink.accept(String.format("Speed-up: %.2fx", vanillaMspt / compiledMspt));
    }

    private static double toMspt(final long nanos) {
        return nanos / 1_000_000.0d / Math.max(1, sampleTicks);
    }

    /** Aborts a running benchmark, restoring the compiler switch. Used when the world unloads. */
    public static synchronized void abort() {
        if (phase == null) {
            return;
        }
        BTCCoreConfig.redstoneCompilerEnabled = true;
        sampling = false;
        benchLevel = null;
        benchWorldName = null;
        phase = null;
        reporter = null;
        foreignUpdates.clear();
    }
}
