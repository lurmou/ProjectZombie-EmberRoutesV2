package emberroutes.npc.brain;

/**
 * 构建 {@code BrainMode.SQUAD} 征召随从状态树（完全屏蔽据点床铺 / 座椅 / 工种分配）。
 *
 * <p>M3-① 落地随从队列跟随叶（经起身屏障启动跟随、退出时安全停随）；M4（{@code EMB-30}）将在前侧扩展
 * {@code VEHICLE_CO_RIDE / EMERGENCY_SELF_DEFENSE / COMBAT_ENGAGE}。
 */
public final class SquadBrainBuilder {
    private SquadBrainBuilder() {}

    public static StateTreeRunner createRunner() {
        StateNode followLeaf = new FormationFollowNode();
        StateNode root = new RootNode("SQUAD", new StateNode[]{followLeaf});
        return new StateTreeRunner(root);
    }

    private static final class RootNode extends StateNode {
        RootNode(String id, StateNode[] children) {
            super(id, children, null, 0.0, false);
        }
    }

    private static final class FormationFollowNode extends StateNode {
        FormationFollowNode() {
            super("FORMATION_FOLLOW", null, null, 0.0, false);
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            ctx.working = false;
            if (ctx.npc == null || ctx.npc.isDead()) {
                return;
            }
            ctx.requestFollow(0, -1);
            scope.defer(() -> {
                if (ctx.npc != null && !ctx.npc.isDead()) {
                    ctx.npc.getPosture().clearPendingContinuation();
                    ctx.npc.getFollow().stop(ctx.npc, false, "SQUAD_EXIT");
                }
            });
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            if (ctx.npc != null && !ctx.npc.isDead()
                    && !ctx.npc.getFollow().isEnabled()
                    && !ctx.npc.getPosture().isBlockingLocomotion()) {
                // 如未在等待起身队列且意外处于非跟随态，保持重连
                ctx.requestFollow(0, -1);
            }
        }
    }
}
