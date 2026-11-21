package emberroutes.warehouse;

import emberroutes.npc.brain.StateScope;
import emberroutes.warehouse.RoomConnectivityIndex.RoomTransition;
import emberroutes.warehouse.SmartSlot.ContainerLease;
import emberroutes.warehouse.SmartSlot.LeaseStatus;
import emberroutes.warehouse.SmartZoningPolicy.ContainerFilterRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmartZoningAndSlotTest {
    @BeforeEach
    void setUp() {
        SmartObjectRegistry.reset();
        SmartItemPicker.reset();
        SmartZoningPolicy.seedDefaultPresets();
    }

    @AfterEach
    void tearDown() {
        SmartObjectRegistry.reset();
        SmartItemPicker.reset();
    }

    @Test
    void fakeWeaponsDoNotClassifyDesksAsArmoryAndFourRolesFilterCorrectly() {
        assertTrue(SmartZoningPolicy.isFakeWeapon("Base.Pen"));
        assertTrue(SmartZoningPolicy.isFakeWeapon("Base.Fork"));
        assertFalse(SmartZoningPolicy.isFakeWeapon("Base.Pistol"));

        // 书桌内即使放了 2 支钢笔+1 把餐叉，因排除伪武器噪音，识别为文献而非军械库
        ContainerFilterRule deskRule = SmartZoningPolicy.evaluateHardwareOrContext(
                "desk", "furniture_tables_high_01", "office",
                Map.of("literature", 1.5), 3);
        assertEquals(ContainerRole.STORAGE, deskRule.role);
        assertTrue(deskRule.allowGroups.contains("literature"));
        assertFalse(deskRule.allowGroups.contains("weapons"));

        // 冰箱识别为生鲜食材优先仓储
        ContainerFilterRule fridgeRule = SmartZoningPolicy.evaluateHardwareOrContext(
                "fridge", "appliances_refrigeration_01", "kitchen", Map.of(), 0);
        assertEquals(4, fridgeRule.priority);
        assertTrue(SmartZoningPolicy.matchesItemNode(fridgeRule, "Base.Apple", "food.perishable"));
        assertFalse(SmartZoningPolicy.matchesItemNode(fridgeRule, "Base.Hammer", "tools"));

        // BUFFER_IN 与 PICKUP_OUT 均不作为自动理货目标箱接纳分拣
        ContainerFilterRule bufferRule = SmartZoningPolicy.buildRuleFromPreset("food", ContainerRole.BUFFER_IN);
        assertFalse(SmartZoningPolicy.matchesItemNode(bufferRule, "Base.Apple", "food.perishable"));
        ContainerFilterRule pickupRule = SmartZoningPolicy.buildRuleFromPreset("food", ContainerRole.PICKUP_OUT);
        assertFalse(SmartZoningPolicy.matchesItemNode(pickupRule, "Base.Apple", "food.perishable"));

        // 智能自动分区仅赋予 STORAGE，WORKSTATION_IO 只能由玩家显式指定
        ContainerFilterRule workshopCounter = SmartZoningPolicy.evaluateHardwareOrContext(
                "crate", "carpentry_01_00", "workshop", Map.of("materials", 4.0), 4);
        assertEquals(ContainerRole.STORAGE, workshopCounter.role);
        assertNotEquals(ContainerRole.WORKSTATION_IO, workshopCounter.role);
    }

    @Test
    void bufferInSwapAndPopReverseBroadcastAndScopeBoundLeases() {
        ContainerFilterRule bufRule = SmartZoningPolicy.buildRuleFromPreset("general", ContainerRole.BUFFER_IN);
        SmartSlot b1 = SmartObjectRegistry.registerContainerSlot(
                "s1", "10:10:0#crate:crate:0#0", 10, 10, 0, 100L, 0b11111111, bufRule, 4, 50.0, 4.0, 10.0);
        SmartSlot b2 = SmartObjectRegistry.registerContainerSlot(
                "s1", "12:10:0#crate:crate:0#0", 12, 10, 0, 100L, 0b11111111, bufRule, 2, 50.0, 2.0, 10.0);

        assertTrue(SmartObjectRegistry.consumeAuctionDirty("s1"));
        assertEquals(2, SmartObjectRegistry.getAdvertisedHaulOutSlots("s1").size());
        assertEquals(0, b1.getAdvertisedHaulOutListIdx());
        assertEquals(1, b2.getAdvertisedHaulOutListIdx());

        // 通过 StateScope 租用 b1 全部 4 件物品 -> O(1) swap-and-pop 自动将 b2 移至下标 0 并撤下 b1
        StateScope scope = new StateScope();
        ContainerLease outLease = SmartObjectRegistry.acquireContainerOutLease(
                "s1", b1.slotId, "npc_1", 4, 10.0, 30.0, scope::defer);
        assertNotNull(outLease);
        assertEquals(LeaseStatus.ACTIVE, outLease.getStatus());
        assertEquals(-1, b1.getAdvertisedHaulOutListIdx());
        assertEquals(0, b2.getAdvertisedHaulOutListIdx());
        assertEquals(1, SmartObjectRegistry.getAdvertisedHaulOutSlots("s1").size());

        // StateScope.close() 自动 LIFO 归还出库租约，b1 立刻重新进入广播并标 auctionDirty
        scope.close();
        assertEquals(LeaseStatus.RELEASED, outLease.getStatus());
        assertEquals(2, SmartObjectRegistry.getAdvertisedHaulOutSlots("s1").size());
        assertTrue(SmartObjectRegistry.isAuctionDirty("s1"));

        // 区块卸载立即使持有租约 INVALIDATED 并撤下广播
        StateScope scope2 = new StateScope();
        ContainerLease inLease = SmartObjectRegistry.acquireContainerInLease(
                "s1", b2.slotId, "npc_2", 10.0, 11.0, 30.0, scope2::defer);
        assertNotNull(inLease);
        SmartObjectRegistry.unregisterSquare("s1", 12, 10, 0);
        assertFalse(b2.isActive());
        assertEquals(LeaseStatus.INVALIDATED, inLease.getStatus());
        assertEquals(1, SmartObjectRegistry.getAdvertisedHaulOutSlots("s1").size());
        scope2.close();
    }

    @Test
    void workstationIoSymmetricBindingStandDirBackoffAndRoomDoors() {
        ContainerFilterRule ioRule = SmartZoningPolicy.buildRuleFromPreset("materials", ContainerRole.WORKSTATION_IO);
        // 先加载 WORKSTATION_IO（10, 10），再加载 3 格内 WORKSTATION（13, 12）与 4 格之外 WORKSTATION（14, 10）
        SmartSlot rack = SmartObjectRegistry.registerContainerSlot(
                "s1", "10:10:0#shelf:shelf:0#0", 10, 10, 0, 10L, 0b00000101, ioRule, 5, 50.0, 5.0, 1.0);
        SmartSlot benchNear = SmartObjectRegistry.registerWorkstationSlot("s1", 13, 12, 0, 10L, 0b11111111);
        SmartSlot benchFar = SmartObjectRegistry.registerWorkstationSlot("s1", 14, 10, 0, 10L, 0b11111111);

        assertTrue(rack.getBoundWorkstationIds().contains(benchNear.slotId));
        assertTrue(benchNear.getBoundWorkstationIoIds().contains(rack.slotId));
        assertFalse(rack.getBoundWorkstationIds().contains(benchFar.slotId));

        // 反向顺序：先加载 WORKSTATION（20, 20），再加载 WORKSTATION_IO（22, 21）
        SmartSlot bench2 = SmartObjectRegistry.registerWorkstationSlot("s1", 20, 20, 0, 10L, 0b11111111);
        SmartSlot rack2 = SmartObjectRegistry.registerContainerSlot(
                "s1", "22:21:0#shelf:shelf:0#0", 22, 21, 0, 10L, 0b11111111, ioRule, 1, 50.0, 1.0, 1.0);
        assertTrue(bench2.getBoundWorkstationIoIds().contains(rack2.slotId));
        assertTrue(rack2.getBoundWorkstationIds().contains(bench2.slotId));

        // 8 方位站位挑选与 30s 局部失败退避换侧
        int[] firstStand = SmartObjectRegistry.pickBestStandTile(rack, 10.5F, 8.0F, 5.0);
        assertEquals(0, firstStand[2]); // North (dx=0, dy=-1) -> (10, 9)
        SmartObjectRegistry.markStandDirFailed(rack, 0, 5.0);
        int[] secondStand = SmartObjectRegistry.pickBestStandTile(rack, 10.5F, 8.0F, 10.0);
        assertEquals(2, secondStand[2]); // 避开北侧 30s 退避改选 East (11, 10)
        int[] recoveredStand = SmartObjectRegistry.pickBestStandTile(rack, 10.5F, 8.0F, 36.0);
        assertEquals(0, recoveredStand[2]);

        // 同层门连通邻接分类
        RoomConnectivityIndex.recordDoorEdge("s1", 0, 10L, 20L);
        assertEquals(RoomTransition.SAME_ROOM, SmartObjectRegistry.classifyRoomTransition("s1", 0, 10L, 0, 10L));
        assertEquals(RoomTransition.DIRECT_DOOR, SmartObjectRegistry.classifyRoomTransition("s1", 0, 10L, 0, 20L));
        assertEquals(RoomTransition.INDIRECT_DETOUR, SmartObjectRegistry.classifyRoomTransition("s1", 0, 10L, 0, 30L));
        assertEquals(RoomTransition.CROSS_FLOOR, SmartObjectRegistry.classifyRoomTransition("s1", 0, 10L, 1, 10L));
    }

    @Test
    void smartItemPickerScoresFefoDurabilityBulkAndManagesSoftLocks() {
        // 易腐食物 FEFO：腐烂拒选，冰箱外临期 > 冰箱外新鲜 > 通电冰箱内临期
        double rotten = SmartItemPicker.scorePerishableFood(false, 9.0F, 8.0F, true, false);
        double counterNearSpoil = SmartItemPicker.scorePerishableFood(false, 6.0F, 8.0F, false, false);
        double counterFresh = SmartItemPicker.scorePerishableFood(false, 1.0F, 8.0F, false, false);
        double fridgeNearSpoil = SmartItemPicker.scorePerishableFood(true, 7.0F, 8.0F, false, false);
        assertEquals(Double.NEGATIVE_INFINITY, rotten);
        assertTrue(counterNearSpoil > counterFresh);
        assertTrue(counterFresh > fridgeNearSpoil);

        // 工具/消耗品：坏件拒选，高耐久/高余量优先
        assertEquals(Double.NEGATIVE_INFINITY, SmartItemPicker.scoreToolOrDrainable(0, 10, 0.0F, false, true));
        assertTrue(SmartItemPicker.scoreToolOrDrainable(9, 10, 1.0F, false, false)
                > SmartItemPicker.scoreToolOrDrainable(4, 10, 1.0F, false, false));
        assertTrue(SmartItemPicker.scoreToolOrDrainable(0, 0, 0.85F, true, false)
                > SmartItemPicker.scoreToolOrDrainable(0, 0, 0.25F, true, false));

        // 30s 软锁互斥与 StateScope 释放
        StateScope scope = new StateScope();
        assertTrue(SmartItemPicker.acquireItemSoftLock(7001, "s1", "npc_1", 10.0, 30.0, scope::defer));
        assertTrue(SmartItemPicker.isItemSoftLockedByOther(7001, "npc_2", 25.0));
        assertFalse(SmartItemPicker.acquireItemSoftLock(7001, "s1", "npc_2", 25.0, 30.0, null));
        assertFalse(SmartItemPicker.isItemSoftLockedByOther(7001, "npc_1", 25.0));
        assertFalse(SmartItemPicker.isItemSoftLockedByOther(7001, "npc_2", 40.5)); // 30s 过期自愈

        assertTrue(SmartItemPicker.acquireItemSoftLock(7002, "s1", "npc_1", 10.0, 30.0, scope::defer));
        scope.close();
        assertFalse(SmartItemPicker.isItemSoftLockedByOther(7002, "npc_2", 11.0));
    }
}
