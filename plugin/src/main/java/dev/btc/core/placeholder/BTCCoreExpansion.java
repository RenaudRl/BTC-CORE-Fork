package dev.btc.core.placeholder;

import dev.btc.core.api.BTCCoreAPI;
import io.github.miniplaceholders.api.Expansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * MiniPlaceholders expansion exposing BTC-CORE server signals.
 *
 * <p>All values are read from the stable {@link BTCCoreAPI} facade. Built and registered from
 * {@code SWPlugin#onEnable()} only when MiniPlaceholders is installed, so BTC-CORE keeps
 * MiniPlaceholders as a soft (compile-only) dependency.</p>
 *
 * <p>Global placeholders: {@code <btccore_mspt>}, {@code <btccore_mspt_int>},
 * {@code <btccore_tps>}, {@code <btccore_maintenance>}, {@code <btccore_queue_size>}.<br>
 * Audience placeholders: {@code <btccore_in_combat>}, {@code <btccore_combat_time>},
 * {@code <btccore_vanished>}, {@code <btccore_vanish_level>}, {@code <btccore_queue_position>}.</p>
 */
public final class BTCCoreExpansion {

    private BTCCoreExpansion() {
    }

    /**
     * Builds the {@code btccore} expansion. The caller is responsible for
     * {@link Expansion#register()} / {@link Expansion#unregister()}.
     *
     * @return the expansion, not yet registered
     */
    public static Expansion create() {
        return Expansion.builder("btccore")
                .author("BTC")
                .version("1.0.0")
                // ----- global placeholders (no audience required) -----
                .globalPlaceholder("mspt", (queue, ctx) -> decimal(api().getCurrentMspt()))
                .globalPlaceholder("mspt_int", (queue, ctx) -> text(Math.round(api().getCurrentMspt())))
                .globalPlaceholder("tps", (queue, ctx) -> {
                    final double mspt = api().getCurrentMspt();
                    return decimal(mspt <= 0.0 ? 20.0 : Math.min(20.0, 1000.0 / mspt));
                })
                .globalPlaceholder("maintenance", (queue, ctx) -> text(api().isMaintenanceMode()))
                .globalPlaceholder("queue_size", (queue, ctx) -> text(api().getQueueSize()))
                // ----- audience placeholders -----
                .audiencePlaceholder(Player.class, "in_combat",
                        (player, queue, ctx) -> text(api().isInCombat(player)))
                .audiencePlaceholder(Player.class, "combat_time",
                        (player, queue, ctx) -> text(api().getRemainingCombatTime(player)))
                .audiencePlaceholder(Player.class, "vanished",
                        (player, queue, ctx) -> text(api().isVanished(player)))
                .audiencePlaceholder(Player.class, "vanish_level",
                        (player, queue, ctx) -> text(api().getVanishLevel(player)))
                .audiencePlaceholder(Player.class, "queue_position",
                        (player, queue, ctx) -> text(api().getQueuePosition(player.getUniqueId())))
                .build();
    }

    private static BTCCoreAPI api() {
        return BTCCoreAPI.instance();
    }

    /**
     * Formats with {@link Locale#ROOT} so the decimal separator stays a dot regardless of the
     * server's default locale — these values are routinely fed into numeric comparisons.
     */
    private static Tag decimal(double value) {
        return Tag.selfClosingInserting(Component.text(String.format(Locale.ROOT, "%.2f", value)));
    }

    private static Tag text(long value) {
        return Tag.selfClosingInserting(Component.text(value));
    }

    private static Tag text(boolean value) {
        return Tag.selfClosingInserting(Component.text(value));
    }
}
