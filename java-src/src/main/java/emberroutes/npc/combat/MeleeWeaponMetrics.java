package emberroutes.npc.combat;

/**
 * 纯 Java 近战武器真实距离与推击规则度量（1:1 对齐原版 {@code HandWeapon} / {@code CombatManager} / {@code SwipeStatePlayer}）。
 *
 * <p>零 {@code zombie.*} 依赖，可在 Gradle JUnit 脱机测试中直接实例化。</p>
 */
public record MeleeWeaponMetrics(
        float minRange,
        float maxRange,
        float reliableRange,
        float groundHeadReach,
        float minAngleCos,
        int maxHitCount,
        boolean isKnife,
        boolean multiHitEnabled,
        boolean unarmed) {

    /** 原版 {@code SwipeStatePlayer.MaxStompDistance} = 0.6F。 */
    public static final float VANILLA_MAX_STOMP_DIST = 0.60F;
    /** 原版 {@code IsoGameCharacter.isMeleeAttackRange:6991} 对扑击丧尸（LungeState）的射程红利 +0.20F。 */
    public static final float VANILLA_LUNGE_REACH_BONUS = 0.20F;
    /** 原版 {@code Base.BareHands} 默认 MaxRange = 1.10F（weapon.txt:14272）。 */
    public static final float VANILLA_BARE_HANDS_MAX_RANGE = 1.10F;
    public static final float ARMED_MULTI_SHOVE_MAX_DIST = 1.00F;

    /** 6 参脱机测试便捷构造。 */
    public MeleeWeaponMetrics(
            float minRange,
            float maxRange,
            float reliableRange,
            boolean isKnife,
            boolean multiHitEnabled,
            boolean unarmed) {
        this(
                minRange,
                maxRange,
                reliableRange,
                unarmed ? 0.30F : Math.max(0.65F, Math.min(1.45F, maxRange * 0.72F)),
                0.65F,
                multiHitEnabled ? 2 : 1,
                isKnife,
                multiHitEnabled,
                unarmed);
    }

    public static MeleeWeaponMetrics unarmed(boolean multiHitEnabled) {
        return unarmed(VANILLA_BARE_HANDS_MAX_RANGE, multiHitEnabled);
    }

    public static MeleeWeaponMetrics unarmed(float bareHandsMaxRange, boolean multiHitEnabled) {
        float effMax = (Float.isFinite(bareHandsMaxRange) && bareHandsMaxRange > 0.3F)
                ? bareHandsMaxRange : VANILLA_BARE_HANDS_MAX_RANGE;
        return new MeleeWeaponMetrics(
                0.0F, effMax, effMax * 0.85F, 0.30F, 0.65F, 1, false, multiHitEnabled, true);
    }

    /**
     * 对站立威胁判断是否触发主动群推（1.55m 内有 {@code >= 2} 名站立威胁围堵时主动推开脱身）。
     */
    public boolean shouldManualMultiShove(float dist, int frontStandingCount) {
        if (unarmed) {
            return false;
        }
        return frontStandingCount >= 2 && dist <= 1.55F;
    }

    /** 站立目标有效攻击上界（结合原版扑击状态 +0.20F 红利，保证走位与出刀同口径）。 */
    public float effectiveMaxRange(boolean targetLunging) {
        return maxRange + (targetLunging ? VANILLA_LUNGE_REACH_BONUS : 0.0F);
    }

    /**
     * 备战出刀理想静止环下界：与 {@link #idealRingMax(boolean)} 保持 {@code >= 0.30m} 宽稳定死区，
     * 避免原 0.16m 窄环导致前后踏步抽搐。
     */
    public float idealRingMin(boolean targetLunging) {
        if (unarmed) {
            return 0.62F;
        }
        float hi = idealRingMax(targetLunging);
        float safeLow = Math.max(minRange + 0.16F, hi - 0.36F);
        return Math.min(safeLow, hi - 0.30F);
    }

    public float idealRingMin() {
        return idealRingMin(false);
    }

    /** 备战出刀理想静止环上界（对齐原版射程外缘，留出骨骼近点余量）。 */
    public float idealRingMax(boolean targetLunging) {
        float eff = effectiveMaxRange(targetLunging);
        if (unarmed) {
            return Math.min(1.02F, eff - 0.04F);
        }
        return Math.max(minRange + 0.38F, eff - 0.02F);
    }

    public float idealRingMax() {
        return idealRingMax(false);
    }

    /** 挥后冷却风筝环形下界（比武器有效射程多退 0.50–0.65 格，封顶 2.15 格防过度退防）。 */
    public float cooldownRingMin(int nearbyEnemyCount) {
        float extra = nearbyEnemyCount >= 2 ? 0.65F : 0.50F;
        float cap = nearbyEnemyCount >= 2 ? 2.15F : 1.95F;
        return Math.min(cap, maxRange + extra);
    }

    /** 挥后冷却风筝环形上界（保持 >= 0.36m 静止缓冲）。 */
    public float cooldownRingMax(int nearbyEnemyCount) {
        return cooldownRingMin(nearbyEnemyCount) + 0.38F;
    }

    /** 倒地处决理想站位半径（锚定 Bip01_Head，确保前向挥击线段扫过头部）。 */
    public float groundHeadStandRadius() {
        return unarmed ? 0.38F : Math.max(0.32F, Math.min(0.75F, groundHeadReach * 0.68F));
    }

    /** 倒地处决允许挥击/踩踏的最大头部距离（对齐原版 MaxStompDistance=0.60F 与 DoSwingCollisionBoneCheck）。 */
    public float groundHeadSwingReach() {
        return unarmed ? VANILLA_MAX_STOMP_DIST : (groundHeadReach + 0.16F);
    }
}
