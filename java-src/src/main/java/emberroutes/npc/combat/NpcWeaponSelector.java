package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.brain.BaseAction;
import emberroutes.npc.brain.SelfInventoryTransferAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.InventoryContainer;
import zombie.scripting.objects.ItemTag;

/**
 * 随从与守卫武器/弹药状态只读评估与背包取物规划器（100% 遵守原版规则：绝不隔空从背囊瞬移物品至主栏）。
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
        LOAD_BULLETS,
        REPACK_MAG
    }

    /** 武器切换只读方案：若在背包中则需先经 {@link SelfInventoryTransferAction} 掏入主栏，再启动 {@link NpcEquipWeaponAction}。 */
    public record DesiredWeaponPlan(
            WeaponMode mode,
            HandWeapon weapon,
            BaseAction.ItemLocation location,
            boolean needsEquip,
            boolean needsUnpackFromBag) {}

    /** 战间弹匣压弹只读方案：包含目标弹匣、原背包归宿与需提前从背包掏出的零散实弹。 */
    public record RepackPlan(
            InventoryItem magazine,
            ItemContainer magOriginBag,
            List<SelfInventoryTransferAction.TransferEntry> prepTransfers) {}

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

    /** 若随从/守卫接管的枪械处于 {@code Safe} 保险档位，自动切至单发（或首个非 Safe 射击档）。 */
    public static void ensureFireModeOffSafe(HandWeapon gun) {
        if (gun == null || !gun.isSelectFire() || !"Safe".equals(gun.getFireMode())) {
            return;
        }
        List<String> modes = gun.getFireModePossibilities();
        if (modes == null || modes.isEmpty()) {
            gun.setFireMode("Single");
            return;
        }
        String fallback = null;
        for (int i = 0, n = modes.size(); i < n; i++) {
            String m = modes.get(i);
            if ("Single".equals(m)) {
                gun.setFireMode(m);
                return;
            }
            if (fallback == null && m != null && !m.isEmpty() && !"Safe".equals(m)) {
                fallback = m;
            }
        }
        if (fallback != null) {
            gun.setFireMode(fallback);
        }
    }

    /** 对照 {@code ISReloadWeaponAction.canShoot}（额外校验 Safe 保险状态）。 */
    public static boolean canShootNow(IsoNPC npc, HandWeapon gun) {
        return canShootCore(npc, gun, true);
    }

    private static boolean canShootCore(IsoNPC npc, HandWeapon gun, boolean checkSafe) {
        if (npc == null || !isUsableFirearm(gun)) return false;
        if (checkSafe && gun.isSelectFire() && "Safe".equals(gun.getFireMode())) {
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

    /** 判定当前所持枪械需要的真实装弹/排障工序；无备弹则返回 {@link ReloadKind#NONE}（支持递归感知背囊储备）。 */
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
            InventoryItem bestMag = findBestNonEmptyMagazine(npc, gun, magType);
            if (bestMag == null) {
                return ReloadKind.NONE;
            }
            if (gun.isContainsClip()) {
                if (gun.getCurrentAmmoCount() <= 0 || bestMag.getCurrentAmmoCount() > gun.getCurrentAmmoCount() + 2) {
                    return ReloadKind.EJECT_AND_INSERT_MAG;
                }
                return ReloadKind.NONE;
            }
            return ReloadKind.INSERT_MAG;
        }
        if (gun.getAmmoType() != null && gun.getCurrentAmmoCount() < gun.getMaxAmmo()) {
            if (hasItemRecursive(npc.getInventory(), gun.getAmmoType().getItemKey())) {
                return ReloadKind.LOAD_BULLETS;
            }
        }
        return ReloadKind.NONE;
    }

    /** 只读规划：确定 NPC 当前最理想的武器及其所在位置（主栏/挂槽 vs 背囊）。 */
    public static DesiredWeaponPlan planDesiredCombatWeapon(
            IsoNPC npc, boolean preferFirearm, boolean allowMeleeFallback) {
        if (npc == null || npc.isDead()) {
            return new DesiredWeaponPlan(WeaponMode.UNARMED, null, null, false, false);
        }
        HandWeapon current = getEquippedWeapon(npc);
        if (isUsableFirearm(current)) {
            ensureFireModeOffSafe(current);
        }
        BaseAction.ItemLocation bestGunLoc = findBestFirearmLocation(npc);
        HandWeapon bestGun = bestGunLoc != null && bestGunLoc.item() instanceof HandWeapon hw ? hw : null;
        BaseAction.ItemLocation bestMeleeLoc = findBestMeleeLocation(npc);
        HandWeapon bestMelee = bestMeleeLoc != null && bestMeleeLoc.item() instanceof HandWeapon hw ? hw : null;
        ItemContainer mainInv = npc.getInventory();

        if (preferFirearm) {
            if (isUsableFirearm(current) && (canShootNow(npc, current) || resolveNeededReload(npc, current) != ReloadKind.NONE)) {
                return new DesiredWeaponPlan(WeaponMode.FIREARM, current, new BaseAction.ItemLocation(mainInv, current), false, false);
            }
            if (bestGun != null && bestGun != current) {
                boolean inBag = mainInv != null && bestGunLoc.container() != null && bestGunLoc.container() != mainInv;
                return new DesiredWeaponPlan(WeaponMode.FIREARM, bestGun, bestGunLoc, true, inBag);
            }
            if (allowMeleeFallback) {
                if (isUsableMelee(current)) {
                    return new DesiredWeaponPlan(WeaponMode.MELEE, current, new BaseAction.ItemLocation(mainInv, current), false, false);
                }
                if (bestMelee != null && bestMelee != current) {
                    boolean inBag = mainInv != null && bestMeleeLoc.container() != null && bestMeleeLoc.container() != mainInv;
                    return new DesiredWeaponPlan(WeaponMode.MELEE, bestMelee, bestMeleeLoc, true, inBag);
                }
            }
            WeaponMode curMode = isUsableFirearm(current) ? WeaponMode.FIREARM
                    : (isUsableMelee(current) ? WeaponMode.MELEE : WeaponMode.UNARMED);
            return new DesiredWeaponPlan(curMode, current, null, false, false);
        }

        if (isUsableMelee(current)) {
            return new DesiredWeaponPlan(WeaponMode.MELEE, current, new BaseAction.ItemLocation(mainInv, current), false, false);
        }
        if (isUsableFirearm(current)) {
            if (canShootNow(npc, current) || resolveNeededReload(npc, current) != ReloadKind.NONE) {
                return new DesiredWeaponPlan(WeaponMode.FIREARM, current, new BaseAction.ItemLocation(mainInv, current), false, false);
            }
            if (allowMeleeFallback && bestMelee != null) {
                boolean inBag = mainInv != null && bestMeleeLoc.container() != null && bestMeleeLoc.container() != mainInv;
                return new DesiredWeaponPlan(WeaponMode.MELEE, bestMelee, bestMeleeLoc, true, inBag);
            }
            return new DesiredWeaponPlan(WeaponMode.FIREARM, current, new BaseAction.ItemLocation(mainInv, current), false, false);
        }
        if (bestMelee != null) {
            boolean inBag = mainInv != null && bestMeleeLoc.container() != null && bestMeleeLoc.container() != mainInv;
            return new DesiredWeaponPlan(WeaponMode.MELEE, bestMelee, bestMeleeLoc, true, inBag);
        }
        if (bestGun != null) {
            boolean inBag = mainInv != null && bestGunLoc.container() != null && bestGunLoc.container() != mainInv;
            return new DesiredWeaponPlan(WeaponMode.FIREARM, bestGun, bestGunLoc, true, inBag);
        }
        return new DesiredWeaponPlan(WeaponMode.UNARMED, null, null, false, false);
    }

    /** 检查装弹所需弹匣/散弹是否仍有部分位于随身背囊；若是，返回需要先行掏入主物品栏的转移清单。 */
    public static List<SelfInventoryTransferAction.TransferEntry> findReloadPrepTransfers(
            IsoNPC npc, HandWeapon gun, ReloadKind kind) {
        if (npc == null || gun == null || npc.getInventory() == null || kind == ReloadKind.NONE
                || kind == ReloadKind.RACK || kind == ReloadKind.UNJAM) {
            return Collections.emptyList();
        }
        ItemContainer mainInv = npc.getInventory();
        String magType = gun.getMagazineType();
        if (magType != null && !magType.isEmpty()
                && (kind == ReloadKind.INSERT_MAG || kind == ReloadKind.EJECT_AND_INSERT_MAG)) {
            BaseAction.ItemLocation bestLoc = findBestNonEmptyMagazineLocation(npc, gun, magType);
            if (bestLoc == null || bestLoc.item() == null || bestLoc.container() == mainInv) {
                return Collections.emptyList();
            }
            return List.of(new SelfInventoryTransferAction.TransferEntry(bestLoc.item(), bestLoc.container(), mainInv));
        }
        if (kind == ReloadKind.LOAD_BULLETS && gun.getAmmoType() != null) {
            String key = gun.getAmmoType().getItemKey();
            int missing = Math.max(0, gun.getMaxAmmo() - gun.getCurrentAmmoCount());
            int inMain = countMatchingItemsInContainer(mainInv, key);
            int needFromBags = Math.max(0, missing - inMain);
            if (needFromBags <= 0) {
                return Collections.emptyList();
            }
            List<SelfInventoryTransferAction.TransferEntry> out = new ArrayList<>();
            collectMatchingItemsFromBags(mainInv, key, needFromBags, mainInv, out);
            return out;
        }
        return Collections.emptyList();
    }

    /** 战间弹匣压弹规划（优先挑选与主枪配套的未满弹匣，并收集背囊中的弹匣与所需散弹）。 */
    public static RepackPlan planMagRepack(IsoNPC npc, HandWeapon preferredGun) {
        if (npc == null || npc.getInventory() == null) {
            return null;
        }
        ItemContainer mainInv = npc.getInventory();
        BaseAction.ItemLocation magLoc = findReloadableEmptyMagazineLocation(npc, preferredGun);
        if (magLoc == null || magLoc.item() == null || magLoc.item().getAmmoType() == null) {
            return null;
        }
        InventoryItem mag = magLoc.item();
        ItemContainer magOriginBag = (magLoc.container() != null && magLoc.container() != mainInv)
                ? magLoc.container()
                : null;
        String ammoKey = mag.getAmmoType().getItemKey();
        int missing = Math.max(0, mag.getMaxAmmo() - mag.getCurrentAmmoCount());
        if (ammoKey == null || missing <= 0) {
            return null;
        }

        List<SelfInventoryTransferAction.TransferEntry> transfers = new ArrayList<>();
        if (magOriginBag != null) {
            transfers.add(new SelfInventoryTransferAction.TransferEntry(mag, magOriginBag, mainInv));
        }
        int inMainBullets = countMatchingItemsInContainer(mainInv, ammoKey);
        int needFromBags = Math.max(0, missing - inMainBullets);
        if (needFromBags > 0) {
            collectMatchingItemsFromBags(mainInv, ammoKey, needFromBags, mainInv, transfers);
        }
        return new RepackPlan(mag, magOriginBag, transfers);
    }

    public static InventoryItem findBestNonEmptyMagazine(IsoNPC npc, String magFullType) {
        return findBestNonEmptyMagazine(npc, null, magFullType);
    }

    public static InventoryItem findBestNonEmptyMagazine(IsoNPC npc, HandWeapon gun, String magFullType) {
        BaseAction.ItemLocation loc = findBestNonEmptyMagazineLocation(npc, gun, magFullType);
        return loc != null ? loc.item() : null;
    }

    /** 仅在主物品栏中查找非空兼容弹匣（{@link NpcReloadAction} 运行期严格只消耗主栏真实物品）。 */
    public static InventoryItem findBestNonEmptyMagazineInMain(IsoNPC npc, String magFullType) {
        if (npc == null || npc.getInventory() == null || magFullType == null || magFullType.isEmpty()) {
            return null;
        }
        ItemContainer main = npc.getInventory();
        List<InventoryItem> items = main.getItems();
        if (items == null) return null;
        InventoryItem best = null;
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem it = items.get(i);
            if (it != null && matchesTypeKey(it, magFullType) && it.getCurrentAmmoCount() >= 1) {
                if (best == null || it.getCurrentAmmoCount() > best.getCurrentAmmoCount()) {
                    best = it;
                }
            }
        }
        return best;
    }

    public static BaseAction.ItemLocation findBestNonEmptyMagazineLocation(
            IsoNPC npc, HandWeapon gun, String magFullType) {
        if (npc == null || npc.getInventory() == null || magFullType == null || magFullType.isEmpty()) {
            return null;
        }
        ItemContainer mainInv = npc.getInventory();
        InventoryItem inMain = findBestNonEmptyMagazineInMain(npc, magFullType);
        if (inMain != null) {
            return new BaseAction.ItemLocation(mainInv, inMain);
        }
        if (gun != null && magFullType.equals(gun.getMagazineType())) {
            InventoryItem vanillaBest = gun.getBestMagazine(npc);
            if (vanillaBest != null && vanillaBest.getCurrentAmmoCount() >= 1) {
                ItemContainer c = vanillaBest.getContainer() != null ? vanillaBest.getContainer() : mainInv;
                return new BaseAction.ItemLocation(c, vanillaBest);
            }
        }
        return findBestMagazineLocationIn(mainInv, magFullType, 1);
    }

    public static InventoryItem findReloadableEmptyMagazine(IsoNPC npc) {
        BaseAction.ItemLocation loc = findReloadableEmptyMagazineLocation(npc, null);
        return loc != null ? loc.item() : null;
    }

    public static BaseAction.ItemLocation findReloadableEmptyMagazineLocation(IsoNPC npc, HandWeapon preferredGun) {
        if (npc == null || npc.getInventory() == null) return null;
        ItemContainer root = npc.getInventory();
        String preferredMagType = (preferredGun != null && preferredGun.getMagazineType() != null)
                ? preferredGun.getMagazineType() : null;
        if (preferredMagType != null && !preferredMagType.isEmpty()) {
            BaseAction.ItemLocation pref = findPartialMagForRepack(root, root, preferredMagType);
            if (pref != null) {
                return pref;
            }
        }
        return findPartialMagForRepack(root, root, null);
    }

    private static BaseAction.ItemLocation findPartialMagForRepack(
            ItemContainer root, ItemContainer cur, String requiredMagType) {
        if (root == null || cur == null) return null;
        List<InventoryItem> items = cur.getItems();
        if (items == null) return null;
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem item = items.get(i);
            if (item == null) continue;
            if (!(item instanceof HandWeapon)
                    && item.getMaxAmmo() > 0
                    && item.getAmmoType() != null
                    && item.getCurrentAmmoCount() < item.getMaxAmmo()
                    && (requiredMagType == null || matchesTypeKey(item, requiredMagType))) {
                String ammoKey = item.getAmmoType().getItemKey();
                if (ammoKey != null && hasItemRecursive(root, ammoKey)) {
                    return new BaseAction.ItemLocation(cur, item);
                }
            }
        }
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem item = items.get(i);
            if (item instanceof InventoryContainer bag && bag.getInventory() != null) {
                BaseAction.ItemLocation nested = findPartialMagForRepack(root, bag.getInventory(), requiredMagType);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    public static boolean hasAnyReserveAmmoForGun(IsoNPC npc, HandWeapon gun) {
        if (npc == null || gun == null) return false;
        if (npc.isUnlimitedAmmo()) return true;
        if (gun.getCurrentAmmoCount() > 0 || (gun.haveChamber() && gun.isRoundChambered())) return true;
        String magType = gun.getMagazineType();
        if (magType != null && !magType.isEmpty()) {
            if (findBestNonEmptyMagazine(npc, gun, magType) != null) return true;
        }
        if (gun.getAmmoType() != null) {
            return hasItemRecursive(npc.getInventory(), gun.getAmmoType().getItemKey());
        }
        return false;
    }

    public static HandWeapon findBestFirearmWithAmmoOrChambered(IsoNPC npc) {
        BaseAction.ItemLocation loc = findBestFirearmLocation(npc);
        return loc != null && loc.item() instanceof HandWeapon hw ? hw : null;
    }

    public static HandWeapon findBestMeleeWeapon(IsoNPC npc) {
        BaseAction.ItemLocation loc = findBestMeleeLocation(npc);
        return loc != null && loc.item() instanceof HandWeapon hw ? hw : null;
    }

    private static BaseAction.ItemLocation findBestFirearmLocation(IsoNPC npc) {
        if (npc == null || npc.getInventory() == null) return null;
        return findBestWeaponLocationRecursive(npc, npc.getInventory(), null, -1.0F, true);
    }

    private static BaseAction.ItemLocation findBestMeleeLocation(IsoNPC npc) {
        if (npc == null || npc.getInventory() == null) return null;
        return findBestWeaponLocationRecursive(npc, npc.getInventory(), null, -1.0F, false);
    }

    private static BaseAction.ItemLocation findBestWeaponLocationRecursive(
            IsoNPC npc,
            ItemContainer container,
            BaseAction.ItemLocation best,
            float bestScore,
            boolean firearm) {
        List<InventoryItem> items = container != null ? container.getItems() : null;
        if (items == null) return best;
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem it = items.get(i);
            if (it instanceof HandWeapon hw) {
                float score = scoreCandidateWeapon(npc, hw, firearm);
                if (container == npc.getInventory()) {
                    score += 0.5F;
                }
                if (score > bestScore) {
                    best = new BaseAction.ItemLocation(container, hw);
                    bestScore = score;
                }
            }
        }
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem it = items.get(i);
            if (it instanceof InventoryContainer bag && bag.getInventory() != null) {
                BaseAction.ItemLocation nested = findBestWeaponLocationRecursive(
                        npc, bag.getInventory(), best, bestScore, firearm);
                if (nested != best && nested != null && nested.item() instanceof HandWeapon hw) {
                    best = nested;
                    bestScore = scoreCandidateWeapon(npc, hw, firearm);
                }
            }
        }
        return best;
    }

    private static float scoreCandidateWeapon(IsoNPC npc, HandWeapon hw, boolean firearm) {
        if (firearm) {
            if (!isUsableFirearm(hw)) return -Float.MAX_VALUE;
            if (!canShootCore(npc, hw, false) && resolveNeededReload(npc, hw) == ReloadKind.NONE) {
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

    /** 仅查询主物品栏是否包含指定类型物品（绝不跨包）。 */
    public static boolean hasItemInMain(ItemContainer mainInv, String itemKey) {
        return findFirstMatchingItemInMain(mainInv, itemKey) != null;
    }

    /** 仅从主物品栏获取首个匹配原件（绝不跨包瞬移）。 */
    public static InventoryItem findFirstMatchingItemInMain(ItemContainer mainInv, String itemKey) {
        if (mainInv == null || itemKey == null) return null;
        List<InventoryItem> items = mainInv.getItems();
        if (items == null) return null;
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem it = items.get(i);
            if (it != null && matchesTypeKey(it, itemKey)) {
                return it;
            }
        }
        return null;
    }

    private static int countMatchingItemsInContainer(ItemContainer c, String itemKey) {
        if (c == null || itemKey == null) return 0;
        List<InventoryItem> items = c.getItems();
        if (items == null) return 0;
        int count = 0;
        for (int i = 0, n = items.size(); i < n; i++) {
            if (matchesTypeKey(items.get(i), itemKey)) {
                count++;
            }
        }
        return count;
    }

    private static int collectMatchingItemsFromBags(
            ItemContainer cur,
            String itemKey,
            int remaining,
            ItemContainer mainInv,
            List<SelfInventoryTransferAction.TransferEntry> out) {
        if (cur == null || remaining <= 0) return remaining;
        List<InventoryItem> items = cur.getItems();
        if (items == null) return remaining;
        if (cur != mainInv) {
            for (int i = 0, n = items.size(); i < n && remaining > 0; i++) {
                InventoryItem it = items.get(i);
                if (it != null && matchesTypeKey(it, itemKey)) {
                    out.add(new SelfInventoryTransferAction.TransferEntry(it, cur, mainInv));
                    remaining--;
                }
            }
        }
        for (int i = 0, n = items.size(); i < n && remaining > 0; i++) {
            InventoryItem it = items.get(i);
            if (it instanceof InventoryContainer bag && bag.getInventory() != null) {
                remaining = collectMatchingItemsFromBags(bag.getInventory(), itemKey, remaining, mainInv, out);
            }
        }
        return remaining;
    }

    private static BaseAction.ItemLocation findBestMagazineLocationIn(
            ItemContainer container, String magType, int minRounds) {
        if (container == null || magType == null) return null;
        List<InventoryItem> items = container.getItems();
        if (items == null) return null;
        BaseAction.ItemLocation best = null;
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem it = items.get(i);
            if (it == null) continue;
            if (matchesTypeKey(it, magType) && it.getCurrentAmmoCount() >= minRounds) {
                if (best == null || it.getCurrentAmmoCount() > best.item().getCurrentAmmoCount()) {
                    best = new BaseAction.ItemLocation(container, it);
                }
            }
        }
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem it = items.get(i);
            if (it instanceof InventoryContainer bag && bag.getInventory() != null) {
                BaseAction.ItemLocation nested = findBestMagazineLocationIn(bag.getInventory(), magType, minRounds);
                if (nested != null && (best == null || nested.item().getCurrentAmmoCount() > best.item().getCurrentAmmoCount())) {
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
