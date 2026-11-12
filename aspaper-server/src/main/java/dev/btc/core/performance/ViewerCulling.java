package dev.btc.core.performance;

import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What one player does not see ({@code performance.raytrace-culling}): the ids of the entities the
 * last scan found behind opaque blocks.
 *
 * <p>The set is replaced, never changed, so the tracker reads it from any thread. One scan at a
 * time: a player whose scan still runs is not scanned again, nothing queues up.
 */
public final class ViewerCulling {

    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile IntSet hidden = IntSets.EMPTY_SET;

    public boolean hides(int entityId) {
        return this.hidden.contains(entityId);
    }

    /** True for the one caller that may start a scan; {@link #publish} ends it. */
    public boolean claim() {
        return this.scanning.compareAndSet(false, true);
    }

    /** The set must not change once handed over. */
    public void publish(IntSet nowHidden) {
        this.hidden = nowHidden;
        this.scanning.set(false);
    }
}
