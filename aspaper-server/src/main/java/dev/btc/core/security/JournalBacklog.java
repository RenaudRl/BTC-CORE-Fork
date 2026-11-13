package dev.btc.core.security;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Holds the journal lines raised while the schema is still being verified, and hands them over once it is.
 *
 * <p>The schema is applied on the database thread, so for a moment after start-up the journal is not
 * ready. The lines raised in that moment are the start-up ones, such as the op lock finding an op outside
 * its list: dropping them loses exactly what an administrator wants to read first.
 *
 * <p>Three states. {@code CLOSED}, the initial one: there is nothing to wait for (storage off, or the
 * schema failed), a line is dropped. {@code HOLDING}: a line is kept, up to {@code capacity}; the oldest
 * are kept, the newest are dropped, so a flood cannot grow memory. {@code OPEN}: the caller writes.
 * Thread-safe: a line can be raised from any thread.
 */
final class JournalBacklog<T> {

    private enum State { CLOSED, HOLDING, OPEN }

    private final int capacity;
    private final ArrayDeque<T> held = new ArrayDeque<>();
    private State state = State.CLOSED;
    private int dropped;

    JournalBacklog(final int capacity) {
        this.capacity = capacity;
    }

    /** The schema check has been launched: from now on lines are held until {@link #open}. */
    synchronized void hold() {
        this.state = State.HOLDING;
    }

    /**
     * Offers a line.
     *
     * @return true when the journal is open and the caller must write the line itself; false when the
     *         line was held for later, or dropped
     */
    synchronized boolean offer(final T line) {
        switch (this.state) {
            case OPEN:
                return true;
            case HOLDING:
                if (this.held.size() < this.capacity) {
                    this.held.addLast(line);
                } else {
                    this.dropped++;
                }
                return false;
            default:
                return false;
        }
    }

    /** The journal is ready: hands the held lines, oldest first, to {@code write}, then opens. */
    synchronized void open(final Consumer<T> write) {
        this.held.forEach(write);
        this.held.clear();
        this.state = State.OPEN;
    }

    /** The journal will not open: forgets what was held, and drops from now on. Returns how many were lost. */
    synchronized int giveUp() {
        final int lost = this.held.size() + this.dropped;
        this.held.clear();
        this.state = State.CLOSED;
        return lost;
    }

    /** How many lines were dropped because the backlog was full. */
    synchronized int dropped() {
        return this.dropped;
    }
}
