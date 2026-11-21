package emberroutes.npc.perception;

import emberroutes.npc.EmberPostureMath;
import emberroutes.npc.EmberTargetProjector;
import emberroutes.npc.IsoNPC;
import emberroutes.npc.perception.StimulusSlot.StimulusKind;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.LosUtil;

/**
 * 单个 {@link IsoNPC} 的统一轻量环境感知器（0-GC、错相位分帧、引擎原生可见性剪枝）：
 * <ul>
 *   <li><b>重要度分档（Significance LOD）</b>：直读 B42 引擎光照线程已算好的
 *       {@link IsoGridSquare#isCouldSee(int)} 360° 几何视线位；不可见时直接落入 {@code HIDDEN}，
 *       装饰性感知 0 射线、0 堆分配；</li>
 *   <li><b>O(1) 缓存视线校验</b>：仅对 {@code NEAR}（≤ 8 格）且位于前方扇区内的刺激，查
 *       {@link LosUtil#lineClearCached} 缓存表以额外剔除 {@code ClearThroughClosedDoor}（不隔闭门抬眼）；</li>
 *   <li><b>共享刺激记忆槽</b>：所有微反应与注视控制器共享同一份 {@link StimulusSlot}，
 *       不随动作数量线性增加采样开销。</li>
 * </ul>
 */
public final class NpcPerception {
    /** 近景焦点区距离平方阈值（8 格）。 */
    public static final float NEAR_DIST_SQ = 64.0F;
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
    }

    /**
     * 在 {@code AGENT_BRAIN} 阶段、状态树决策之前调用。
     */
    public void tick(IsoNPC npc, double simTime, double dtSim) {
        double step = Math.max(0.0, Math.min(0.25, dtSim));
        if (npc == null || npc.isDead()) {
            this.significance = PerceptionSignificance.HIDDEN;
            AttentionSelector.advanceAbsence(playerSlot, step, REARM_ABSENCE_SEC);
            playerSlot.visible = false;
            playerSlot.signedAngleRad = Float.NaN;
            return;
        }

        IsoPlayer player = resolveLocalVisiblePlayer(npc);
        IsoGridSquare npcSq = npc.getCurrentSquare();
        if (player == null || npcSq == null || !npcSq.isCouldSee(0)) {
            this.significance = PerceptionSignificance.HIDDEN;
            AttentionSelector.updateStimulusMemory(
                    playerSlot, false, 0.0F, 0.0F, 0.0F, 0, Float.POSITIVE_INFINITY, Float.NaN,
                    simTime, step, REARM_ABSENCE_SEC);
            return;
        }

        float dx = player.getX() - npc.getX();
        float dy = player.getY() - npc.getY();
        float distSq = dx * dx + dy * dy;
        this.significance = (distSq <= NEAR_DIST_SQ) ? PerceptionSignificance.NEAR : PerceptionSignificance.FAR;

        if (this.significance != PerceptionSignificance.NEAR) {
            AttentionSelector.updateStimulusMemory(
                    playerSlot, false, player.getX(), player.getY(), player.getZ(),
                    EmberTargetProjector.levelOf(player.getZ()), distSq, Float.NaN,
                    simTime, step, REARM_ABSENCE_SEC);
            return;
        }

        // 错相位轮询：非采样帧仅推进离场倒计时，避免多 NPC 同帧尖峰
        pollAccumSec += step;
        if (pollAccumSec < SENSOR_POLL_INTERVAL_SEC) {
            AttentionSelector.advanceAbsence(playerSlot, step, REARM_ABSENCE_SEC);
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

        boolean visibleNow = !Float.isNaN(angleRad) && hasClearCachedLineOfSight(npc, player);
        AttentionSelector.updateStimulusMemory(
                playerSlot, visibleNow,
                player.getX(), player.getY(), player.getZ(), playerLevel,
                distSq, visibleNow ? angleRad : Float.NaN,
                simTime, pollDt, REARM_ABSENCE_SEC);
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
     * O(1) 查表校验无墙体且不隔闭门：复用引擎光照阶段已填充的 {@code LosUtil.cachedresults[0]}。
     */
    private static boolean hasClearCachedLineOfSight(IsoNPC npc, IsoPlayer player) {
        IsoCell cell = npc.getCell();
        if (cell == null) {
            return false;
        }
        LosUtil.TestResults res = LosUtil.lineClearCached(
                cell,
                (int) Math.floor(npc.getX()),
                (int) Math.floor(npc.getY()),
                (int) Math.floor(npc.getZ()),
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()),
                false,
                0);
        return res != LosUtil.TestResults.Blocked
                && res != LosUtil.TestResults.ClearThroughClosedDoor;
    }
}
