package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.brain.BaseAction;
import emberroutes.npc.brain.BrainContext;
import emberroutes.npc.combat.NpcWeaponSelector.ReloadKind;
import zombie.characters.CharacterActionAnims;
import zombie.characters.skills.PerkFactory;
import zombie.core.random.Rand;
import zombie.core.skinnedmodel.advancedanimation.AnimEvent;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.HandWeapon;
import zombie.scripting.objects.AmmoType;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.scripting.objects.ItemTag;
import zombie.scripting.objects.MoodleType;

/**
 * 纯 Java B42 原生装弹 / 换弹匣 / 拉栓 / 排障 {@link BaseAction}（1:1 对齐 {@code ISReloadWeaponAction} 与双管 {@code needToBeClosedOnceReload}）。
 *
 * <ul>
 *   <li>严格只消费主物品栏 {@code npc.getInventory()} 内的真实弹匣/散弹（背囊取弹由前序 {@code SelfInventoryTransferAction} 完成，严禁瞬移）；</li>
 *   <li>双管霰弹合膛（{@code needToBeClosedOnceReload}）设置 {@code isRacking=true} 对齐 {@code RackDblBarrel.xml}；</li>
 *   <li>仅 {@code LOAD_BULLETS / RACK / UNJAM} 退空壳、仅实际装弹阶段结束播放 {@code InsertAmmoStopSound}。</li>
 * </ul>
 */
public final class NpcReloadAction extends BaseAction {
    private static final double WATCHDOG_SIM_SEC = 5.5;

    private final HandWeapon gun;
    private final ReloadKind kind;
    private InventoryItem targetMagazine;
    private double elapsedSimSec;
    private boolean ejectThenInsertPending;
    private boolean playedInsertStartSound;
    private boolean playedEjectStartSound;

    public NpcReloadAction(IsoNPC npc, HandWeapon gun, ReloadKind kind) {
        super(npc, 1.0, null);
        this.gun = gun;
        this.kind = kind;
        this.stopOnWalk = false;
        this.stopOnRun = true;
        this.stopOnAim = false;
        enableEventDrivenMode();
    }

    @Override
    public boolean valid() {
        return super.valid()
                && gun != null
                && npc.getPrimaryHandItem() == gun
                && kind != null
                && kind != ReloadKind.NONE;
    }

    @Override
    protected void onStart(BrainContext ctx) {
        this.elapsedSimSec = 0.0;
        this.playedInsertStartSound = false;
        this.playedEjectStartSound = false;
        this.ejectThenInsertPending = false;
        this.targetMagazine = null;

        if (!valid()) {
            failOnStart();
            return;
        }

        String reloadTypeStr = gun.getWeaponReloadType() != null ? gun.getWeaponReloadType().toString() : "";
        setAnimVariable("WeaponReloadType", reloadTypeStr);
        configureHandModels(gun, null);

        if (kind == ReloadKind.LOAD_BULLETS || kind == ReloadKind.RACK || kind == ReloadKind.UNJAM) {
            ejectSpentRounds();
        }

        switch (kind) {
            case UNJAM, RACK -> startRackPhase();
            case EJECT_AND_INSERT_MAG -> {
                this.targetMagazine = NpcWeaponSelector.findBestNonEmptyMagazineInMain(npc, gun.getMagazineType());
                if (this.targetMagazine == null) {
                    failOnStart();
                    return;
                }
                this.ejectThenInsertPending = true;
                startUnloadMagPhase();
            }
            case INSERT_MAG -> {
                this.targetMagazine = NpcWeaponSelector.findBestNonEmptyMagazineInMain(npc, gun.getMagazineType());
                if (this.targetMagazine == null) {
                    failOnStart();
                    return;
                }
                startLoadPhase();
            }
            case LOAD_BULLETS -> {
                if (gun.getAmmoType() == null
                        || !NpcWeaponSelector.hasItemInMain(npc.getInventory(), gun.getAmmoType().getItemKey())) {
                    failOnStart();
                    return;
                }
                startLoadPhase();
            }
            default -> forceComplete();
        }
    }

