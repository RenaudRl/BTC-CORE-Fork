package dev.btc.core.integrity.engine;

/**
 * What the server knows about one player's movement capabilities at the instant a packet is judged.
 *
 * <p>Every number here is read from the live player — attributes with their modifiers folded in,
 * effects with their amplifier — and never from a constant (task 3.4, design D15). A speed potion, a
 * sprint modifier, an island buff on {@code movement_speed}, a jump-boost effect: all of them change the
 * prediction through this record without anyone declaring anything, because they are server state and
 * the server can read its own state.
 *
 * <p>The flags name the situations where vanilla physics is not the simple ground/air model of
 * {@link MovementPredictor}: the engine does not model them yet, so it leaves the corresponding axis
 * unjudged rather than guess. Each one is a false-positive family the observation phase would
 * otherwise journal by the thousand.
 *
 * @param movementSpeed {@code movement_speed}, modifiers included — sprinting and Speed are modifiers
 * @param jumpStrength {@code jump_strength}
 * @param jumpBoostPower what Jump Boost adds to a jump: {@code 0.1 * (amplifier + 1)}, {@code 0} without
 * @param gravity {@code gravity}, per tick
 * @param stepHeight {@code step_height}: a supported player rises this much without any velocity
 * @param slowFalling whether Slow Falling caps gravity while descending
 * @param levitating whether Levitation drives the vertical axis
 * @param flying whether creative-style flight is engaged (abilities {@code flying}, not {@code mayfly})
 * @param gliding whether an elytra is deployed
 * @param riptiding whether a riptide trident is spinning the player
 * @param inVehicle whether the player is a passenger — their movement packets do not move them
 * @param inFluid whether the player is in water or lava
 * @param onClimbable whether the player is on a ladder, vine or scaffolding
 */
record PlayerLimits(double movementSpeed, double jumpStrength, double jumpBoostPower, double gravity,
                    double stepHeight, boolean slowFalling, boolean levitating, boolean flying,
                    boolean gliding, boolean riptiding, boolean inVehicle, boolean inFluid,
                    boolean onClimbable) {

    /** A survival player on foot with no effect and no modifier: the attribute defaults of this version. */
    static PlayerLimits vanilla() {
        return new PlayerLimits(0.1, 0.42, 0, 0.08, 0.6,
            false, false, false, false, false, false, false, false);
    }

    /** The gravity vanilla applies this tick: Slow Falling caps it while the player is descending. */
    double effectiveGravity(final double previousDy) {
        return slowFalling && previousDy <= 0 ? Math.min(gravity, 0.01) : gravity;
    }

    /** The vertical velocity a jump from the ground gives, boost included. */
    double jumpPower() {
        return jumpStrength + jumpBoostPower;
    }

    PlayerLimits withMovementSpeed(final double value) {
        return new PlayerLimits(value, jumpStrength, jumpBoostPower, gravity, stepHeight, slowFalling,
            levitating, flying, gliding, riptiding, inVehicle, inFluid, onClimbable);
    }

    PlayerLimits withJumpBoostPower(final double value) {
        return new PlayerLimits(movementSpeed, jumpStrength, value, gravity, stepHeight, slowFalling,
            levitating, flying, gliding, riptiding, inVehicle, inFluid, onClimbable);
    }

    PlayerLimits withSlowFalling(final boolean value) {
        return new PlayerLimits(movementSpeed, jumpStrength, jumpBoostPower, gravity, stepHeight, value,
            levitating, flying, gliding, riptiding, inVehicle, inFluid, onClimbable);
    }

    PlayerLimits withLevitating(final boolean value) {
        return new PlayerLimits(movementSpeed, jumpStrength, jumpBoostPower, gravity, stepHeight,
            slowFalling, value, flying, gliding, riptiding, inVehicle, inFluid, onClimbable);
    }

    PlayerLimits withFlying(final boolean value) {
        return new PlayerLimits(movementSpeed, jumpStrength, jumpBoostPower, gravity, stepHeight,
            slowFalling, levitating, value, gliding, riptiding, inVehicle, inFluid, onClimbable);
    }

    PlayerLimits withInFluid(final boolean value) {
        return new PlayerLimits(movementSpeed, jumpStrength, jumpBoostPower, gravity, stepHeight,
            slowFalling, levitating, flying, gliding, riptiding, inVehicle, value, onClimbable);
    }

    PlayerLimits withOnClimbable(final boolean value) {
        return new PlayerLimits(movementSpeed, jumpStrength, jumpBoostPower, gravity, stepHeight,
            slowFalling, levitating, flying, gliding, riptiding, inVehicle, inFluid, value);
    }
}
