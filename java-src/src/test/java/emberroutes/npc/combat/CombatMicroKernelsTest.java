package emberroutes.npc.combat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CombatMicroKernelsTest {
    @Test
    void staggerPressureChecksBudgetAndSecondarySafetyMargin() {
        int staggeredMask = CombatTargetState.FLAG_VALID
                | CombatTargetState.FLAG_STANDING
                | CombatTargetState.FLAG_STAGGERED;

        // budget = 100 * 0.65 + 50 + 550 = 665ms; required = 665 + 250 = 915ms
        assertEquals(665.0F, StaggerPressure.computeCommitBudgetMs(100.0F), 0.1F);

        // 剩余硬直窗 1800 - 400 = 1400ms > 915ms，且无次级近敌 -> 安全压进白嫖
        assertEquals(
                StaggerPressure.Decision.COMMIT_PRESS,
                StaggerPressure.evaluate(1000L, 1400L, 100.0F, 2200.0F, staggeredMask));

        // 剩余硬直窗仅剩 1800 - 1100 = 700ms <= 915ms -> 保持风筝
        assertEquals(
                StaggerPressure.Decision.HOLD_KITE,
                StaggerPressure.evaluate(1000L, 2100L, 100.0F, 3000.0F, staggeredMask));

        // 次级站立丧尸 800ms 后抵达 <= 915ms -> 不贪刀
        assertEquals(
                StaggerPressure.Decision.HOLD_KITE,
                StaggerPressure.evaluate(1000L, 1100L, 100.0F, 800.0F, staggeredMask));
    }

    @Test
    void combatLocomotionSolverAppliesCooldownPushoutAndWallSlide() {
        EmberSquadDirector dir = new EmberSquadDirector();
        int standing = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_STANDING;
        int crawler = CombatTargetState.FLAG_VALID
                | CombatTargetState.FLAG_PRONE_OR_CRAWL
                | CombatTargetState.FLAG_CRAWLER;
        dir.upsertManualThreat(99, 10.8F, 10.0F, 0.0F, 0.0F, 0.0F, crawler, 1000L);

        CombatLocomotionSolver.CombatMoveState moveState = new CombatLocomotionSolver.CombatMoveState();
        CombatLocomotionSolver.MoveCommand cmd = new CombatLocomotionSolver.MoveCommand();
        // 攻击冷却期内 + 贴身 0.9 格 + 身侧存在爬行者 + 左侧 x=9.3 处为墙面，观察切向滑移而非撞墙死锁
        EmberSquadDirector.PassabilityProbe leftWall = (x1, y1, x2, y2, z) -> x2 >= 9.3F;
        CombatLocomotionSolver.computeMeleeStep(
                moveState, dir, "npc_1",
                10.0F, 10.0F, 0,
                10.9F, 10.0F, standing,
                10.1F, 10.0F,
                0.70F, 1.05F, 1.40F, 1.80F,
                true, false, 2.60F,
                null, null, 0,
                false, 0.0F, 0.0F, 0.0F,
                null, leftWall, 10.0, 0.05F, cmd);

        assertTrue(cmd.wantMove);
        assertEquals(CombatLocomotionSolver.RadialMode.RETREAT, cmd.radialMode);
        assertTrue(Math.hypot(cmd.dirX, cmd.dirY) > 0.9F);
    }

    @Test
    void hordeRetreatEnforcesMinDwellAndCooldown() {
        HordeRetreatPolicy policy = new HordeRetreatPolicy();
        EmberSquadDirector.PassabilityProbe open = (x1, y1, x2, y2, z) -> true;

        // 群 centroid 在 (10,10)，NPC 在 (12,10) 距离 2.0 < 3.0 -> 触发背离外推至 (15,10)
        assertTrue(policy.update(12.0F, 10.0F, 0, true, 10.0F, 10.0F, 10.0, open));
        assertTrue(policy.isActive());
        assertEquals(15.0F, policy.getTargetX(), 0.05F);
        assertEquals(10.0F, policy.getTargetY(), 0.05F);

        // 未满 1.35s 最小驻留，即使已到达目标也维持撤退状态防抖
        assertTrue(policy.update(15.0F, 10.0F, 0, true, 10.0F, 10.0F, 10.8, open));
        assertTrue(policy.isActive());

        // 满 1.35s 且离开危险区后结束撤退并进入 4.0s 冷却
        assertFalse(policy.update(15.0F, 10.0F, 0, true, 10.0F, 10.0F, 11.4, open));
        assertFalse(policy.isActive());

        // 冷却期 4.0s 内不再复发触发
        assertFalse(policy.update(11.5F, 10.0F, 0, true, 10.0F, 10.0F, 13.0, open));
        assertTrue(policy.update(11.5F, 10.0F, 0, true, 10.0F, 10.0F, 15.5, open));
    }

    @Test
    void virtualAimReadinessSafetyConeAndCqbSweep() {
        NpcVirtualAim aim = new NpcVirtualAim();
        // 从 0 rad 转向目标 (+10, 0) 带 y 方向速度 +2 格/s（0.12s 提前量 = +0.24 格）
        aim.aimAtTarget(0.0F, 0.0F, 0.0F, 0.0F, 10.0F, 0.0F, 1.0F, 0.0F, 2.0F, 15.0F, 0.05F);
        assertTrue(aim.isActive());
        assertEquals(0.24F, aim.getDesiredWorldY(), 0.005F);
        assertTrue(aim.getPitchDegrees() > 0.0F);

        // 90° -> 270°/s 阻尼速率：大角度偏差时 0.1s 最大可转不超过 27°
        NpcVirtualAim bigTurn = new NpcVirtualAim();
        bigTurn.aimAtTarget(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 10.0F, 0.0F, 0.0F, 0.0F, 15.0F, 0.10F);
        assertEquals(27.0F, bigTurn.getAimYawDeg(), 0.2F);

        // 友军在 ±12° 安全锥内阻塞开火；偏开后放行
        assertTrue(AiAimingReadiness.isBlockedByFriendlyCone(
                0.0F, 0.0F, 1.0F, 0.0F, 8.0F,
                new float[]{4.0F}, new float[]{0.3F}, 1));
        assertFalse(AiAimingReadiness.isBlockedByFriendlyCone(
                0.0F, 0.0F, 1.0F, 0.0F, 8.0F,
                new float[]{4.0F}, new float[]{2.5F}, 1));

        // 急迫度开火阈值：贴脸 2 格时 aimingDelay=6 可立刻甩枪；8 格远距要求更充分缩圈
        assertTrue(AiAimingReadiness.isAimReady(6.0F, 0.0F, 4.0F, 2.0F, 12.0F, false));
        assertFalse(AiAimingReadiness.isAimReady(6.0F, 0.0F, 4.0F, 10.0F, 12.0F, false));

        // CQB 4 相位 210°/s 切角推进
        CqbController cqb = new CqbController();
        cqb.setSectorYaws(new float[]{0.0F, 1.0F, 2.0F, -1.5F}, 4);
        NpcVirtualAim cqbAim = new NpcVirtualAim();
        cqb.stepSectorSweep(cqbAim, 0.0F, 0.0F, 0.0F, 0.0F, 8.0F, 0.90);
        assertEquals(1, cqb.getPhaseCursor());
    }
}
