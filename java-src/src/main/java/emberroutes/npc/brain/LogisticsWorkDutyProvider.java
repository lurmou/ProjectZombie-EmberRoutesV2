package emberroutes.npc.brain;

import emberroutes.npc.EmberLocomotionController;
import emberroutes.npc.EmberTargetProjector;
import emberroutes.npc.IsoNPC;
import emberroutes.npc.NpcPostureController.PostureState;
import emberroutes.npc.brain.BatchContainerTransferAction.TransferMode;
import emberroutes.npc.brain.BatchTourBuilder.TourAnchor;
import emberroutes.npc.brain.BatchTourBuilder.TourPlan;
import emberroutes.npc.brain.BatchTourBuilder.TourStop;
import emberroutes.npc.brain.DeliveryOrderManager.DeliveryOrder;
import emberroutes.npc.brain.NeedsPolicy.UrgentNeed;
import emberroutes.npc.brain.SettlementTaskAuctioneer.Assignment;
import emberroutes.npc.brain.SettlementTaskAuctioneer.Bidder;
import emberroutes.npc.brain.SettlementTaskAuctioneer.TaskKind;
import emberroutes.npc.brain.SettlementTaskAuctioneer.TaskSlice;
import emberroutes.warehouse.RoomConnectivityIndex;
import emberroutes.warehouse.SmartItemPicker;
import emberroutes.warehouse.SmartItemPicker.EquippedBagSpec;
import emberroutes.warehouse.SmartItemPicker.PickedItemRef;
import emberroutes.warehouse.SmartObjectRegistry;
import emberroutes.warehouse.SmartSlot;
import emberroutes.warehouse.WarehouseFastIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;

/**
 * M3-② {@code SETTLEMENT} 物流与实体配送大脑供给器（挂接 {@link WorkDutyProvider}）：
 * <ul>
 *   <li>每帧在 {@code NpcManager.tickBrains} 前统一执行一次 {@link #tickSettlementAuctions} 全局边际成本拍卖；</li>
 *   <li>支持空闲 NPC 即时接单 + {@code remainingEtaSec <= 2.5s} 在途 NPC Lookahead 零空驶接力（单槽 {@code chainedNextScope} 6s TTL）；</li>
 *   <li>严格通过 {@link BatchContainerTransferAction} 真实搬运原件，支持目的地满仓换箱/退回源箱、提货外扩 15 格降级转存 {@code PICKUP_OUT} 与 8 方位退避换侧。</li>
 * </ul>
 */
public final class LogisticsWorkDutyProvider implements WorkDutyProvider {
    public static final LogisticsWorkDutyProvider INSTANCE = new LogisticsWorkDutyProvider();
    private static final double CONTAINER_CLOSE_DIST = 1.15;
    private static final double STAND_TILE_ARRIVE_TOL = 0.35;
    private static final double CONTAINER_MAX_REACH_DIST = 1.50;
    private static final double SOURCE_NO_MATCH_COOLDOWN_SEC = 2.0;
    private static final double PLAYER_REORDER_HYSTERESIS_TILES = 6.0;

    private enum WorkPhase {
        NONE,
        MOVING_TO_PICKUP,
        LOADING,
        MOVING_TO_DROPOFF,
        UNLOADING,
        MOVING_TO_PLAYER,
        HANDOVER,
        FALLBACK_PICKUP_OUT,
        RETURNING_LEFTOVER
    }

    private static final class DeliveryPickupGroup {
        final String containerId;
        final int x;
        final int y;
        final int z;
        final int standX;
        final int standY;
        final int standDirIdx;
        final List<InventoryItem> items;

        DeliveryPickupGroup(
                String containerId,
                int x,
                int y,
                int z,
                int standX,
                int standY,
                int standDirIdx,
                List<InventoryItem> items) {
            this.containerId = containerId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.standX = standX;
            this.standY = standY;
            this.standDirIdx = standDirIdx;
            this.items = items;
        }
    }

    private static final class DeliveryPlan {
        final List<String> orderIds;
        final String settlementId;
        final int playerIndex;
        final String targetPickupOutContainerId;
        final boolean directToPickupOut;
        final List<DeliveryPickupGroup> pickupGroups;
        final List<InventoryItem> carriedItems = new ArrayList<>();
        final Map<InventoryItem, ItemContainer> loadTargetsByItem = new IdentityHashMap<>();
        final Map<InventoryItem, String> orderIdByItem = new IdentityHashMap<>();
        final SmartItemPicker.CarryBudget carryBudget;
        final List<ItemContainer> bagContainers;
        final Set<Integer> pickedItemKeys;
        final Set<String> chosenContainerIds;
        double totalWeight;
        final TourAnchor terminalAnchor;
        double lastOrderDestX;
        double lastOrderDestY;
        int lastOrderDestZ;

        DeliveryPlan(
                List<String> orderIds,
                String settlementId,
                int playerIndex,
                String targetPickupOutContainerId,
                List<DeliveryPickupGroup> pickupGroups,
                double totalWeight,
                TourAnchor terminalAnchor,
                double destX,
                double destY,
                int destZ,
                SmartItemPicker.CarryBudget carryBudget,
                List<ItemContainer> bagContainers,
                Set<Integer> pickedItemKeys,
                Set<String> chosenContainerIds) {
            this.orderIds = orderIds;
            this.settlementId = settlementId;
            this.playerIndex = playerIndex;
            this.targetPickupOutContainerId = targetPickupOutContainerId;
            this.directToPickupOut = targetPickupOutContainerId != null && !targetPickupOutContainerId.isBlank();
            this.pickupGroups = new ArrayList<>(pickupGroups);
            this.totalWeight = totalWeight;
            this.terminalAnchor = terminalAnchor;
            this.lastOrderDestX = destX;
            this.lastOrderDestY = destY;
            this.lastOrderDestZ = destZ;
            this.carryBudget = carryBudget;
            this.bagContainers = bagContainers;
            this.pickedItemKeys = pickedItemKeys;
            this.chosenContainerIds = chosenContainerIds;
        }
    }

    private static final class NpcWorkState {
        TaskSlice activeSlice;
        TourPlan haulTour;
        DeliveryPlan deliveryPlan;
        WorkPhase phase = WorkPhase.NONE;
        int stopIdx;
        BatchContainerTransferAction activeTransfer;
        SelfInventoryTransferAction activeSelfPack;
        long activeMoveSeq = -1L;
        String currentTargetContainerId;
        int targetX;
        int targetY;
        int targetZ;
        int standDirIdx = -1;
        int sideRetries;
        int playerMoveRetries;
        boolean moveIssued;

        double remainingEtaSec;
        boolean wasIdleLastTick = true;
        boolean wasLookaheadEligible;
        boolean hasTerminalAnchor;
        int terminalX;
        int terminalY;
        int terminalZ;
        long terminalRoomId = RoomConnectivityIndex.UNKNOWN_ROOM_ID;

        TaskSlice nextChainedSlice;
        TourPlan nextChainedHaulTour;
        DeliveryPlan nextChainedDeliveryPlan;
        double chainedExpiresSimTime;

        final List<InventoryItem> carriedHaulItems = new ArrayList<>();
        final List<InventoryItem> fallbackItems = new ArrayList<>();
        SmartSlot fallbackDestSlot;
        SmartSlot fallbackPickupOutSlot;
        String returnSourceContainerId;
        int returnSourceX;
        int returnSourceY;
        int returnSourceZ;
        double fullInventoryCooldownUntil;
    }

    private static final SettlementTaskAuctioneer AUCTIONEER = new SettlementTaskAuctioneer();
    private static final Map<String, NpcWorkState> WORK_STATES = new HashMap<>();
    private static final Map<String, Double> UNMATCHABLE_SOURCE_COOLDOWN = new HashMap<>();
    private static final List<Assignment> REUSABLE_ASSIGNMENTS = new ArrayList<>(16);

    private LogisticsWorkDutyProvider() {}

    public static synchronized void reset() {
        WORK_STATES.clear();
        UNMATCHABLE_SOURCE_COOLDOWN.clear();
        REUSABLE_ASSIGNMENTS.clear();
        AUCTIONEER.reset();
        DeliveryOrderManager.reset();
    }

    private static NpcWorkState stateOf(String npcId) {
        return WORK_STATES.computeIfAbsent(npcId, k -> new NpcWorkState());
    }

    /**
     * 每帧由 {@code NpcManager.tickBrains} 在调度各 NPC 状态树前统一调用：
     * 维护 Lookahead 6s 预锁 TTL、边缘触发判定并按 {@code 250ms} 节拍门控执行全局拍卖。
     */
    public static synchronized void tickSettlementAuctions(List<IsoNPC> liveNpcs, double simTime) {
        SmartObjectRegistry.setCurrentSimTime(simTime);
        if (liveNpcs == null || liveNpcs.isEmpty()) {
            return;
        }

        Map<String, List<IsoNPC>> npcsBySettlement = new HashMap<>();
        for (int i = 0, n = liveNpcs.size(); i < n; i++) {
            IsoNPC npc = liveNpcs.get(i);
            if (npc == null || npc.isDead() || npc.getBrain().getMode() != BrainMode.SETTLEMENT) {
                continue;
            }
            BrainContext ctx = npc.getBrainContext();
            if (ctx.settlementId == null || ctx.settlementId.isEmpty()) {
                continue;
            }
            npcsBySettlement.computeIfAbsent(ctx.settlementId, k -> new ArrayList<>()).add(npc);
        }

        for (Map.Entry<String, List<IsoNPC>> entry : npcsBySettlement.entrySet()) {
            String sid = entry.getKey();
            List<IsoNPC> npcs = entry.getValue();
            SmartObjectRegistry.refreshTimeBasedStates(sid, simTime);
            processSettlementAuction(sid, npcs, simTime);
        }
    }

