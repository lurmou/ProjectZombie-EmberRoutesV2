package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import zombie.characters.CharacterStat;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.Food;

/**
 * 确定性进食动作（100% 对齐原版 {@code ISEatFoodAction}）：
 * <ul>
 *   <li>从背包挑选真实可食且未腐烂/烧焦/危险生食的 {@link Food} 原件，自动匹配随身汤匙/叉子（{@code Base.Spoon / Base.Fork}）；</li>
 *   <li>按原版 {@code ISEatFoodAction:start()} 映射 {@code FoodType}、挂载双手模型并播放 {@code getCustomEatSound()}；</li>
 *   <li>中途打断（{@code onInterrupt}）时按原版 {@code ISEatFoodAction:serverStop()} 比例部分进食。</li>
 * </ul>
 */
public final class EatFoodAction extends BaseAction {
    private Food activeFood;
    private float targetPercentage = 1.0F;
    private boolean useUtensil;
    private String eatSound;

    public EatFoodAction() {
        super(3.0, "eat");
        setReportEventName("EventEating");
    }

    public static ItemLocation findBestEdibleFood(IsoNPC npc) {
        if (npc == null || npc.getInventory() == null) {
            return null;
        }
        return findItemRecursive(npc.getInventory(), item ->
                !TempPropHelper.isTempPropItem(item)
                        && item instanceof Food food
                        && !food.isRotten()
                        && !food.isBurnt()
                        && !food.isbDangerousUncooked()
                        && food.getHungChange() < -0.005F);
    }

    private static InventoryItem findUtensil(IsoNPC npc, String typeName) {
        if (npc == null || npc.getInventory() == null) {
            return null;
        }
        ItemLocation loc = findItemRecursive(npc.getInventory(), item ->
                item != null
                        && !TempPropHelper.isTempPropItem(item)
                        && !item.isBroken()
                        && (typeName.equals(item.getFullType()) || typeName.endsWith("." + item.getType())));
        return loc != null ? loc.item() : null;
    }

    @Override
    protected void onStart(BrainContext ctx) {
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead()) {
            return;
        }
        ItemLocation loc = findBestEdibleFood(npc);
        if (loc == null || !(loc.item() instanceof Food food) || !unpackToMainInventoryIfNeeded(npc, loc)) {
            return;
        }
        this.activeFood = food;
        float curHunger = npc.getStats() != null ? npc.getStats().get(CharacterStat.HUNGER) : ctx.hunger;
        float absHung = Math.max(0.05F, Math.abs(food.getHungChange()));
        this.targetPercentage = Math.min(1.0F, Math.max(0.25F, curHunger / absHung));

        InventoryItem spoon = findUtensil(npc, "Base.Spoon");
        InventoryItem fork = findUtensil(npc, "Base.Fork");
        InventoryItem secondItem = null;
        String eatType = food.getEatType();
        String foodTypeVar = null;

        if (eatType != null && !eatType.isBlank()) {
            switch (eatType) {
                case "2handbowl" -> {
                    if (spoon != null) {
                        foodTypeVar = "2handbowl";
                        secondItem = spoon;
                    } else {
                        foodTypeVar = "bowl";
                    }
                }
                case "Can", "Candrink", "2hand", "Plate" -> {
                    secondItem = fork != null ? fork : spoon;
                    if ("Plate".equals(eatType)) {
                        foodTypeVar = secondItem != null ? "plate" : "NoSpoon";
                    } else if ("2hand".equals(eatType)) {
                        foodTypeVar = "2hand";
                    } else if ("Candrink".equals(eatType)) {
                        foodTypeVar = secondItem != null ? "can" : "drink";
                    } else {
                        foodTypeVar = eatType;
                    }
                }
                case "plate" -> foodTypeVar = "Plate";
                case "Popcan" -> foodTypeVar = "drink";
                case "EatSmall" -> foodTypeVar = "EatSmall";
                case "EatBox" -> foodTypeVar = "eatBox";
                default -> foodTypeVar = eatType;
            }
        }

        this.useUtensil = (secondItem != null);
        if (foodTypeVar != null && !foodTypeVar.isBlank()) {
            setExtraAnimVar("FoodType", foodTypeVar);
        }
        if ("Pot".equals(eatType) || "PotForged".equals(eatType)) {
            configureHandModels(food, null);
        } else {
            configureHandModels(secondItem, food);
        }

        String customSound = food.getCustomEatSound();
        this.eatSound = (customSound != null && !customSound.isBlank()) ? customSound : "Eating";
        playActionSound(npc, this.eatSound);
    }

    @Override
    protected void onTick(BrainContext ctx, double dtSim) {
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc != null && !npc.isDead()) {
            ensureActionSoundPlaying(npc, this.eatSound);
        }
    }

    @Override
    protected boolean onComplete(BrainContext ctx) {
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead()) {
            return false;
        }
        Food food = this.activeFood;
        if (food == null) {
            ItemLocation loc = findBestEdibleFood(npc);
            if (loc == null || !(loc.item() instanceof Food fallback)) {
                return false;
            }
            food = fallback;
            float curHunger = npc.getStats() != null ? npc.getStats().get(CharacterStat.HUNGER) : ctx.hunger;
            float absHung = Math.max(0.05F, Math.abs(food.getHungChange()));
            this.targetPercentage = Math.min(1.0F, Math.max(0.25F, curHunger / absHung));
        }
        return npc.Eat(food, this.targetPercentage, this.useUtensil);
    }

    @Override
    protected void onInterrupt(BrainContext ctx, String reason) {
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead() || activeFood == null) {
            return;
        }
        float progress = getProgress01();
        if (progress <= 0.15F) {
            return;
        }
        float hungerChangePct = Math.abs(activeFood.getHungerChange() * 100.0F);
        if (hungerChangePct <= 1.0F) {
            return;
        }
        float finalPct = (progress > 0.95F ? 1.0F : progress) * this.targetPercentage;
        npc.Eat(activeFood, finalPct, this.useUtensil);
    }
}
