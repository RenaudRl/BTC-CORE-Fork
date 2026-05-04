package dev.btc.core.event.entity;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Called when an entity targets a player.
 * Allows cancellation of the targeting.
 */
public class EntityTargetPlayerEvent extends EntityEvent implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private final Player target;
    private final TargetReason reason;
    private boolean cancelled;

    public enum TargetReason {
        CLOSEST_PLAYER,
        ATTACKED_BY,
        COLLISION,
        RANDOM,
        CUSTOM
    }

    public EntityTargetPlayerEvent(@NotNull Entity entity, @NotNull Player target, @NotNull TargetReason reason) {
        super(entity);
        this.target = target;
        this.reason = reason;
    }

    @NotNull
    public Player getTarget() { return target; }

    @NotNull
    public TargetReason getReason() { return reason; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public @NotNull HandlerList getHandlers() { return handlers; }

    public static @NotNull HandlerList getHandlerList() { return handlers; }
}
