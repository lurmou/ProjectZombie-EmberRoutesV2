package emberroutes.npc.perception;

import emberroutes.npc.EmberPostureMath;
import emberroutes.npc.EmberTargetProjector;
import emberroutes.npc.IsoNPC;
import emberroutes.npc.perception.StimulusSlot.StimulusKind;
import java.util.Locale;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.LosUtil;

/**
 * 单个 {@link IsoNPC} 的统一轻量环境感知器（0-GC、错相位分帧、四级漏斗剪枝）：
 * <ol>
 *   <li><b>纯浮点距离剪枝</b>：先判 {@code distSq <= 64}（8 格），远处 NPC 仅 2 次浮点乘法即短路，零 JNI 开销；</li>
 *   <li><b>引擎原生可见性剪枝</b>：仅对 8 格内 NPC 查 {@link IsoGridSquare#isCouldSee(int)} 360° 几何视线位，
 *       隔墙/别屋直接落入 {@code HIDDEN}；</li>
 *   <li><b>0.12s 错相位 + 前方视野锥</b>：按 {@code npcId} 哈希错开相位，过滤背后与 4.5 格以外；</li>
 *   <li><b>无状态视线精检</b>：仅对同屋、≤ 4.5 格且位于前方锥内的 1~2 个 NPC 调无状态
 *       {@link LosUtil#lineClear}（避免 B42 {@code LightingJNI} 管线下 {@code lineClearCached} 永不清零导致的永久脏读）
 *       以剔除 {@code Blocked / ClearThroughClosedDoor}。</li>
 * </ol>
 */
public final class NpcPerception {
    /** 近景焦点区距离平方阈值（8 格）。 */
    public static final float NEAR_DIST_SQ = 64.0F;
    /** 诊断日志关注距离平方阈值（6 格，仅用于边缘触发状态变化日志）。 */
    private static final float DIAG_DIST_SQ = 36.0F;
    /** 触发检测最大水平距离（格）。 */
    public static final float TRIGGER_MAX_DIST = EmberPostureMath.GLANCE_TRIGGER_MAX_DIST;
    /** 触发检测最小水平距离（防重叠奇点）。 */
    public static final float TRIGGER_MIN_DIST = EmberPostureMath.GLANCE_TRIGGER_MIN_DIST;
    /** 保持在场状态的迟滞最大距离（格）。 */
    public static final float PRESENCE_HYSTERESIS_DIST = EmberPostureMath.GLANCE_PRESENCE_HYSTERESIS_DIST;
    /** 前方可视扇区最小点积（cos 75.5° ≈ 0.25，杜绝背后触发）。 */
    public static final float TRIGGER_MIN_DOT = EmberPostureMath.GLANCE_TRIGGER_MIN_DOT;
    /** 保持在场状态的迟滞最小点积（cos 84° ≈ 0.10）。 */
    public static final float PRESENCE_HYSTERESIS_DOT = EmberPostureMath.GLANCE_PRESENCE_HYSTERESIS_DOT;
    /** 刺激离开视野多久后解除习惯化重新武装（秒）。 */
    public static final double REARM_ABSENCE_SEC = 4.0;
    /** 感知采样基础间隔（秒）。 */
    public static final double SENSOR_POLL_INTERVAL_SEC = 0.12;

    private final int phaseOffsetBucket;
    private PerceptionSignificance significance = PerceptionSignificance.HIDDEN;
    private final StimulusSlot playerSlot = new StimulusSlot(StimulusKind.PLAYER);
    private double pollAccumSec;
    private float forwardX = 0.0F;
    private float forwardY = 1.0F;
    private String lastDiagGate = "INIT";

    public NpcPerception(String npcId) {
        int hash = npcId != null ? npcId.hashCode() : 0;
        this.phaseOffsetBucket = Math.floorMod(hash, 4);
        this.pollAccumSec = this.phaseOffsetBucket * (SENSOR_POLL_INTERVAL_SEC * 0.25);
    }

    public PerceptionSignificance getSignificance() {
        return significance;
    }

    public StimulusSlot getPlayerSlot() {
        return playerSlot;
    }

    public void reset() {
        this.significance = PerceptionSignificance.HIDDEN;
        this.playerSlot.reset();
        this.pollAccumSec = this.phaseOffsetBucket * (SENSOR_POLL_INTERVAL_SEC * 0.25);
        this.lastDiagGate = "INIT";
    }

