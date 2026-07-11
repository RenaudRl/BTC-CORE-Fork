package dev.btc.core.api.event;

import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired BEFORE vanilla damage calculation (armor, enchant, resistance).
 * Plugins can read, modify, or cancel the damage before any reduction is applied.
 * <p>
 * This event fires from the NMS hook in {@code LivingEntity.hurt()} prior to
 * calling {@code actuallyHurt()}. It replaces the post-calculation
 * {@code EntityDamageByEntityEvent} MONITOR pattern used by many plugins.
 * <p>
 * Priority usage:
 * <ul>
 *   <li>{@code LOW} — modify base damage before other plugins</li>
 *   <li>{@code NORMAL} — read/modify after most plugins</li>
 *   <li>{@code MONITOR} — read-only, final value before vanilla processing</li>
 * </ul>
 */
public class PreDamageCalculationEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();

    private final Entity damagee;
    private final Entity damager;
    private double baseDamage;
    private boolean cancelled;

    public PreDamageCalculationEvent(Entity damagee, Entity damager, double baseDamage) {
        this.damagee = damagee;
        this.damager = damager;
        this.baseDamage = baseDamage;
    }

    /**
     * @return The entity receiving damage.
     */
    public Entity getDamagee() {
        return damagee;
    }

    /**
     * @return The entity dealing damage, or {@code null} for environmental damage.
     */
    public Entity getDamager() {
        return damager;
    }

    /**
     * @return The raw damage amount before armor/enchant reduction.
     */
    public double getBaseDamage() {
        return baseDamage;
    }

    /**
     * Sets the base damage that will be passed to vanilla calculation.
     *
     * @param baseDamage The new damage value.
     */
    public void setBaseDamage(double baseDamage) {
        this.baseDamage = baseDamage;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
