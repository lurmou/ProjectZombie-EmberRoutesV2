package emberroutes.npc.brain;

import emberroutes.npc.combat.SquadCombatNodes;

/**
 * 构建 {@code BrainMode.SQUAD} 征召随从状态树（完全屏蔽据点床铺 / 座椅 / 工种分配）。
 *
 * <p>严格优先序：
 * {@code VEHICLE_CO_RIDE} → {@code EMERGENCY_SELF_DEFENSE} → {@code COMBAT_ENGAGE} →
 * {@code SQUAD_FIELD_FIRST_AID} → {@code FORMATION_FOLLOW}。
 */
public final class SquadBrainBuilder {
    private SquadBrainBuilder() {}

    public static StateTreeRunner createRunner() {
        StateNode vehicleLeaf = new VehicleCoRideNode();
        StateNode emergencyLeaf = SquadCombatNodes.createEmergencySelfDefenseNode();
        StateNode combatLeaf = SquadCombatNodes.createCombatRootNode();
        StateNode firstAidLeaf = SelfAidActionNode.forSquadFieldAid(
                ctx -> SquadCombatNodes.hasThreatWithin(ctx.npc, 4.5F));
        StateNode followLeaf = new FormationFollowNode();
        StateNode root = new RootNode(
                "SQUAD",
                new StateNode[]{vehicleLeaf, emergencyLeaf, combatLeaf, firstAidLeaf, followLeaf});
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
            ctx.requestFollow(ctx.followPlayerIndex, ctx.followSlot);
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
                ctx.requestFollow(ctx.followPlayerIndex, ctx.followSlot);
            }
        }
    }
}