    /**
     * 在 {@code AGENT_BRAIN} 阶段、状态树决策之前调用。
     */
    public void tick(IsoNPC npc, double simTime, double dtSim) {
        double step = Math.max(0.0, Math.min(0.25, dtSim));
        boolean wasHabituated = playerSlot.habituated;
        if (npc == null || npc.isDead()) {
            this.significance = PerceptionSignificance.HIDDEN;
            AttentionSelector.advanceAbsence(playerSlot, step, REARM_ABSENCE_SEC);
            playerSlot.visible = false;
            playerSlot.signedAngleRad = Float.NaN;
            return;
        }

        IsoPlayer player = resolveLocalVisiblePlayer(npc);
        if (player == null) {
            this.significance = PerceptionSignificance.HIDDEN;
            AttentionSelector.updateStimulusMemory(
                    playerSlot, false, 0.0F, 0.0F, 0.0F, 0, Float.POSITIVE_INFINITY, Float.NaN,
                    simTime, step, REARM_ABSENCE_SEC);
            logRearmedIfChanged(npc, wasHabituated);
            return;
        }

        // 第 1 级漏斗：纯浮点距离平方（2 次乘法，0 JNI）
        float dx = player.getX() - npc.getX();
        float dy = player.getY() - npc.getY();
        float distSq = dx * dx + dy * dy;
        if (distSq > NEAR_DIST_SQ) {
            this.significance = PerceptionSignificance.FAR;
            this.lastDiagGate = "FAR";
            AttentionSelector.updateStimulusMemory(
                    playerSlot, false, player.getX(), player.getY(), player.getZ(),
                    EmberTargetProjector.levelOf(player.getZ()), distSq, Float.NaN,
                    simTime, step, REARM_ABSENCE_SEC);
            logRearmedIfChanged(npc, wasHabituated);
            return;
        }

        // 第 2 级漏斗：引擎光照线程已算好的 360° 几何视线位
        IsoGridSquare npcSq = npc.getCurrentSquare();
        if (npcSq == null || !npcSq.isCouldSee(0)) {
            this.significance = PerceptionSignificance.HIDDEN;
            if (distSq <= DIAG_DIST_SQ) {
                logGateChange(npc, "COULDSEE_FALSE", distSq, Float.NaN, "N/A");
            }
            AttentionSelector.updateStimulusMemory(
                    playerSlot, false, player.getX(), player.getY(), player.getZ(),
                    EmberTargetProjector.levelOf(player.getZ()), distSq, Float.NaN,
                    simTime, step, REARM_ABSENCE_SEC);
            logRearmedIfChanged(npc, wasHabituated);
            return;
        }

        this.significance = PerceptionSignificance.NEAR;

        // 第 3 级漏斗：0.12s 错相位轮询（非采样帧仅推进离场倒计时）
        pollAccumSec += step;
        if (pollAccumSec < SENSOR_POLL_INTERVAL_SEC) {
            AttentionSelector.advanceAbsence(playerSlot, step, REARM_ABSENCE_SEC);
            logRearmedIfChanged(npc, wasHabituated);
            return;
        }
        double pollDt = pollAccumSec;
        pollAccumSec = 0.0;

        resolveReferenceForwardUnit(npc);
        int npcLevel = EmberTargetProjector.levelOf(npc.getZ());
        int playerLevel = EmberTargetProjector.levelOf(player.getZ());
        float maxDist = playerSlot.habituated ? PRESENCE_HYSTERESIS_DIST : TRIGGER_MAX_DIST;
        float minDot = playerSlot.habituated ? PRESENCE_HYSTERESIS_DOT : TRIGGER_MIN_DOT;

        float angleRad = EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                npc.getX(), npc.getY(), npcLevel,
                forwardX, forwardY,
                player.getX(), player.getY(), playerLevel,
                TRIGGER_MIN_DIST, maxDist, minDot);

        // 第 4 级漏斗：无状态 LosUtil.lineClear 剔除墙体与闭门
        boolean visibleNow = false;
        if (Float.isNaN(angleRad)) {
            if (distSq <= DIAG_DIST_SQ) {
                logGateChange(npc, "OUT_OF_CONE", distSq, Float.NaN, "SKIP");
            }
        } else {
            LosUtil.TestResults los = testStatelessLineOfSight(npc, player);
            visibleNow = (los != LosUtil.TestResults.Blocked
                    && los != LosUtil.TestResults.ClearThroughClosedDoor);
            if (distSq <= DIAG_DIST_SQ) {
                logGateChange(npc, visibleNow ? "VISIBLE" : ("LOS_" + los.name()), distSq, angleRad, los.name());
            }
        }

