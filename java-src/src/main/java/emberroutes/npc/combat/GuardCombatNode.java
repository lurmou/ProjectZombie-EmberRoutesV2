package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.brain.BrainContext;
import emberroutes.npc.brain.StateNode;
import emberroutes.npc.brain.StateScope;
import emberroutes.warehouse.WarehouseFastIndex;

/**
 * {@code BrainMode.SETTLEMENT} 守卫专属战斗节点（{@code GUARD_COMBAT}）：
 * <ul>
 *   <li>仅当 NPC 启用了持久 {@code guardDuty} 且绑定了所属据点时抢占原版平民 {@code THREAT_EVADE}；</li>
 *   <li>警戒半径：据点领地外扩 {@code 12.0} 格，硬追击牵引绳（Leash）{@code 18.0} 格，越线立即停止追击折返驻地；</li>
 *   <li>内部 100% 复用 {@link SquadCombatNodes#executeTacticalCombatTick} 与集中式 {@link EmberSquadDirector}，0 逻辑重复。</li>
 * </ul>
 */
public final class GuardCombatNode extends StateNode {
    public static final float GUARD_ENGAGE_MARGIN = 12.0F;
    public static final float GUARD_HARD_LEASH = 18.0F;

    private final SquadCombatNodes.CombatSessionState session = new SquadCombatNodes.CombatSessionState();
    private boolean returningToPost;

    public GuardCombatNode() {
        super("GUARD_COMBAT", null, null, 0.25, true);
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
        if (returningToPost) {
            double d = WarehouseFastIndex.distanceToSettlement2D(ctx.settlementId, ctx.npc.getX(), ctx.npc.getY());
            return d > 3.0;
        }
        return canEnter(ctx);
    }

    @Override
    public void onEnter(BrainContext ctx, StateScope scope) {
        ctx.working = false;
        returningToPost = false;
        session.reset();
        IsoNPC npc = ctx.npc;
        if (npc == null || npc.isDead()) return;
        ctx.requestStandUp(true);
        scope.defer(npc.getGaze().suppress("COMBAT"));
        scope.defer(() -> {
            returningToPost = false;
            SquadCombatNodes.cleanupCombatScope(ctx, session);
        });
    }

    @Override
    public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
        IsoNPC npc = ctx.npc;
        if (npc == null || npc.isDead() || ctx.settlementId == null) return;

        double distHome = WarehouseFastIndex.distanceToSettlement2D(ctx.settlementId, npc.getX(), npc.getY());
        if (distHome > GUARD_HARD_LEASH) {
            returningToPost = true;
        } else if (returningToPost && distHome <= 3.0) {
            returningToPost = false;
            npc.getLocomotion().stop(npc);
        }

        if (returningToPost) {
            ctx.combatActivityState = "COMBAT";
            npc.clearVirtualAim();
            npc.setIsAiming(false);
            int[] anchor = WarehouseFastIndex.getSettlementAnchorTile(ctx.settlementId);
            if (anchor != null) {
                npc.getLocomotion().clearCombatMove(npc);
                ctx.requestMoveTo(anchor[0] + 0.5, anchor[1] + 0.5, anchor[2], true);
            }
            return;
        }

        SquadCombatNodes.executeTacticalCombatTick(ctx, session, false, false, dtSim);
    }
}