    private static void processSettlementAuction(String settlementId, List<IsoNPC> npcs, double simTime) {
        boolean hasEdgeTrigger = false;
        Set<String> claimedSourceSlots = new HashSet<>();
        Set<String> claimedDeliveryOrders = new HashSet<>();

        for (int i = 0, n = npcs.size(); i < n; i++) {
            IsoNPC npc = npcs.get(i);
            NpcWorkState st = stateOf(npc.getNpcId());

            if (st.activeSlice != null && st.haulTour == null && st.deliveryPlan == null
                    && !npc.getBrain().getActivePathString().endsWith("WORK_DUTY")) {
                // 上一拍刚获胜但本拍被高优威胁/生理需求抢占未能进入 WORK_DUTY，释放占位以便重新竞拍
                if (st.activeSlice.deliveryOrderId != null) {
                    DeliveryOrderManager.requeueIfInterrupted(st.activeSlice.deliveryOrderId, npc.getNpcId());
                }
                st.activeSlice = null;
                st.phase = WorkPhase.NONE;
            }

            if (st.nextChainedSlice != null && simTime > st.chainedExpiresSimTime) {
                npc.getBrain().getActiveRunner().closeAndResetChainedScope();
                clearChainedPlan(st);
                SmartObjectRegistry.markAuctionDirty(settlementId);
            }

            if (st.activeSlice != null) {
                st.remainingEtaSec = estimateRemainingEta(npc, st);
                st.wasIdleLastTick = false;
                if (st.remainingEtaSec <= SettlementTaskAuctioneer.LOOKAHEAD_WINDOW_SEC
                        && st.hasTerminalAnchor
                        && st.nextChainedSlice == null
                        && !st.wasLookaheadEligible) {
                    st.wasLookaheadEligible = true;
                    hasEdgeTrigger = true;
                }
                if (st.activeSlice.sourceSlotId != null) {
                    claimedSourceSlots.add(st.activeSlice.sourceSlotId);
                }
                if (st.activeSlice.deliveryOrderId != null) {
                    claimedDeliveryOrders.add(st.activeSlice.deliveryOrderId);
                }
                if (st.deliveryPlan != null) {
                    claimedDeliveryOrders.addAll(st.deliveryPlan.orderIds);
                }
            } else {
                if (!st.wasIdleLastTick) {
                    hasEdgeTrigger = true;
                    st.wasIdleLastTick = true;
                }
                st.wasLookaheadEligible = false;
            }

            if (st.nextChainedSlice != null) {
                if (st.nextChainedSlice.sourceSlotId != null) {
                    claimedSourceSlots.add(st.nextChainedSlice.sourceSlotId);
                }
                if (st.nextChainedSlice.deliveryOrderId != null) {
                    claimedDeliveryOrders.add(st.nextChainedSlice.deliveryOrderId);
                }
                if (st.nextChainedDeliveryPlan != null) {
                    claimedDeliveryOrders.addAll(st.nextChainedDeliveryPlan.orderIds);
                }
            }
        }

        List<DeliveryOrder> pendingOrders = DeliveryOrderManager.getPendingOrders(settlementId);
        boolean hasPendingOrders = !pendingOrders.isEmpty();
        boolean dirty = SmartObjectRegistry.isAuctionDirty(settlementId) || hasPendingOrders;
        if (!AUCTIONEER.canTriggerAuction(settlementId, simTime, dirty, hasEdgeTrigger)) {
            return;
        }

        List<Bidder> bidders = new ArrayList<>();
        Map<String, IsoNPC> npcById = new HashMap<>();
        for (int i = 0, n = npcs.size(); i < n; i++) {
            IsoNPC npc = npcs.get(i);
            BrainContext ctx = npc.getBrainContext();
            PostureState posture = npc.getPosture().getState();
            if (ctx.threatDetected
                    || ctx.urgentNeed != UrgentNeed.NONE
                    || ctx.hasBorrowedTool
                    || posture == null
                    || !SettlementTaskAuctioneer.isBidderPostureEligible(posture.name())) {
                continue;
            }
            if (!hasPendingOrders && npc.getBrain().getActiveRunner().isMinDwellActive(simTime)) {
                continue;
            }
            NpcWorkState st = stateOf(npc.getNpcId());
            npcById.put(npc.getNpcId(), npc);

            if (st.activeSlice == null && st.phase == WorkPhase.NONE) {
                if (simTime < st.fullInventoryCooldownUntil) {
                    continue;
                }
                if (!canFitMoreAfterPacking(npc)) {
                    continue;
                }
                IsoGridSquare curSq = npc.getCurrentSquare();
                long roomId = curSq != null ? curSq.getRoomID() : RoomConnectivityIndex.UNKNOWN_ROOM_ID;
                bidders.add(new Bidder(
                        npc.getNpcId(),
                        npc.getX(),
                        npc.getY(),
                        EmberTargetProjector.levelOf(npc.getZ()),
                        roomId,
                        0.0,
                        1.0,
                        false,
                        false));
            } else if (st.haulTour != null
                    && st.deliveryPlan == null
                    && st.nextChainedSlice == null
                    && st.hasTerminalAnchor
                    && st.remainingEtaSec <= SettlementTaskAuctioneer.LOOKAHEAD_WINDOW_SEC) {
                bidders.add(new Bidder(
                        npc.getNpcId(),
                        st.terminalX + 0.5,
                        st.terminalY + 0.5,
                        st.terminalZ,
                        st.terminalRoomId,
                        st.remainingEtaSec,
                        1.0,
                        false,
                        true));
            }
        }

        if (bidders.isEmpty()) {
            return;
        }

        List<TaskSlice> tasks = collectCandidateTasks(
                settlementId, pendingOrders, claimedSourceSlots, claimedDeliveryOrders, simTime);
        if (tasks.isEmpty()) {
            SmartObjectRegistry.consumeAuctionDirty(settlementId);
            return;
        }

        SmartObjectRegistry.consumeAuctionDirty(settlementId);
        AUCTIONEER.runAuction(settlementId, bidders, tasks, simTime, REUSABLE_ASSIGNMENTS);

        for (int i = 0, n = REUSABLE_ASSIGNMENTS.size(); i < n; i++) {
            Assignment asg = REUSABLE_ASSIGNMENTS.get(i);
            IsoNPC winner = npcById.get(asg.bidder().npcId);
            if (winner == null) {
                continue;
            }
            NpcWorkState st = stateOf(winner.getNpcId());
            if (asg.bidder().lookahead) {
                mountChainedWinner(winner, st, asg.task(), simTime);
            } else {
                if (asg.task().kind != TaskKind.DELIVERY_ORDER
                        && winner.getBrain().getActiveRunner().isMinDwellActive(simTime)) {
                    continue;
                }
                mountImmediateWinner(winner, st, asg.task(), simTime);
            }
        }
    }

    private static List<TaskSlice> collectCandidateTasks(
            String settlementId,
            List<DeliveryOrder> pendingOrders,
            Set<String> claimedSourceSlots,
            Set<String> claimedDeliveryOrders,
            double simTime) {
        List<TaskSlice> tasks = new ArrayList<>();

        for (int i = 0, n = pendingOrders.size(); i < n; i++) {
            DeliveryOrder ord = pendingOrders.get(i);
            if (claimedDeliveryOrders.contains(ord.orderId)) {
                continue;
            }
            List<WarehouseFastIndex.LoadedSource> sources =
                    WarehouseFastIndex.getLoadedSourcesByType(settlementId, ord.fullType);
            if (sources.isEmpty()) {
                continue;
            }
            WarehouseFastIndex.LoadedSource first = sources.get(0);
            SmartSlot srcSlot = SmartObjectRegistry.getContainerSlot(settlementId, first.containerId());
            long roomId = srcSlot != null ? srcSlot.getRoomId() : RoomConnectivityIndex.UNKNOWN_ROOM_ID;
            tasks.add(new TaskSlice(
                    "delivery:" + ord.orderId,
                    TaskKind.DELIVERY_ORDER,
                    settlementId,
                    first.containerId(),
                    first.x(),
                    first.y(),
                    first.z(),
                    roomId,
                    ord.createdSimTime,
                    4.0,
                    ord.orderId));
        }

        List<SmartSlot> haulSlots = SmartObjectRegistry.getAdvertisedHaulOutSlots(settlementId);
        for (int i = 0, n = haulSlots.size(); i < n; i++) {
            SmartSlot slot = haulSlots.get(i);
            if (claimedSourceSlots.contains(slot.slotId)) {
                continue;
            }
            Double cooldownUntil = UNMATCHABLE_SOURCE_COOLDOWN.get(slot.slotId);
            if (cooldownUntil != null && simTime < cooldownUntil.doubleValue()) {
                continue;
            }
            double execEst = BatchTourBuilder.computeDampedTransferDuration(slot.getUnreservedOutItemCount()) * 2.0;
            tasks.add(new TaskSlice(
                    "haul:" + slot.slotId,
                    TaskKind.HAUL_BATCH,
                    settlementId,
                    slot.slotId,
                    slot.originX,
                    slot.originY,
                    slot.originZ,
                    slot.getRoomId(),
                    simTime,
                    execEst,
                    null));
        }
        return tasks;
    }

    private static void mountImmediateWinner(IsoNPC npc, NpcWorkState st, TaskSlice task, double simTime) {
        if (task.kind == TaskKind.DELIVERY_ORDER && task.deliveryOrderId != null) {
            if (!DeliveryOrderManager.markAssigned(task.deliveryOrderId, npc.getNpcId())) {
                return;
            }
        }
        st.activeSlice = task;
        st.haulTour = null;
        st.deliveryPlan = null;
        st.phase = WorkPhase.MOVING_TO_PICKUP;
        st.stopIdx = 0;
        st.moveIssued = false;
        st.sideRetries = 0;
        st.playerMoveRetries = 0;
    }

    private static void mountChainedWinner(IsoNPC npc, NpcWorkState st, TaskSlice task, double simTime) {
        if (task.kind == TaskKind.DELIVERY_ORDER) {
            return;
        }
        StateScope chained = npc.getBrain().getActiveRunner().getChainedNextScope();
        float anchorX = st.hasTerminalAnchor ? (st.terminalX + 0.5F) : npc.getX();
        float anchorY = st.hasTerminalAnchor ? (st.terminalY + 0.5F) : npc.getY();
        TourPlan plan = buildTourForSlice(npc, task, anchorX, anchorY, simTime, chained::defer);
        if (plan == null) {
            UNMATCHABLE_SOURCE_COOLDOWN.put(task.sourceSlotId, Double.valueOf(simTime + SOURCE_NO_MATCH_COOLDOWN_SEC));
            return;
        }
        st.nextChainedSlice = task;
        st.nextChainedHaulTour = plan;
        st.nextChainedDeliveryPlan = null;
        st.chainedExpiresSimTime = simTime + SettlementTaskAuctioneer.CHAINED_PRELOCK_TTL_SEC;
    }

    private static TourPlan buildTourForSlice(
            IsoNPC npc,
            TaskSlice task,
            float fromX,
            float fromY,
            double simTime,
            Consumer<Runnable> scopeRegistrar) {
        SmartSlot srcSlot = SmartObjectRegistry.getSlot(task.settlementId, task.sourceSlotId);
        if (srcSlot == null) {
            return null;
        }
        List<ItemContainer> bagContainers = new ArrayList<>(2);
        List<EquippedBagSpec> bagSpecs = new ArrayList<>(2);
        double currentEff = prepareProjectedCarryState(npc, bagContainers, bagSpecs);
        double maxWeight = npc.getMaxWeight();
        double noDamageLimit = SmartItemPicker.computeNoDamageWeightLimit(maxWeight);
        double estHaulWeight = estimateHaulSourceWeight(srcSlot);
        boolean preferBags = !bagSpecs.isEmpty()
                && !Double.isInfinite(maxWeight)
                && (currentEff + estHaulWeight > noDamageLimit + 1.0E-4
                        || currentEff + estHaulWeight > SmartItemPicker.MAIN_INVENTORY_HARD_CAP + 1.0E-4);
        SmartItemPicker.CarryBudget budget = new SmartItemPicker.CarryBudget(
                currentEff, maxWeight, bagSpecs, preferBags);
        if (!budget.canFitAnyMinWeight()) {
            return null;
        }
        return BatchTourBuilder.buildHaulTour(
                task.settlementId,
                srcSlot,
                budget,
                bagContainers,
                npc.getNpcId(),
                fromX,
                fromY,
                simTime,
                scopeRegistrar);
    }

