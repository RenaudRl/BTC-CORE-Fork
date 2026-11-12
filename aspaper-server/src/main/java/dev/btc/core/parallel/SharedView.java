package dev.btc.core.parallel;

import java.util.Collection;
import java.util.List;

/**
 * What a reader gets of a collection that every world may change - the objectives, the teams and
 * the members of a team of the one scoreboard of the server.
 *
 * <p>Vanilla hands out the live view. Under {@code parallel-world-ticking} a world thread may change
 * it while another walks it, which breaks the walk: the reader then gets a copy, taken under the
 * lock every change is made under. The thread that holds that lock still gets the collection
 * itself: it is the one changing it.
 */
public final class SharedView {

    private SharedView() {
    }

    /**
     * @param parallel {@code parallel-world-ticking} is on
     * @param lock     what every change of {@code live} is made under
     * @return {@code live} itself with the option off, as before
     */
    public static <T> Collection<T> of(boolean parallel, Object lock, Collection<T> live) {
        if (!parallel || Thread.holdsLock(lock)) return live;
        synchronized (lock) {
            return List.copyOf(live);
        }
    }
}
