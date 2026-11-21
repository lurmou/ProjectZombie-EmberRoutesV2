package emberroutes.npc;

import emberroutes.npc.NpcPostureController.PostureState;
import emberroutes.npc.PostureGazeProfile.GazeChannel;
import emberroutes.npc.PostureGazeProfile.GazeStyle;
import emberroutes.npc.perception.AttentionSelector;
import emberroutes.npc.perception.AttentionSelector.AttentionTarget;
import emberroutes.npc.perception.NpcPerception;
import emberroutes.npc.perception.PerceptionSignificance;
import emberroutes.npc.perception.StimulusSlot;
import java.lang.reflect.Field;
import java.util.List;
import zombie.GameTime;
import zombie.core.skinnedmodel.animation.AnimationMultiTrack;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.util.PooledFloatArrayObject;

/**
 * {@link IsoNPC} 统一注视身体控制器（与 {@link EmberLocomotionController}、{@link NpcPostureController} 平级）：
 * <ul>
 *   <li><b>唯一头部通道写者</b>：压制 B42 原版 {@code IsoPlayer.updateInternal2} 对 {@code lookHorizontal / lookVertical}
 *       的每帧归零与真玩家右键瞄准窜扰，确保所有注视输出经唯一出口写往 {@code LookAround} 2D 混合树；</li>
 *   <li><b>姿态自持生命周期</b>：根据角色当前姿态自动解析 {@link PostureGazeProfile}，姿态切换时自动淡出并归还骨骼遮罩，
 *       业务状态节点（如 {@code SeatLeisureNode}）零注视代码与零手动清理；</li>
 *   <li><b>多请求仲裁 + 压制令牌</b>：支持行为树通过 {@link #requestTrack} / {@link #suppress} 挂载带
 *       {@code StateScope.defer} 自动回收的高优意图，未请求时由 {@link NpcPerception} 驱动常驻环境注意力。</li>
 * </ul>
 */
public final class EmberGazeController {
    public static final int PRIORITY_AMBIENT = 0;
    public static final int PRIORITY_BEHAVIOR = 10;
    public static final int PRIORITY_DIALOGUE = 20;

    private static final int MAX_REQUESTS = 4;
    private static final int MAX_SUPPRESSORS = 4;
    private static final float SOFT_TRACK_LERP_SPEED = 6.0F;
    private static final float BRIEF_TARGET_SMOOTH = 0.25F;

    private static final class GazeRequestSlot {
        Object token;
        float targetX;
        float targetY;
        int targetLevel;
        int priority;

        void clear() {
            this.token = null;
            this.targetX = 0.0F;
            this.targetY = 0.0F;
            this.targetLevel = 0;
            this.priority = Integer.MIN_VALUE;
        }
    }

    private final GazeRequestSlot[] requests = new GazeRequestSlot[MAX_REQUESTS];
    private final Object[] suppressors = new Object[MAX_SUPPRESSORS];
    private final AttentionTarget ambientTarget = new AttentionTarget();

    private PostureGazeProfile activeProfile = PostureGazeProfile.NONE;
    private double profileDwellSec;

    private boolean briefGlanceActive;
    private double briefGlanceElapsedSec;
    private float briefTargetHorizontal;

    private float appliedHorizontal;
    private float appliedVertical;
    private float appliedEnvelope;
    private boolean maskDirty;
    private String pendingRestoreTrackKeyword;

    private static Field boneWeightsField;
    private static boolean boneWeightsFieldResolved;

    public EmberGazeController() {
        for (int i = 0; i < MAX_REQUESTS; i++) {
            requests[i] = new GazeRequestSlot();
            requests[i].clear();
        }
    }

    public PostureGazeProfile getActiveProfile() {
        return activeProfile;
    }

    public boolean isGlancing() {
        return briefGlanceActive;
    }

    public float getAppliedHorizontal() {
        return appliedHorizontal;
    }

    public float getAppliedVertical() {
        return appliedVertical;
    }

    public float getAppliedEnvelope() {
        return appliedEnvelope;
    }

