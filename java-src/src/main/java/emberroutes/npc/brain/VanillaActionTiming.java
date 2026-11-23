package emberroutes.npc.brain;

/**
 * 原版 B42 TimedAction 耗时纯 Java 映射（1:1 对齐 {@code ISInventoryTransferAction / ISEatFoodAction /
 * ISDrinkFluidAction / ISApplyBandage}，零 {@code zombie.*} 依赖以便 Gradle 脱机单测）。
 */
public final class VanillaActionTiming {
    /** 原版 {@code BaseAction.update} 使用 {@code GameTime.instance.getMultiplier()}，1x 模拟秒对应 30 刻。 */
    public static final double TICKS_PER_SIM_SEC = 30.0;

    private VanillaActionTiming() {}

    /**
     * 对齐 {@code ISInventoryTransferAction:new}（L789-L838）计算单件物品转移耗时（模拟秒）。
     */
    public static double computeInventoryTransferSeconds(
            double actualWeight,
            boolean isSelfBagPacking,
            double destCapacityRatio,
            boolean dextrous,
            boolean allThumbsOrAwkwardGloves,
            boolean toFloor,
            boolean fromMainInv) {
        double baseTicks = isSelfBagPacking ? 120.0 : 50.0;
        double capDelta = isSelfBagPacking ? Math.max(0.4, Math.min(1.0, destCapacityRatio)) : 1.0;
        double w = Math.min(3.0, Math.max(0.05, actualWeight));
        double ticks = baseTicks * w * capDelta;

        if (toFloor) {
            ticks *= fromMainInv ? 0.1 : 0.2;
        }
        if (dextrous) {
            ticks *= 0.5;
        }
        if (allThumbsOrAwkwardGloves) {
            ticks *= 2.0;
        }
        return Math.max(0.05, ticks / TICKS_PER_SIM_SEC);
    }

    /**
     * 对齐 {@code ISEatFoodAction:getDuration()}（L208-L253）计算进食动作耗时（模拟秒）。
     */
    public static double computeEatFoodSeconds(
            double baseHunger,
            double hungerChange,
            double thirstChange,
            double percentage,
            boolean useUtensil,
            boolean isDrinkOption,
            int customEatTime,
            String eatType) {
        double pct = Math.max(0.0, Math.min(1.0, percentage));
        double consumed = isDrinkOption
                ? Math.abs(thirstChange * pct * 100.0)
                : Math.abs(baseHunger * pct * 100.0);
        int loop = 1;
        if (isDrinkOption) {
            if (consumed >= 3.0) {
                loop = 2;
            }
            if (consumed >= 6.0) {
                loop = 3;
            }
        } else {
            if (consumed >= 30.0) {
                loop = 2;
            }
            if (consumed >= 80.0) {
                loop = 3;
            }
        }
        if (useUtensil && loop >= 2) {
            loop -= 1;
        }
        double timerForOne = isDrinkOption ? 171.0 : 232.0;
        double ticks = timerForOne * loop;
        if (consumed == 0.0) {
            ticks = 460.0;
        }
        if (customEatTime > 0) {
            ticks = customEatTime;
        }
        if ("popcan".equalsIgnoreCase(eatType)) {
            ticks = 160.0;
        }
        return Math.max(0.05, ticks / TICKS_PER_SIM_SEC);
    }

    /**
     * 对齐 {@code ISDrinkFluidAction:new()}（L145-L170）计算流体容器饮水动作耗时（模拟秒）。
     */
    public static double computeDrinkFluidSeconds(
            double fluidHungerChange,
            double percentage,
            String eatType) {
        double pct = Math.max(0.0, Math.min(1.0, percentage));
        double consumed = Math.abs(fluidHungerChange * pct * 100.0);
        int loop = 1;
        if (consumed >= 30.0) {
            loop = 2;
        }
        if (consumed >= 80.0) {
            loop = 3;
        }
        double ticks = 232.0 * loop;
        if (consumed == 0.0) {
            ticks = 460.0;
        }
        if ("popcan".equalsIgnoreCase(eatType)) {
            ticks = 160.0;
        }
        return Math.max(0.05, ticks / TICKS_PER_SIM_SEC);
    }

    /**
     * 对齐 {@code ISApplyBandage:getDuration()}（L161）计算包扎动作耗时（模拟秒）。
     */
    public static double computeApplyBandageSeconds(int doctorLevel) {
        int lvl = Math.max(0, doctorLevel);
        double ticks = Math.max(1.0, 120.0 - lvl * 4.0);
        return ticks / TICKS_PER_SIM_SEC;
    }
}
