package emberroutes.npc.combat;

/**
 * 统一零抽搐战术走位求解器（取代旧 {@code SpacingController} / {@code ContextSteering16} / {@code CombatEscapeField} 三套并行场）：
 * <ol>
 *   <li><b>极坐标径向状态机</b>（{@code ADVANCE / HOLD / RETREAT} + {@code 0.35s} 最小驻留 + {@code 0.18s} 反向停顿窗），
 *       彻底消灭世界系 EMA 过零单位化导致的 90° 纯侧步闪跳与 20Hz 前后翻号；</li>
 *   <li><b>挥击期锁步</b>：{@code performingSwing == true} 时冻结径向切换与方向跳变，杜绝 {@code SlowingMovement} 叠切；</li>
 *   <li><b>16 向纯角速度避障</b>：叠加墙壁探测、友军分离、{@code FiringCorridor} 净空推力与倒地安全侧半平面，仅在圆周上做角度插值；</li>
 *   <li><b>单一安全侧倒地策略</b>：存在近身站立威胁时不越过脚边倒地尸，清完站立敌后再处决倒地尸；</li>
 *   <li><b>远程迟滞退距</b>：进退 {@code 2.55m / 3.45m} 迟滞环共用同一 16 向安全避障器。</li>
 * </ol>
 */
public final class CombatLocomotionSolver {
    public static final int NUM_DIRS = 16;
    private static final float[] DIR_COS = new float[NUM_DIRS];
    private static final float[] DIR_SIN = new float[NUM_DIRS];

    public static final double RADIAL_DWELL_SIM = 0.35;
    public static final double REVERSE_SETTLE_SIM = 0.18;
    public static final double SLOT_LATCH_SIM = 0.25;
    public static final float MAX_ANGLE_SLEW_RAD_PER_SEC = 6.0F;

    public static final float RANGED_RETREAT_ENTER_DIST = 2.55F;
    public static final float RANGED_RETREAT_EXIT_DIST = 3.45F;

    public static final double STANCE_EXIT_DWELL_SIM = 0.60;
    public static final float STANCE_HYSTERESIS_TILES = 0.45F;

    private static final float[] WALL_PROBE_RADS = {
        0.0F,
        (float) Math.toRadians(25.0), (float) Math.toRadians(-25.0),
        (float) Math.toRadians(50.0), (float) Math.toRadians(-50.0),
        (float) Math.toRadians(75.0), (float) Math.toRadians(-75.0),
        (float) Math.toRadians(110.0), (float) Math.toRadians(-110.0)
    };

    static {
        for (int i = 0; i < NUM_DIRS; i++) {
            double ang = i * (Math.PI * 2.0 / NUM_DIRS);
            DIR_COS[i] = (float) Math.cos(ang);
            DIR_SIN[i] = (float) Math.sin(ang);
        }
    }

    public enum RadialMode {
        ADVANCE,
        HOLD,
        RETREAT
    }

    /** 单兵战斗走位持久状态（纯内存瞬态，由 CombatSessionState 持有并在退出战斗时重置）。 */
    public static final class CombatMoveState {
        public RadialMode radialMode = RadialMode.HOLD;
        public double radialEnteredSim = -10.0;
        public double reverseSettleUntilSim = -1.0;

        public boolean hasMoveAngle;
        public float smoothedMoveAngleRad;
        public int latchedDirIdx = -1;
        public double dirLatchUntilSim = -1.0;

        public boolean aimStanceActive;
        public double aimStanceChangedSim = -10.0;

        public boolean rangedRetreatActive;
        public final EmberSquadDirector.CorridorAvoidanceResult corridorScratch =
                new EmberSquadDirector.CorridorAvoidanceResult();

        // 连续近战间距与 0.25s 目标速度前瞻低通状态（ProjectRemnants 同款零抽搐核）
        public int spacingTargetId = -1;
        public float prevTargetX = Float.NaN;
        public float prevTargetY = Float.NaN;
        public float estVelX;
        public float estVelY;
        public double lastTargetSampleSim = -1.0;
        public float outVelX;
        public float outVelY;
        public double lastOutSampleSim = -1.0;
        public final float[] wallScratch = new float[2];

        public void reset() {
            radialMode = RadialMode.HOLD;
            radialEnteredSim = -10.0;
            reverseSettleUntilSim = -1.0;
            hasMoveAngle = false;
            smoothedMoveAngleRad = 0.0F;
            latchedDirIdx = -1;
            dirLatchUntilSim = -1.0;
            aimStanceActive = false;
            aimStanceChangedSim = -10.0;
            rangedRetreatActive = false;
            corridorScratch.reset();
            spacingTargetId = -1;
            prevTargetX = Float.NaN;
            prevTargetY = Float.NaN;
            estVelX = 0.0F;
            estVelY = 0.0F;
            lastTargetSampleSim = -1.0;
            outVelX = 0.0F;
            outVelY = 0.0F;
            lastOutSampleSim = -1.0;
        }

