package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.brain.BaseAction;
import java.util.List;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.InventoryContainer;
import zombie.scripting.objects.ItemTag;

/**
 * 随从与守卫武器/弹药状态评估与真原件换装器（兼容主栏与身上多层背包）。
 */
public final class NpcWeaponSelector {
    public enum WeaponMode {
        UNARMED,
        MELEE,
        FIREARM
    }

    public enum ReloadKind {
        NONE,
        UNJAM,
        RACK,
        EJECT_AND_INSERT_MAG,
        INSERT_MAG,
        LOAD_BULLETS
    }

    private NpcWeaponSelector() {}

    public static HandWeapon getEquippedWeapon(IsoNPC npc) {
        if (npc == null) return null;
        return npc.getPrimaryHandItem() instanceof HandWeapon hw ? hw : null;
    }

    public static boolean isUsableFirearm(HandWeapon hw) {
        return hw != null && hw.isAimedFirearm() && hw.getCondition() > 0 && !hw.hasTag(ItemTag.FAKE_WEAPON);
    }

    public static boolean isUsableMelee(HandWeapon hw) {
        return hw != null && !hw.isRanged() && hw.getCondition() > 0 && !hw.hasTag(ItemTag.FAKE_WEAPON);
    }

    /** 对照 {@code ISReloadWeaponAction.canShoot}（额外校验 Safe 保险状态）。 */
    public static boolean canShootNow(IsoNPC npc, HandWeapon gun) {
        if (npc == null || !isUsableFirearm(gun)) return false;
        if (gun.isSelectFire() && "Safe".equals(gun.getFireMode())) {
            return false;
        }
        if (npc.isUnlimitedAmmo()) {
            return true;
        }
        if (gun.isJammed()) {
            return false;
        }
        if (gun.haveChamber() && !gun.isRoundChambered()) {
            return false;
        }
        return gun.haveChamber() || gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot();
    }

    public static boolean needsChamberRack(HandWeapon gun) {
        if (!isUsableFirearm(gun)) return false;
        if (gun.isJammed()) return true;
        if (gun.haveChamber() && !gun.isRoundChambered()) {
            return gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot();
        }
        return false;
    }

    /** 判定当前所持枪械需要的真实装弹/排障工序；无备弹则返回 {@link ReloadKind#NONE}。 */
    public static ReloadKind resolveNeededReload(IsoNPC npc, HandWeapon gun) {
        if (npc == null || !isUsableFirearm(gun)) {
            return ReloadKind.NONE;
        }
        if (gun.isJammed()) {
            return ReloadKind.UNJAM;
        }
        if (gun.haveChamber() && !gun.isRoundChambered() && gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot()) {
            return ReloadKind.RACK;
        }
        if (gun.getCurrentAmmoCount() >= gun.getMaxAmmo() && (!gun.haveChamber() || gun.isRoundChambered())) {
            return ReloadKind.NONE;
        }
        String magType = gun.getMagazineType();
        if (magType != null && !magType.isEmpty()) {
            InventoryItem bestMag = findBestNonEmptyMagazine(npc, magType);
            if (bestMag == null) {
                return ReloadKind.NONE;
            }
            if (gun.isContainsClip()) {
                // 若手中已有可用余弹且只是不满仓，仅当有更高装弹实匣或余弹为空才换匣
                if (gun.getCurrentAmmoCount() <= 0 || bestMag.getCurrentAmmoCount() > gun.getCurrentAmmoCount() + 2) {
                    return ReloadKind.EJECT_AND_INSERT_MAG;
                }
                return ReloadKind.NONE;
            }
            return ReloadKind.INSERT_MAG;
        }
        // 内置弹仓/左轮/霰弹：逐发填装实弹
        if (gun.getAmmoType() != null && gun.getCurrentAmmoCount() < gun.getMaxAmmo()) {
            if (hasItemRecursive(npc.getInventory(), gun.getAmmoType().getItemKey())) {
                return ReloadKind.LOAD_BULLETS;
            }
        }
        return ReloadKind.NONE;
    }

    /** 在 NPC 全背包树中查找带实弹的最满兼容弹匣（优先已在主物品栏，次选背包）。 */
    public static InventoryItem findBestNonEmptyMagazine(IsoNPC npc, String magFullType) {
        if (npc == null || magFullType == null || magFullType.isEmpty()) return null;
        return findBestMagazineIn(npc.getInventory(), magFullType, 1);
    }

    public static InventoryItem findReloadableEmptyMagazine(IsoNPC npc) {
        if (npc == null) return null;
        ItemContainer root = npc.getInventory();
        return findPartialMagForRepack(root, root);
    }

