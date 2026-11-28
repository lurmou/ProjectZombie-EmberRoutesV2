package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.NpcNullInputComponent;
import emberroutes.npc.SquadTacticalConfig;
import emberroutes.npc.brain.BrainContext;
import emberroutes.npc.brain.SettlementDangerField;
import java.util.ArrayList;
import java.util.List;
import zombie.CombatManager;
import zombie.SandboxOptions;
import zombie.ai.states.FakeDeadZombieState;
import zombie.ai.states.LungeNetworkState;
import zombie.ai.states.LungeState;
import zombie.ai.states.StaggerBackState;
import zombie.ai.states.ZombieFallDownState;
import zombie.ai.states.ZombieGetUpFromCrawlState;
import zombie.ai.states.ZombieGetUpState;
import zombie.ai.states.ZombieOnGroundState;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.iso.Vector3;
import zombie.iso.areas.isoregion.IsoRegions;
import zombie.iso.areas.isoregion.regions.IWorldRegion;
import zombie.iso.areas.isoregion.regions.IsoWorldRegion;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.network.fields.hit.HitInfo;
import zombie.pathfind.PolygonalMap2;
import zombie.scripting.objects.WeaponCategory;
import zombie.util.list.PZArrayList;

/**
 * B42 引擎战斗世界适配器（{@code IsoZombie} / {@code CombatManager} / {@code PolygonalMap2} / {@code IsoCamera}）。
 *
 * <p>集中隔离所有依赖 {@code zombie.*} 类的世界探针、原版命中框复核、骨骼测距、帧扫描与战斗清理收口。</p>
 */
public final class B42CombatRuntime {
    private static final float VISUAL_SENSE_RANGE = 18.0F;
    private static final float HEAR_RANGE_NORMAL = 4.5F;
    private static final float HEAR_RANGE_ATTACKING = 6.5F;

    private static final IsoZombie[] LIVE_ZOMBIES = new IsoZombie[EmberSquadDirector.MAX_THREATS];
    private static final float[] DF_X = new float[EmberSquadDirector.MAX_THREATS];
    private static final float[] DF_Y = new float[EmberSquadDirector.MAX_THREATS];
    private static final float[] DF_Z = new float[EmberSquadDirector.MAX_THREATS];

    private static final PZArrayList<HitInfo> SCRATCH_PRONE = new PZArrayList<>(HitInfo.class, 8);
    private static final PZArrayList<HitInfo> SCRATCH_STANDING = new PZArrayList<>(HitInfo.class, 8);
    private static final Vector3 SCRATCH_BONE = new Vector3();

    public static final EmberSquadDirector.PassabilityProbe WORLD_PROBE = (x1, y1, x2, y2, z) -> {
        try {
            if (PolygonalMap2.instance != null) {
                return !PolygonalMap2.instance.lineClearCollide(x1, y1, x2, y2, z, null, false, true);
            }
            IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
            if (cell != null) {
                IsoGridSquare sq = cell.getGridSquare((int) Math.floor(x2), (int) Math.floor(y2), z);
                return sq != null && sq.TreatAsSolidFloor() && !sq.isSolid() && !sq.isSolidTrans();
            }
        } catch (Throwable ignored) {}
        return true;
    };

