package emberroutes.npc.brain;

import emberroutes.npc.EmberPostureMath;
import emberroutes.npc.EmberTargetProjector;
import emberroutes.npc.IsoNPC;
import emberroutes.npc.NpcPostureController.PostureState;
import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.animation.AnimationMultiTrack;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.LosUtil;

/**
 * 座椅休闲阅读抬眼注视控制器（{@code SeatedReadingGlance}）：
 * <ul>
 *   <li><b>看见判定</b>：以 NPC 当前座椅朝向（{@code sitOnFurnitureDirection}）为基准，仅当真玩家处于同楼层、
 *       近距离（{@code 0.35m ~ 4.5m}）、前方可视扇区（{@code dot >= 0.25}，排除背后靠近）且视线无墙体/闭门阻挡时触发；</li>
 *   <li><b>左右分侧与单次锁存</b>：玩家从座椅左侧进入看左边，右侧进入看右边；在场期间只触发一次，
 *       离开可视范围持续满 {@code 4.0s} 后重新武装；</li>
 *   <li><b>样片三段节奏与翻页零打断</b>：严格复刻 Blender 样片“抬眼 {@code 0.60s} → 停半拍 {@code 0.25s} → 收回 {@code 0.70s}”包络，
 *       驱动 B42 原版 {@code headlook/LookAround} 2D 混合层，同时在注视期仅对 {@code Bob_IdleReadBook} 轨道释放
 *       {@code Bip01_Head / Bip01_Neck} 骨骼遮罩权重，100% 保留双手持书、翻书动作与 {@code 60%} 处 {@code PageFlip} 翻页进度。</li>
 * </ul>
 */
public final class SeatedReadingGlanceController {
    /** 触发检测最大水平距离（格）。 */
    public static final float TRIGGER_MAX_DIST = 4.5F;
    /** 触发检测最小水平距离（防重叠奇点）。 */
    public static final float TRIGGER_MIN_DIST = 0.35F;
    /** 保持在场状态的迟滞最大距离（格）。 */
    public static final float PRESENCE_HYSTERESIS_DIST = 5.5F;
    /** 座椅前方可视扇区最小点积（cos 75.5° ≈ 0.25，杜绝背后/正后侧触发）。 */
    public static final float TRIGGER_MIN_DOT = 0.25F;
    /** 保持在场状态的迟滞最小点积（cos 84° ≈ 0.10）。 */
    public static final float PRESENCE_HYSTERESIS_DOT = 0.10F;

    /** 样片阶段 1：抬眼转头时长（Frames 7..21 @ 24fps ≈ 0.60s）。 */
    public static final double RAISE_DURATION_SEC = 0.60;
    /** 样片阶段 2：停半拍注视时长（Frames 21..26 @ 24fps ≈ 0.25s）。 */
    public static final double HOLD_DURATION_SEC = 0.25;
    /** 样片阶段 3：低头收回时长（Frames 26..43 @ 24fps ≈ 0.70s）。 */
    public static final double RETURN_DURATION_SEC = 0.70;
    /** 单次抬眼总包络时长（1.55s）。 */
    public static final double TOTAL_GLANCE_DURATION_SEC =
            RAISE_DURATION_SEC + HOLD_DURATION_SEC + RETURN_DURATION_SEC;

    /** 玩家离开视野多久后重新允许下一次抬眼（秒）。 */
    public static final double REARM_ABSENCE_SEC = 4.0;
    /** 刚坐下拿起书本后的初始阅读稳定期（秒）。 */
    public static final double INITIAL_SETTLE_SEC = 0.45;
    /** 视线遮挡轮询间隔（秒），避免每帧射线开销。 */
    private static final double SENSOR_POLL_INTERVAL_SEC = 0.12;

    /** 抬眼峰值垂直分量（驱动 LookAround 混合 Bob_LookUp，从低头读书抬起下巴）。 */
    public static final float PEAK_LOOK_VERTICAL = 0.42F;
    /** 抬眼峰值对阅读轨道头骨（Bip01_Head）权重的释放比例。 */
    public static final float HEAD_RELEASE_PEAK = 0.92F;
    /** 抬眼峰值对阅读轨道颈骨（Bip01_Neck）权重的释放比例。 */
    public static final float NECK_RELEASE_PEAK = 0.55F;

    private boolean activeGlance;
    private double glanceElapsedSec;
    private float targetHorizontal;
    private boolean latchedForPresence;
    private double absenceAccumSec;
    private double readingReadyAccumSec;
    private double sensorPollAccumSec;

    public void reset(IsoNPC npc) {
        this.activeGlance = false;
        this.glanceElapsedSec = 0.0;
        this.targetHorizontal = 0.0F;
        this.latchedForPresence = false;
        this.absenceAccumSec = 0.0;
        this.readingReadyAccumSec = 0.0;
        this.sensorPollAccumSec = 0.0;
        if (npc != null) {
            npc.clearSeatedReadingGlance();
        }
    }

    public boolean isGlancing() {
        return activeGlance;
    }

    public boolean isLatchedForPresence() {
        return latchedForPresence;
    }

