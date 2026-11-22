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
import java.util.Set;
import java.util.function.Consumer;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.IsoGridSquare;

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
    private static final double ARRIVAL_INTERACT_DIST = 1.75;
    private static final double SOURCE_NO_MATCH_COOLDOWN_SEC = 2.0;

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
        final String orderId;
        final String settlementId;
        final int playerIndex;
        final String targetPickupOutContainerId;
        final boolean directToPickupOut;
        final List<DeliveryPickupGroup> pickupGroups;
        final List<InventoryItem> carriedItems = new ArrayList<>();
        final Map<InventoryItem, ItemContainer> loadTargetsByItem = new IdentityHashMap<>();
        double totalWeight;
        final TourAnchor terminalAnchor;

        DeliveryPlan(
                String orderId,
                String settlementId,
                int playerIndex,
                String targetPickupOutContainerId,
                List<DeliveryPickupGroup> pickupGroups,
                double totalWeight,
                TourAnchor terminalAnchor) {
            this.orderId = orderId;
            this.settlementId = settlementId;
            this.playerIndex = playerIndex;
            this.targetPickupOutContainerId = targetPickupOutContainerId;
            this.directToPickupOut = targetPickupOutContainerId != null && !targetPickupOutContainerId.isBlank();
            this.pickupGroups = pickupGroups;
            this.totalWeight = totalWeight;
            this.terminalAnchor = terminalAnchor;
        }
    }

    private static final class NpcWorkState {
        TaskSlice activeSlice;
        TourPlan haulTour;
        DeliveryPlan deliveryPlan;
        WorkPhase phase = WorkPhase.NONE;
        int stopIdx;
        BatchContainerTransferAction activeTransfer;
        long activeMoveSeq = -1L;
        String currentTargetContainerId;
        int targetX;
        int targetY;
        int targetZ;
        int standDirIdx = -1;
        int sideRetries;
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
                npc.getBrain().getActiveRunner().getChainedNextScope().close();
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
            } else if (st.activeSlice != null
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
    }

    private static void mountChainedWinner(IsoNPC npc, NpcWorkState st, TaskSlice task, double simTime) {
        StateScope chained = npc.getBrain().getActiveRunner().getChainedNextScope();
        float anchorX = st.hasTerminalAnchor ? (st.terminalX + 0.5F) : npc.getX();
        float anchorY = st.hasTerminalAnchor ? (st.terminalY + 0.5F) : npc.getY();
        if (task.kind == TaskKind.HAUL_BATCH) {
            TourPlan plan = buildTourForSlice(npc, task, anchorX, anchorY, simTime, chained::defer);
            if (plan == null) {
                UNMATCHABLE_SOURCE_COOLDOWN.put(task.sourceSlotId, Double.valueOf(simTime + SOURCE_NO_MATCH_COOLDOWN_SEC));
                return;
            }
            st.nextChainedSlice = task;
            st.nextChainedHaulTour = plan;
            st.nextChainedDeliveryPlan = null;
            st.chainedExpiresSimTime = simTime + SettlementTaskAuctioneer.CHAINED_PRELOCK_TTL_SEC;
        } else {
            DeliveryPlan plan = buildDeliveryPlanForSlice(npc, task, anchorX, anchorY, simTime, chained::defer);
            if (plan == null) {
                return;
            }
            st.nextChainedSlice = task;
            st.nextChainedHaulTour = null;
            st.nextChainedDeliveryPlan = plan;
            st.chainedExpiresSimTime = simTime + SettlementTaskAuctioneer.CHAINED_PRELOCK_TTL_SEC;
        }
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
        double currentEff = getNpcEffectiveWeight(npc);
        double noDamageLimit = SmartItemPicker.computeNoDamageWeightLimit(npc.getMaxWeight());
        float remainingCarry = (float) Math.max(2.0, noDamageLimit - currentEff);
        return BatchTourBuilder.buildHaulTour(
                task.settlementId, srcSlot, remainingCarry, npc.getNpcId(), fromX, fromY, simTime, scopeRegistrar);
    }

    private static double getNpcEffectiveWeight(IsoNPC npc) {
        if (npc == null) {
            return 0.0;
        }
        return npc.getInventory() != null ? npc.getInventory().getCapacityWeight() : npc.getInventoryWeight();
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

    private static DeliveryPlan buildDeliveryPlanForSlice(
            IsoNPC npc,
            TaskSlice task,
            float fromX,
            float fromY,
            double simTime,
            Consumer<Runnable> scopeRegistrar) {
        DeliveryOrder order = DeliveryOrderManager.getOrder(task.deliveryOrderId);
        if (order == null || order.getRemainingCount() <= 0) {
            return null;
        }
        if (order.status == DeliveryOrderManager.OrderStatus.PENDING) {
            if (!DeliveryOrderManager.markAssigned(order.orderId, npc.getNpcId())) {
                return null;
            }
        } else if (order.status != DeliveryOrderManager.OrderStatus.ASSIGNED
                || !npc.getNpcId().equals(order.assignedNpcId)) {
            return null;
        }
        scopeRegistrar.accept(() -> DeliveryOrderManager.requeueIfInterrupted(order.orderId, npc.getNpcId()));

        List<ItemContainer> bagContainers = new ArrayList<>(2);
        List<EquippedBagSpec> bagSpecs = collectEquippedBags(npc, bagContainers);
        double currentEff = getNpcEffectiveWeight(npc);
        List<PickedItemRef> picked = SmartItemPicker.pickItemsForOrder(
                task.settlementId,
                order.fullType,
                order.getRemainingCount(),
                npc.getNpcId(),
                fromX,
                fromY,
                EmberTargetProjector.levelOf(npc.getZ()),
                simTime,
                currentEff,
                npc.getMaxWeight(),
                bagSpecs,
                scopeRegistrar);
        if (picked.isEmpty()) {
            return null;
        }

        Map<String, List<InventoryItem>> byContainer = new LinkedHashMap<>();
        Map<InventoryItem, ItemContainer> loadTargets = new IdentityHashMap<>(picked.size());
        double totalWeight = 0.0;
        for (int i = 0, n = picked.size(); i < n; i++) {
            PickedItemRef ref = picked.get(i);
            byContainer.computeIfAbsent(ref.containerId(), k -> new ArrayList<>()).add(ref.item());
            totalWeight += Math.max(0.05, ref.item().getUnequippedWeight());
            if (ref.targetBagIndex() >= 0 && ref.targetBagIndex() < bagContainers.size()) {
                loadTargets.put(ref.item(), bagContainers.get(ref.targetBagIndex()));
            }
        }

        List<DeliveryPickupGroup> groups = new ArrayList<>(byContainer.size());
        for (Map.Entry<String, List<InventoryItem>> e : byContainer.entrySet()) {
            SmartSlot slot = SmartObjectRegistry.getContainerSlot(task.settlementId, e.getKey());
            int cx = slot != null ? slot.originX : picked.get(0).x();
            int cy = slot != null ? slot.originY : picked.get(0).y();
            int cz = slot != null ? slot.originZ : picked.get(0).z();
            int[] stand = slot != null
                    ? SmartObjectRegistry.pickBestStandTile(slot, fromX, fromY, simTime)
                    : new int[]{cx, cy, -1};
            groups.add(new DeliveryPickupGroup(
                    e.getKey(), cx, cy, cz, stand[0], stand[1], stand[2], e.getValue()));
        }

        TourAnchor anchor = null;
        if (order.targetPickupOutContainerId != null && !order.targetPickupOutContainerId.isBlank()) {
            SmartSlot targetSlot = SmartObjectRegistry.getContainerSlot(
                    task.settlementId, order.targetPickupOutContainerId);
            if (targetSlot != null) {
                anchor = new TourAnchor(
                        targetSlot.originX, targetSlot.originY, targetSlot.originZ, targetSlot.getRoomId());
            }
        }
        if (anchor == null) {
            IsoPlayer player = resolvePlayer(order.playerIndex);
            if (player != null && !player.isDead()) {
                IsoGridSquare pSq = player.getCurrentSquare();
                anchor = new TourAnchor(
                        (int) Math.floor(player.getX()),
                        (int) Math.floor(player.getY()),
                        EmberTargetProjector.levelOf(player.getZ()),
                        pSq != null ? pSq.getRoomID() : RoomConnectivityIndex.UNKNOWN_ROOM_ID);
            } else {
                DeliveryPickupGroup last = groups.get(groups.size() - 1);
                anchor = new TourAnchor(last.standX, last.standY, last.z, RoomConnectivityIndex.UNKNOWN_ROOM_ID);
            }
        }
        DeliveryPlan plan = new DeliveryPlan(
                order.orderId,
                task.settlementId,
                order.playerIndex,
                order.targetPickupOutContainerId,
                groups,
                totalWeight,
                anchor);
        plan.loadTargetsByItem.putAll(loadTargets);
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
        if (st.activeTransfer != null) {
            st.activeTransfer.interrupt(ctx, "WORK_EXIT");
            st.activeTransfer = null;
        }
        if (ctx.npc != null && !ctx.npc.isDead()) {
            ctx.npc.getPosture().clearPendingContinuation();
            ctx.npc.getLocomotion().stop(ctx.npc);
        }
        if (st.deliveryPlan != null) {
            DeliveryOrderManager.requeueIfInterrupted(st.deliveryPlan.orderId, ctx.npcId);
        }
        st.activeSlice = null;
        st.haulTour = null;
        st.deliveryPlan = null;
        st.phase = WorkPhase.NONE;
        st.stopIdx = 0;
        st.moveIssued = false;
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
                    startTransfer(ctx, st, TransferMode.LOAD_FROM_CONTAINER, stop.containerId, -1, stop.items, WorkPhase.LOADING);
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
                st.carriedHaulItems.addAll(st.activeTransfer.getTransferredItems());
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
        if (plan.directToPickupOut) {
            double remWeight = computeCarriedWeight(plan.carriedItems);
            SmartSlot pickupOut = DeliveryOrderManager.findPickupOutSlotById(
                    plan.settlementId, plan.targetPickupOutContainerId, remWeight, ctx.simTime);
            if (pickupOut == null) {
                pickupOut = DeliveryOrderManager.findBestPickupOutSlot(
                        plan.settlementId, remWeight, ctx.npc.getX(), ctx.npc.getY(),
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

    private static void tickDeliveryPlan(BrainContext ctx, NpcWorkState st, StateScope scope, double dtSim) {
        DeliveryPlan plan = st.deliveryPlan;
        switch (st.phase) {
            case MOVING_TO_PICKUP -> {
                if (st.stopIdx >= plan.pickupGroups.size()) {
                    if (plan.carriedItems.isEmpty()) {
                        DeliveryOrderManager.markCompleted(plan.orderId);
                        finishOrChainNext(ctx, st);
                    } else {
                        enterDeliveryDestinationPhase(ctx, st, plan);
                    }
                    return;
                }
                DeliveryPickupGroup grp = plan.pickupGroups.get(st.stopIdx);
                if (advanceMoveToContainer(
                        ctx, st, plan.settlementId, grp.containerId,
                        grp.standX, grp.standY, grp.z, grp.standDirIdx)) {
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
                plan.carriedItems.addAll(st.activeTransfer.getTransferredItems());
                st.activeTransfer = null;
                st.stopIdx++;
                if (st.stopIdx < plan.pickupGroups.size()) {
                    st.phase = WorkPhase.MOVING_TO_PICKUP;
                    st.moveIssued = false;
                } else if (plan.carriedItems.isEmpty()) {
                    DeliveryOrderManager.markCompleted(plan.orderId);
                    finishOrChainNext(ctx, st);
                } else {
                    enterDeliveryDestinationPhase(ctx, st, plan);
                }
            }
            case MOVING_TO_PLAYER -> {
                IsoPlayer player = resolvePlayer(plan.playerIndex);
                double remWeight = computeCarriedWeight(plan.carriedItems);
                boolean playerValid = player != null && !player.isDead() && player.getInventory() != null;
                double px = playerValid ? player.getX() : 0.0;
                double py = playerValid ? player.getY() : 0.0;
                boolean playerHasRoom = playerValid
                        && (remWeight <= 0.0 || player.getInventory().hasRoomFor(player, (float) remWeight));
                if (DeliveryOrderManager.shouldFallbackToPickupOut(
                        plan.settlementId, px, py, playerValid, playerHasRoom, ctx.threatDetected)) {
                    SmartSlot pickupOut = DeliveryOrderManager.findBestPickupOutSlot(
                            plan.settlementId, remWeight, ctx.npc.getX(), ctx.npc.getY(),
                            EmberTargetProjector.levelOf(ctx.npc.getZ()), ctx.simTime);
                    if (pickupOut != null) {
                        st.fallbackPickupOutSlot = pickupOut;
                        st.phase = WorkPhase.FALLBACK_PICKUP_OUT;
                        st.moveIssued = false;
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
                if (npcZ == pZ && dist <= DeliveryOrderManager.HANDOVER_RANGE_TILES) {
                    ctx.npc.getLocomotion().stop(ctx.npc);
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
                    ctx.requestMoveTo(player.getX(), player.getY(), pZ, false);
                }
            }
            case HANDOVER -> {
                List<InventoryItem> leftover = pollCompletedLeftovers(ctx, st, dtSim);
                if (leftover == null) {
                    return;
                }
                if (leftover.isEmpty()) {
                    DeliveryOrderManager.recordDeliveredBatch(
                            plan.orderId, ctx.npcId, plan.carriedItems.size(), false);
                    finishOrChainNext(ctx, st);
                } else {
                    int deliveredNow = Math.max(0, plan.carriedItems.size() - leftover.size());
                    if (deliveredNow > 0) {
                        DeliveryOrderManager.recordPartialProgress(plan.orderId, deliveredNow);
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
                    DeliveryOrderManager.requeueIfInterrupted(plan.orderId, ctx.npcId);
                    finishOrChainNext(ctx, st);
                    return;
                }
                if (st.activeTransfer != null) {
                    st.activeTransfer.tick(ctx, dtSim);
                    if (st.activeTransfer.isRunning()) {
                        return;
                    }
                    int deliveredNow = st.activeTransfer.getTransferredItems().size();
                    List<InventoryItem> leftover = new ArrayList<>(st.activeTransfer.getLeftoverItems());
                    SmartObjectRegistry.markWithdrawnWindow(
                            plan.settlementId,
                            box.getContainerId(),
                            ctx.simTime + DeliveryOrderManager.PICKUP_OUT_PROTECTION_SEC);
                    st.activeTransfer = null;
                    st.fallbackPickupOutSlot = null;
                    if (deliveredNow > 0) {
                        DeliveryOrderManager.recordDeliveredBatch(
                                plan.orderId, ctx.npcId, deliveredNow, !plan.directToPickupOut && leftover.isEmpty());
                    }
                    if (!leftover.isEmpty()) {
                        SmartObjectRegistry.markFullCooldown(
                                plan.settlementId,
                                box.getContainerId(),
                                ctx.simTime + DeliveryOrderManager.PICKUP_OUT_PROTECTION_SEC);
                        DeliveryPickupGroup first = plan.pickupGroups.get(0);
                        enterReturnToSource(st, first.containerId, first.standX, first.standY, first.z, leftover);
                        return;
                    }
                    finishOrChainNext(ctx, st);
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
                    DeliveryOrderManager.requeueIfInterrupted(plan.orderId, ctx.npcId);
                    finishOrChainNext(ctx, st);
                    return;
                }
                if (st.returnSourceContainerId == null || st.fallbackItems.isEmpty()) {
                    DeliveryOrderManager.requeueIfInterrupted(plan.orderId, ctx.npcId);
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
            ctx.npc.getBrain().getActiveRunner().getChainedNextScope().close();
            clearChainedPlan(st);
        }
        st.activeSlice = null;
        st.haulTour = null;
        st.deliveryPlan = null;
        st.phase = WorkPhase.NONE;
        st.hasTerminalAnchor = false;
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
        int curZ = EmberTargetProjector.levelOf(npc.getZ());
        double dist = Math.hypot((standX + 0.5) - npc.getX(), (standY + 0.5) - npc.getY());
        if (curZ == z && dist <= ARRIVAL_INTERACT_DIST) {
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
            if (status == EmberLocomotionController.Status.ARRIVED && curZ == z && dist <= 2.4) {
                st.moveIssued = false;
                return true;
            }
            if ((status == EmberLocomotionController.Status.NO_PATH
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
            // 超出换侧重试次数，中止本次异常切片释放租约防卡死
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

    private static double estimateRemainingEta(IsoNPC npc, NpcWorkState st) {
        if (st.activeTransfer != null && st.activeTransfer.isRunning()) {
            double actRem = Math.max(0.0, st.activeTransfer.getDurationSimSec() - st.activeTransfer.getElapsedSimSec());
            if (st.phase == WorkPhase.UNLOADING || st.phase == WorkPhase.HANDOVER || st.phase == WorkPhase.FALLBACK_PICKUP_OUT) {
                return actRem;
            }
            return actRem + 2.0;
        }
        if (st.phase == WorkPhase.MOVING_TO_DROPOFF && st.haulTour != null && st.stopIdx == st.haulTour.dropOffs.size() - 1) {
            double dist = Math.hypot((st.targetX + 0.5) - npc.getX(), (st.targetY + 0.5) - npc.getY());
            return dist / SettlementTaskAuctioneer.WALK_SPEED_TILES_PER_SEC + 1.0;
        }
        if (st.phase == WorkPhase.MOVING_TO_PLAYER || st.phase == WorkPhase.FALLBACK_PICKUP_OUT) {
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
