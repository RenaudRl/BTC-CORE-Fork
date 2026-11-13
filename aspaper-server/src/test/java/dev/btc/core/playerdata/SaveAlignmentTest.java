package dev.btc.core.playerdata;

import dev.btc.core.playerdata.SaveAlignment.Mode;
import dev.btc.core.playerdata.SaveAlignment.Plan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 5.1: which players the save of a Slime world is aligned with. Pure: no server, nothing is saved. */
class SaveAlignmentTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private final AtomicLong clock = new AtomicLong();
    private final SaveAlignment alignment = new SaveAlignment(clock::get);

    /** Everybody online, persistent and owned by the calling thread, unless the test says otherwise. */
    private static final class Players implements SaveAlignment.Probe {
        final Set<UUID> offline = new HashSet<>();
        final Set<UUID> npcs = new HashSet<>();
        final Set<UUID> foreign = new HashSet<>();

        @Override
        public boolean persistent(UUID player) {
            return !npcs.contains(player);
        }

        @Override
        public boolean online(UUID player) {
            return !offline.contains(player);
        }

        @Override
        public boolean ownedHere(UUID player) {
            return !foreign.contains(player);
        }
    }

    private static Set<UUID> everyone(Plan plan) {
        Set<UUID> all = new HashSet<>(plan.inline());
        all.addAll(plan.scheduled());
        return all;
    }

    @Test
    @DisplayName("the players in the world when it is saved are selected")
    void worldSaveSelectsPlayersPresentInTheWorld() {
        Plan plan = alignment.onWorldSaved(List.of(ALICE, BOB), new Players());

        assertEquals(Set.of(ALICE, BOB), everyone(plan));
    }

    @Test
    @DisplayName("so are the players who left it since its last save")
    void worldSaveSelectsPlayersWhoLeftSinceTheLastSave() {
        alignment.onLeft(CAROL);

        Plan plan = alignment.onWorldSaved(List.of(ALICE), new Players());

        assertEquals(Set.of(ALICE, CAROL), everyone(plan));
    }

    @Test
    @DisplayName("a leaver is selected by one save, not by every later one")
    void leaversAreForgottenOnceTheWorldIsSaved() {
        alignment.onLeft(CAROL);
        alignment.onWorldSaved(List.of(), new Players());

        assertEquals(Set.of(), everyone(alignment.onWorldSaved(List.of(), new Players())));
    }

    @Test
    @DisplayName("someone who left and came back is one player of the plan, not two")
    void aPlayerWhoLeftAndCameBackIsSelectedOnce() {
        alignment.onLeft(ALICE);
        alignment.onLeft(ALICE);

        Plan plan = alignment.onWorldSaved(List.of(ALICE), new Players());

        assertEquals(1, plan.size());
    }

    @Test
    @DisplayName("a player NPC is never selected: it has no player file")
    void nonPersistentPlayersAreNeverSelected() {
        Players players = new Players();
        players.npcs.add(BOB);
        alignment.onLeft(BOB);

        Plan plan = alignment.onWorldSaved(List.of(ALICE, BOB), players);

        assertEquals(Set.of(ALICE), everyone(plan));
    }

    @Test
    @DisplayName("the players this thread owns are for now, the others are for their own thread")
    void ownedPlayersAreInlineOthersAreScheduled() {
        Players players = new Players();
        players.foreign.add(CAROL);
        alignment.onLeft(CAROL);

        Plan plan = alignment.onWorldSaved(List.of(ALICE), players);

        assertEquals(List.of(ALICE), plan.inline());
        assertEquals(List.of(CAROL), plan.scheduled());
    }

    @Test
    @DisplayName("someone who left and is offline was saved by their disconnection: nothing to align")
    void offlineLeaversAreSkipped() {
        Players players = new Players();
        players.offline.add(CAROL);
        alignment.onLeft(CAROL);

        Plan plan = alignment.onWorldSaved(List.of(ALICE), players);

        assertEquals(Set.of(ALICE), everyone(plan));
    }

    @Test
    @DisplayName("exposedBy lists those in the world and those who left since its save, and consumes nobody")
    void exposedByCountsPresentPlayersAndLeaversAndConsumesNothing() {
        alignment.onLeft(CAROL);

        assertEquals(Set.of(ALICE, CAROL), alignment.exposedBy(List.of(ALICE)));
        assertEquals(Set.of(ALICE, CAROL), alignment.exposedBy(List.of(ALICE)), "asking twice changes nothing");
        assertEquals(Set.of(CAROL), everyone(alignment.onWorldSaved(List.of(), new Players())), "the save still sees the leaver");
    }

    @Test
    @DisplayName("the age of the last save is unknown before one, then counted in whole seconds")
    void secondsSinceTheLastSave() {
        assertEquals(OptionalLong.empty(), alignment.secondsSinceLastSave());

        clock.set(TimeUnit.SECONDS.toNanos(100));
        alignment.onWritten();
        clock.set(TimeUnit.SECONDS.toNanos(190) + TimeUnit.MILLISECONDS.toNanos(900));

        assertEquals(OptionalLong.of(90), alignment.secondsSinceLastSave());
    }

    @Test
    @DisplayName("a save request is not a save: only a successful write stamps the age")
    void aSaveRequestDoesNotStampTheAge() {
        clock.set(TimeUnit.SECONDS.toNanos(100));
        alignment.onWorldSaved(List.of(ALICE), new Players());

        assertEquals(OptionalLong.empty(), alignment.secondsSinceLastSave());
    }

    @Test
    @DisplayName("the plan reads as one line: how many, how many now, how many scheduled")
    void thePlanDescribesItself() {
        Players players = new Players();
        players.foreign.add(BOB);

        Plan plan = alignment.onWorldSaved(List.of(ALICE, BOB, CAROL), players);

        assertEquals("3 player(s) to align with the save of world island_1: 2 now, 1 scheduled", plan.describe("island_1"));
    }

    @Test
    @DisplayName("off and observe are modes; enforce is not one of this release and falls back with a warning")
    void onlyOffAndObserveAreModes() {
        assertEquals(Optional.of(Mode.OBSERVE), Mode.parse(" Observe "));
        assertEquals(Optional.of(Mode.OFF), Mode.parse("off"));
        assertEquals(Optional.empty(), Mode.parse("enforce"));
        assertTrue(Mode.parse(null).isEmpty());
    }
}
