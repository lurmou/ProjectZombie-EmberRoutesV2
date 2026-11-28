package emberroutes.npc.combat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RangedTacticsControllerTest {
    @Test
    void vanillaAimAndRecoilReadinessGatesWork() {
        assertTrue(AiAimingReadiness.isAimReady(1.0F, 0.0F, 2.0F, 8.0F, 14.0F, false));
        assertFalse(AiAimingReadiness.isAimReady(1.0F, 1.5F, 2.0F, 8.0F, 14.0F, false));
        assertFalse(AiAimingReadiness.isAimReady(6.0F, 0.0F, 2.0F, 8.0F, 14.0F, false));
        assertFalse(AiAimingReadiness.isAimReady(1.0F, 0.0F, 12.0F, 8.0F, 14.0F, false));
    }

    @Test
    void blockedLosTriggersLateralSidestepInsteadOfMeleeCharge() {
        RangedTacticsController.RepositionCommand cmd = new RangedTacticsController.RepositionCommand();
        EmberSquadDirector.PassabilityProbe allWalkable = (x1, y1, x2, y2, z) -> true;
        // 假设正东方向 x=5 处有一根柱子挡住从 (0,0) 看向 (10,0) 的射线，但侧移到 |y|>=1.0 就能看清目标
        RangedTacticsController.LosProbe pillarLos = (x1, y1, x2, y2, z) -> Math.abs(y1) >= 1.0F;

        RangedTacticsController.computeRangedReposition(
                0.0F, 0.0F, 0,
                10.0F, 0.0F,
                15.0F, 2.75F,
                false, false,
                false, 0.0F, 0.0F, 0.0F,
                allWalkable, pillarLos, cmd);

        assertTrue(cmd.shouldMove);
        assertTrue(cmd.directVector);
        assertTrue(cmd.foundClearLos);
        assertTrue(cmd.aimWhileMoving);
        assertTrue(Math.abs(cmd.dirY) > 0.8F);
        assertTrue(Math.abs(cmd.goalY) >= 1.0F);
    }

    @Test
    void holdAnchorPreventsSteppingOutsideDefenseCircle() {
        RangedTacticsController.RepositionCommand cmd = new RangedTacticsController.RepositionCommand();
        EmberSquadDirector.PassabilityProbe allWalkable = (x1, y1, x2, y2, z) -> true;
        RangedTacticsController.LosProbe alwaysClear = (x1, y1, x2, y2, z) -> true;

        // 敌贴到 2.0m 触发后撤，但角色已在原地驻守圆边缘 (0, 0)，驻守中心在 (4.2, 0) 半径 4.3m -> 往左逃出就会越界，应禁止退离
        RangedTacticsController.computeRangedReposition(
                0.0F, 0.0F, 0,
                2.0F, 0.0F,
                14.0F, 2.75F,
                true, false,
                true, 4.2F, 0.0F, 4.3F,
                allWalkable, alwaysClear, cmd);
        assertFalse(cmd.shouldMove);
    }

    @Test
    void rangedRetreatAvoidsRearZombiePackAndFlagsBoxedIn() {
        EmberSquadDirector dir = new EmberSquadDirector();
        int standing = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_STANDING;
        // 前方主目标在 (12.0, 10.0) 触发后撤 (dist=2.0 < 2.75)，背后 (8.0, 10.0) 周围有僵尸群
        dir.upsertManualThreat(1, 12.0F, 10.0F, 0.0F, 0.0F, 0.0F, standing, 1000L);
        dir.upsertManualThreat(2, 8.0F, 10.0F, 0.0F, 0.0F, 0.0F, standing, 1000L);
        dir.upsertManualThreat(3, 8.2F, 10.5F, 0.0F, 0.0F, 0.0F, standing, 1000L);
        dir.upsertManualThreat(4, 8.2F, 9.5F, 0.0F, 0.0F, 0.0F, standing, 1000L);

        RangedTacticsController.RepositionCommand cmd = new RangedTacticsController.RepositionCommand();
        EmberSquadDirector.PassabilityProbe allWalkable = (x1, y1, x2, y2, z) -> true;
        RangedTacticsController.LosProbe alwaysClear = (x1, y1, x2, y2, z) -> true;

        RangedTacticsController.computeRangedReposition(
                10.0F, 10.0F, 0,
                12.0F, 10.0F,
                15.0F, 2.75F,
                true, false,
                false, 0.0F, 0.0F, 0.0F,
                dir, allWalkable, alwaysClear, cmd);
        assertTrue(cmd.shouldMove);
        assertFalse(cmd.retreatBlocked);
        assertTrue(Math.abs(cmd.dirY) > 0.55F,
                "Ranged retreat must steer laterally instead of backing into rear pack, dir=("
                        + cmd.dirX + ", " + cmd.dirY + ")");

        // 四面 1.0m 内都被僵尸围死 -> retreatBlocked 必须置位，交上层触发应急推搡
        dir.clearAllThreats();
        dir.upsertManualThreat(11, 11.0F, 10.0F, 0.0F, 0.0F, 0.0F, standing, 2000L);
        dir.upsertManualThreat(12, 9.0F, 10.0F, 0.0F, 0.0F, 0.0F, standing, 2000L);
        dir.upsertManualThreat(13, 10.0F, 11.0F, 0.0F, 0.0F, 0.0F, standing, 2000L);
        dir.upsertManualThreat(14, 10.0F, 9.0F, 0.0F, 0.0F, 0.0F, standing, 2000L);

        RangedTacticsController.computeRangedReposition(
                10.0F, 10.0F, 0,
                11.0F, 10.0F,
                15.0F, 2.75F,
                true, false,
                false, 0.0F, 0.0F, 0.0F,
                dir, allWalkable, alwaysClear, cmd);
        assertTrue(cmd.retreatBlocked, "Surrounded ranged NPC within 1.0m on all 4 sides must report retreatBlocked=true");
    }
}
