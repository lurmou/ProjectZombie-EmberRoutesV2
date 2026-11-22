package emberroutes.npc.brain;

import emberroutes.npc.EmberLocomotionController;
import emberroutes.npc.EmberTargetProjector;
import emberroutes.npc.IsoNPC;
import emberroutes.npc.NpcPostureController;
import emberroutes.npc.brain.NeedsPolicy.UrgentNeed;
import emberroutes.warehouse.SmartObjectRegistry;
import emberroutes.warehouse.SmartSlot.SlotKind;
import emberroutes.warehouse.SmartSlot.SmartSlotLease;
import emberroutes.warehouse.WarehouseFastIndex;
import java.util.function.Predicate;
import java.util.function.Supplier;
import zombie.characters.CharacterStat;
import zombie.iso.IsoCell;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;

/**
 * 构建 {@code BrainMode.SETTLEMENT} 自治据点平民状态树（严格层级优先级序）：
 * <ol>
 *   <li>{@code THREAT_EVADE}（0 帧抢占，经起身屏障逃入 {@code isEnclosed} 安全室）；</li>
 *   <li>{@code SURVIVAL_CRITICAL}（{@code BANDAGE -> EAT -> DRINK -> SLEEP}，真实消耗背包原件与 {@code BedSmartSlot}）；</li>
 *   <li>{@code RETURN_BORROWED_TOOL}（按 {@code ModData.ER_BorrowedFrom} 来源戳自动归还）；</li>
 *   <li>{@code WORK_DUTY}（挂接 {@link WorkDutyProvider}）；</li>
 *   <li>{@code LEISURE_AND_IDLE}（{@code SEAT_REST} 座椅 + {@code TEMP_PROP} + 12s 防抖 / {@code IDLE_STAND} 兜底）。</li>
 * </ol>
 */
public final class SettlementBrainBuilder {
    private SettlementBrainBuilder() {}

    public static StateTreeRunner createRunner(WorkDutyProvider workProvider) {
        WorkDutyProvider work = workProvider != null ? workProvider : WorkDutyProvider.NOOP;

        StateEvaluator rootDangerEval = SettlementDangerField::evaluate;
        StateEvaluator rootNeedsEval = SettlementBrainBuilder::evaluateNeedsAndBorrowedTools;

        StateNode threatEvade = new ThreatEvadeNode();
        StateNode bandageNode = new SurvivalActionNode(
                "BANDAGE",
                BandageSelfAction::new,
                ctx -> ctx.bleeding && BandageSelfAction.findBestBandage(ctx.npc) != null,
                ctx -> ctx.bleeding && BandageSelfAction.findBestBandage(ctx.npc) != null);
        StateNode eatNode = new SurvivalActionNode(
                "EAT",
                EatFoodAction::new,
                ctx -> ctx.urgentNeed == UrgentNeed.EAT && EatFoodAction.findBestEdibleFood(ctx.npc) != null,
                ctx -> ctx.hunger > NeedsPolicy.HUNGER_EXIT && EatFoodAction.findBestEdibleFood(ctx.npc) != null);
        StateNode drinkNode = new SurvivalActionNode(
                "DRINK",
                DrinkWaterAction::new,
                ctx -> ctx.urgentNeed == UrgentNeed.DRINK && DrinkWaterAction.findCleanWaterContainer(ctx.npc) != null,
                ctx -> ctx.thirst > NeedsPolicy.THIRST_EXIT && DrinkWaterAction.findCleanWaterContainer(ctx.npc) != null);
        StateNode sleepNode = new SleepSlotNode();

        StateNode survivalBranch = new BranchNode(
                "SURVIVAL_CRITICAL",
                new StateNode[]{bandageNode, eatNode, drinkNode, sleepNode},
                true);

        StateNode returnTool = new ReturnToolNode();
        StateNode workDuty = new WorkDutyNode(work);
        StateNode returnHome = new ReturnToSettlementNode();
        StateNode seatRest = new SeatLeisureNode();
        StateNode idleStand = new IdleStandNode();
        StateNode leisureBranch = new BranchNode(
                "LEISURE_AND_IDLE",
                new StateNode[]{returnHome, seatRest, idleStand},
                false);

        StateNode root = new RootNode(
                "SETTLEMENT",
                new StateNode[]{threatEvade, survivalBranch, returnTool, workDuty, leisureBranch},
                new StateEvaluator[]{rootDangerEval, rootNeedsEval});
        return new StateTreeRunner(root);
    }

