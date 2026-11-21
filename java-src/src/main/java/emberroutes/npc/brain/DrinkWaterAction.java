package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import zombie.entity.ComponentType;
import zombie.entity.components.fluids.FluidContainer;
import zombie.inventory.InventoryItem;

/**
 * 确定性饮水动作：扫描背包真实 {@link FluidContainer} 清洁水源原件并调用 {@code npc.DrinkFluid}。
 */
public final class DrinkWaterAction extends BaseAction {
    public DrinkWaterAction() {
        super(2.2, "drink");
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
    protected boolean onComplete(BrainContext ctx) {
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead()) {
            return false;
        }
        ItemLocation loc = findCleanWaterContainer(npc);
        if (loc == null) {
            return false;
        }
        InventoryItem item = loc.item();
        float pct = Math.min(1.0F, Math.max(0.25F, ctx.thirst));
        return npc.DrinkFluid(item, pct);
    }
}
