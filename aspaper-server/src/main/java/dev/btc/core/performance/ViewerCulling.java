package dev.btc.core.performance;

import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What one player does not see ({@code performance.raytrace-culling}): the ids of the entities the
 * last scan found behind opaque blocks, and the tick of the player that scan was taken at.
 *
 * <p>The set is replaced, never changed, so the tracker reads it from any thread. One scan at a
 * time: a player whose scan still runs is not scanned again, nothing queues up. A set too old
 * hides nothing: workers that run late leave entities shown, never hidden for good.
 */
public final class ViewerCulling {

    private record Scan(IntSet hidden, int takenAtTick) {}

    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile Scan last = new Scan(IntSets.EMPTY_SET, 0);

    /**
     * @param nowTick     the tick of the player now
     * @param maxAgeTicks past this many ticks since it was taken, the last scan hides nothing
     */
    public boolean hides(int entityId, int nowTick, int maxAgeTicks) {
        Scan scan = this.last;
        return nowTick - scan.takenAtTick <= maxAgeTicks && scan.hidden.contains(entityId);
    }

    /** True for the one caller that may start a scan; {@link #publish} ends it. */
    public boolean claim() {
        return this.scanning.compareAndSet(false, true);
    }

    /**
     * Worker thread, after {@link #claim}: runs the rays and ends the claim whatever happens. A scan
     * that fails hides nothing, and its failure is handed over, not kept.
     */
    public void scan(Supplier<IntSet> rays, int takenAtTick, Consumer<Throwable> onFailure) {
        IntSet hidden = IntSets.EMPTY_SET;
        try {
            hidden = rays.get();
        } catch (Throwable failure) {
            onFailure.accept(failure);
        } finally {
            this.publish(hidden, takenAtTick);
        }
    }

    /**
     * The set must not change once handed over.
     *
     * @param takenAtTick the tick of the player when the eye and the boxes were copied, not when the rays ended
     */
    public void publish(IntSet nowHidden, int takenAtTick) {
        this.last = new Scan(nowHidden, takenAtTick);
        this.scanning.set(false);
    }
}
