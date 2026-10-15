package dev.btc.core.api.mining;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * The blocks whose dig the server measures, published once by the plugin that owns the break-speed
 * provider.
 *
 * <p><b>What it is for.</b> A crop of hardness 0 can only take time to break if the client stops
 * predicting the break, which it does when the server shows it a {@code block_break_speed} of 0. The
 * server switches a player to that view — the "server-driven mode" — when they aim at one of these
 * blocks or one of their neighbours, so the first swing already finds the client unable to break the
 * crop on its own. Without this set, a player only enters the mode on their first hit, which costs
 * one visible flicker. Holding one of the {@link #armingItems()} arms nothing by itself.
 *
 * <p>The mode is inert outside this set: the server only drives and finishes the dig of a block (or
 * state) listed here, at the rate the provider answers ({@link BreakDecision.ServerDriven}) or at
 * the normal rate; any other block, a hoe in hand or not, is dug the normal way with the normal view.
 *
 * <p>Immutable: every collection is copied, every {@link BlockData} cloned, so the caller may reuse
 * or modify what it passed in. The platform resolves it once, at publication, into a table indexed by
 * block-state id; nothing here is read during a tick.
 *
 * @param blocks      block types of which every state arms the mode (the vanilla crops, typically)
 * @param states      single block states that arm the mode, such as a CraftEngine crop; they are
 *                    armed for the view only, the platform never finishes a non-vanilla block
 * @param armingItems item types the plugin associates with these blocks (the hoes, typically); holding
 *                    one does not arm the mode, only aiming at a listed block does
 * @param timing      the timings of the mode
 * @since 26.3 (fork build after 26.3.build.1)
 */
public record ServerDrivenBlocks(Set<Material> blocks, Set<BlockData> states, Set<Material> armingItems,
                                 Timing timing) {

    /** Validates and copies. */
    public ServerDrivenBlocks {
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(states, "states");
        Objects.requireNonNull(armingItems, "armingItems");
        Objects.requireNonNull(timing, "timing");
        blocks = Set.copyOf(blocks);
        states = states.stream().map(state -> Objects.requireNonNull(state, "state").clone())
            .collect(Collectors.toUnmodifiableSet());
        armingItems = Set.copyOf(armingItems);
    }

    /** Copies out, so a caller cannot reach the platform's own instances. */
    @Override
    public Set<BlockData> states() {
        return this.states.stream().map(BlockData::clone).collect(Collectors.toUnmodifiableSet());
    }

    /** Whether nothing arms the mode: publishing it is the same as clearing the set. */
    public boolean isEmpty() {
        return this.blocks.isEmpty() && this.states.isEmpty() && this.armingItems.isEmpty();
    }

    /**
     * The timings of the server-driven mode, in server ticks.
     *
     * @param lingerTicks           how long the mode lasts after the last aim, held arming item or
     *                              server-driven break; 5 to 600 (0.25 s to 30 s)
     * @param aimCheckIntervalTicks how often the platform looks at what the player aims at; 1 to 20
     * @param aimNeighbourRadius    how far around the aimed block an armed block still arms the mode
     *                              (0 = the aimed block only); 0 to 2. Aiming at the farmland under a
     *                              crop then arms the mode before the crop is reached
     * @param heartbeatTicks        how many ticks without a swing before a server-driven dig pauses
     *                              (it never aborts on its own); 2 to 40
     * @param postBreakPauseTicks   the pause between two server-driven breaks, vanilla's
     *                              {@code destroyDelay}; 0 to 20
     */
    public record Timing(int lingerTicks, int aimCheckIntervalTicks, int aimNeighbourRadius, int heartbeatTicks,
                         int postBreakPauseTicks) {

        /** Lower and upper bounds, inclusive. */
        public static final int MIN_LINGER_TICKS = 5;
        public static final int MAX_LINGER_TICKS = 600;
        public static final int MIN_AIM_INTERVAL_TICKS = 1;
        public static final int MAX_AIM_INTERVAL_TICKS = 20;
        public static final int MIN_AIM_RADIUS = 0;
        public static final int MAX_AIM_RADIUS = 2;
        public static final int MIN_HEARTBEAT_TICKS = 2;
        public static final int MAX_HEARTBEAT_TICKS = 40;
        public static final int MIN_POST_BREAK_PAUSE_TICKS = 0;
        public static final int MAX_POST_BREAK_PAUSE_TICKS = 20;

        /** 2 s of linger, an aim check every 2 ticks over a radius of 1, 5 ticks of heartbeat and pause. */
        public static final Timing DEFAULT = new Timing(40, 2, 1, 5, 5);

        /**
         * Validates the bounds.
         *
         * @throws IllegalArgumentException when a value is outside its bounds; the caller is expected
         *                                  to keep its previous, valid timing
         */
        public Timing {
            requireWithin("lingerTicks", lingerTicks, MIN_LINGER_TICKS, MAX_LINGER_TICKS);
            requireWithin("aimCheckIntervalTicks", aimCheckIntervalTicks, MIN_AIM_INTERVAL_TICKS, MAX_AIM_INTERVAL_TICKS);
            requireWithin("aimNeighbourRadius", aimNeighbourRadius, MIN_AIM_RADIUS, MAX_AIM_RADIUS);
            requireWithin("heartbeatTicks", heartbeatTicks, MIN_HEARTBEAT_TICKS, MAX_HEARTBEAT_TICKS);
            requireWithin("postBreakPauseTicks", postBreakPauseTicks, MIN_POST_BREAK_PAUSE_TICKS, MAX_POST_BREAK_PAUSE_TICKS);
        }

        private static void requireWithin(final String name, final int value, final int min, final int max) {
            if (value < min || value > max) {
                throw new IllegalArgumentException(name + " = " + value + " is outside [" + min + ", " + max + "]");
            }
        }
    }
}
