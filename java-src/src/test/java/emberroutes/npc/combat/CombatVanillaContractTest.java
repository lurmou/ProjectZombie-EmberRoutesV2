package emberroutes.npc.combat;

import static org.junit.jupiter.api.Assertions.*;

import emberroutes.npc.EmberContextSteering.PlayerAvoidanceMath;
import emberroutes.npc.RulesOfEngagement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CombatVanillaContractTest {
    @Test
    void targetStateTransitionLungeAndEmergencyContractsHold() {
        int transition = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_POSTURE_TRANSITION;
        assertFalse(CombatTargetState.allowsStompOrShove(transition));
        assertFalse(CombatTargetState.isGroundAttackable(transition));

        assertTrue(CombatTargetState.isEmergencySelfDefenseNeeded(RulesOfEngagement.HOLD_FIRE, false, false, true));
        assertTrue(CombatTargetState.isEmergencySelfDefenseNeeded(RulesOfEngagement.FREE_FIRE, true, false, true));
        assertTrue(CombatTargetState.isEmergencySelfDefenseNeeded(RulesOfEngagement.FREE_FIRE, false, true, true));
        assertFalse(CombatTargetState.isEmergencySelfDefenseNeeded(RulesOfEngagement.FREE_FIRE, false, false, true));
    }

    @Test
    void meleeWeaponMetricsKeepVanillaRangesLungeBonusAndWideDeadband() {
        MeleeWeaponMetrics spear = new MeleeWeaponMetrics(0.60F, 2.80F, CombatTargetState.reliableMeleeRange(2.80F), false, true, false);
        assertEquals(2.80F, spear.maxRange(), 0.001F);
        assertEquals(3.00F, spear.effectiveMaxRange(true), 0.001F);
        assertTrue(spear.idealRingMax() - spear.idealRingMin() >= 0.30F);

        MeleeWeaponMetrics bare = MeleeWeaponMetrics.unarmed(true);
        assertEquals(MeleeWeaponMetrics.VANILLA_BARE_HANDS_MAX_RANGE, bare.maxRange(), 0.001F);
        assertEquals(MeleeWeaponMetrics.VANILLA_MAX_STOMP_DIST, bare.groundHeadSwingReach(), 0.001F);
    }

    @Test
    void virtualAimProneOffsetAndVanillaPitchFormulaMatchBallisticsController() {
        NpcVirtualAim aim = new NpcVirtualAim();
        // 目标倒地且位于高一层楼 (dz = (1.0 + 0.22) - (0.0 + 0.495) = 0.725F)，水平距 4.0F
        aim.aimAtTarget(0.0F, 0.0F, 0.0F, 0.0F, 4.0F, 0.0F, 1.0F, 0.0F, 0.0F, true, 12.0F, 0.05F);
        assertEquals(1.0F + NpcVirtualAim.PRONE_HEIGHT_OFFSET, aim.getDesiredWorldZ(), 0.001F);

        float dz = (1.0F + NpcVirtualAim.PRONE_HEIGHT_OFFSET) - NpcVirtualAim.CHEST_HEIGHT_OFFSET;
        float expectedDeg = (float) Math.toDegrees(Math.atan2(dz, 4.0F) * NpcVirtualAim.VERTICAL_PROJ_SCALE);
        assertEquals(expectedDeg, aim.getPitchDegrees(), 0.05F);
    }

    @Test
    void aimingReadinessTightensWithMovingAndStaggerTimerRespectsTicks() {
        // 5.0/15.0 = 0.333 -> 静止允许 aimingDelay <= 2.66; beenMovingFor=0.5 收紧至 <= 1.995
        assertTrue(AiAimingReadiness.isAimReady(2.2F, 0.0F, 3.0F, 5.0F, 15.0F, false, 0.0F));
        assertFalse(AiAimingReadiness.isAimReady(2.2F, 0.0F, 3.0F, 5.0F, 15.0F, false, 0.50F));

        int standing = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_STANDING | CombatTargetState.FLAG_STAGGERED;
        assertEquals(
                StaggerPressure.Decision.COMMIT_PRESS,
                StaggerPressure.evaluateWithEngineTimer(1100.0F, 0L, 1000L, 100.0F, 3000.0F, standing));
        assertEquals(
                StaggerPressure.Decision.HOLD_KITE,
                StaggerPressure.evaluateWithEngineTimer(600.0F, 0L, 1000L, 100.0F, 3000.0F, standing));
    }

    @Test
    void directorSimTimeMemoryAndPerceptionContractsHold() {
        EmberSquadDirector dir = new EmberSquadDirector();
        int standing = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_STANDING;
        dir.recordScannedThreat(7, 10.0F, 10.0F, 0.0F, standing, 1_000L, 0.08F, true, true, 1, "npc_1");
        EmberSquadDirector.ThreatEntry e = dir.findById(7);
        assertNotNull(e);
        assertTrue(e.perceivedBySquad);
        assertTrue(e.heardOnly);
        assertEquals(1, e.attackedAllyPriority);

        // 未再感知且超过 HEARING_MEMORY_MS (3000ms) 后记忆过期清除 perceivedBySquad
        dir.recordScannedThreat(7, 10.2F, 10.0F, 0.0F, standing, 4_200L, 0.08F, false, false, 0, null);
        assertFalse(e.perceivedBySquad);
        assertFalse(e.heardOnly);

        dir.deactivateThreat(7);
        assertNull(dir.findById(7));
    }

    @Test
    void isoNpcUpdateLosRunsEveryFrameWithoutWallClockThrottle() throws IOException {
        Path relFromJavaSrc = Path.of("src/main/java/emberroutes/npc/IsoNPC.java");
        Path isoNpcPath = Files.exists(relFromJavaSrc)
                ? relFromJavaSrc
                : Path.of("java-src/src/main/java/emberroutes/npc/IsoNPC.java");
        assertTrue(Files.exists(isoNpcPath), "IsoNPC.java not found at " + isoNpcPath.toAbsolutePath());

        String src = Files.readString(isoNpcPath, StandardCharsets.UTF_8);
        int start = src.indexOf("public void updateLOS()");
        assertTrue(start >= 0, "IsoNPC.updateLOS() missing");
        int end = src.indexOf("@Override", start + "public void updateLOS()".length());
        assertTrue(end > start, "IsoNPC.updateLOS() end boundary missing");

        String body = src.substring(start, end);
        assertFalse(body.contains("lastLosUpdateMs"), "updateLOS() must not throttle with lastLosUpdateMs");
        assertFalse(body.contains("currentTimeMillis"), "updateLOS() must not throttle with System.currentTimeMillis()");
        assertFalse(body.contains("nanoTime"), "updateLOS() must not throttle with System.nanoTime()");
        assertTrue(body.contains("this.TestZombieSpotPlayer("), "updateLOS() must call TestZombieSpotPlayer every frame");
    }

    @Test
    void friendlySeparateBumpSuppressionAndZombiePreservationHold() {
        // 玩家 ↔ NPC 与 NPC ↔ NPC 互相分离时不触发绊倒/击倒，但保持物理推开
        assertTrue(CombatHookContext.shouldSuppressSeparateBumpFlags(true, false, true, true, null));
        assertTrue(CombatHookContext.shouldSuppressSeparateBumpFlags(true, true, true, false, null));
        assertTrue(CombatHookContext.shouldSuppressSeparateBumpFlags(true, true, true, true, null));

        // NPC 已处于 bumped / bumped-bump 态时防止连续撞击重复刷新硬直
        assertTrue(CombatHookContext.shouldSuppressSeparateBumpFlags(true, true, false, false, "bumped"));
        assertTrue(CombatHookContext.shouldSuppressSeparateBumpFlags(true, true, false, false, "bumped-bump"));

        // 玩家或 NPC 冲撞僵尸必须完整保留原版碰撞与绊倒机制
        assertFalse(CombatHookContext.shouldSuppressSeparateBumpFlags(true, false, false, false, null));
        assertFalse(CombatHookContext.shouldSuppressSeparateBumpFlags(true, true, false, false, "idle"));
    }

    @Test
    void patchCombatHooksContainsCursorVisibilityAndSeparateBumpGuards() throws IOException {
        Path relFromJavaSrc = Path.of("src/main/java/emberroutes/Patch_CombatHooks.java");
        Path patchPath = Files.exists(relFromJavaSrc)
                ? relFromJavaSrc
                : Path.of("java-src/src/main/java/emberroutes/Patch_CombatHooks.java");
        assertTrue(Files.exists(patchPath), "Patch_CombatHooks.java not found at " + patchPath.toAbsolutePath());

        String src = Files.readString(patchPath, StandardCharsets.UTF_8);
        assertTrue(src.contains("methodName = \"updateCursorVisibility\""),
                "CursorVisibilityGuard must patch IsoPlayer.updateCursorVisibility");
        assertTrue(src.contains("class CursorVisibilityGuard"),
                "CursorVisibilityGuard inner class missing");
        assertTrue(src.contains("class SeparateBumpScope")
                        && src.contains("class SeparateCandidateTracker")
                        && src.contains("class SeparateFriendlyBumpGuard"),
                "Separate bump suppression patches must remain intact");
    }

    @Test
    void playerAvoidanceCorridorDetourAndStaticPassContractsHold() {
        // 正前方 1.0m 处站着玩家 -> 阻挡走廊；正后方或横向 > 0.62m -> 不阻挡
        assertTrue(PlayerAvoidanceMath.blocksCorridor(1.0F, 0.25F, 1.0F, 0.0F));
        assertFalse(PlayerAvoidanceMath.blocksCorridor(1.0F, 0.70F, 1.0F, 0.0F));
        assertFalse(PlayerAvoidanceMath.blocksCorridor(-0.50F, 0.0F, 1.0F, 0.0F));

        // 静止玩家绕行释放门限：NPC 必须沿着锁存基准方向越过玩家 >= 0.62m 才释放 staticPass
        assertFalse(PlayerAvoidanceMath.hasPassedStaticBlocker(-0.40F, 0.30F, 1.0F, 0.0F));
        assertTrue(PlayerAvoidanceMath.hasPassedStaticBlocker(-0.65F, 0.30F, 1.0F, 0.0F));

        // 绕行合成向量必须具有稳定的侧向避让偏移且保持单位长度
        float[] detourRight = PlayerAvoidanceMath.computePlayerDetour(0.9F, 0.0F, 1.0F, 0.0F, 1);
        assertEquals(1.0F, (float) Math.hypot(detourRight[0], detourRight[1]), 0.001F);
        assertTrue(detourRight[0] > 0.2F, "detour should keep forward progress");
        assertTrue(detourRight[1] > 0.3F, "detour should steer laterally around player");

        // 点到线段最短距离校验（净空 >= 0.48m）
        float distSqClear = PlayerAvoidanceMath.pointToSegmentDistSq(0.0F, 0.60F, -1.0F, 0.0F, 1.0F, 0.0F);
        assertEquals(0.36F, distSqClear, 0.001F);
    }
}
