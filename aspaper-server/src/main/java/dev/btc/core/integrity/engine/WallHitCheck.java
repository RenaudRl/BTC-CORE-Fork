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
 * <p>Cheap by construction: first one short ray per box, from the eye to the box's nearest point; only when all
 * of them are blocked, a few more per box ({@link #points}), since that nearest point can sit inside a block the
 * target is half buried in, or just past a step's edge (bench 14/11). The first clear ray ends the judgement, so
 * an honest attack costs one ray. Only an attack within the reach
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

    /**
     * How far the other points of a box sit from its edges: a ray to a corner exactly on an edge would graze the
     * block next to it.
     */
    static final double POINT_INSET = 0.1;

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
        // Every nearest point is hidden: a box half in a block or behind a step's edge may still show another one.
        for (final Box box : context.targetBoxes()) {
            final double[][] points = points(context, box);
            for (int i = 1; i < points.length; i++) {
                final double[] point = points[i];
                if (!probe.blocked(context.eyeX(), context.eyeY(), context.eyeZ(), point[0], point[1], point[2])) {
                    return Optional.empty();
                }
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
     * The points of one box a line may reach, the nearest first ({@link #ray}'s end), then the centre, the centre of
     * the top face, the centre at eye height and the four corners of the face turned to the eye. A box half inside a
     * block, or whose nearest point lies just past a step's edge, still shows its other points.
     */
    static double[][] points(final ReachContext context, final Box box) {
        final double[] eye = {context.eyeX(), context.eyeY(), context.eyeZ()};
        final double[] min = {box.minX(), box.minY(), box.minZ()};
        final double[] max = {box.maxX(), box.maxY(), box.maxZ()};
        final double[] centre = new double[3];
        final double[] nearest = new double[3];
        int face = 0;
        double farthest = -1;
        for (int axis = 0; axis < 3; axis++) {
            centre[axis] = (min[axis] + max[axis]) / 2;
            nearest[axis] = end(eye[axis], min[axis], max[axis]);
            // The face turned to the eye is on the axis the eye is farthest outside the box.
            final double outside = Math.max(min[axis] - eye[axis], eye[axis] - max[axis]);
            if (outside > farthest) {
                farthest = outside;
                face = axis;
            }
        }
        final double insetY = inset(min[1], max[1]);
        final double[][] points = new double[8][];
        points[0] = nearest;
        points[1] = centre;
        points[2] = new double[] {centre[0], max[1] - insetY, centre[2]};
        points[3] = new double[] {centre[0], Math.clamp(eye[1], min[1] + insetY, max[1] - insetY), centre[2]};
        final int u = (face + 1) % 3;
        final int v = (face + 2) % 3;
        for (int corner = 0; corner < 4; corner++) {
            final double[] point = new double[3];
            point[face] = nearest[face];
            point[u] = (corner & 1) == 0 ? min[u] + inset(min[u], max[u]) : max[u] - inset(min[u], max[u]);
            point[v] = (corner & 2) == 0 ? min[v] + inset(min[v], max[v]) : max[v] - inset(min[v], max[v]);
            points[4 + corner] = point;
        }
        return points;
    }

    /** {@link #POINT_INSET}, never past the middle of the box on that axis. */
    private static double inset(final double min, final double max) {
        return Math.min(POINT_INSET, (max - min) / 2);
    }

    /**
     * The geometry of a judged line for the journal, so that a line can be re-read without the world:
     * where the eye was, where the ray ended, and the box it aimed at. {@code seen} is what the server
     * adds from the world ({@link ServerAdapter#describeWallHit}); empty adds nothing. It comes first: the
     * journal column keeps 255 characters, and the block hit says more than the geometry.
     */
    static String evidence(final double[] ray, final Box box, final String seen) {
        final String geometry = String.format(Locale.ROOT,
            "eye=(%.2f %.2f %.2f) ray-end=(%.2f %.2f %.2f) box=(%.2f %.2f %.2f)-(%.2f %.2f %.2f)",
            ray[0], ray[1], ray[2], ray[3], ray[4], ray[5],
            box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        return seen.isEmpty() ? geometry : seen + "; " + geometry;
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
