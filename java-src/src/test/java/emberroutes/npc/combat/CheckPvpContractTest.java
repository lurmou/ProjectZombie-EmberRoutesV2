package emberroutes.npc.combat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CheckPvpContractTest {
    @Test
    void friendlyFireRulesBlockHumanFriendlyPairsOnly() {
        // C7 纯 Java 决策表：友方人类/NPC 相互阻断，僵尸/动物/同体不阻断
        assertTrue(FriendlyFireRules.evaluateFriendlyFlags(true, false, true, false, false));
        assertFalse(FriendlyFireRules.evaluateFriendlyFlags(true, false, true, false, true));
        assertFalse(FriendlyFireRules.evaluateFriendlyFlags(true, false, false, true, false));
        assertFalse(FriendlyFireRules.evaluateFriendlyFlags(false, true, true, false, false));
        assertFalse(FriendlyFireRules.evaluateFriendlyFlags(true, false, false, false, false));
    }

    @Test
    void targetStateBitmasksEnforceFallAndGetUpTransitionAndGroundStompGates() {
        int transitionMask = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_POSTURE_TRANSITION;
        assertFalse(CombatTargetState.allowsStompOrShove(transitionMask));
        assertFalse(CombatTargetState.isGroundAttackable(transitionMask));

        int proneMask = CombatTargetState.FLAG_VALID
                | CombatTargetState.FLAG_PRONE_OR_CRAWL
                | CombatTargetState.FLAG_CRAWLER;
        assertTrue(CombatTargetState.allowsStompOrShove(proneMask));
        assertTrue(CombatTargetState.isGroundAttackable(proneMask));
        assertTrue(CombatTargetState.isCrawler(proneMask));
    }
}
