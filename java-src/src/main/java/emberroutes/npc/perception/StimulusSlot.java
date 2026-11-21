package emberroutes.npc.perception;

/**
 * 单个可追踪刺激的定长记忆槽（0-GC 复用，瞬态不落盘）：
 * <p>
 * 统一承载“当前可见性、空间几何（位置/平方距离/前方带符号偏角）、记忆时间戳（首见/末见）
 * 以及习惯化状态（{@link #habituated} + {@link #continuousAbsenceSec}）”，
 * 供任意微反应或注意力选择器共享消费。
 * </p>
 */
public final class StimulusSlot {
    public enum StimulusKind {
        PLAYER
    }

    public final StimulusKind kind;

    public boolean visible;
    public float x;
    public float y;
    public float z;
    public int level;
    public float distSq;
    public float signedAngleRad = Float.NaN;

    public double firstSeenSimTime = Double.NaN;
    public double lastSeenSimTime = Double.NaN;
    public double continuousAbsenceSec;
    public boolean habituated;

    public StimulusSlot(StimulusKind kind) {
        this.kind = kind;
    }

    public void reset() {
        this.visible = false;
        this.x = 0.0F;
        this.y = 0.0F;
        this.z = 0.0F;
        this.level = 0;
        this.distSq = Float.POSITIVE_INFINITY;
        this.signedAngleRad = Float.NaN;
        this.firstSeenSimTime = Double.NaN;
        this.lastSeenSimTime = Double.NaN;
        this.continuousAbsenceSec = 0.0;
        this.habituated = false;
    }
}