    private static double getNpcEffectiveWeight(IsoNPC npc) {
        if (npc == null) {
            return 0.0;
        }
        return npc.getInventory() != null ? npc.getInventory().getCapacityWeight() : npc.getInventoryWeight();
    }

    private static boolean canFitMoreAfterPacking(IsoNPC npc) {
        if (npc == null || Double.isInfinite(npc.getMaxWeight())) {
            return true;
        }
        if (getNpcEffectiveWeight(npc) + 0.05 <= SmartItemPicker.MAIN_INVENTORY_HARD_CAP) {
            return true;
        }
        return !planPackIntoEquippedBags(npc).isEmpty();
    }

    private static double prepareProjectedCarryState(
            IsoNPC npc,
            List<ItemContainer> outBagContainers,
            List<EquippedBagSpec> outBagSpecs) {
        List<EquippedBagSpec> baseSpecs = collectEquippedBags(npc, outBagContainers);
        double[] effOut = new double[]{getNpcEffectiveWeight(npc)};
        planPackIntoEquippedBags(npc, outBagContainers, baseSpecs, outBagSpecs, effOut);
        return effOut[0];
    }

    /**
     * 规划将主物品栏中非穿戴/非手持/非借用工具/非临时道具的散件迁入已装备减重背包的列表，
     * 不在规划阶段直接瞬移变更容器，由 {@link SelfInventoryTransferAction} 按原版 {@code TransferItemOnSelf} 读条执行。
     */
    private static List<SelfInventoryTransferAction.TransferEntry> planPackIntoEquippedBags(IsoNPC npc) {
        List<ItemContainer> bagContainers = new ArrayList<>(2);
        List<EquippedBagSpec> baseSpecs = collectEquippedBags(npc, bagContainers);
        return planPackIntoEquippedBags(npc, bagContainers, baseSpecs, null, null);
    }

    private static List<SelfInventoryTransferAction.TransferEntry> planPackIntoEquippedBags(
            IsoNPC npc,
            List<ItemContainer> bagContainers,
            List<EquippedBagSpec> bagSpecs,
            List<EquippedBagSpec> outAdjustedSpecs,
            double[] outEffWeight) {
        double effNow = getNpcEffectiveWeight(npc);
        if (npc == null || npc.isDead() || npc.getInventory() == null) {
            if (outAdjustedSpecs != null) {
                outAdjustedSpecs.addAll(bagSpecs);
            }
            if (outEffWeight != null && outEffWeight.length > 0) {
                outEffWeight[0] = effNow;
            }
            return List.of();
        }
        double maxWeight = npc.getMaxWeight();
        double noDamageLimit = Double.isInfinite(maxWeight)
                ? Double.POSITIVE_INFINITY
                : SmartItemPicker.computeNoDamageWeightLimit(maxWeight);
        if (Double.isInfinite(maxWeight)
                || (effNow < noDamageLimit && effNow < SmartItemPicker.MAIN_INVENTORY_HARD_CAP - 5.0)
                || bagSpecs.isEmpty()) {
            if (outAdjustedSpecs != null) {
                outAdjustedSpecs.addAll(bagSpecs);
            }
            if (outEffWeight != null && outEffWeight.length > 0) {
                outEffWeight[0] = effNow;
            }
            return List.of();
        }
        ItemContainer mainInv = npc.getInventory();
        List<InventoryItem> rawMain = mainInv.getItems();
        if (rawMain == null || rawMain.isEmpty()) {
            if (outAdjustedSpecs != null) {
                outAdjustedSpecs.addAll(bagSpecs);
            }
            if (outEffWeight != null && outEffWeight.length > 0) {
                outEffWeight[0] = effNow;
            }
            return List.of();
        }

        List<InventoryItem> movable = new ArrayList<>();
        for (int i = 0, n = rawMain.size(); i < n; i++) {
            InventoryItem it = rawMain.get(i);
            if (it == null
                    || npc.isEquipped(it)
                    || npc.isAttachedItem(it)
                    || it instanceof InventoryContainer
                    || it.isFavorite()
                    || TempPropHelper.isTempPropItem(it)) {
                continue;
            }
            if (it.hasModData() && it.getModData().rawget(ReturnToolAction.MODDATA_BORROWED_FROM_KEY) != null) {
                continue;
            }
            movable.add(it);
        }
        if (movable.isEmpty()) {
            if (outAdjustedSpecs != null) {
                outAdjustedSpecs.addAll(bagSpecs);
            }
            if (outEffWeight != null && outEffWeight.length > 0) {
                outEffWeight[0] = effNow;
            }
            return List.of();
        }

        movable.sort((a, b) -> Double.compare(b.getUnequippedWeight(), a.getUnequippedWeight()));
        List<EquippedBagSpec> sortedBags = new ArrayList<>(bagSpecs);
        sortedBags.sort((a, b) -> Integer.compare(b.weightReduction(), a.weightReduction()));
        double[] bagFree = new double[bagContainers.size()];
        for (int i = 0; i < bagContainers.size(); i++) {
            bagFree[i] = Math.max(0.0, bagContainers.get(i).getFreeCapacity(npc));
        }

        List<SelfInventoryTransferAction.TransferEntry> plan = new ArrayList<>();
        for (int i = 0, n = movable.size(); i < n; i++) {
            if (effNow < noDamageLimit * 0.90 && effNow <= SmartItemPicker.MAIN_INVENTORY_HARD_CAP - 10.0) {
                break;
            }
            InventoryItem it = movable.get(i);
            if (!mainInv.contains(it)) {
                continue;
            }
            double w = Math.max(0.05, it.getUnequippedWeight());
            for (int bi = 0, bn = sortedBags.size(); bi < bn; bi++) {
                EquippedBagSpec spec = sortedBags.get(bi);
                int idx = spec.bagIndex();
                ItemContainer bagCont = bagContainers.get(idx);
                if (spec.maxItemSize() > 0.0 && w > spec.maxItemSize() + 1.0e-4) {
                    continue;
                }
                if (bagFree[idx] + 1.0e-4 >= w && bagCont.isItemAllowed(it)) {
                    bagFree[idx] -= w;
                    double reductionRatio = Math.max(0, Math.min(100, spec.weightReduction())) / 100.0;
                    effNow -= w * reductionRatio;
                    plan.add(new SelfInventoryTransferAction.TransferEntry(it, mainInv, bagCont));
                    break;
                }
            }
        }
        if (outAdjustedSpecs != null) {
            for (int i = 0, n = bagSpecs.size(); i < n; i++) {
                EquippedBagSpec orig = bagSpecs.get(i);
                int idx = orig.bagIndex();
                outAdjustedSpecs.add(new EquippedBagSpec(
                        idx, bagFree[idx], orig.weightReduction(), orig.maxItemSize()));
            }
        }
        if (outEffWeight != null && outEffWeight.length > 0) {
            outEffWeight[0] = Math.max(0.0, effNow);
        }
        return plan;
    }

    private static boolean tickOrStartSelfPackIfNeeded(BrainContext ctx, NpcWorkState st, double dtSim) {
        if (st.activeSelfPack != null) {
            st.activeSelfPack.tick(ctx, dtSim);
            if (st.activeSelfPack.isRunning()) {
                return true;
            }
            st.activeSelfPack = null;
            return false;
        }
        if (ctx != null && ctx.npc != null) {
            List<SelfInventoryTransferAction.TransferEntry> plan = planPackIntoEquippedBags(ctx.npc);
            if (!plan.isEmpty()) {
                st.activeSelfPack = new SelfInventoryTransferAction(plan);
                st.activeSelfPack.start(ctx);
                return st.activeSelfPack.isRunning();
            }
        }
        return false;
    }

