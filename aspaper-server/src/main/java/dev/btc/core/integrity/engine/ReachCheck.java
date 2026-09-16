package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.integrity.engine.MovementPredictor.Divergence;

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
 * the server does. What the server adds on top — a leniency of three blocks, configurable — is the
 * reason this check exists: the server tolerates a claim it cannot verify, and this journals it.
 *
 * <p>Pure: the adapter reads the world into a {@link ReachContext}, this judges it.
 */
final class ReachCheck {

    static final CheckId REACH = new CheckId(SentinelEngine.NAMESPACE, "reach");

    /**
     * Slack on the reach, for the target's movement between the client's view and the server's:
     * there is no latency compensation yet (3.5). The raw distance goes into the journal so 4.1 can
     * re-threshold from it without re-measuring.
     */
    static final double REACH_UNCERTAINTY = 0.3;

    /**
     * What the reach judgement needs, read on the region thread.
     *
     * @param eyeX attacker's eye position
     * @param minX target's bounding box, as the server has it this tick
     * @param maxReach the reach the server grants this attacker with this weapon, margin included
     * @param targetIgnored whether the target is furniture (technical marker) or a client-only entity
     *     the attacker sees but the server does not judge (2.8): no reach applies to those
     * @param platform the session's origin, for the journal
     */
    record ReachContext(double eyeX, double eyeY, double eyeZ,
                        double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ,
                        double maxReach, boolean targetIgnored, ClientPlatform platform) {

        /** Euclidean distance from the eye to the nearest point of the box; zero inside it. */
        double distanceToBox() {
            final double dx = axisDistance(eyeX, minX, maxX);
            final double dy = axisDistance(eyeY, minY, maxY);
            final double dz = axisDistance(eyeZ, minZ, maxZ);
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

    private ReachCheck() {
    }

    static Optional<Divergence> judge(final ReachContext context) {
        if (context.targetIgnored()) {
            return Optional.empty();
        }
        final double distance = context.distanceToBox();
        final double bound = context.maxReach() + REACH_UNCERTAINTY;
        if (distance <= bound) {
            return Optional.empty();
        }
        return Optional.of(new Divergence(REACH, distance - context.maxReach(), String.format(Locale.ROOT,
            "attacked from %.3f blocks, reach granted %.3f (+%.2f slack)",
            distance, context.maxReach(), REACH_UNCERTAINTY)));
    }
}
