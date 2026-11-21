package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import zombie.inventory.types.Food;

/**
 * 确定性进食动作：从背包挑选真实可食且未腐烂/烧焦/危险生食的 {@link Food} 原件并调用 {@code npc.Eat}。
 */
public final class EatFoodAction extends BaseAction {
    public EatFoodAction() {
        super(3.0, "eat");
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

    @Override
    protected boolean onComplete(BrainContext ctx) {
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead()) {
            return false;
        }
        ItemLocation loc = findBestEdibleFood(npc);
        if (loc == null || !(loc.item() instanceof Food food)) {
            return false;
        }
        float absHung = Math.max(0.05F, Math.abs(food.getHungChange()));
        float ratio = Math.min(1.0F, Math.max(0.25F, ctx.hunger / absHung));
        return npc.Eat(food, ratio);
    }
}