    private static List<EquippedBagSpec> collectEquippedBags(IsoNPC npc, List<ItemContainer> outBagContainers) {
        if (npc == null || npc.getInventory() == null) {
            return List.of();
        }
        List<InventoryItem> items = npc.getInventory().getItems();
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<EquippedBagSpec> specs = null;
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem it = items.get(i);
            if (it == null || !npc.isEquipped(it) || !(it instanceof InventoryContainer bag)) {
                continue;
            }
            ItemContainer bagCont = bag.getInventory();
            if (bagCont == null) {
                continue;
            }
            if (specs == null) {
                specs = new ArrayList<>(2);
            }
            int bagIdx = outBagContainers.size();
            outBagContainers.add(bagCont);
            specs.add(new EquippedBagSpec(
                    bagIdx, bagCont.getFreeCapacity(npc), bag.getWeightReduction(), bag.getMaxItemSize()));
        }
        return specs != null ? specs : List.of();
    }

    private static double estimateHaulSourceWeight(SmartSlot srcSlot) {
        if (srcSlot == null) {
            return 0.0;
        }
        WarehouseFastIndex.LiveContainerRef ref =
                WarehouseFastIndex.resolveLiveContainer(srcSlot.getContainerId());
        if (ref == null || ref.container() == null) {
            return 0.0;
        }
        List<InventoryItem> rawItems = ref.container().getItems();
        if (rawItems == null || rawItems.isEmpty()) {
            return 0.0;
        }
        int maxCount = srcSlot.getUnreservedOutItemCount();
        int counted = 0;
        double est = 0.0;
        for (int i = 0, n = rawItems.size(); i < n && counted < maxCount; i++) {
            InventoryItem it = rawItems.get(i);
            if (it == null || it.isEquipped()) {
                continue;
            }
            est += Math.max(0.05, it.getUnequippedWeight());
            counted++;
        }
        return est;
    }

    private static double estimateOrderBatchWeight(String settlementId, List<DeliveryOrder> orders) {
        double est = 0.0;
        for (int i = 0, n = orders.size(); i < n; i++) {
            DeliveryOrder ord = orders.get(i);
            int rem = ord.getRemainingCount();
            if (rem <= 0) {
                continue;
            }
            double unitW = 0.5;
            List<WarehouseFastIndex.LoadedSource> sources =
                    WarehouseFastIndex.getLoadedSourcesByType(settlementId, ord.fullType);
            outer:
            for (int si = 0, sn = sources.size(); si < sn; si++) {
                WarehouseFastIndex.LiveContainerRef ref =
                        WarehouseFastIndex.resolveLiveContainer(sources.get(si).containerId());
                if (ref == null || ref.container() == null) {
                    continue;
                }
                List<InventoryItem> items = ref.container().getItems();
                if (items == null) {
                    continue;
                }
                for (int ii = 0, in = items.size(); ii < in; ii++) {
                    InventoryItem it = items.get(ii);
                    if (it != null && ord.fullType.equals(it.getFullType())) {
                        unitW = Math.max(0.05, it.getUnequippedWeight());
                        break outer;
                    }
                }
            }
            est += unitW * rem;
        }
        return est;
    }

    private static DeliveryPlan buildDeliveryPlanForSlice(
            IsoNPC npc,
            TaskSlice task,
            float fromX,
            float fromY,
            double simTime,
            Consumer<Runnable> scopeRegistrar) {
        DeliveryOrder seedOrder = DeliveryOrderManager.getOrder(task.deliveryOrderId);
        if (seedOrder == null || seedOrder.getRemainingCount() <= 0) {
            return null;
        }
        if (seedOrder.status == DeliveryOrderManager.OrderStatus.PENDING) {
            if (!DeliveryOrderManager.markAssigned(seedOrder.orderId, npc.getNpcId())) {
                return null;
            }
        } else if (seedOrder.status != DeliveryOrderManager.OrderStatus.ASSIGNED
                || !npc.getNpcId().equals(seedOrder.assignedNpcId)) {
            return null;
        }
        scopeRegistrar.accept(() -> DeliveryOrderManager.requeueIfInterrupted(seedOrder.orderId, npc.getNpcId()));

        int npcZ = EmberTargetProjector.levelOf(npc.getZ());
        double destX = fromX;
        double destY = fromY;
        int destZ = npcZ;
        TourAnchor anchor = null;
        if (seedOrder.targetPickupOutContainerId != null && !seedOrder.targetPickupOutContainerId.isBlank()) {
            SmartSlot targetSlot = SmartObjectRegistry.getContainerSlot(
                    task.settlementId, seedOrder.targetPickupOutContainerId);
            if (targetSlot != null) {
                destX = targetSlot.originX + 0.5;
                destY = targetSlot.originY + 0.5;
                destZ = targetSlot.originZ;
                anchor = new TourAnchor(
                        targetSlot.originX, targetSlot.originY, targetSlot.originZ, targetSlot.getRoomId());
            }
        }
        if (anchor == null && (seedOrder.targetPickupOutContainerId == null || seedOrder.targetPickupOutContainerId.isBlank())) {
            IsoPlayer player = resolvePlayer(seedOrder.playerIndex);
            if (player != null && !player.isDead()) {
                destX = player.getX();
                destY = player.getY();
                destZ = EmberTargetProjector.levelOf(player.getZ());
                IsoGridSquare pSq = player.getCurrentSquare();
                anchor = new TourAnchor(
                        (int) Math.floor(destX),
                        (int) Math.floor(destY),
                        destZ,
                        pSq != null ? pSq.getRoomID() : RoomConnectivityIndex.UNKNOWN_ROOM_ID);
            }
        }

        List<DeliveryOrder> candidateOrders = new ArrayList<>();
        candidateOrders.add(seedOrder);
        List<DeliveryOrder> pendingAll = DeliveryOrderManager.getPendingOrders(task.settlementId);
        for (int i = 0, n = pendingAll.size(); i < n; i++) {
            DeliveryOrder ord = pendingAll.get(i);
            if (ord == null || ord == seedOrder || ord.getRemainingCount() <= 0) {
                continue;
            }
            if (ord.playerIndex == seedOrder.playerIndex
                    && Objects.equals(ord.targetPickupOutContainerId, seedOrder.targetPickupOutContainerId)) {
                candidateOrders.add(ord);
            }
        }

        List<ItemContainer> bagContainers = new ArrayList<>(2);
        List<EquippedBagSpec> bagSpecs = new ArrayList<>(2);
        double currentEff = prepareProjectedCarryState(npc, bagContainers, bagSpecs);
        double maxWeight = npc.getMaxWeight();
        double noDamageLimit = SmartItemPicker.computeNoDamageWeightLimit(maxWeight);
        double estBatchWeight = estimateOrderBatchWeight(task.settlementId, candidateOrders);
        boolean preferBags = !bagSpecs.isEmpty()
                && !Double.isInfinite(maxWeight)
                && (currentEff + estBatchWeight > noDamageLimit + 1.0E-4
                        || currentEff + estBatchWeight > SmartItemPicker.MAIN_INVENTORY_HARD_CAP + 1.0E-4);
        SmartItemPicker.CarryBudget budget = new SmartItemPicker.CarryBudget(
                currentEff, maxWeight, bagSpecs, preferBags);
        if (!budget.canFitAnyMinWeight()) {
            return null;
        }

        Map<String, List<InventoryItem>> byContainer = new LinkedHashMap<>();
        Set<String> chosenContainerIds = new HashSet<>();
        Set<Integer> pickedItemKeys = new HashSet<>();
        Map<InventoryItem, ItemContainer> loadTargets = new IdentityHashMap<>();
        Map<InventoryItem, String> orderIdByItem = new IdentityHashMap<>();
        List<String> assignedOrderIds = new ArrayList<>();
        double totalWeight = 0.0;

        for (int ci = 0, cn = candidateOrders.size(); ci < cn; ci++) {
            DeliveryOrder ord = candidateOrders.get(ci);
            if (ord != seedOrder && !budget.canFitAnyMinWeight()) {
                break;
            }
            List<PickedItemRef> picked = SmartItemPicker.pickItemsForOrderWithBudget(
                    task.settlementId,
                    ord.fullType,
                    ord.getRemainingCount(),
                    npc.getNpcId(),
                    fromX,
                    fromY,
                    npcZ,
                    destX,
                    destY,
                    destZ,
                    chosenContainerIds,
                    pickedItemKeys,
                    simTime,
                    budget,
                    scopeRegistrar);
            if (picked.isEmpty()) {
                if (ord == seedOrder) {
                    return null;
                }
                continue;
            }
            if (ord != seedOrder) {
                if (!DeliveryOrderManager.markAssigned(ord.orderId, npc.getNpcId())) {
                    continue;
                }
                scopeRegistrar.accept(() -> DeliveryOrderManager.requeueIfInterrupted(ord.orderId, npc.getNpcId()));
            }
            assignedOrderIds.add(ord.orderId);
            for (int i = 0, n = picked.size(); i < n; i++) {
                PickedItemRef ref = picked.get(i);
                byContainer.computeIfAbsent(ref.containerId(), k -> new ArrayList<>()).add(ref.item());
                chosenContainerIds.add(ref.containerId());
                orderIdByItem.put(ref.item(), ord.orderId);
                totalWeight += Math.max(0.05, ref.item().getUnequippedWeight());
                if (ref.targetBagIndex() >= 0 && ref.targetBagIndex() < bagContainers.size()) {
                    loadTargets.put(ref.item(), bagContainers.get(ref.targetBagIndex()));
                }
            }
        }

        if (byContainer.isEmpty()) {
            return null;
        }

        List<DeliveryPickupGroup> groups = new ArrayList<>(byContainer.size());
        for (Map.Entry<String, List<InventoryItem>> e : byContainer.entrySet()) {
            SmartSlot slot = SmartObjectRegistry.getContainerSlot(task.settlementId, e.getKey());
            int cx = slot != null ? slot.originX : (int) Math.floor(fromX);
            int cy = slot != null ? slot.originY : (int) Math.floor(fromY);
            int cz = slot != null ? slot.originZ : npcZ;
            int[] stand = slot != null
                    ? SmartObjectRegistry.pickBestStandTile(slot, fromX, fromY, simTime)
                    : new int[]{cx, cy, -1};
            groups.add(new DeliveryPickupGroup(
                    e.getKey(), cx, cy, cz, stand[0], stand[1], stand[2], e.getValue()));
        }

        groups = BatchTourBuilder.orderStopsShortestPath(
                fromX, fromY, npcZ, destX, destY, destZ,
                groups, g -> g.standX, g -> g.standY, g -> g.z);

        if (anchor == null) {
            DeliveryPickupGroup last = groups.get(groups.size() - 1);
            anchor = new TourAnchor(last.standX, last.standY, last.z, RoomConnectivityIndex.UNKNOWN_ROOM_ID);
        }

        DeliveryPlan plan = new DeliveryPlan(
                assignedOrderIds,
                task.settlementId,
                seedOrder.playerIndex,
                seedOrder.targetPickupOutContainerId,
                groups,
                totalWeight,
                anchor,
                destX,
                destY,
                destZ,
                budget,
                bagContainers,
                pickedItemKeys,
                chosenContainerIds);
        plan.loadTargetsByItem.putAll(loadTargets);
        plan.orderIdByItem.putAll(orderIdByItem);
        return plan;
    }

    private static void updateTerminalAnchor(NpcWorkState st, TourAnchor anchor) {
        if (anchor != null) {
            st.hasTerminalAnchor = true;
            st.terminalX = anchor.x();
            st.terminalY = anchor.y();
            st.terminalZ = anchor.z();
            st.terminalRoomId = anchor.roomId();
        } else {
            st.hasTerminalAnchor = false;
        }
    }

    private static void clearChainedPlan(NpcWorkState st) {
        st.nextChainedSlice = null;
        st.nextChainedHaulTour = null;
        st.nextChainedDeliveryPlan = null;
        st.chainedExpiresSimTime = 0.0;
    }

    // ==================== WorkDutyProvider 状态机挂接实现 ====================

    @Override
    public boolean isUrgentWork(BrainContext ctx) {
        if (ctx == null || ctx.npcId == null) {
            return false;
        }
        NpcWorkState st = WORK_STATES.get(ctx.npcId);
        return st != null && st.activeSlice != null && st.activeSlice.kind == TaskKind.DELIVERY_ORDER;
    }

    @Override
    public boolean canEnterWork(BrainContext ctx) {
        if (ctx == null || ctx.npc == null || ctx.settlementId == null) {
            return false;
        }
        NpcWorkState st = WORK_STATES.get(ctx.npcId);
        return st != null && st.activeSlice != null && st.phase != WorkPhase.NONE;
    }

    @Override
    public boolean canContinueWork(BrainContext ctx) {
        return canEnterWork(ctx);
    }

    @Override
    public void onEnterWork(BrainContext ctx, StateScope scope) {
        if (ctx == null || ctx.npc == null) {
            return;
        }
        NpcWorkState st = stateOf(ctx.npcId);
        scope.defer(() -> cleanupWorkOnScopeExit(ctx, st));
        if (st.activeSlice != null && st.haulTour == null && st.deliveryPlan == null) {
            float anchorX = ctx.npc.getX();
            float anchorY = ctx.npc.getY();
            if (st.activeSlice.kind == TaskKind.HAUL_BATCH) {
                TourPlan plan = buildTourForSlice(
                        ctx.npc, st.activeSlice, anchorX, anchorY, ctx.simTime, scope::defer);
                if (plan == null) {
                    UNMATCHABLE_SOURCE_COOLDOWN.put(
                            st.activeSlice.sourceSlotId,
                            Double.valueOf(ctx.simTime + SOURCE_NO_MATCH_COOLDOWN_SEC));
                    st.activeSlice = null;
                    st.phase = WorkPhase.NONE;
                    return;
                }
                st.haulTour = plan;
                updateTerminalAnchor(st, plan.tourTerminalAnchor);
            } else {
                DeliveryPlan plan = buildDeliveryPlanForSlice(
                        ctx.npc, st.activeSlice, anchorX, anchorY, ctx.simTime, scope::defer);
                if (plan == null) {
                    st.activeSlice = null;
                    st.phase = WorkPhase.NONE;
                    return;
                }
                st.deliveryPlan = plan;
                updateTerminalAnchor(st, plan.terminalAnchor);
            }
        }
    }

    @Override
    public void onTickWork(BrainContext ctx, StateScope scope, double dtSim) {
        if (ctx == null || ctx.npc == null || ctx.npc.isDead()) {
            return;
        }
        NpcWorkState st = stateOf(ctx.npcId);
        if (st.activeSlice == null) {
            st.phase = WorkPhase.NONE;
            return;
        }

        if (st.haulTour != null) {
            tickHaulTour(ctx, st, scope, dtSim);
        } else if (st.deliveryPlan != null) {
            tickDeliveryPlan(ctx, st, scope, dtSim);
        } else {
            st.activeSlice = null;
            st.phase = WorkPhase.NONE;
        }
    }

    @Override
    public void onExitWork(BrainContext ctx) {
        // 清理全部托管在 StateScope LIFO 的 cleanupWorkOnScopeExit，保证无重复或遗漏
    }

    private static void cleanupWorkOnScopeExit(BrainContext ctx, NpcWorkState st) {
        if (st.activeSelfPack != null) {
            st.activeSelfPack.interrupt(ctx, "WORK_EXIT");
            st.activeSelfPack = null;
        }
        if (st.activeTransfer != null) {
            st.activeTransfer.interrupt(ctx, "WORK_EXIT");
            st.activeTransfer = null;
        }
        if (ctx.npc != null && !ctx.npc.isDead()) {
            ctx.npc.getPosture().clearPendingContinuation();
            ctx.npc.getLocomotion().stop(ctx.npc);
        }
        if (st.deliveryPlan != null) {
            requeueAllPlanOrders(st.deliveryPlan, ctx.npcId);
        }
        st.activeSlice = null;
        st.haulTour = null;
        st.deliveryPlan = null;
        st.phase = WorkPhase.NONE;
        st.stopIdx = 0;
        st.moveIssued = false;
        st.playerMoveRetries = 0;
        st.carriedHaulItems.clear();
        st.fallbackItems.clear();
        st.fallbackDestSlot = null;
        st.fallbackPickupOutSlot = null;
        st.hasTerminalAnchor = false;
        st.wasLookaheadEligible = false;
        st.remainingEtaSec = 0.0;
    }

    // ==================== 1. 批量巡回理货执行状态机 ====================

    private static void tickHaulTour(BrainContext ctx, NpcWorkState st, StateScope scope, double dtSim) {
        TourPlan tour = st.haulTour;
        switch (st.phase) {
            case MOVING_TO_PICKUP -> {
                if (st.activeSelfPack != null && tickOrStartSelfPackIfNeeded(ctx, st, dtSim)) {
                    return;
                }
                if (st.stopIdx >= tour.pickups.size()) {
                    st.phase = WorkPhase.MOVING_TO_DROPOFF;
                    st.stopIdx = 0;
                    st.moveIssued = false;
                    return;
                }
                TourStop stop = tour.pickups.get(st.stopIdx);
                if (advanceMoveToContainer(
                        ctx, st, tour.settlementId, stop.containerId,
                        stop.standX, stop.standY, stop.z, stop.standDirIdx)) {
                    if (tickOrStartSelfPackIfNeeded(ctx, st, dtSim)) {
                        return;
                    }
                    startTransfer(
                            ctx, st, TransferMode.LOAD_FROM_CONTAINER, stop.containerId, -1,
                            stop.items, tour.loadTargetsByItem, WorkPhase.LOADING);
                }
            }
            case LOADING -> {
                if (st.activeTransfer == null) {
                    st.phase = WorkPhase.MOVING_TO_DROPOFF;
                    st.stopIdx = 0;
                    return;
                }
                st.activeTransfer.tick(ctx, dtSim);
                if (st.activeTransfer.isRunning()) {
                    return;
                }
                List<InventoryItem> transferred = st.activeTransfer.getTransferredItems();
                List<InventoryItem> leftover = st.activeTransfer.getLeftoverItems();
                if (transferred.isEmpty() && !leftover.isEmpty()) {
                    st.fullInventoryCooldownUntil = ctx.simTime + 3.0;
                }
                st.carriedHaulItems.addAll(transferred);
                st.activeTransfer = null;
                st.stopIdx++;
                if (st.stopIdx < tour.pickups.size()) {
                    st.phase = WorkPhase.MOVING_TO_PICKUP;
                    st.moveIssued = false;
                } else if (st.carriedHaulItems.isEmpty()) {
                    finishOrChainNext(ctx, st);
                } else {
                    st.phase = WorkPhase.MOVING_TO_DROPOFF;
                    st.stopIdx = 0;
                    st.moveIssued = false;
                }
            }
            case MOVING_TO_DROPOFF -> {
                if (st.fallbackDestSlot != null) {
                    SmartSlot fb = st.fallbackDestSlot;
                    int[] stand = SmartObjectRegistry.pickBestStandTile(
                            fb, ctx.npc.getX(), ctx.npc.getY(), ctx.simTime);
                    if (advanceMoveToContainer(
                            ctx, st, tour.settlementId, fb.getContainerId(),
                            stand[0], stand[1], fb.originZ, stand[2])) {
                        startTransfer(ctx, st, TransferMode.UNLOAD_TO_CONTAINER, fb.getContainerId(), -1, st.fallbackItems, WorkPhase.UNLOADING);
                    }
                    return;
                }
                if (st.stopIdx >= tour.dropOffs.size()) {
                    finishOrChainNext(ctx, st);
                    return;
                }
                TourStop stop = tour.dropOffs.get(st.stopIdx);
                if (advanceMoveToContainer(
                        ctx, st, tour.settlementId, stop.containerId,
                        stop.standX, stop.standY, stop.z, stop.standDirIdx)) {
                    List<InventoryItem> carriedForStop = filterCurrentlyCarried(ctx.npc, stop.items);
                    if (carriedForStop.isEmpty()) {
                        st.stopIdx++;
                        st.moveIssued = false;
                        return;
                    }
                    startTransfer(ctx, st, TransferMode.UNLOAD_TO_CONTAINER, stop.containerId, -1, carriedForStop, WorkPhase.UNLOADING);
                }
            }
            case UNLOADING -> {
                List<InventoryItem> leftover = pollCompletedLeftovers(ctx, st, dtSim);
                if (leftover == null) {
                    return;
                }
                if (st.fallbackDestSlot == null && st.stopIdx >= tour.dropOffs.size() && leftover.isEmpty()) {
                    finishOrChainNext(ctx, st);
                    return;
                }

                if (!leftover.isEmpty()) {
                    String fullContId = st.fallbackDestSlot != null
                            ? st.fallbackDestSlot.getContainerId()
                            : tour.dropOffs.get(Math.min(st.stopIdx, tour.dropOffs.size() - 1)).containerId;
                    SmartObjectRegistry.markFullCooldown(
                            tour.settlementId, fullContId, ctx.simTime + BatchTourBuilder.FULL_CONTAINER_COOLDOWN_SEC);

                    SmartSlot alt = BatchTourBuilder.findFallbackDestinationForItem(
                            tour.settlementId,
                            leftover.get(0),
                            EmberTargetProjector.levelOf(ctx.npc.getZ()),
                            ctx.npc.getX(),
                            ctx.npc.getY(),
                            fullContId,
                            ctx.simTime);
                    if (alt != null && st.fallbackDestSlot == null) {
                        st.fallbackDestSlot = alt;
                        st.fallbackItems.clear();
                        st.fallbackItems.addAll(leftover);
                        st.phase = WorkPhase.MOVING_TO_DROPOFF;
                        st.moveIssued = false;
                        return;
                    }
                    // 无可替代目标箱：退回原源箱避免私吞货物
                    TourStop firstSrc = tour.pickups.get(0);
                    enterReturnToSource(st, firstSrc.containerId, firstSrc.standX, firstSrc.standY, firstSrc.z, leftover);
                    return;
                }

                if (st.fallbackDestSlot != null) {
                    st.fallbackDestSlot = null;
                    st.fallbackItems.clear();
                } else {
                    st.stopIdx++;
                }

                if (st.stopIdx < tour.dropOffs.size()) {
                    st.phase = WorkPhase.MOVING_TO_DROPOFF;
                    st.moveIssued = false;
                } else {
                    finishOrChainNext(ctx, st);
                }
            }
            case RETURNING_LEFTOVER -> {
                if (st.fallbackItems.isEmpty() || st.returnSourceContainerId == null) {
                    finishOrChainNext(ctx, st);
                    return;
                }
                if (advanceMoveToContainer(
                        ctx, st, tour.settlementId, st.returnSourceContainerId,
                        st.returnSourceX, st.returnSourceY, st.returnSourceZ, -1)) {
                    startTransfer(ctx, st, TransferMode.UNLOAD_TO_CONTAINER, st.returnSourceContainerId, -1, st.fallbackItems, WorkPhase.UNLOADING);
                    st.fallbackItems.clear();
                    st.returnSourceContainerId = null;
                    st.stopIdx = tour.dropOffs.size();
                }
            }
            default -> finishOrChainNext(ctx, st);
        }
    }

    // ==================== 2. 机械动力式实体提货配送状态机 ====================

    private static void enterDeliveryDestinationPhase(BrainContext ctx, NpcWorkState st, DeliveryPlan plan) {
        st.playerMoveRetries = 0;
        if (plan.directToPickupOut) {
            double remWeight = computeCarriedWeight(plan.carriedItems);
            double minItemWeight = computeMinCarriedWeight(plan.carriedItems);
            SmartSlot pickupOut = DeliveryOrderManager.findPickupOutSlotById(
                    plan.settlementId, plan.targetPickupOutContainerId, remWeight, ctx.simTime);
            if (pickupOut == null && minItemWeight > 0.0 && minItemWeight < remWeight) {
                pickupOut = DeliveryOrderManager.findPickupOutSlotById(
                        plan.settlementId, plan.targetPickupOutContainerId, minItemWeight, ctx.simTime);
            }
            if (pickupOut == null) {
                pickupOut = DeliveryOrderManager.findBestPickupOutSlot(
                        plan.settlementId, remWeight, ctx.npc.getX(), ctx.npc.getY(),
                        EmberTargetProjector.levelOf(ctx.npc.getZ()), ctx.simTime);
            }
            if (pickupOut == null && minItemWeight > 0.0 && minItemWeight < remWeight) {
                pickupOut = DeliveryOrderManager.findBestPickupOutSlot(
                        plan.settlementId, minItemWeight, ctx.npc.getX(), ctx.npc.getY(),
                        EmberTargetProjector.levelOf(ctx.npc.getZ()), ctx.simTime);
            }
            if (pickupOut != null) {
                st.fallbackPickupOutSlot = pickupOut;
                st.phase = WorkPhase.FALLBACK_PICKUP_OUT;
                st.moveIssued = false;
                return;
            }
            DeliveryPickupGroup first = plan.pickupGroups.get(0);
            enterReturnToSource(st, first.containerId, first.standX, first.standY, first.z, plan.carriedItems);
            return;
        }
        st.phase = WorkPhase.MOVING_TO_PLAYER;
        st.moveIssued = false;
    }

    private static void tryAbsorbNewPendingOrders(
            BrainContext ctx, NpcWorkState st, DeliveryPlan plan, StateScope scope) {
        if (ctx == null
                || ctx.npc == null
                || plan == null
                || scope == null
                || scope.isClosed()
                || plan.carryBudget == null
                || !plan.carryBudget.canFitAnyMinWeight()) {
            return;
        }
        List<DeliveryOrder> pendingAll = DeliveryOrderManager.getPendingOrders(plan.settlementId);
        if (pendingAll.isEmpty()) {
            return;
        }

        List<DeliveryOrder> matching = null;
        for (int i = 0, n = pendingAll.size(); i < n; i++) {
            DeliveryOrder ord = pendingAll.get(i);
            if (ord == null || ord.getRemainingCount() <= 0 || plan.orderIds.contains(ord.orderId)) {
                continue;
            }
            if (ord.playerIndex == plan.playerIndex
                    && Objects.equals(ord.targetPickupOutContainerId, plan.targetPickupOutContainerId)) {
                if (matching == null) {
                    matching = new ArrayList<>(4);
                }
                matching.add(ord);
            }
        }
        if (matching == null) {
            return;
        }

        if (!plan.bagContainers.isEmpty() && !Double.isInfinite(ctx.npc.getMaxWeight())) {
            double estExtra = estimateOrderBatchWeight(plan.settlementId, matching);
            double noDamageLimit = SmartItemPicker.computeNoDamageWeightLimit(ctx.npc.getMaxWeight());
            if (plan.carryBudget.getEffUsed() + estExtra > noDamageLimit + 1.0E-4
                    || plan.carryBudget.getEffUsed() + estExtra > SmartItemPicker.MAIN_INVENTORY_HARD_CAP + 1.0E-4) {
                plan.carryBudget.setPreferBags(true);
            }
        }

        float fromX = ctx.npc.getX();
        float fromY = ctx.npc.getY();
        int npcZ = EmberTargetProjector.levelOf(ctx.npc.getZ());
        double destX = plan.lastOrderDestX;
        double destY = plan.lastOrderDestY;
        int destZ = plan.lastOrderDestZ;
        if (!plan.directToPickupOut) {
            IsoPlayer player = resolvePlayer(plan.playerIndex);
            if (player != null && !player.isDead()) {
                destX = player.getX();
                destY = player.getY();
                destZ = EmberTargetProjector.levelOf(player.getZ());
                plan.lastOrderDestX = destX;
                plan.lastOrderDestY = destY;
                plan.lastOrderDestZ = destZ;
            }
        }

        Set<String> unvisitedIds = new HashSet<>();
        Map<String, DeliveryPickupGroup> unvisitedByContainer = new HashMap<>();
        for (int i = st.stopIdx, n = plan.pickupGroups.size(); i < n; i++) {
            DeliveryPickupGroup grp = plan.pickupGroups.get(i);
            unvisitedIds.add(grp.containerId);
            unvisitedByContainer.put(grp.containerId, grp);
        }

        Map<String, List<InventoryItem>> newByContainer = null;
        for (int i = 0, n = matching.size(); i < n; i++) {
            DeliveryOrder ord = matching.get(i);
            if (!plan.carryBudget.canFitAnyMinWeight()) {
                break;
            }
            List<PickedItemRef> picked = SmartItemPicker.pickItemsForOrderWithBudget(
                    plan.settlementId,
                    ord.fullType,
                    ord.getRemainingCount(),
                    ctx.npcId,
                    fromX,
                    fromY,
                    npcZ,
                    destX,
                    destY,
                    destZ,
                    unvisitedIds,
                    plan.pickedItemKeys,
                    ctx.simTime,
                    plan.carryBudget,
                    scope::defer);
            if (picked.isEmpty()) {
                continue;
            }
            if (!DeliveryOrderManager.markAssigned(ord.orderId, ctx.npcId)) {
                continue;
            }
            scope.defer(() -> DeliveryOrderManager.requeueIfInterrupted(ord.orderId, ctx.npcId));
            plan.orderIds.add(ord.orderId);

            for (int pi = 0, pn = picked.size(); pi < pn; pi++) {
                PickedItemRef ref = picked.get(pi);
                plan.chosenContainerIds.add(ref.containerId());
                unvisitedIds.add(ref.containerId());
                plan.orderIdByItem.put(ref.item(), ord.orderId);
                plan.totalWeight += Math.max(0.05, ref.item().getUnequippedWeight());
                if (ref.targetBagIndex() >= 0 && ref.targetBagIndex() < plan.bagContainers.size()) {
                    plan.loadTargetsByItem.put(ref.item(), plan.bagContainers.get(ref.targetBagIndex()));
                }
                DeliveryPickupGroup existing = unvisitedByContainer.get(ref.containerId());
                if (existing != null) {
                    existing.items.add(ref.item());
                } else {
                    if (newByContainer == null) {
                        newByContainer = new LinkedHashMap<>();
                    }
                    newByContainer.computeIfAbsent(ref.containerId(), k -> new ArrayList<>()).add(ref.item());
                }
            }
        }

        if (newByContainer == null || newByContainer.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<InventoryItem>> e : newByContainer.entrySet()) {
            SmartSlot slot = SmartObjectRegistry.getContainerSlot(plan.settlementId, e.getKey());
            int cx = slot != null ? slot.originX : (int) Math.floor(fromX);
            int cy = slot != null ? slot.originY : (int) Math.floor(fromY);
            int cz = slot != null ? slot.originZ : npcZ;
            int[] stand = slot != null
                    ? SmartObjectRegistry.pickBestStandTile(slot, fromX, fromY, ctx.simTime)
                    : new int[]{cx, cy, -1};
            DeliveryPickupGroup added = new DeliveryPickupGroup(
                    e.getKey(), cx, cy, cz, stand[0], stand[1], stand[2], e.getValue());
            plan.pickupGroups.add(added);
            unvisitedByContainer.put(e.getKey(), added);
        }

        int reorderStart = st.moveIssued ? (st.stopIdx + 1) : st.stopIdx;
        if (plan.pickupGroups.size() - reorderStart >= 2) {
            double anchorX = fromX;
            double anchorY = fromY;
            int anchorZ = npcZ;
            if (reorderStart > st.stopIdx && st.stopIdx < plan.pickupGroups.size()) {
                DeliveryPickupGroup activeStop = plan.pickupGroups.get(st.stopIdx);
                anchorX = activeStop.standX + 0.5;
                anchorY = activeStop.standY + 0.5;
                anchorZ = activeStop.z;
            }
            List<DeliveryPickupGroup> rem = new ArrayList<>(
                    plan.pickupGroups.subList(reorderStart, plan.pickupGroups.size()));
            List<DeliveryPickupGroup> reordered = BatchTourBuilder.orderStopsShortestPath(
                    anchorX, anchorY, anchorZ, destX, destY, destZ,
                    rem, g -> g.standX, g -> g.standY, g -> g.z);
            for (int ri = 0; ri < reordered.size(); ri++) {
                plan.pickupGroups.set(reorderStart + ri, reordered.get(ri));
            }
        }
    }

    private static void requeueAllPlanOrders(DeliveryPlan plan, String npcId) {
        if (plan == null || plan.orderIds == null) {
            return;
        }
        for (int i = 0, n = plan.orderIds.size(); i < n; i++) {
            DeliveryOrderManager.requeueIfInterrupted(plan.orderIds.get(i), npcId);
        }
    }

    private static void settleDeliveryOrders(
            DeliveryPlan plan,
            String npcId,
            List<InventoryItem> deliveredItems,
            boolean fallbackPickupOut,
            boolean partialOnly) {
        if (plan == null || plan.orderIds == null) {
            return;
        }
        Map<String, Integer> countsByOrder = new HashMap<>();
        if (deliveredItems != null) {
            for (int i = 0, n = deliveredItems.size(); i < n; i++) {
                InventoryItem item = deliveredItems.get(i);
                String ordId = item != null ? plan.orderIdByItem.get(item) : null;
                if (ordId != null) {
                    countsByOrder.merge(ordId, 1, Integer::sum);
                }
            }
        }
        if (partialOnly) {
            for (Map.Entry<String, Integer> e : countsByOrder.entrySet()) {
                if (e.getValue() > 0) {
                    DeliveryOrderManager.recordPartialProgress(e.getKey(), e.getValue());
                }
            }
            return;
        }
        for (int i = 0, n = plan.orderIds.size(); i < n; i++) {
            String ordId = plan.orderIds.get(i);
            int deliveredCnt = countsByOrder.getOrDefault(ordId, 0);
            boolean orderFallback = fallbackPickupOut && deliveredCnt > 0;
            DeliveryOrderManager.recordDeliveredBatch(ordId, npcId, deliveredCnt, orderFallback);
        }
    }

    private static void tickDeliveryPlan(BrainContext ctx, NpcWorkState st, StateScope scope, double dtSim) {
        DeliveryPlan plan = st.deliveryPlan;
        switch (st.phase) {
            case MOVING_TO_PICKUP -> {
                if (st.activeSelfPack != null && tickOrStartSelfPackIfNeeded(ctx, st, dtSim)) {
                    return;
                }
                tryAbsorbNewPendingOrders(ctx, st, plan, scope);
                if (st.stopIdx >= plan.pickupGroups.size()) {
                    if (plan.carriedItems.isEmpty()) {
                        requeueAllPlanOrders(plan, ctx.npcId);
                        finishOrChainNext(ctx, st);
                    } else {
                        enterDeliveryDestinationPhase(ctx, st, plan);
                    }
                    return;
                }
                if (!plan.directToPickupOut && !st.moveIssued && (plan.pickupGroups.size() - st.stopIdx) >= 2) {
                    IsoPlayer livePlayer = resolvePlayer(plan.playerIndex);
                    if (livePlayer != null && !livePlayer.isDead()) {
                        int pZ = EmberTargetProjector.levelOf(livePlayer.getZ());
                        double movedDist = Math.hypot(
                                livePlayer.getX() - plan.lastOrderDestX, livePlayer.getY() - plan.lastOrderDestY);
                        if (pZ != plan.lastOrderDestZ || movedDist > PLAYER_REORDER_HYSTERESIS_TILES) {
                            int npcZ = EmberTargetProjector.levelOf(ctx.npc.getZ());
                            List<DeliveryPickupGroup> rem = new ArrayList<>(
                                    plan.pickupGroups.subList(st.stopIdx, plan.pickupGroups.size()));
                            List<DeliveryPickupGroup> reordered = BatchTourBuilder.orderStopsShortestPath(
                                    ctx.npc.getX(), ctx.npc.getY(), npcZ,
                                    livePlayer.getX(), livePlayer.getY(), pZ,
                                    rem, g -> g.standX, g -> g.standY, g -> g.z);
                            for (int ri = 0; ri < reordered.size(); ri++) {
                                plan.pickupGroups.set(st.stopIdx + ri, reordered.get(ri));
                            }
                            plan.lastOrderDestX = livePlayer.getX();
                            plan.lastOrderDestY = livePlayer.getY();
                            plan.lastOrderDestZ = pZ;
                        }
                    }
                }
                DeliveryPickupGroup grp = plan.pickupGroups.get(st.stopIdx);
                if (advanceMoveToContainer(
                        ctx, st, plan.settlementId, grp.containerId,
                        grp.standX, grp.standY, grp.z, grp.standDirIdx)) {
                    if (tickOrStartSelfPackIfNeeded(ctx, st, dtSim)) {
                        return;
                    }
                    startTransfer(ctx, st, TransferMode.LOAD_FROM_CONTAINER, grp.containerId, -1, grp.items, plan.loadTargetsByItem, WorkPhase.LOADING);
                }
            }
            case LOADING -> {
                if (st.activeTransfer == null) {
                    enterDeliveryDestinationPhase(ctx, st, plan);
                    return;
                }
                st.activeTransfer.tick(ctx, dtSim);
                if (st.activeTransfer.isRunning()) {
                    return;
                }
                List<InventoryItem> transferred = st.activeTransfer.getTransferredItems();
                List<InventoryItem> leftover = st.activeTransfer.getLeftoverItems();
                if (transferred.isEmpty() && !leftover.isEmpty()) {
                    st.fullInventoryCooldownUntil = ctx.simTime + 3.0;
                }
                plan.carriedItems.addAll(transferred);
                st.activeTransfer = null;
                st.stopIdx++;
                tryAbsorbNewPendingOrders(ctx, st, plan, scope);
                if (st.stopIdx < plan.pickupGroups.size()) {
                    st.phase = WorkPhase.MOVING_TO_PICKUP;
                    st.moveIssued = false;
                } else if (plan.carriedItems.isEmpty()) {
                    requeueAllPlanOrders(plan, ctx.npcId);
                    finishOrChainNext(ctx, st);
                } else {
                    enterDeliveryDestinationPhase(ctx, st, plan);
                }
            }
            case MOVING_TO_PLAYER -> {
                IsoPlayer player = resolvePlayer(plan.playerIndex);
                double remWeight = computeCarriedWeight(plan.carriedItems);
                double minItemWeight = computeMinCarriedWeight(plan.carriedItems);
                boolean playerValid = player != null && !player.isDead() && player.getInventory() != null;
                double px = playerValid ? player.getX() : 0.0;
                double py = playerValid ? player.getY() : 0.0;
                boolean playerHasRoom = playerValid
                        && (minItemWeight <= 0.0 || player.getInventory().hasRoomFor(player, (float) minItemWeight));
                if (DeliveryOrderManager.shouldFallbackToPickupOut(
                        plan.settlementId, px, py, playerValid, playerHasRoom, ctx.threatDetected)) {
                    SmartSlot pickupOut = DeliveryOrderManager.findBestPickupOutSlot(
                            plan.settlementId, remWeight, ctx.npc.getX(), ctx.npc.getY(),
                            EmberTargetProjector.levelOf(ctx.npc.getZ()), ctx.simTime);
                    if (pickupOut == null && minItemWeight > 0.0 && minItemWeight < remWeight) {
                        pickupOut = DeliveryOrderManager.findBestPickupOutSlot(
                                plan.settlementId, minItemWeight, ctx.npc.getX(), ctx.npc.getY(),
                                EmberTargetProjector.levelOf(ctx.npc.getZ()), ctx.simTime);
                    }
                    if (pickupOut != null) {
                        st.fallbackPickupOutSlot = pickupOut;
                        st.phase = WorkPhase.FALLBACK_PICKUP_OUT;
                        st.moveIssued = false;
                        st.playerMoveRetries = 0;
                        return;
                    }
                    // 若未设 PICKUP_OUT 箱则退回原取货箱
                    DeliveryPickupGroup first = plan.pickupGroups.get(0);
                    enterReturnToSource(st, first.containerId, first.standX, first.standY, first.z, plan.carriedItems);
                    return;
                }

                int npcZ = EmberTargetProjector.levelOf(ctx.npc.getZ());
                int pZ = EmberTargetProjector.levelOf(player.getZ());
                double dist = Math.hypot(player.getX() - ctx.npc.getX(), player.getY() - ctx.npc.getY());
                if (npcZ == pZ
                        && dist <= DeliveryOrderManager.HANDOVER_RANGE_TILES
                        && SmartObjectRegistry.canReachSquare(ctx.npc.getCurrentSquare(), player.getCurrentSquare())) {
                    ctx.npc.getLocomotion().stop(ctx.npc);
                    st.playerMoveRetries = 0;
                    startTransfer(ctx, st, TransferMode.HANDOVER_TO_PLAYER, null, plan.playerIndex, plan.carriedItems, WorkPhase.HANDOVER);
                    return;
                }
                double moveGoalDist = st.moveIssued
                        ? Math.hypot(player.getX() - (st.targetX + 0.5), player.getY() - (st.targetY + 0.5))
                        : 999.0;
                if (!st.moveIssued || moveGoalDist > 2.0 || st.targetZ != pZ) {
                    st.targetX = (int) Math.floor(player.getX());
                    st.targetY = (int) Math.floor(player.getY());
                    st.targetZ = pZ;
                    st.moveIssued = true;
                    st.playerMoveRetries = 0;
                    ctx.requestMoveTo(player.getX(), player.getY(), pZ, false);
                } else if (!ctx.npc.getLocomotion().isMoving() && !ctx.npc.getPosture().isBlockingLocomotion()) {
                    if (st.playerMoveRetries < 2) {
                        st.playerMoveRetries++;
                        ctx.requestMoveTo(player.getX(), player.getY(), pZ, false);
                    } else {
                        SmartSlot pickupOut = DeliveryOrderManager.findBestPickupOutSlot(
                                plan.settlementId, remWeight, ctx.npc.getX(), ctx.npc.getY(), npcZ, ctx.simTime);
                        if (pickupOut == null && minItemWeight > 0.0 && minItemWeight < remWeight) {
                            pickupOut = DeliveryOrderManager.findBestPickupOutSlot(
                                    plan.settlementId, minItemWeight, ctx.npc.getX(), ctx.npc.getY(), npcZ, ctx.simTime);
                        }
                        if (pickupOut != null) {
                            st.fallbackPickupOutSlot = pickupOut;
                            st.phase = WorkPhase.FALLBACK_PICKUP_OUT;
                            st.moveIssued = false;
                            st.playerMoveRetries = 0;
                            return;
                        }
                        DeliveryPickupGroup first = plan.pickupGroups.get(0);
                        enterReturnToSource(st, first.containerId, first.standX, first.standY, first.z, plan.carriedItems);
                    }
                }
            }
            case HANDOVER -> {
                if (st.activeTransfer == null) {
                    settleDeliveryOrders(plan, ctx.npcId, plan.carriedItems, false, false);
                    finishOrChainNext(ctx, st);
                    return;
                }
                st.activeTransfer.tick(ctx, dtSim);
                if (st.activeTransfer.isRunning()) {
                    return;
                }
                List<InventoryItem> transferred = new ArrayList<>(st.activeTransfer.getTransferredItems());
                List<InventoryItem> leftover = new ArrayList<>(st.activeTransfer.getLeftoverItems());
                st.activeTransfer = null;
                if (leftover.isEmpty()) {
                    settleDeliveryOrders(plan, ctx.npcId, transferred, false, false);
                    finishOrChainNext(ctx, st);
                } else {
                    if (!transferred.isEmpty()) {
                        settleDeliveryOrders(plan, ctx.npcId, transferred, false, true);
                    }
                    plan.carriedItems.clear();
                    plan.carriedItems.addAll(leftover);
                    st.phase = WorkPhase.MOVING_TO_PLAYER;
                    st.moveIssued = false;
                }
            }
            case FALLBACK_PICKUP_OUT -> {
                SmartSlot box = st.fallbackPickupOutSlot;
                if (box == null || !box.isActive()) {
                    requeueAllPlanOrders(plan, ctx.npcId);
                    finishOrChainNext(ctx, st);
                    return;
                }
                if (st.activeTransfer != null) {
                    st.activeTransfer.tick(ctx, dtSim);
                    if (st.activeTransfer.isRunning()) {
                        return;
                    }
                    List<InventoryItem> transferred = new ArrayList<>(st.activeTransfer.getTransferredItems());
                    List<InventoryItem> leftover = new ArrayList<>(st.activeTransfer.getLeftoverItems());
                    SmartObjectRegistry.markWithdrawnWindow(
                            plan.settlementId,
                            box.getContainerId(),
                            ctx.simTime + DeliveryOrderManager.PICKUP_OUT_PROTECTION_SEC);
                    st.activeTransfer = null;
                    st.fallbackPickupOutSlot = null;
                    if (leftover.isEmpty()) {
                        settleDeliveryOrders(plan, ctx.npcId, transferred, !plan.directToPickupOut, false);
                        finishOrChainNext(ctx, st);
                        return;
                    }
                    if (!transferred.isEmpty()) {
                        settleDeliveryOrders(plan, ctx.npcId, transferred, false, true);
                    }
                    SmartObjectRegistry.markFullCooldown(
                            plan.settlementId,
                            box.getContainerId(),
                            ctx.simTime + DeliveryOrderManager.PICKUP_OUT_PROTECTION_SEC);
                    DeliveryPickupGroup first = plan.pickupGroups.get(0);
                    enterReturnToSource(st, first.containerId, first.standX, first.standY, first.z, leftover);
                    return;
                }
                int[] stand = SmartObjectRegistry.pickBestStandTile(
                        box, ctx.npc.getX(), ctx.npc.getY(), ctx.simTime);
                if (advanceMoveToContainer(
                        ctx, st, plan.settlementId, box.getContainerId(),
                        stand[0], stand[1], box.originZ, stand[2])) {
                    startTransfer(ctx, st, TransferMode.UNLOAD_TO_CONTAINER, box.getContainerId(), -1, plan.carriedItems, WorkPhase.FALLBACK_PICKUP_OUT);
                }
            }
            case RETURNING_LEFTOVER -> {
                if (st.activeTransfer != null) {
                    st.activeTransfer.tick(ctx, dtSim);
                    if (st.activeTransfer.isRunning()) {
                        return;
                    }
                    st.activeTransfer = null;
                    requeueAllPlanOrders(plan, ctx.npcId);
                    finishOrChainNext(ctx, st);
                    return;
                }
                if (st.returnSourceContainerId == null || st.fallbackItems.isEmpty()) {
                    requeueAllPlanOrders(plan, ctx.npcId);
                    finishOrChainNext(ctx, st);
                    return;
                }
                if (advanceMoveToContainer(
                        ctx, st, plan.settlementId, st.returnSourceContainerId,
                        st.returnSourceX, st.returnSourceY, st.returnSourceZ, -1)) {
                    startTransfer(ctx, st, TransferMode.UNLOAD_TO_CONTAINER, st.returnSourceContainerId, -1, st.fallbackItems, WorkPhase.RETURNING_LEFTOVER);
                }
            }
            default -> finishOrChainNext(ctx, st);
        }
    }

    private static List<InventoryItem> pollCompletedLeftovers(
            BrainContext ctx, NpcWorkState st, double dtSim) {
        if (st.activeTransfer == null) {
            return List.of();
        }
        st.activeTransfer.tick(ctx, dtSim);
        if (st.activeTransfer.isRunning()) {
            return null;
        }
        List<InventoryItem> leftover = new ArrayList<>(st.activeTransfer.getLeftoverItems());
        st.activeTransfer = null;
        return leftover;
    }

    private static void startTransfer(
            BrainContext ctx,
            NpcWorkState st,
            TransferMode mode,
            String containerId,
            int playerIndex,
            List<InventoryItem> items,
            WorkPhase nextPhase) {
        startTransfer(ctx, st, mode, containerId, playerIndex, items, null, nextPhase);
    }

    private static void startTransfer(
            BrainContext ctx,
            NpcWorkState st,
            TransferMode mode,
            String containerId,
            int playerIndex,
            List<InventoryItem> items,
            Map<InventoryItem, ItemContainer> loadTargetsByItem,
            WorkPhase nextPhase) {
        double dur = BatchTourBuilder.computeDampedTransferDuration(items.size());
        st.activeTransfer = new BatchContainerTransferAction(
                mode, containerId, playerIndex, items, loadTargetsByItem, dur);
        st.activeTransfer.start(ctx);
        st.phase = nextPhase;
        st.moveIssued = false;
    }

    private static void enterReturnToSource(
            NpcWorkState st,
            String sourceContainerId,
            int standX,
            int standY,
            int z,
            List<InventoryItem> itemsToReturn) {
        st.fallbackDestSlot = null;
        st.fallbackItems.clear();
        st.fallbackItems.addAll(itemsToReturn);
        st.returnSourceContainerId = sourceContainerId;
        st.returnSourceX = standX;
        st.returnSourceY = standY;
        st.returnSourceZ = z;
        st.phase = WorkPhase.RETURNING_LEFTOVER;
        st.moveIssued = false;
    }

    private static void finishOrChainNext(BrainContext ctx, NpcWorkState st) {
        st.carriedHaulItems.clear();
        st.fallbackItems.clear();
        st.fallbackDestSlot = null;
        st.fallbackPickupOutSlot = null;
        st.returnSourceContainerId = null;
        st.playerMoveRetries = 0;

        if (st.nextChainedSlice != null && ctx.simTime <= st.chainedExpiresSimTime) {
            ctx.npc.getBrain().getActiveRunner().transferChainedScopeToActiveLeaf();
            st.activeSlice = st.nextChainedSlice;
            st.haulTour = st.nextChainedHaulTour;
            st.deliveryPlan = st.nextChainedDeliveryPlan;
            clearChainedPlan(st);
            st.phase = WorkPhase.MOVING_TO_PICKUP;
            st.stopIdx = 0;
            st.moveIssued = false;
            st.sideRetries = 0;
            st.wasLookaheadEligible = false;
            if (st.haulTour != null) {
                updateTerminalAnchor(st, st.haulTour.tourTerminalAnchor);
            } else if (st.deliveryPlan != null) {
                updateTerminalAnchor(st, st.deliveryPlan.terminalAnchor);
            }
            return;
        }

        if (st.nextChainedSlice != null) {
            ctx.npc.getBrain().getActiveRunner().closeAndResetChainedScope();
            clearChainedPlan(st);
        }
        st.activeSlice = null;
        st.haulTour = null;
        st.deliveryPlan = null;
        st.phase = WorkPhase.NONE;
        st.hasTerminalAnchor = false;
    }

    private static IsoGridSquare resolveContainerSquare(String settlementId, String containerId) {
        WarehouseFastIndex.LiveContainerRef ref = WarehouseFastIndex.resolveLiveContainer(containerId);
        if (ref != null && ref.square() != null) {
            return ref.square();
        }
        SmartSlot slot = SmartObjectRegistry.getContainerSlot(settlementId, containerId);
        if (slot != null && IsoWorld.instance != null && IsoWorld.instance.currentCell != null) {
            return IsoWorld.instance.currentCell.getGridSquare(slot.originX, slot.originY, slot.originZ);
        }
        return null;
    }

    private static boolean advanceMoveToContainer(
            BrainContext ctx,
            NpcWorkState st,
            String settlementId,
            String containerId,
            int standX,
            int standY,
            int z,
            int standDirIdx) {
        IsoNPC npc = ctx.npc;
        IsoGridSquare contSq = resolveContainerSquare(settlementId, containerId);
        if (contSq == null) {
            npc.getLocomotion().stop(npc);
            st.moveIssued = false;
            st.activeSlice = null;
            st.phase = WorkPhase.NONE;
            return false;
        }
        int activeStandX = st.moveIssued ? st.targetX : standX;
        int activeStandY = st.moveIssued ? st.targetY : standY;
        int curZ = EmberTargetProjector.levelOf(npc.getZ());
        double distToContainer = Math.hypot((contSq.getX() + 0.5) - npc.getX(), (contSq.getY() + 0.5) - npc.getY());
        double distToStand = Math.hypot((activeStandX + 0.5) - npc.getX(), (activeStandY + 0.5) - npc.getY());
        boolean canReach = curZ == z && SmartObjectRegistry.canReachSquare(npc.getCurrentSquare(), contSq);

        if (canReach && (distToContainer <= CONTAINER_CLOSE_DIST
                || (distToStand <= STAND_TILE_ARRIVE_TOL && distToContainer <= CONTAINER_MAX_REACH_DIST))) {
            npc.getLocomotion().stop(npc);
            st.moveIssued = false;
            return true;
        }

        EmberLocomotionController loco = npc.getLocomotion();
        if (!st.moveIssued) {
            st.currentTargetContainerId = containerId;
            st.targetX = standX;
            st.targetY = standY;
            st.targetZ = z;
            st.standDirIdx = standDirIdx;
            st.moveIssued = true;
            ctx.requestMoveTo(standX + 0.5, standY + 0.5, z, false);
            st.activeMoveSeq = loco.getMoveSeq();
            return false;
        }

        if (!loco.isMoving() && !npc.getPosture().isBlockingLocomotion()) {
            EmberLocomotionController.Status status = loco.getStatus();
            if (status == EmberLocomotionController.Status.ARRIVED
                    && canReach
                    && distToContainer <= CONTAINER_MAX_REACH_DIST) {
                st.moveIssued = false;
                return true;
            }
            if ((status == EmberLocomotionController.Status.ARRIVED
                    || status == EmberLocomotionController.Status.NO_PATH
                    || status == EmberLocomotionController.Status.STUCK)
                    && containerId != null
                    && st.sideRetries < 3) {
                SmartSlot slot = SmartObjectRegistry.getContainerSlot(settlementId, containerId);
                if (slot != null && st.standDirIdx >= 0) {
                    SmartObjectRegistry.markStandDirFailed(slot, st.standDirIdx, ctx.simTime);
                    int[] alt = SmartObjectRegistry.pickBestStandTile(slot, npc.getX(), npc.getY(), ctx.simTime);
                    st.sideRetries++;
                    st.standDirIdx = alt[2];
                    st.targetX = alt[0];
                    st.targetY = alt[1];
                    st.targetZ = slot.originZ;
                    ctx.requestMoveTo(alt[0] + 0.5, alt[1] + 0.5, slot.originZ, false);
                    st.activeMoveSeq = loco.getMoveSeq();
                    return false;
                }
            }
            if (st.deliveryPlan != null && st.phase == WorkPhase.MOVING_TO_PICKUP) {
                st.stopIdx++;
                st.moveIssued = false;
                st.sideRetries = 0;
                if (st.stopIdx < st.deliveryPlan.pickupGroups.size()) {
                    return false;
                }
                if (!st.deliveryPlan.carriedItems.isEmpty()) {
                    enterDeliveryDestinationPhase(ctx, st, st.deliveryPlan);
                    return false;
                }
                requeueAllPlanOrders(st.deliveryPlan, ctx.npcId);
            }
            // 超出换侧重试次数或不可直达（隔墙/过远），中止本次异常切片释放租约防卡死
            st.moveIssued = false;
            st.activeSlice = null;
            st.phase = WorkPhase.NONE;
        }
        return false;
    }

    private static List<InventoryItem> filterCurrentlyCarried(IsoNPC npc, List<InventoryItem> planned) {
        if (npc == null || npc.getInventory() == null || planned == null) {
            return List.of();
        }
        List<InventoryItem> out = new ArrayList<>(planned.size());
        for (int i = 0, n = planned.size(); i < n; i++) {
            InventoryItem it = planned.get(i);
            if (it != null && BatchContainerTransferAction.resolveNpcOwnedContainer(npc, it) != null) {
                out.add(it);
            }
        }
        return out;
    }

    private static double computeCarriedWeight(List<InventoryItem> items) {
        double w = 0.0;
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem it = items.get(i);
            if (it != null) {
                w += Math.max(0.05, it.getUnequippedWeight());
            }
        }
        return w;
    }

    private static double computeMinCarriedWeight(List<InventoryItem> items) {
        double minW = Double.MAX_VALUE;
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem it = items.get(i);
            if (it != null) {
                minW = Math.min(minW, Math.max(0.05, it.getUnequippedWeight()));
            }
        }
        return minW == Double.MAX_VALUE ? 0.0 : minW;
    }

    private static double estimateRemainingEta(IsoNPC npc, NpcWorkState st) {
        if (st.activeTransfer != null && st.activeTransfer.isRunning()) {
            double actRem = Math.max(0.0, st.activeTransfer.getDurationSimSec() - st.activeTransfer.getElapsedSimSec());
            if ((st.phase == WorkPhase.UNLOADING
                    && st.haulTour != null
                    && st.fallbackDestSlot == null
                    && st.stopIdx >= st.haulTour.dropOffs.size() - 1)
                    || st.phase == WorkPhase.HANDOVER
                    || st.phase == WorkPhase.FALLBACK_PICKUP_OUT) {
                return actRem;
            }
            return 99.0;
        }
        if (st.moveIssued
                && st.phase == WorkPhase.MOVING_TO_DROPOFF
                && st.haulTour != null
                && st.fallbackDestSlot == null
                && st.stopIdx == st.haulTour.dropOffs.size() - 1) {
            double dist = Math.hypot((st.targetX + 0.5) - npc.getX(), (st.targetY + 0.5) - npc.getY());
            return dist / SettlementTaskAuctioneer.WALK_SPEED_TILES_PER_SEC + 1.0;
        }
        if (st.moveIssued
                && (st.phase == WorkPhase.MOVING_TO_PLAYER || st.phase == WorkPhase.FALLBACK_PICKUP_OUT)) {
            double dist = Math.hypot((st.targetX + 0.5) - npc.getX(), (st.targetY + 0.5) - npc.getY());
            return dist / SettlementTaskAuctioneer.WALK_SPEED_TILES_PER_SEC + 1.0;
        }
        return 99.0;
    }

    private static IsoPlayer resolvePlayer(int playerIndex) {
        if (IsoPlayer.players == null || playerIndex < 0 || playerIndex >= IsoPlayer.players.length) {
            return null;
        }
        return IsoPlayer.players[playerIndex];
    }

    public static synchronized String getDebugSummary(String settlementId) {
        int working = 0;
        int chained = 0;
        for (NpcWorkState st : WORK_STATES.values()) {
            if (st.activeSlice != null && (settlementId == null || settlementId.equals(st.activeSlice.settlementId))) {
                working++;
            }
            if (st.nextChainedSlice != null && (settlementId == null || settlementId.equals(st.nextChainedSlice.settlementId))) {
                chained++;
            }
        }
        return SmartObjectRegistry.getSummary(settlementId)
                + " | " + DeliveryOrderManager.getSummary(settlementId)
                + String.format(" | workers(active=%d,lookaheadChained=%d)", working, chained);
    }
}
