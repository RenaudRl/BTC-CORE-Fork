package dev.btc.core.parallel;

/**
 * A teleport whose events were fired on the thread of a world, and whose journey - a listener sent
 * the entity to a world that thread does not tick - is left to the main thread. The main thread
 * runs the teleport again, from its top since entities override it; this tells that run, once,
 * that its events are behind it, so a listener that charges for the journey charges once.
 *
 * <p>Main thread only: one teleport is resumed at a time.
 */
public final class ResumedTeleport {

    private Object traveller;

    /** Runs {@code teleport} for {@code traveller}, whose events were fired already. */
    public void resume(Object traveller, Runnable teleport) {
        this.traveller = traveller;
        try {
            teleport.run();
        } finally {
            this.traveller = null; // a run that gave up before its events leaves nothing to a later teleport
        }
    }

    /**
     * Is the teleport that asks the one being resumed? True once: a teleport the journey itself
     * makes - a passenger, a spectator, the same entity again - fires its own events.
     */
    public boolean take(Object traveller) {
        if (this.traveller != traveller) return false;
        this.traveller = null;
        return true;
    }
}
