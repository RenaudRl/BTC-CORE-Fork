package dev.btc.core.api.drop;

/**
 * What kind of event produced a drop roll.
 *
 * <p>This is a convenience classification, not an authority. The server derives it from the loot
 * context and from the loot table identifier, both of which a datapack can arrange freely, so a
 * provider that must be exact should read {@link DropContext#lootTable()} instead.
 */
public enum DropSource {

    /** A block was broken, exploded, burned or otherwise destroyed. */
    BLOCK,

    /** An entity died, or was sheared, milked or interacted with. */
    ENTITY,

    /** A container is being filled from its loot table — structure chests, barrels, minecarts. */
    CONTAINER,

    /** A fishing rod caught something. */
    FISHING,

    /** Anything else: villager gifts, archaeology, cat morning gifts, {@code /loot} with a bare table. */
    OTHER
}
