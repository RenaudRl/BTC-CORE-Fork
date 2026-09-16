package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.CustomMechanic;
import dev.btc.core.api.integrity.IntegrityAPI.MechanicType;
import dev.btc.core.integrity.engine.MovementPredictor.Divergence;
import dev.btc.core.integrity.engine.MovementPredictor.Judgement;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The envelope against the vanilla movement it must contain, and against the cheats it must not.
 *
 * <p>Each vanilla trajectory here is computed from the equations of {@code LivingEntity#travelInAir}
 * and {@code #jumpFromGround} of this version — not recorded from a client, so that a test that goes
 * red names the equation the envelope broke. The cheats are the same trajectories with one number a
 * client cannot honestly produce.
 */
class MovementPredictorTest {

    private static final long NOW = 1_000_000_000L;

    /** Sprint modifier folded in the live attribute: {@code 0.1 * 1.3}. */
    private static final double SPRINT_SPEED = 0.13;

    /** Terminal ground displacement at vanilla friction: {@code a / (1 - 0.6 * 0.91)}. */
    private static final double SPRINT_TERMINAL = SPRINT_SPEED / 0.454;

    private final SessionState state = new SessionState();

    // ------------------------------------------------------------------ speed

    @Test
    void sprintingOnTheGroundAtTerminalSpeedIsInsideTheEnvelope() {
        MovementContext context = MovementContext.grounded()
            .withLimits(PlayerLimits.vanilla().withMovementSpeed(SPRINT_SPEED));
        double x = 0;
        double dx = 0;
        state.moved(x, 64, 0, true, false);
        state.moved(x, 64, 0, true, false);
        // From rest to terminal, as vanilla ramps: momentum after ground friction, plus acceleration.
        for (int tick = 0; tick < 40; tick++) {
            dx = dx * 0.6 * 0.91 + SPRINT_SPEED;
            x += dx;
            Judgement judgement = judge(context, x, 64, 0, true);
            assertEquals(List.of(), judgement.divergences(), "tick " + tick);
            record(judgement, x, 64, 0, true, true);
        }
        assertEquals(SPRINT_TERMINAL, dx, 1e-6, "the ramp did reach the terminal speed");
    }

    @Test
    void aSprintJumpStartsFasterThanTheGroundAllowsAndIsInsideTheEnvelope() {
        MovementContext context = MovementContext.grounded()
            .withLimits(PlayerLimits.vanilla().withMovementSpeed(SPRINT_SPEED));
        state.moved(0, 64, 0, true, false);
        state.moved(SPRINT_TERMINAL, 64, 0, true, false);
        // Jump tick, vanilla: momentum after ground friction, plus acceleration, plus the 0.2 boost.
        double jumpTick = SPRINT_TERMINAL * 0.6 * 0.91 + SPRINT_SPEED + 0.2;
        assertEquals(List.of(), judge(context, SPRINT_TERMINAL + jumpTick, 64.42, 0, false).divergences());
    }

    @Test
    void theSprintJumpBoostIsNotAllowedFromTheAirNorWithoutARise() {
        PlayerLimits sprinting = PlayerLimits.vanilla().withMovementSpeed(SPRINT_SPEED);
        double jumpTick = SPRINT_TERMINAL * 0.6 * 0.91 + SPRINT_SPEED + 0.2;

        state.moved(0, 70, 0, false, false);
        state.moved(SPRINT_TERMINAL, 70, 0, false, false);
        assertOnly(MovementPredictor.SPEED,
            judge(MovementContext.airborne().withLimits(sprinting), SPRINT_TERMINAL + jumpTick, 69.9, 0, false),
            "in the air");

        SessionState grounded = new SessionState();
        grounded.moved(0, 64, 0, true, false);
        grounded.moved(SPRINT_TERMINAL, 64, 0, true, false);
        assertOnly(MovementPredictor.SPEED,
            MovementPredictor.judge(grounded, MovementContext.grounded().withLimits(sprinting),
                SPRINT_TERMINAL + jumpTick, 64, 0, true, NOW).divergences(),
            "on the ground without rising: the boost is a jump's, and nothing jumped");
    }

    @Test
    void aSpeedEffectWidensTheEnvelopeThroughTheLiveAttributeNotThroughADeclaration() {
        // Speed II while sprinting: 0.1 * 1.3 * 1.4. The same vanilla ramp from rest, judged twice.
        double boostedSpeed = 0.182;
        PlayerLimits boosted = PlayerLimits.vanilla().withMovementSpeed(boostedSpeed);
        PlayerLimits plain = PlayerLimits.vanilla().withMovementSpeed(SPRINT_SPEED);
        assertEquals(List.of(), groundRamp(boostedSpeed, boosted),
            "with the effect read on the player, the whole ramp to 0.4 per tick is honest");
        assertOnly(MovementPredictor.SPEED, groundRamp(boostedSpeed, plain),
            "without it, the very first tick accelerates beyond what the attribute allows");
    }

    /**
     * Runs a vanilla ground ramp from rest at {@code speed} against {@code limits}: the first
     * divergences met, or none if the whole ramp passes.
     */
    private static List<Divergence> groundRamp(double speed, PlayerLimits limits) {
        SessionState ramp = new SessionState();
        MovementContext context = MovementContext.grounded().withLimits(limits);
        ramp.moved(0, 64, 0, true, false);
        ramp.moved(0, 64, 0, true, false);
        double x = 0;
        double dx = 0;
        for (int tick = 0; tick < 30; tick++) {
            dx = dx * 0.6 * 0.91 + speed;
            x += dx;
            Judgement judgement = MovementPredictor.judge(ramp, context, x, 64, 0, true, NOW);
            if (!judgement.divergences().isEmpty()) {
                return judgement.divergences();
            }
            ramp.moved(x, 64, 0, true, true, false, judgement.horizontalCap(), judgement.verticalCap());
        }
        return List.of();
    }

    @Test
    void theWindowDoesNotCompoundThroughTheCarriedMomentum() {
        // A client exceeding the bound by less than the window every tick, on the ground that keeps
        // most momentum. Without the cap it would climb to several times the terminal speed unseen.
        MovementContext context = MovementContext.grounded()
            .withLimits(PlayerLimits.vanilla().withMovementSpeed(SPRINT_SPEED));
        double x = 0;
        state.moved(x, 64, 0, true, false);
        double shown = 0;
        for (int tick = 0; tick < 200; tick++) {
            double bound = Math.max(shown * 0.6 * 0.91 + SPRINT_SPEED, shown * 0.989 * 0.91
                + SPRINT_SPEED * 0.21600002 / Math.pow(0.989, 3));
            shown = bound + MovementPredictor.HORIZONTAL_UNCERTAINTY - 1e-9;
            x += shown;
            Judgement judgement = judge(context, x, 64, 0, true);
            assertEquals(List.of(), judgement.divergences(), "the window is honoured on tick " + tick);
            record(judgement, x, 64, 0, true, true);
            shown = Math.min(shown, judgement.horizontalCap());
        }
        assertTrue(state.lastDx() < SPRINT_TERMINAL + 3 * MovementPredictor.HORIZONTAL_UNCERTAINTY,
            () -> "momentum is capped at the honest terminal plus one window, not " + state.lastDx());
    }

    @Test
    void aTeleportBeyondTheEnvelopeIsSpeedNotFly() {
        state.moved(0, 64, 0, true, false);
        state.moved(0.1, 64, 0, true, false);
        Divergence divergence = single(MovementPredictor.SPEED, judge(MovementContext.grounded(), 5, 64, 0, true));
        assertTrue(divergence.amount() > 4, () -> "excess is the whole jump: " + divergence);
        assertTrue(divergence.detail().contains("speed attribute 0.100"), divergence.detail());
    }

    @Test
    void aFlaggedTickIsAcceptedAsMomentumSoOneImpulseIsOneLineNotACascade() {
        // Something the engine does not model (an explosion) throws the player 1.5 blocks in a tick.
        state.moved(0, 70, 0, false, false);
        state.moved(0, 69.9, 0, false, false);
        double dy = (-0.1 - 0.08) * 0.98;
        double y = 69.9 + dy;
        Judgement thrown = judge(MovementContext.airborne(), 1.5, y, 0, false);
        assertOnly(MovementPredictor.SPEED, thrown.divergences(), "the impulse itself");
        record(thrown, 1.5, y, 0, false, false);
        // The decay of that momentum is vanilla from here on.
        double x = 1.5;
        double dx = 1.5;
        for (int tick = 0; tick < 10; tick++) {
            dx = dx * 0.91 + 0.026;
            dy = (dy - 0.08) * 0.98;
            x += dx;
            y += dy;
            Judgement judgement = judge(MovementContext.airborne(), x, y, 0, false);
            assertEquals(List.of(), judgement.divergences(), "decay tick " + tick);
            record(judgement, x, y, 0, false, false);
        }
    }

    // ------------------------------------------------------------------ fly

    @Test
    void aVanillaJumpArcIsInsideTheEnvelope() {
        state.moved(0, 64, 0, true, false);
        state.moved(0, 64, 0, true, false);
        double y = 64;
        double dy = 0.42; // jump_strength, no boost
        y += dy;
        Judgement judgement = judge(MovementContext.airborne(), 0, y, 0, false);
        assertEquals(List.of(), judgement.divergences(), "jump tick");
        record(judgement, 0, y, 0, false, false);
        for (int tick = 0; tick < 12; tick++) {
            dy = (dy - 0.08) * 0.98;
            y += dy;
            judgement = judge(MovementContext.airborne(), 0, y, 0, false);
            assertEquals(List.of(), judgement.divergences(), "arc tick " + tick);
            record(judgement, 0, y, 0, false, false);
        }
    }

    @Test
    void aJumpFromTheAirIsFly() {
        state.moved(0, 70, 0, false, false);
        state.moved(0, 69.9, 0, false, false);
        assertOnly(MovementPredictor.FLY, judge(MovementContext.airborne(), 0, 70.32, 0, false));
    }

    @Test
    void hoveringInTheAirIsFly() {
        state.moved(0, 70, 0, false, false);
        state.moved(0, 70, 0, false, false);
        // Two ticks of zero vertical movement with nothing under the feet: gravity says otherwise.
        assertOnly(MovementPredictor.FLY, judge(MovementContext.airborne(), 0, 70, 0, false));
    }

    @Test
    void jumpBoostRaisesTheJumpThroughTheLiveEffect() {
        // Jump Boost II: 0.42 + 0.1 * 3.
        PlayerLimits boosted = PlayerLimits.vanilla().withJumpBoostPower(0.3);
        state.moved(0, 64, 0, true, false);
        state.moved(0, 64, 0, true, false);
        assertEquals(List.of(), judge(MovementContext.airborne().withLimits(boosted), 0, 64.72, 0, false).divergences());
        assertOnly(MovementPredictor.FLY, judge(MovementContext.airborne(), 0, 64.72, 0, false));
    }

    @Test
    void slowFallingCapsGravityWhileDescending() {
        state.moved(0, 70, 0, false, false);
        state.moved(0, 69.98, 0, false, false);
        // Next honest dy under Slow Falling: (-0.02 - 0.01) * 0.98 = -0.0294.
        double y = 69.98 - 0.0294;
        assertEquals(List.of(), judge(MovementContext.airborne()
            .withLimits(PlayerLimits.vanilla().withSlowFalling(true)), 0, y, 0, false).divergences());
        assertOnly(MovementPredictor.FLY, judge(MovementContext.airborne(), 0, y, 0, false),
            "without the effect, gravity is 0.08 and that descent is too slow");
    }

    @Test
    void slowFallingDoesNotLetTheWindowCompoundIntoARise() {
        // Under Slow Falling gravity is 0.01, below the vertical window: a client rising by less
        // than the window every tick would, without the cap, accelerate upward for ever.
        MovementContext context = MovementContext.airborne()
            .withLimits(PlayerLimits.vanilla().withSlowFalling(true));
        state.moved(0, 70, 0, false, false);
        state.moved(0, 70, 0, false, false);
        double y = 70;
        boolean flagged = false;
        for (int tick = 0; tick < 50 && !flagged; tick++) {
            y += 0.02;
            Judgement judgement = judge(context, 0, y, 0, false);
            flagged = !judgement.divergences().isEmpty();
            record(judgement, 0, y, 0, false, false);
        }
        assertTrue(flagged, "a steady rise under Slow Falling must be flagged within a few ticks");
    }

    @Test
    void aBounceOffASlimeBlockReturnsAtMostTheFall() {
        state.moved(0, 70, 0, false, false);
        state.moved(0, 65, 0, true, false); // landed at -5 per tick on a slime block
        assertEquals(List.of(), judge(MovementContext.airborne(), 0, 69.9, 0, false).divergences(),
            "a bounce of 4.9 after a fall of 5 is what a slime block does");
        assertOnly(MovementPredictor.FLY, judge(MovementContext.airborne(), 0, 70.5, 0, false),
            "more than the fall brought is not a bounce");
    }

    @Test
    void aStepUpIsNotAJumpAndIsAllowedFromSupport() {
        state.moved(0, 64, 0, true, false);
        state.moved(0.1, 64, 0, true, false);
        assertEquals(List.of(), judge(MovementContext.grounded(), 0.2, 64.5, 0, true).divergences());
    }

    @Test
    void verticalPhysicsTheEnvelopeDoesNotDescribeLeavesTheAxisUnjudged() {
        state.moved(0, 70, 0, false, false);
        state.moved(0, 70, 0, false, false);
        for (PlayerLimits free : List.of(
            PlayerLimits.vanilla().withLevitating(true),
            PlayerLimits.vanilla().withFlying(true),
            PlayerLimits.vanilla().withInFluid(true),
            PlayerLimits.vanilla().withOnClimbable(true))) {
            assertEquals(List.of(), judge(MovementContext.airborne().withLimits(free), 0, 70.3, 0, false).divergences(),
                free.toString());
        }
    }

    @Test
    void theTickAfterAFreeVerticalStateIsNotJudgedEither() {
        // Leaving water: the momentum the envelope never saw.
        state.moved(0, 62, 0, false, true);
        state.moved(0, 62.3, 0, false, true);
        assertEquals(List.of(), judge(MovementContext.airborne(), 0, 62.6, 0, false).divergences());
    }

    // ------------------------------------------------------------------ declarations and server velocity

    @Test
    void aDeclaredLaunchIsAllowedWhileItIsLive() {
        CustomMechanic launch = new CustomMechanic(MechanicType.LAUNCH, Optional.of(new Vector(0.6, 1.5, 0)));
        MovementContext context = MovementContext.airborne().withMechanics(List.of(launch));
        state.moved(0, 70, 0, false, false);
        state.moved(0, 69.9, 0, false, false);
        assertEquals(List.of(), judge(context, 0.6, 71.4, 0, false).divergences());
        List<Divergence> without = judge(MovementContext.airborne(), 0.6, 71.4, 0, false).divergences();
        assertEquals(2, without.size(), () -> "both axes exceed without the declaration: " + without);
    }

    @Test
    void aSustainedVelocityIsAllowedEveryTickItIsLive() {
        CustomMechanic climb = new CustomMechanic(MechanicType.SUSTAINED_VELOCITY, Optional.of(new Vector(0, 0.3, 0)));
        MovementContext context = MovementContext.airborne().withMechanics(List.of(climb));
        double y = 70;
        state.moved(0, y, 0, false, false);
        state.moved(0, y, 0, false, false);
        for (int tick = 0; tick < 10; tick++) {
            y += 0.3;
            Judgement judgement = judge(context, 0, y, 0, false);
            assertEquals(List.of(), judgement.divergences(), "climb tick " + tick);
            record(judgement, 0, y, 0, false, false);
        }
    }

    @Test
    void aFlightGrantFreesTheVerticalAxisWithoutAVector() {
        CustomMechanic grant = new CustomMechanic(MechanicType.FLIGHT_GRANT, Optional.empty());
        state.moved(0, 70, 0, false, false);
        state.moved(0, 70, 0, false, false);
        assertEquals(List.of(), judge(MovementContext.airborne().withMechanics(List.of(grant)), 0, 70, 0, false)
            .divergences());
    }

    @Test
    void aVelocityTheServerSentIsAllowedUntilItExpires() {
        state.moved(0, 64, 0, true, false);
        state.moved(0, 64, 0, true, false);
        state.serverVelocity(0.8, 0.4, 0, NOW + 1_000);
        assertEquals(List.of(), judge(MovementContext.airborne(), 0.8, 64.4, 0, false, NOW).divergences());
        assertOnly(MovementPredictor.SPEED, judge(MovementContext.airborne(), 0.8, 64.4, 0, false, NOW + 2_000),
            "expired: the horizontal part is no longer explained; the vertical part is a jump");
    }

    @Test
    void anInvulnerabilityOrPostureDeclarationCarriesNoVelocity() {
        CustomMechanic posture = new CustomMechanic(MechanicType.POSTURE_OVERRIDE, Optional.of(new Vector(5, 5, 5)));
        state.moved(0, 64, 0, true, false);
        state.moved(0.1, 64, 0, true, false);
        assertOnly(MovementPredictor.SPEED,
            judge(MovementContext.grounded().withMechanics(List.of(posture)), 3, 64, 0, true));
    }

    // ------------------------------------------------------------------ fall and phase

    @Test
    void claimingGroundWhereTheServerHasNothingIsFall() {
        state.moved(0, 70, 0, false, false);
        state.moved(0, 69.9, 0, false, false);
        assertOnly(MovementPredictor.FALL, judge(MovementContext.airborne(), 0, 69.72, 0, true));
    }

    @Test
    void groundClaimedOnDeclaredClientTerrainIsNotFall() {
        state.moved(0, 70, 0, false, false);
        state.moved(0, 69.9, 0, false, false);
        assertEquals(List.of(), judge(MovementContext.airborne().withClientTerrainDeclared(true), 0, 69.72, 0, true)
            .divergences());
    }

    @Test
    void aPositionInsideSolidCollisionIsPhaseUnlessTheTerrainIsDeclared() {
        state.moved(0, 64, 0, true, false);
        state.moved(0.1, 64, 0, true, false);
        assertOnly(MovementPredictor.PHASE, judge(MovementContext.grounded().withInsideSolid(true), 0.2, 64, 0, true));
        assertEquals(List.of(), judge(MovementContext.grounded().withInsideSolid(true)
            .withClientTerrainDeclared(true), 0.2, 64, 0, true).divergences());
    }

    @Test
    void aPassengerIsNotJudgedAtAll() {
        state.moved(0, 64, 0, false, false);
        state.moved(0, 64, 0, false, false);
        MovementContext riding = MovementContext.airborne().withInsideSolid(true)
            .withLimits(new PlayerLimits(0.1, 0.42, 0, 0.08, 0.6, false, false, false, false, false,
                true, false, false));
        assertEquals(List.of(), judge(riding, 9, 70, 9, true).divergences());
    }

    // ------------------------------------------------------------------ helpers

    private Judgement judge(MovementContext context, double x, double y, double z, boolean onGround) {
        return judge(context, x, y, z, onGround, NOW);
    }

    private Judgement judge(MovementContext context, double x, double y, double z, boolean onGround, long now) {
        return MovementPredictor.judge(state, context, x, y, z, onGround, now);
    }

    /** What the engine does after a judgement: the position becomes the reference, caps included. */
    private void record(Judgement judgement, double x, double y, double z, boolean supported, boolean onGround) {
        state.moved(x, y, z, supported, onGround, false, judgement.horizontalCap(), judgement.verticalCap());
    }

    private static void assertOnly(CheckId check, Judgement judgement) {
        assertOnly(check, judgement.divergences(), "");
    }

    private static void assertOnly(CheckId check, Judgement judgement, String message) {
        assertOnly(check, judgement.divergences(), message);
    }

    private static void assertOnly(CheckId check, List<Divergence> divergences, String message) {
        assertEquals(1, divergences.size(), () -> message + " — expected only " + check + ", got " + divergences);
        assertEquals(check, divergences.get(0).check(), () -> message + " — " + divergences);
    }

    private static Divergence single(CheckId check, Judgement judgement) {
        assertOnly(check, judgement);
        return judgement.divergences().get(0);
    }
}
