package com.infernalsuite.asp.api.visual;

import java.util.UUID;

/**
 * Identifies one client-side display entity owned by a single viewer.
 *
 * @param type display variant
 * @param entityId protocol entity identifier
 * @param entityUuid protocol entity UUID
 * @param viewerUuid UUID of the player that received the display
 * @since 26.2
 */
public record VirtualDisplayHandle(
    VirtualDisplayType type,
    int entityId,
    UUID entityUuid,
    UUID viewerUuid
) {

    public VirtualDisplayHandle {
        if (type == null || entityUuid == null || viewerUuid == null) {
            throw new IllegalArgumentException("type, entityUuid and viewerUuid must not be null");
        }
    }
}
