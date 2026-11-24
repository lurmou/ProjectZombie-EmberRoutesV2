package emberroutes.npc.brain;

import emberroutes.npc.brain.VehicleSeatRegistry.SeatAllocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VehicleSeatRegistryTest {

    @BeforeEach
    void cleanRegistry() {
        VehicleSeatRegistry.reset();
    }

    @Test
    void driverSeatZeroIsNeverAllocated() {
        Object car = new Object();
        boolean[] installed = { true, true };
        boolean[] occupied = { false, true }; // 驾驶位 0 空，副驾 1 已占
        String[] seated = { null, "npc_1" };
        boolean[] direct = { true, true };
        boolean[][] canSwitch = {
            { false, true },
            { true, false }
        };
        float[] distSq = { 0.5F, 1.0F };

        assertFalse(VehicleSeatRegistry.hasCandidateSeat(
                car, "npc_2", 2, installed, occupied, seated, direct, canSwitch));
        assertNull(VehicleSeatRegistry.chooseAndReserve(
                car, "npc_2", 2, installed, occupied, seated, direct, canSwitch, distSq));
    }

    @Test
    void deepSeatFirstDualLockAndTransitReleaseUnblocksSecondFollower() {
        Object van = new Object();
        // 4 座厢式车：0=主驾, 1=前右直通外门, 2=后右深座(无直通外门), 3=后左深座(无直通外门)
        boolean[] installed = { true, true, true, true };
        boolean[] occupied = { true, false, false, false };
        String[] seated = { null, null, null, null };
        boolean[] direct = { true, true, false, false };
        boolean[][] canSwitch = new boolean[4][4];
        canSwitch[1][2] = true;
        canSwitch[1][3] = true;
        canSwitch[2][1] = true;
        canSwitch[3][1] = true;
        float[] distSq = { 9.0F, 1.0F, 99.0F, 99.0F };

        SeatAllocation a1 = VehicleSeatRegistry.chooseAndReserve(
                van, "npc_1", 4, installed, occupied, seated, direct, canSwitch, distSq);
        assertNotNull(a1);
        assertFalse(a1.shiftRequested());
        assertEquals(2, a1.finalSeat(), "Earliest follower must take deep seat 2 first");
        assertEquals(1, a1.transitEntrySeat(), "Must dual-lock doorway seat 1 during approach");

        // npc_1 仍在走去车门期间，npc_2 不能抢同一扇门中转位 1
        assertTrue(VehicleSeatRegistry.isSeatReservedByOther(van, 1, "npc_2"));
        assertTrue(VehicleSeatRegistry.isSeatReservedByOther(van, 2, "npc_2"));
        SeatAllocation a2Blocked = VehicleSeatRegistry.chooseAndReserve(
                van, "npc_2", 4, installed, occupied, seated, direct, canSwitch, distSq);
        assertNull(a2Blocked);

        // npc_1 进入车厢并换到深座 2 后，调用 releaseTransitSeat 释放门口锁 1（保留 2）
        occupied[2] = true;
        seated[2] = "npc_1";
        VehicleSeatRegistry.releaseTransitSeat("npc_1");
        assertFalse(VehicleSeatRegistry.isSeatReservedByOther(van, 1, "npc_2"));
        assertTrue(VehicleSeatRegistry.isSeatReservedByOther(van, 2, "npc_2"));

        // npc_2 现在可以立刻借 1 号门进入深座 3
        SeatAllocation a2 = VehicleSeatRegistry.chooseAndReserve(
                van, "npc_2", 4, installed, occupied, seated, direct, canSwitch, distSq);
        assertNotNull(a2);
        assertEquals(3, a2.finalSeat());
        assertEquals(1, a2.transitEntrySeat());
    }

    @Test
    void doorwayOccupantReceivesShiftOrderAndPreventsMultiNpcDeepSeatCollision() {
        Object van = new Object();
        boolean[] installed = { true, true, true, true };
        boolean[] occupied = { true, true, false, false }; // npc_1 坐在门口 1，深座 2/3 空
        String[] seated = { null, "npc_1", null, null };
        boolean[] direct = { true, true, false, false };
        boolean[][] canSwitch = new boolean[4][4];
        canSwitch[1][2] = true;
        canSwitch[1][3] = true;
        float[] distSq = { 9.0F, 1.0F, 99.0F, 99.0F };

        assertTrue(VehicleSeatRegistry.hasCandidateSeat(
                van, "npc_2", 4, installed, occupied, seated, direct, canSwitch));

        SeatAllocation shiftAlloc = VehicleSeatRegistry.chooseAndReserve(
                van, "npc_2", 4, installed, occupied, seated, direct, canSwitch, distSq);
        assertNotNull(shiftAlloc);
        assertTrue(shiftAlloc.shiftRequested());
        assertEquals("npc_1", shiftAlloc.shiftBlockerNpcId());
        assertEquals(2, shiftAlloc.shiftTargetSeat());

        // 若同一瞬第 3 名随从也申请上车，不得向同一个深座/同一名堵门者重复撞车派单
        SeatAllocation secondShift = VehicleSeatRegistry.chooseAndReserve(
                van, "npc_3", 4, installed, occupied, seated, direct, canSwitch, distSq);
        assertNull(secondShift);

        // npc_1 消费一次让道指令后立刻清空，避免重复触发
        assertEquals(2, VehicleSeatRegistry.consumeDoorwayShiftTarget(van, "npc_1"));
        assertEquals(-1, VehicleSeatRegistry.consumeDoorwayShiftTarget(van, "npc_1"));

        // 验证 releaseForNpc 与 StateScope.defer 清理契约
        VehicleSeatRegistry.releaseForNpc("npc_2");
        VehicleSeatRegistry.reset();
        assertFalse(VehicleSeatRegistry.isSeatReservedByOther(van, 2, "npc_3"));
    }
}
