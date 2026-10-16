package dev.btc.core.mining;

import dev.btc.core.api.mining.BreakDecision;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The arithmetic of the break-speed hook, kept free of server types so it can be tested on its own.
 *
 * <p>{@link BlockBreakSpeedRegistry} reads the player, the block and the attribute; every decision it
 * takes from those readings lives here: which factor wins between the platform tables and the bound
 * provider, whether the answer is a refusal, and whether the client has to be told.
 */
final class BreakSpeedFactor {

    private BreakSpeedFactor() {
    }

    /** Two factors closer than this are the same factor: no packet is worth sending for the gap. */
    static final double SAME_FACTOR_EPSILON = 1.0e-9;

    /**
     * What the hook decided for one block.
     *
     * @param factor  the final multiplier written to {@code minecraft:block_break_speed}, never
     *                negative and never {@code NaN}
     * @param refused whether the provider refused the block outright (a factor of zero or less): the
     *                caller must then stop the dig before it starts
     */
    record Resolution(double factor, boolean refused) {
    }

    /**
     * Whether a value handed back by a provider can be used as a factor.
     *
     * <p>{@code NaN} and the infinities are rejected rather than clamped: a provider that divides by
     * a zero hardness has a bug, and writing its answer to the attribute would turn a whole mining
     * session into a stuck or instant one. The caller declines the answer and says so.
     */
    static boolean isUsable(final double providerFactor) {
        return Double.isFinite(providerFactor);
    }

    /**
     * Asks the provider without letting its failure reach the packet handler.
     *
     * <p>The provider belongs to another plugin and runs inside the handler of the player's packet:
     * an exception that escapes is a disconnected player, for a bug that is not theirs. Anything it
     * throws is therefore reported to {@code onFailure} and answered as a decline, so the platform
     * tables apply and the dig goes on. A {@code null} answer is a decline too.
     *
     * <p>Only a {@link VirtualMachineError} (out of memory, stack overflow, internal error) is
     * rethrown: the JVM is no longer in a state where carrying on is honest.
     *
     * @param ask       the call to the provider
     * @param onFailure told what was thrown; it must not throw itself
     */
    static OptionalDouble askProvider(final Supplier<OptionalDouble> ask, final Consumer<Throwable> onFailure) {
        return ask(ask, onFailure, OptionalDouble.empty());
    }

    /**
     * Asks the provider for its decision, with the same protection as {@link #askProvider}: a throw
     * or a {@code null} is a {@link BreakDecision.Declined}.
     */
    static BreakDecision askDecision(final Supplier<BreakDecision> ask, final Consumer<Throwable> onFailure) {
        return ask(ask, onFailure, BreakDecision.declined());
    }

    private static <T> T ask(final Supplier<T> ask, final Consumer<Throwable> onFailure, final T decline) {
        try {
            final T answer = ask.get();
            return answer == null ? decline : answer;
        } catch (final VirtualMachineError fatal) {
            throw fatal;
        } catch (final Throwable failure) {
            onFailure.accept(failure);
            return decline;
        }
    }

    /** What the platform does with one decision. */
    enum Kind {
        /** Write {@link Verdict#factor()} into the attribute; the client digs on its own. */
        FACTOR,
        /** Stop the dig before it starts. */
        REFUSED,
        /** Measure the dig on the server at {@link Verdict#progressPerTick()}. */
        SERVER_DRIVEN
    }

    /** Why a decision was not followed as given, for a warning logged once. */
    enum Warning {
        NONE,
        /** {@code NaN} or an infinity: declined. */
        UNUSABLE_NUMBER,
        /** A server-driven dig asked for a block, player or server where it is not allowed: declined. */
        SERVER_DRIVEN_NOT_ALLOWED
    }

    /**
     * The platform's verdict on one decision.
     *
     * @param kind            what to do
     * @param factor          the attribute factor: the decided one, the tables on a decline, 0 on a
     *                        refusal, 1 for a server-driven dig (the attribute then carries no
     *                        platform factor, the rate does)
     * @param progressPerTick the server-driven rate before the environment, {@code NaN} otherwise
     * @param warning         why the decision was not followed as given
     */
    record Verdict(Kind kind, double factor, double progressPerTick, Warning warning) {

        boolean refused() {
            return this.kind == Kind.REFUSED;
        }
    }

    /**
     * Turns a provider's decision into what the platform does.
     *
     * <ul>
     *   <li>a decline (or {@code null}) keeps the tables;</li>
     *   <li>a factor follows {@link #resolve}: above zero it replaces the tables, zero or less
     *       refuses, a non-number is declined;</li>
     *   <li>a server-driven rate of zero or less refuses — a dig that never ends is a refusal that
     *       costs a crack — a non-number is declined, and a rate where the server may not drive the
     *       dig is declined too, so the block behaves as it always did;</li>
     * </ul>
     *
     * @param table               the factor the platform tables resolved
     * @param decision            the provider's decision
     * @param serverDrivenAllowed whether this player and this block state may be driven by the server
     *                            (switch on, player not opted out, vanilla block of hardness 0)
     */
    static Verdict decide(final double table, final BreakDecision decision, final boolean serverDrivenAllowed) {
        if (decision == null) return new Verdict(Kind.FACTOR, table, Double.NaN, Warning.NONE);
        return switch (decision) {
            case BreakDecision.Declined declined -> new Verdict(Kind.FACTOR, table, Double.NaN, Warning.NONE);
            case BreakDecision.Refused refused -> new Verdict(Kind.REFUSED, 0.0, Double.NaN, Warning.NONE);
            case BreakDecision.Factor factor -> {
                final Resolution resolution = resolve(table, OptionalDouble.of(factor.factor()));
                final Warning warning = isUsable(factor.factor()) ? Warning.NONE : Warning.UNUSABLE_NUMBER;
                yield new Verdict(resolution.refused() ? Kind.REFUSED : Kind.FACTOR, resolution.factor(), Double.NaN, warning);
            }
            case BreakDecision.ServerDriven driven -> {
                final double rate = driven.progressPerTick();
                if (!isUsable(rate)) yield new Verdict(Kind.FACTOR, table, Double.NaN, Warning.UNUSABLE_NUMBER);
                if (rate <= 0.0) yield new Verdict(Kind.REFUSED, 0.0, Double.NaN, Warning.NONE);
                if (!serverDrivenAllowed) yield new Verdict(Kind.FACTOR, table, Double.NaN, Warning.SERVER_DRIVEN_NOT_ALLOWED);
                yield new Verdict(Kind.SERVER_DRIVEN, 1.0, rate, Warning.NONE);
            }
        };
    }

