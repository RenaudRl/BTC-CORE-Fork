package dev.btc.core.integrity.engine;

import dev.btc.core.integrity.engine.ReachCheck.Box;
import dev.btc.core.integrity.engine.ReachCheck.ReachContext;

import java.util.Locale;
import java.util.Optional;

/**
 * An attack through a wall: the line from the attacker's eye to the target is blocked by a solid block
 * for every position the target occupied during the attacker's round trip. That history exists for
 * players only: a mob has just its current box.
 *
 * <p>No position history is invented here. {@link ReachContext#targetBoxes()} is the target's box now
 * and the boxes it occupied while the attacker's client was catching up, taken from the history the
 * reach check already reads ({@code PlayerSimulationCache}); this judges the same boxes. A hit is a
 * violation only when <em>none</em> of them can be reached in a straight line, so a target that stepped
 * behind a corner after the attacker saw it is not one.
 *
 * <p>Cheap by construction: one short ray per box, from the eye to the box's nearest point, and the
 * first clear ray ends the judgement. An honest attack costs one ray. Only an attack within the reach
 * the server grants is judged: beyond it the reach check already speaks, and the handler refuses it.
 *
 * <p>Pure: the adapter reads the world through the {@link Probe}.
 */
final class WallHitCheck {

    /**
     * How far inside the box the ray ends. A ray that ends exactly on the target's face, when that face
     * lies flush against a block, would count the block it only touches.
     */
    static final double END_INSET = 0.02;

    /** Whether a solid collider lies on the straight line between two points. */
    @FunctionalInterface
    interface Probe {
        boolean blocked(double fromX, double fromY, double fromZ, double toX, double toY, double toZ);
    }

    /** A wall hit: how many boxes were considered (all blocked) and the distance to the nearest one. */
    record Verdict(int boxes, double distance) {}

    private WallHitCheck() {
    }

    /** Whether this attack is one the wall judgement applies to: a target that counts, within reach. */
    static boolean inScope(final ReachContext context) {
        return !context.targetIgnored()
            && context.distanceToTarget() <= context.maxReach() + ReachCheck.REACH_UNCERTAINTY;
    }

    static Optional<Verdict> judge(final ReachContext context, final Probe probe) {
        if (!inScope(context)) {
            return Optional.empty();
        }
        for (final Box box : context.targetBoxes()) {
            if (box.distanceTo(context.eyeX(), context.eyeY(), context.eyeZ()) <= 0) {
                // The eye is inside the box: there is no line to be blocked.
                return Optional.empty();
            }
            final double[] ray = ray(context, box);
            if (!probe.blocked(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5])) {
                return Optional.empty();
            }
        }
        return Optional.of(new Verdict(context.targetBoxes().size(), context.distanceToTarget()));
    }

    /** The line judged for one box: {@code {eyeX, eyeY, eyeZ, endX, endY, endZ}}, absolute coordinates. */
    static double[] ray(final ReachContext context, final Box box) {
        return new double[] {context.eyeX(), context.eyeY(), context.eyeZ(),
            end(context.eyeX(), box.minX(), box.maxX()),
            end(context.eyeY(), box.minY(), box.maxY()),
            end(context.eyeZ(), box.minZ(), box.maxZ())};
    }

    /**
     * The geometry of a judged line for the journal, so that a line can be re-read without the world:
     * where the eye was, where the ray ended, and the box it aimed at. {@code seen} is what the server
     * adds from the world ({@link ServerAdapter#describeWallHit}); empty adds nothing.
     */
    static String evidence(final double[] ray, final Box box, final String seen) {
        final String geometry = String.format(Locale.ROOT,
            "eye=(%.2f %.2f %.2f) ray-end=(%.2f %.2f %.2f) box=(%.2f %.2f %.2f)-(%.2f %.2f %.2f)",
            ray[0], ray[1], ray[2], ray[3], ray[4], ray[5],
            box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        return seen.isEmpty() ? geometry : geometry + " " + seen;
    }

    /** The nearest point of the box on one axis, pushed {@link #END_INSET} inside it (never past its middle). */
    private static double end(final double eye, final double min, final double max) {
        final double middle = (min + max) / 2;
        final double inset = Math.min(END_INSET, (max - min) / 2);
        if (eye < min) {
            return Math.min(min + inset, middle);
        }
        if (eye > max) {
            return Math.max(max - inset, middle);
        }
        return eye;
    }
}
