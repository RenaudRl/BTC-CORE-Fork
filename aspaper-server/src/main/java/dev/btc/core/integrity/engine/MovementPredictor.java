package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.CustomMechanic;
import dev.btc.core.api.integrity.IntegrityAPI.MechanicType;
import dev.btc.core.integrity.engine.SessionState.Impulse;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stage-1 movement prediction: an envelope, not a simulation.
 *
 * <p>A full re-simulation of the client (GrimAC's approach) needs the whole input model — every
 * possible key combination, every block the client thinks it touched — and gets it wrong wherever
 * the client's world differs from the server's. What this engine can do without that, and prove, is
 * bound the displacement of one client tick from above: given the momentum the last packet showed,
 * the capabilities the server reads on the player (3.4) and every velocity it applied or was told
 * about (declarations, D4; server velocity packets, D15), how far could an honest client have gone?
 * Anything beyond that bound plus an uncertainty window is a divergence.
 *
 * <p>The bound is derived from the vanilla movement equations of this version, read in
 * {@code LivingEntity#travelInAir}, {@code #handleRelativeFrictionAndCalculateMovement} and
 * {@code #jumpFromGround}: position advances by the velocity of the tick <em>before</em> drag and
 * gravity are applied to it, so the next displacement is at most
 * <pre>
 *     on the ground:  |d| * friction * 0.91 + speed * 0.216 / friction^3   (+ 0.2 as a sprint-jump starts)
 *     in the air:     |d| * 0.91 + 0.026
 *     vertical:       (dy - gravity) * 0.98        (or a jump, a step, a bounce, when supported)
 * </pre>
 * where {@code speed} is the player's live {@code movement_speed} and {@code friction} the block's.
 * The block under a client is not known here, so the ground bound is the larger of the two extremes
 * vanilla has — ordinary ground, which accelerates most, and blue ice, which keeps most momentum.
 *
 * <p><b>The uncertainty window does not compound.</b> The momentum carried into a tick is the smaller
 * of what the client showed and what the previous bound allowed without its window: otherwise a
 * client exceeding the bound by less than the window every tick would accumulate the window through
 * the drag and reach, on ice or under Slow Falling, several times the honest terminal speed unseen.
 * When a tick is flagged, the client's displacement is accepted as the new momentum instead: one
 * unexplained impulse is one journal line, not a cascade for as long as it decays.
 *
 * <p>Every situation vanilla handles with a physics the envelope does not describe — flight,
 * gliding, riptide, fluids, ladders, levitation, a vehicle — leaves the corresponding axis unjudged
 * rather than guessed. The observation journal will say which of those are worth modelling.
 *
 * <p>Pure: nothing here touches a clock or a world. The caller passes the context it read and the
 * instant it read it at.
 */
final class MovementPredictor {

    static final CheckId SPEED = new CheckId(SentinelEngine.NAMESPACE, "speed");
    static final CheckId FLY = new CheckId(SentinelEngine.NAMESPACE, "fly");
    static final CheckId FALL = new CheckId(SentinelEngine.NAMESPACE, "fall");
    static final CheckId PHASE = new CheckId(SentinelEngine.NAMESPACE, "phase");

    /** Horizontal momentum kept from one tick to the next in the air. Ground keeps {@code friction} times it. */
    static final double AIR_DRAG = 0.91;

    /** Vertical momentum kept from one tick to the next. */
    static final double VERTICAL_DRAG = 0.98;

    /** Horizontal acceleration of a sprinting player in the air: {@code 0.02 + 0.006}, not an attribute. */
    static final double AIR_ACCELERATION = 0.026;

    /** Friction of ordinary blocks: the ground that accelerates most. */
    static final double GROUND_FRICTION = 0.6;

    /** Friction of blue ice: the ground that keeps most momentum. */
    static final double ICE_FRICTION = 0.989;

    /** Vanilla's ground acceleration constant: {@code speed * 0.216 / friction^3}. */
    private static final double GROUND_ACCELERATION_FACTOR = 0.21600002;

    /** What a sprint-jump adds horizontally on the tick it starts. */
    static final double SPRINT_JUMP_BOOST = 0.2;

    /**
     * Slack on the horizontal bound. Covers the rounding of a client position sent as doubles and the
     * block-edge cases where the client and the server disagree by a hair about the friction under
     * the feet. A guess to be replaced by the measurement of 4.1.
     */
    static final double HORIZONTAL_UNCERTAINTY = 0.05;

    /** Slack on the vertical bound, same status as {@link #HORIZONTAL_UNCERTAINTY}. */
    static final double VERTICAL_UNCERTAINTY = 0.03;

    /** A divergence between what the client claimed and what the bound allowed. */
    record Divergence(CheckId check, double amount, String detail) {}

    /**
     * What one judgement produced: the divergences, and the momentum caps the next judgement
     * carries so that the window does not compound. A cap is {@link Double#POSITIVE_INFINITY} when
     * the axis was not judged, or was flagged and the client's momentum is accepted as it is.
     */
    record Judgement(List<Divergence> divergences, double horizontalCap, double verticalCap) {}

    private MovementPredictor() {
    }

    /**
     * Judges one position claim against the bound the last one allowed.
     *
     * <p>Does not record anything: the caller decides whether this position becomes the reference
     * for the next, so that a judged and a skipped packet update the state the same way.
     */
    static Judgement judge(final SessionState state, final MovementContext context,
                           final double x, final double y, final double z,
                           final boolean claimedOnGround, final long nowNanos) {
        final List<Divergence> divergences = new ArrayList<>(2);
        final PlayerLimits limits = context.limits();
        if (limits.inVehicle()) {
            // A passenger's movement packets do not move them; the vehicle's do. Nothing to judge.
            return new Judgement(divergences, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
        final Impulses impulses = Impulses.of(context.mechanics(), state.liveServerVelocity(nowNanos));

        double horizontalCap = Double.POSITIVE_INFINITY;
        if (!horizontalFree(limits)) {
            final double dx = x - state.lastX();
            final double dz = z - state.lastZ();
            final double moved = Math.sqrt(dx * dx + dz * dz);
            final double bound = horizontalBound(state, limits, impulses, y - state.lastY() > 0);
            if (moved > bound + HORIZONTAL_UNCERTAINTY) {
                divergences.add(new Divergence(SPEED, moved - bound, String.format(Locale.ROOT,
                    "moved %.3f blocks horizontally, bound %.3f (speed attribute %.3f, %s%s)",
                    moved, bound, limits.movementSpeed(), groundOrAir(state), impulses.describe())));
            } else {
                horizontalCap = bound;
            }
        }

        double verticalCap = Double.POSITIVE_INFINITY;
        final boolean verticalFree = verticalFree(limits, impulses);
        if (!verticalFree && !state.lastVerticalFree()) {
            final double dy = y - state.lastY();
            final double bound = verticalBound(state, limits, impulses);
            if (dy > bound + VERTICAL_UNCERTAINTY) {
                divergences.add(new Divergence(FLY, dy - bound, String.format(Locale.ROOT,
                    "rose %.3f blocks, bound %.3f (last dy %.3f, %s%s)",
                    dy, bound, state.lastDy(), groundOrAir(state), impulses.describe())));
            } else {
                verticalCap = bound;
            }
        }

        if (claimedOnGround && !context.supported() && !verticalFree && !context.clientTerrainDeclared()) {
            divergences.add(new Divergence(FALL, 1.0,
                "claimed to stand on ground where the server has nothing under the feet"));
        }

        if (context.insideSolid() && !context.clientTerrainDeclared()) {
            divergences.add(new Divergence(PHASE, 1.0,
                "position intersects solid collision the client should not be inside"));
        }
        return new Judgement(divergences, horizontalCap, verticalCap);
    }

    /**
     * Whether the vertical axis is driven by a physics this envelope does not describe.
     *
     * <p>Package-visible so the engine records it with the position: the tick after such a state
     * starts from a momentum the envelope never saw, and is left alone too.
     */
    static boolean verticalFree(final PlayerLimits limits, final Impulses impulses) {
        return limits.flying() || limits.gliding() || limits.riptiding() || limits.inFluid()
            || limits.onClimbable() || limits.levitating() || impulses.flightGranted();
    }

    static boolean verticalFree(final MovementContext context, final long nowNanos, final SessionState state) {
        return verticalFree(context.limits(),
            Impulses.of(context.mechanics(), state.liveServerVelocity(nowNanos)));
    }

    private static boolean horizontalFree(final PlayerLimits limits) {
        // Flight, gliding and riptide move faster than any ground bound and are not modelled.
        // Fluids and ladders are slower than air: the air bound still holds above them.
        return limits.flying() || limits.gliding() || limits.riptiding();
    }

    /**
     * Whether the last tick was played with ground physics.
     *
     * <p>Either side saying "ground" counts: the client applies ground acceleration when <em>it</em>
     * believes it stands, and the server's support is what an honest client believes when the two
     * agree. Where they disagree the fall check says so; this bound stays the wider of the two so
     * that a disagreement is journalled once, not twice.
     */
    private static boolean lastOnGround(final SessionState state) {
        return state.lastSupported() || state.lastClaimedOnGround();
    }

    private static String groundOrAir(final SessionState state) {
        return lastOnGround(state) ? "from the ground" : "in the air";
    }

    /**
     * The horizontal bound without its window.
     *
     * <p>The sprint-jump boost is allowed only on a tick that rises: vanilla applies it in
     * {@code jumpFromGround}, which also sets the vertical velocity, so a boost without a rise is
     * not a jump. This is a coarse jump model — a client that alternates a small rise with a small
     * drop while the server still finds support under it can collect the boost every other tick —
     * and 4.2 will need a real one (a jump is followed by a flight of at least a dozen ticks) before
     * this check is armed. For observation, the coarse model journals the honest cases correctly,
     * including the sprint-jump cut short by a low ceiling.
     */
    private static double horizontalBound(final SessionState state, final PlayerLimits limits,
                                          final Impulses impulses, final boolean rising) {
        final double shown = Math.sqrt(state.lastDx() * state.lastDx() + state.lastDz() * state.lastDz());
        final double carried = Math.min(shown, state.horizontalCap());
        double bound;
        if (lastOnGround(state)) {
            bound = Math.max(
                groundBound(carried, limits.movementSpeed(), GROUND_FRICTION),
                groundBound(carried, limits.movementSpeed(), ICE_FRICTION));
            if (rising) {
                bound += SPRINT_JUMP_BOOST;
            }
        } else {
            bound = carried * AIR_DRAG + AIR_ACCELERATION;
        }
        return bound + impulses.horizontal();
    }

    private static double groundBound(final double carried, final double speed, final double friction) {
        return carried * friction * AIR_DRAG + speed * GROUND_ACCELERATION_FACTOR / (friction * friction * friction);
    }

    /** The bound without its window. */
    private static double verticalBound(final SessionState state, final PlayerLimits limits,
                                        final Impulses impulses) {
        final double shownDy = state.lastDy();
        final double carriedDy = Math.min(shownDy, state.verticalCap());
        double bound = (carriedDy - limits.effectiveGravity(carriedDy)) * VERTICAL_DRAG;
        if (state.lastSupported()) {
            // A jump, a step up, or a bounce: a slime block returns at most what the fall brought.
            bound = Math.max(bound, Math.max(limits.jumpPower(), limits.stepHeight()));
            bound = Math.max(bound, -shownDy);
        }
        if (impulses.upward() > 0) {
            // A velocity may replace the client's (setVelocity) or add to it (knockback): the
            // bound covers both by adding the impulse to a momentum that is never negative.
            bound = Math.max(bound, 0) + impulses.upward();
        }
        return bound;
    }

    /**
     * Every velocity the server applied or was told about, folded into what the bound must allow.
     *
     * <p>Impulses are added to the bound for as long as they are live rather than consumed on first
     * sight: the client applies a velocity packet when it receives it, which under latency is a few
     * ticks after the server sent it, and a bound that stopped allowing the impulse before the client
     * applied it would flag the honest application. The cost is a wider bound for the impulse's
     * lifetime, which is bounded by the declaration's TTL or by {@link SentinelEngine#SERVER_VELOCITY_TTL}.
     */
    record Impulses(double horizontal, double upward, boolean flightGranted, String describe) {

        static Impulses of(final List<CustomMechanic> mechanics, final List<Impulse> serverVelocity) {
            double horizontal = 0;
            double upward = 0;
            boolean flightGranted = false;
            final StringBuilder description = new StringBuilder();
            for (final CustomMechanic mechanic : mechanics) {
                if (mechanic.type() == MechanicType.FLIGHT_GRANT) {
                    flightGranted = true;
                    description.append(", flight granted");
                    continue;
                }
                if (!carriesVelocity(mechanic.type()) || mechanic.appliedVelocity().isEmpty()) {
                    continue;
                }
                final Vector velocity = mechanic.appliedVelocity().get();
                horizontal += Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
                upward += Math.max(0, velocity.getY());
                description.append(", declared ").append(mechanic.type());
            }
            for (final Impulse impulse : serverVelocity) {
                horizontal += Math.sqrt(impulse.x() * impulse.x() + impulse.z() * impulse.z());
                upward += Math.max(0, impulse.y());
                description.append(", server velocity");
            }
            return new Impulses(horizontal, upward, flightGranted, description.toString());
        }

        private static boolean carriesVelocity(final MechanicType type) {
            return switch (type) {
                case KNOCKBACK, LAUNCH, SUSTAINED_VELOCITY, AOE_PULL, AOE_PUSH -> true;
                case SPEED_MODIFIER, FLIGHT_GRANT, POSTURE_OVERRIDE, INVULNERABILITY_WINDOW -> false;
            };
        }
    }
}
