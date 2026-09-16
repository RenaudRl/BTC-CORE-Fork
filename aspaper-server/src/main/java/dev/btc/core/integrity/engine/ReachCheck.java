package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.integrity.engine.MovementPredictor.Divergence;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Attack reach against the reach the server actually grants.
 *
 * <p>The bound is read live (3.4): in this version an attack's range is the {@code attack_range}
 * component of the weapon in hand — whose default is the {@code entity_interaction_range} attribute,
 * modifiers included — plus the component's own hitbox margin, exactly what
 * {@code Player#isWithinAttackRange} computes before the server accepts the hit. A relocated attribute,
 * a spear, a creative player: all of them widen the bound here without anyone declaring anything.
 *
 * <p>The distance is measured from the attacker's eye to the closest point of the target's box, as
 * the server does — over every box the target occupied during the attacker's round trip (3.5), so
 * that a hit on where the target <em>was</em> when the client saw it is not a divergence. What the
 * server adds on top — a leniency of three blocks, configurable — is the reason this check exists:
 * the server tolerates a claim it cannot verify, and this journals it.
 *
 * <p>Pure: the adapter reads the world into a {@link ReachContext}, this judges it.
 */
final class ReachCheck {

    static final CheckId REACH = new CheckId(SentinelEngine.NAMESPACE, "reach");

    /**
     * Slack on the reach, for what the round trip does not cover: the target's own interpolation
     * on the attacker's client, and the half tick between two history samples. The raw distance
     * goes into the journal so 4.1 can re-threshold from it without re-measuring.
     */
    static final double REACH_UNCERTAINTY = 0.3;

    /** An axis-aligned box, as the server has it or had it. */
    record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

        /** Euclidean distance from a point to the nearest point of this box; zero inside it. */
        double distanceTo(final double x, final double y, final double z) {
            final double dx = axisDistance(x, minX, maxX);
            final double dy = axisDistance(y, minY, maxY);
            final double dz = axisDistance(z, minZ, maxZ);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        private static double axisDistance(final double point, final double min, final double max) {
            if (point < min) {
                return min - point;
            }
            if (point > max) {
                return point - max;
            }
            return 0;
        }
    }

    /**
     * What the reach judgement needs, read on the region thread.
     *
     * @param eyeX attacker's eye position
     * @param targetBoxes the target's box now, first, then every box it occupied during the
     *     attacker's round trip, most recent first. Never empty
     * @param maxReach the reach the server grants this attacker with this weapon, margin included
     * @param targetIgnored whether the target is furniture (technical marker) or a client-only entity
     *     the attacker sees but the server does not judge (2.8): no reach applies to those
     * @param platform the session's origin, for the journal
     * @param roundTripMillis the attacker's measured round trip, or {@code -1} before the first
     *     answer — the journal says which, so 4.1 can tell a compensated line from a bare one
     */
    record ReachContext(double eyeX, double eyeY, double eyeZ, List<Box> targetBoxes,
                        double maxReach, boolean targetIgnored, ClientPlatform platform,
                        long roundTripMillis) {

        ReachContext {
            if (targetBoxes == null || targetBoxes.isEmpty()) {
                throw new IllegalArgumentException("a reach context needs the target's box");
            }
            targetBoxes = List.copyOf(targetBoxes);
        }

        /** The smallest distance from the eye to any box the target occupied in the window. */
        double distanceToTarget() {
            double closest = Double.POSITIVE_INFINITY;
            for (final Box box : targetBoxes) {
                closest = Math.min(closest, box.distanceTo(eyeX, eyeY, eyeZ));
            }
            return closest;
        }
    }

    private ReachCheck() {
    }

    static Optional<Divergence> judge(final ReachContext context) {
        if (context.targetIgnored()) {
            return Optional.empty();
        }
        final double distance = context.distanceToTarget();
        final double bound = context.maxReach() + REACH_UNCERTAINTY;
        if (distance <= bound) {
            return Optional.empty();
        }
        final String compensation = context.roundTripMillis() < 0
            ? "no round trip measured yet"
            : String.format(Locale.ROOT, "round trip %d ms, %d boxes considered",
                context.roundTripMillis(), context.targetBoxes().size());
        return Optional.of(new Divergence(REACH, distance - context.maxReach(), String.format(Locale.ROOT,
            "attacked from %.3f blocks, reach granted %.3f (+%.2f slack; %s)",
            distance, context.maxReach(), REACH_UNCERTAINTY, compensation)));
    }
}
