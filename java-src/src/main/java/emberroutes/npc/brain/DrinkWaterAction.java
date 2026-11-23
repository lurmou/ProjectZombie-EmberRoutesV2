package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import zombie.characters.CharacterStat;
import zombie.entity.ComponentType;
import zombie.entity.components.fluids.FluidContainer;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.Food;

/**
 * 确定性饮水动作（100% 对齐原版 {@code ISDrinkFluidAction} 与 {@code IsoGameCharacter#autoDrink}）：
 * <ul>
 *   <li>根据 {@code item.getEatType()} 设置 {@code FoodType}（{@code Pot / PotForged} 挂右手，其余默认 {@code "bottle"} 挂左手）并播放 {@code getCustomDrinkSound()}；</li>
 *   <li>按原版 {@code 1.0L = 0.50 Thirst}（{@code amountNeeded = thirst * 2.0F}）计算目标消耗比例，并在 {@code onTick} 中逐帧增量调用 {@code npc.DrinkFluid}。</li>
 * </ul>
 */
public final class DrinkWaterAction extends BaseAction {
    private InventoryItem activeItem;
    private FluidContainer activeContainer;
    private float startRatio;
    private float targetConsumedRatio;
    private String drinkSound;

    public DrinkWaterAction() {
        super(2.2, "drink", "FoodType", "bottle");
        setReportEventName("EventEating");
    }

    public static ItemLocation findCleanWaterContainer(IsoNPC npc) {
        if (npc == null || npc.getInventory() == null) {
            return null;
        }
        return findItemRecursive(npc.getInventory(), item -> {
            if (item == null || TempPropHelper.isTempPropItem(item) || !item.hasComponent(ComponentType.FluidContainer)) {
                return false;
            }
            FluidContainer fc = item.getFluidContainer();
            return fc != null
                    && !fc.isEmpty()
                    && fc.isWaterSource()
                    && !fc.isTainted()
                    && !fc.isPoisonous();
        });
    }

    @Override
    protected void onStart(BrainContext ctx) {
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead()) {
            return;
        }
        ItemLocation loc = findCleanWaterContainer(npc);
        if (loc == null || npc.getInventory() == null || !npc.getInventory().contains(loc.item())) {
            return;
        }
        this.activeItem = loc.item();
        this.activeContainer = activeItem.getFluidContainer();
        if (this.activeContainer == null || this.activeContainer.isEmpty()) {
            return;
        }
        float curThirst = npc.getStats() != null ? npc.getStats().get(CharacterStat.THIRST) : ctx.thirst;
        // 原版 fluids.txt: Fluid.Water ThirstChange = -50.0 -> 1.0L 降低 0.50 口渴 (autoDrink: amountNeeded = thirst * 2.0F)
        float amountNeeded = Math.max(0.20F, curThirst * 2.0F);
        float availAmount = Math.max(0.001F, activeContainer.getAmount());
        float percentage = Math.min(1.0F, Math.max(0.15F, amountNeeded / availAmount));

        this.startRatio = activeContainer.getFilledRatio();
        float endRatio = this.startRatio * (1.0F - percentage);
        this.targetConsumedRatio = Math.max(0.0F, this.startRatio - endRatio);

        double fluidHung = activeContainer.getProperties() != null
                ? activeContainer.getProperties().getHungerChange()
                : 0.0;
        setDurationSimSec(VanillaActionTiming.computeDrinkFluidSeconds(
                fluidHung, percentage, activeItem.getEatType()));

        String eatType = activeItem.getEatType();
        if (eatType != null && !eatType.isBlank()) {
            setExtraAnimVar("FoodType", eatType);
            if ("Pot".equals(eatType) || "PotForged".equals(eatType)) {
                configureHandModels(activeItem, null);
            } else {
                configureHandModels(null, activeItem);
            }
        } else {
            setExtraAnimVar("FoodType", "bottle");
            configureHandModels(null, activeItem);
        }

        this.drinkSound = activeContainer.getCustomDrinkSound();
        if ("DrinkingFromMug".equals(this.drinkSound)) {
            float heat;
            if (activeItem instanceof Food f) {
                heat = f.getHeat();
            } else if (activeItem instanceof DrainableComboItem d) {
                heat = d.getHeat();
            } else {
                heat = activeItem.getItemHeat();
            }
            if (heat > 1.0F) {
                this.drinkSound = "DrinkingFromHotTeaCup";
            }
        }
        if (this.drinkSound != null && !this.drinkSound.isBlank()) {
            playActionSound(npc, this.drinkSound);
        }
    }

    @Override
    protected void onTick(BrainContext ctx, double dtSim) {
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead()) {
            return;
        }
        ensureActionSoundPlaying(npc, this.drinkSound);
        updateDrinkStep(npc, getProgress01());
    }

    @Override
    protected boolean onComplete(BrainContext ctx) {
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead()) {
            return false;
        }
        if (activeItem == null || activeContainer == null) {
            ItemLocation loc = findCleanWaterContainer(npc);
            if (loc == null) {
                return false;
            }
            activeItem = loc.item();
            activeContainer = activeItem.getFluidContainer();
            if (activeContainer == null || activeContainer.isEmpty()) {
                return false;
            }
            float curThirst = npc.getStats() != null ? npc.getStats().get(CharacterStat.THIRST) : ctx.thirst;
            float pct = Math.min(1.0F, Math.max(0.20F, (curThirst * 2.0F) / Math.max(0.001F, activeContainer.getAmount())));
            return npc.DrinkFluid(activeItem, pct, false);
        }
        updateDrinkStep(npc, 1.0F);
        return true;
    }

    private void updateDrinkStep(IsoNPC npc, float delta01) {
        if (activeItem == null || activeContainer == null || npc.getInventory() == null || !npc.getInventory().contains(activeItem)) {
            return;
        }
        float curFilledRatio = activeContainer.getFilledRatio();
        if (curFilledRatio <= 1.0e-5F) {
            return;
        }
        float targetRatio = Math.min(1.0F, Math.max(0.0F, delta01)) * targetConsumedRatio;
        float consumedRatio = startRatio - curFilledRatio;
        float ratioToConsume = targetRatio - consumedRatio;
        if (ratioToConsume <= 1.0e-4F) {
            return;
        }
        float deltaToConsume = Math.min(1.0F, ratioToConsume / curFilledRatio);
        npc.DrinkFluid(activeItem, deltaToConsume, false);
    }
}