    /**
     * 向注视控制器注册高优目标追踪请求，返回可传入 {@code StateScope.defer(...)} 的自动释放句柄。
     */
    public Runnable requestTrack(Object token, float targetX, float targetY, int targetLevel, int priority) {
        if (token == null) {
            return () -> {};
        }
        int freeIdx = -1;
        int lowestIdx = 0;
        int lowestPrio = Integer.MAX_VALUE;
        for (int i = 0; i < MAX_REQUESTS; i++) {
            GazeRequestSlot slot = requests[i];
            if (slot.token == token) {
                slot.targetX = targetX;
                slot.targetY = targetY;
                slot.targetLevel = targetLevel;
                slot.priority = priority;
                return () -> releaseRequest(token);
            }
            if (slot.token == null && freeIdx < 0) {
                freeIdx = i;
            } else if (slot.token != null && slot.priority < lowestPrio) {
                lowestPrio = slot.priority;
                lowestIdx = i;
            }
        }
        int targetIdx = freeIdx >= 0 ? freeIdx : (priority >= lowestPrio ? lowestIdx : -1);
        if (targetIdx >= 0) {
            GazeRequestSlot slot = requests[targetIdx];
            slot.token = token;
            slot.targetX = targetX;
            slot.targetY = targetY;
            slot.targetLevel = targetLevel;
            slot.priority = priority;
        }
        return () -> releaseRequest(token);
    }

    public void releaseRequest(Object token) {
        if (token == null) {
            return;
        }
        for (int i = 0; i < MAX_REQUESTS; i++) {
            if (requests[i].token == token) {
                requests[i].clear();
            }
        }
    }

    /**
     * 压制所有环境与行为注视（例如进食/瞄准时），返回可传入 {@code StateScope.defer(...)} 的自动释放句柄。
     */
    public Runnable suppress(Object token) {
        if (token == null) {
            return () -> {};
        }
        int freeIdx = -1;
        for (int i = 0; i < MAX_SUPPRESSORS; i++) {
            if (suppressors[i] == token) {
                return () -> releaseSuppress(token);
            }
            if (suppressors[i] == null && freeIdx < 0) {
                freeIdx = i;
            }
        }
        if (freeIdx >= 0) {
            suppressors[freeIdx] = token;
        }
        return () -> releaseSuppress(token);
    }

    public void releaseSuppress(Object token) {
        if (token == null) {
            return;
        }
        for (int i = 0; i < MAX_SUPPRESSORS; i++) {
            if (suppressors[i] == token) {
                suppressors[i] = null;
            }
        }
    }

