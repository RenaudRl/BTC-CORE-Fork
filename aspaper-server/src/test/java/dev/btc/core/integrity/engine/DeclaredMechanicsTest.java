package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.CustomMechanic;
import dev.btc.core.api.integrity.IntegrityAPI.MechanicType;
import dev.btc.core.integrity.engine.MovementPredictor.Divergence;
import dev.btc.core.integrity.engine.MovementPredictor.Judgement;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One scenario per declared {@link MechanicType}: what the declaration explains and, the half that
 * matters, what it does not. The spec (integrity-api, "Scripted launch") asks that a declared velocity
 * is accepted and that the check "remains armed for any displacement beyond the declared vector".
 *
 * <p>The nine types fall into four families, because the envelope has only four ways to take a
 * declaration in. {@link #everyMechanicTypeHasAFamily} fails when a tenth type is added without one, so
 * that a new mechanic cannot ship with no test saying what it may explain.
 */
class DeclaredMechanicsTest {

    private static final long NOW = 1_000_000_000L;

    /** How {@link MovementPredictor.Impulses} takes a declaration of this type in. */
    private enum Family {
        /** Adds the declared vector to the bound, on both axes. */
        VELOCITY,
        /** Frees the vertical axis; the horizontal one stays judged. */
        FLIGHT,
        /** Carries no vector: the speed comes from the live attribute, never from the declaration. */
        ATTRIBUTE,
        /** Explains no displacement at all, whatever vector it is declared with. */
        NO_MOTION
    }

    private static final Map<MechanicType, Family> FAMILIES = Map.of(
        MechanicType.KNOCKBACK, Family.VELOCITY,
        MechanicType.LAUNCH, Family.VELOCITY,
        MechanicType.SUSTAINED_VELOCITY, Family.VELOCITY,
        MechanicType.AOE_PULL, Family.VELOCITY,
        MechanicType.AOE_PUSH, Family.VELOCITY,
        MechanicType.FLIGHT_GRANT, Family.FLIGHT,
        MechanicType.SPEED_MODIFIER, Family.ATTRIBUTE,
        MechanicType.POSTURE_OVERRIDE, Family.NO_MOTION,
        MechanicType.INVULNERABILITY_WINDOW, Family.NO_MOTION);

    private final SessionState state = new SessionState();

    @Test
    void everyMechanicTypeHasAFamily() {
        assertEquals(EnumSet.allOf(MechanicType.class), FAMILIES.keySet(),
            "a mechanic type with no scenario here has no test saying what its declaration may explain");
    }

    @ParameterizedTest
    @EnumSource(MechanicType.class)
    void aDeclarationExplainsExactlyWhatItsTypeSays(final MechanicType type) {
        final Family family = FAMILIES.get(type);
        switch (family) {
            case VELOCITY -> velocityIsExplainedUpToTheDeclaredVectorOnly(type);
            case FLIGHT -> flightFreesTheVerticalAxisOnly(type);
            case ATTRIBUTE -> theSpeedComesFromTheAttributeNotFromTheDeclaration(type);
            case NO_MOTION -> noDisplacementIsExplained(type);
        }
    }

    // ------------------------------------------------------------------ families

    /** A falling player (dy -0.1) declared as thrown by (0.6, 1.2, 0): that, and not a hair more. */
    private void velocityIsExplainedUpToTheDeclaredVectorOnly(final MechanicType type) {
        final MovementContext declared = MovementContext.airborne().withMechanics(
            List.of(new CustomMechanic(type, Optional.of(new Vector(0.6, 1.2, 0)))));
        falling();

        assertEquals(Set.of(), checks(judge(declared, 0.6, 69.9 + 1.2, 0, false)),
            type + ": the declared displacement is not a violation");
        assertEquals(Set.of(MovementPredictor.SPEED, MovementPredictor.FLY),
            checks(judge(MovementContext.airborne(), 0.6, 69.9 + 1.2, 0, false)),
            type + ": the same displacement undeclared is judged on both axes");
        assertEquals(Set.of(MovementPredictor.SPEED), checks(judge(declared, 0.9, 69.9 + 1.2, 0, false)),
            type + ": horizontally beyond the declared vector stays a violation");
        assertEquals(Set.of(MovementPredictor.FLY), checks(judge(declared, 0.6, 69.9 + 1.4, 0, false)),
            type + ": vertically beyond the declared vector stays a violation");
    }

    /** A grant frees the vertical axis; a flight that is also fast is still judged horizontally. */
    private void flightFreesTheVerticalAxisOnly(final MechanicType type) {
        final MovementContext granted = MovementContext.airborne().withMechanics(
            List.of(new CustomMechanic(type, Optional.empty())));
        hovering();

        assertEquals(Set.of(MovementPredictor.FLY), checks(judge(MovementContext.airborne(), 0, 70, 0, false)),
            type + ": hovering without the grant is flight");
        assertEquals(Set.of(), checks(judge(granted, 0, 70, 0, false)), type + ": hovering with the grant");
        assertEquals(Set.of(), checks(judge(granted, 0, 75, 0, false)), type + ": climbing with the grant");
        assertEquals(Set.of(MovementPredictor.SPEED), checks(judge(granted, 0.9, 70, 0, false)),
            type + ": the horizontal axis is still judged");
    }

    /** The declaration carries no vector the envelope reads: only the live speed attribute widens it. */
    private void theSpeedComesFromTheAttributeNotFromTheDeclaration(final MechanicType type) {
        final CustomMechanic declaration = new CustomMechanic(type, Optional.of(new Vector(0.3, 0, 0)));
        final MovementContext declaredOnly = MovementContext.grounded().withMechanics(List.of(declaration));
        final MovementContext faster = MovementContext.grounded()
            .withLimits(PlayerLimits.vanilla().withMovementSpeed(0.3));
        standing();

        assertEquals(Set.of(MovementPredictor.SPEED), checks(judge(declaredOnly, 0.3, 64, 0, true)),
            type + ": a declaration alone does not whitelist a speed");
        assertEquals(Set.of(), checks(judge(faster.withMechanics(List.of(declaration)), 0.3, 64, 0, true)),
            type + ": the speed the attribute shows is honest");
        assertEquals(Set.of(MovementPredictor.SPEED), checks(judge(faster.withMechanics(List.of(declaration)), 0.6, 64, 0, true)),
            type + ": beyond what the attribute shows is not");
    }

    /** Posture and invulnerability move nothing: not even a vector smuggled in with them is read. */
    private void noDisplacementIsExplained(final MechanicType type) {
        final MovementContext declared = MovementContext.grounded().withMechanics(List.of(
            new CustomMechanic(type, Optional.of(new Vector(5, 5, 5))),
            new CustomMechanic(type, Optional.empty())));
        standing();

        assertEquals(Set.of(), checks(judge(declared, 0.1, 64, 0, true)),
            type + ": an honest step is not made a violation by being declared");
        assertEquals(Set.of(MovementPredictor.SPEED), checks(judge(declared, 0.3, 64, 0, true)),
            type + ": the vector is not read: the speed is judged as if nothing was declared");
    }

    // ------------------------------------------------------------------ helpers

    /** Two packets: one tick of free fall known, so the momentum is known and the next one is judged. */
    private void falling() {
        state.moved(0, 70, 0, false, false);
        state.moved(0, 69.9, 0, false, false);
    }

    private void hovering() {
        state.moved(0, 70, 0, false, false);
        state.moved(0, 70, 0, false, false);
    }

    private void standing() {
        state.moved(0, 64, 0, true, false);
        state.moved(0, 64, 0, true, false);
    }

    private Judgement judge(final MovementContext context, final double x, final double y, final double z,
                            final boolean onGround) {
        return MovementPredictor.judge(state, context, x, y, z, onGround, NOW);
    }

    private static Set<CheckId> checks(final Judgement judgement) {
        return judgement.divergences().stream().map(Divergence::check).collect(Collectors.toSet());
    }
}
