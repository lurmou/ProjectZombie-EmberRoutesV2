package emberroutes.npc.brain;

import emberroutes.npc.brain.SettlementTaskAuctioneer.Assignment;
import emberroutes.npc.brain.SettlementTaskAuctioneer.Bidder;
import emberroutes.npc.brain.SettlementTaskAuctioneer.TaskKind;
import emberroutes.npc.brain.SettlementTaskAuctioneer.TaskSlice;
import emberroutes.warehouse.RoomConnectivityIndex;
import emberroutes.warehouse.RoomConnectivityIndex.RoomTransition;
import emberroutes.warehouse.SmartObjectRegistry;
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
    }

    @AfterEach
    void tearDown() {
        SmartObjectRegistry.reset();
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
}
