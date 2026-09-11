package dev.btc.core.integrity.sanction;

/**
 * What a moderation decision does.
 *
 * <p>Ordered from the least to the most intrusive. Everything up to and including {@link #RESTRICT}
 * may be issued automatically; {@link #KICK} and {@link #BAN} may not, and {@link #BAN} is refused
 * outright when attributed to automatic detection.
 */
public enum SanctionType {

    /** A note with no in-game effect, kept so a pattern can be seen later. */
    NOTE,

    /** A warning the player is told about, with no further effect. */
    WARN,

    /** Chat is withheld. */
    MUTE,

    /** A specific privilege is withdrawn — flight, an item, an interaction — for a bounded time. */
    RESTRICT,

    /** The player is moved to an isolated area or server pending review. */
    QUARANTINE,

    /** The session is ended; the player may return immediately. */
    KICK,

    /** Access is refused. Only ever a human decision. */
    BAN;

    /** Whether the platform may issue this on its own. */
    public boolean issuableAutomatically() {
        return this == NOTE || this == RESTRICT || this == QUARANTINE;
    }
}
