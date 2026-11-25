package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import zombie.GameTime;
import zombie.characters.CharacterActionAnims;
import zombie.characters.CharacterTimedActions.BaseAction;
import zombie.core.skinnedmodel.advancedanimation.AnimEvent;
import zombie.inventory.InventoryItem;

/**
 * 脱战安全间隙真弹匣压弹 {@link BaseAction}（对齐原版 {@code ISLoadBulletsInMagazine}）：
 * 从背囊与主物品栏取真实散弹逐发压入未满兼容弹匣，支持步行且受威胁瞬间打断。
 */
public final class NpcRepackMagAction extends BaseAction {
    private static final double STALL_WATCHDOG_SIM_SEC = 3.0;

    private final IsoNPC npc;
    private final InventoryItem magazine;
    private float updateLoadBulletsTime;
    private boolean loadedThisLoop;
    private double stallWatchdogSec;
    private boolean done;

    public NpcRepackMagAction(IsoNPC npc, InventoryItem magazine) {
        super(npc);
        this.npc = npc;
        this.magazine = magazine;
        this.stopOnWalk = false;
        this.stopOnRun = true;
        this.stopOnAim = true;
        this.useProgressBar = false;
        this.forceProgressBar = false;
        this.maxTime = -1;
    }

    public static boolean tryStart(IsoNPC npc) {
        if (npc == null || npc.isDead() || !npc.getCharacterActions().isEmpty()) {
            return false;
        }
        InventoryItem mag = NpcWeaponSelector.findReloadableEmptyMagazine(npc);
        if (mag == null) {
            return false;
        }
        npc.StartAction(new NpcRepackMagAction(npc, mag));
        return true;
    }

    @Override
    public boolean valid() {
        return npc != null
                && !npc.isDead()
                && magazine != null
                && magazine.getAmmoType() != null
                && !done
                && magazine.getCurrentAmmoCount() < magazine.getMaxAmmo()
                && NpcWeaponSelector.hasItemRecursive(npc.getInventory(), magazine.getAmmoType().getItemKey());
    }

    @Override
    public boolean finished() {
        return done || magazine == null || magazine.getCurrentAmmoCount() >= magazine.getMaxAmmo();
    }

    @Override
    public void start() {
        super.start();
        updateLoadBulletsTime = 0.0F;
        loadedThisLoop = false;
        stallWatchdogSec = 0.0;
        if (!NpcWeaponSelector.ensureItemInMainInventory(npc, magazine)) {
            done = true;
            forceStop();
            return;
        }
        setOverrideHandModelsString(null, "GunMagazine");
        setActionAnim(CharacterActionAnims.InsertBullets);
        NpcReloadAction.applyReloadSpeed(npc, null, false);
        npc.setVariable("UpdateLoadBulletsTime", 0.0F);
    }

    @Override
    public void update() {
        super.update();
        if (done) return;
        float reloadSpeed = npc.getVariableFloat("ReloadSpeed", 1.0F);
        float animTimeDelta = Math.max(0.0F, npc.getAnimationTimeDelta()) * reloadSpeed;
        float oldUpdateLoadBulletsTime = updateLoadBulletsTime;
        updateLoadBulletsTime = (updateLoadBulletsTime + animTimeDelta) % 1.0F;
        if (oldUpdateLoadBulletsTime > updateLoadBulletsTime) {
            loadedThisLoop = false;
        }
        npc.setVariable("UpdateLoadBulletsTime", updateLoadBulletsTime);

        double dt = GameTime.getInstance() != null ? GameTime.getInstance().getTimeDelta() : 0.016;
        stallWatchdogSec += Math.max(0.0, dt);
        if (stallWatchdogSec >= STALL_WATCHDOG_SIM_SEC) {
            stallWatchdogSec = 0.0;
            loadOneBullet();
        }
    }

    @Override
    public void OnAnimEvent(AnimEvent event) {
        if (event == null || done) return;
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
        if (magazine == null || magazine.getAmmoType() == null) {
            done = true;
            forceComplete();
            return;
        }
        if (magazine.getCurrentAmmoCount() >= magazine.getMaxAmmo()) {
            done = true;
            forceComplete();
            return;
        }
        InventoryItem bullet = NpcWeaponSelector.transferOneMatchingItemToMain(
                npc, magazine.getAmmoType().getItemKey());
        if (bullet == null) {
            done = true;
            forceComplete();
            return;
        }
        npc.getInventory().Remove(bullet);
        magazine.setCurrentAmmoCount(magazine.getCurrentAmmoCount() + 1);
        NpcReloadAction.awardReloadXp(npc);
        if (magazine.getCurrentAmmoCount() >= magazine.getMaxAmmo()
                || !NpcWeaponSelector.hasItemRecursive(npc.getInventory(), magazine.getAmmoType().getItemKey())) {
            done = true;
            forceComplete();
        }
    }

    @Override
    public void stop() {
        done = true;
        NpcReloadAction.stowLooseMagToBagIfConvenient(npc, magazine);
        super.stop();
    }

    @Override
    public void perform() {
        done = true;
        NpcReloadAction.stowLooseMagToBagIfConvenient(npc, magazine);
        super.perform();
    }
}
