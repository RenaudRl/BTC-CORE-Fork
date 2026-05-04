package dev.btc.core.event.entity;

import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Called before damage is applied to an entity.
 * Allows modification or cancellation of the damage.
 */
public class PreDamageCalculationEvent extends EntityEvent implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private final Entity damager;
    private double finalDamage;
    private boolean cancelled;

    public PreDamageCalculationEvent(@NotNull Entity entity, @Nullable Entity damager, double damage) {
        super(entity);
        this.damager = damager;
        this.finalDamage = damage;
    }

    @Nullable
    public Entity getDamager() { return damager; }

    public double getFinalDamage() { return finalDamage; }

    public void setFinalDamage(double damage) { this.finalDamage = damage; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public @NotNull HandlerList getHandlers() { return handlers; }

    public static @NotNull HandlerList getHandlerList() { return handlers; }
}
