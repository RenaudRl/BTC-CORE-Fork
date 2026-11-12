package dev.btc.core.parallel;

import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The one scoreboard of the server, which every world thread changes when an entity with a score
 * or a team dies. Runs on the real tables: no server is needed for them.
 */
class ScoreboardTablesTest {

    private static final int ENTITIES_PER_WORLD = 20_000;
    private static final int WAIT_SECONDS = 60; // tables broken by two writers can also spin forever

    @Test // T19
    void scoreboard_removals_from_two_threads_keep_tables_consistent() throws Exception {
        Scoreboard scoreboard = new Scoreboard();
        Objective kills = scoreboard.addObjective("kills", ObjectiveCriteria.DUMMY, Component.literal("kills"),
            ObjectiveCriteria.RenderType.INTEGER, false, null);
        PlayerTeam mobs = scoreboard.addPlayerTeam("mobs");
        List<String> island = names("island");
        List<String> dungeon = names("dungeon");
        for (List<String> world : List.of(island, dungeon)) {
            for (String entity : world) {
                scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(entity), kills).set(1);
                scoreboard.addPlayerToTeam(entity, mobs);
            }
        }

        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService worlds = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<?> first = worlds.submit(() -> removeAll(scoreboard, island, together));
            Future<?> second = worlds.submit(() -> removeAll(scoreboard, dungeon, together));
            first.get(WAIT_SECONDS, TimeUnit.SECONDS); // what a removal threw comes out here
            second.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            worlds.shutdownNow();
        }

        assertEquals(List.of(), List.copyOf(scoreboard.getTrackedPlayers()));
        assertEquals(List.of(), List.copyOf(scoreboard.listPlayerScores(kills)));
        assertEquals(List.of(), List.copyOf(mobs.getPlayers()));
        for (List<String> world : List.of(island, dungeon)) {
            for (String entity : world) assertNull(scoreboard.getPlayersTeam(entity));
        }
    }

    /** What {@code Scoreboard.entityRemoved} does for each dead entity of a world. */
    private static Void removeAll(Scoreboard scoreboard, List<String> entities, CyclicBarrier together) throws Exception {
        together.await(WAIT_SECONDS, TimeUnit.SECONDS);
        for (String entity : entities) {
            scoreboard.resetAllPlayerScores(ScoreHolder.forNameOnly(entity));
            scoreboard.removePlayerFromTeam(entity);
        }
        return null;
    }

    private static List<String> names(String world) {
        List<String> names = new ArrayList<>(ENTITIES_PER_WORLD);
        for (int i = 0; i < ENTITIES_PER_WORLD; i++) names.add(world + "-" + i);
        return names;
    }
}