    public static final RangedTacticsController.LosProbe RANGED_LOS_PROBE = (x1, y1, x2, y2, z) -> {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) {
            return true;
        }
        try {
            LosUtil.TestResults los = LosUtil.lineClear(
                    cell,
                    (int) Math.floor(x1),
                    (int) Math.floor(y1),
                    z,
                    (int) Math.floor(x2),
                    (int) Math.floor(y2),
                    z,
                    false);
            return los != LosUtil.TestResults.Blocked && los != LosUtil.TestResults.ClearThroughClosedDoor;
        } catch (Throwable ignored) {
            return true;
        }
    };

    public static final MeleeTargetSelector.MeleeBarrierProbe MELEE_BARRIER_PROBE = (x1, y1, x2, y2, z) -> {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) {
            return false;
        }
        try {
            int sx1 = (int) Math.floor(x1);
            int sy1 = (int) Math.floor(y1);
            int sx2 = (int) Math.floor(x2);
            int sy2 = (int) Math.floor(y2);
            if (sx1 == sx2 && sy1 == sy2) {
                return false;
            }
            IsoGridSquare sq1 = cell.getGridSquare(sx1, sy1, z);
            IsoGridSquare sq2 = cell.getGridSquare(sx2, sy2, z);
            if (sq1 == null || sq2 == null) {
                return false;
            }
            if (Math.abs(sx2 - sx1) + Math.abs(sy2 - sy1) == 1) {
                if (sq1.getWindowTo(sq2) != null || sq1.isHoppableTo(sq2)) {
                    return true;
                }
            }
            int midX = (int) Math.floor((x1 + x2) * 0.5F);
            int midY = (int) Math.floor((y1 + y2) * 0.5F);
            IsoGridSquare midSq = cell.getGridSquare(midX, midY, z);
            if (midSq != null && midSq != sq1 && (sq1.getWindowTo(midSq) != null || sq1.isHoppableTo(midSq))) {
                return true;
            }
            if (midSq != null && midSq != sq2 && (midSq.getWindowTo(sq2) != null || midSq.isHoppableTo(sq2))) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    };

    private B42CombatRuntime() {}

    public static void reset() {
        for (int i = 0; i < LIVE_ZOMBIES.length; i++) {
            LIVE_ZOMBIES[i] = null;
        }
        EmberSquadDirector.reset();
    }

    /** O(1) 读取导演威胁槽对应的实时 {@link IsoZombie} 引用（校验槽位、ID 与存活状态防陈旧串号）。 */
    public static IsoZombie getLiveZombie(EmberSquadDirector.ThreatEntry entry) {
        if (entry == null || !entry.active || entry.slotIndex < 0 || entry.slotIndex >= LIVE_ZOMBIES.length) {
            return null;
        }
        IsoZombie z = LIVE_ZOMBIES[entry.slotIndex];
        if (z == null || z.isDead() || !z.isAlive() || z.getID() != entry.zombieId) {
            return null;
        }
        return z;
    }

    public static boolean isThreatEntryLive(EmberSquadDirector.ThreatEntry entry) {
        return getLiveZombie(entry) != null;
    }

    public static void markThreatDead(EmberSquadDirector.ThreatEntry entry) {
        if (entry == null) {
            return;
        }
        if (entry.slotIndex >= 0 && entry.slotIndex < LIVE_ZOMBIES.length) {
            LIVE_ZOMBIES[entry.slotIndex] = null;
        }
        EmberSquadDirector.getInstance().deactivateThreat(entry.zombieId);
    }

    public static boolean isShootableTarget(IsoZombie z) {
        if (z == null || z.isDead() || !z.isAlive() || !z.isShootable()) {
            return false;
        }
        return classifyTarget(z) != 0;
    }

    /** 按 B42 {@link IsoZombie} 权威状态机与字段一次性分类为 {@link CombatTargetState} 位掩码。 */
    public static int classifyTarget(IsoZombie z) {
        if (z == null || z.isDead() || !z.isAlive() || z.isFakeDead()) {
            return 0;
        }
        if (z.isCurrentState(FakeDeadZombieState.instance()) || z.isReanimatedForGrappleOnly()) {
            return 0;
        }
        String hitReaction = z.getHitReaction();
        if (hitReaction != null && hitReaction.contains("Death")) {
            return 0;
        }

        int mask = CombatTargetState.FLAG_VALID;
        boolean transition = z.isGettingUp()
                || z.isCurrentState(ZombieFallDownState.instance())
                || z.isCurrentState(ZombieGetUpState.instance())
                || z.isCurrentState(ZombieGetUpFromCrawlState.instance());

        boolean crawler = z.isCrawling() || z.isBecomeCrawler();
        boolean groundOrProne = !transition && (crawler
                || z.isCurrentState(ZombieOnGroundState.instance())
                || z.isKnockedDown()
                || z.isProne()
                || z.isOnFloor());

        if (transition) {
            mask |= CombatTargetState.FLAG_POSTURE_TRANSITION;
        } else if (groundOrProne) {
            mask |= CombatTargetState.FLAG_PRONE_OR_CRAWL;
            if (crawler) {
                mask |= CombatTargetState.FLAG_CRAWLER;
            }
        } else {
            mask |= CombatTargetState.FLAG_STANDING;
        }

        if (z.speedType == 1) {
            mask |= CombatTargetState.FLAG_SPRINTER;
        }
        if (z.isStaggerBack()
                || z.isCurrentState(StaggerBackState.instance())
                || (hitReaction != null && !hitReaction.isEmpty())) {
            mask |= CombatTargetState.FLAG_STAGGERED;
        }
        if (z.isZombieAttacking()) {
            mask |= CombatTargetState.FLAG_ATTACKING;
        }
        if (z.isCurrentState(LungeState.instance()) || z.isCurrentState(LungeNetworkState.instance())) {
            mask |= CombatTargetState.FLAG_LUNGING;
        }
        return mask;
    }

    public static void assignAttackTargetSquare(IsoNPC npc, EmberSquadDirector.ThreatEntry target) {
        IsoZombie liveZ = getLiveZombie(target);
        if (npc != null && liveZ != null && liveZ.getCurrentSquare() != null) {
            npc.setAttackTargetSquare(liveZ.getCurrentSquare());
        }
    }

    /** 1:1 从原版 {@link HandWeapon} 与 {@link SandboxOptions} 提取近战武器射程、弧度与多目标配额（不设人工钳位）。 */
    public static MeleeWeaponMetrics extractWeaponMetrics(IsoNPC npc, HandWeapon weapon) {
        boolean multiHit = false;
        try {
            multiHit = SandboxOptions.instance != null && SandboxOptions.instance.multiHitZombies.getValue();
        } catch (Throwable ignored) {}

        if (weapon == null || weapon.isRanged()) {
            float bareMax = (npc != null && npc.bareHands != null)
                    ? npc.bareHands.getMaxRange()
                    : MeleeWeaponMetrics.VANILLA_BARE_HANDS_MAX_RANGE;
            return MeleeWeaponMetrics.unarmed(bareMax, multiHit);
        }

        float rawMax = npc != null ? weapon.getMaxRange(npc) : weapon.getMaxRange();
        float rangeMod = npc != null ? weapon.getRangeMod(npc) : 1.0F;
        float maxRange = (Float.isFinite(rawMax * rangeMod) && rawMax * rangeMod > 0.35F)
                ? (rawMax * rangeMod)
                : MeleeWeaponMetrics.VANILLA_BARE_HANDS_MAX_RANGE;
        float minRange = Math.max(0.0F, weapon.getMinRange());
        float reliable = CombatTargetState.reliableMeleeRange(maxRange);
        float groundHeadReach = Math.max(0.55F, weapon.weaponLength + 0.50F);
        float minAngleCos = weapon.getMinAngle();
        int maxHitCount = Math.max(1, weapon.getMaxHitCount());
        boolean isKnife = weapon.isOfWeaponCategory(WeaponCategory.SMALL_BLADE)
                || "Knife".equalsIgnoreCase(weapon.getSwingAnim())
                || "Stab".equalsIgnoreCase(weapon.getSwingAnim());

        return new MeleeWeaponMetrics(
                minRange,
                maxRange,
                reliable,
                groundHeadReach,
                minAngleCos,
                maxHitCount,
                isKnife,
                multiHit,
                false);
    }

    /** 使用原版 {@link CombatManager#calcValidTargets} 预检目标是否真实落入原版近战/踩踏合法命中窗口内。 */
    public static boolean hasVanillaMeleeHitTarget(
            IsoNPC npc, HandWeapon weapon, IsoZombie expectedTarget, boolean unarmed) {
        if (npc == null || npc.isDead() || expectedTarget == null || expectedTarget.isDead() || !expectedTarget.isAlive()) {
            return false;
        }
        CombatManager cm = CombatManager.getInstance();
        if (cm == null || IsoWorld.instance == null || IsoWorld.instance.currentCell == null) {
            return false;
        }
        HandWeapon effWeapon = (unarmed || weapon == null || weapon.isRanged()) ? npc.bareHands : weapon;
        if (effWeapon == null) {
            return false;
        }
        if (unarmed && expectedTarget.isProne()
                && getNearestBoneDistance(npc, expectedTarget) > MeleeWeaponMetrics.VANILLA_MAX_STOMP_DIST + 0.08F) {
            return false;
        }
        try {
            SCRATCH_PRONE.clear();
            SCRATCH_STANDING.clear();
            cm.calcValidTargets(npc, effWeapon, SCRATCH_PRONE, SCRATCH_STANDING);
            int expectedId = expectedTarget.getID();
            return containsTarget(SCRATCH_STANDING, expectedTarget, expectedId)
                    || containsTarget(SCRATCH_PRONE, expectedTarget, expectedId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsTarget(PZArrayList<HitInfo> list, IsoZombie expectedTarget, int expectedId) {
        for (int i = 0, n = list.size(); i < n; i++) {
            HitInfo hi = list.get(i);
            if (hi == null) continue;
            if (hi.getObject() == expectedTarget) {
                return true;
            }
            if (hi.getObject() instanceof IsoZombie hz && hz.getID() == expectedId) {
                return true;
            }
        }
        return false;
    }

    /** 对齐 {@code CombatManager.java:1648-1688}：取质心与 {@code Pelvis / Head / HeadNub} 骨骼的最小 2D 距离。 */
    public static float getNearestBoneDistance(IsoNPC npc, IsoZombie z) {
        if (npc == null || z == null) {
            return Float.MAX_VALUE;
        }
        float nx = npc.getX();
        float ny = npc.getY();
        float best = (float) Math.hypot(z.getX() - nx, z.getY() - ny);
        best = Math.min(best, sampleBoneDist(nx, ny, z, "Bip01_Pelvis"));
        best = Math.min(best, sampleBoneDist(nx, ny, z, "Bip01_Head"));
        best = Math.min(best, sampleBoneDist(nx, ny, z, "Bip01_HeadNub"));
        return best;
    }

    private static float sampleBoneDist(float nx, float ny, IsoZombie z, String boneName) {
        try {
            Vector3 pos = CombatManager.getBoneWorldPos(z, boneName, SCRATCH_BONE);
            if (pos != null && Float.isFinite(pos.x) && Float.isFinite(pos.y)) {
                return (float) Math.hypot(pos.x - nx, pos.y - ny);
            }
        } catch (Throwable ignored) {}
        return Float.MAX_VALUE;
    }

    /** 读取原版 {@link StaggerBackState} 剩余硬直时间（{@code stateEventDelayTimer} 以 30-FPS ticks 计，折算毫秒）。 */
    public static float readZombieStaggerRemainingMs(IsoZombie z) {
        if (z == null || (!z.isCurrentState(StaggerBackState.instance()) && !z.isStaggerBack())) {
            return -1.0F;
        }
        float ticks = z.getStateEventDelayTimer();
        if (!Float.isFinite(ticks) || ticks <= 0.0F) {
            return -1.0F;
        }
        return (ticks / 30.0F) * 1000.0F;
    }

    public static boolean isIndoorViaIsoRegions(float x, float y, int z) {
        try {
            int ix = (int) Math.floor(x);
            int iy = (int) Math.floor(y);
            IWorldRegion reg = IsoRegions.getIsoWorldRegion(ix, iy, z);
            if (reg instanceof IsoWorldRegion wr && wr.isEnclosed()) {
                return true;
            }
            IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
            IsoGridSquare sq = cell != null ? cell.getGridSquare(ix, iy, z) : null;
            return sq != null && sq.getRoom() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int countWalkableNeighbors4(float x, float y, int z) {
        int clear = 0;
        if (WORLD_PROBE.isLineClear(x, y, x + 1.0F, y, z)) clear++;
        if (WORLD_PROBE.isLineClear(x, y, x - 1.0F, y, z)) clear++;
        if (WORLD_PROBE.isLineClear(x, y, x, y + 1.0F, z)) clear++;
        if (WORLD_PROBE.isLineClear(x, y, x, y - 1.0F, z)) clear++;
        return clear;
    }

    /** 单一权威战斗清理收口：清空寻路/姿势接力/动画动作队列与变量/虚拟准星/输入意图/导演锁与诊断条目。 */
    public static void cleanupNpcCombat(IsoNPC npc, SquadCombatNodes.CombatSessionState session) {
        if (session != null) {
            session.reset();
        }
        if (npc == null) {
            return;
        }
        BrainContext brainCtx = npc.getBrainContext();
        if (brainCtx != null) {
            brainCtx.combatActivityState = "";
        }
        npc.getLocomotion().clearCombatMove(npc);
        npc.getLocomotion().stop(npc);
        npc.getPosture().clearPendingContinuation();
        if (!npc.getCharacterActions().isEmpty()) {
            npc.StopAllActionQueue();
        }
        npc.clearVariable("isLoading");
        npc.clearVariable("isRacking");
        npc.clearVariable("isUnloading");
        npc.clearVariable("WeaponReloadType");
        npc.clearVariable("RackAiming");
        npc.clearVariable("ReloadSpeed");
        npc.clearVariable("AttachAnim");
        npc.clearVariable("AttachItemSpeed");
        npc.clearVariable("UpdateLoadBulletsTime");

        npc.clearVirtualAim();
        npc.setIsAiming(false);
        npc.setForceLocalForCombat(false);
        npc.setAimAtFloor(false);
        npc.targetOnGround = null;
        npc.setShoveStompAnim(false);
        npc.clearHandToHandAttack();

        NpcNullInputComponent in = npc.getNullInputComponent();
        if (in != null) {
            in.clearCombatIntents();
        }
        npc.resetLastAttackerZombieId();

        String npcId = npc.getNpcId();
        if (npcId != null && !npcId.isEmpty()) {
            EmberSquadDirector.getInstance().releaseAllForNpc(npcId);
            CombatDiagLog.removeNpc(npcId);
        }
    }

    /** 将 {@link NpcVirtualAim} 世界坐标投影至与 {@code BallisticsController.update()} 一致的本地视口像素 X。 */
    public static int projectReticleScreenX(NpcVirtualAim aim) {
        return projectReticleScreenAxis(aim, false);
    }

    /** 将 {@link NpcVirtualAim} 世界坐标投影至本地视口像素 Y。 */
    public static int projectReticleScreenY(NpcVirtualAim aim) {
        return projectReticleScreenAxis(aim, true);
    }

    private static int projectReticleScreenAxis(NpcVirtualAim aim, boolean yAxis) {
        if (aim == null || !aim.isActive()) {
            return 0;
        }
        float wx = aim.getWorldX();
        float wy = aim.getWorldY();
        float wz = aim.getWorldZ();
        if (!Float.isFinite(wx) || !Float.isFinite(wy) || !Float.isFinite(wz)) {
            return 0;
        }
        int idx = safePlayerCameraIndex();
        float off = safeCameraOff(idx, yAxis);
        float projected = yAxis ? IsoUtils.YToScreen(wx, wy, wz, 0) : IsoUtils.XToScreen(wx, wy, wz, 0);
        return Math.round(projected - off);
    }

    private static int safePlayerCameraIndex() {
        try {
            return Math.max(0, Math.min(3, IsoPlayer.getPlayerIndex()));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static float safeCameraOff(int idx, boolean yAxis) {
        try {
            if (IsoCamera.cameras[idx] == null) {
                return 0.0F;
            }
            return yAxis ? IsoCamera.getOffY(idx) : IsoCamera.getOffX(idx);
        } catch (Throwable ignored) {
            return 0.0F;
        }
    }

    /** 从世界实体列表采集同层友军（玩家 + 友方 NPC）并检查 {@code ±12°} 安全锥。 */
    public static boolean isLineBlockedByAllyInWorld(
            IsoGameCharacter shooter,
            float aimDirX,
            float aimDirY,
            float targetDist,
            IsoPlayer leader,
            List<? extends IsoGameCharacter> allies) {
        if (shooter == null) {
            return false;
        }
        float sx = shooter.getX();
        float sy = shooter.getY();
        int sz = (int) Math.floor(shooter.getZ());
        float cosHalfCone = (float) Math.cos(Math.toRadians(AiAimingReadiness.FRIENDLY_SAFETY_CONE_DEG));
        float maxCheckDist = targetDist + 0.65F;

        if (isOneAllyInsideCone(shooter, sx, sy, sz, aimDirX, aimDirY, cosHalfCone, maxCheckDist, leader)) {
            return true;
        }
        if (allies != null) {
            for (int i = 0, n = allies.size(); i < n; i++) {
                IsoGameCharacter ally = allies.get(i);
                if (isOneAllyInsideCone(shooter, sx, sy, sz, aimDirX, aimDirY, cosHalfCone, maxCheckDist, ally)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isOneAllyInsideCone(
            IsoGameCharacter shooter,
            float sx,
            float sy,
            int sz,
            float aimDirX,
            float aimDirY,
            float cosHalfCone,
            float maxCheckDist,
            IsoGameCharacter ally) {
        if (ally == null || ally == shooter || ally.isDead() || (int) Math.floor(ally.getZ()) != sz) {
            return false;
        }
        return AiAimingReadiness.isPointInsideCone(
                sx, sy, aimDirX, aimDirY, cosHalfCone, maxCheckDist, ally.getX(), ally.getY());
    }

    /** 检查射线在窄走廊场景下是否因近距 {@code < 2.2} 格被近战友军前沿接战接管（射手应停火让近战顶上）。 */
    public static boolean shouldShooterHoldInNarrowCorridor(
            float shooterX,
            float shooterY,
            float aimDirX,
            float aimDirY,
            float targetDist,
            List<? extends IsoPlayer> allies) {
        if (targetDist >= EmberSquadDirector.NARROW_CORRIDOR_MELEE_PRESS_DIST || allies == null) {
            return false;
        }
        for (int i = 0, n = allies.size(); i < n; i++) {
            IsoPlayer ally = allies.get(i);
            if (ally == null || ally.isDead()) continue;
            if (EmberSquadDirector.isAllyInsideCorridorHold(
                    shooterX, shooterY, aimDirX, aimDirY, targetDist, ally.getX(), ally.getY())) {
                return true;
            }
        }
        return false;
    }

    /** 扫描 8 方位 {@code 3.0m} 门窗/开口，写入最多 4 个相邻威胁来向角至 {@link CqbController}。 */
    public static void scanDoorWindowSectors(
            CqbController controller, float selfX, float selfY, int selfZ, float fallbackYawRad) {
        if (controller == null) {
            return;
        }
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) {
            controller.seedFallbackCrossSectors(fallbackYawRad);
            return;
        }
        int cx = (int) Math.floor(selfX);
        int cy = (int) Math.floor(selfY);
        int rInt = 3;
        float[] bestYaw = new float[CqbController.MAX_PHASES];
        float[] bestDistSq = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        int found = 0;

        for (int dx = -rInt; dx <= rInt; dx++) {
            for (int dy = -rInt; dy <= rInt; dy++) {
                if (dx == 0 && dy == 0) continue;
                float dSq = dx * dx + dy * dy;
                if (dSq > CqbController.OPENING_SCAN_RADIUS * CqbController.OPENING_SCAN_RADIUS) continue;
                IsoGridSquare sq = cell.getGridSquare(cx + dx, cy + dy, selfZ);
                if (sq == null || !hasDoorOrWindowOpening(sq)) continue;

                float yaw = (float) Math.atan2(dy, dx);
                int dup = CqbController.findSimilarSector(bestYaw, found, yaw);
                if (dup >= 0) {
                    if (dSq < bestDistSq[dup]) {
                        bestDistSq[dup] = dSq;
                        bestYaw[dup] = yaw;
                    }
                    continue;
                }
                if (found < CqbController.MAX_PHASES) {
                    bestYaw[found] = yaw;
                    bestDistSq[found] = dSq;
                    found++;
                } else {
                    int worst = 0;
                    for (int k = 1; k < CqbController.MAX_PHASES; k++) {
                        if (bestDistSq[k] > bestDistSq[worst]) worst = k;
                    }
                    if (dSq < bestDistSq[worst]) {
                        bestYaw[worst] = yaw;
                        bestDistSq[worst] = dSq;
                    }
                }
            }
        }

        if (found == 0) {
            controller.seedFallbackCrossSectors(fallbackYawRad);
            return;
        }
        controller.setSectorYaws(bestYaw, found);
    }

    private static boolean hasDoorOrWindowOpening(IsoGridSquare sq) {
        List<IsoObject> objs = sq.getObjects();
        if (objs == null) return false;
        for (int i = 0, n = objs.size(); i < n; i++) {
            IsoObject obj = objs.get(i);
            if (obj instanceof IsoDoor || obj instanceof IsoWindow) {
                return true;
            }
            if (obj instanceof IsoThumpable th && (th.isDoor() || th.isWindow())) {
                return true;
            }
        }
        return false;
    }

    public static void tickDirector(List<IsoNPC> activeNpcs, IsoPlayer leader, long nowMs) {
        tickDirector(activeNpcs, leader, nowMs / 1000.0, nowMs);
    }

    /** 运行期每帧入口（内部以 80ms 节流单遍扫图、聚类并按 {@code simTimeSec} 投喂危险场）。 */
    public static void tickDirector(List<IsoNPC> activeNpcs, IsoPlayer leader, double simTimeSec, long nowMs) {
        EmberSquadDirector dir = EmberSquadDirector.getInstance();
        synchronized (dir) {
            dir.purgeExpiredCorridors(nowMs);
            registerLeaderCorridor(dir, leader, nowMs);
            updateActiveShooterCount(dir, activeNpcs);

            float scanDt = dir.beginScanIfDue(nowMs);
            if (scanDt < 0.0F) {
                return;
            }

            collectAnchors(dir, activeNpcs, leader);
            scanCellZombies(dir, activeNpcs, leader, nowMs, scanDt);
            dir.rebuildClusters();
            feedDangerField(dir, simTimeSec);
        }
    }

    private static void registerLeaderCorridor(EmberSquadDirector dir, IsoPlayer leader, long nowMs) {
        if (leader != null && !leader.isDead() && leader.isAiming()
                && leader.getPrimaryHandItem() instanceof HandWeapon hw && hw.isAimedFirearm()) {
            float fx = leader.getForwardDirectionX();
            float fy = leader.getForwardDirectionY();
            float fl = (float) Math.hypot(fx, fy);
            if (fl > 0.05F) {
                fx /= fl;
                fy /= fl;
                float range = Math.max(4.0F, Math.min(16.0F, hw.getMaxRange(leader)));
                dir.registerCorridor(
                        EmberSquadDirector.PLAYER_TARGET_ID,
                        leader.getX(),
                        leader.getY(),
                        leader.getX() + fx * range,
                        leader.getY() + fy * range,
                        (int) Math.floor(leader.getZ()),
                        nowMs);
            }
        }
    }

    private static void updateActiveShooterCount(EmberSquadDirector dir, List<IsoNPC> activeNpcs) {
        int shooters = 0;
        boolean anyCqb = SquadTacticalConfig.isDefaultCqb();
        if (activeNpcs != null) {
            for (int i = 0, n = activeNpcs.size(); i < n; i++) {
                IsoNPC npc = activeNpcs.get(i);
                if (npc == null || npc.isDead()) {
                    continue;
                }
                if (SquadTacticalConfig.isEffectiveCqb(npc.getNpcId())) {
                    anyCqb = true;
                }
                if (npc.getPrimaryHandItem() instanceof HandWeapon hw && hw.isAimedFirearm()) {
                    shooters++;
                }
            }
        }
        dir.getReloadPool().setActiveShooterCount(shooters);
        dir.getReloadPool().setCqbSingleReloadOnly(anyCqb);
    }

    private static void collectAnchors(EmberSquadDirector dir, List<IsoNPC> activeNpcs, IsoPlayer leader) {
        if (leader != null && !leader.isDead()) {
            dir.addScanAnchor(leader.getX(), leader.getY(), leader.getZ());
        }
        if (activeNpcs != null) {
            for (int i = 0, n = activeNpcs.size(); i < n; i++) {
                IsoNPC npc = activeNpcs.get(i);
                if (npc != null && !npc.isDead()) {
                    dir.addScanAnchor(npc.getX(), npc.getY(), npc.getZ());
                }
            }
        }
    }

    private static void scanCellZombies(
            EmberSquadDirector dir,
            List<IsoNPC> activeNpcs,
            IsoPlayer leader,
            long nowMs,
            float scanDt) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null || dir.getAnchorCount() == 0) {
            clearAllThreats(dir);
            return;
        }
        ArrayList<IsoZombie> zombies = cell.getZombieList();
        if (zombies == null || zombies.isEmpty()) {
            clearAllThreats(dir);
            return;
        }

        for (int i = 0, n = zombies.size(); i < n; i++) {
            IsoZombie z = zombies.get(i);
            if (z == null) continue;
            float zx = z.getX();
            float zy = z.getY();
            float zz = z.getZ();
            if (!dir.isNearAnyAnchor(zx, zy, zz)) {
                continue;
            }
            int mask = classifyTarget(z);
            if (mask == 0) {
                continue;
            }

            int attackedAllyPriority = 0;
            String liveTargetEntityId = null;
            if (z.getTarget() == leader && leader != null && !leader.isDead()) {
                attackedAllyPriority = 2;
                liveTargetEntityId = EmberSquadDirector.PLAYER_TARGET_ID;
            } else if (z.getTarget() instanceof IsoNPC targetNpc && !targetNpc.isDead()) {
                attackedAllyPriority = 1;
                liveTargetEntityId = targetNpc.getNpcId();
            }

            boolean seenDirect = canTeamSeeZombie(cell, leader, activeNpcs, z, zx, zy, zz);
            boolean attackingNow = CombatTargetState.isAttacking(mask) || CombatTargetState.isLunging(mask);
            float hearMaxDist = attackingNow ? HEAR_RANGE_ATTACKING : HEAR_RANGE_NORMAL;
            boolean heardNow = !seenDirect && isWithinHearRange(leader, activeNpcs, zx, zy, zz, hearMaxDist);
            boolean targetingAlly = attackedAllyPriority > 0;
            boolean perceivedNow = seenDirect || heardNow || targetingAlly;

            if (seenDirect) {
                mask |= CombatTargetState.FLAG_SEEN_DIRECT;
            } else if (heardNow) {
                mask |= CombatTargetState.FLAG_HEARD;
            }
            if (targetingAlly) {
                mask |= CombatTargetState.FLAG_ATTACKING_ALLY;
            }

            EmberSquadDirector.ThreatEntry entry = dir.recordScannedThreat(
                    z.getID(), zx, zy, zz, mask, nowMs, scanDt,
                    perceivedNow, heardNow && !seenDirect && !targetingAlly,
                    attackedAllyPriority, liveTargetEntityId);
            if (entry != null) {
                LIVE_ZOMBIES[entry.slotIndex] = z;
                if (CombatTargetState.isProneOrCrawl(mask)) {
                    try {
                        Vector3 hp = CombatManager.getBoneWorldPos(z, "Bip01_Head", SCRATCH_BONE);
                        if (hp != null && Float.isFinite(hp.x) && Float.isFinite(hp.y)) {
                            entry.headX = hp.x;
                            entry.headY = hp.y;
                            entry.hasHeadPos = true;
                        } else {
                            entry.hasHeadPos = false;
                        }
                    } catch (Throwable ignored) {
                        entry.hasHeadPos = false;
                    }
                } else {
                    entry.hasHeadPos = false;
                }
            }
        }

        dir.pruneUnseenThreats(nowMs);
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            if (!dir.getThreatAt(i).active) {
                LIVE_ZOMBIES[i] = null;
            }
        }
    }

    private static boolean canTeamSeeZombie(
            IsoCell cell,
            IsoPlayer leader,
            List<IsoNPC> activeNpcs,
            IsoZombie z,
            float zx,
            float zy,
            float zz) {
        if (canCharacterSeeZombie(cell, leader, z, zx, zy, zz)) {
            return true;
        }
        if (activeNpcs != null) {
            for (int i = 0, n = activeNpcs.size(); i < n; i++) {
                if (canCharacterSeeZombie(cell, activeNpcs.get(i), z, zx, zy, zz)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean canCharacterSeeZombie(
            IsoCell cell, IsoGameCharacter viewer, IsoZombie z, float zx, float zy, float zz) {
        if (viewer == null || viewer.isDead() || Math.abs(viewer.getZ() - zz) > 1.5F) {
            return false;
        }
        float dx = zx - viewer.getX();
        float dy = zy - viewer.getY();
        if (dx * dx + dy * dy > VISUAL_SENSE_RANGE * VISUAL_SENSE_RANGE) {
            return false;
        }
        try {
            int vz = (int) Math.floor(viewer.getZ());
            int zLvl = (int) Math.floor(zz);
            LosUtil.TestResults los = LosUtil.lineClear(
                    cell,
                    (int) Math.floor(viewer.getX()),
                    (int) Math.floor(viewer.getY()),
                    vz,
                    (int) Math.floor(zx),
                    (int) Math.floor(zy),
                    zLvl,
                    false);
            return los != LosUtil.TestResults.Blocked && los != LosUtil.TestResults.ClearThroughClosedDoor;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isWithinHearRange(
            IsoPlayer leader, List<IsoNPC> activeNpcs, float zx, float zy, float zz, float maxDist) {
        float maxSq = maxDist * maxDist;
        if (leader != null && !leader.isDead() && Math.abs(leader.getZ() - zz) <= 1.5F) {
            float dx = zx - leader.getX();
            float dy = zy - leader.getY();
            if (dx * dx + dy * dy <= maxSq) {
                return true;
            }
        }
        if (activeNpcs != null) {
            for (int i = 0, n = activeNpcs.size(); i < n; i++) {
                IsoNPC npc = activeNpcs.get(i);
                if (npc == null || npc.isDead() || Math.abs(npc.getZ() - zz) > 1.5F) continue;
                float dx = zx - npc.getX();
                float dy = zy - npc.getY();
                if (dx * dx + dy * dy <= maxSq) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void clearAllThreats(EmberSquadDirector dir) {
        dir.clearAllThreats();
        for (int i = 0; i < LIVE_ZOMBIES.length; i++) {
            LIVE_ZOMBIES[i] = null;
        }
    }

    private static void feedDangerField(EmberSquadDirector dir, double simTimeSec) {
        int count = 0;
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = dir.getThreatAt(i);
            if (t.active) {
                DF_X[count] = t.x;
                DF_Y[count] = t.y;
                DF_Z[count] = t.z;
                count++;
            }
        }
        EmberSquadDirector.HordeCluster firstCluster = null;
        for (int i = 0; i < EmberSquadDirector.MAX_CLUSTERS; i++) {
            EmberSquadDirector.HordeCluster c = dir.getClusterAt(i);
            if (c.active) {
                firstCluster = c;
                break;
            }
        }
        if (firstCluster != null) {
            SettlementDangerField.ingestDirectorThreats(
                    simTimeSec,
                    DF_X,
                    DF_Y,
                    DF_Z,
                    count,
                    true,
                    firstCluster.centroidX,
                    firstCluster.centroidY,
                    firstCluster.zLevel,
                    firstCluster.count);
        } else {
            SettlementDangerField.ingestDirectorThreats(
                    simTimeSec, DF_X, DF_Y, DF_Z, count, false, 0.0F, 0.0F, 0.0F, 0);
        }
    }
}
