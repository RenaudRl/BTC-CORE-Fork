package dev.btc.core;

/**
 * BTCCore: contract version shared by the two artifacts of the fork.
 *
 * <p>BTCCore ships as a pair — the server jar ({@code aspaper-paperclip-*.jar}, deployed as
 * {@code purpur.jar}) which owns every {@code dev.btc.core.*} class, and the plugin jar
 * ({@code btccore-plugin-*.jar}) which compiles against them. Nothing forces the two to come from
 * the same build, and a mismatched pair does not fail at load: it fails on the first call, mid-tick,
 * as a {@link NoSuchMethodError} nobody connects back to a deployment mistake. That is exactly how
 * {@code BatchedInventoryUpdates.flushAll()} kept throwing once the batch rewrite dropped it.
 *
 * <p>{@link #CONTRACT} guards against that. The plugin embeds its own copy of the number at compile
 * time and compares it to the one the running server reports; a difference means the two jars did
 * not come from compatible builds, and the plugin refuses to load with a message that says so.
 *
 * <h2>When to bump it</h2>
 * Bump {@code CONTRACT} — here and in {@code BTCCoreContractCheck.EXPECTED_CONTRACT} — whenever the
 * {@code dev.btc.core.*} surface the plugin calls changes incompatibly: a method removed, renamed,
 * or given a different signature. Adding a method the plugin does not call yet needs no bump.
 */
public final class BTCCoreBuild {

    /**
     * Version of the server-side {@code dev.btc.core.*} surface the plugin is allowed to call.
     *
     * <p>History:
     * <ul>
     *   <li>{@code 1} — surface before the batched inventory rewrite
     *       ({@code BatchedInventoryUpdates.flushAll()} still present);</li>
     *   <li>{@code 2} — {@code flushAll()} removed, replaced by the per-player
     *       {@code flush(ServerPlayer)} called from {@code ServerPlayer#doTick}.</li>
     * </ul>
     */
    private static final int CONTRACT = 2;

    /**
     * Returns the contract this server jar implements.
     *
     * <p>A method, not a {@code public static final int}: javac inlines a constant initialised from
     * a literal straight into the caller's constant pool, so the plugin would end up comparing its
     * own compiled-in value against itself and never load this class at all. A method call is
     * resolved against the running server, and its absence on an older jar surfaces as a
     * {@link NoSuchMethodError} the check turns into a readable message.
     */
    public static int contract() {
        return CONTRACT;
    }

    private BTCCoreBuild() {}
}
