package emberroutes.npc.brain;

/**
 * UE5 {@code EmberStateTree} 运行时调度器（持久化定长激活栈 + 三阶段 Tick + LCA 差分进出 + RAII 防泄漏）：
 * <ol>
 *   <li>Phase 1 — 仅执行根节点与当前 {@code activeStack} 上挂载的 {@link StateEvaluator}；</li>
 *   <li>Phase 2 — 自根按声明顺序解析高优目标路径，支持 {@code minDwellSimSeconds} 防抖与高优抢占，按最近公共祖先（LCA）逆序退出、正序进入；</li>
 *   <li>Phase 3 — 逐层驱动 {@code activeStack[0..activeDepth-1]} 的 {@code onTick}。</li>
 * </ol>
 */
public final class StateTreeRunner {
    public static final int MAX_DEPTH = 6;

    private final StateNode root;
    private final StateNode[] activeStack = new StateNode[MAX_DEPTH];
    private final StateScope[] activeScopes = new StateScope[MAX_DEPTH];
    private final int[] activeChildIdx = new int[MAX_DEPTH];
    private final double[] enteredSimTime = new double[MAX_DEPTH];
    private int activeDepth;

    private final StateNode[] candPath = new StateNode[MAX_DEPTH];
    private final int[] candChildIdx = new int[MAX_DEPTH];
    private StateScope chainedNextScope = new StateScope();

    public StateTreeRunner(StateNode root) {
        if (root == null) {
            throw new IllegalArgumentException("StateTree root must not be null");
        }
        this.root = root;
    }

    public StateNode getRoot() {
        return root;
    }

    public int getActiveDepth() {
        return activeDepth;
    }

    public StateNode getActiveLeaf() {
        return activeDepth > 0 ? activeStack[activeDepth - 1] : null;
    }

    public StateScope getScopeAtDepth(int depth) {
        return (depth >= 0 && depth < activeDepth) ? activeScopes[depth] : null;
    }

    public StateScope getChainedNextScope() {
        return chainedNextScope;
    }

    /** 查询当前叶子是否仍处于防抖最小驻留保护窗内（用于拍卖器避开刚坐下休息的 NPC）。 */
    public boolean isMinDwellActive(double simTime) {
        if (activeDepth <= 0) {
            return false;
        }
        StateNode leaf = activeStack[activeDepth - 1];
        double minDwell = leaf.getMinDwellSimSeconds();
        return minDwell > 0.0 && (simTime - enteredSimTime[activeDepth - 1]) < minDwell;
    }

