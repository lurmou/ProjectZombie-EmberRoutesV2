package emberroutes.npc.brain;

/**
 * 顶层职责双大脑硬分叉路由器（{@code BrainMode.SETTLEMENT} vs {@code BrainMode.SQUAD}）：
 * <ul>
 *   <li>两棵状态树彻底隔离：征召随从态完全不感知据点床铺、座椅与生产队列；</li>
 *   <li>模式切换立刻对旧大脑执行 {@link StateTreeRunner#shutdown(BrainContext)}，自动 LIFO 释放全部
 *       {@link StateScope} 租约、寻路与临时道具，实现零烂账切换。</li>
 * </ul>
 */
public final class BrainRouter {
    private BrainMode mode = BrainMode.SETTLEMENT;
    private StateTreeRunner activeRunner;
    private WorkDutyProvider workProvider = LogisticsWorkDutyProvider.INSTANCE;

    public BrainRouter() {
        this.activeRunner = SettlementBrainBuilder.createRunner(this.workProvider);
    }

    /** 构造自定义双树路由器（供脱机 JUnit 严密测试模式切换与逆序清理使用）。 */
    public BrainRouter(StateTreeRunner settlementRunner, StateTreeRunner squadRunner) {
        this.settlementOverride = settlementRunner;
        this.squadOverride = squadRunner;
        this.activeRunner = settlementRunner;
    }

    private StateTreeRunner settlementOverride;
    private StateTreeRunner squadOverride;

    public BrainMode getMode() {
        return mode;
    }

    public StateTreeRunner getActiveRunner() {
        return activeRunner;
    }

    public String getActivePathString() {
        return activeRunner != null ? activeRunner.getActivePathString() : "NONE";
    }

    public void setWorkDutyProvider(WorkDutyProvider provider) {
        this.workProvider = provider != null ? provider : LogisticsWorkDutyProvider.INSTANCE;
        if (settlementOverride == null && mode == BrainMode.SETTLEMENT) {
            shutdown(null);
            this.activeRunner = SettlementBrainBuilder.createRunner(this.workProvider);
        }
    }

    /** 硬切换大脑模式：先完整 {@code shutdown} 旧树与临时道具再挂载新树，零锁泄漏。 */
    public boolean setMode(BrainMode nextMode, BrainContext ctx) {
        if (nextMode == null || nextMode == this.mode) {
            return false;
        }
        shutdown(ctx);
        this.mode = nextMode;
        if (nextMode == BrainMode.SQUAD) {
            this.activeRunner = squadOverride != null ? squadOverride : SquadBrainBuilder.createRunner();
        } else {
            this.activeRunner = settlementOverride != null
                    ? settlementOverride
                    : SettlementBrainBuilder.createRunner(this.workProvider);
        }
        return true;
    }

    public void tick(BrainContext ctx) {
        if (activeRunner != null && ctx != null) {
            activeRunner.tick(ctx);
        }
    }

    public void shutdown(BrainContext ctx) {
        if (activeRunner != null) {
            activeRunner.shutdown(ctx);
        }
        if (ctx != null && ctx.npc != null) {
            TempPropHelper.cleanupAllTempProps(ctx.npc);
        }
    }
}
