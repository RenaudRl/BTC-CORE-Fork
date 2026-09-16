package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.integrity.engine.MovementPredictor.Divergence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the engine remembers about one player's session between two packets.
 *
 * <p>Owned by the region thread that owns the player: every hook fires there, after
 * {@code PacketUtils.ensureRunningOnSameThread}, so no field here needs to be atomic. The map that
 * holds these states is concurrent because players live on different regions; the state itself is not.
 * The one exception is {@link #serverVelocity}: it is written from the event thread that sends the
 * velocity packet, which is the same region thread, so it is not an exception after all.
 *
 * <p>Nothing here is a verdict. It is the minimum a check needs to tell a packet that continues a
 * session from one that starts it, a position sent about the old place from one sent about the new
 * one, and the movement of this tick from the movement of the last.
 */
final class SessionState {

    /** A velocity the server sent this player, valid for the prediction until its deadline. */
    record Impulse(double x, double y, double z, long deadlineNanos) {
        boolean live(final long nowNanos) {
            return nowNanos - deadlineNanos < 0;
        }
    }

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

    // ------------------------------------------------------------------ last judged position

    /** {@code false} until a position has been recorded: the first one is a reference, not a move. */
    private boolean hasLastPosition;
    private double lastX;
    private double lastY;
    private double lastZ;

    /** The displacement of the last judged packet: the momentum this tick starts from. */
    private double lastDx;
    private double lastDy;
    private double lastDz;

    /**
     * Whether the momentum the last displacement shows is the client's. {@code false} for a reference
     * position: after a join, a teleport or an unreadable context, the client's velocity is unknown —
     * a vanilla correction teleport even keeps it ({@code Relative.DELTA}) — and a zero displacement
     * recorded there is a fiction the next tick must not be judged against.
     */
    private boolean momentumKnown;

    /** Whether the last judged position stood on something — whether a jump could start now. */
    private boolean lastSupported;

    /** What the client said about the ground with its last position. A claim, not a fact. */
    private boolean lastClaimedOnGround;

    /** Whether the vertical axis was left unjudged last tick: the momentum it left is not modelled. */
    private boolean lastVerticalFree;

    /**
     * The most horizontal momentum the next judgement may carry, from the last bound without its
     * window; infinite when unknown or when the last tick was flagged. See {@link MovementPredictor}.
     */
    private double horizontalCap = Double.POSITIVE_INFINITY;

    /** Same for the vertical axis. */
    private double verticalCap = Double.POSITIVE_INFINITY;

    /** Velocities the server sent, newest last. Purged on read. */
    private final List<Impulse> serverVelocity = new ArrayList<>(2);

    /** The session's round trip, fed from two threads; see {@link RoundTripMeter}. */
    private final RoundTripMeter roundTrip = new RoundTripMeter();

    /** When the last ping left; {@code 0} before the first. */
    private long lastPingNanos;

    /** A phase divergence and its origin, held until the server has accepted or refused the step. */
    record HeldPhase(Divergence divergence, ClientPlatform origin) {}

    /**
     * The phase divergence of the last judged packet, not journalled yet. The seam fires before
     * vanilla handles the packet; when vanilla refuses the step as clipping into a block it sets the
     * player back — {@link #teleportExpected} — within the handling of that same packet, and the
     * intrusion never happened on the server. On the bench every join produced one: the client fell
     * a gravity tick into ground it had not received, for a few packets.
     */
    private HeldPhase heldPhase;

    /** Server teleports armed in this session, corrections included. Diagnostics for 4.1. */
    private int teleportCount;

    void holdPhase(final Divergence divergence, final ClientPlatform origin) {
        heldPhase = new HeldPhase(divergence, origin);
    }

    /** The held phase divergence, now known to have been accepted, and forgotten here. */
    Optional<HeldPhase> releaseHeldPhase() {
        final Optional<HeldPhase> released = Optional.ofNullable(heldPhase);
        heldPhase = null;
        return released;
    }

    int teleportCount() {
        return teleportCount;
    }

    void teleportExpected(final double x, final double y, final double z) {
        teleportCount++;
        // A set-back refuses the step that is being judged: its intrusion did not happen.
        heldPhase = null;
        awaitingTeleportAck = true;
        expectingArrival = false;
        teleportX = x;
        teleportY = y;
        teleportZ = z;
        // A teleport is where honest cadence breaks: a world change stalls the client, then it
        // flushes. Measuring across it would flag the flush. The clock restarts on the first packet
        // sent about the new place.
        resetTimer();
        // And where momentum breaks: the client lands with the velocity the server gave it — none.
        forgetPosition();
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

    void timerBalanceMillis(final double value) {
        timerBalanceMillis = value;
    }

    long lastMoveNanos() {
        return lastMoveNanos;
    }

    void lastMoveNanos(final long value) {
        lastMoveNanos = value;
    }

    void resetTimer() {
        timerBalanceMillis = 0;
        lastMoveNanos = 0;
    }

    // ------------------------------------------------------------------ position memory

    boolean hasLastPosition() {
        return hasLastPosition;
    }

    double lastX() {
        return lastX;
    }

    double lastY() {
        return lastY;
    }

    double lastZ() {
        return lastZ;
    }

    double lastDx() {
        return lastDx;
    }

    double lastDy() {
        return lastDy;
    }

    double lastDz() {
        return lastDz;
    }

    boolean momentumKnown() {
        return momentumKnown;
    }

    boolean lastSupported() {
        return lastSupported;
    }

    boolean lastClaimedOnGround() {
        return lastClaimedOnGround;
    }

    boolean lastVerticalFree() {
        return lastVerticalFree;
    }

    double horizontalCap() {
        return horizontalCap;
    }

    double verticalCap() {
        return verticalCap;
    }

    /**
     * Records a judged position and the displacement that led to it, with no cap on the momentum.
     *
     * <p>The first record after a {@link #forgetPosition()} carries no displacement: there is nothing
     * to measure it from, and inventing one would judge the next packet against a fiction.
     */
    void moved(final double x, final double y, final double z, final boolean supported,
               final boolean verticalFree) {
        moved(x, y, z, supported, false, verticalFree, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    /** Records a judged position, what the client claimed with it, and the caps its judgement left. */
    void moved(final double x, final double y, final double z, final boolean supported,
               final boolean claimedOnGround, final boolean verticalFree,
               final double nextHorizontalCap, final double nextVerticalCap) {
        momentumKnown = hasLastPosition;
        if (hasLastPosition) {
            lastDx = x - lastX;
            lastDy = y - lastY;
            lastDz = z - lastZ;
        } else {
            lastDx = 0;
            lastDy = 0;
            lastDz = 0;
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        lastSupported = supported;
        lastClaimedOnGround = claimedOnGround;
        lastVerticalFree = verticalFree;
        horizontalCap = nextHorizontalCap;
        verticalCap = nextVerticalCap;
        hasLastPosition = true;
    }

    /** Drops the position memory: the next position is a reference, not a move. */
    void forgetPosition() {
        hasLastPosition = false;
        momentumKnown = false;
        lastDx = 0;
        lastDy = 0;
        lastDz = 0;
        lastSupported = false;
        lastClaimedOnGround = false;
        lastVerticalFree = true;
        horizontalCap = Double.POSITIVE_INFINITY;
        verticalCap = Double.POSITIVE_INFINITY;
        serverVelocity.clear();
    }

    // ------------------------------------------------------------------ server velocity

    void serverVelocity(final double x, final double y, final double z, final long deadlineNanos) {
        serverVelocity.add(new Impulse(x, y, z, deadlineNanos));
    }

    /** The server velocities still live, oldest first. Expired ones are dropped on the way. */
    List<Impulse> liveServerVelocity(final long nowNanos) {
        serverVelocity.removeIf(impulse -> !impulse.live(nowNanos));
        return List.copyOf(serverVelocity);
    }

    // ------------------------------------------------------------------ round trip

    RoundTripMeter roundTrip() {
        return roundTrip;
    }

    /** Whether a ping is due: none has left for {@code intervalNanos}. Marks one as leaving when so. */
    boolean pingDue(final long nowNanos, final long intervalNanos) {
        if (lastPingNanos != 0 && nowNanos - lastPingNanos < intervalNanos) {
            return false;
        }
        lastPingNanos = nowNanos;
        return true;
    }
}