    public boolean isSuppressed() {
        for (int i = 0; i < MAX_SUPPRESSORS; i++) {
            if (suppressors[i] != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 异常打断 / 死亡 / 离屏卸载 / 强制起身时的唯一清理入口。
     */
    public void forceReset(IsoNPC npc) {
        for (int i = 0; i < MAX_REQUESTS; i++) {
            requests[i].clear();
        }
        for (int i = 0; i < MAX_SUPPRESSORS; i++) {
            suppressors[i] = null;
        }
        String restoreKeyword = activeProfile.hasBoneMaskRelease()
                ? activeProfile.releaseTrackKeyword
                : pendingRestoreTrackKeyword;
        boolean needMaskRestore = maskDirty || appliedEnvelope > 0.0F || activeProfile.hasBoneMaskRelease();

        this.activeProfile = PostureGazeProfile.NONE;
        this.profileDwellSec = 0.0;
        this.briefGlanceActive = false;
        this.briefGlanceElapsedSec = 0.0;
        this.briefTargetHorizontal = 0.0F;
        this.appliedHorizontal = 0.0F;
        this.appliedVertical = 0.0F;
        this.appliedEnvelope = 0.0F;
        this.pendingRestoreTrackKeyword = restoreKeyword;
        this.maskDirty = needMaskRestore;

        if (npc != null) {
            npc.applyGazeHeadLookDirect(0.0F, 0.0F);
            if (needMaskRestore) {
                applyBoneMaskWeights(npc, restoreKeyword, 0.0F, 0.0F, 0.0F);
                this.maskDirty = false;
                this.pendingRestoreTrackKeyword = null;
            }
        }
    }

    /**
     * 在 {@link IsoNPC#update()} 中紧跟 {@link NpcPostureController#beforeUpdate(IsoNPC)} 调用。
     */
    public void beforeUpdate(IsoNPC npc) {
        if (npc == null || npc.isDead()) {
            if (activeProfile != PostureGazeProfile.NONE || maskDirty || briefGlanceActive) {
                forceReset(npc);
            }
            return;
        }

        PostureGazeProfile nextProfile = resolveProfile(npc);
        if (nextProfile != activeProfile) {
            onProfileChanged(npc, activeProfile, nextProfile);
            activeProfile = nextProfile;
        }

        if (activeProfile.channel != GazeChannel.HEAD_LAYER) {
            return;
        }

        GameTime gt = GameTime.getInstance();
        double dt = (gt != null && gt.getTimeDelta() > 0.0F)
                ? Math.max(0.0, Math.min(0.25, gt.getTimeDelta()))
                : (1.0 / 60.0);
        profileDwellSec += dt;

        NpcPerception perception = npc.getPerception();
        if (isSuppressed() || perception.getSignificance() == PerceptionSignificance.HIDDEN) {
            resetOutputToCenter(npc, dt);
            return;
        }

        if (activeProfile.style == GazeStyle.BRIEF_RAISE) {
            tickBriefRaise(npc, perception, dt);
        } else if (activeProfile.style == GazeStyle.SOFT_TRACK) {
            tickSoftTrack(npc, perception, dt);
        }

        npc.applyGazeHeadLookDirect(appliedHorizontal, appliedVertical);
    }

    /**
     * 拦截引擎 {@code IsoPlayer.updateInternal2} 对 {@code setHeadLookAroundDirection} 的写入，
     * 保持 {@code EmberGazeController} 为唯一写者（同时杜绝真玩家右键瞄准窜扰坐姿 NPC 头部）。
     */
    public void interceptEngineHeadLookWrite(IsoNPC npc, float engineHorizontal, float engineVertical) {
        if (npc == null) {
            return;
        }
        if (npc.isHeadLookAround()
                || activeProfile.channel == GazeChannel.HEAD_LAYER
                || appliedEnvelope > 0.0F
                || Math.abs(appliedHorizontal) > 1.0E-4F
                || Math.abs(appliedVertical) > 1.0E-4F) {
            npc.applyGazeHeadLookDirect(appliedHorizontal, appliedVertical);
            return;
        }
        npc.applyGazeHeadLookDirect(engineHorizontal, engineVertical);
    }

    /**
     * 在 {@link IsoNPC#getTurnDelta()}（动画轨道混合前）按当前档案执行头/颈骨骼遮罩权重释放或恢复。
     */
    public void applyBoneMaskIfNeeded(IsoNPC npc) {
        if (npc == null || (!maskDirty && appliedEnvelope <= 0.0F)) {
            return;
        }
        if (activeProfile.hasBoneMaskRelease() && appliedEnvelope > 0.0F) {
            applyBoneMaskWeights(
                    npc,
                    activeProfile.releaseTrackKeyword,
                    appliedEnvelope,
                    activeProfile.headReleasePeak,
                    activeProfile.neckReleasePeak);
            maskDirty = true;
            pendingRestoreTrackKeyword = activeProfile.releaseTrackKeyword;
        } else {
            String keyword = pendingRestoreTrackKeyword != null
                    ? pendingRestoreTrackKeyword
                    : activeProfile.releaseTrackKeyword;
            applyBoneMaskWeights(npc, keyword, 0.0F, 0.0F, 0.0F);
            maskDirty = false;
            pendingRestoreTrackKeyword = null;
        }
    }

    private void tickBriefRaise(IsoNPC npc, NpcPerception perception, double dt) {
        if (briefGlanceActive) {
            briefGlanceElapsedSec += dt;
            if (briefGlanceElapsedSec <= activeProfile.raiseSec + activeProfile.holdSec) {
                float liveAngle = perception.sampleLivePlayerAngleRad(npc);
                if (!Float.isNaN(liveAngle)) {
                    float desiredH = EmberPostureMath.computeGlanceNormalizedHorizontal(
                            liveAngle, npc.getHeadLookAngleMax());
                    briefTargetHorizontal += (desiredH - briefTargetHorizontal) * BRIEF_TARGET_SMOOTH;
                }
            }
            if (briefGlanceElapsedSec >= activeProfile.totalBriefDurationSec()) {
                briefGlanceActive = false;
                briefGlanceElapsedSec = 0.0;
                appliedHorizontal = 0.0F;
                appliedVertical = 0.0F;
                appliedEnvelope = 0.0F;
                if (activeProfile.hasBoneMaskRelease()) {
                    maskDirty = true;
                    pendingRestoreTrackKeyword = activeProfile.releaseTrackKeyword;
                }
            } else {
                float env = AttentionSelector.computeThreePhaseEnvelope(
                        briefGlanceElapsedSec,
                        activeProfile.raiseSec,
                        activeProfile.holdSec,
                        activeProfile.returnSec);
                appliedEnvelope = env;
                appliedHorizontal = briefTargetHorizontal * env;
                appliedVertical = activeProfile.verticalBias * env;
                if (activeProfile.hasBoneMaskRelease()) {
                    maskDirty = true;
                    pendingRestoreTrackKeyword = activeProfile.releaseTrackKeyword;
                }
            }
            return;
        }

        if (profileDwellSec < activeProfile.settleSec) {
            return;
        }

        StimulusSlot playerSlot = perception.getPlayerSlot();
        AttentionSelector.selectAmbientTarget(playerSlot, true, ambientTarget);
        if (ambientTarget.hasTarget()) {
            playerSlot.habituated = true;
            briefGlanceActive = true;
            briefGlanceElapsedSec = 0.0;
            briefTargetHorizontal = EmberPostureMath.computeGlanceNormalizedHorizontal(
                    ambientTarget.signedAngleRad, npc.getHeadLookAngleMax());
            float env = AttentionSelector.computeThreePhaseEnvelope(
                    0.0, activeProfile.raiseSec, activeProfile.holdSec, activeProfile.returnSec);
            appliedEnvelope = env;
            appliedHorizontal = briefTargetHorizontal * env;
            appliedVertical = activeProfile.verticalBias * env;
            if (activeProfile.hasBoneMaskRelease()) {
                maskDirty = true;
                pendingRestoreTrackKeyword = activeProfile.releaseTrackKeyword;
            }
        }
    }

    private void tickSoftTrack(IsoNPC npc, NpcPerception perception, double dt) {
        float desiredH = 0.0F;
        if (profileDwellSec >= activeProfile.settleSec) {
            GazeRequestSlot topReq = selectHighestPriorityRequest();
            if (topReq != null) {
                float reqAngle = perception.sampleWorldPointAngleRad(
                        npc, topReq.targetX, topReq.targetY, topReq.targetLevel);
                if (!Float.isNaN(reqAngle)) {
                    desiredH = EmberPostureMath.computeGlanceNormalizedHorizontal(
                            reqAngle, npc.getHeadLookAngleMax());
                }
            } else {
                AttentionSelector.selectAmbientTarget(perception.getPlayerSlot(), false, ambientTarget);
                if (ambientTarget.hasTarget()) {
                    float liveAngle = perception.sampleLivePlayerAngleRad(npc);
                    float useAngle = !Float.isNaN(liveAngle) ? liveAngle : ambientTarget.signedAngleRad;
                    desiredH = EmberPostureMath.computeGlanceNormalizedHorizontal(
                            useAngle, npc.getHeadLookAngleMax());
                }
            }
        }
        float alpha = AttentionSelector.computeSmoothAlpha(dt, SOFT_TRACK_LERP_SPEED);
        appliedHorizontal += (desiredH - appliedHorizontal) * alpha;
        if (Math.abs(appliedHorizontal) < 1.0E-3F && desiredH == 0.0F) {
            appliedHorizontal = 0.0F;
        }
        appliedVertical = activeProfile.verticalBias;
        appliedEnvelope = 0.0F;
    }

    private void resetOutputToCenter(IsoNPC npc, double dt) {
        if (briefGlanceActive) {
            briefGlanceActive = false;
            briefGlanceElapsedSec = 0.0;
            appliedEnvelope = 0.0F;
            if (activeProfile.hasBoneMaskRelease()) {
                maskDirty = true;
                pendingRestoreTrackKeyword = activeProfile.releaseTrackKeyword;
            }
        }
        float alpha = AttentionSelector.computeSmoothAlpha(dt, SOFT_TRACK_LERP_SPEED);
        appliedHorizontal += (0.0F - appliedHorizontal) * alpha;
        appliedVertical += (0.0F - appliedVertical) * alpha;
        if (Math.abs(appliedHorizontal) < 1.0E-3F) {
            appliedHorizontal = 0.0F;
        }
        if (Math.abs(appliedVertical) < 1.0E-3F) {
            appliedVertical = 0.0F;
        }
        npc.applyGazeHeadLookDirect(appliedHorizontal, appliedVertical);
    }

    private void onProfileChanged(IsoNPC npc, PostureGazeProfile prev, PostureGazeProfile next) {
        this.profileDwellSec = 0.0;
        this.briefGlanceActive = false;
        this.briefGlanceElapsedSec = 0.0;
        this.briefTargetHorizontal = 0.0F;
        this.appliedEnvelope = 0.0F;

        if (prev.hasBoneMaskRelease()) {
            this.maskDirty = true;
            this.pendingRestoreTrackKeyword = prev.releaseTrackKeyword;
            applyBoneMaskWeights(npc, prev.releaseTrackKeyword, 0.0F, 0.0F, 0.0F);
            this.maskDirty = false;
            this.pendingRestoreTrackKeyword = null;
        }
        if (next.channel != GazeChannel.HEAD_LAYER) {
            this.appliedHorizontal = 0.0F;
            this.appliedVertical = 0.0F;
            npc.applyGazeHeadLookDirect(0.0F, 0.0F);
        }
    }

    private GazeRequestSlot selectHighestPriorityRequest() {
        GazeRequestSlot best = null;
        for (int i = 0; i < MAX_REQUESTS; i++) {
            GazeRequestSlot slot = requests[i];
            if (slot.token != null && slot.priority > PRIORITY_AMBIENT) {
                if (best == null || slot.priority > best.priority) {
                    best = slot;
                }
            }
        }
        return best;
    }

    private static PostureGazeProfile resolveProfile(IsoNPC npc) {
        if (npc.getPosture().getState() != PostureState.SEATED || !npc.isSittingOnFurniture()) {
            return PostureGazeProfile.NONE;
        }
        String action = npc.getVariableString("PerformingAction");
        if (action != null && "read".equalsIgnoreCase(action)) {
            return PostureGazeProfile.SEATED_READING;
        }
        return PostureGazeProfile.SEATED_IDLE;
    }

    private static void applyBoneMaskWeights(
            IsoNPC npc, String trackKeyword, float envelope01, float headReleasePeak, float neckReleasePeak) {
        if (npc == null || trackKeyword == null || trackKeyword.isEmpty()) {
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

        float clampedEnv = Math.max(0.0F, Math.min(1.0F, envelope01));
        float headWeight = 1.0F - headReleasePeak * clampedEnv;
        float neckWeight = 1.0F - neckReleasePeak * clampedEnv;

        for (int i = 0, n = tracks.size(); i < n; i++) {
            AnimationTrack track = tracks.get(i);
            if (track == null || !track.isPlaying || !track.hasBoneMask()) {
                continue;
            }
            String clipName = track.getName();
            if (clipName == null || !clipName.contains(trackKeyword)) {
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

    private static void setTrackBoneWeightSafe(AnimationTrack track, int boneIdx, float weight) {
        try {
            if (!boneWeightsFieldResolved) {
                Field f = AnimationTrack.class.getDeclaredField("boneWeights");
                f.setAccessible(true);
                boneWeightsField = f;
                boneWeightsFieldResolved = true;
            }
            if (boneWeightsField != null) {
                Object pooled = boneWeightsField.get(track);
                if (pooled instanceof PooledFloatArrayObject floatArr) {
                    if (boneIdx >= 0 && boneIdx < floatArr.length()) {
                        floatArr.set(boneIdx, weight);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