    private static void evaluateNeedsAndBorrowedTools(BrainContext ctx) {
        if (ctx.npc == null || ctx.npc.isDead()) {
            ctx.bleeding = false;
            ctx.urgentNeed = UrgentNeed.NONE;
            ctx.hasBorrowedTool = false;
            return;
        }
        IsoNPC npc = ctx.npc;
        if (npc.getStats() != null) {
            ctx.hunger = npc.getStats().get(CharacterStat.HUNGER);
            ctx.thirst = npc.getStats().get(CharacterStat.THIRST);
            ctx.fatigue = npc.getStats().get(CharacterStat.FATIGUE);
            ctx.endurance = npc.getStats().get(CharacterStat.ENDURANCE);
        }
        ctx.bleeding = BandageSelfAction.hasBleedingUnbandagedPart(npc);
        ctx.urgentNeed = NeedsPolicy.evaluateUrgentNeed(
                ctx.hunger, ctx.thirst, ctx.fatigue, ctx.bleeding, ctx.working, ctx.urgentNeed);

        // 若目标生存动作欠缺消耗品（无食物/无水/无绷带），清回 NONE 防止闭锁后续分支
        if (ctx.urgentNeed == UrgentNeed.BANDAGE && BandageSelfAction.findBestBandage(npc) == null) {
            ctx.urgentNeed = NeedsPolicy.evaluateUrgentNeed(
                    ctx.hunger, ctx.thirst, ctx.fatigue, false, ctx.working, UrgentNeed.NONE);
        }
        if (ctx.urgentNeed == UrgentNeed.EAT && EatFoodAction.findBestEdibleFood(npc) == null) {
            ctx.urgentNeed = UrgentNeed.NONE;
        } else if (ctx.urgentNeed == UrgentNeed.DRINK && DrinkWaterAction.findCleanWaterContainer(npc) == null) {
            ctx.urgentNeed = UrgentNeed.NONE;
        }

        if (ctx.frameTick % 15L == 0L || ctx.hasBorrowedTool) {
            ctx.hasBorrowedTool = (ReturnToolAction.findBorrowedTool(npc) != null);
        }
    }

    private static void deferStopMove(BrainContext ctx, StateScope scope) {
        scope.defer(() -> {
            if (ctx.npc != null && !ctx.npc.isDead()) {
                ctx.npc.getPosture().clearPendingContinuation();
                ctx.npc.getLocomotion().stop(ctx.npc);
            }
        });
    }

    private static final class RootNode extends StateNode {
        RootNode(String id, StateNode[] children, StateEvaluator[] evaluators) {
            super(id, children, evaluators, 0.0, false);
        }
    }

    private static final class BranchNode extends StateNode {
        BranchNode(String id, StateNode[] children, boolean preemptive) {
            super(id, children, null, 0.0, preemptive);
        }
    }

    private static final class ThreatEvadeNode extends StateNode {
        private float sentGoalX;
        private float sentGoalY;
        private int sentGoalZ;

        ThreatEvadeNode() {
            super("THREAT_EVADE", null, null, 0.0, true);
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            return ctx.threatDetected && ctx.hasSafeAnchor;
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            return ctx.threatDetected;
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            ctx.working = false;
            sentGoalX = Float.NaN;
            sentGoalY = Float.NaN;
            sentGoalZ = Integer.MIN_VALUE;
            issueMoveIfNeeded(ctx);
            deferStopMove(ctx, scope);
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            if (!ctx.hasSafeAnchor || ctx.npc == null) {
                return;
            }
            float distToGoal = (float) Math.hypot(ctx.safeAnchorX - ctx.npc.getX(), ctx.safeAnchorY - ctx.npc.getY());
            if (distToGoal > 1.1F) {
                double shift = Float.isNaN(sentGoalX) ? 999.0
                        : Math.hypot(ctx.safeAnchorX - sentGoalX, ctx.safeAnchorY - sentGoalY);
                if (shift > 1.5 || sentGoalZ != ctx.safeAnchorZ) {
                    issueMoveIfNeeded(ctx);
                }
            }
        }

