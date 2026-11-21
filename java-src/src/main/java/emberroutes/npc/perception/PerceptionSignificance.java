package emberroutes.npc.perception;

/**
 * 实体感知重要度档位（Simulation LOD）：
 * <p>
 * 仅用于门控<b>装饰性</b>环境感知与注视微反应；玩法级感知（如 {@code SettlementDangerField} 威胁检测）
 * 严禁受本档位剪枝，避免玩家背身时 NPC 丧失危险反应。
 * </p>
 */
public enum PerceptionSignificance {
    /** 玩家当前不可见该 NPC 所在格（{@code isCouldSee(0) == false}）：装饰性感知与注视 0 CPU。 */
    HIDDEN,
    /** 玩家可见但距离较远（{@code distSq > 64}，> 8 格）：跳过近景微反应细节。 */
    FAR,
    /** 玩家可见且处于近景焦点区（{@code distSq <= 64}，≤ 8 格）：全精度感知与注视。 */
    NEAR
}