        private void resetSpacingFilter(int newTargetId) {
            spacingTargetId = newTargetId;
            prevTargetX = Float.NaN;
            prevTargetY = Float.NaN;
            estVelX = 0.0F;
            estVelY = 0.0F;
            lastTargetSampleSim = -1.0;
            outVelX = 0.0F;
            outVelY = 0.0F;
            lastOutSampleSim = -1.0;
        }
    }

    /** 输出给身体控制器的纯净运动指令。 */
    public static final class MoveCommand {
        public boolean wantMove;
        public boolean runGear;
        public boolean aimStrafe;
        public float dirX;
        public float dirY;
        public float walkSpeedCap = -1.0F;
        public RadialMode radialMode = RadialMode.HOLD;

        public void reset() {
            wantMove = false;
            runGear = false;
            aimStrafe = false;
            dirX = 0.0F;
            dirY = 0.0F;
            walkSpeedCap = -1.0F;
            radialMode = RadialMode.HOLD;
        }
    }

    /** 单一倒地安全侧守卫（有近距站立敌时严禁跨过脚边倒地尸）。 */
    public static final class SafeSideGuard {
        public boolean standingOverrideProne;
        public boolean hasForbiddenHalfSpace;
        public float originX;
        public float originY;
        public float forbiddenNormalX;
        public float forbiddenNormalY;

        public void reset() {
            standingOverrideProne = false;
            hasForbiddenHalfSpace = false;
            originX = 0.0F;
            originY = 0.0F;
            forbiddenNormalX = 0.0F;
            forbiddenNormalY = 0.0F;
        }

        public boolean isPointInForbiddenPocket(float px, float py, float margin) {
            if (!hasForbiddenHalfSpace) {
                return false;
            }
            float dot = (px - originX) * forbiddenNormalX + (py - originY) * forbiddenNormalY;
            return dot > margin;
        }
    }

    private CombatLocomotionSolver() {}

