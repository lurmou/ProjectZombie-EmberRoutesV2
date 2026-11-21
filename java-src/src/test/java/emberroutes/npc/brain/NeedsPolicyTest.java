package emberroutes.npc.brain;

import emberroutes.npc.brain.NeedsPolicy.UrgentNeed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NeedsPolicyTest {
    @Test
    void idleThresholdsTriggerAtExpectedLevels() {
        assertEquals(UrgentNeed.NONE, NeedsPolicy.evaluateUrgentNeed(0.24F, 0.19F, 0.64F, false, false, UrgentNeed.NONE));
        assertEquals(UrgentNeed.EAT, NeedsPolicy.evaluateUrgentNeed(0.25F, 0.30F, 0.80F, false, false, UrgentNeed.NONE));
        assertEquals(UrgentNeed.DRINK, NeedsPolicy.evaluateUrgentNeed(0.10F, 0.20F, 0.80F, false, false, UrgentNeed.NONE));
        assertEquals(UrgentNeed.SLEEP, NeedsPolicy.evaluateUrgentNeed(0.10F, 0.10F, 0.65F, false, false, UrgentNeed.NONE));
    }

    @Test
    void workingToleranceRaisesThresholdAndPreventsPingPong() {
        // Working suppresses mild hunger/thirst/fatigue below 0.50 / 0.50 / 0.80
        assertEquals(UrgentNeed.NONE, NeedsPolicy.evaluateUrgentNeed(0.49F, 0.49F, 0.79F, false, true, UrgentNeed.NONE));
        assertEquals(UrgentNeed.EAT, NeedsPolicy.evaluateUrgentNeed(0.50F, 0.10F, 0.10F, false, true, UrgentNeed.NONE));
        assertEquals(UrgentNeed.DRINK, NeedsPolicy.evaluateUrgentNeed(0.30F, 0.50F, 0.10F, false, true, UrgentNeed.NONE));
        assertEquals(UrgentNeed.SLEEP, NeedsPolicy.evaluateUrgentNeed(0.30F, 0.30F, 0.80F, false, true, UrgentNeed.NONE));

        // Bleeding always bypasses working suppression
        assertEquals(UrgentNeed.BANDAGE, NeedsPolicy.evaluateUrgentNeed(0.90F, 0.90F, 0.90F, true, true, UrgentNeed.NONE));
    }

    @Test
    void exitHysteresisRetainsCurrentNeedUntilSafeFloor() {
        assertEquals(UrgentNeed.EAT, NeedsPolicy.evaluateUrgentNeed(0.15F, 0.0F, 0.0F, false, false, UrgentNeed.EAT));
        assertEquals(UrgentNeed.NONE, NeedsPolicy.evaluateUrgentNeed(0.10F, 0.0F, 0.0F, false, false, UrgentNeed.EAT));

        assertEquals(UrgentNeed.DRINK, NeedsPolicy.evaluateUrgentNeed(0.0F, 0.15F, 0.0F, false, false, UrgentNeed.DRINK));
        assertEquals(UrgentNeed.NONE, NeedsPolicy.evaluateUrgentNeed(0.0F, 0.10F, 0.0F, false, false, UrgentNeed.DRINK));

        assertEquals(UrgentNeed.SLEEP, NeedsPolicy.evaluateUrgentNeed(0.0F, 0.0F, 0.25F, false, false, UrgentNeed.SLEEP));
        assertEquals(UrgentNeed.NONE, NeedsPolicy.evaluateUrgentNeed(0.0F, 0.0F, 0.15F, false, false, UrgentNeed.SLEEP));
    }
}
