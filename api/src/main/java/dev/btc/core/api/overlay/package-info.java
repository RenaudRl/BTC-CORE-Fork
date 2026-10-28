/**
 * Per-player block overlay: blocks the server shows to one player only, without touching the world.
 *
 * <h2>Why</h2>
 * A plugin that fakes blocks with raw packets has to fight the server: every chunk resend and every
 * vanilla block update erases its blocks, the client's block-change prediction has to be acknowledged
 * by hand, and the movement checks see the real world, so standing on a fake floor reads as flying.
 * The overlay lives in the server instead. The server writes it into what it sends to that player
 * (after each chunk, and in place of vanilla block updates on a covered cell), answers interactions
 * with a covered cell itself ({@link dev.btc.core.api.overlay.PlayerOverlayBlockInteractEvent}), and
 * lets the movement checks count cells marked solid.
 *
 * <h2>Threading</h2>
 * An overlay is written on the thread that owns its player: the main thread on Paper, the player's
 * region thread on Folia. Writes from any other thread throw {@link java.lang.IllegalStateException}.
 * Reads ({@code getBlock}, {@code isCovered}, {@code isSolid}, {@code size}) are safe from any thread
 * and see the last completed write.
 *
 * <h2>Life cycle</h2>
 * An overlay belongs to one player and to the world the player is in. It is emptied, without any
 * packet (the client drops its own copy of the world), when the player changes world — teleport,
 * portal, or respawn in another world — and when the player disconnects. A player who never gets an
 * overlay costs nothing: the server checks one field per outgoing packet and stops there.
 *
 * <h2>Limits</h2>
 * <ul>
 *   <li>Placing a real block <em>into</em> a covered cell (clicking a real neighbour) is left to
 *       vanilla: the real block is placed and stays hidden under the overlay for this player.</li>
 *   <li>Vanilla's own collision ("moved wrongly") still sees the real world. Solid cells only exempt
 *       the flight checks (vanilla and Sentinel) and Sentinel's ground and phase checks.</li>
 *   <li>Block updates sent inside a bundle packet are not rewritten.</li>
 * </ul>
 *
 * @since 26.3 (fork build after 26.3.build.1)
 */
package dev.btc.core.api.overlay;
