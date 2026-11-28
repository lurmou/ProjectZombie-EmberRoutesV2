package emberroutes.npc.combat;

import emberroutes.npc.RulesOfEngagement;

/**
 * 第 3 号战斗微操内核：B42 丧尸权威姿态与受击硬直状态分类掩码（零 {@code zombie.*} 依赖）。
 *
 * <p>严格区隔「假复活/假死」「倒起转换期（禁踩禁推）」「真倒地/爬行（支持踩踏/砸地与安全侧避让）」「扑击态（+0.20F 射程）」，
 * 全字段采用无堆分配原始位掩码，支持脱机 JUnit 直接组合测试；运行时映射由 {@code B42CombatRuntime.classifyTarget} 执行。</p>
 */
public final class CombatTargetState {
    public static final int FLAG_VALID = 1 << 0;
    public static final int FLAG_STANDING = 1 << 1;
    public static final int FLAG_POSTURE_TRANSITION = 1 << 2;
    public static final int FLAG_PRONE_OR_CRAWL = 1 << 3;
    public static final int FLAG_CRAWLER = 1 << 4;
    public static final int FLAG_SPRINTER = 1 << 5;
    public static final int FLAG_STAGGERED = 1 << 6;
    public static final int FLAG_ATTACKING = 1 << 7;
    public static final int FLAG_LUNGING = 1 << 8;
    public static final int FLAG_BARRIER_WINDOW = 1 << 9;
    public static final int FLAG_BARRIER_FENCE = 1 << 10;
    public static final int FLAG_ATTACKING_ALLY = 1 << 11;
    public static final int FLAG_SEEN_DIRECT = 1 << 12;
    public static final int FLAG_HEARD = 1 << 13;

    private CombatTargetState() {}

    public static boolean isValid(int mask) {
        return (mask & FLAG_VALID) != 0;
    }

    public static boolean isStanding(int mask) {
        return (mask & FLAG_STANDING) != 0;
    }

    public static boolean isPostureTransition(int mask) {
        return (mask & FLAG_POSTURE_TRANSITION) != 0;
    }

    public static boolean isProneOrCrawl(int mask) {
        return (mask & FLAG_PRONE_OR_CRAWL) != 0;
    }

    public static boolean isCrawler(int mask) {
        return (mask & FLAG_CRAWLER) != 0;
    }

    public static boolean isSprinter(int mask) {
        return (mask & FLAG_SPRINTER) != 0;
    }

    public static boolean isStaggered(int mask) {
        return (mask & FLAG_STAGGERED) != 0;
    }

    public static boolean isAttacking(int mask) {
        return (mask & FLAG_ATTACKING) != 0;
    }

    public static boolean isLunging(int mask) {
        return (mask & FLAG_LUNGING) != 0;
    }

    public static boolean isBarrierWindow(int mask) {
        return (mask & FLAG_BARRIER_WINDOW) != 0;
    }

    public static boolean isBarrierFence(int mask) {
        return (mask & FLAG_BARRIER_FENCE) != 0;
    }

    public static boolean isAttackingAlly(int mask) {
        return (mask & FLAG_ATTACKING_ALLY) != 0;
    }

    public static boolean isSeenDirect(int mask) {
        return (mask & FLAG_SEEN_DIRECT) != 0;
    }

    public static boolean isHeard(int mask) {
        return (mask & FLAG_HEARD) != 0;
    }

    /** 处于 FallDown / GetUp 转换期严格禁止踩踏与推搡（停火等起身或倒地落稳）。 */
    public static boolean allowsStompOrShove(int mask) {
        return isValid(mask) && !isPostureTransition(mask);
    }

    /** 倒地且非起身/倒地转换期才允许执行地面踩踏/砸地。 */
    public static boolean isGroundAttackable(int mask) {
        return isValid(mask) && !isPostureTransition(mask) && isProneOrCrawl(mask);
    }

    public static boolean isEmergencySelfDefenseNeeded(
            RulesOfEngagement roe,
            boolean hasActiveAction,
            boolean holdingUsableFirearm,
            boolean threatWithinRadius) {
        if (!threatWithinRadius) {
            return false;
        }
        return roe == RulesOfEngagement.HOLD_FIRE || hasActiveAction || holdingUsableFirearm;
    }

    public static float reliableMeleeRange(float effectiveReach) {
        if (!Float.isFinite(effectiveReach) || effectiveReach <= 0.0F) {
            return 1.0F;
        }
        return Math.max(effectiveReach * 0.90F, effectiveReach - 0.08F);
    }
}
