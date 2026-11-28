package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.brain.BaseAction;
import emberroutes.npc.brain.BrainContext;
import emberroutes.npc.brain.VanillaActionTiming;
import zombie.core.skinnedmodel.advancedanimation.AnimEvent;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.HandWeapon;

/**
 * 纯 Java B42 原生武器拔出 / 换装 {@link BaseAction}（1:1 对齐原版 {@code ISEquipWeaponAction} & {@code ISHotbarAttachDefinition.lua}）。
 *
 * <ul>
 *   <li>统一继承仓库 {@link BaseAction} 纳入 {@code BrainContext.activeAction} 生命周期；</li>
 *   <li>按槽位映射精确 {@code AttachAnim}（{@code "belt left" / "belt right" / "holster right" / "holster left" / "back"}），
 *       并在 {@code detachConnect} 动画事件当帧摘除挂饰、切换手模与 {@code overrideWeaponType()}；</li>
 *   <li>必须在主物品栏存在目标武器原件，严禁瞬移换装。</li>
 * </ul>
 */
public final class NpcEquipWeaponAction extends BaseAction {
    private static final double HOTBAR_EQUIP_SIM_SEC = 25.0 / VanillaActionTiming.TICKS_PER_SIM_SEC;
    private static final double INVENTORY_EQUIP_SIM_SEC = 50.0 / VanillaActionTiming.TICKS_PER_SIM_SEC;

    private final HandWeapon targetWeapon;
    private boolean fromAttachedSlot;
    private boolean detachedConnected;

    public NpcEquipWeaponAction(IsoNPC npc, HandWeapon targetWeapon) {
        super(npc, INVENTORY_EQUIP_SIM_SEC, null);
        this.targetWeapon = targetWeapon;
        this.stopOnWalk = false;
        this.stopOnRun = true;
        this.stopOnAim = false;
    }

    public HandWeapon getTargetWeapon() {
        return targetWeapon;
    }

    /** 1:1 对齐 {@code ISHotbarAttachDefinition.lua} 映射挂槽到原版带空格的 {@code AttachAnim} 值。 */
    public static String resolveAttachAnim(HandWeapon weapon) {
        if (weapon == null) {
            return null;
        }
        String slotType = weapon.getAttachedSlotType();
        if (slotType == null || slotType.isEmpty()) {
            return null;
        }
        return switch (slotType) {
            case "SmallBeltLeft" -> "belt left";
            case "SmallBeltRight" -> "belt right";
            case "HolsterRight", "WebbingRight", "HolsterAnkle" -> "holster right";
            case "HolsterLeft", "HolsterShoulder", "WebbingLeft" -> "holster left";
            case "Back", "Bag", "BedrollBottom", "BedrollBottomBig", "BedrollBottomALICE" -> "back";
            default -> null;
        };
    }

    @Override
    public boolean valid() {
        return super.valid()
                && targetWeapon != null
                && npc.getInventory() != null
                && npc.getInventory().contains(targetWeapon);
    }

    @Override
    protected void onStart(BrainContext ctx) {
        this.detachedConnected = false;
        if (!valid()) {
            failOnStart();
            return;
        }
        boolean twoHands = targetWeapon.isTwoHandWeapon() || targetWeapon.isRequiresEquippedBothHands();
        boolean alreadyEquipped = npc.getPrimaryHandItem() == targetWeapon
                && (!twoHands || npc.getSecondaryHandItem() == targetWeapon);
        if (alreadyEquipped) {
            forceComplete();
            return;
        }

        this.fromAttachedSlot = npc.isAttachedItem(targetWeapon);
        String attachAnim = this.fromAttachedSlot ? resolveAttachAnim(targetWeapon) : null;
        if (this.fromAttachedSlot && attachAnim != null) {
            setDurationSimSec(HOTBAR_EQUIP_SIM_SEC);
            setAnimVariable("AttachAnim", attachAnim);
            npc.setVariable("AttachItemSpeed", 1.0F);
            setPerformingActionAnim("DetachItem");
        } else {
            setDurationSimSec(INVENTORY_EQUIP_SIM_SEC);
            setPerformingActionAnim("EquipItem");
        }
        setReportEventName("EventAttachItem");

        String sound = targetWeapon.getEquipSound();
        if (sound != null && !sound.isEmpty()) {
            playActionSound(npc, sound);
        }
    }

    @Override
    public void OnAnimEvent(AnimEvent event) {
        if (event == null || !isRunning() || npc == null || targetWeapon == null) {
            return;
        }
        if ("detachConnect".equals(event.eventName) && fromAttachedSlot && !detachedConnected) {
            detachedConnected = true;
            if (npc.isAttachedItem(targetWeapon)) {
                npc.removeAttachedItem(targetWeapon);
            }
            boolean twoHands = targetWeapon.isTwoHandWeapon() || targetWeapon.isRequiresEquippedBothHands();
            configureHandModels(targetWeapon, twoHands ? targetWeapon : null);
            overrideWeaponType();
        }
    }

    @Override
    protected boolean onComplete(BrainContext ctx) {
        if (npc == null || targetWeapon == null || npc.getInventory() == null || !npc.getInventory().contains(targetWeapon)) {
            cleanupVars();
            return false;
        }
        if (fromAttachedSlot && npc.isAttachedItem(targetWeapon)) {
            npc.removeAttachedItem(targetWeapon);
        }
        boolean twoHands = targetWeapon.isTwoHandWeapon() || targetWeapon.isRequiresEquippedBothHands();
        if (twoHands) {
            npc.setPrimaryHandItem(null);
            npc.setSecondaryHandItem(null);
            npc.setPrimaryHandItem(targetWeapon);
            npc.setSecondaryHandItem(targetWeapon);
        } else {
            InventoryItem sec = npc.getSecondaryHandItem();
            if (sec != null && (sec == targetWeapon
                    || sec == npc.getPrimaryHandItem()
                    || sec.isRequiresEquippedBothHands()
                    || sec instanceof HandWeapon)) {
                npc.setSecondaryHandItem(null);
            }
            if (npc.getPrimaryHandItem() != targetWeapon) {
                npc.setPrimaryHandItem(null);
                npc.setPrimaryHandItem(targetWeapon);
            }
        }
        if (targetWeapon.isAimedFirearm()) {
            NpcWeaponSelector.ensureFireModeOffSafe(targetWeapon);
        }
        ItemContainer mainInv = npc.getInventory();
        mainInv.setDirty(true);
        mainInv.setDrawDirty(true);
        cleanupVars();
        return true;
    }

    @Override
    protected void onInterrupt(BrainContext ctx, String reason) {
        cleanupVars();
    }

    private void cleanupVars() {
        if (npc == null) {
            return;
        }
        npc.clearVariable("AttachAnim");
        npc.clearVariable("AttachItemSpeed");
        restoreWeaponType();
    }
}