    public void tick(IsoNPC npc, double dtSim) {
        if (npc == null || npc.isDead()
                || npc.getPosture().getState() != PostureState.SEATED
                || !npc.isSittingOnFurniture()) {
            if (activeGlance || (npc != null && npc.isSeatedReadingGlanceActive())) {
                reset(npc);
            }
            return;
        }

        double step = Math.max(0.0, Math.min(0.25, dtSim));
        readingReadyAccumSec += step;

        // 1. 若当前正在执行“抬眼 -> 停半拍 -> 收回”包络，推进时间轴并更新骨骼混合
        if (activeGlance) {
            glanceElapsedSec += step;
            if (glanceElapsedSec <= RAISE_DURATION_SEC + HOLD_DURATION_SEC) {
                refreshTargetHorizontalIfPlayerVisible(npc);
            }
            if (glanceElapsedSec >= TOTAL_GLANCE_DURATION_SEC) {
                activeGlance = false;
                glanceElapsedSec = 0.0;
                npc.clearSeatedReadingGlance();
            } else {
                float env = computeGlanceEnvelope(glanceElapsedSec);
                float curH = targetHorizontal * env;
                float curV = PEAK_LOOK_VERTICAL * env;
                npc.setSeatedReadingGlance(curH, curV, env);
            }
        }

        if (readingReadyAccumSec < INITIAL_SETTLE_SEC) {
            return;
        }

        // 2. 低频感知本地玩家是否进入座椅前方可视区域
        sensorPollAccumSec += step;
        if (sensorPollAccumSec < SENSOR_POLL_INTERVAL_SEC) {
            if (latchedForPresence && !activeGlance) {
                if (absenceAccumSec > 0.0) {
                    absenceAccumSec += step;
                    if (absenceAccumSec >= REARM_ABSENCE_SEC) {
                        latchedForPresence = false;
                        absenceAccumSec = 0.0;
                    }
                }
            }
            return;
        }
        double pollDt = sensorPollAccumSec;
        sensorPollAccumSec = 0.0;

        IsoPlayer player = resolveLocalVisiblePlayer(npc);
        float[] seatFwd = resolveSeatForwardUnit(npc);
        if (player == null) {
            handlePlayerNotSeen(pollDt);
            return;
        }

        int npcLevel = EmberTargetProjector.levelOf(npc.getZ());
        int playerLevel = EmberTargetProjector.levelOf(player.getZ());
        float maxDist = latchedForPresence ? PRESENCE_HYSTERESIS_DIST : TRIGGER_MAX_DIST;
        float minDot = latchedForPresence ? PRESENCE_HYSTERESIS_DOT : TRIGGER_MIN_DOT;

        float angleRad = computeSignedAngleIfInFrontCone(
                npc.getX(), npc.getY(), npcLevel,
                seatFwd[0], seatFwd[1],
                player.getX(), player.getY(), playerLevel,
                TRIGGER_MIN_DIST, maxDist, minDot);

        if (Float.isNaN(angleRad) || !hasClearLineOfSight(npc, player)) {
            handlePlayerNotSeen(pollDt);
            return;
        }

        // 玩家当前处于同层、近距离、前方可视扇区内且无遮挡
        absenceAccumSec = 0.0;
        if (!latchedForPresence && !activeGlance) {
            latchedForPresence = true;
            activeGlance = true;
            glanceElapsedSec = 0.0;
            targetHorizontal = computeNormalizedHorizontal(angleRad, npc.getHeadLookAngleMax());
            float env = computeGlanceEnvelope(0.0);
            npc.setSeatedReadingGlance(targetHorizontal * env, PEAK_LOOK_VERTICAL * env, env);
        }
    }

    private void handlePlayerNotSeen(double dt) {
        if (!latchedForPresence) {
            absenceAccumSec = 0.0;
            return;
        }
        absenceAccumSec += dt;
        if (absenceAccumSec >= REARM_ABSENCE_SEC) {
            latchedForPresence = false;
            absenceAccumSec = 0.0;
        }
    }

    private void refreshTargetHorizontalIfPlayerVisible(IsoNPC npc) {
        IsoPlayer player = resolveLocalVisiblePlayer(npc);
        if (player == null) {
            return;
        }
        float[] seatFwd = resolveSeatForwardUnit(npc);
        float angleRad = computeSignedAngleIfInFrontCone(
                npc.getX(), npc.getY(), EmberTargetProjector.levelOf(npc.getZ()),
                seatFwd[0], seatFwd[1],
                player.getX(), player.getY(), EmberTargetProjector.levelOf(player.getZ()),
                TRIGGER_MIN_DIST, PRESENCE_HYSTERESIS_DIST, PRESENCE_HYSTERESIS_DOT);
        if (!Float.isNaN(angleRad)) {
            float desiredH = computeNormalizedHorizontal(angleRad, npc.getHeadLookAngleMax());
            targetHorizontal = targetHorizontal + (desiredH - targetHorizontal) * 0.25F;
        }
    }

    public static float computeGlanceEnvelope(double elapsedSec) {
        return EmberPostureMath.computeGlanceEnvelope(elapsedSec);
    }

