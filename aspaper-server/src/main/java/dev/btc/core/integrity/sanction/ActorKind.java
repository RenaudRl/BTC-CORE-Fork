package dev.btc.core.integrity.sanction;

/**
 * Who decided a sanction.
 *
 * <p>This is the field that turns a doctrine into an invariant. "The anticheat never bans on its own"
 * is unverifiable as prose; as a column, paired with a database constraint, it is a fact anyone can
 * check with one query.
 *
 * <p>It is also what makes an eventual assisted-review model possible: a model can only be trained on
 * human verdicts if human verdicts are distinguishable from the machine's own output. Conflating them
 * would train the model on its own predictions.
 */
public enum ActorKind {

    /** A person decided, and is named. */
    HUMAN,

    /** The platform decided, within what it is allowed to do alone — never a ban. */
    ANTICHEAT_AUTO
}
