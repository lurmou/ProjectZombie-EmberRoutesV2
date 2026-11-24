package emberroutes.npc.combat;

/**
 * 第 3 号战斗微操内核：B42 丧尸权威姿态与受击硬直状态分类掩码（零 {@code zombie.*} 依赖）。
 *
 * <p>严格区隔「假复活/假死」「倒起转换期（禁踩禁推、强制普通重击）」「真倒地/爬行（支持踩踏/砸地与反爬行排斥）」，
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

    /** 处于 FallDown / GetUp 转换期严格禁止踩踏与推搡，改走普通近战白嫖重击。 */
    public static boolean allowsStompOrShove(int mask) {
        return isValid(mask) && !isPostureTransition(mask);
    }

    /** 倒地且非起身/倒地转换期才允许执行地面踩踏/砸地。 */
    public static boolean isGroundAttackable(int mask) {
        return isValid(mask) && !isPostureTransition(mask) && isProneOrCrawl(mask);
    }
}
