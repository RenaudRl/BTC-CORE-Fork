package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.integrity.engine.ReachCheck.Box;
import dev.btc.core.integrity.engine.ReachCheck.ReachContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wall hits judged against a small world of full blocks, as the server's ray would see them (bench 14/11, B.1). */
class WallHitCheckTest {

    /** Full blocks by their lowest corner; a ray is blocked when any point of it lies inside one. */
    private final Set<List<Integer>> solid = new HashSet<>();
    private final List<double[]> rays = new ArrayList<>();

    private void block(int x, int y, int z) {
        solid.add(List.of(x, y, z));
    }

    private boolean inSolid(double x, double y, double z) {
        return solid.contains(List.of((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }

    private final WallHitCheck.Probe probe = (fromX, fromY, fromZ, toX, toY, toZ) -> {
        rays.add(new double[] {fromX, fromY, fromZ, toX, toY, toZ});
        int steps = 2_000;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            if (inSolid(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t, fromZ + (toZ - fromZ) * t)) {
                return true;
            }
        }
        return false;
    };

    private static ReachContext eyeAt(double x, double y, double z, Box target) {
        return new ReachContext(x, y, z, List.of(target), 3.0, false, ClientPlatform.JAVA, 50);
    }

    @Test
    void aTargetWhoseLowerHalfIsInAWallButWhoseTopIsInSightIsNotAWallHit() {
        // 155 of the 163 bench lines: the box's nearest point is inside the block, its upper half is in the open.
        block(2, 0, 0);
        Box target = new Box(2.2, 0, 0.2, 2.8, 1.8, 0.8);
        ReachContext context = eyeAt(0.5, 0.62, 0.5, target); // the attacker one block lower, eye level in the block

        Optional<WallHitCheck.Verdict> verdict = WallHitCheck.judge(context, probe);

        assertTrue(verdict.isEmpty(), "the top of the box is in sight");
        assertTrue(rays.size() > 1, "the nearest point was blocked, so other points were tried");
    }

    @Test
    void aStepEdgeGrazedByTheNearestRayDoesNotHideATargetWhoseTopIsInSight() {
        // The other 8 bench lines: a player on a step 1.2 to 1.7 away, the ray to its feet clips the step's edge.
        block(1, 0, 0);
        block(1, 1, 0);
        Box target = new Box(1.7, 2, 0.2, 2.3, 3.8, 0.8);
        ReachContext context = eyeAt(0.5, 1.62, 0.5, target);
        double[] nearest = WallHitCheck.ray(context, target);
        assertTrue(probe.blocked(nearest[0], nearest[1], nearest[2], nearest[3], nearest[4], nearest[5]),
            "the nearest ray really clips the step");
        rays.clear();

        assertTrue(WallHitCheck.judge(context, probe).isEmpty(), "the top of the box is in sight");
    }

    @Test
    void aTargetWhollyBehindAFullWallIsAWallHitAfterEveryPointWasTried() {
        for (int y = -1; y <= 4; y++) {
            for (int z = -3; z <= 3; z++) {
                block(1, y, z);
            }
        }
        Box target = new Box(2.2, 0, 0.2, 2.8, 1.8, 0.8);
        ReachContext context = eyeAt(0.5, 1.62, 0.5, target);

        Optional<WallHitCheck.Verdict> verdict = WallHitCheck.judge(context, probe);

        assertTrue(verdict.isPresent(), "every line is blocked");
        assertEquals(1, verdict.get().boxes());
        assertEquals(WallHitCheck.points(context, target).length, rays.size(), "every point of the box was tried");
    }

    /** A wall of full blocks one block ahead of the eye, covering z 0 to 3 only: its corner is at z = 0. */
    private void wallWithACornerAtZeroZ() {
        for (int y = -1; y <= 4; y++) {
            for (int z = 0; z <= 3; z++) {
                block(1, y, z);
            }
        }
    }

    private static ReachContext eyeAt(double x, double y, double z, Box current, Box earlier) {
        return new ReachContext(x, y, z, List.of(current, earlier), 3.0, false, ClientPlatform.JAVA, 50);
    }

    @Test
    void aTargetNowBehindACornerButSeenThereOnTheRoundTripIsNotAWallHit() {
        wallWithACornerAtZeroZ();
        Box behindTheWall = new Box(2.2, 0, 0.2, 2.8, 1.8, 0.8);
        Box pastTheCorner = new Box(2.2, 0, -1.8, 2.8, 1.8, -1.2);

        assertTrue(WallHitCheck.judge(eyeAt(0.5, 1.62, 0.5, behindTheWall, pastTheCorner), probe).isEmpty(),
            "the attacker saw the target in the open, before the round trip");
    }

    @Test
    void aTargetHiddenInEveryBoxOfTheRoundTripIsAWallHit() {
        wallWithACornerAtZeroZ();
        Box behindTheWall = new Box(2.2, 0, 0.2, 2.8, 1.8, 0.8);
        Box alsoBehindIt = new Box(2.4, 0, 0.3, 3.0, 1.8, 0.9);

        Optional<WallHitCheck.Verdict> verdict = WallHitCheck.judge(eyeAt(0.5, 1.62, 0.5, behindTheWall, alsoBehindIt), probe);

        assertTrue(verdict.isPresent(), "no box of the round trip can be reached in a straight line");
        assertEquals(2, verdict.get().boxes());
    }

    @Test
    void anHonestHitCostsOneRay() {
        Box target = new Box(2.2, 0, 0.2, 2.8, 1.8, 0.8);

        assertTrue(WallHitCheck.judge(eyeAt(0.5, 1.62, 0.5, target), probe).isEmpty());
        assertEquals(1, rays.size());
    }
}