        AttentionSelector.updateStimulusMemory(
                playerSlot, visibleNow,
                player.getX(), player.getY(), player.getZ(), playerLevel,
                distSq, visibleNow ? angleRad : Float.NaN,
                simTime, pollDt, REARM_ABSENCE_SEC);
        logRearmedIfChanged(npc, wasHabituated);
    }

    /**
     * 注视包络进行中实时采样玩家相对偏角（0 射线、0 堆分配），使头部柔和跟进玩家微动。
     *
     * @return 若玩家仍在前方迟滞扇区内返回带符号偏角（弧度），否则返回 {@link Float#NaN}
     */
    public float sampleLivePlayerAngleRad(IsoNPC npc) {
        if (npc == null || npc.isDead() || significance != PerceptionSignificance.NEAR) {
            return Float.NaN;
        }
        IsoPlayer player = resolveLocalVisiblePlayer(npc);
        if (player == null) {
            return Float.NaN;
        }
        resolveReferenceForwardUnit(npc);
        return EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                npc.getX(), npc.getY(), EmberTargetProjector.levelOf(npc.getZ()),
                forwardX, forwardY,
                player.getX(), player.getY(), EmberTargetProjector.levelOf(player.getZ()),
                TRIGGER_MIN_DIST, PRESENCE_HYSTERESIS_DIST, PRESENCE_HYSTERESIS_DOT);
    }

    /**
     * 计算任意世界坐标相对于 NPC 参考朝向的带符号水平偏角（供高优行为注视请求使用，0-GC）。
     */
    public float sampleWorldPointAngleRad(IsoNPC npc, float targetX, float targetY, int targetLevel) {
        if (npc == null || npc.isDead()) {
            return Float.NaN;
        }
        resolveReferenceForwardUnit(npc);
        return EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                npc.getX(), npc.getY(), EmberTargetProjector.levelOf(npc.getZ()),
                forwardX, forwardY,
                targetX, targetY, targetLevel,
                TRIGGER_MIN_DIST, PRESENCE_HYSTERESIS_DIST * 2.0F, -0.25F);
    }

    private void resolveReferenceForwardUnit(IsoNPC npc) {
        IsoDirections dir = npc.isSittingOnFurniture() ? npc.getSitOnFurnitureDirection() : null;
        if (dir == null) {
            dir = npc.getDir();
        }
        if (dir != null && (dir.dx() != 0 || dir.dy() != 0)) {
            float dx = dir.dx();
            float dy = dir.dy();
            float len = (float) Math.hypot(dx, dy);
            this.forwardX = dx / len;
            this.forwardY = dy / len;
            return;
        }
        float fx = npc.getForwardDirectionX();
        float fy = npc.getForwardDirectionY();
        float len = (float) Math.hypot(fx, fy);
        if (len > 1.0E-5F) {
            this.forwardX = fx / len;
            this.forwardY = fy / len;
        } else {
            this.forwardX = 0.0F;
            this.forwardY = 1.0F;
        }
    }

    private static IsoPlayer resolveLocalVisiblePlayer(IsoNPC npc) {
        IsoPlayer p = IsoPlayer.getInstance();
        if (p == null && IsoPlayer.players != null && IsoPlayer.players.length > 0) {
            p = IsoPlayer.players[0];
        }
        if (p == null || p == npc || p.isDead() || p.isInvisible() || p.isGhostMode()) {
            return null;
        }
        return p;
    }

    /**
     * 无状态视线校验：直接调用 {@link LosUtil#lineClear}（不触碰单人模式下永不清零的 {@code cachedresults}），
     * 用于精准区分 {@code Blocked} 与 {@code ClearThroughClosedDoor}。
     */
    private static LosUtil.TestResults testStatelessLineOfSight(IsoNPC npc, IsoPlayer player) {
        IsoCell cell = npc.getCell();
        if (cell == null) {
            return LosUtil.TestResults.Blocked;
        }
        return LosUtil.lineClear(
                cell,
                (int) Math.floor(npc.getX()),
                (int) Math.floor(npc.getY()),
                (int) Math.floor(npc.getZ()),
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()),
                false);
    }

    private void logGateChange(IsoNPC npc, String gate, float distSq, float angleRad, String losName) {
        if (gate.equals(lastDiagGate)) {
            return;
        }
        lastDiagGate = gate;
        float dist = (float) Math.sqrt(Math.max(0.0F, distSq));
        float deg = Float.isNaN(angleRad) ? Float.NaN : (float) Math.toDegrees(angleRad);
        DebugLog.log(String.format(
                Locale.US,
                "[EmberRoutes][GAZE_DIAG] SENSE_GATE npc=%s gate=%s dist=%.2f angleDeg=%.1f los=%s hab=%s fwd=(%.2f,%.2f)",
                npc.getNpcId(), gate, dist, deg, losName, playerSlot.habituated, forwardX, forwardY));
    }

    private void logRearmedIfChanged(IsoNPC npc, boolean wasHabituated) {
        if (wasHabituated && !playerSlot.habituated) {
            DebugLog.log(String.format(
                    Locale.US,
                    "[EmberRoutes][GAZE_DIAG] REARMED npc=%s sig=%s",
                    npc.getNpcId(), significance));
        }
    }
}