    private void startRackPhase() {
        npc.clearVariable("isLoading");
        npc.clearVariable("isUnloading");
        if (gun.haveChamber() || gun.needToBeClosedOnceReload()) {
            setAnimVariable("isRacking", true);
        } else {
            setAnimVariable("isUnloading", true);
        }
        setAnimVariable("RackAiming", npc.isAiming());
        if (npc.isAiming()) {
            npc.setAimingDelay(npc.getAimingDelay() + gun.getAimingTime() * (0.15F - npc.getPerkLevel(PerkFactory.Perks.Reloading) * 0.01F));
        }
        applyReloadSpeed(npc, gun, true);
        setActionAnim(CharacterActionAnims.Reload);
        npc.reportEvent("EventReloading");
    }

    private void startUnloadMagPhase() {
        npc.clearVariable("isLoading");
        npc.clearVariable("isRacking");
        setAnimVariable("isUnloading", true);
        applyReloadSpeed(npc, gun, false);
        setActionAnim(CharacterActionAnims.Reload);
        npc.reportEvent("EventReloading");
    }

    private void startLoadPhase() {
        npc.clearVariable("isUnloading");
        npc.clearVariable("isRacking");
        setAnimVariable("isLoading", true);
        applyReloadSpeed(npc, gun, false);
        setActionAnim(CharacterActionAnims.Reload);
        npc.reportEvent("EventReloading");
    }

    @Override
    protected void onTick(BrainContext ctx, double dtSim) {
        if (!isRunning()) {
            return;
        }
        elapsedSimSec += Math.max(0.0, dtSim);
        if (elapsedSimSec >= WATCHDOG_SIM_SEC) {
            completeFallbackOnWatchdog();
        }
    }

    @Override
    public void OnAnimEvent(AnimEvent event) {
        if (event == null || event.eventName == null || !isRunning()) {
            return;
        }
        String name = event.eventName;
        String param = event.parameterValue != null ? event.parameterValue : "";

        switch (name) {
            case "playReloadSound" -> handlePlayReloadSound(param);
            case "changeWeaponSprite" -> handleChangeWeaponSprite(param);
            case "rackBullet" -> handleRackBullet();
            case "rackingFinished" -> forceComplete();
            case "unloadFinished" -> handleUnloadFinished();
            case "loadFinished" -> handleLoadFinished();
            default -> {}
        }
    }

    private void handlePlayReloadSound(String param) {
        switch (param) {
            case "load" -> {
                if (gun.getInsertAmmoSound() != null && gun.getCurrentAmmoCount() < gun.getMaxAmmo()) {
                    npc.playSound(gun.getInsertAmmoSound());
                }
            }
            case "insertAmmoStart" -> {
                if (!playedInsertStartSound && gun.getInsertAmmoStartSound() != null) {
                    playedInsertStartSound = true;
                    npc.playSound(gun.getInsertAmmoStartSound());
                }
            }
            case "unload" -> {
                if (gun.getEjectAmmoSound() != null) {
                    npc.playSound(gun.getEjectAmmoSound());
                }
            }
            case "ejectAmmoStart" -> {
                if (!playedEjectStartSound && gun.getEjectAmmoStartSound() != null) {
                    playedEjectStartSound = true;
                    npc.playSound(gun.getEjectAmmoStartSound());
                }
            }
            case "rack" -> {
                if (gun.getRackSound() != null) {
                    npc.playSound(gun.getRackSound());
                }
            }
            default -> {}
        }
    }

    private void handleChangeWeaponSprite(String param) {
        if (param.isEmpty() || "original".equals(param)) {
            configureHandModels(gun, null);
        } else {
            setOverrideHandModelsString(param, null);
        }
    }

    private void handleRackBullet() {
        if (gun.haveChamber()) {
            if (gun.isJammed() && gun.checkUnJam(npc)) {
                return;
            }
            if (!gun.isJammed() && gun.isRoundChambered() && gun.getAmmoType() != null) {
                spawnEjectedRoundToInventory();
            }
            gun.setRoundChambered(false);
            gun.setJammed(false);
            if (gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot()) {
                gun.setRoundChambered(true);
                gun.setCurrentAmmoCount(gun.getCurrentAmmoCount() - gun.getAmmoPerShoot());
                if (gun.getJamGunChance() > 0.0F) {
                    gun.checkJam(npc, true);
                }
            }
        } else {
            if (!gun.isJammed() && !gun.needToBeClosedOnceReload()
                    && gun.getCurrentAmmoCount() > 0 && gun.getAmmoType() != null) {
                spawnEjectedRoundToInventory();
                gun.setCurrentAmmoCount(Math.max(0, gun.getCurrentAmmoCount() - gun.getAmmoPerShoot()));
            }
            gun.setJammed(false);
        }
    }

