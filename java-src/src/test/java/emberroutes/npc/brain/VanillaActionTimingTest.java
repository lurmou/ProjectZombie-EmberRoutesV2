package emberroutes.npc.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VanillaActionTimingTest {
    private static final double EPS = 1e-6;

    @Test
    void inventoryTransferMatchesVanillaWeightCapAndTraits() {
        // 标准箱↔人基础 50 刻 / 30 = 1.6667s（重量 1.0）
        assertEquals(50.0 / 30.0,
                VanillaActionTiming.computeInventoryTransferSeconds(1.0, false, 1.0, false, false, false, false),
                EPS);

        // 重量上限封顶 3.0（5.0 kg 按 3.0 计算）
        assertEquals(150.0 / 30.0,
                VanillaActionTiming.computeInventoryTransferSeconds(5.0, false, 1.0, false, false, false, false),
                EPS);

        // Dextrous 减半 / AllThumbs 或笨重手套翻倍
        assertEquals(25.0 / 30.0,
                VanillaActionTiming.computeInventoryTransferSeconds(1.0, false, 1.0, true, false, false, false),
                EPS);
        assertEquals(100.0 / 30.0,
                VanillaActionTiming.computeInventoryTransferSeconds(1.0, false, 1.0, false, true, false, false),
                EPS);

        // 自身主包<->随身背包：120 刻，满载系数钳位至 [0.4, 1.0]
        assertEquals((120.0 * 1.0 * 0.4) / 30.0,
                VanillaActionTiming.computeInventoryTransferSeconds(1.0, true, 0.1, false, false, false, true),
                EPS);
        assertEquals((120.0 * 2.0 * 0.75) / 30.0,
                VanillaActionTiming.computeInventoryTransferSeconds(2.0, true, 0.75, false, false, false, true),
                EPS);

        // 扔到地面（主包 0.1x，非主包 0.2x）
        assertEquals((50.0 * 1.0 * 0.1) / 30.0,
                VanillaActionTiming.computeInventoryTransferSeconds(1.0, false, 1.0, false, false, true, true),
                EPS);
        assertEquals((50.0 * 2.0 * 0.2) / 30.0,
                VanillaActionTiming.computeInventoryTransferSeconds(2.0, false, 1.0, false, false, true, false),
                EPS);
    }

    @Test
    void eatFoodMatchesVanillaLoopsUtensilAndPopcan() {
        // 小食（<30 饥饿）：1 轮 = 232 刻
        assertEquals(232.0 / 30.0,
                VanillaActionTiming.computeEatFoodSeconds(-0.15, -0.15, 0.0, 1.0, false, false, 0, null),
                EPS);

        // 中份（>=30 饥饿）：2 轮 = 464 刻；带餐具减 1 轮为 232 刻
        assertEquals(464.0 / 30.0,
                VanillaActionTiming.computeEatFoodSeconds(-0.40, -0.40, 0.0, 1.0, false, false, 0, "Plate"),
                EPS);
        assertEquals(232.0 / 30.0,
                VanillaActionTiming.computeEatFoodSeconds(-0.40, -0.40, 0.0, 1.0, true, false, 0, "Plate"),
                EPS);

        // 大餐（>=80 饥饿）：3 轮；带餐具减为 2 轮
        assertEquals((232.0 * 3) / 30.0,
                VanillaActionTiming.computeEatFoodSeconds(-0.90, -0.90, 0.0, 1.0, false, false, 0, null),
                EPS);
        assertEquals((232.0 * 2) / 30.0,
                VanillaActionTiming.computeEatFoodSeconds(-0.90, -0.90, 0.0, 1.0, true, false, 0, null),
                EPS);

        // 饮料菜单选项按 thirstChange * 100 分档（171 刻），且 popcan 覆盖为 160 刻
        assertEquals((171.0 * 2) / 30.0,
                VanillaActionTiming.computeEatFoodSeconds(0.0, 0.0, -0.04, 1.0, false, true, 0, "bottle"),
                EPS);
        assertEquals(160.0 / 30.0,
                VanillaActionTiming.computeEatFoodSeconds(-0.10, -0.10, -0.10, 1.0, false, true, 0, "popcan"),
                EPS);
    }

    @Test
    void drinkFluidMatchesVanillaZeroHungerWaterAndPopcan() {
        // 纯水 fluidHungerChange = 0 -> consumed == 0 -> 460 刻
        assertEquals(460.0 / 30.0,
                VanillaActionTiming.computeDrinkFluidSeconds(0.0, 0.5, "bottle"),
                EPS);
        // 含饥饿度流体 >=30 -> 2 轮 = 464 刻
        assertEquals((232.0 * 2) / 30.0,
                VanillaActionTiming.computeDrinkFluidSeconds(-0.50, 0.8, "bottle"),
                EPS);
        // popcan -> 160 刻
        assertEquals(160.0 / 30.0,
                VanillaActionTiming.computeDrinkFluidSeconds(0.0, 1.0, "Popcan"),
                EPS);
    }

    @Test
    void applyBandageMatchesDoctorPerkLevel() {
        assertEquals(120.0 / 30.0, VanillaActionTiming.computeApplyBandageSeconds(0), EPS);
        assertEquals(100.0 / 30.0, VanillaActionTiming.computeApplyBandageSeconds(5), EPS);
        assertEquals(80.0 / 30.0, VanillaActionTiming.computeApplyBandageSeconds(10), EPS);
    }
}
