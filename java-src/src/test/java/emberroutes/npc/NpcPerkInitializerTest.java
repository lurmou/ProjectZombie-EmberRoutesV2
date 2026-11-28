package emberroutes.npc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NpcPerkInitializerTest {
    @Test
    void rollRandomProducesBoundedVariedNonZeroBaselinePerks() {
        Random rng = new Random(42L);
        Set<String> signatures = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            NpcPerkInitializer.PerkRoll roll = NpcPerkInitializer.rollRandom(rng);
            assertTrue(roll.strength() >= 4 && roll.strength() <= 7, "strength out of [4,7]: " + roll.strength());
            assertTrue(roll.fitness() >= 4 && roll.fitness() <= 7, "fitness out of [4,7]: " + roll.fitness());
            assertTrue(roll.nimble() >= 1 && roll.nimble() <= 4, "nimble out of [1,4]: " + roll.nimble());
            assertTrue(roll.sprinting() >= 0 && roll.sprinting() <= 3);
            assertTrue(roll.lightfoot() >= 0 && roll.lightfoot() <= 3);
            assertTrue(roll.sneak() >= 0 && roll.sneak() <= 3);
            assertTrue(roll.maintenance() >= 0 && roll.maintenance() <= 3);
            assertTrue(roll.aiming() >= 1 && roll.aiming() <= 4);
            assertTrue(roll.reloading() >= 1 && roll.reloading() <= 3);

            int[] melee = roll.meleeLevels();
            assertEquals(6, melee.length);
            boolean hasMeleeSpec = false;
            for (int lvl : melee) {
                assertTrue(lvl >= 0 && lvl <= 10);
                if (lvl >= 2) hasMeleeSpec = true;
            }
            assertTrue(hasMeleeSpec, "Every spawned NPC must have at least 1 melee specialty >= 2");

            int[] life = roll.lifeLevels();
            assertEquals(9, life.length);
            boolean hasLifeSpec = false;
            for (int lvl : life) {
                assertTrue(lvl >= 0 && lvl <= 10);
                if (lvl >= 2) hasLifeSpec = true;
            }
            assertTrue(hasLifeSpec, "Every spawned NPC must have at least 1 life specialty >= 2");

            signatures.add(roll.strength() + "," + roll.fitness() + "," + roll.nimble()
                    + "," + melee[0] + "," + melee[1] + "," + life[0] + "," + life[3]);
        }

        assertTrue(signatures.size() >= 25, "Expected rich random perk diversity across 200 NPCs");
    }
}
