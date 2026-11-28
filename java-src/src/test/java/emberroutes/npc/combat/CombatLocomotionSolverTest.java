package emberroutes.npc.combat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CombatLocomotionSolverTest {
    @Test
    void closedLoop1v1Avoids20HzLegTwitchAndZeroCrossingLateralSpikes() {
        EmberSquadDirector dir = new EmberSquadDirector();
        int standing = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_STANDING;
        CombatLocomotionSolver.CombatMoveState state = new CombatLocomotionSolver.CombatMoveState();
        CombatLocomotionSolver.MoveCommand cmd = new CombatLocomotionSolver.MoveCommand();
        EmberSquadDirector.PassabilityProbe open = (x1, y1, x2, y2, z) -> true;

        float npcX = 10.0F;
        float npcY = 10.0F;
        float tgtX = 11.40F;
        float tgtY = 10.0F;
        int prevLongSign = 0;
        int flips = 0;
        int rapidFlipFrame = -100;
        int rapidFlips = 0;
        int lateralSpikes = 0;

        float idealMin = 0.72F;
        float idealMax = 1.08F;
        float coolMin = 1.45F;
        float coolMax = 1.85F;

        for (int frame = 0; frame < 120; frame++) {
            double nowSim = frame * 0.05;
            // 单轮接近 -> 出刀后退风筝 -> 稳态持距，验证不出现 15-20Hz 腿部抽搐或 90° 横向闪跳
            tgtX -= 0.015F;
            boolean onCooldown = frame >= 30 && frame <= 75;
            dir.upsertManualThreat(1, tgtX, tgtY, 0.0F, -0.30F, 0.0F, standing, Math.round(nowSim * 1000.0));

            CombatLocomotionSolver.computeMeleeStep(
                    state, dir, "npc_1",
                    npcX, npcY, 0,
                    tgtX, tgtY, standing,
                    tgtX - 0.9F, tgtY,
                    idealMin, idealMax, coolMin, coolMax,
                    onCooldown, false, 2.65F,
                    null, null, 0,
                    false, 0.0F, 0.0F, 0.0F,
                    null, open, nowSim, 0.05F, cmd);

            if (cmd.wantMove) {
                float dx = tgtX - npcX;
                float dy = tgtY - npcY;
                float d = (float) Math.hypot(dx, dy);
                if (d > 1.0E-4F) {
                    float fX = dx / d;
                    float fY = dy / d;
                    float dotFwd = cmd.dirX * fX + cmd.dirY * fY;
                    float dotLat = Math.abs(-cmd.dirX * fY + cmd.dirY * fX);
                    if (dotLat > 0.85F && Math.abs(dotFwd) < 0.25F) {
                        lateralSpikes++;
                    }
                    int curSign = dotFwd > 0.15F ? 1 : (dotFwd < -0.15F ? -1 : 0);
                    if (curSign != 0 && prevLongSign != 0 && curSign != prevLongSign) {
                        flips++;
                        if (frame - rapidFlipFrame <= 5) {
                            rapidFlips++;
                        }
                        rapidFlipFrame = frame;
                    }
                    if (curSign != 0) {
                        prevLongSign = curSign;
                    }
                }
                npcX += cmd.dirX * 1.6F * 0.05F;
                npcY += cmd.dirY * 1.6F * 0.05F;
            }
        }

        assertTrue(flips <= 4, "Longitudinal sign flips should be <= 4 over 120 frames, was " + flips);
        assertEquals(0, rapidFlips, "Zero rapid <=5-frame ping-pong sign flips allowed");
        assertEquals(0, lateralSpikes, "Zero 90-deg zero-crossing lateral spikes allowed");
    }

    @Test
    void midSwingAllowsAimStrafeKiteWithoutDroppingAimOrRunning() {
        EmberSquadDirector dir = new EmberSquadDirector();
        int standing = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_STANDING;
        dir.upsertManualThreat(1, 10.5F, 10.0F, 0.0F, 0.0F, 0.0F, standing, 1000L);

        CombatLocomotionSolver.CombatMoveState state = new CombatLocomotionSolver.CombatMoveState();
        CombatLocomotionSolver.MoveCommand cmd = new CombatLocomotionSolver.MoveCommand();
        EmberSquadDirector.PassabilityProbe open = (x1, y1, x2, y2, z) -> true;

        // 挥击进行期即使目标突然贴近到 0.45m：绝不收架转身跑，而是保持 aimStrafe 边举架边顺滑后撤（走 A）
        CombatLocomotionSolver.computeMeleeStep(
                state, dir, "npc_1",
                10.0F, 10.0F, 0,
                1, 10.45F, 10.0F, standing,
                10.3F, 10.0F,
                0.70F, 1.05F, 1.40F, 1.80F,
                false, true, 2.65F,
                null, null, 0,
                false, 0.0F, 0.0F, 0.0F,
                null, open, 1.05, 0.05F, cmd);
        assertTrue(cmd.aimStrafe, "Must keep aim stance during swing (no turning back to run)");
        assertFalse(cmd.runGear, "Must never trigger sprint/run during active swing");
        assertTrue(cmd.wantMove, "Too-close zombie during swing should smoothly backpedal while swinging");
        assertTrue(cmd.dirX < -0.5F, "Backpedal direction should move away from approaching zombie");
    }

    @Test
    void velocityPredictionStartsBackpedalingBeforeZombieReachesBody() {
        EmberSquadDirector dir = new EmberSquadDirector();
        int standing = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_STANDING;

        CombatLocomotionSolver.CombatMoveState state = new CombatLocomotionSolver.CombatMoveState();
        CombatLocomotionSolver.MoveCommand cmd = new CombatLocomotionSolver.MoveCommand();
        EmberSquadDirector.PassabilityProbe open = (x1, y1, x2, y2, z) -> true;

        // 僵尸从 1.35m 处以 ~1.4m/s 连续朝 NPC (10,10) 逼近，即使刚踩入 0.72~1.08 环带仍应凭 0.25s 前瞻提前平稳后撤
        float tx = 1.35F + 10.0F;
        for (int i = 0; i < 6; i++) {
            double sim = i * 0.05;
            dir.upsertManualThreat(1, tx, 10.0F, 0.0F, -1.4F, 0.0F, standing, Math.round(sim * 1000.0));
            CombatLocomotionSolver.computeMeleeStep(
                    state, dir, "npc_1",
                    10.0F, 10.0F, 0,
                    1, tx, 10.0F, standing,
                    tx - 0.9F, 10.0F,
                    0.72F, 1.08F, 1.40F, 1.80F,
                    true, false, 2.65F,
                    null, null, 0,
                    false, 0.0F, 0.0F, 0.0F,
                    null, open, sim, 0.05F, cmd);
            tx -= 0.07F;
        }
        assertTrue(cmd.wantMove, "Predictive velocity kiting should engage smoothly as zombie closes in");
        assertTrue(cmd.dirX < -0.5F, "Should backpedal (-X) ahead of zombie contact, dirX=" + cmd.dirX);
    }

    @Test
    void proneSafeSideGuardPreventsSteppingAcrossProneZombieAndRangedHysteresisHolds() {
        EmberSquadDirector dir = new EmberSquadDirector();
        int standing = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_STANDING;
        int prone = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_PRONE_OR_CRAWL;

        // 近身站立僵尸 A(12.0, 10) 与脚边倒地僵尸 B(11.1, 10)，NPC P 在 (10, 10)
        dir.upsertManualThreat(1, 12.0F, 10.0F, 0.0F, 0.0F, 0.0F, standing, 1000L);
        dir.upsertManualThreat(2, 11.1F, 10.0F, 0.0F, 0.0F, 0.0F, prone, 1000L);

        CombatLocomotionSolver.SafeSideGuard guard = new CombatLocomotionSolver.SafeSideGuard();
        CombatLocomotionSolver.evaluateProneSafeSide(dir, 10.0F, 10.0F, 0, guard);
        assertTrue(guard.standingOverrideProne);
        assertTrue(guard.hasForbiddenHalfSpace);
        assertTrue(guard.isPointInForbiddenPocket(11.5F, 10.0F, 0.15F));
        assertFalse(guard.isPointInForbiddenPocket(9.2F, 10.0F, 0.15F));

        // 远程 2.55m / 3.45m 迟滞环：在 2.70m ~ 3.30m 之间连续晃动 60 帧，切换次数 <= 1
        CombatLocomotionSolver.CombatMoveState rangedState = new CombatLocomotionSolver.CombatMoveState();
        CombatLocomotionSolver.MoveCommand rCmd = new CombatLocomotionSolver.MoveCommand();
        EmberSquadDirector.PassabilityProbe open = (x1, y1, x2, y2, z) -> true;
        int modeSwitches = 0;
        boolean prevActive = rangedState.rangedRetreatActive;
        for (int f = 0; f < 60; f++) {
            float d = 3.00F + 0.30F * (float) Math.sin(f * 0.25);
            CombatLocomotionSolver.computeRangedRetreatStep(
                    rangedState, dir, "npc_r",
                    10.0F, 10.0F, 0,
                    10.0F + d, 10.0F, d,
                    null, null, 0,
                    false, 0.0F, 0.0F, 0.0F,
                    open, f * 0.05, 0.05F, rCmd);
            if (rangedState.rangedRetreatActive != prevActive) {
                modeSwitches++;
                prevActive = rangedState.rangedRetreatActive;
            }
        }
        assertTrue(modeSwitches <= 1, "Ranged hysteresis must prevent oscillation in 2.70..3.30m band, switches=" + modeSwitches);
    }

    @Test
    void meleeAdvanceClosesInAgainstFourZombieGroupInsteadOfOrbitingAway() {
        EmberSquadDirector dir = new EmberSquadDirector();
        int standing = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_STANDING;

        // 4 只站立僵尸围聚在 (12.6, 10.0) 一带，侧面 NPC_2 位于 (10.0, 10.0)，距主目标约 2.6m
        dir.upsertManualThreat(1, 12.6F, 10.0F, 0.0F, 0.0F, 0.0F, standing, 1000L);
        dir.upsertManualThreat(2, 12.8F, 10.4F, 0.0F, 0.0F, 0.0F, standing, 1000L);
        dir.upsertManualThreat(3, 12.9F, 9.6F, 0.0F, 0.0F, 0.0F, standing, 1000L);
        dir.upsertManualThreat(4, 13.1F, 10.1F, 0.0F, 0.0F, 0.0F, standing, 1000L);

        CombatLocomotionSolver.CombatMoveState state = new CombatLocomotionSolver.CombatMoveState();
        CombatLocomotionSolver.MoveCommand cmd = new CombatLocomotionSolver.MoveCommand();
        EmberSquadDirector.PassabilityProbe open = (x1, y1, x2, y2, z) -> true;

        CombatLocomotionSolver.computeMeleeStep(
                state, dir, "npc_2",
                10.0F, 10.0F, 0,
                1, 12.6F, 10.0F, standing,
                11.8F, 10.0F,
                0.70F, 1.05F, 1.40F, 1.80F,
                false, false, 2.65F,
                null, null, 0,
                false, 0.0F, 0.0F, 0.0F,
                null, open, 1.0, 0.05F, cmd);

        assertEquals(CombatLocomotionSolver.RadialMode.ADVANCE, cmd.radialMode);
        assertTrue(cmd.wantMove, "Flanking NPC must advance toward 4-zombie group instead of stalling outside range");
        // 到目标单位方向是 (+1, 0)，推进方向必须具有明显正向逼近分量
        assertTrue(cmd.dirX > 0.55F, "Advance direction must close in toward main target, dirX=" + cmd.dirX);
    }

    @Test
    void flankingBehindAllyDropsAimStanceAndMovesLaterallyToSideSlot() {
        EmberSquadDirector dir = new EmberSquadDirector();
        int standing = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_STANDING;
        dir.upsertManualThreat(1, 12.0F, 10.0F, 0.0F, 0.0F, 0.0F, standing, 1000L);

        // 1 号队友位于 (11.0, 10.0)，正挡在 2 号 NPC (10.0, 10.0) 与僵尸 (12.0, 10.0) 连线上
        float[] allyXs = { 11.0F };
        float[] allyYs = { 10.0F };
        CombatLocomotionSolver.CombatMoveState state = new CombatLocomotionSolver.CombatMoveState();
        state.aimStanceActive = true; // 模拟原本已进入举架慢走的状态
        state.radialMode = CombatLocomotionSolver.RadialMode.HOLD;
        CombatLocomotionSolver.MoveCommand cmd = new CombatLocomotionSolver.MoveCommand();
        EmberSquadDirector.PassabilityProbe open = (x1, y1, x2, y2, z) -> true;

        // 侧翼槽位在 (11.8, 11.1)，距 2 号 NPC ~2.11m，且带明显横向 (+Y) 分量
        CombatLocomotionSolver.computeMeleeStep(
                state, dir, "npc_2",
                10.0F, 10.0F, 0,
                1, 12.0F, 10.0F, standing,
                11.8F, 11.1F,
                0.70F, 1.05F, 1.40F, 1.80F,
                false, false, 2.00F,
                allyXs, allyYs, 1,
                false, 0.0F, 0.0F, 0.0F,
                null, open, 1.0, 0.05F, cmd);

        assertTrue(cmd.wantMove, "NPC behind ally must not stall in HOLD");
        assertFalse(cmd.aimStrafe, "NPC blocked behind ally should drop aim stance to flank rapidly");
        assertTrue(cmd.runGear, "NPC >1.35m from side slot should run/jog to flank");
        assertTrue(cmd.dirY > 0.30F, "NPC should steer laterally toward side slot rather than pushing into ally's back, dirY=" + cmd.dirY);
    }
}
