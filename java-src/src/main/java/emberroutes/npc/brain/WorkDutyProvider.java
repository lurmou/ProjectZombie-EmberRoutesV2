package emberroutes.npc.brain;

/**
 * {@code SETTLEMENT} 状态下 {@code WORK_DUTY} 节点的可插拔工作供给接口。
 *
 * <p>M3-① 默认为空实现（无挂起工单）；M3-②（{@code EMB-13} 组合拍卖）与 M5（工种切片流水线）
 * 无需改造 {@link StateTreeRunner} 内核即可直接挂载实现。
 */
public interface WorkDutyProvider {
    WorkDutyProvider NOOP = new WorkDutyProvider() {
        @Override
        public boolean canEnterWork(BrainContext ctx) {
            return false;
        }

        @Override
        public boolean canContinueWork(BrainContext ctx) {
            return false;
        }

        @Override
        public void onEnterWork(BrainContext ctx, StateScope scope) {}

        @Override
        public void onTickWork(BrainContext ctx, StateScope scope, double dtSim) {}

        @Override
        public void onExitWork(BrainContext ctx) {}
    };

    boolean canEnterWork(BrainContext ctx);

    boolean canContinueWork(BrainContext ctx);

    void onEnterWork(BrainContext ctx, StateScope scope);

    void onTickWork(BrainContext ctx, StateScope scope, double dtSim);

    void onExitWork(BrainContext ctx);
}