    /** 评估脚边倒地/转换期丧尸与近身站立威胁的安全侧约束（零堆分配）。 */
    public static void evaluateProneSafeSide(
            EmberSquadDirector director,
            float selfX,
            float selfY,
            int selfZ,
            SafeSideGuard out) {
        out.reset();
        if (director == null) {
            return;
        }
        float nearestStandingDist = Float.MAX_VALUE;
        boolean nearestStandingSprinter = false;
        float nearestProneX = 0.0F;
        float nearestProneY = 0.0F;
        float nearestProneDist = Float.MAX_VALUE;

        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || Math.abs(t.z - selfZ) > 0.6F) {
                continue;
            }
            float d = (float) Math.hypot(t.x - selfX, t.y - selfY);
            if (CombatTargetState.isStanding(t.stateMask)) {
                if (d < nearestStandingDist) {
                    nearestStandingDist = d;
                    nearestStandingSprinter = CombatTargetState.isSprinter(t.stateMask);
                }
            } else if (CombatTargetState.isProneOrCrawl(t.stateMask)
                    || CombatTargetState.isPostureTransition(t.stateMask)) {
                if (d < nearestProneDist) {
                    nearestProneDist = d;
                    nearestProneX = t.x;
                    nearestProneY = t.y;
                }
            }
        }

        float standThreatThreshold = nearestStandingSprinter ? 3.80F : 2.25F;
        if (nearestStandingDist <= standThreatThreshold) {
            out.standingOverrideProne = true;
            if (nearestProneDist <= 2.10F) {
                float dx = nearestProneX - selfX;
                float dy = nearestProneY - selfY;
                float len = (float) Math.hypot(dx, dy);
                if (len > 1.0E-4F) {
                    out.hasForbiddenHalfSpace = true;
                    out.originX = selfX;
                    out.originY = selfY;
                    out.forbiddenNormalX = dx / len;
                    out.forbiddenNormalY = dy / len;
                }
            }
        }
    }

    /** 评估某坐标点所承受的站立/爬行僵尸局域危险度（0-GC，供撤退与侧移共用）。 */
    public static float evaluatePointThreatDanger(float x, float y, int z, EmberSquadDirector director) {
        return evaluatePointThreatDanger(x, y, z, director, -1);
    }

    /** 评估某坐标点承受的局域危险度，支持在 {@code ADVANCE} 逼近主目标时排除当前主目标 ID。 */
    public static float evaluatePointThreatDanger(
            float x, float y, int z, EmberSquadDirector director, int excludeZombieId) {
        if (director == null) {
            return 0.0F;
        }
        float danger = 0.0F;
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || t.zombieId == excludeZombieId || Math.abs(t.z - z) > 0.6F) {
                continue;
            }
            float d = (float) Math.hypot(x - t.x, y - t.y);
            if (CombatTargetState.isStanding(t.stateMask)) {
                if (d < 2.20F) {
                    danger += (2.20F - d) * (CombatTargetState.isSprinter(t.stateMask) ? 2.4F : 1.7F);
                }
            } else if (CombatTargetState.isProneOrCrawl(t.stateMask) && d < 1.05F) {
                danger += (1.05F - d) * 1.1F;
            }
        }
        return danger;
    }

    /** 选取远离威胁质心且畅通低危的安全撤退目标点（供 {@link HordeRetreatPolicy} 一次性锁定）。 */
    public static void pickSafeRetreatGoal(
            float selfX,
            float selfY,
            int selfZ,
            float awayX,
            float awayY,
            float desiredDist,
            EmberSquadDirector director,
            EmberSquadDirector.PassabilityProbe probe,
            float[] outGoal) {
        float ax = awayX;
        float ay = awayY;
        float aLen = (float) Math.hypot(ax, ay);
        if (aLen <= 1.0E-4F) {
            ax = 1.0F;
            ay = 0.0F;
        } else {
            ax /= aLen;
            ay /= aLen;
        }
        double baseAng = Math.atan2(ay, ax);
        float[] scales = {Math.max(1.5F, desiredDist), 2.0F, 1.2F};
        float bestScore = -Float.MAX_VALUE;
        float bestX = selfX + ax * 1.2F;
        float bestY = selfY + ay * 1.2F;
        boolean found = false;

        for (int si = 0; si < scales.length; si++) {
            float dist = scales[si];
            for (int d = 0; d < NUM_DIRS; d++) {
                float gx = selfX + DIR_COS[d] * dist;
                float gy = selfY + DIR_SIN[d] * dist;
                if (probe != null && !probe.isLineClear(selfX, selfY, gx, gy, selfZ)) {
                    continue;
                }
                double ang = d * (Math.PI * 2.0 / NUM_DIRS);
                float align = (float) Math.cos(ang - baseAng);
                float danger = evaluatePointThreatDanger(gx, gy, selfZ, director);
                float score = align * 1.4F + dist * 0.25F - danger * 1.5F;
                if (!found || score > bestScore) {
                    found = true;
                    bestScore = score;
                    bestX = gx;
                    bestY = gy;
                }
            }
            if (found) {
                break;
            }
        }
        outGoal[0] = bestX;
        outGoal[1] = bestY;
    }

    /** 平滑更新举架（isAiming）迟滞门控，杜绝因硬直/敌数瞬间变化造成的举架↔收架乒乓。 */
    public static boolean updateAimStanceHysteresis(
            CombatMoveState state, float distToTarget, float enterStanceDist, double nowSim) {
        float enterDist = Math.max(1.95F, enterStanceDist);
        float exitDist = enterDist + STANCE_HYSTERESIS_TILES;
        if (!state.aimStanceActive) {
            if (distToTarget <= enterDist) {
                state.aimStanceActive = true;
                state.aimStanceChangedSim = nowSim;
            }
        } else if (distToTarget > exitDist && (nowSim - state.aimStanceChangedSim) >= STANCE_EXIT_DWELL_SIM) {
            state.aimStanceActive = false;
            state.aimStanceChangedSim = nowSim;
        }
        return state.aimStanceActive;
    }

    /** 检测是否有队友挡在当前角色到目标的连线上（前方 {@code t in [0.15, 0.90]} 且横向偏移 {@code < 0.65m}）。 */
    public static boolean isAllyBlockingLine(
            float selfX,
            float selfY,
            float targetX,
            float targetY,
            float[] allyXs,
            float[] allyYs,
            int allyCount) {
        if (allyXs == null || allyYs == null || allyCount <= 0) {
            return false;
        }
        float vx = targetX - selfX;
        float vy = targetY - selfY;
        float lenSq = vx * vx + vy * vy;
        if (lenSq < 0.25F) {
            return false;
        }
        float len = (float) Math.sqrt(lenSq);
        float ux = vx / len;
        float uy = vy / len;
        int n = Math.min(allyCount, Math.min(allyXs.length, allyYs.length));
        for (int i = 0; i < n; i++) {
            float rx = allyXs[i] - selfX;
            float ry = allyYs[i] - selfY;
            float along = rx * ux + ry * uy;
            if (along < len * 0.15F || along > len * 0.90F) {
                continue;
            }
            float lateral = Math.abs(rx * (-uy) + ry * ux);
            if (lateral < 0.65F) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCloseStandingThreat(
            EmberSquadDirector director,
            float selfX,
            float selfY,
            int selfZ,
            float radius,
            int excludeZombieId) {
        if (director == null) {
            return false;
        }
        float rSq = radius * radius;
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || t.zombieId == excludeZombieId || Math.abs(t.z - selfZ) > 0.6F) {
                continue;
            }
            if (!CombatTargetState.isStanding(t.stateMask)) {
                continue;
            }
            float dx = t.x - selfX;
            float dy = t.y - selfY;
            if (dx * dx + dy * dy <= rSq) {
                return true;
            }
        }
        return false;
    }

    /** 近战单轨走位计算（极坐标滞回状态机 + 挥击锁步 + 16 向圆周角避障）。 */
    public static void computeMeleeStep(
            CombatMoveState state,
            EmberSquadDirector director,
            String npcId,
            float selfX,
            float selfY,
            int selfZ,
            float targetX,
            float targetY,
            int targetMask,
            float slotX,
            float slotY,
            float idealMin,
            float idealMax,
            float cooldownMin,
            float cooldownMax,
            boolean attackOnCooldown,
            boolean performingSwing,
            float stanceBaseDist,
            float[] allyXs,
            float[] allyYs,
            int allyCount,
            boolean holdActive,
            float holdX,
            float holdY,
            float holdRadius,
            SafeSideGuard guard,
            EmberSquadDirector.PassabilityProbe probe,
            double nowSim,
            float dtSim,
            MoveCommand out) {
        computeMeleeStep(
                state, director, npcId, selfX, selfY, selfZ,
                -1, targetX, targetY, targetMask, slotX, slotY,
                idealMin, idealMax, cooldownMin, cooldownMax,
                attackOnCooldown, performingSwing, stanceBaseDist,
                allyXs, allyYs, allyCount,
                holdActive, holdX, holdY, holdRadius,
                guard, probe, nowSim, dtSim, out);
    }

    /**
     * 对齐 {@code ProjectRemnants.combatStanceDistance} 的动态进架基准距离（{@code [1.30, 3.50]} 格）：
     * 随武器有效射程、丧尸逼近速度与周边敌数平滑抬升；倒地/硬直破绽缩至贴身压制。
     */
    public static float computeDynamicStanceDist(
            float reliableRange,
            int nearbyEnemies,
            boolean attackingMe,
            boolean vulnerable,
            boolean groundAttackable,
            boolean sprinter,
            float closingSpeed,
            boolean currentlyAiming) {
        float d;
        if (!groundAttackable && !vulnerable) {
            d = reliableRange + 0.65F + Math.min(0.90F, Math.max(0.0F, closingSpeed) * 0.75F);
            if (attackingMe) {
                d = Math.max(d, 3.00F);
            }
            if (sprinter && closingSpeed > 0.0F) {
                d = Math.max(d, 3.20F);
            }
            if (nearbyEnemies >= 2) {
                d = Math.max(d, 2.80F);
            }
            if (nearbyEnemies >= 4) {
                d = Math.max(d, 3.10F);
            }
        } else {
            d = reliableRange + 0.40F;
        }
        if (currentlyAiming) {
            d += 0.35F;
        }
        return Math.max(1.30F, Math.min(3.50F, d));
    }

    private static float frameRetention(float base60Fps, double dtSec) {
        float clamped = Math.max(0.0F, Math.min(1.0F, base60Fps));
        if (dtSec <= 0.0) {
            return clamped;
        }
        return (float) Math.pow(clamped, dtSec * 60.0);
    }

    private static void wallAvoid9(
            float selfX,
            float selfY,
            int selfZ,
            float ux,
            float uy,
            float mag,
            EmberSquadDirector.PassabilityProbe probe,
            float[] outVec) {
        if (probe == null) {
            outVec[0] = ux * mag;
            outVec[1] = uy * mag;
            return;
        }
        final float probeDist = 1.35F;
        for (int i = 0; i < WALL_PROBE_RADS.length; i++) {
            float rad = WALL_PROBE_RADS[i];
            float cos = (rad == 0.0F) ? 1.0F : (float) Math.cos(rad);
            float sin = (rad == 0.0F) ? 0.0F : (float) Math.sin(rad);
            float rx = ux * cos - uy * sin;
            float ry = ux * sin + uy * cos;
            if (probe.isLineClear(selfX, selfY, selfX + rx * probeDist, selfY + ry * probeDist, selfZ)) {
                outVec[0] = rx * mag;
                outVec[1] = ry * mag;
                return;
            }
        }
        outVec[0] = 0.0F;
        outVec[1] = 0.0F;
    }

    /** 连续弹簧 + 0.25s 速度前瞻近战风筝求解器（支持挥击期举架走 A 与防抖零抽搐）。 */
    public static void computeMeleeStep(
            CombatMoveState state,
            EmberSquadDirector director,
            String npcId,
            float selfX,
            float selfY,
            int selfZ,
            int mainTargetId,
            float targetX,
            float targetY,
            int targetMask,
            float slotX,
            float slotY,
            float idealMin,
            float idealMax,
            float cooldownMin,
            float cooldownMax,
            boolean attackOnCooldown,
            boolean performingSwing,
            float stanceBaseDist,
            float[] allyXs,
            float[] allyYs,
            int allyCount,
            boolean holdActive,
            float holdX,
            float holdY,
            float holdRadius,
            SafeSideGuard guard,
            EmberSquadDirector.PassabilityProbe probe,
            double nowSim,
            float dtSim,
            MoveCommand out) {
        out.reset();
        float toTgtX = targetX - selfX;
        float toTgtY = targetY - selfY;
        float dist = (float) Math.hypot(toTgtX, toTgtY);
        if (dist < 1.0E-4F) {
            out.aimStrafe = true;
            return;
        }

        // 0. 切换主目标时清空目标速度与输出低通状态，避免继承上一只僵尸的速度判断
        if (mainTargetId >= 0 && state.spacingTargetId != mainTargetId) {
            state.resetSpacingFilter(mainTargetId);
        }

        // 1. 0.35s 窗口内帧率无关低通估算目标移动速度
        if (Float.isFinite(state.prevTargetX) && Float.isFinite(state.prevTargetY) && state.lastTargetSampleSim >= 0.0) {
            double sampleDt = nowSim - state.lastTargetSampleSim;
            if (sampleDt > 1.0E-4 && sampleDt <= 0.35) {
                float ret = frameRetention(0.80F, sampleDt);
                float instVx = (float) ((targetX - state.prevTargetX) / sampleDt);
                float instVy = (float) ((targetY - state.prevTargetY) / sampleDt);
                state.estVelX = state.estVelX * ret + instVx * (1.0F - ret);
                state.estVelY = state.estVelY * ret + instVy * (1.0F - ret);
            } else if (sampleDt > 0.35) {
                state.estVelX = 0.0F;
                state.estVelY = 0.0F;
            }
        }
        state.prevTargetX = targetX;
        state.prevTargetY = targetY;
        state.lastTargetSampleSim = nowSim;

        boolean aimStance = updateAimStanceHysteresis(state, dist, stanceBaseDist, nowSim);
        if (performingSwing) {
            // 挥击动画播放期间绝不收架转身跑；允许举架边退边打（走 A）
            aimStance = true;
            state.aimStanceActive = true;
        }

        float toSlotX = slotX - selfX;
        float toSlotY = slotY - selfY;
        float toSlotDist = (float) Math.hypot(toSlotX, toSlotY);
        boolean allyBlocking = isAllyBlockingLine(selfX, selfY, targetX, targetY, allyXs, allyYs, allyCount);
        boolean flankingToSlot = !performingSwing
                && toSlotDist > 0.55F
                && (allyBlocking || toSlotDist > 0.85F)
                && !attackOnCooldown
                && !CombatTargetState.isGroundAttackable(targetMask);

        if (flankingToSlot && (allyBlocking || dist > 1.90F)
                && !hasCloseStandingThreat(director, selfX, selfY, selfZ, 1.55F, allyBlocking ? mainTargetId : -1)) {
            aimStance = false;
            state.aimStanceActive = false;
            state.aimStanceChangedSim = nowSim;
        }
        out.aimStrafe = aimStance;

        // 2. 四档连续 [inner, outer] 死区（强约束 inner <= outer - 0.20）
        boolean groundAttackable = CombatTargetState.isGroundAttackable(targetMask);
        boolean staggered = CombatTargetState.isStaggered(targetMask)
                || CombatTargetState.isPostureTransition(targetMask);
        float inner;
        float outer;
        if (groundAttackable) {
            inner = 0.24F;
            outer = Math.max(0.54F, idealMax);
        } else if (!attackOnCooldown && staggered) {
            inner = idealMin * 0.65F;
            outer = idealMax * 0.90F;
        } else if (attackOnCooldown) {
            inner = cooldownMin;
            outer = Math.max(cooldownMin + 0.30F, cooldownMax);
        } else {
            inner = idealMin;
            outer = idealMax;
        }
        if (inner > outer - 0.20F) {
            inner = Math.max(0.0F, outer - 0.20F);
        }

        float uxToTgt = toTgtX / dist;
        float uyToTgt = toTgtY / dist;
        float mainX = 0.0F;
        float mainY = 0.0F;

        if (dist < inner) {
            float strength = Math.min(1.0F, Math.max(0.45F, (inner - dist) / 0.85F));
            mainX = -uxToTgt * strength * 1.20F;
            mainY = -uyToTgt * strength * 1.20F;
            state.radialMode = RadialMode.RETREAT;
        } else if (flankingToSlot) {
            float strength = Math.min(1.0F, Math.max(0.50F, toSlotDist / 1.20F));
            mainX = (toSlotX / toSlotDist) * strength * 1.20F;
            mainY = (toSlotY / toSlotDist) * strength * 1.20F;
            state.radialMode = RadialMode.ADVANCE;
        } else if (dist > outer) {
            float strength = Math.min(1.0F, Math.max(0.35F, (dist - outer) / 1.50F));
            if (toSlotDist > 0.15F) {
                mainX = (toSlotX / toSlotDist) * strength * 1.20F;
                mainY = (toSlotY / toSlotDist) * strength * 1.20F;
            } else {
                mainX = uxToTgt * strength * 1.20F;
                mainY = uyToTgt * strength * 1.20F;
            }
            state.radialMode = RadialMode.ADVANCE;
        } else {
            state.radialMode = RadialMode.HOLD;
        }
        out.radialMode = state.radialMode;

        // 3. 0.25s 目标速度前瞻修正（非破绽/非倒地：僵尸刚迈步逼近即提前顺滑后撤）
        float predCorrX = 0.0F;
        float predCorrY = 0.0F;
        if (!staggered && !groundAttackable) {
            float predX = targetX + state.estVelX * 0.25F;
            float predY = targetY + state.estVelY * 0.25F;
            float toPredX = predX - selfX;
            float toPredY = predY - selfY;
            float predDist = (float) Math.hypot(toPredX, toPredY);
            if (predDist > 0.01F) {
                float optimal = 0.5F * (inner + outer);
                float bias = Math.max(-1.0F, Math.min(1.0F, (predDist - optimal) / 2.0F));
                if (!attackOnCooldown && bias > 0.0F && dist <= outer) {
                    bias = 0.0F;
                }
                predCorrX = (toPredX / predDist) * bias * 0.35F;
                predCorrY = (toPredY / predDist) * bias * 0.35F;
            }
        }

        float vecX = mainX + predCorrX;
        float vecY = mainY + predCorrY;

        // 4. 避开非主目标爬行者、过近队友及射击走廊
        if (director != null) {
            for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
                EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
                if (!t.active || t.zombieId == mainTargetId || Math.abs(t.z - selfZ) > 0.6F) {
                    continue;
                }
                if (CombatTargetState.isProneOrCrawl(t.stateMask)) {
                    float rx = selfX - t.x;
                    float ry = selfY - t.y;
                    float cd = (float) Math.hypot(rx, ry);
                    if (cd < 2.60F && cd > 0.01F) {
                        float w = 0.35F + ((2.60F - cd) / 2.60F) * 1.05F;
                        vecX += (rx / cd) * w;
                        vecY += (ry / cd) * w;
                    }
                }
            }
            EmberSquadDirector.CorridorAvoidanceResult corrRes = state.corridorScratch;
            director.evaluateCorridorAvoidance(selfX, selfY, selfZ, npcId, dist, probe, corrRes);
            if (corrRes.inCorridor) {
                vecX += corrRes.pushX * 0.65F;
                vecY += corrRes.pushY * 0.65F;
            }
        }
        if (allyXs != null && allyYs != null && allyCount > 0) {
            int n = Math.min(allyCount, Math.min(allyXs.length, allyYs.length));
            for (int a = 0; a < n; a++) {
                float adx = selfX - allyXs[a];
                float ady = selfY - allyYs[a];
                float ad = (float) Math.hypot(adx, ady);
                if (ad < 0.88F && ad > 0.01F) {
                    float w = (0.88F - ad) * 0.90F;
                    vecX += (adx / ad) * w;
                    vecY += (ady / ad) * w;
                }
            }
        }
        if (guard != null && guard.hasForbiddenHalfSpace) {
            float probeX = selfX + vecX * 0.6F;
            float probeY = selfY + vecY * 0.6F;
            if (guard.isPointInForbiddenPocket(probeX, probeY, 0.10F)) {
                vecX -= guard.forbiddenNormalX * 1.10F;
                vecY -= guard.forbiddenNormalY * 1.10F;
            }
        }
        if (holdActive && holdRadius > 0.5F) {
            float hx = selfX - holdX;
            float hy = selfY - holdY;
            float hd = (float) Math.hypot(hx, hy);
            if (hd > holdRadius * 0.92F && hd > 0.01F) {
                float dotOut = (vecX * hx + vecY * hy) / hd;
                if (dotOut > 0.0F) {
                    vecX -= (hx / hd) * dotOut * 1.25F;
                    vecY -= (hy / hd) * dotOut * 1.25F;
                }
            }
        }

        // 5. 9 向顺墙滑移（仅向量足够长时触发，防微噪声贴墙抖动）
        float rawMag = (float) Math.hypot(vecX, vecY);
        if (rawMag > 0.30F) {
            wallAvoid9(selfX, selfY, selfZ, vecX / rawMag, vecY / rawMag, rawMag, probe, state.wallScratch);
            vecX = state.wallScratch[0];
            vecY = state.wallScratch[1];
        }

        // 6. 帧率无关输出低通滤波 + <0.15 噪声截断
        float retainBase = staggered ? 0.05F : ((attackOnCooldown || nearbyEnemiesLikeMulti(director, selfX, selfY, selfZ)) ? 0.45F : 0.15F);
        double outDt = (state.lastOutSampleSim >= 0.0 && nowSim > state.lastOutSampleSim)
                ? Math.min(0.10, nowSim - state.lastOutSampleSim)
                : Math.max(0.004, Math.min(0.10, dtSim));
        float outRet = frameRetention(retainBase, outDt);
        state.outVelX = state.outVelX * outRet + vecX * (1.0F - outRet);
        state.outVelY = state.outVelY * outRet + vecY * (1.0F - outRet);
        state.lastOutSampleSim = nowSim;

        float outMag = (float) Math.hypot(state.outVelX, state.outVelY);
        if (outMag < 0.15F) {
            out.wantMove = false;
            return;
        }

        out.wantMove = true;
        out.dirX = state.outVelX / outMag;
        out.dirY = state.outVelY / outMag;
        out.runGear = !performingSwing && !aimStance && ((flankingToSlot && toSlotDist > 1.35F) || dist > stanceBaseDist + 1.20F);
        out.walkSpeedCap = aimStance ? -1.0F : (flankingToSlot ? -1.0F : 0.85F);
    }

    private static boolean nearbyEnemiesLikeMulti(
            EmberSquadDirector director, float selfX, float selfY, int selfZ) {
        if (director == null) {
            return false;
        }
        int c = 0;
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || !CombatTargetState.isStanding(t.stateMask) || Math.abs(t.z - selfZ) > 0.6F) {
                continue;
            }
            float dx = t.x - selfX;
            float dy = t.y - selfY;
            if (dx * dx + dy * dy <= 9.0F) {
                c++;
                if (c >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 远程近身退避走位（2.55m / 3.45m 迟滞环 + 16 向安全避障）。 */
    public static void computeRangedRetreatStep(
            CombatMoveState state,
            EmberSquadDirector director,
            String npcId,
            float selfX,
            float selfY,
            int selfZ,
            float nearestThreatX,
            float nearestThreatY,
            float nearestThreatDist,
            float[] allyXs,
            float[] allyYs,
            int allyCount,
            boolean holdActive,
            float holdX,
            float holdY,
            float holdRadius,
            EmberSquadDirector.PassabilityProbe probe,
            double nowSim,
            float dtSim,
            MoveCommand out) {
        out.reset();
        out.aimStrafe = true;
        if (!state.rangedRetreatActive) {
            if (nearestThreatDist < RANGED_RETREAT_ENTER_DIST) {
                state.rangedRetreatActive = true;
            }
        } else if (nearestThreatDist >= RANGED_RETREAT_EXIT_DIST) {
            state.rangedRetreatActive = false;
        }

        if (!state.rangedRetreatActive) {
            state.hasMoveAngle = false;
            out.wantMove = false;
            out.radialMode = RadialMode.HOLD;
            return;
        }

        float dx = selfX - nearestThreatX;
        float dy = selfY - nearestThreatY;
        float d = (float) Math.hypot(dx, dy);
        float awayX = d > 1.0E-4F ? dx / d : 1.0F;
        float awayY = d > 1.0E-4F ? dy / d : 0.0F;

        boolean solved = solve16DirAvoidance(
                state, director, npcId, selfX, selfY, selfZ,
                awayX, awayY, nearestThreatDist, true, false, -1,
                allyXs, allyYs, allyCount,
                holdActive, holdX, holdY, holdRadius,
                null, probe, nowSim, dtSim, out);
        out.radialMode = solved ? RadialMode.RETREAT : RadialMode.HOLD;
        out.wantMove = solved;
        out.runGear = false;
        out.walkSpeedCap = -1.0F;
    }

    private static boolean solve16DirAvoidance(
            CombatMoveState state,
            EmberSquadDirector director,
            String npcId,
            float selfX,
            float selfY,
            int selfZ,
            float desX,
            float desY,
            float mainTargetDist,
            boolean rangedRetreat,
            boolean advancing,
            int excludeTargetId,
            float[] allyXs,
            float[] allyYs,
            int allyCount,
            boolean holdActive,
            float holdX,
            float holdY,
            float holdRadius,
            SafeSideGuard guard,
            EmberSquadDirector.PassabilityProbe probe,
            double nowSim,
            float dtSim,
            MoveCommand out) {
        EmberSquadDirector.CorridorAvoidanceResult corrRes = null;
        if (director != null) {
            corrRes = state.corridorScratch;
            director.evaluateCorridorAvoidance(selfX, selfY, selfZ, npcId, mainTargetDist, probe, corrRes);
        }

        int bestIdx = -1;
        float bestScore = -Float.MAX_VALUE;
        float probeStep = rangedRetreat ? 1.15F : 0.85F;

        for (int i = 0; i < NUM_DIRS; i++) {
            float dx = DIR_COS[i];
            float dy = DIR_SIN[i];
            float testX = selfX + dx * probeStep;
            float testY = selfY + dy * probeStep;

            if (probe != null && !probe.isLineClear(selfX, selfY, testX, testY, selfZ)) {
                continue;
            }
            if (holdActive && holdRadius > 0.5F
                    && Math.hypot(testX - holdX, testY - holdY) > holdRadius
                    && Math.hypot(testX - holdX, testY - holdY) > Math.hypot(selfX - holdX, selfY - holdY)) {
                continue;
            }

            float align = dx * desX + dy * desY;
            float score = align * 1.80F;

            if (guard != null && guard.isPointInForbiddenPocket(testX, testY, 0.15F)) {
                score -= 2.40F;
            }

            if (director != null) {
                if (advancing) {
                    float secondaryDanger = evaluatePointThreatDanger(testX, testY, selfZ, director, excludeTargetId);
                    score -= Math.min(0.90F, secondaryDanger * 0.18F);
                } else {
                    float danger = evaluatePointThreatDanger(testX, testY, selfZ, director, -1);
                    score -= danger * (rangedRetreat ? 1.40F : 0.70F);
                }
            }

            if (allyXs != null && allyYs != null && allyCount > 0) {
                int n = Math.min(allyCount, Math.min(allyXs.length, allyYs.length));
                for (int a = 0; a < n; a++) {
                    float adx = testX - allyXs[a];
                    float ady = testY - allyYs[a];
                    float ad = (float) Math.hypot(adx, ady);
                    if (ad < 0.90F) {
                        score -= (0.90F - ad) * 1.35F;
                    }
                }
            }

            if (corrRes != null && corrRes.inCorridor) {
                float pushLen = (float) Math.hypot(corrRes.pushX, corrRes.pushY);
                if (pushLen > 1.0E-4F) {
                    score += (dx * (corrRes.pushX / pushLen) + dy * (corrRes.pushY / pushLen)) * 0.95F;
                }
            }

            if (i == state.latchedDirIdx && nowSim < state.dirLatchUntilSim) {
                score += 0.35F;
            }

            if (score > bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }

        if (bestIdx < 0) {
            return false;
        }

        if (bestIdx != state.latchedDirIdx && nowSim >= state.dirLatchUntilSim) {
            state.latchedDirIdx = bestIdx;
            state.dirLatchUntilSim = nowSim + SLOT_LATCH_SIM;
        } else if (state.latchedDirIdx < 0) {
            state.latchedDirIdx = bestIdx;
            state.dirLatchUntilSim = nowSim + SLOT_LATCH_SIM;
        }

        float chosenAng = bestIdx * (float) (Math.PI * 2.0 / NUM_DIRS);
        if (!state.hasMoveAngle) {
            state.smoothedMoveAngleRad = chosenAng;
            state.hasMoveAngle = true;
        } else {
            float delta = NpcVirtualAim.shortestAngleDelta(state.smoothedMoveAngleRad, chosenAng);
            float maxStep = MAX_ANGLE_SLEW_RAD_PER_SEC * Math.max(0.004F, Math.min(0.1F, dtSim));
            if (Math.abs(delta) <= maxStep) {
                state.smoothedMoveAngleRad = chosenAng;
            } else {
                state.smoothedMoveAngleRad = NpcVirtualAim.normalizeRadians(
                        state.smoothedMoveAngleRad + Math.signum(delta) * maxStep);
            }
        }

        out.dirX = (float) Math.cos(state.smoothedMoveAngleRad);
        out.dirY = (float) Math.sin(state.smoothedMoveAngleRad);
        return true;
    }
}
