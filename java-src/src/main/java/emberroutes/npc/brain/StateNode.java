package emberroutes.npc.brain;

/**
 * UE5 {@code EmberStateTree} 状态节点抽象基类：
 * <ul>
 *   <li>{@link #children} 数组顺序即严格优先级序（索引越小优先级越高）；</li>
 *   <li>{@link #preemptive} 标记的高优抢占节点可零延迟打破低优节点 {@link #minDwellSimSeconds} 防抖驻留；</li>
 *   <li>所有持有的 {@code SmartSlotLease}、寻路票据、{@code TEMP_PROP} 或 {@code BaseAction} 必须在 {@link #onEnter} 挂载进 {@link StateScope}。</li>
 * </ul>
 */
public abstract class StateNode {
    private static final StateNode[] EMPTY_CHILDREN = new StateNode[0];
    private static final StateEvaluator[] EMPTY_EVALS = new StateEvaluator[0];

    private final String id;
    private final StateNode[] children;
    private final StateEvaluator[] evaluators;
    private final double minDwellSimSeconds;
    private final boolean preemptive;

    protected StateNode(
            String id,
            StateNode[] children,
            StateEvaluator[] evaluators,
            double minDwellSimSeconds,
            boolean preemptive) {
        this.id = id;
        this.children = children != null ? children : EMPTY_CHILDREN;
        this.evaluators = evaluators != null ? evaluators : EMPTY_EVALS;
        this.minDwellSimSeconds = Math.max(0.0, minDwellSimSeconds);
        this.preemptive = preemptive;
    }

    public final String getId() {
        return id;
    }

    public final StateNode[] getChildren() {
        return children;
    }

    public final StateEvaluator[] getEvaluators() {
        return evaluators;
    }

    public final double getMinDwellSimSeconds() {
        return minDwellSimSeconds;
    }

    public final boolean isPreemptive() {
        return preemptive;
    }

    /** 节点进入门控条件（默认永远可进入）。 */
    public boolean canEnter(BrainContext ctx) {
        return true;
    }

    /** 节点持续驻留门控条件（默认与 {@link #canEnter(BrainContext)} 一致，可用滞回或失效检查覆写）。 */
    public boolean canContinue(BrainContext ctx) {
        return canEnter(ctx);
    }

    public void onEnter(BrainContext ctx, StateScope scope) {}

    public void onTick(BrainContext ctx, StateScope scope, double dtSim) {}

    public void onExit(BrainContext ctx) {}
}
