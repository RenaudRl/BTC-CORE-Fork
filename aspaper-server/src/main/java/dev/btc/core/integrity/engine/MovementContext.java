package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.api.integrity.IntegrityAPI.CustomMechanic;

import java.util.List;

/**
 * Everything the prediction needs from the world, read once per movement packet on the region
 * thread and handed to the pure predictor as plain values.
 *
 * <p>The split is the point: the adapter touches the world and this record is what it found; the
 * predictor never sees an entity. A test builds one of these by hand and drives the engine through
 * the seam without a server.
 *
 * @param limits the player's live capabilities (3.4)
 * @param support whether the claimed position stands on something, as the server sees it
 * @param insideSolid whether the player's box at the claimed position intersects solid collision
 * @param clientTerrainDeclared whether an extension declared the client's geometry divergent here
 *     (D8): collision-based judgements are then meaningless and are not made
 * @param mechanics the live velocity declarations for this player, in declaration order
 * @param platform the session's origin as bound by the proxy; {@link ClientPlatform#UNKNOWN} until
 *     the bridge binds it (D14). Carried into the journal so 4.5 can measure the Bedrock margin
 */
record MovementContext(PlayerLimits limits, Support support, boolean insideSolid,
                       boolean clientTerrainDeclared, List<CustomMechanic> mechanics,
                       ClientPlatform platform) {

    /** Whether something under the player's feet holds them up, in the server's geometry. */
    enum Support {
        SUPPORTED,
        UNSUPPORTED
    }

    MovementContext {
        if (limits == null || support == null || mechanics == null || platform == null) {
            throw new IllegalArgumentException("a movement context has no absent field");
        }
        mechanics = List.copyOf(mechanics);
    }

    /** A vanilla player standing on solid ground, no declaration, origin unknown. */
    static MovementContext grounded() {
        return new MovementContext(PlayerLimits.vanilla(), Support.SUPPORTED, false, false,
            List.of(), ClientPlatform.UNKNOWN);
    }

    /** A vanilla player in the air, no declaration, origin unknown. */
    static MovementContext airborne() {
        return new MovementContext(PlayerLimits.vanilla(), Support.UNSUPPORTED, false, false,
            List.of(), ClientPlatform.UNKNOWN);
    }

    boolean supported() {
        return support == Support.SUPPORTED;
    }

    MovementContext withLimits(final PlayerLimits value) {
        return new MovementContext(value, support, insideSolid, clientTerrainDeclared, mechanics, platform);
    }

    MovementContext withMechanics(final List<CustomMechanic> value) {
        return new MovementContext(limits, support, insideSolid, clientTerrainDeclared, value, platform);
    }

    MovementContext withInsideSolid(final boolean value) {
        return new MovementContext(limits, support, value, clientTerrainDeclared, mechanics, platform);
    }

    MovementContext withClientTerrainDeclared(final boolean value) {
        return new MovementContext(limits, support, insideSolid, value, mechanics, platform);
    }

    MovementContext withPlatform(final ClientPlatform value) {
        return new MovementContext(limits, support, insideSolid, clientTerrainDeclared, mechanics, value);
    }
}