    private void handleUnloadFinished() {
        ItemContainer mainInv = npc.getInventory();
        if (gun.isContainsClip() && gun.getMagazineType() != null && mainInv != null) {
            InventoryItem ejected = InventoryItemFactory.CreateItem(gun.getMagazineType());
            if (ejected != null) {
                ejected.setCurrentAmmoCount(gun.getCurrentAmmoCount());
                mainInv.AddItem(ejected);
            }
            gun.setContainsClip(false);
            gun.setCurrentAmmoCount(0);
        }
        npc.clearVariable("isUnloading");
        if (ejectThenInsertPending && targetMagazine != null && mainInv != null && mainInv.contains(targetMagazine)) {
            ejectThenInsertPending = false;
            elapsedSimSec = 0.0;
            startLoadPhase();
        } else {
            forceComplete();
        }
    }

    private void handleLoadFinished() {
        awardReloadXp(npc);
        ItemContainer mainInv = npc.getInventory();
        if (mainInv == null) {
            forceStop();
            return;
        }
        if (gun.getMagazineType() != null && !gun.getMagazineType().isEmpty()) {
            if (targetMagazine != null && mainInv.contains(targetMagazine) && !gun.isContainsClip()) {
                mainInv.Remove(targetMagazine);
                npc.removeFromHands(targetMagazine);
                gun.setCurrentAmmoCount(targetMagazine.getCurrentAmmoCount());
                gun.setContainsClip(true);
                npc.clearVariable("isLoading");
                if (gun.haveChamber() && !gun.isRoundChambered() && gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot()) {
                    elapsedSimSec = 0.0;
                    startRackPhase();
                    return;
                }
            }
            forceComplete();
            return;
        }

        if (gun.getAmmoType() == null) {
            forceComplete();
            return;
        }
        String key = gun.getAmmoType().getItemKey();
        if (gun.isInsertAllBulletsReload()) {
            while (gun.getCurrentAmmoCount() < gun.getMaxAmmo()) {
                InventoryItem bullet = NpcWeaponSelector.findFirstMatchingItemInMain(mainInv, key);
                if (bullet == null) break;
                mainInv.Remove(bullet);
                gun.setCurrentAmmoCount(gun.getCurrentAmmoCount() + 1);
            }
        } else if (gun.getCurrentAmmoCount() < gun.getMaxAmmo()) {
            InventoryItem bullet = NpcWeaponSelector.findFirstMatchingItemInMain(mainInv, key);
            if (bullet != null) {
                mainInv.Remove(bullet);
                gun.setCurrentAmmoCount(gun.getCurrentAmmoCount() + 1);
            }
        }

        boolean doneBullets = gun.getCurrentAmmoCount() >= gun.getMaxAmmo()
                || !NpcWeaponSelector.hasItemInMain(mainInv, key);
        if (doneBullets) {
            npc.clearVariable("isLoading");
            if ((gun.haveChamber() && !gun.isRoundChambered() && gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot())
                    || gun.needToBeClosedOnceReload()) {
                elapsedSimSec = 0.0;
                startRackPhase();
                return;
            }
            forceComplete();
        } else {
            elapsedSimSec = 0.0;
        }
    }

    private void completeFallbackOnWatchdog() {
        elapsedSimSec = 0.0;
        if (kind == ReloadKind.UNJAM || kind == ReloadKind.RACK) {
            handleRackBullet();
            forceComplete();
            return;
        }
        if (kind == ReloadKind.INSERT_MAG || kind == ReloadKind.EJECT_AND_INSERT_MAG) {
            if (gun.isContainsClip() && ejectThenInsertPending) {
                handleUnloadFinished();
                return;
            }
            handleLoadFinished();
            return;
        }
        if (kind == ReloadKind.LOAD_BULLETS) {
            handleLoadFinished();
            return;
        }
        forceComplete();
    }

    private void spawnEjectedRoundToInventory() {
        AmmoType at = gun.getAmmoType();
        if (at == null || npc.getInventory() == null) return;
        InventoryItem newBullet = InventoryItemFactory.CreateItem(at.getItemKey());
        if (newBullet != null) {
            npc.getInventory().AddItem(newBullet);
        }
    }

