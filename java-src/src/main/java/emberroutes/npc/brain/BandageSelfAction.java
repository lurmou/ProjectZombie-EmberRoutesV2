package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import java.util.List;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.BodyDamage.BodyPart;
import zombie.inventory.InventoryItem;

/**
 * 确定性自我包扎动作：使用背包真实绷带/消毒碎布原件止血并按原版 {@code BodyPart#setBandaged} 结算。
 */
public final class BandageSelfAction extends BaseAction {
    public BandageSelfAction() {
        super(2.8, "Bandage");
    }

    public static boolean hasBleedingUnbandagedPart(IsoNPC npc) {
        if (npc == null || npc.getBodyDamage() == null) {
            return false;
        }
        List<BodyPart> parts = npc.getBodyDamage().getBodyParts();
        if (parts == null) {
            return false;
        }
        for (int i = 0, n = parts.size(); i < n; i++) {
            BodyPart bp = parts.get(i);
            if (bp != null && bp.bleeding() && !bp.bandaged()) {
                return true;
            }
        }
        return false;
    }

    public static ItemLocation findBestBandage(IsoNPC npc) {
        if (npc == null || npc.getInventory() == null) {
            return null;
        }
        ItemLocation clean = findItemRecursive(npc.getInventory(), item -> isUsableBandage(item, false));
        if (clean != null) {
            return clean;
        }
        return findItemRecursive(npc.getInventory(), item -> isUsableBandage(item, true));
    }

    private static boolean isUsableBandage(InventoryItem item, boolean allowDirty) {
        if (item == null || TempPropHelper.isTempPropItem(item) || !item.isCanBandage()) {
            return false;
        }
        if (!allowDirty) {
            String ft = item.getFullType();
            if (ft != null && ft.contains("Dirty")) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected boolean onComplete(BrainContext ctx) {
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead()) {
            return false;
        }
        BodyDamage bd = npc.getBodyDamage();
        if (bd == null || bd.getBodyParts() == null) {
            return false;
        }
        BodyPart targetPart = null;
        List<BodyPart> parts = bd.getBodyParts();
        for (int i = 0, n = parts.size(); i < n; i++) {
            BodyPart bp = parts.get(i);
            if (bp != null && bp.bleeding() && !bp.bandaged()) {
                targetPart = bp;
                break;
            }
        }
        if (targetPart == null) {
            return false;
        }
        ItemLocation loc = findBestBandage(npc);
        if (loc == null) {
            return false;
        }
        InventoryItem item = loc.item();
        float power = item.getBandagePower() > 0.0F ? item.getBandagePower() : 4.0F;
        targetPart.setBandaged(true, power, item.isAlcoholic(), item.getFullType());
        if (npc.getPrimaryHandItem() == item) {
            npc.setPrimaryHandItem(null);
        }
        if (npc.getSecondaryHandItem() == item) {
            npc.setSecondaryHandItem(null);
        }
        item.Use();
        return true;
    }
}
