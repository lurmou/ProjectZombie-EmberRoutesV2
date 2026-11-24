package emberroutes.npc.combat;

/**
 * 第 1 号战斗微操内核：{@code StaggerPressure}（硬直窗口期近战出手时间预算裁决）。
 *
 * <p>核心公式：
 * <ul>
 *   <li>边缘触发受击硬直窗口 {@code 1800ms}；</li>
 *   <li>近战上步出刀预算：{@code commitMs = closeMoveMs * 0.65f + 50.0f + 550.0f}；</li>
 *   <li>须同时满足比硬直剩余窗口及附近次级站立威胁抵达时间均富裕 {@code +250ms} 安全余量才压进出刀。</li>
 * </ul>
 */
public final class StaggerPressure {
    public static final long STAGGER_WINDOW_MS = 1800L;
    public static final float CLOSE_MOVE_WEIGHT = 0.65F;
    public static final float FIXED_PREPARE_MS = 50.0F;
    public static final float SWING_COMMIT_MS = 550.0F;
    public static final float SAFETY_MARGIN_MS = 250.0F;

    public enum Decision {
        COMMIT_PRESS,
        HOLD_KITE,
        ABORT_THREAT
    }

    private StaggerPressure() {}

    public static float computeCommitBudgetMs(float closeMoveMs) {
        return Math.max(0.0F, closeMoveMs) * CLOSE_MOVE_WEIGHT + FIXED_PREPARE_MS + SWING_COMMIT_MS;
    }

    /**
     * 判定受击硬直目标是否可安全前压白嫖一刀（纯函数，可直接用于单测与运行期）。
     *
     * @param staggerStartMs 目标受击硬直上沿时间戳（{@code <=0} 表示未进入硬直）
     * @param nowMs 当前时间戳
     * @param closeMoveMs NPC 贴入有效出刀距离所需的移动估算毫秒
     * @param nearestSecondaryEtaMs 除主目标外最近一只站立威胁抵达贴身位的估算毫秒（无次级威胁传 {@code Float.POSITIVE_INFINITY}）
     * @param targetMask 主目标姿态掩码
     */
    public static Decision evaluate(
            long staggerStartMs,
            long nowMs,
            float closeMoveMs,
            float nearestSecondaryEtaMs,
            int targetMask) {
        if (!CombatTargetState.isValid(targetMask)) {
            return Decision.ABORT_THREAT;
        }
        float commitMs = computeCommitBudgetMs(closeMoveMs);
        float requiredMs = commitMs + SAFETY_MARGIN_MS;

        // 次级威胁即将合围贴脸，禁止贪刀前压
        if (Float.isFinite(nearestSecondaryEtaMs) && nearestSecondaryEtaMs <= requiredMs) {
            return Decision.HOLD_KITE;
        }

        // 倒地/过渡态或尚处于有效硬直窗口内
        boolean staggered = CombatTargetState.isStaggered(targetMask)
                || CombatTargetState.isPostureTransition(targetMask)
                || CombatTargetState.isProneOrCrawl(targetMask);
        if (!staggered) {
            // 对正常站立单一目标：若次级威胁足够远且贴身成本低，可允许标准节奏接战，由近战风筝步控制
            return nearestSecondaryEtaMs > requiredMs ? Decision.COMMIT_PRESS : Decision.HOLD_KITE;
        }

        if (staggerStartMs > 0L && nowMs >= staggerStartMs) {
            long elapsed = nowMs - staggerStartMs;
            float remainingWindowMs = Math.max(0.0F, (float) (STAGGER_WINDOW_MS - elapsed));
            if (remainingWindowMs <= requiredMs && CombatTargetState.isStanding(targetMask)) {
                return Decision.HOLD_KITE;
            }
        }
        return Decision.COMMIT_PRESS;
    }
}