        private void issueMoveIfNeeded(BrainContext ctx) {
            if (!ctx.hasSafeAnchor) {
                return;
            }
            sentGoalX = ctx.safeAnchorX;
            sentGoalY = ctx.safeAnchorY;
            sentGoalZ = ctx.safeAnchorZ;
            ctx.requestMoveTo(ctx.safeAnchorX, ctx.safeAnchorY, ctx.safeAnchorZ, true);
        }
    }

    /** 复用型确定性定时生存动作叶（供 {@code BANDAGE / EAT / DRINK} 共享，零重复逻辑）。 */
    private static final class SurvivalActionNode extends StateNode {
        private final Supplier<BaseAction> factory;
        private final Predicate<BrainContext> enterGuard;
        private final Predicate<BrainContext> continueGuard;
        private BaseAction action;
        private boolean actionStarted;

        SurvivalActionNode(
                String id,
                Supplier<BaseAction> factory,
                Predicate<BrainContext> enterGuard,
                Predicate<BrainContext> continueGuard) {
            super(id, null, null, 0.0, true);
            this.factory = factory;
            this.enterGuard = enterGuard;
            this.continueGuard = continueGuard;
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            return ctx.npc != null && enterGuard.test(ctx);
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            if (ctx.npc == null) {
                return false;
            }
            if (actionStarted && action != null && action.getStatus() == BaseAction.ActionStatus.FAILED) {
                return false;
            }
            return continueGuard.test(ctx);
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            ctx.working = false;
            this.action = factory.get();
            this.actionStarted = false;
            if (ctx.npc != null) {
                ctx.npc.getLocomotion().stop(ctx.npc);
                if (ctx.npc.getPosture().isBlockingLocomotion()) {
                    ctx.requestStandUp(true);
                }
            }
            scope.defer(() -> {
                if (this.action != null) {
                    this.action.interrupt(ctx, "SCOPE_EXIT");
                    this.action = null;
                }
            });
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            if (ctx.npc == null || action == null) {
                return;
            }
            if (!actionStarted) {
                if (ctx.npc.getPosture().isActive()) {
                    ctx.requestStandUp(false);
                    return;
                }
                action.start(ctx);
                actionStarted = true;
            }
            if (action.isRunning()) {
                action.tick(ctx, dtSim);
            }
            if (actionStarted
                    && action != null
                    && action.getStatus() == BaseAction.ActionStatus.COMPLETED
                    && continueGuard.test(ctx)) {
                this.action = factory.get();
                this.action.start(ctx);
            }
        }
    }

