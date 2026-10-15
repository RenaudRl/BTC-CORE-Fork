package dev.btc.core.mining;

import java.util.Objects;

/**
 * The rule that keeps the break-speed provider and the server-driven set in one plugin's hands.
 *
 * <p>Both shape how every player digs, and both are read without a lock. Two plugins taking turns at
 * replacing them would leave the server with whichever one loaded last, silently; a third-party
 * plugin could arm the server-driven mode everywhere. So a binding or a publication is accepted only
 * when nobody holds the place, when its holder is no longer enabled, or when it comes from the holder
 * itself (a reload).
 */
final class SingleOwner {

    private SingleOwner() {
    }

    /**
     * Whether {@code candidate} may take or keep the place.
     *
     * @param current        the current holder, or {@code null} when the place is free
     * @param currentEnabled whether the current holder is still enabled
     * @param candidate      who asks
     */
    static <O> boolean accepts(final O current, final boolean currentEnabled, final O candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return current == null || !currentEnabled || current.equals(candidate);
    }
}