    public static float computeSignedAngleIfInFrontCone(
            float npcX, float npcY, int npcLevel,
            float forwardX, float forwardY,
            float playerX, float playerY, int playerLevel,
            float minDist, float maxDist, float minDot) {
        return EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                npcX, npcY, npcLevel, forwardX, forwardY, playerX, playerY, playerLevel, minDist, maxDist, minDot);
    }

    public static float computeNormalizedHorizontal(float signedAngleRad, float maxAngleRad) {
        return EmberPostureMath.computeGlanceNormalizedHorizontal(signedAngleRad, maxAngleRad);
    }

    private static float[] resolveSeatForwardUnit(IsoNPC npc) {
        IsoDirections dir = npc.getSitOnFurnitureDirection();
        if (dir == null) {
            dir = npc.getDir();
        }
        if (dir != null && (dir.dx() != 0 || dir.dy() != 0)) {
            float dx = dir.dx();
            float dy = dir.dy();
            float len = (float) Math.hypot(dx, dy);
            return new float[]{dx / len, dy / len};
        }
        float fx = npc.getForwardDirectionX();
        float fy = npc.getForwardDirectionY();
        float len = (float) Math.hypot(fx, fy);
        if (len > 1.0E-5F) {
            return new float[]{fx / len, fy / len};
        }
        return new float[]{0.0F, 1.0F};
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

    private static boolean hasClearLineOfSight(IsoNPC npc, IsoPlayer player) {
        IsoCell cell = npc.getCell();
        if (cell == null) {
            return false;
        }
        if (!npc.CanSee(player)) {
            return false;
        }
        LosUtil.TestResults res = LosUtil.lineClear(
                cell,
                (int) Math.floor(npc.getX()),
                (int) Math.floor(npc.getY()),
                (int) Math.floor(npc.getZ()),
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()),
                false);
        return res != LosUtil.TestResults.Blocked
                && res != LosUtil.TestResults.ClearThroughClosedDoor;
    }

    /**
     * 在 {@code postupdate()} 动画轨道混合前调用：
     * 动态将 {@code Bob_IdleReadBook} 轨道上的 {@code Bip01_Head / Bip01_Neck} 骨骼权重按注视包络衰减，
     * 使下层 {@code headlook/LookAround} 2D 混合树顺利驱动头部抬起与侧转，同时 100% 保持脊柱、双臂、手部书本与翻页事件。
     */
    public static void applyReadingHeadMaskRelease(IsoNPC npc, float glanceEnvelope01) {
        if (npc == null) {
            return;
        }
        AnimationPlayer animPlayer = npc.getAnimationPlayer();
        if (animPlayer == null || !animPlayer.isReady() || animPlayer.getSkinningData() == null) {
            return;
        }
        AnimationMultiTrack multiTrack = animPlayer.getMultiTrack();
        if (multiTrack == null) {
            return;
        }
        List<AnimationTrack> tracks = multiTrack.getTracks();
        if (tracks == null || tracks.isEmpty()) {
            return;
        }

        Integer headIdxObj = animPlayer.getSkinningData().boneIndices.get("Bip01_Head");
        Integer neckIdxObj = animPlayer.getSkinningData().boneIndices.get("Bip01_Neck");
        int headIdx = headIdxObj != null ? headIdxObj : -1;
        int neckIdx = neckIdxObj != null ? neckIdxObj : -1;
        if (headIdx < 0 && neckIdx < 0) {
            return;
        }

        float clampedEnv = Math.max(0.0F, Math.min(1.0F, glanceEnvelope01));
        float headWeight = 1.0F - HEAD_RELEASE_PEAK * clampedEnv;
        float neckWeight = 1.0F - NECK_RELEASE_PEAK * clampedEnv;

        for (int i = 0, n = tracks.size(); i < n; i++) {
            AnimationTrack track = tracks.get(i);
            if (track == null || !track.isPlaying || !track.hasBoneMask()) {
                continue;
            }
            String clipName = track.getName();
            if (clipName == null || !clipName.contains("Read")) {
                continue;
            }
            if (headIdx >= 0) {
                setTrackBoneWeightSafe(track, headIdx, headWeight);
            }
            if (neckIdx >= 0) {
                setTrackBoneWeightSafe(track, neckIdx, neckWeight);
            }
        }
    }

    private static java.lang.reflect.Field boneWeightsField;
    private static boolean boneWeightsFieldResolved;

    private static void setTrackBoneWeightSafe(AnimationTrack track, int boneIdx, float weight) {
        try {
            if (!boneWeightsFieldResolved) {
                java.lang.reflect.Field f = AnimationTrack.class.getDeclaredField("boneWeights");
                f.setAccessible(true);
                boneWeightsField = f;
                boneWeightsFieldResolved = true;
            }
            if (boneWeightsField != null) {
                Object pooled = boneWeightsField.get(track);
                if (pooled instanceof zombie.util.PooledFloatArrayObject floatArr) {
                    if (boneIdx >= 0 && boneIdx < floatArr.length()) {
                        floatArr.set(boneIdx, weight);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