    private static boolean canAcquireFurnitureSlot(BrainContext ctx, SlotKind kind) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (ctx == null || ctx.npc == null || ctx.settlementId == null || cell == null) {
            return false;
        }
        boolean isBed = (kind == SlotKind.BED);
        return SmartObjectRegistry.hasAvailableSlot(
                ctx.settlementId,
                kind,
                ctx.npcId,
                ctx.simTime,
                slot -> {
                    IsoObject obj = SmartObjectRegistry.resolveWorldObject(slot, cell);
                    return obj != null && !ctx.isFurniturePhysicallyOccupied(obj, isBed);
                });
    }

    private static SmartSlotLease acquireFurnitureLease(BrainContext ctx, IsoCell cell, SlotKind kind, double ttlSec) {
        IsoNPC npc = ctx.npc;
        boolean isBed = (kind == SlotKind.BED);
        return SmartObjectRegistry.acquireNearest(
                ctx.settlementId,
                kind,
                ctx.npcId,
                npc.getX(),
                npc.getY(),
                EmberTargetProjector.levelOf(npc.getZ()),
                ctx.simTime,
                ttlSec,
                slot -> {
                    IsoObject obj = SmartObjectRegistry.resolveWorldObject(slot, cell);
                    return obj != null && !ctx.isFurniturePhysicallyOccupied(obj, isBed);
                });
    }

    /** 共享型 {@link SmartSlot} 家具入座/卧睡生命周期基类（绑定逻辑租约、物理占座延迟至 STANDING 释放与失活自愈）。 */
    private abstract static class FurnitureSlotNode extends StateNode {
        private final SlotKind slotKind;
        private final double leaseTtlSec;
        protected SmartSlotLease lease;
        protected boolean postureActivated;
        protected boolean failed;
        protected double enteredSimTime;

        FurnitureSlotNode(String id, SlotKind slotKind, double leaseTtlSec, double minDwellSec, boolean preemptive) {
            super(id, null, null, minDwellSec, preemptive);
            this.slotKind = slotKind;
            this.leaseTtlSec = leaseTtlSec;
        }

        protected abstract boolean issuePostureCommand(IsoNPC npc, IsoObject obj);

        protected abstract boolean checkSlotStillNeeded(BrainContext ctx);

        protected void onBeforeRelease(IsoNPC npc) {}

        @Override
        public boolean canContinue(BrainContext ctx) {
            if (ctx.npc == null || failed || lease == null || !lease.isValid()) {
                return false;
            }
            if (postureActivated && !ctx.npc.getPosture().isActive()) {
                return false;
            }
            return checkSlotStillNeeded(ctx);
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            ctx.working = false;
            this.postureActivated = false;
            this.failed = false;
            this.enteredSimTime = ctx.simTime;
            IsoNPC npc = ctx.npc;
            IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
            if (npc == null || cell == null || ctx.settlementId == null) {
                this.failed = true;
                return;
            }
            SmartSlotLease acquired = acquireFurnitureLease(ctx, cell, slotKind, leaseTtlSec);
            if (acquired == null) {
                this.failed = true;
                return;
            }
            this.lease = acquired;

            // LIFO：先释放逻辑租约，再清临时道具并起立（物理占座由 PostureController 保留至 STANDING）
            scope.defer(() -> {
                SmartObjectRegistry.releaseLease(acquired);
                if (this.lease == acquired) {
                    this.lease = null;
                }
            });
            scope.defer(() -> {
                onBeforeRelease(npc);
                ctx.requestStandUp(ctx.threatDetected);
            });

            IsoObject worldObj = SmartObjectRegistry.resolveWorldObject(acquired.getSlot(), cell);
            if (worldObj == null || !issuePostureCommand(npc, worldObj)) {
                if (worldObj == null) {
                    SmartObjectRegistry.unregisterSquare(
                            ctx.settlementId,
                            acquired.getSlot().originX,
                            acquired.getSlot().originY,
                            acquired.getSlot().originZ);
                }
                this.failed = true;
            }
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            if (ctx.npc != null && ctx.npc.getPosture().isActive()) {
                postureActivated = true;
            }
        }

        @Override
        public void onExit(BrainContext ctx) {
            this.postureActivated = false;
            this.failed = false;
        }
    }

    /** 床铺 SmartSlot 睡眠叶。 */
    private static final class SleepSlotNode extends FurnitureSlotNode {
        SleepSlotNode() {
            super("SLEEP", SlotKind.BED, 120.0, 0.0, true);
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            return ctx.urgentNeed == UrgentNeed.SLEEP
                    && !failed
                    && canAcquireFurnitureSlot(ctx, SlotKind.BED);
        }

        @Override
        protected boolean issuePostureCommand(IsoNPC npc, IsoObject obj) {
            return npc.getPosture().commandSleepOnBed(npc, obj);
        }

        @Override
        protected boolean checkSlotStillNeeded(BrainContext ctx) {
            return ctx.fatigue > NeedsPolicy.FATIGUE_EXIT;
        }
    }

    private static final class ReturnToolNode extends StateNode {
        private int targetX;
        private int targetY;
        private int targetZ;
        private boolean hasTarget;
        private boolean moveIssued;
        private BaseAction.ItemLocation targetLoc;

        ReturnToolNode() {
            super("RETURN_BORROWED_TOOL", null, null, 0.0, true);
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            return ctx.hasBorrowedTool && ctx.npc != null;
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            return ctx.hasBorrowedTool && hasTarget && ctx.npc != null;
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            ctx.working = false;
            moveIssued = false;
            hasTarget = false;
            targetLoc = ReturnToolAction.findBorrowedTool(ctx.npc);
            if (targetLoc == null || !targetLoc.item().hasModData()) {
                ctx.hasBorrowedTool = false;
                return;
            }
            Object raw = targetLoc.item().getModData().rawget(ReturnToolAction.MODDATA_BORROWED_FROM_KEY);
            int[] xyz = (raw instanceof String s) ? ReturnToolAction.parseSourceCoords(s) : null;
            if (xyz == null) {
                targetLoc.item().getModData().rawset(ReturnToolAction.MODDATA_BORROWED_FROM_KEY, null);
                ctx.hasBorrowedTool = false;
                return;
            }
            targetX = xyz[0];
            targetY = xyz[1];
            targetZ = xyz[2];
            hasTarget = true;
            scope.defer(() -> {
                if (ctx.npc != null && !ctx.npc.isDead()) {
                    ctx.npc.getLocomotion().stop(ctx.npc);
                }
            });
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            if (!hasTarget || ctx.npc == null) {
                return;
            }
            targetLoc = ReturnToolAction.findBorrowedTool(ctx.npc);
            if (targetLoc == null) {
                ctx.hasBorrowedTool = false;
                return;
            }
            IsoNPC npc = ctx.npc;
            int curZ = EmberTargetProjector.levelOf(npc.getZ());
            double dist = Math.hypot((targetX + 0.5) - npc.getX(), (targetY + 0.5) - npc.getY());
            if (curZ == targetZ && dist <= 2.2) {
                ReturnToolAction.tryDepositToSourceSquare(npc, targetLoc, targetX, targetY, targetZ);
                ctx.hasBorrowedTool = (ReturnToolAction.findBorrowedTool(npc) != null);
                hasTarget = ctx.hasBorrowedTool;
                return;
            }
            if (!moveIssued) {
                moveIssued = true;
                ctx.requestMoveTo(targetX + 0.5, targetY + 0.5, targetZ, false);
            } else {
                EmberLocomotionController loco = npc.getLocomotion();
                if (!loco.isMoving() && !npc.getPosture().isBlockingLocomotion()) {
                    // 到达投影边缘但尚差半步或路径中断，直接尝试交付并清戳防死锁
                    ReturnToolAction.tryDepositToSourceSquare(npc, targetLoc, targetX, targetY, targetZ);
                    ctx.hasBorrowedTool = (ReturnToolAction.findBorrowedTool(npc) != null);
                    hasTarget = ctx.hasBorrowedTool;
                }
            }
        }
    }

    private static final class WorkDutyNode extends StateNode {
        private final WorkDutyProvider provider;

        WorkDutyNode(WorkDutyProvider provider) {
            super("WORK_DUTY", null, null, 0.0, false);
            this.provider = provider;
        }

        @Override
        public boolean isPreemptive(BrainContext ctx) {
            return provider.isUrgentWork(ctx);
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            return provider.canEnterWork(ctx);
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            return provider.canContinueWork(ctx);
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            ctx.working = true;
            scope.defer(() -> ctx.working = false);
            provider.onEnterWork(ctx, scope);
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            provider.onTickWork(ctx, scope, dtSim);
        }

        @Override
        public void onExit(BrainContext ctx) {
            ctx.working = false;
            provider.onExitWork(ctx);
        }
    }

    /** 闲暇座椅休息叶：申领 {@code SeatSmartSlot} + 挂载 {@code TEMP_PROP} 视觉道具 + 12s 最短驻留防抖。 */
    private static final class SeatLeisureNode extends FurnitureSlotNode {
        private static final double MIN_DWELL_SEC = 12.0;
        private static final double MAX_SESSION_SEC = 22.0;
        private static final double COOLDOWN_AFTER_EXIT_SEC = 8.0;

        private boolean tempPropEquipped;
        private double cooldownUntilSimTime;

        SeatLeisureNode() {
            super("SEAT_REST", SlotKind.SEAT, 45.0, MIN_DWELL_SEC, false);
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            if (ctx.npc == null || ctx.settlementId == null || ctx.simTime < cooldownUntilSimTime) {
                return false;
            }
            if (ctx.npc.getLocomotion().isMoving()
                    || ctx.npc.getFollow().isEnabled()
                    || ctx.npc.getPosture().isActive()) {
                return false;
            }
            return canAcquireFurnitureSlot(ctx, SlotKind.SEAT);
        }

        @Override
        protected boolean issuePostureCommand(IsoNPC npc, IsoObject obj) {
            return npc.getPosture().commandSitOnFurniture(npc, obj);
        }

        @Override
        protected boolean checkSlotStillNeeded(BrainContext ctx) {
            return (ctx.simTime - enteredSimTime) < MAX_SESSION_SEC;
        }

        @Override
        protected void onBeforeRelease(IsoNPC npc) {
            TempPropHelper.cleanupAllTempProps(npc);
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            this.tempPropEquipped = false;
            super.onEnter(ctx, scope);
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            super.onTick(ctx, scope, dtSim);
            if (ctx.npc == null) {
                return;
            }
            if (!tempPropEquipped
                    && ctx.npc.getPosture().getState() == NpcPostureController.PostureState.SEATED) {
                TempPropHelper.equipTempBookProp(ctx.npc);
                tempPropEquipped = true;
            }
        }

        @Override
        public void onExit(BrainContext ctx) {
            this.cooldownUntilSimTime = ctx.simTime + COOLDOWN_AFTER_EXIT_SEC;
            this.tempPropEquipped = false;
            super.onExit(ctx);
        }
    }

    /** 若 NPC 被指派了所属据点且当前身处领地外（例如切回据点自治模式后），自动经起身屏障步行返回据点。 */
    private static final class ReturnToSettlementNode extends StateNode {
        private String targetSettlementId;
        private int anchorX;
        private int anchorY;
        private double nextRetrySimTime;

        ReturnToSettlementNode() {
            super("RETURN_TO_SETTLEMENT", null, null, 0.0, false);
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            if (ctx.npc == null || ctx.settlementId == null || ctx.npc.getFollow().isEnabled()) {
                return false;
            }
            int nx = (int) Math.floor(ctx.npc.getX());
            int ny = (int) Math.floor(ctx.npc.getY());
            if (WarehouseFastIndex.containsPoint(ctx.settlementId, nx, ny)) {
                return false;
            }
            return WarehouseFastIndex.getSettlementAnchorTile(ctx.settlementId) != null;
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            return canEnter(ctx);
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            ctx.working = false;
            this.targetSettlementId = ctx.settlementId;
            this.nextRetrySimTime = 0.0;
            int[] anchor = WarehouseFastIndex.getSettlementAnchorTile(ctx.settlementId);
            if (anchor == null) {
                return;
            }
            this.anchorX = anchor[0];
            this.anchorY = anchor[1];
            ctx.requestMoveTo(anchorX + 0.5, anchorY + 0.5, 0.0, false);
            deferStopMove(ctx, scope);
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            if (ctx.npc == null || ctx.settlementId == null) {
                return;
            }
            if (!ctx.settlementId.equals(this.targetSettlementId)) {
                int[] anchor = WarehouseFastIndex.getSettlementAnchorTile(ctx.settlementId);
                if (anchor != null) {
                    this.targetSettlementId = ctx.settlementId;
                    this.anchorX = anchor[0];
                    this.anchorY = anchor[1];
                    ctx.requestMoveTo(anchorX + 0.5, anchorY + 0.5, 0.0, false);
                    this.nextRetrySimTime = ctx.simTime + 2.5;
                }
                return;
            }
            if (!ctx.npc.getLocomotion().isMoving()
                    && !ctx.npc.getPosture().isBlockingLocomotion()
                    && ctx.simTime >= this.nextRetrySimTime) {
                ctx.requestMoveTo(anchorX + 0.5, anchorY + 0.5, 0.0, false);
                this.nextRetrySimTime = ctx.simTime + 3.0;
            }
        }
    }

    private static final class IdleStandNode extends StateNode {
        IdleStandNode() {
            super("IDLE_STAND", null, null, 0.0, false);
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            ctx.working = false;
        }
    }
}
