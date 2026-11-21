package emberroutes.npc;

/**
 * 数据驱动的姿态注视能力档案表（零 {@code zombie.*} 依赖）：
 * <p>
 * B42 引擎中只有 {@code sitonfurniture} 动作组挂载了原版 {@code headlook/LookAround} 2D 头部混合层，
 * 站立/行走/卧床均无头部转向层。本枚举以单一权威声明每种姿态“能否注视、走哪条通道、采用何种包络
 * 以及需要释放哪条基础轨道的头/颈骨骼权重”，彻底消除业务节点里的硬编码分支。
 * </p>
 */
public enum PostureGazeProfile {
    /** 当前姿态不支持注视（站立/行走/卧床/执行动作中）。 */
    NONE(
            GazeChannel.NONE,
            GazeStyle.NONE,
            0.0F,
            0.0F,
            0.0F,
            0.0,
            0.0,
            0.0,
            0.0,
            null),

    /**
     * 座椅休闲阅读：三段式抬眼包络（抬眼 0.60s → 停半拍 0.25s → 收回 0.70s），
     * 峰值垂直抬升 {@code 0.42}，并释放 {@code Read} 轨道的 {@code Bip01_Head / Bip01_Neck} 遮罩权重，
     * 100% 保留双手持书与翻页进度。
     */
    SEATED_READING(
            GazeChannel.HEAD_LAYER,
            GazeStyle.BRIEF_RAISE,
            0.42F,
            0.92F,
            0.55F,
            EmberPostureMath.GLANCE_RAISE_DURATION_SEC,
            EmberPostureMath.GLANCE_HOLD_DURATION_SEC,
            EmberPostureMath.GLANCE_RETURN_DURATION_SEC,
            0.45,
            "Read"),

    /**
     * 座椅休闲发呆（未持书）：柔和跟随前方近距离目标，目标离开后平滑回正，无需释放骨骼遮罩。
     */
    SEATED_IDLE(
            GazeChannel.HEAD_LAYER,
            GazeStyle.SOFT_TRACK,
            0.0F,
            0.0F,
            0.0F,
            0.35,
            0.0,
            0.45,
            0.35,
            null);

    public enum GazeChannel {
        NONE,
        HEAD_LAYER
    }

    public enum GazeStyle {
        NONE,
        BRIEF_RAISE,
        SOFT_TRACK
    }

    public final GazeChannel channel;
    public final GazeStyle style;
    public final float verticalBias;
    public final float headReleasePeak;
    public final float neckReleasePeak;
    public final double raiseSec;
    public final double holdSec;
    public final double returnSec;
    public final double settleSec;
    public final String releaseTrackKeyword;

    PostureGazeProfile(
            GazeChannel channel,
            GazeStyle style,
            float verticalBias,
            float headReleasePeak,
            float neckReleasePeak,
            double raiseSec,
            double holdSec,
            double returnSec,
            double settleSec,
            String releaseTrackKeyword) {
        this.channel = channel;
        this.style = style;
        this.verticalBias = verticalBias;
        this.headReleasePeak = headReleasePeak;
        this.neckReleasePeak = neckReleasePeak;
        this.raiseSec = raiseSec;
        this.holdSec = holdSec;
        this.returnSec = returnSec;
        this.settleSec = settleSec;
        this.releaseTrackKeyword = releaseTrackKeyword;
    }

    public double totalBriefDurationSec() {
        return raiseSec + holdSec + returnSec;
    }

    public boolean hasBoneMaskRelease() {
        return releaseTrackKeyword != null
                && !releaseTrackKeyword.isEmpty()
                && (headReleasePeak > 0.0F || neckReleasePeak > 0.0F);
    }
}
