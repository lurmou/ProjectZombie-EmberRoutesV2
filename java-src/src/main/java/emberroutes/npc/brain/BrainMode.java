package emberroutes.npc.brain;

/**
 * {@link BrainRouter} 顶层硬分叉大脑模式枚举。
 *
 * <p>{@code SETTLEMENT} 自治据点平民大脑与 {@code SQUAD} 征召随从/战斗大脑彻底隔离，切换时自动 LIFO 销毁旧大脑作用域。
 */
public enum BrainMode {
    SETTLEMENT,
    SQUAD
}
