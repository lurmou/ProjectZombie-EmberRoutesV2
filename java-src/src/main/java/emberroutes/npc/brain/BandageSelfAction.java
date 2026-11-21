package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import java.util.List;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.BodyDamage.BodyPart;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.inventory.InventoryItem;

/**
 * 确定性自我包扎动作（100% 对齐原版 {@code ISApplyBandage} 与 {@code ISHealthPanel.getBandageType}）：
 * <ul>
 *   <li>根据受伤部位映射 {@code BandageType}（{@code Head / LowerBody / UpperBody / LeftArm / RightArm / LeftLeg / RightLeg}）并隐藏手部武器模型；</li>
 *   <li>播放 {@code FirstAidApplyBandage} 音效，使用背包真实绷带/消毒碎布原件止血并按原版 {@code BodyPart#setBandaged} 结算。</li>
 * </ul>
 */
public final class BandageSelfAction extends BaseAction {
    public BandageSelfAction() {
        super(2.8, "Bandage", "BandageType", "LeftArm");
    }

    public static boolean hasBleedingUnbandagedPart(IsoNPC npc) {
        return findFirstBleedingUnbandagedPart(npc) != null;
    }

    public static BodyPart findFirstBleedingUnbandagedPart(IsoNPC npc) {
        if (npc == null || npc.getBodyDamage() == null) {
            return null;
        }
        List<BodyPart> parts = npc.getBodyDamage().getBodyParts();
        if (parts == null) {
            return null;
        }
        for (int i = 0, n = parts.size(); i < n; i++) {
            BodyPart bp = parts.get(i);
            if (bp != null && bp.bleeding() && !bp.bandaged()) {
                return bp;
            }
        }
        return null;
    }

    /** 严格对齐原版 {@code ISHealthPanel.getBandageType(bodyPart)} 部位动画节点映射。 */
    public static String resolveBandageType(BodyPart bodyPart) {
        if (bodyPart == null || bodyPart.getType() == null) {
            return "LeftArm";
        }
        BodyPartType type = bodyPart.getType();
        if (type == BodyPartType.Head || type == BodyPartType.Neck) {
            return "Head";
        }
        if (type == BodyPartType.Torso_Lower || type == BodyPartType.Groin) {
            return "LowerBody";
        }
        if (type == BodyPartType.Torso_Upper) {
            return "UpperBody";
        }
        if (type == BodyPartType.Hand_L || type == BodyPartType.ForeArm_L || type == BodyPartType.UpperArm_L) {
            return "LeftArm";
        }
        if (type == BodyPartType.Hand_R || type == BodyPartType.ForeArm_R || type == BodyPartType.UpperArm_R) {
            return "RightArm";
        }
        if (type == BodyPartType.UpperLeg_L || type == BodyPartType.LowerLeg_L || type == BodyPartType.Foot_L) {
            return "LeftLeg";
        }
        if (type == BodyPartType.UpperLeg_R || type == BodyPartType.LowerLeg_R || type == BodyPartType.Foot_R) {
            return "RightLeg";
        }
        return "LeftArm";
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
    protected void onStart(BrainContext ctx) {
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead()) {
            return;
        }
        BodyPart targetPart = findFirstBleedingUnbandagedPart(npc);
        setExtraAnimVar("BandageType", resolveBandageType(targetPart));
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
        BodyPart targetPart = findFirstBleedingUnbandagedPart(npc);
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
