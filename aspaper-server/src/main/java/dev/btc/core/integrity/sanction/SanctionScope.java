package dev.btc.core.integrity.sanction;

/** How far a sanction reaches. */
public enum SanctionScope {

    /** Applies on one backend only. */
    SERVER,

    /**
     * Applies everywhere on the network.
     *
     * <p>The distinction is not cosmetic: a mute that only binds one backend is lifted by typing
     * {@code /server lobby}, which makes it no mute at all.
     */
    NETWORK
}
