package emberroutes.npc.brain;

import emberroutes.npc.EmberFormationMatcher;
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
        assertEquals(50.0 / 30.0, t1, 1e-6);
        assertEquals((4 * 50.0) / 30.0, t4, 1e-6);
        assertEquals((9 * 50.0) / 30.0, t9, 1e-6);

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

    @Test
    void shortestPathOrderingMinimizesStartToEndRouteForSmallAndLargeStopCounts() {
        record Stop3D(String id, int x, int y, int z) {}

        // n <= 7: Held-Karp 精确 s-t 最短路应先绕取反方向近端箱再顺路回终点，总路程 36 < 贪心最近邻 40
        List<Stop3D> small = List.of(
                new Stop3D("near", 12, 0, 0),
                new Stop3D("farBack", 2, 0, 0),
                new Stop3D("nearEnd", 28, 0, 0));
        List<Stop3D> smallOpt = BatchTourBuilder.orderStopsShortestPath(
                10.5, 0.5, 0, 30.5, 0.5, 0, small, Stop3D::x, Stop3D::y, Stop3D::z);
        assertEquals(List.of("farBack", "near", "nearEnd"), smallOpt.stream().map(Stop3D::id).toList());

        // 跨楼层惩罚分组：先完成 0 层全取货再上 1 层，避免来回上下楼
        List<Stop3D> multiFloor = List.of(
                new Stop3D("f1_b", 18, 0, 1),
                new Stop3D("f0_a", 5, 0, 0),
                new Stop3D("f1_a", 6, 0, 1),
                new Stop3D("f0_b", 12, 0, 0));
        List<Stop3D> floorOpt = BatchTourBuilder.orderStopsShortestPath(
                0.5, 0.5, 0, 20.5, 0.5, 1, multiFloor, Stop3D::x, Stop3D::y, Stop3D::z);
        assertEquals(0, floorOpt.get(0).z());
        assertEquals(0, floorOpt.get(1).z());
        assertEquals(1, floorOpt.get(2).z());
        assertEquals(1, floorOpt.get(3).z());

        // n > 7: 2-opt 消除折返交叉
        List<Stop3D> large = List.of(
                new Stop3D("s80", 80, 0, 0),
                new Stop3D("s20", 20, 0, 0),
                new Stop3D("s60", 60, 0, 0),
                new Stop3D("s10", 10, 0, 0),
                new Stop3D("s90", 90, 0, 0),
                new Stop3D("s40", 40, 0, 0),
                new Stop3D("s70", 70, 0, 0),
                new Stop3D("s30", 30, 0, 0),
                new Stop3D("s50", 50, 0, 0));
        List<Stop3D> largeOpt = BatchTourBuilder.orderStopsShortestPath(
                0.5, 0.5, 0, 100.5, 0.5, 0, large, Stop3D::x, Stop3D::y, Stop3D::z);
        assertEquals(9, largeOpt.size());
        double total = Math.hypot((largeOpt.get(0).x() + 0.5) - 0.5, (largeOpt.get(0).y() + 0.5) - 0.5);
        for (int i = 1; i < largeOpt.size(); i++) {
            total += Math.hypot(largeOpt.get(i).x() - largeOpt.get(i - 1).x(), largeOpt.get(i).y() - largeOpt.get(i - 1).y());
        }
        total += Math.hypot(100.5 - (largeOpt.get(largeOpt.size() - 1).x() + 0.5), 0.5 - (largeOpt.get(largeOpt.size() - 1).y() + 0.5));
        assertEquals(100.0, total, 1e-4);
    }

    @Test
    void carryBudgetSharesCapacityAcrossMultipleOrdersAndStopsCleanly() {
        List<EquippedBagSpec> bags = List.of(new EquippedBagSpec(0, 15.0, 80, 0.0));
        SmartItemPicker.CarryBudget budget = new SmartItemPicker.CarryBudget(4.0, 12.0, bags, true);

        // Order 1: 3 x 3.0kg -> 进 0 号背包（effUsed: 4.0 + 3*0.6 = 5.8，背包余量 6.0）
        for (int i = 0; i < 3; i++) {
            assertEquals(0, budget.tryAssign(3.0));
        }
        // Order 2: 2 x 3.0kg -> 填满 0 号背包（effUsed: 5.8 + 2*0.6 = 7.0，背包余量 0.0）
        for (int i = 0; i < 2; i++) {
            assertEquals(0, budget.tryAssign(3.0));
        }
        // Order 3: 5 x 3.0kg -> 前 3 件进主物品栏（effUsed = 16.0 <= 17.95），第 4 件超限拒收
        for (int i = 0; i < 3; i++) {
            assertEquals(-1, budget.tryAssign(3.0));
        }
        assertEquals(Integer.MIN_VALUE, budget.tryAssign(3.0));
        assertEquals(8, budget.getAssignedCount());
        assertEquals(16.0, budget.getEffUsed(), 1e-6);
        assertTrue(budget.canFitAnyMinWeight());

        // 还能塞 1 件 1.95kg 小件达到 17.95 上限，之后不可再装
        assertEquals(-1, budget.tryAssign(1.95));
        assertFalse(budget.canFitAnyMinWeight());
        assertEquals(Integer.MIN_VALUE, budget.tryAssign(0.05));
    }

    @Test
    void chainedNextScopeResetAllowsSubsequentDeferWithoutImmediateRun() {
        StateNode work = new StateNode("WORK_DUTY", null, null, 0.0, false) {
            @Override
            public boolean canEnter(BrainContext ctx) {
                return true;
            }
        };
        StateNode root = new StateNode("ROOT", new StateNode[]{work}, null, 0.0, false) {};
        StateTreeRunner runner = new StateTreeRunner(root);

        AtomicInteger expiredRuns = new AtomicInteger();
        runner.getChainedNextScope().defer(expiredRuns::incrementAndGet);
        runner.closeAndResetChainedScope();
        assertEquals(1, expiredRuns.get());
        assertFalse(runner.getChainedNextScope().isClosed(),
                "closeAndResetChainedScope must replace closed scope with fresh open StateScope");

        AtomicInteger freshRuns = new AtomicInteger();
        runner.getChainedNextScope().defer(freshRuns::incrementAndGet);
        assertEquals(0, freshRuns.get(),
                "defer on fresh chained scope must NOT execute immediately after prior expiration");
        runner.closeAndResetChainedScope();
        assertEquals(1, freshRuns.get());
    }

    @Test
    void lookaheadBidderIsExcludedFromDeliveryOrders() {
        SettlementTaskAuctioneer auctioneer = new SettlementTaskAuctioneer();
        Bidder lookaheadNear = new Bidder("npc_1", 10.5, 10.5, 0, 1L, 0.5, 1.0, false, true);
        Bidder idleFar = new Bidder("npc_2", 30.5, 10.5, 0, 1L, 0.0, 1.0, false, false);
        TaskSlice delivery = new TaskSlice(
                "delivery:ord_1", TaskKind.DELIVERY_ORDER, "s1", "src1", 11, 10, 0, 1L, 10.0, 4.0, "ord_1");
        TaskSlice haul = new TaskSlice(
                "haul:src2", TaskKind.HAUL_BATCH, "s1", "src2", 11, 10, 0, 1L, 10.0, 2.0, null);

        assertEquals(EmberFormationMatcher.DUMMY_COST,
                SettlementTaskAuctioneer.computeMarginalCost(lookaheadNear, delivery, RoomTransition.SAME_ROOM, 10.0),
                1e-6);

        List<Assignment> out = new ArrayList<>();
        int assigned = auctioneer.runAuction(
                "s1", List.of(lookaheadNear, idleFar), List.of(delivery, haul), 10.0, out);
        assertEquals(2, assigned);
        for (Assignment asg : out) {
            if (asg.task().kind == TaskKind.DELIVERY_ORDER) {
                assertEquals("npc_2", asg.bidder().npcId,
                        "Lookahead bidder must never win DELIVERY_ORDER; idle bidder takes it");
            } else if (asg.task().kind == TaskKind.HAUL_BATCH) {
                assertEquals("npc_1", asg.bidder().npcId);
            }
        }
    }

    @Test
    void carryBudgetRespectsHardCapAndUsesEquippedBackpackWhenMainInventoryHeavy() {
        List<EquippedBagSpec> bags = List.of(
                new EquippedBagSpec(0, 17.0, 80, 0.0),
                new EquippedBagSpec(1, 10.0, 50, 0.0));

        // 1) 主包已达 50.0 硬上限时，即使背包还有空位也不再强塞（防 hasRoomFor(world -> bag) 瞬时过手失败死锁）
        SmartItemPicker.CarryBudget atCap = new SmartItemPicker.CarryBudget(50.0, 12.0, bags, true);
        assertFalse(atCap.canFitAnyMinWeight());
        assertFalse(atCap.canFit(1.0));
        assertEquals(Integer.MIN_VALUE, atCap.tryAssign(1.0));

        // 2) 主包 19.0（已超过 maxWeight=12 的不扣血上限 17.95，但远低于 50.0 硬上限）且背有减重背包：
        //    首件应优先塞进减重率最高的 0 号背包（>= 0），而非盲目塞主物品栏（-1）
        SmartItemPicker.CarryBudget overNoDamageUnderCap = new SmartItemPicker.CarryBudget(19.0, 12.0, bags, true);
        assertTrue(overNoDamageUnderCap.canFitAnyMinWeight());
        assertTrue(overNoDamageUnderCap.canFit(1.0));
        assertEquals(0, overNoDamageUnderCap.tryAssign(1.0));

        // 3) preferBags = true 时先装减重率 80% 的 0 号包，再装 50% 的 1 号包，最后才落主包
        List<EquippedBagSpec> smallBags = List.of(
                new EquippedBagSpec(0, 2.0, 50, 0.0),
                new EquippedBagSpec(1, 2.0, 80, 0.0));
        SmartItemPicker.CarryBudget multiBag = new SmartItemPicker.CarryBudget(2.0, 12.0, smallBags, true);
        assertEquals(1, multiBag.tryAssign(2.0));
        assertEquals(0, multiBag.tryAssign(2.0));
        assertEquals(-1, multiBag.tryAssign(2.0));

        // 4) 轻任务 preferBags = false 时先放主物品栏，途中追单切 setPreferBags(true) 后再启用最高减重背包
        SmartItemPicker.CarryBudget lightThenOverflow = new SmartItemPicker.CarryBudget(2.0, 12.0, smallBags, false);
        assertEquals(-1, lightThenOverflow.tryAssign(2.0));
        lightThenOverflow.setPreferBags(true);
        assertEquals(1, lightThenOverflow.tryAssign(2.0));
    }
}
