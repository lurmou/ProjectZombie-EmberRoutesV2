package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.brain.BrainContext;
import emberroutes.npc.brain.StateNode;
import emberroutes.npc.brain.StateScope;
import emberroutes.warehouse.WarehouseFastIndex;

/**
 * {@code BrainMode.SETTLEMENT} 守卫专属战斗分支节点（{@code GUARD_COMBAT}）：
 * <ul>
 *   <li>仅当 NPC 启用了持久 {@code guardDuty} 且绑定了所属据点时抢占原版平民 {@code THREAT_EVADE}；</li>
 *   <li>子树由 {@code GUARD_RETURN_POST}（18m 硬牵引折返）+ 4 个共用 {@link SquadCombatNodes#createCombatChildLeaves} 组成，0 逻辑重复。</li>
 * </ul>
 */
public final class GuardCombatNode extends StateNode {
    public static final float GUARD_ENGAGE_MARGIN = 12.0F;
    public static final float GUARD_HARD_LEASH = 18.0F;

    private final SquadCombatNodes.CombatSessionState session;

    public GuardCombatNode() {
        this(new SquadCombatNodes.CombatSessionState());
    }

    private GuardCombatNode(SquadCombatNodes.CombatSessionState session) {
        super("GUARD_COMBAT", buildChildren(session), null, 0.25, true);
        this.session = session;
    }

    private static StateNode[] buildChildren(SquadCombatNodes.CombatSessionState session) {
        StateNode[] combatLeaves = SquadCombatNodes.createCombatChildLeaves(session, false);
        StateNode[] all = new StateNode[1 + combatLeaves.length];
        all[0] = new GuardReturnPostLeaf();
        System.arraycopy(combatLeaves, 0, all, 1, combatLeaves.length);
        return all;
    }

    @Override
    public boolean canEnter(BrainContext ctx) {
        if (!ctx.guardDuty || ctx.npc == null || ctx.npc.isDead()) {
            return false;
        }
        if (ctx.settlementId == null || ctx.settlementId.isEmpty()) {
            return false;
        }
        double distHome = WarehouseFastIndex.distanceToSettlement2D(
                ctx.settlementId, ctx.npc.getX(), ctx.npc.getY());
        if (distHome > GUARD_HARD_LEASH) {
            return true;
        }
        EmberSquadDirector.ThreatEntry threat = SquadCombatNodes.findClosestThreatDist(ctx.npc, 14.0F);
        if (threat == null) {
            return false;
        }
        double threatDist = WarehouseFastIndex.distanceToSettlement2D(ctx.settlementId, threat.x, threat.y);
        return threatDist <= GUARD_ENGAGE_MARGIN;
    }

    @Override
    public boolean canContinue(BrainContext ctx) {
        if (!ctx.guardDuty || ctx.npc == null || ctx.npc.isDead() || ctx.settlementId == null) {
            return false;
        }
        return session.retreatPolicy.isActive() || canEnter(ctx);
    }

    @Override
    public void onEnter(BrainContext ctx, StateScope scope) {
        ctx.working = false;
        session.reset();
        IsoNPC npc = ctx.npc;
        if (npc == null || npc.isDead()) return;
        int[] anchor = WarehouseFastIndex.getSettlementAnchorTile(ctx.settlementId);
        if (anchor != null) {
            ctx.guardAnchorX = anchor[0] + 0.5F;
            ctx.guardAnchorY = anchor[1] + 0.5F;
            ctx.guardAnchorZ = anchor[2];
        }
        ctx.requestStandUp(true);
        scope.defer(npc.getGaze().suppress("COMBAT"));
        scope.defer(() -> B42CombatRuntime.cleanupNpcCombat(ctx.npc, session));
    }

    /** 越过 18m 追击牵引绳后立即解除交战折返据点驻地。 */
    private static final class GuardReturnPostLeaf extends StateNode {
        GuardReturnPostLeaf() {
            super("GUARD_RETURN_POST", null, null, 0.0, true);
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            if (ctx.npc == null || ctx.settlementId == null) return false;
            return WarehouseFastIndex.distanceToSettlement2D(
                    ctx.settlementId, ctx.npc.getX(), ctx.npc.getY()) > GUARD_HARD_LEASH;
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            if (ctx.npc == null || ctx.settlementId == null) return false;
            return WarehouseFastIndex.distanceToSettlement2D(
                    ctx.settlementId, ctx.npc.getX(), ctx.npc.getY()) > 3.0;
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            issueReturnMove(ctx);
            scope.defer(() -> {
                if (ctx.npc != null && !ctx.npc.isDead()) {
                    ctx.npc.getLocomotion().stop(ctx.npc);
                }
            });
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            issueReturnMove(ctx);
        }

        private static void issueReturnMove(BrainContext ctx) {
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead() || ctx.settlementId == null) return;
            ctx.combatActivityState = "COMBAT";
            npc.clearVirtualAim();
            npc.setIsAiming(false);
            npc.getLocomotion().clearCombatMove(npc);
            int[] anchor = WarehouseFastIndex.getSettlementAnchorTile(ctx.settlementId);
            if (anchor != null) {
                ctx.guardAnchorX = anchor[0] + 0.5F;
                ctx.guardAnchorY = anchor[1] + 0.5F;
                ctx.guardAnchorZ = anchor[2];
                ctx.requestMoveTo(anchor[0] + 0.5, anchor[1] + 0.5, anchor[2], true);
            }
        }
    }
}
