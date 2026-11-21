package emberroutes.npc.brain;

/**
 * {@link emberroutes.npc.brain} 层级状态树的评估器接口（每帧 Phase 1 仅运行根节点及当前活跃路径上的评估器）。
 */
@FunctionalInterface
public interface StateEvaluator {
    void evaluate(BrainContext ctx);
}
