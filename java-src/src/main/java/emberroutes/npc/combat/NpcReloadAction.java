package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.combat.NpcWeaponSelector.ReloadKind;
import zombie.GameTime;
import zombie.characters.CharacterActionAnims;
import zombie.characters.IsoPlayer;
import zombie.characters.CharacterTimedActions.BaseAction;
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
 * 纯 Java B42 原生装弹 / 换弹匣 / 拉栓 / 排障 {@link BaseAction}（100% 遵循背包→主栏→枪械真原件守恒与动画事件驱动）。
 *
 * <ul>
 *   <li>{@code stopOnWalk = false} 允许步行风筝与退避换弹；{@code stopOnRun = true} 防切奔跑穿模；</li>
 *   <li>由 {@code IsoPlayer.OnAnimEvent:8170} 直接驱动 {@link #OnAnimEvent(AnimEvent)} 落袋每发实弹/弹匣/拉栓与声效，
 *       并附 {@code 5.5s} simTime 看门狗防第三方模组枪械漏配事件卡死；</li>
 *   <li>与泵动霰弹枪开火后原版自动入队的 {@code ISRackFirearm} 天然互斥共存（已有动作运行则等待完成不重复入队）。</li>
 * </ul>
 */
public final class NpcReloadAction extends BaseAction {
    private static final double WATCHDOG_SIM_SEC = 5.5;

    private final IsoNPC npc;
    private final HandWeapon gun;
    private final ReloadKind kind;
    private InventoryItem targetMagazine;
    private double elapsedSimSec;
    private boolean ejectThenInsertPending;
    private boolean playedInsertStartSound;
    private boolean playedEjectStartSound;
    private boolean done;

    public NpcReloadAction(IsoNPC npc, HandWeapon gun, ReloadKind kind) {
        super(npc);
        this.npc = npc;
        this.gun = gun;
        this.kind = kind;
        this.stopOnWalk = false;
        this.stopOnRun = true;
        this.stopOnAim = false;
        this.useProgressBar = false;
        this.forceProgressBar = false;
        this.maxTime = -1;
    }

    public boolean isDone() {
        return done || forceStop || forceComplete;
    }

    public static void startIfIdle(IsoNPC npc, HandWeapon gun, ReloadKind kind) {
        if (npc == null || npc.isDead() || gun == null || kind == ReloadKind.NONE) {
            return;
        }
        if (!npc.getCharacterActions().isEmpty()) {
            return;
        }
        NpcReloadAction act = new NpcReloadAction(npc, gun, kind);
        npc.StartAction(act);
    }

    @Override
    public boolean valid() {
        return npc != null
                && !npc.isDead()
                && gun != null
                && npc.getPrimaryHandItem() == gun
                && !done;
    }

    @Override
    public boolean finished() {
        return done;
    }

    @Override
    public void start() {
        super.start();
        this.elapsedSimSec = 0.0;
        this.playedInsertStartSound = false;
        this.playedEjectStartSound = false;
        this.ejectThenInsertPending = false;
        this.targetMagazine = null;

        if (!valid()) {
            done = true;
            forceComplete();
            return;
        }

        npc.setVariable("WeaponReloadType", String.valueOf(gun.getWeaponReloadType()));
        setOverrideHandModels(gun, null);
        ejectSpentRounds();

        switch (kind) {
            case UNJAM, RACK -> startRackPhase();
            case EJECT_AND_INSERT_MAG -> {
                String magType = gun.getMagazineType();
                this.targetMagazine = NpcWeaponSelector.findBestNonEmptyMagazine(npc, magType);
                if (this.targetMagazine == null) {
                    done = true;
                    forceStop();
                    return;
                }
                NpcWeaponSelector.ensureItemInMainInventory(npc, this.targetMagazine);
                this.ejectThenInsertPending = true;
                startUnloadMagPhase();
            }
            case INSERT_MAG -> {
                String magType = gun.getMagazineType();
                this.targetMagazine = NpcWeaponSelector.findBestNonEmptyMagazine(npc, magType);
                if (this.targetMagazine == null || !NpcWeaponSelector.ensureItemInMainInventory(npc, this.targetMagazine)) {
                    done = true;
                    forceStop();
                    return;
                }
                startLoadPhase();
            }
            case LOAD_BULLETS -> {
                if (gun.getAmmoType() == null
                        || !NpcWeaponSelector.hasItemRecursive(npc.getInventory(), gun.getAmmoType().getItemKey())) {
                    done = true;
                    forceStop();
                    return;
                }
                startLoadPhase();
            }
            default -> {
                done = true;
                forceComplete();
            }
        }
    }

    private void startRackPhase() {
        npc.clearVariable("isLoading");
        npc.clearVariable("isUnloading");
        if (gun.haveChamber()) {
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
    public void update() {
        super.update();
        if (done) return;
        float mult = GameTime.getInstance() != null ? GameTime.getInstance().getMultiplier() : 1.0F;
        elapsedSimSec += Math.max(0.0, GameTime.getInstance() != null ? GameTime.getInstance().getTimeDelta() : 0.016 * mult);
        if (elapsedSimSec >= WATCHDOG_SIM_SEC) {
            // 看门狗防呆：若第三方面具/枪械 AnimSet 缺失结束事件，执行安全状态落地并退出
            completeFallbackOnWatchdog();
        }
    }

    @Override
    public void OnAnimEvent(AnimEvent event) {
        if (event == null || event.eventName == null || done) {
            return;
        }
        String name = event.eventName;
        String param = event.parameterValue != null ? event.parameterValue : "";

        switch (name) {
            case "playReloadSound" -> handlePlayReloadSound(param);
            case "changeWeaponSprite" -> handleChangeWeaponSprite(param);
            case "rackBullet" -> handleRackBullet();
            case "rackingFinished" -> {
                done = true;
                forceComplete();
            }
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
            setOverrideHandModels(gun, null);
        } else {
            setOverrideHandModelsString(param, null);
        }
    }

    private void handleRackBullet() {
        if (gun.haveChamber()) {
            if (gun.isJammed() && gun.checkUnJam((IsoPlayer) npc)) {
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
                    gun.checkJam((IsoPlayer) npc, true);
                }
            }
        } else {
            if (!gun.isJammed() && gun.getCurrentAmmoCount() > 0 && gun.getAmmoType() != null) {
                spawnEjectedRoundToInventory();
                gun.setCurrentAmmoCount(Math.max(0, gun.getCurrentAmmoCount() - gun.getAmmoPerShoot()));
            }
            gun.setJammed(false);
        }
    }

    private void handleUnloadFinished() {
        if (gun.isContainsClip() && gun.getMagazineType() != null) {
            InventoryItem ejected = InventoryItemFactory.CreateItem(gun.getMagazineType());
            if (ejected != null) {
                ejected.setCurrentAmmoCount(gun.getCurrentAmmoCount());
                npc.getInventory().AddItem(ejected);
                stowLooseMagToBagIfConvenient(npc, ejected);
            }
            gun.setContainsClip(false);
            gun.setCurrentAmmoCount(0);
        }
        npc.clearVariable("isUnloading");
        if (ejectThenInsertPending && targetMagazine != null && NpcWeaponSelector.ensureItemInMainInventory(npc, targetMagazine)) {
            ejectThenInsertPending = false;
            elapsedSimSec = 0.0;
            startLoadPhase();
        } else {
            done = true;
            forceComplete();
        }
    }

    private void handleLoadFinished() {
        awardReloadXp(npc);
        if (gun.getMagazineType() != null && !gun.getMagazineType().isEmpty()) {
            if (targetMagazine != null && NpcWeaponSelector.ensureItemInMainInventory(npc, targetMagazine) && !gun.isContainsClip()) {
                npc.getInventory().Remove(targetMagazine);
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
            done = true;
            forceComplete();
            return;
        }

        // 内置弹仓 / 左轮 / 霰弹：每次 loadFinished 扣一枚实弹（或全部填满型）
        if (gun.getAmmoType() == null) {
            done = true;
            forceComplete();
            return;
        }
        String key = gun.getAmmoType().getItemKey();
        if (gun.isInsertAllBulletsReload()) {
            while (gun.getCurrentAmmoCount() < gun.getMaxAmmo()) {
                InventoryItem bullet = NpcWeaponSelector.transferOneMatchingItemToMain(npc, key);
                if (bullet == null) break;
                npc.getInventory().Remove(bullet);
                gun.setCurrentAmmoCount(gun.getCurrentAmmoCount() + 1);
            }
        } else if (gun.getCurrentAmmoCount() < gun.getMaxAmmo()) {
            InventoryItem bullet = NpcWeaponSelector.transferOneMatchingItemToMain(npc, key);
            if (bullet != null) {
                npc.getInventory().Remove(bullet);
                gun.setCurrentAmmoCount(gun.getCurrentAmmoCount() + 1);
            }
        }

        boolean doneBullets = gun.getCurrentAmmoCount() >= gun.getMaxAmmo()
                || !NpcWeaponSelector.hasItemRecursive(npc.getInventory(), key);
        if (doneBullets) {
            npc.clearVariable("isLoading");
            if ((gun.haveChamber() && !gun.isRoundChambered() && gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot())
                    || gun.needToBeClosedOnceReload()) {
                elapsedSimSec = 0.0;
                startRackPhase();
                return;
            }
            done = true;
            forceComplete();
        }
    }

    private void completeFallbackOnWatchdog() {
        if (kind == ReloadKind.UNJAM || kind == ReloadKind.RACK) {
            handleRackBullet();
        } else if (kind == ReloadKind.INSERT_MAG || kind == ReloadKind.EJECT_AND_INSERT_MAG) {
            if (gun.isContainsClip() && ejectThenInsertPending) {
                handleUnloadFinished();
                return;
            }
            handleLoadFinished();
            return;
        } else if (kind == ReloadKind.LOAD_BULLETS) {
            handleLoadFinished();
            return;
        }
        done = true;
        forceComplete();
    }

    private void spawnEjectedRoundToInventory() {
        AmmoType at = gun.getAmmoType();
        if (at == null) return;
        InventoryItem newBullet = InventoryItemFactory.CreateItem(at.getItemKey());
        if (newBullet != null && npc.getInventory() != null) {
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
    public void stop() {
        done = true;
        clearReloadAnimVars();
        if (kind == ReloadKind.EJECT_AND_INSERT_MAG && ejectThenInsertPending && gun.getEjectAmmoStopSound() != null) {
            npc.playSound(gun.getEjectAmmoStopSound());
        } else if (gun.getInsertAmmoStopSound() != null) {
            npc.playSound(gun.getInsertAmmoStopSound());
        }
        super.stop();
    }

    @Override
    public void perform() {
        done = true;
        clearReloadAnimVars();
        if (gun.getInsertAmmoStopSound() != null) {
            npc.playSound(gun.getInsertAmmoStopSound());
        }
        super.perform();
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

    /** 检查是否允许原地/随行归还空匣到尚有余量的背负容器（主包仍保有则留在主包）。 */
    public static void stowLooseMagToBagIfConvenient(IsoNPC npc, InventoryItem item) {
        if (npc == null || npc.getInventory() == null || item == null) return;
        for (InventoryItem invItem : npc.getInventory().getItems()) {
            if (invItem instanceof zombie.inventory.types.InventoryContainer bag && bag.getInventory() != null) {
                ItemContainer bagInv = bag.getInventory();
                if (bagInv.hasRoomFor(npc, item)) {
                    npc.getInventory().DoRemoveItem(item);
                    bagInv.AddItem(item);
                    return;
                }
            }
        }
    }
}