    /**
     * Whether the server may drive the dig of a block state.
     *
     * <p>Only a vanilla block of hardness exactly 0. The hardness is the reason the mode exists — any
     * other block already takes the time its attribute gives it. The namespace keeps the platform out
     * of blocks another plugin breaks itself: CraftEngine registers its blocks as real server states
     * under {@code craftengine:} and finishes their digs on its own, and two finishers on one block is
     * a block broken twice.
     *
     * @param switchOn  {@code mining.server-driven.enabled} (and break speed) set
     * @param optedOut  the player is kept out of the mode
     * @param namespace the namespace of the block's registry key
     * @param hardness  the hardness of the server state
     */
    static boolean serverDrivenAllowed(final boolean switchOn, final boolean optedOut, final String namespace,
                                       final float hardness) {
        return switchOn && !optedOut && "minecraft".equals(namespace) && hardness == 0.0F;
    }

    /**
     * Whether the platform may finish a dig on this block itself (the server-driven mode finishes
     * every dig, the client sending no {@code STOP} under the zero view) — never for a block of
     * another plugin's namespace, which finishes its own digs.
     */
    static boolean platformMayFinish(final String namespace) {
        return !"craftengine".equals(namespace);
    }

    /**
     * Whether the server clock may drive and finish the dig of a block: it is in the published set and
     * the platform may finish it. Any other block — leaves, hay, dirt, even with a hoe in hand — keeps
     * the vanilla / BTC dig, so the mode is inert outside the published crops.
     *
     * @param inPublishedSet the block state is one the plugin published ({@code blocks} or {@code states})
     * @param namespace      the namespace of the block's registry key
     */
    static boolean clockMayDrive(final boolean inPublishedSet, final String namespace) {
        return inPublishedSet && platformMayFinish(namespace);
    }

    /**
     * Whether the client is shown the zero view this tick: only while the player aims at a published
     * crop (or next to one), and never while a dig of a block outside the set is under way — the client
     * of that dig predicts it itself and the clock would not finish it. A held item arms nothing.
     */
    static boolean zeroViewWanted(final boolean aimsAtPublishedBlock, final boolean digsOutsideSet) {
        return aimsAtPublishedBlock && !digsOutsideSet;
    }

    /**
     * Whether this is the first failure seen from a provider class, so the journal says it once.
     *
     * @param reported the classes already reported, safe for concurrent use
     */
    static boolean isFirstFailureOf(final Set<String> reported, final Class<?> providerClass) {
        return reported.add(providerClass.getName());
    }

    /**
     * Picks the factor for one block.
     *
     * <p><b>A provider's factor replaces the tables, it does not multiply them.</b> The bound
     * provider carries the whole curve — affinity, hardness and tier — so multiplying by the platform
     * tables again would count the same thing twice. The tables apply alone only when no provider is
     * bound, or when the bound one declines ({@link OptionalDouble#empty()}).
     *
     * <p>A factor of zero or less is a refusal: the factor written is exactly {@code 0.0}, the
     * progression of every tick is nil, and {@link Resolution#refused()} is set so the caller can
     * stop the dig at the first hit instead of letting the server draw a first crack.
     *
     * @param table    the factor the platform tables resolved, {@code 1.0} meaning no change
     * @param provided the provider's answer, or empty when no provider is bound or it declined; an
     *                 unusable value (see {@link #isUsable}) counts as a decline
     */
    static Resolution resolve(final double table, final OptionalDouble provided) {
        if (provided.isPresent() && isUsable(provided.getAsDouble())) {
            final double factor = provided.getAsDouble();
            if (factor <= 0.0) return new Resolution(0.0, true);
            return new Resolution(factor, false);
        }
        return new Resolution(table, false);
    }

    /**
     * The amount of the {@code ADD_MULTIPLIED_TOTAL} modifier that yields a final factor.
     *
     * <p>That operation scales the finished value, so a factor of zero is an amount of {@code -1.0}.
     */
    static double modifierAmount(final double factor) {
        return factor - 1.0;
    }

    /**
     * Whether the client must be sent the attribute for a new factor.
     *
     * <p>The modifier the platform owns sits on the player's own attribute instance, which the client
     * is always resynchronised from; its amount is therefore the last factor the client was given.
     * Sending the same one again at every {@code START_DESTROY} is the whole cost of a client that
     * spams the packet, so an unchanged factor sends nothing.
     *
     * @param previousAmount the amount of the modifier currently on the attribute, or {@code null}
     *                       when there is none (the client holds the unmodified value, factor 1.0)
     * @param factor         the factor just decided
     */
    static boolean needsPacket(final Double previousAmount, final double factor) {
        final double current = previousAmount == null ? 0.0 : previousAmount;
        return Math.abs(modifierAmount(factor) - current) > SAME_FACTOR_EPSILON;
    }
}