    /** 返回当前激活路径拼接字符串（如 {@code ROOT/SURVIVAL_CRITICAL/EAT}），无 Heap 浪费。 */
    public String getActivePathString() {
        if (activeDepth <= 0) {
            return "NONE";
        }
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < activeDepth; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(activeStack[i].getId());
        }
        return sb.toString();
    }

    /** 三阶段定长栈单帧 Tick。 */
    public void tick(BrainContext ctx) {
        if (ctx == null) {
            return;
        }

        // Phase 1: Scoped Evaluators（仅评估根节点及当前活跃路径栈）
        if (activeDepth == 0) {
            runEvaluators(root, ctx);
        } else {
            for (int d = 0; d < activeDepth; d++) {
                runEvaluators(activeStack[d], ctx);
            }
        }

        // Phase 2: Target Path Selection & LCA Differential Transition
        int candDepth = resolveTargetPath(root, 0, ctx);
        if (candDepth <= 0) {
            if (activeDepth > 0) {
                exitDownTo(0, ctx);
            }
            return;
        }

        int div = 0;
        int minDepth = Math.min(activeDepth, candDepth);
        while (div < minDepth && activeStack[div] == candPath[div]) {
            div++;
        }

        if (div < activeDepth || div < candDepth) {
            boolean holdForDwell = false;
            if (activeDepth > 0 && div < activeDepth && allActiveCanContinue(ctx)) {
                StateNode currentLeaf = activeStack[activeDepth - 1];
                double dwellElapsed = ctx.simTime - enteredSimTime[activeDepth - 1];
                if (dwellElapsed < currentLeaf.getMinDwellSimSeconds()
                        && !isPreemptiveSwitch(div, candDepth, ctx)) {
                    holdForDwell = true;
                }
            }

            if (!holdForDwell) {
                exitDownTo(div, ctx);
                if (div <= 1 && activeDepth > 0) {
                    closeAndResetChainedScope();
                } else if (div <= 1) {
                    closeAndResetChainedScope();
                }
                for (int d = div; d < candDepth; d++) {
                    StateNode next = candPath[d];
                    StateScope scope = new StateScope();
                    activeStack[d] = next;
                    activeScopes[d] = scope;
                    activeChildIdx[d] = candChildIdx[d];
                    enteredSimTime[d] = ctx.simTime;
                    activeDepth = d + 1;
                    next.onEnter(ctx, scope);
                }
            }
        }

        // Phase 3: Active Path Tick
        for (int d = 0; d < activeDepth; d++) {
            activeStack[d].onTick(ctx, activeScopes[d], ctx.dtSim);
        }
    }

    /** 彻底停止整棵状态树并按 LIFO 逆序释放全部层级 {@link StateScope} 与预锁槽。 */
    public void shutdown(BrainContext ctx) {
        if (activeDepth > 0) {
            exitDownTo(0, ctx);
        }
        closeAndResetChainedScope();
    }

    /** 将挂于 {@code chainedNextScope} 的接力预锁零拷贝转移给当前活跃叶子（若无活跃叶子则立即释放防泄漏）。 */
    public void transferChainedScopeToActiveLeaf() {
        StateScope leafScope = getScopeAtDepth(activeDepth - 1);
        if (leafScope != null && !leafScope.isClosed()) {
            chainedNextScope.transferTo(leafScope);
        } else {
            chainedNextScope.close();
        }
        chainedNextScope = new StateScope();
    }

    private void closeAndResetChainedScope() {
        if (chainedNextScope != null && !chainedNextScope.isClosed()) {
            chainedNextScope.close();
        }
        chainedNextScope = new StateScope();
    }

    private void exitDownTo(int targetDepth, BrainContext ctx) {
        for (int d = activeDepth - 1; d >= targetDepth; d--) {
            StateNode node = activeStack[d];
            StateScope scope = activeScopes[d];
            activeStack[d] = null;
            activeScopes[d] = null;
            activeChildIdx[d] = 0;
            enteredSimTime[d] = 0.0;
            activeDepth = d;
            if (node != null && ctx != null) {
                try {
                    node.onExit(ctx);
                } catch (Throwable ignored) {
                }
            }
            if (scope != null) {
                scope.close();
            }
        }
    }

    private boolean allActiveCanContinue(BrainContext ctx) {
        for (int d = 0; d < activeDepth; d++) {
            if (!activeStack[d].canContinue(ctx)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPreemptiveSwitch(int div, int candDepth, BrainContext ctx) {
        if (div <= 0 || div >= candDepth || div >= activeDepth) {
            return false;
        }
        if (candChildIdx[div] >= activeChildIdx[div]) {
            return false;
        }
        for (int d = div; d < candDepth; d++) {
            if (candPath[d].isPreemptive(ctx)) {
                return true;
            }
        }
        return false;
    }

    private static void runEvaluators(StateNode node, BrainContext ctx) {
        StateEvaluator[] evals = node.getEvaluators();
        for (int i = 0; i < evals.length; i++) {
            evals[i].evaluate(ctx);
        }
    }

    private int resolveTargetPath(StateNode node, int depth, BrainContext ctx) {
        if (depth >= MAX_DEPTH || node == null) {
            return 0;
        }
        candPath[depth] = node;
        if (depth == 0) {
            candChildIdx[0] = 0;
        }
        StateNode[] children = node.getChildren();
        if (children.length == 0) {
            return depth + 1;
        }
        for (int i = 0; i < children.length; i++) {
            StateNode child = children[i];
            boolean sameAtDepth = (depth + 1 < activeDepth) && (activeStack[depth + 1] == child);
            boolean allowed = sameAtDepth ? child.canContinue(ctx) : child.canEnter(ctx);
            if (!allowed) {
                continue;
            }
            candChildIdx[depth + 1] = i;
            int resolvedDepth = resolveTargetPath(child, depth + 1, ctx);
            if (resolvedDepth > 0) {
                return resolvedDepth;
            }
        }
        return 0;
    }
}
