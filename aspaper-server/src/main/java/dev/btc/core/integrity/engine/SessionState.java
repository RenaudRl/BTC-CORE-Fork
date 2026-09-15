package dev.btc.core.integrity.engine;

/**
 * What the engine remembers about one player's session between two packets.
 *
 * <p>Owned by the region thread that owns the player: every hook fires there, after
 * {@code PacketUtils.ensureRunningOnSameThread}, so no field here needs to be atomic. The map that
 * holds these states is concurrent because players live on different regions; the state itself is not.
 *
 * <p>Nothing here is a verdict. It is the minimum a check needs to tell a packet that continues a
 * session from one that starts it, and a position sent about the old place from one sent about the
 * new one.
 */
final class SessionState {

    /** A teleport the fork has armed and the client has not confirmed yet. */
    private boolean awaitingTeleportAck;

    /** The client confirmed; its next position must be the destination. */
    private boolean expectingArrival;

    private double teleportX;
    private double teleportY;
    private double teleportZ;

    /** Timer balance: how many milliseconds ahead of real time the client's packet cadence runs. */
    private double timerBalanceMillis;

    /** {@code 0} until the first movement packet: the first one starts the clock, it is not measured. */
    private long lastMoveNanos;

    void teleportExpected(double x, double y, double z) {
        awaitingTeleportAck = true;
        expectingArrival = false;
        teleportX = x;
        teleportY = y;
        teleportZ = z;
        // A teleport is where honest cadence breaks: a world change stalls the client, then it
        // flushes. Measuring across it would flag the flush. The clock restarts on the first packet
        // sent about the new place.
        resetTimer();
    }

    void teleportAcknowledged() {
        if (!awaitingTeleportAck) {
            return;
        }
        awaitingTeleportAck = false;
        expectingArrival = true;
    }

    /** Consumes the arrival expectation. Call only when {@link #expectingArrival()}. */
    void arrived() {
        expectingArrival = false;
    }

    boolean awaitingTeleportAck() {
        return awaitingTeleportAck;
    }

    boolean expectingArrival() {
        return expectingArrival;
    }

    double teleportX() {
        return teleportX;
    }

    double teleportY() {
        return teleportY;
    }

    double teleportZ() {
        return teleportZ;
    }

    double timerBalanceMillis() {
        return timerBalanceMillis;
    }

    void timerBalanceMillis(double value) {
        timerBalanceMillis = value;
    }

    long lastMoveNanos() {
        return lastMoveNanos;
    }

    void lastMoveNanos(long value) {
        lastMoveNanos = value;
    }

    void resetTimer() {
        timerBalanceMillis = 0;
        lastMoveNanos = 0;
    }
}
