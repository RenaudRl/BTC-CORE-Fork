package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.integrity.engine.MovementPredictor.Divergence;
import dev.btc.core.integrity.engine.ReachCheck.ReachContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reach measured from the eye to the box, against the reach the server grants live. */
class ReachCheckTest {

    /** Survival default: {@code entity_interaction_range} 3.0 plus the default weapon's 0.3 margin. */
    private static final double SURVIVAL_REACH = 3.3;

    @Test
    void aHitInsideTheGrantedReachIsNotADivergence() {
        assertEquals(Optional.empty(), ReachCheck.judge(target(3.2, SURVIVAL_REACH, false)));
    }

    @Test
    void aHitBeyondTheGrantedReachAndTheSlackIsADivergence() {
        Divergence divergence = ReachCheck.judge(target(3.7, SURVIVAL_REACH, false)).orElseThrow();
        assertEquals(ReachCheck.REACH, divergence.check());
        assertEquals(0.4, divergence.amount(), 1e-9, "the excess over the granted reach, slack excluded");
        assertTrue(divergence.detail().contains("3.700 blocks"), divergence.detail());
    }

    @Test
    void theSlackIsNotADivergence() {
        assertEquals(Optional.empty(), ReachCheck.judge(target(SURVIVAL_REACH + ReachCheck.REACH_UNCERTAINTY - 1e-6,
            SURVIVAL_REACH, false)));
    }

    @Test
    void aWiderReachReadOnThePlayerWidensTheBoundWithoutADeclaration() {
        // A creative player, or a relocated entity_interaction_range: 5.3 granted by the server.
        assertEquals(Optional.empty(), ReachCheck.judge(target(5.2, 5.3, false)));
        assertTrue(ReachCheck.judge(target(5.2, SURVIVAL_REACH, false)).isPresent());
    }

    @Test
    void theDistanceIsToTheBoxNotToItsCentre() {
        // Eye 3.2 from the near face of a 1-block box whose centre is 3.7 away.
        ReachContext context = new ReachContext(0, 0, 0, 3.2, -0.5, -0.5, 4.2, 0.5, 0.5,
            SURVIVAL_REACH, false, ClientPlatform.UNKNOWN);
        assertEquals(3.2, context.distanceToBox(), 1e-9);
        assertEquals(Optional.empty(), ReachCheck.judge(context));
    }

    @Test
    void anEyeInsideTheBoxIsAtDistanceZero() {
        ReachContext context = new ReachContext(0, 0, 0, -1, -1, -1, 1, 1, 1,
            SURVIVAL_REACH, false, ClientPlatform.UNKNOWN);
        assertEquals(0, context.distanceToBox(), 1e-9);
    }

    @Test
    void furnitureAndClientOnlyEntitiesAreNotJudged() {
        assertEquals(Optional.empty(), ReachCheck.judge(target(40, SURVIVAL_REACH, true)));
    }

    /** A 1x1x1 box whose nearest face is {@code distance} from the eye along x. */
    private static ReachContext target(double distance, double reach, boolean ignored) {
        return new ReachContext(0, 0, 0, distance, -0.5, -0.5, distance + 1, 0.5, 0.5,
            reach, ignored, ClientPlatform.UNKNOWN);
    }
}
