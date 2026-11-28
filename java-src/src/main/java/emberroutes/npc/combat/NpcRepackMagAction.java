package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.brain.BaseAction;
import emberroutes.npc.brain.BrainContext;
import zombie.characters.CharacterActionAnims;
import zombie.core.skinnedmodel.advancedanimation.AnimEvent;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.HandWeapon;

/**
 * 脱战安全间隙真弹匣压弹 {@link BaseAction}（对齐原版 {@code ISLoadBulletsInMagazine}）：
 * 严格仅消费主物品栏 {@code npc.getInventory()} 内弹匣与散弹（前置取弹与后置归还背囊由 {@code SelfAidActionNode} 统筹）。
 */
public final class NpcRepackMagAction extends BaseAction {
    private static final double STALL_WATCHDOG_SIM_SEC = 3.0;

    private final InventoryItem magazine;
    private final HandWeapon preferredGun;
    private float updateLoadBulletsTime;
    private boolean loadedThisLoop;
    private double stallWatchdogSec;

    public NpcRepackMagAction(IsoNPC npc, InventoryItem magazine) {
        this(npc, magazine, null);
    }

    public NpcRepackMagAction(IsoNPC npc, InventoryItem magazine, HandWeapon preferredGun) {
        super(npc, 1.0, null);
        this.magazine = magazine;
        this.preferredGun = preferredGun;
        this.stopOnWalk = false;
        this.stopOnRun = true;
        this.stopOnAim = true;
        enableEventDrivenMode();
    }

    @Override
    public boolean valid() {
        ItemContainer mainInv = npc != null ? npc.getInventory() : null;
        return super.valid()
                && mainInv != null
                && magazine != null
                && mainInv.contains(magazine)
                && magazine.getAmmoType() != null
                && magazine.getCurrentAmmoCount() < magazine.getMaxAmmo()
                && NpcWeaponSelector.hasItemInMain(mainInv, magazine.getAmmoType().getItemKey());
    }

    @Override
    protected void onStart(BrainContext ctx) {
        this.updateLoadBulletsTime = 0.0F;
        this.loadedThisLoop = false;
        this.stallWatchdogSec = 0.0;
        if (!valid()) {
            failOnStart();
            return;
        }
        setOverrideHandModelsString(null, "GunMagazine");
        setActionAnim(CharacterActionAnims.InsertBullets);
        NpcReloadAction.applyReloadSpeed(npc, preferredGun, false);
        npc.setVariable("UpdateLoadBulletsTime", 0.0F);
    }

    @Override
    protected void onTick(BrainContext ctx, double dtSim) {
        if (!isRunning()) {
            return;
        }
        float reloadSpeed = npc.getVariableFloat("ReloadSpeed", 1.0F);
        float animTimeDelta = Math.max(0.0F, npc.getAnimationTimeDelta()) * reloadSpeed;
        float oldUpdateLoadBulletsTime = updateLoadBulletsTime;
        updateLoadBulletsTime = (updateLoadBulletsTime + animTimeDelta) % 1.0F;
        if (oldUpdateLoadBulletsTime > updateLoadBulletsTime) {
            loadedThisLoop = false;
        }
        npc.setVariable("UpdateLoadBulletsTime", updateLoadBulletsTime);

        stallWatchdogSec += Math.max(0.0, dtSim);
        if (stallWatchdogSec >= STALL_WATCHDOG_SIM_SEC) {
            stallWatchdogSec = 0.0;
            loadOneBullet();
        }
    }

    @Override
    public void OnAnimEvent(AnimEvent event) {
        if (event == null || !isRunning()) return;
        if ("InsertBullet".equals(event.eventName)) {
            if (loadedThisLoop) {
                return;
            }
            loadedThisLoop = true;
            stallWatchdogSec = 0.0;
            loadOneBullet();
        } else if ("InsertBulletSound".equals(event.eventName) && event.parameterValue != null) {
            if (!loadedThisLoop && magazine.getCurrentAmmoCount() < magazine.getMaxAmmo()) {
                npc.playSound(event.parameterValue);
            }
        }
    }

    private void loadOneBullet() {
        ItemContainer mainInv = npc != null ? npc.getInventory() : null;
        if (mainInv == null || magazine == null || magazine.getAmmoType() == null) {
            forceComplete();
            return;
        }
        if (magazine.getCurrentAmmoCount() >= magazine.getMaxAmmo()) {
            forceComplete();
            return;
        }
        String ammoKey = magazine.getAmmoType().getItemKey();
        InventoryItem bullet = NpcWeaponSelector.findFirstMatchingItemInMain(mainInv, ammoKey);
        if (bullet == null) {
            forceComplete();
            return;
        }
        mainInv.Remove(bullet);
        magazine.setCurrentAmmoCount(magazine.getCurrentAmmoCount() + 1);
        NpcReloadAction.awardReloadXp(npc);
        if (magazine.getCurrentAmmoCount() >= magazine.getMaxAmmo()
                || !NpcWeaponSelector.hasItemInMain(mainInv, ammoKey)) {
            forceComplete();
        }
    }

    @Override
    protected boolean onComplete(BrainContext ctx) {
        cleanupVars();
        return true;
    }

    @Override
    protected void onInterrupt(BrainContext ctx, String reason) {
        cleanupVars();
    }

    private void cleanupVars() {
        if (npc == null) return;
        npc.clearVariable("UpdateLoadBulletsTime");
        npc.clearVariable("ReloadSpeed");
    }
}
