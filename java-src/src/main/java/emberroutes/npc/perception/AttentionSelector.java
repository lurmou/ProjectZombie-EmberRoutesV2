package emberroutes.npc.perception;

import emberroutes.npc.EmberPostureMath;
import emberroutes.npc.perception.StimulusSlot.StimulusKind;

/**
 * 纯数学环境注意力选择器与刺激记忆/习惯化内核（零 {@code zombie.*} 依赖，支持脱机 JUnit 严密测试）：
 * <ul>
 *   <li><b>刺激记忆与习惯化</b>：将“在场单次锁存 + 持续离开 {@code rearmAbsenceSec} 重新武装”泛化为
 *       任意 {@link StimulusSlot} 的通用记忆更新，避免每个微反应重复抄写去抖状态机；</li>
 *   <li><b>注意力挑选</b>：按新颖度要求（{@code requireNovelty}）输出 {@code NEW_ARRIVAL} 或持续 {@code TRACK}；</li>
 *   <li><b>三段平滑包络</b>：按档案声明的 {@code raise / hold / return} 时长计算 {@code [0, 1]} 权重。</li>
 * </ul>
 */
public final class AttentionSelector {
    public enum AttentionReason {
        NONE,
        NEW_ARRIVAL,
        TRACK
    }

    /** 复用型输出载体（0-GC）。 */
    public static final class AttentionTarget {
        public StimulusKind kind;
        public AttentionReason reason = AttentionReason.NONE;
        public float signedAngleRad = Float.NaN;
        public float distSq = Float.POSITIVE_INFINITY;
        public float targetX;
        public float targetY;
        public int targetLevel;

        public void clear() {
            this.kind = null;
            this.reason = AttentionReason.NONE;
            this.signedAngleRad = Float.NaN;
            this.distSq = Float.POSITIVE_INFINITY;
            this.targetX = 0.0F;
            this.targetY = 0.0F;
            this.targetLevel = 0;
        }

        public boolean hasTarget() {
            return reason != AttentionReason.NONE && kind != null && !Float.isNaN(signedAngleRad);
        }
    }

    private AttentionSelector() {}

    /**
     * 更新单个刺激槽的可见状态、记忆时间戳与习惯化离场计时。
     */
    public static void updateStimulusMemory(
            StimulusSlot slot,
            boolean currentlyVisible,
            float targetX,
            float targetY,
            float targetZ,
            int targetLevel,
            float distSq,
            float signedAngleRad,
            double simTime,
            double dtSim,
            double rearmAbsenceSec) {
        if (slot == null) {
            return;
        }
        double safeDt = Math.max(0.0, dtSim);
        if (currentlyVisible && !Float.isNaN(signedAngleRad)) {
            slot.visible = true;
            slot.x = targetX;
            slot.y = targetY;
            slot.z = targetZ;
            slot.level = targetLevel;
            slot.distSq = distSq;
            slot.signedAngleRad = signedAngleRad;
            if (Double.isNaN(slot.firstSeenSimTime)) {
                slot.firstSeenSimTime = simTime;
            }
            slot.lastSeenSimTime = simTime;
            slot.continuousAbsenceSec = 0.0;
        } else {
            slot.visible = false;
            slot.signedAngleRad = Float.NaN;
            advanceAbsence(slot, safeDt, rearmAbsenceSec);
        }
    }

    /**
     * 在非采样帧推进离场计时，确保习惯化重武装时间精确。
     */
    public static void advanceAbsence(StimulusSlot slot, double dtSim, double rearmAbsenceSec) {
        if (slot == null || slot.visible) {
            return;
        }
        if (!slot.habituated) {
            slot.continuousAbsenceSec = 0.0;
            return;
        }
        slot.continuousAbsenceSec += Math.max(0.0, dtSim);
        if (slot.continuousAbsenceSec >= rearmAbsenceSec) {
            slot.habituated = false;
            slot.continuousAbsenceSec = 0.0;
            slot.firstSeenSimTime = Double.NaN;
        }
    }

    /**
     * 从感知刺激槽挑选当前环境注意力目标（0-GC 写入 {@code out}）。
     *
     * @param playerSlot 玩家刺激记忆槽
     * @param requireNovelty {@code true} 时仅在未习惯化的新进入事件触发 {@link AttentionReason#NEW_ARRIVAL}；
     *                       {@code false} 时只要可见即持续输出 {@link AttentionReason#TRACK}
     * @param out 复用输出对象
     */
    public static void selectAmbientTarget(
            StimulusSlot playerSlot, boolean requireNovelty, AttentionTarget out) {
        if (out == null) {
            return;
        }
        out.clear();
        if (playerSlot == null || !playerSlot.visible || Float.isNaN(playerSlot.signedAngleRad)) {
            return;
        }
        if (requireNovelty) {
            if (playerSlot.habituated) {
                return;
            }
            out.kind = playerSlot.kind;
            out.reason = AttentionReason.NEW_ARRIVAL;
        } else {
            out.kind = playerSlot.kind;
            out.reason = AttentionReason.TRACK;
        }
        out.signedAngleRad = playerSlot.signedAngleRad;
        out.distSq = playerSlot.distSq;
        out.targetX = playerSlot.x;
        out.targetY = playerSlot.y;
        out.targetLevel = playerSlot.level;
    }

    /**
     * 通用三段式注视包络（{@code raise -> hold -> return}），输出 {@code [0.0, 1.0]}。
     */
    public static float computeThreePhaseEnvelope(
            double elapsedSec, double raiseSec, double holdSec, double returnSec) {
        double total = raiseSec + holdSec + returnSec;
        if (total <= 0.0 || elapsedSec <= 0.0 || elapsedSec >= total) {
            return 0.0F;
        }
        if (raiseSec > 0.0 && elapsedSec < raiseSec) {
            return EmberPostureMath.smoothstep01((float) (elapsedSec / raiseSec));
        }
        if (elapsedSec <= raiseSec + holdSec) {
            return 1.0F;
        }
        if (returnSec <= 0.0) {
            return 0.0F;
        }
        float u = (float) ((elapsedSec - raiseSec - holdSec) / returnSec);
        return 1.0F - EmberPostureMath.smoothstep01(u);
    }

    /**
     * 帧率无关指数平滑系数（用于 {@code SOFT_TRACK} 柔和转头与离场回正）。
     */
    public static float computeSmoothAlpha(double dtSec, float speedPerSec) {
        double dt = Math.max(0.0, Math.min(0.25, dtSec));
        if (dt <= 0.0 || speedPerSec <= 0.0F) {
            return 0.0F;
        }
        return (float) (1.0 - Math.exp(-speedPerSec * dt));
    }
}
