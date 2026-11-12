package dev.btc.core.world;

import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityCensusTest {

    private static final int CATEGORIES = 8;
    private static final int TYPES = 40;
    private static final int MONSTER = 3;
    private static final int CREATURE = 1;
    private static final int ZOMBIE = 7;
    private static final int SKELETON = 8;
    private static final int COW = 20;

    /** The whole list, spelt out here on purpose: a reason added to or dropped from the caps must be a decision. */
    private static final Set<SpawnReason> MADE_BY_THE_GAME_OR_FARMED = EnumSet.of(
        SpawnReason.NATURAL, SpawnReason.SPAWNER, SpawnReason.TRIAL_SPAWNER, SpawnReason.BREEDING,
        SpawnReason.EGG, SpawnReason.DISPENSE_EGG, SpawnReason.SPAWNER_EGG, SpawnReason.SLIME_SPLIT,
        SpawnReason.REINFORCEMENTS, SpawnReason.NETHER_PORTAL, SpawnReason.JOCKEY, SpawnReason.PATROL,
        SpawnReason.RAID, SpawnReason.VILLAGE_INVASION, SpawnReason.VILLAGE_DEFENSE, SpawnReason.TRAP);

    private static EntityCensus census() {
        return new EntityCensus(CATEGORIES, TYPES);
    }

    @Test
    @DisplayName("an entity that comes counts in its category and in its type, and nowhere else")
    void anEntityCountsInItsCategoryAndType() {
        EntityCensus census = census();

        census.added(MONSTER, ZOMBIE);
        census.added(MONSTER, ZOMBIE);
        census.added(MONSTER, SKELETON);
        census.added(CREATURE, COW);

        assertEquals(3, census.count(MONSTER));
        assertEquals(1, census.count(CREATURE));
        assertEquals(2, census.countOfType(ZOMBIE));
        assertEquals(1, census.countOfType(SKELETON));
        assertEquals(1, census.countOfType(COW));
        assertEquals(0, census.count(2));
        assertEquals(0, census.countOfType(0));
    }

    @Test
    @DisplayName("an entity that goes is taken off its category and its type")
    void anEntityThatGoesIsTakenOff() {
        EntityCensus census = census();
        census.added(MONSTER, ZOMBIE);
        census.added(MONSTER, SKELETON);
        census.added(CREATURE, COW);

        census.removed(MONSTER, ZOMBIE);

        assertEquals(1, census.count(MONSTER));
        assertEquals(0, census.countOfType(ZOMBIE));
        assertEquals(1, census.countOfType(SKELETON));
        assertEquals(1, census.count(CREATURE));
        assertEquals(1, census.countOfType(COW));
    }

    @Test
    @DisplayName("a category without a cap refuses nothing, however many it holds")
    void noCapRefusesNothing() {
        EntityCensus census = census();
        for (int i = 0; i < 500; i++) {
            census.added(MONSTER, ZOMBIE);
        }

        assertEquals(EntityCensus.NO_CAP, census.cap(MONSTER));
        assertFalse(census.refuses(MONSTER, SpawnReason.NATURAL));
    }

    @Test
    @DisplayName("a natural spawn comes in under the cap and is refused once the cap is reached, in that category only")
    void aSpawnIsRefusedOnceTheCapIsReached() {
        EntityCensus census = census();
        census.cap(MONSTER, 2);
        census.added(MONSTER, ZOMBIE);
        census.added(CREATURE, COW);
        census.added(CREATURE, COW);
        census.added(CREATURE, COW);

        assertFalse(census.refuses(MONSTER, SpawnReason.NATURAL), "one of two");
        census.added(MONSTER, SKELETON);
        assertTrue(census.refuses(MONSTER, SpawnReason.NATURAL), "two of two");
        assertFalse(census.refuses(CREATURE, SpawnReason.NATURAL), "the cap of the monsters is not that of the animals");

        census.removed(MONSTER, ZOMBIE);
        assertFalse(census.refuses(MONSTER, SpawnReason.NATURAL), "room again");
    }

    @Test
    @DisplayName("a cap of zero lets no capped spawn in")
    void aCapOfZeroLetsNothingIn() {
        EntityCensus census = census();

        assertEquals(0, census.cap(MONSTER, 0));

        assertTrue(census.refuses(MONSTER, SpawnReason.SPAWNER));
    }

    @Test
    @DisplayName("what a plugin, a command or an entity that already existed brings is never refused")
    void askedForOrExistingEntitiesAlwaysComeIn() {
        EntityCensus census = census();
        census.cap(MONSTER, 0);

        for (SpawnReason reason : SpawnReason.values()) {
            assertEquals(MADE_BY_THE_GAME_OR_FARMED.contains(reason), census.refuses(MONSTER, reason), reason.name());
        }
        assertFalse(census.refuses(MONSTER, null), "an entity that comes from another world carries no reason");
    }

    @Test
    @DisplayName("the mount of a refused spawn goes with it when it was made for it, and stays when it lived in the world before")
    void aMountThatLivedInTheWorldStays() {
        assertTrue(EntityCensus.madeForSpawn(false, 0), "not in the world yet");
        assertTrue(EntityCensus.madeForSpawn(true, 0), "added this tick: the spider of a jockey, the chicken made for a baby zombie");
        assertFalse(EntityCensus.madeForSpawn(true, 1), "the chicken that was there, which a baby zombie climbed on");
        assertTrue(EntityCensus.madeForSpawn(false, 1), "out of the world: nothing of the world is lost with it");
    }

    @Test
    @DisplayName("a cap below zero clears the cap")
    void aNegativeCapClearsIt() {
        EntityCensus census = census();
        census.cap(MONSTER, 0);

        assertEquals(EntityCensus.NO_CAP, census.cap(MONSTER, -5));

        assertEquals(EntityCensus.NO_CAP, census.cap(MONSTER));
        assertFalse(census.refuses(MONSTER, SpawnReason.NATURAL));
    }
}
