package emberroutes.npc.brain;

import emberroutes.npc.brain.SettlementTaskAuctioneer.Assignment;
import emberroutes.npc.brain.SettlementTaskAuctioneer.Bidder;
import emberroutes.npc.brain.SettlementTaskAuctioneer.TaskKind;
import emberroutes.npc.brain.SettlementTaskAuctioneer.TaskSlice;
import emberroutes.warehouse.RoomConnectivityIndex;
import emberroutes.warehouse.RoomConnectivityIndex.RoomTransition;
import emberroutes.warehouse.SmartItemPicker;
import emberroutes.warehouse.SmartItemPicker.EquippedBagSpec;
import emberroutes.warehouse.SmartItemPicker.PlannedLoadAssignment;
import emberroutes.warehouse.SmartObjectRegistry;
import emberroutes.warehouse.SmartSlot;
import emberroutes.warehouse.SmartZoningPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementTaskAuctioneerTest {
    @BeforeEach
    void setUp() {
        SmartObjectRegistry.reset();
        DeliveryOrderManager.reset();
    }

    @AfterEach
    void tearDown() {
        SmartObjectRegistry.reset();
        DeliveryOrderManager.reset();
    }

    @Test
    void marginalCostFormulaCapturesRoomPenaltiesSpecialistOpportunityChainBonusAndAging() {
        Bidder worker = new Bidder("npc_w", 10.5, 10.5, 0, 1L, 0.0, 1.0, false, false);
        Bidder specialist = new Bidder("npc_sp", 10.5, 10.5, 0, 1L, 0.0, 1.0, true, false);
        TaskSlice nearHaul = new TaskSlice(
                "t1", TaskKind.HAUL_BATCH, "s1", "src1", 12, 10, 0, 1L, 0.0, 2.0, null);
        TaskSlice delivery = new TaskSlice(
                "t2", TaskKind.DELIVERY_ORDER, "s1", "src2", 12, 10, 0, 1L, 0.0, 2.0, "ord_1");

        double sameRoom = SettlementTaskAuctioneer.computeMarginalCost(worker, nearHaul, RoomTransition.SAME_ROOM, 0.0);
        double directDoor = SettlementTaskAuctioneer.computeMarginalCost(worker, nearHaul, RoomTransition.DIRECT_DOOR, 0.0);
        double indirect = SettlementTaskAuctioneer.computeMarginalCost(worker, nearHaul, RoomTransition.INDIRECT_DETOUR, 0.0);
        double crossFloor = SettlementTaskAuctioneer.computeMarginalCost(worker, nearHaul, RoomTransition.CROSS_FLOOR, 0.0);

        assertEquals(2.5, directDoor - sameRoom, 1e-6);
        assertEquals(9.0, indirect - sameRoom, 1e-6);
        assertEquals(12.0, crossFloor - sameRoom, 1e-6);

        // 专家干搬运 +8.0s 机会成本
        double specHaul = SettlementTaskAuctioneer.computeMarginalCost(specialist, nearHaul, RoomTransition.SAME_ROOM, 0.0);
        assertEquals(8.0, specHaul - sameRoom, 1e-6);

        // 同层 4 格内顺路回程链 -6.0s
        TaskSlice farHaul = new TaskSlice(
                "t3", TaskKind.HAUL_BATCH, "s1", "src3", 20, 10, 0, 1L, 0.0, 2.0, null);
        double nearCost = SettlementTaskAuctioneer.computeMarginalCost(worker, nearHaul, RoomTransition.SAME_ROOM, 0.0);
        double farCost = SettlementTaskAuctioneer.computeMarginalCost(worker, farHaul, RoomTransition.SAME_ROOM, 0.0);
        // far 多走 8 格 (8/1.8s) 且失去 6s chain bonus
        assertTrue(farCost - nearCost > 10.0);

        // 老化 0.30s/s（上限 35s）与提货紧急加权
        double aged50 = SettlementTaskAuctioneer.computeMarginalCost(worker, nearHaul, RoomTransition.SAME_ROOM, 50.0);
        double aged200 = SettlementTaskAuctioneer.computeMarginalCost(worker, nearHaul, RoomTransition.SAME_ROOM, 200.0);
        assertEquals(15.0, sameRoom - aged50, 1e-6);
        assertEquals(35.0, sameRoom - aged200, 1e-6);

        double delivCost = SettlementTaskAuctioneer.computeMarginalCost(worker, delivery, RoomTransition.SAME_ROOM, 0.0);
        assertEquals(SettlementTaskAuctioneer.DELIVERY_URGENCY_BONUS_SEC, sameRoom - delivCost, 1e-6);
    }

    @Test
    void lookaheadWinnerOutbidsFarIdleNpcAndRespects250msCooldownAndScopeHandoff() {
        SettlementTaskAuctioneer auctioneer = new SettlementTaskAuctioneer();
        assertFalse(auctioneer.canTriggerAuction("s1", 10.0, false, false));
        assertTrue(auctioneer.canTriggerAuction("s1", 10.0, true, false));

        // 同一房间（room=10），在途 NPC_1 还有 1.5s 完工且终点锚点距新任务仅 1 格；NPC_2 空闲但远在 40 格外且隔无直通门
        RoomConnectivityIndex.recordDoorEdge("s1", 0, 10L, 10L);
        Bidder lookaheadNear = new Bidder("npc_1", 10.5, 10.5, 0, 10L, 1.5, 1.0, false, true);
        Bidder idleFar = new Bidder("npc_2", 50.5, 10.5, 0, 99L, 0.0, 1.0, false, false);
        TaskSlice task = new TaskSlice(
                "haul:src1", TaskKind.HAUL_BATCH, "s1", "src1", 11, 10, 0, 10L, 10.0, 2.0, null);

        List<Assignment> out = new ArrayList<>();
        int count = auctioneer.runAuction("s1", List.of(lookaheadNear, idleFar), List.of(task), 10.0, out);
        assertEquals(1, count);
        assertEquals("npc_1", out.get(0).bidder().npcId, "Lookahead NPC finishing near task must beat far idle NPC");

        // 250ms 最短冷却窗门控
        assertFalse(auctioneer.canTriggerAuction("s1", 10.10, true, true));
        assertTrue(auctioneer.canTriggerAuction("s1", 10.26, true, false));

        // StateTreeRunner chainedNextScope 预锁接力验证：不提前释放，退出才释放
        AtomicInteger cleanupCount = new AtomicInteger();
        StateNode work = new StateNode("WORK", null, null, 0.0, false) {
            @Override
            public boolean canEnter(BrainContext ctx) {
                return true;
            }
        };
        StateNode root = new StateNode("ROOT", new StateNode[]{work}, null, 0.0, false) {};
        StateTreeRunner runner = new StateTreeRunner(root);
        BrainContext ctx = new BrainContext("npc_1");
        ctx.simTime = 10.0;
        runner.tick(ctx);
        runner.getChainedNextScope().defer(cleanupCount::incrementAndGet);
        runner.transferChainedScopeToActiveLeaf();
        assertEquals(0, cleanupCount.get());
        runner.shutdown(ctx);
        assertEquals(1, cleanupCount.get());
    }

    @Test
    void batchTourBuilderDampedDurationAndNearestNeighborOrdering() {
        double t1 = BatchTourBuilder.computeDampedTransferDuration(1);
        double t4 = BatchTourBuilder.computeDampedTransferDuration(4);
        double t9 = BatchTourBuilder.computeDampedTransferDuration(9);
        assertEquals(1.0, t1, 1e-6);
        assertEquals(1.35, t4, 1e-6);
        assertEquals(1.70, t9, 1e-6);

        record Point(String id, int x, int y) {}
        List<Point> raw = List.of(
                new Point("far", 30, 10),
                new Point("near", 12, 10),
                new Point("mid", 18, 10));
        List<Point> ordered = BatchTourBuilder.orderStopsNearestNeighbor(10, 10, raw, Point::x, Point::y);
        assertEquals("near", ordered.get(0).id());
        assertEquals("mid", ordered.get(1).id());
        assertEquals("far", ordered.get(2).id());
    }

    @Test
    void deliveryRespectsNoDamageOverloadBackpackReductionAndSplitsOrdersAcrossTrips() {
        // 1. 不扣血超载上限：maxWeight=12 时允许超载至 < 1.5*12 = 18.0（即 17.95），绝不踩入 Lv3 扣血线
        assertEquals(17.95, SmartItemPicker.computeNoDamageWeightLimit(12.0), 1e-6);
        assertEquals(50.0, SmartItemPicker.computeNoDamageWeightLimit(40.0), 1e-6);

        // 2. 主物品栏放得下整单（4.0 + 4*2.0 = 12.0 <= 17.95）-> 全部直接放主包（targetBagIndex == -1），绝不塞背包
        double[] lightItems = {2.0, 2.0, 2.0, 2.0};
        List<EquippedBagSpec> bags = List.of(new EquippedBagSpec(0, 20.0, 80, 0.0));
        List<PlannedLoadAssignment> mainOnly = SmartItemPicker.planCarryAssignments(lightItems, 4, 4.0, 12.0, bags);
        assertEquals(4, mainOnly.size());
        for (PlannedLoadAssignment a : mainOnly) {
            assertEquals(-1, a.targetBagIndex(), "Whole order fitting in main budget must stay in main inventory");
        }

        // 3. 大单超主包（10 根 x 3.0 = 30.0）且背有 80% 减重、容量 20 的背包：
        // 6 根进 0 号背包（6*3.0=18.0 <= cap 20，有效负重仅 6*0.6=3.6 -> 6+3.6=9.6），
        // 再加 2 根进主包（+6.0 -> 15.6 <= 17.95），单趟可扛 8 根，第 9 根 15.6+3.0=18.6 > 17.95 截断留下一趟
        double[] tenPlanks = {3.0, 3.0, 3.0, 3.0, 3.0, 3.0, 3.0, 3.0, 3.0, 3.0};
        List<PlannedLoadAssignment> mixedLoad = SmartItemPicker.planCarryAssignments(tenPlanks, 10, 6.0, 12.0, bags);
        assertEquals(8, mixedLoad.size());
        int inBag = 0;
        int inMain = 0;
        for (PlannedLoadAssignment a : mixedLoad) {
            if (a.targetBagIndex() == 0) inBag++;
            else if (a.targetBagIndex() == -1) inMain++;
        }
        assertEquals(6, inBag);
        assertEquals(2, inMain);

        // 4. 超过单件 maxItemSize 的重物不会塞进小包（走主包 -1），且单件超重保底放行 1 件防死锁
        List<EquippedBagSpec> smallMaxItemBag = List.of(new EquippedBagSpec(0, 20.0, 80, 2.0));
        List<PlannedLoadAssignment> oversized = SmartItemPicker.planCarryAssignments(
                new double[]{25.0, 25.0}, 2, 0.0, 12.0, smallMaxItemBag);
        assertEquals(1, oversized.size());
        assertEquals(-1, oversized.get(0).targetBagIndex());

        // 5. 10 件大单分 2 趟送完 + 离线纯数据 shouldFallbackToPickupOut
        SmartObjectRegistry.registerContainerSlot(
                "s1", "10:10:0#crate:crate:0#0", 10, 10, 0, 1L, 0b11111111, null, 0, 50.0, 0.0, 1.0);
        String orderId = DeliveryOrderManager.submitOrder("s1", 0, "Base.Plank", 10, 5.0);
        assertNotNull(orderId);
        SmartObjectRegistry.consumeAuctionDirty("s1");

        assertTrue(DeliveryOrderManager.markAssigned(orderId, "npc_1"));
        DeliveryOrderManager.recordDeliveredBatch(orderId, "npc_1", 8, false);

        DeliveryOrderManager.DeliveryOrder ord = DeliveryOrderManager.getOrder(orderId);
        assertNotNull(ord);
        assertEquals(DeliveryOrderManager.OrderStatus.PENDING, ord.status);
        assertNull(ord.assignedNpcId);
        assertEquals(8, ord.deliveredCount);
        assertEquals(2, ord.getRemainingCount());
        assertTrue(SmartObjectRegistry.isAuctionDirty("s1"));

        assertTrue(DeliveryOrderManager.markAssigned(orderId, "npc_2"));
        DeliveryOrderManager.recordDeliveredBatch(orderId, "npc_2", 2, false);
        assertEquals(DeliveryOrderManager.OrderStatus.COMPLETED, ord.status);
        assertEquals(0, ord.getRemainingCount());

        // 6. 指定 PICKUP_OUT 提货箱派单与最近订单列表查询
        String pickupBoxId = "12:10:0#crate:crate:0#0";
        SmartZoningPolicy.ContainerFilterRule pickupRule = new SmartZoningPolicy.ContainerFilterRule();
        pickupRule.role = emberroutes.warehouse.ContainerRole.PICKUP_OUT;
        SmartSlot boxSlot = SmartObjectRegistry.registerContainerSlot(
                "s1", pickupBoxId, 12, 10, 0, 1L, 0b11111111, pickupRule, 0, 50.0, 5.0, 1.0);
        assertNotNull(boxSlot);
        assertNotNull(DeliveryOrderManager.findPickupOutSlotById("s1", pickupBoxId, 10.0, 10.0));

        String boxOrderId = DeliveryOrderManager.submitOrder("s1", 0, "Base.Bandage", 3, 12.0, pickupBoxId);
        assertNotNull(boxOrderId);
        DeliveryOrderManager.DeliveryOrder boxOrder = DeliveryOrderManager.getOrder(boxOrderId);
        assertEquals(pickupBoxId, boxOrder.targetPickupOutContainerId);

        List<DeliveryOrderManager.DeliveryOrder> recent = DeliveryOrderManager.listOrders("s1");
        assertEquals(2, recent.size());
        assertEquals(boxOrderId, recent.get(0).orderId);
        assertEquals(orderId, recent.get(1).orderId);
    }

    @Test
    void seatedNpcRemainsAuctionEligibleAndUrgentDeliveryBreaksLeisureDwell() {
        assertTrue(SettlementTaskAuctioneer.isBidderPostureEligible("STANDING"));
        assertTrue(SettlementTaskAuctioneer.isBidderPostureEligible("SEATED"),
                "Seated leisure NPCs must remain eligible to accept orders instead of sitting until timer expiry");
        assertFalse(SettlementTaskAuctioneer.isBidderPostureEligible("SLEEPING"));
        assertFalse(SettlementTaskAuctioneer.isBidderPostureEligible("ON_BED_AWAKE"));
        assertFalse(SettlementTaskAuctioneer.isBidderPostureEligible("STANDING_UP"));
        assertFalse(SettlementTaskAuctioneer.isBidderPostureEligible("ENTERING_SEAT"));

        final boolean[] workReady = {false};
        final boolean[] urgent = {false};
        StateNode work = new StateNode("WORK_DUTY", null, null, 0.0, false) {
            @Override
            public boolean isPreemptive(BrainContext ctx) {
                return urgent[0];
            }

            @Override
            public boolean canEnter(BrainContext ctx) {
                return workReady[0];
            }
        };
        StateNode seat = new StateNode("SEAT_REST", null, null, 12.0, false) {};
        StateNode root = new StateNode("ROOT", new StateNode[]{work, seat}, null, 0.0, false) {};
        StateTreeRunner runner = new StateTreeRunner(root);

        BrainContext ctx = new BrainContext("npc_seat");
        ctx.simTime = 10.0;
        runner.tick(ctx);
        assertEquals("ROOT/SEAT_REST", runner.getActivePathString());

        // Background haul (non-urgent) during 12s minDwell still waits for debounce
        workReady[0] = true;
        urgent[0] = false;
        ctx.simTime = 13.0;
        runner.tick(ctx);
        assertEquals("ROOT/SEAT_REST", runner.getActivePathString());

        // Player-submitted DELIVERY_ORDER marks work preemptive and interrupts SEAT_REST immediately
        urgent[0] = true;
        ctx.simTime = 13.5;
        runner.tick(ctx);
        assertEquals("ROOT/WORK_DUTY", runner.getActivePathString());
    }
}
