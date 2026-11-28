package emberroutes.npc.combat;

/**
 * C7 友伤硬盾纯逻辑判定规则（零 {@code zombie.*} 依赖，供脱机 JUnit 与运行时共享）。
 */
public final class FriendlyFireRules {
    private FriendlyFireRules() {}

    /**
     * C7 友伤硬盾纯逻辑判断：
     * <ul>
     *   <li>若攻击方或受击方解析后均为人类/玩家阵营（{@code IsoPlayer} 含 {@code IsoNPC}，排除 {@code IsoAnimal}），
     *       无论 {@code CoopPVP} 开关或散弹散射角度如何，一律视为友方豁免；</li>
     *   <li>保留真玩家本人受击于僵尸 / 动物等普通威胁时的原版校验路径。</li>
     * </ul>
     */
    public static boolean evaluateFriendlyFlags(
            boolean attackerIsPlayer,
            boolean attackerIsZombie,
            boolean victimIsPlayer,
            boolean victimIsZombie,
            boolean sameEntity) {
        if (sameEntity || attackerIsZombie || victimIsZombie) {
            return false;
        }
        return attackerIsPlayer && victimIsPlayer;
    }
}
