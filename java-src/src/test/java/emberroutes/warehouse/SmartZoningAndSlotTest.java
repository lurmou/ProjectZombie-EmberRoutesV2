package emberroutes.warehouse;

import emberroutes.npc.brain.StateScope;
import emberroutes.warehouse.RoomConnectivityIndex.RoomTransition;
import emberroutes.warehouse.SmartSlot.ContainerLease;
import emberroutes.warehouse.SmartSlot.LeaseStatus;
import emberroutes.warehouse.SmartZoningPolicy.ContainerFilterRule;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void fakeWeaponsDoNotClassifyDesksAsArmoryAndThreeRolesFilterCorrectly() {
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

        // 工坊旁备料架直接使用高优先级 STORAGE 规则，无需独立封闭料架角色
        ContainerFilterRule workshopCounter = SmartZoningPolicy.evaluateHardwareOrContext(
                "crate", "carpentry_01_00", "workshop", Map.of("materials", 4.0), 4);
        assertEquals(ContainerRole.STORAGE, workshopCounter.role);
        workshopCounter.priority = SmartZoningPolicy.clampPriority(5);
        assertEquals(5, workshopCounter.priority);
        assertTrue(SmartZoningPolicy.matchesItemNode(workshopCounter, "Base.Plank", "materials"));
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
    void workstationSlotStandDirBackoffAndRoomDoors() {
        ContainerFilterRule prepRule = SmartZoningPolicy.buildRuleFromPreset("materials", ContainerRole.STORAGE);
        prepRule.priority = 5;
        SmartSlot rack = SmartObjectRegistry.registerContainerSlot(
                "s1", "10:10:0#shelf:shelf:0#0", 10, 10, 0, 10L, 0b00000101, prepRule, 5, 50.0, 5.0, 1.0);
        SmartSlot bench = SmartObjectRegistry.registerWorkstationSlot("s1", 12, 11, 0, 10L, 0b11111111);

        assertTrue(rack.isActive());
        assertEquals(ContainerRole.STORAGE, rack.getContainerRole());
        assertEquals(5, rack.getPriority());
        assertTrue(bench.isActive());
        assertEquals(1, SmartObjectRegistry.getActiveCount("s1", SmartSlot.SlotKind.WORKSTATION));

        // 8 方位站位挑选与 30s 局部失败退避换侧
        int[] firstStand = SmartObjectRegistry.pickBestStandTile(rack, 10.5F, 8.0F, 5.0);
        assertEquals(0, firstStand[2]); // North (dx=0, dy=-1) -> (10, 9)
        SmartObjectRegistry.markStandDirFailed(rack, 0, 5.0);
        int[] secondStand = SmartObjectRegistry.pickBestStandTile(rack, 10.5F, 8.0F, 10.0);
        assertEquals(2, secondStand[2]); // 避开北侧 30s 退避改选 East (11, 10)
        int[] recoveredStand = SmartObjectRegistry.pickBestStandTile(rack, 10.5F, 8.0F, 36.0);
        assertEquals(0, recoveredStand[2]);

        // 正向优先：即使 NPC 从斜角靠近且 NE(bit1)/SE(bit3) 更近，只要四正方向可用仍优先挑 N/E 等正位；四正全屏蔽/退避时才退回斜角
        SmartSlot allEightRack = SmartObjectRegistry.registerContainerSlot(
                "s1", "20:20:0#shelf:shelf:0#0", 20, 20, 0, 10L, 0b11111111, prepRule, 5, 50.0, 5.0, 1.0);
        int[] cardOverDiag = SmartObjectRegistry.pickBestStandTile(allEightRack, 22.2F, 19.6F, 5.0);
        assertEquals(0, cardOverDiag[2] & 1); // 虽斜角 NE(dSq=0.50) 最近，四正可用仍优先正位 E(dSq=1.30)
        assertEquals(2, cardOverDiag[2]);
        SmartSlot diagOnlyRack = SmartObjectRegistry.registerContainerSlot(
                "s1", "22:22:0#shelf:shelf:0#0", 22, 22, 0, 10L, 0b10101010, prepRule, 5, 50.0, 5.0, 1.0);
        int[] diagFallback = SmartObjectRegistry.pickBestStandTile(diagOnlyRack, 24.2F, 21.6F, 5.0);
        assertEquals(1, diagFallback[2]);     // 四正全不可达时优雅退回 NE(1)

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

    @Test
    void fineGrainedCategoriesPrioritizeMedicalToolsAndMatchParentPresets() {
        // 手术刀/镊子/缝合针/持针器按最常用用途优先归入 medical.bandages，绝不滑入武器或文具
        assertFalse(SmartZoningPolicy.isFakeWeapon("Base.Scalpel"));
        assertEquals("medical.bandages", WarehouseFastIndex.classifyItemFacts(
                "Base.Scalpel", "Weapon", "FirstAidWeapon", Set.of("sharpenable", "hasmetal"),
                false, false, false, false, false, false, false, false, false));
        assertEquals("medical.bandages", WarehouseFastIndex.classifyItemFacts(
                "Base.Tweezers", "Normal", "FirstAid", Set.of("removebullet", "removeglass", "tweezers"),
                false, false, false, false, false, false, false, false, false));
        assertEquals("medical.bandages", WarehouseFastIndex.classifyItemFacts(
                "Base.SutureNeedle", "Normal", "FirstAid", Set.of("fitswallet"),
                false, false, false, false, false, false, false, false, false));
        assertEquals("medical.bandages", WarehouseFastIndex.classifyItemFacts(
                "Base.SutureNeedleHolder", "Normal", "FirstAid", Set.of("removebullet", "removeglass"),
                false, false, false, false, false, false, false, false, false));
        assertEquals("medical.pills", WarehouseFastIndex.classifyItemFacts(
                "Base.Pills", "Drainable", "FirstAid", Set.of("consumable"),
                false, false, false, false, false, false, false, false, false));
        assertEquals("medical.pills", WarehouseFastIndex.classifyItemFacts(
                "Base.Antibiotics", "Food", "FirstAid", Set.of(),
                true, false, false, false, false, false, false, false, false));

        // 食物四分流：腐烂->scrap.compost，熟食->food.meals，生鲜->food.perishable，罐头->food.stable
        assertEquals("scrap.compost", WarehouseFastIndex.classifyItemFacts(
                "Base.Apple", "Food", "Food", Set.of(),
                true, true, false, true, false, false, false, false, false));
        assertEquals("food.meals", WarehouseFastIndex.classifyItemFacts(
                "Base.StewPot", "Food", "Food", Set.of(),
                true, false, true, true, false, false, false, false, false));
        assertEquals("food.perishable", WarehouseFastIndex.classifyItemFacts(
                "Base.Apple", "Food", "Food", Set.of(),
                true, false, false, true, false, false, false, false, false));
        assertEquals("food.stable", WarehouseFastIndex.classifyItemFacts(
                "Base.TinnedBeans", "Food", "Food", Set.of(),
                true, false, false, false, false, false, false, false, false));

        // 文献、穿戴/背包、武器配件、电子材料与套组细分
        assertEquals("literature.skills", WarehouseFastIndex.classifyItemFacts(
                "Base.BookCarpentry1", "Literature", "SkillBook", Set.of("hardcover"),
                false, false, false, false, true, false, false, false, false));
        assertEquals("literature.entertainment", WarehouseFastIndex.classifyItemFacts(
                "Base.ComicBook", "Literature", "Literature", Set.of("magazine"),
                false, false, false, false, true, false, false, false, false));
        assertEquals("apparel.bags", WarehouseFastIndex.classifyItemFacts(
                "Base.Bag_DuffelBag", "Container", "Bag", Set.of("duffelbag"),
                false, false, false, false, false, true, false, false, false));
        assertEquals("apparel.armor", WarehouseFastIndex.classifyItemFacts(
                "Base.Vest_BulletCivilian", "Clothing", "ProtectiveGear", Set.of("wearable"),
                false, false, false, false, false, false, true, false, false));
        assertEquals("apparel.clothing", WarehouseFastIndex.classifyItemFacts(
                "Base.Tshirt_DefaultDECAL", "Clothing", "Clothing", Set.of("wearable"),
                false, false, false, false, false, false, true, false, false));
        assertEquals("weapons.parts", WarehouseFastIndex.classifyItemFacts(
                "Base.RedDot", "WeaponPart", "WeaponPart", Set.of(),
                false, false, false, false, false, false, false, false, false));
        assertEquals("kits.tailoring", WarehouseFastIndex.classifyItemFacts(
                "Base.Needle", "Normal", "Tool", Set.of("sewingneedle"),
                false, false, false, false, false, false, false, false, false));
        assertEquals("kits.mechanic", WarehouseFastIndex.classifyItemFacts(
                "Base.LugWrench", "Weapon", "VehicleMaintenanceWeapon", Set.of("lugwrench"),
                false, false, false, false, false, false, false, false, false));
        assertEquals("materials.electronics", WarehouseFastIndex.classifyItemFacts(
                "Base.Battery", "Drainable", "Electronics", Set.of(),
                false, false, false, false, false, false, false, false, false));

        // 冰箱规则：允许 food.perishable 与 food.meals，拒收 scrap.compost 与 food.stable
        ContainerFilterRule fridge = SmartZoningPolicy.evaluateHardwareOrContext(
                "fridge", "appliances_refrigeration_01", "kitchen", Map.of(), 0);
        assertTrue(SmartZoningPolicy.matchesItemNode(fridge, "Base.Apple", "food.perishable"));
        assertTrue(SmartZoningPolicy.matchesItemNode(fridge, "Base.StewPot", "food.meals"));
        assertFalse(SmartZoningPolicy.matchesItemNode(fridge, "Base.Apple", "scrap.compost"));
        assertFalse(SmartZoningPolicy.matchesItemNode(fridge, "Base.TinnedBeans", "food.stable"));

        // 大类预设前向兼容所有细分小类
        ContainerFilterRule medAll = SmartZoningPolicy.buildRuleFromPreset("medical", ContainerRole.STORAGE);
        assertTrue(SmartZoningPolicy.matchesItemNode(medAll, "Base.Scalpel", "medical.bandages"));
        assertTrue(SmartZoningPolicy.matchesItemNode(medAll, "Base.Antibiotics", "medical.pills"));

        ContainerFilterRule kitAll = SmartZoningPolicy.buildRuleFromPreset("kits", ContainerRole.STORAGE);
        assertTrue(SmartZoningPolicy.matchesItemNode(kitAll, "Base.Needle", "kits.tailoring"));
        assertTrue(SmartZoningPolicy.matchesItemNode(kitAll, "Base.LugWrench", "kits.mechanic"));

        ContainerFilterRule matAll = SmartZoningPolicy.buildRuleFromPreset("materials", ContainerRole.STORAGE);
        assertTrue(SmartZoningPolicy.matchesItemNode(matAll, "Base.Battery", "materials.electronics"));
    }
}
