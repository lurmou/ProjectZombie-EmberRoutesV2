package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.brain.SettlementDangerField;
import java.util.ArrayList;
import java.util.List;
import zombie.ai.states.FakeDeadZombieState;
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
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.pathfind.PolygonalMap2;

/**
 * B42 引擎战斗世界适配器（{@code IsoZombie} / {@code PolygonalMap2} / {@code IsoCamera} / {@code SettlementDangerField}）。
 *
 * <p>集中隔离所有依赖 {@code zombie.*} 类的世界探针与帧扫描桥接逻辑，使 7 大微操内核及 {@link EmberSquadDirector}
 * 保持零 {@code zombie.*} 依赖，可在无 {@code projectzomboid.jar} 的 Gradle JUnit 环境直接脱机单测。</p>
 */
public final class B42CombatRuntime {
    private static final IsoZombie[] LIVE_ZOMBIES = new IsoZombie[EmberSquadDirector.MAX_THREATS];
    private static final float[] DF_X = new float[EmberSquadDirector.MAX_THREATS];
    private static final float[] DF_Y = new float[EmberSquadDirector.MAX_THREATS];
    private static final float[] DF_Z = new float[EmberSquadDirector.MAX_THREATS];

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
        return mask;
    }

    /**
     * 将 {@link NpcVirtualAim} 世界坐标投影至与 {@code BallisticsController.update()} 一致的本地视口像素 X。
     * 必须使用当前主摄 {@code IsoPlayer.getPlayerIndex()} 的 {@code IsoCamera} 偏移量，才能与 {@code IsoUtils.XToIso(x, y, 0.0F)} 精确抵消。
     */
    public static int projectReticleScreenX(NpcVirtualAim aim) {
        return projectReticleScreenAxis(aim, false);
    }

    /** 将 {@link NpcVirtualAim} 世界坐标投影至本地视口像素 Y（对齐 {@code IsoUtils.YToIso(x, y, 0.0F)}）。 */
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

    /** 运行期每帧入口（内部以 80ms 节流单遍扫图、聚类并投喂危险场），先于全部 NPC 大脑执行。 */
    public static void tickDirector(List<IsoNPC> activeNpcs, IsoPlayer leader, long nowMs) {
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
            scanCellZombies(dir, nowMs, scanDt);
            dir.rebuildClusters();
            feedDangerField(dir, nowMs);
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
                        "__PLAYER__",
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
        if (activeNpcs != null) {
            for (int i = 0, n = activeNpcs.size(); i < n; i++) {
                IsoNPC npc = activeNpcs.get(i);
                if (npc != null && !npc.isDead()
                        && npc.getPrimaryHandItem() instanceof HandWeapon hw
                        && hw.isAimedFirearm()) {
                    shooters++;
                }
            }
        }
        dir.getReloadPool().setActiveShooterCount(shooters);
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

    private static void scanCellZombies(EmberSquadDirector dir, long nowMs, float scanDt) {
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
            EmberSquadDirector.ThreatEntry entry =
                    dir.recordScannedThreat(z.getID(), zx, zy, zz, mask, nowMs, scanDt);
            if (entry != null) {
                LIVE_ZOMBIES[entry.slotIndex] = z;
            }
        }

        dir.pruneUnseenThreats(nowMs);
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            if (!dir.getThreatAt(i).active) {
                LIVE_ZOMBIES[i] = null;
            }
        }
    }

    private static void clearAllThreats(EmberSquadDirector dir) {
        dir.clearAllThreats();
        for (int i = 0; i < LIVE_ZOMBIES.length; i++) {
            LIVE_ZOMBIES[i] = null;
        }
    }

    private static void feedDangerField(EmberSquadDirector dir, long nowMs) {
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
                    nowMs,
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
                    nowMs, DF_X, DF_Y, DF_Z, count, false, 0.0F, 0.0F, 0.0F, 0);
        }
    }
}