    private void ejectSpentRounds() {
        if (gun.getSpentRoundCount() > 0) {
            gun.setSpentRoundCount(0);
        } else if (gun.isSpentRoundChambered()) {
            gun.setSpentRoundChambered(false);
        } else {
            return;
        }
        if (gun.getShellFallSound() != null && npc.getEmitter() != null) {
            npc.getEmitter().playSound(gun.getShellFallSound());
        }
    }

    @Override
    protected boolean onComplete(BrainContext ctx) {
        clearReloadAnimVars();
        boolean loadPhase = kind == ReloadKind.LOAD_BULLETS
                || kind == ReloadKind.INSERT_MAG
                || (kind == ReloadKind.EJECT_AND_INSERT_MAG && !ejectThenInsertPending);
        if (loadPhase && gun != null && gun.getInsertAmmoStopSound() != null) {
            npc.playSound(gun.getInsertAmmoStopSound());
        }
        return true;
    }

    @Override
    protected void onInterrupt(BrainContext ctx, String reason) {
        clearReloadAnimVars();
        if (gun == null) return;
        if (kind == ReloadKind.EJECT_AND_INSERT_MAG && ejectThenInsertPending && gun.getEjectAmmoStopSound() != null) {
            npc.playSound(gun.getEjectAmmoStopSound());
        } else if (kind != ReloadKind.RACK && kind != ReloadKind.UNJAM && gun.getInsertAmmoStopSound() != null) {
            npc.playSound(gun.getInsertAmmoStopSound());
        }
    }

    private void clearReloadAnimVars() {
        if (npc == null) return;
        npc.clearVariable("isLoading");
        npc.clearVariable("isRacking");
        npc.clearVariable("isUnloading");
        npc.clearVariable("WeaponReloadType");
        npc.clearVariable("RackAiming");
        npc.clearVariable("ReloadSpeed");
    }

    /** 1:1 复刻 {@code ISReloadWeaponAction.setReloadSpeed}，不污染全局 Lua 环境。 */
    public static void applyReloadSpeed(IsoNPC npc, HandWeapon gun, boolean rack) {
        if (npc == null) return;
        float speed = 0.8F;
        int perkLvl = npc.getPerkLevel(PerkFactory.Perks.Reloading);
        if (rack) {
            speed += perkLvl * 0.04F;
        } else {
            speed += perkLvl * 0.10F;
            if (npc.getMoodles() != null) {
                speed -= npc.getMoodles().getMoodleLevel(MoodleType.PANIC) * 0.05F;
            }
        }
        boolean strapBonus = false;
        InventoryItem strap = npc.getWornItem(ItemBodyLocation.AMMO_STRAP);
        if (gun != null) {
            boolean shells = gun.getAmmoType() == AmmoType.SHOTGUN_SHELLS;
            boolean hasMag = gun.getMagazineType() != null && !gun.getMagazineType().isEmpty();
            if (hasMag && (npc.hasEquippedTag(ItemTag.RELOAD_FAST_MAGAZINES) || npc.hasWornTag(ItemTag.RELOAD_FAST_MAGAZINES))) {
                strapBonus = true;
            } else if (shells && (npc.hasEquippedTag(ItemTag.RELOAD_FAST_SHELLS)
                    || npc.hasWornTag(ItemTag.RELOAD_FAST_SHELLS)
                    || (strap != null && "AmmoStrap_Shells".equals(strap.getClothingItemName())))) {
                strapBonus = true;
            } else if (!shells && !hasMag && (npc.hasEquippedTag(ItemTag.RELOAD_FAST_BULLETS)
                    || npc.hasWornTag(ItemTag.RELOAD_FAST_BULLETS)
                    || (strap != null && "AmmoStrap_Bullets".equals(strap.getClothingItemName())))) {
                strapBonus = true;
            }
        }
        if (strapBonus) {
            speed *= 1.15F;
        }
        if (npc.getVehicle() != null && npc.getVehicle().getDriver() == npc) {
            speed *= 0.8F;
        }
        npc.setVariable("ReloadSpeed", Math.max(0.45F, speed));
    }

    public static void awardReloadXp(IsoNPC npc) {
        if (npc == null || npc.getXp() == null) return;
        int lvl = npc.getPerkLevel(PerkFactory.Perks.Reloading);
        int chance = lvl < 5 ? 1 : 3;
        float xp = lvl < 5 ? 4.0F : 1.0F;
        if (Rand.Next(chance) == 0) {
            npc.getXp().AddXP(PerkFactory.Perks.Reloading, xp, true, true, false, false);
        }
    }
}