    private static InventoryItem findPartialMagForRepack(ItemContainer root, ItemContainer cur) {
        if (root == null || cur == null) return null;
        List<InventoryItem> items = cur.getItems();
        if (items == null) return null;
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem item = items.get(i);
            if (item == null) continue;
            if (!(item instanceof HandWeapon)
                    && item.getMaxAmmo() > 0
                    && item.getAmmoType() != null
                    && item.getCurrentAmmoCount() < item.getMaxAmmo()) {
                String ammoKey = item.getAmmoType().getItemKey();
                if (ammoKey != null && containerHasAmmoAnywhere(root, ammoKey)) {
                    return item;
                }
            }
            if (item instanceof InventoryContainer bag && bag.getInventory() != null) {
                InventoryItem nested = findPartialMagForRepack(root, bag.getInventory());
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static boolean containerHasAmmoAnywhere(ItemContainer container, String itemKey) {
        return BaseAction.findItemRecursive(container, it -> matchesTypeKey(it, itemKey)) != null;
    }

    public static boolean hasAnyReserveAmmoForGun(IsoNPC npc, HandWeapon gun) {
        if (npc == null || gun == null) return false;
        if (npc.isUnlimitedAmmo()) return true;
        if (gun.getCurrentAmmoCount() > 0 || (gun.haveChamber() && gun.isRoundChambered())) return true;
        String magType = gun.getMagazineType();
        if (magType != null && !magType.isEmpty()) {
            if (findBestNonEmptyMagazine(npc, magType) != null) return true;
        }
        if (gun.getAmmoType() != null) {
            return hasItemRecursive(npc.getInventory(), gun.getAmmoType().getItemKey());
        }
        return false;
    }

    /** 检查是否应切换手上武器（CQB/枪械优先、断弹降级近战、备弹恢复重新切枪）；保证原件在物品栏与手持间守恒转移。 */
    public static WeaponMode ensureBestCombatWeapon(IsoNPC npc, boolean preferFirearm, boolean allowMeleeFallback) {
        if (npc == null || npc.isDead()) return WeaponMode.UNARMED;
        if (!npc.getCharacterActions().isEmpty()) {
            HandWeapon cur = getEquippedWeapon(npc);
            if (isUsableFirearm(cur)) return WeaponMode.FIREARM;
            if (isUsableMelee(cur)) return WeaponMode.MELEE;
            return WeaponMode.UNARMED;
        }

        HandWeapon current = getEquippedWeapon(npc);
        HandWeapon bestGun = findBestFirearmWithAmmoOrChambered(npc);
        HandWeapon bestMelee = findBestMeleeWeapon(npc);

        if (preferFirearm) {
            if (isUsableFirearm(current) && (canShootNow(npc, current) || resolveNeededReload(npc, current) != ReloadKind.NONE)) {
                return WeaponMode.FIREARM;
            }
            if (bestGun != null && bestGun != current) {
                if (equipWeapon(npc, bestGun)) {
                    return WeaponMode.FIREARM;
                }
            }
            if (allowMeleeFallback) {
                if (isUsableMelee(current)) return WeaponMode.MELEE;
                if (bestMelee != null && equipWeapon(npc, bestMelee)) {
                    return WeaponMode.MELEE;
                }
            }
            return isUsableFirearm(current) ? WeaponMode.FIREARM
                    : (isUsableMelee(current) ? WeaponMode.MELEE : WeaponMode.UNARMED);
        }

        // 常规近战优先模式：若当前已有枪且备弹充足保留原配；若手中枪彻底断弹且不准开火，切近战防呆
        if (isUsableMelee(current)) {
            return WeaponMode.MELEE;
        }
        if (isUsableFirearm(current)) {
            if (canShootNow(npc, current) || resolveNeededReload(npc, current) != ReloadKind.NONE) {
                return WeaponMode.FIREARM;
            }
            if (allowMeleeFallback && bestMelee != null && equipWeapon(npc, bestMelee)) {
                return WeaponMode.MELEE;
            }
            return WeaponMode.FIREARM;
        }
        if (bestMelee != null && equipWeapon(npc, bestMelee)) {
            return WeaponMode.MELEE;
        }
        if (bestGun != null && equipWeapon(npc, bestGun)) {
            return WeaponMode.FIREARM;
        }
        return WeaponMode.UNARMED;
    }

    public static boolean equipWeapon(IsoNPC npc, HandWeapon weapon) {
        if (npc == null || weapon == null) return false;
        ItemContainer mainInv = npc.getInventory();
        if (mainInv == null) return false;
        if (!mainInv.contains(weapon)) {
            ItemContainer curParent = weapon.getContainer();
            if (curParent == null) {
                BaseAction.ItemLocation loc = BaseAction.findItemRecursive(mainInv, it -> it == weapon);
                if (loc == null) return false;
                curParent = loc.container();
            }
            curParent.DoRemoveItem(weapon);
            mainInv.AddItem(weapon);
        }
        InventoryItem prevPrimary = npc.getPrimaryHandItem();
        if (prevPrimary != null && prevPrimary != weapon && npc.getSecondaryHandItem() == prevPrimary) {
            npc.setSecondaryHandItem(null);
        }
        npc.setPrimaryHandItem(weapon);
        if (weapon.isTwoHandWeapon()) {
            npc.setSecondaryHandItem(weapon);
        }
        npc.setUseHandWeapon(weapon);
        return true;
    }

    public static HandWeapon findBestFirearmWithAmmoOrChambered(IsoNPC npc) {
        if (npc == null || npc.getInventory() == null) return null;
        return findBestWeaponRecursive(npc, npc.getInventory(), null, -1.0F, true);
    }

    public static HandWeapon findBestMeleeWeapon(IsoNPC npc) {
        if (npc == null || npc.getInventory() == null) return null;
        return findBestWeaponRecursive(npc, npc.getInventory(), null, -1.0F, false);
    }

    private static HandWeapon findBestWeaponRecursive(
            IsoNPC npc, ItemContainer container, HandWeapon best, float bestScore, boolean firearm) {
        List<InventoryItem> items = container != null ? container.getItems() : null;
        if (items == null) return best;
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem it = items.get(i);
            if (it instanceof HandWeapon hw) {
                float score = scoreCandidateWeapon(npc, hw, firearm);
                if (score > bestScore) {
                    best = hw;
                    bestScore = score;
                }
            } else if (it instanceof InventoryContainer bag && bag.getInventory() != null) {
                HandWeapon nested = findBestWeaponRecursive(npc, bag.getInventory(), best, bestScore, firearm);
                if (nested != best && nested != null) {
                    best = nested;
                    bestScore = scoreCandidateWeapon(npc, best, firearm);
                }
            }
        }
        return best;
    }

    private static float scoreCandidateWeapon(IsoNPC npc, HandWeapon hw, boolean firearm) {
        if (firearm) {
            if (!isUsableFirearm(hw)) return -Float.MAX_VALUE;
            if (!canShootNow(npc, hw) && resolveNeededReload(npc, hw) == ReloadKind.NONE) {
                return -Float.MAX_VALUE;
            }
            float score = hw.getMaxDamage() * 10.0F + hw.getCurrentAmmoCount()
                    + (hw.haveChamber() && hw.isRoundChambered() ? 5.0F : 0.0F);
            return hw == npc.getPrimaryHandItem() ? score + 12.0F : score;
        }
        if (!isUsableMelee(hw)) return -Float.MAX_VALUE;
        float score = hw.getMaxDamage() * 10.0F + hw.getMaxRange() * 4.0F;
        return hw == npc.getPrimaryHandItem() ? score + 8.0F : score;
    }

    public static boolean hasItemRecursive(ItemContainer root, String itemKey) {
        return BaseAction.findItemRecursive(root, it -> matchesTypeKey(it, itemKey)) != null;
    }

    public static InventoryItem transferOneMatchingItemToMain(IsoNPC npc, String itemKey) {
        if (npc == null || npc.getInventory() == null || itemKey == null) return null;
        ItemContainer main = npc.getInventory();
        BaseAction.ItemLocation loc = BaseAction.findItemRecursive(main, it -> matchesTypeKey(it, itemKey));
        if (loc == null || loc.item() == null) return null;
        if (loc.container() != main) {
            loc.container().DoRemoveItem(loc.item());
            main.AddItem(loc.item());
        }
        return loc.item();
    }

    public static boolean ensureItemInMainInventory(IsoNPC npc, InventoryItem item) {
        if (npc == null || npc.getInventory() == null || item == null) return false;
        ItemContainer main = npc.getInventory();
        if (main.contains(item)) return true;
        BaseAction.ItemLocation loc = BaseAction.findItemRecursive(main, it -> it == item);
        if (loc == null || loc.container() == null) return false;
        loc.container().DoRemoveItem(item);
        main.AddItem(item);
        return true;
    }

    private static InventoryItem findBestMagazineIn(ItemContainer container, String magType, int minRounds) {
        if (container == null || magType == null) return null;
        List<InventoryItem> items = container.getItems();
        if (items == null) return null;
        InventoryItem best = null;
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem it = items.get(i);
            if (it == null) continue;
            if (matchesTypeKey(it, magType) && it.getCurrentAmmoCount() >= minRounds) {
                if (best == null || it.getCurrentAmmoCount() > best.getCurrentAmmoCount()) {
                    best = it;
                }
            } else if (it instanceof InventoryContainer bag && bag.getInventory() != null) {
                InventoryItem nested = findBestMagazineIn(bag.getInventory(), magType, minRounds);
                if (nested != null && (best == null || nested.getCurrentAmmoCount() > best.getCurrentAmmoCount())) {
                    best = nested;
                }
            }
        }
        return best;
    }

    public static boolean matchesTypeKey(InventoryItem item, String itemKey) {
        if (item == null || itemKey == null) return false;
        return itemKey.equals(item.getFullType()) || itemKey.equals(item.getType());
    }
}
