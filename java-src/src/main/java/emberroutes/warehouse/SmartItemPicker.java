package emberroutes.warehouse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.Food;

/**
 * 智能差异化挑件器与 30s 纯内存物品软预约锁（杜绝多 NPC 争抢同件原件）：
 * <ul>
 *   <li>易腐食物 FEFO：优先挑选未通电冰箱外且最临期（未腐烂）的原件；</li>
 *   <li>工具/武器/消耗品：优先挑选高耐久/高剩余次数原件；</li>
 *   <li>大宗建材/常规物资：就近整批或优先清理零散碎堆。</li>
 * </ul>
 */
public final class SmartItemPicker {
    public static final double DEFAULT_SOFT_LOCK_TTL_SEC = 30.0;

    private static final class SoftLock {
        final int itemKey;
        final String settlementId;
        final String npcId;
        final long token;
        final double expiresSimTime;

        SoftLock(int itemKey, String settlementId, String npcId, long token, double expiresSimTime) {
            this.itemKey = itemKey;
            this.settlementId = settlementId;
            this.npcId = npcId;
            this.token = token;
            this.expiresSimTime = expiresSimTime;
        }
    }

    public record PickedItemRef(
            String settlementId,
            String containerId,
            int x,
            int y,
            int z,
            InventoryItem item) {}

    private static final Map<Integer, SoftLock> SOFT_LOCKS = new HashMap<>();
    private static long nextToken = 1L;

    private SmartItemPicker() {}

    public static synchronized void reset() {
        SOFT_LOCKS.clear();
        nextToken = 1L;
    }

    public static synchronized void clearSettlement(String settlementId) {
        if (settlementId == null) {
            return;
        }
        SOFT_LOCKS.values().removeIf(lock -> settlementId.equals(lock.settlementId));
    }

    public static synchronized void purgeExpiredLocks(double simTime) {
        if (SOFT_LOCKS.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<Integer, SoftLock>> it = SOFT_LOCKS.entrySet().iterator(); it.hasNext(); ) {
            SoftLock lock = it.next().getValue();
            if (lock.expiresSimTime > 0.0 && simTime >= lock.expiresSimTime) {
                it.remove();
            }
        }
    }

    public static int itemLockKey(InventoryItem item) {
        if (item == null) {
            return 0;
        }
        int id = item.getID();
        return id != 0 ? id : -Math.max(1, Math.abs(System.identityHashCode(item)));
    }

    public static synchronized boolean isItemSoftLockedByOther(int itemKey, String npcId, double simTime) {
        SoftLock lock = SOFT_LOCKS.get(Integer.valueOf(itemKey));
        if (lock == null) {
            return false;
        }
        if (lock.expiresSimTime > 0.0 && simTime >= lock.expiresSimTime) {
            SOFT_LOCKS.remove(Integer.valueOf(itemKey));
            return false;
        }
        return npcId == null || !npcId.equals(lock.npcId);
    }

    public static synchronized boolean acquireItemSoftLock(
            int itemKey,
            String settlementId,
            String npcId,
            double simTime,
            double ttlSec,
            Consumer<Runnable> scopeRegistrar) {
        if (itemKey == 0 || npcId == null) {
            return false;
        }
        if (isItemSoftLockedByOther(itemKey, npcId, simTime)) {
            return false;
        }
        long tok = nextToken++;
        double expires = ttlSec > 0.0 ? (simTime + ttlSec) : 0.0;
        SOFT_LOCKS.put(Integer.valueOf(itemKey), new SoftLock(itemKey, settlementId, npcId, tok, expires));
        if (scopeRegistrar != null) {
            scopeRegistrar.accept(() -> releaseItemSoftLock(itemKey, tok));
        }
        return true;
    }

    public static synchronized void releaseItemSoftLock(int itemKey, long token) {
        SoftLock cur = SOFT_LOCKS.get(Integer.valueOf(itemKey));
        if (cur != null && cur.token == token) {
            SOFT_LOCKS.remove(Integer.valueOf(itemKey));
        }
    }

    public static synchronized int getActiveLockCount() {
        return SOFT_LOCKS.size();
    }

    // ==================== 差异化挑件评分策略（纯数学，支持离线 JUnit） ====================

    /** 易腐食物 FEFO：排除腐烂/烧焦，优先未进通电冰箱及最临期食材。 */
    public static double scorePerishableFood(
            boolean inPoweredFridge, float age, float offAgeMax, boolean rotten, boolean burnt) {
        if (rotten || burnt) {
            return Double.NEGATIVE_INFINITY;
        }
        double fridgeBias = inPoweredFridge ? 0.0 : 1000.0;
        double spoilRatio = Math.max(0.0, age) / Math.max(1.0, offAgeMax);
        return fridgeBias + spoilRatio * 100.0;
    }

    /** 工具/武器/电池消耗品：排除损坏/耗尽，优先高耐久或高剩余次数。 */
    public static double scoreToolOrDrainable(
            int condition, int conditionMax, float currentUsesFloat, boolean isDrainable, boolean broken) {
        if (broken) {
            return Double.NEGATIVE_INFINITY;
        }
        if (isDrainable) {
            if (currentUsesFloat <= 0.0F) {
                return Double.NEGATIVE_INFINITY;
            }
            return currentUsesFloat * 100.0;
        }
        if (conditionMax > 0) {
            if (condition <= 0) {
                return Double.NEGATIVE_INFINITY;
            }
            return ((double) condition / conditionMax) * 100.0;
        }
        return 50.0;
    }

    /** 大宗建材/常规物：就近整批满足优先，次选清理零散碎堆。 */
    public static double scoreBulkMaterial(double distTiles, int stackCountInContainer, int neededCount) {
        double batchBonus = stackCountInContainer >= Math.max(1, neededCount)
                ? 200.0
                : (50.0 / Math.max(1, stackCountInContainer));
        return batchBonus - distTiles * 2.0;
    }

    private record ScoredCandidate(
            String containerId,
            int x,
            int y,
            int z,
            InventoryItem item,
            int lockKey,
            double score) {}

    /**
     * 按差异化挑件策略从 {@link WarehouseFastIndex#getLoadedSourcesByType(String, String)} 挑选真实原件并加 30s 软锁。
     */
    public static List<PickedItemRef> pickItemsForOrder(
            String settlementId,
            String fullType,
            int neededCount,
            String npcId,
            float npcX,
            float npcY,
            int npcZ,
            double simTime,
            Consumer<Runnable> scopeRegistrar) {
        if (settlementId == null || fullType == null || neededCount <= 0 || npcId == null) {
            return Collections.emptyList();
        }
        purgeExpiredLocks(simTime);

        List<WarehouseFastIndex.LoadedSource> sources =
                WarehouseFastIndex.getLoadedSourcesByType(settlementId, fullType);
        if (sources.isEmpty()) {
            return Collections.emptyList();
        }

        List<ScoredCandidate> candidates = new ArrayList<>();
        for (int si = 0, sn = sources.size(); si < sn; si++) {
            WarehouseFastIndex.LoadedSource src = sources.get(si);
            SmartSlot slot = SmartObjectRegistry.getContainerSlot(settlementId, src.containerId());
            if (slot != null && (slot.getContainerRole() == ContainerRole.PICKUP_OUT
                    || slot.getContainerRole() == ContainerRole.WORKSTATION_IO
                    || slot.isWithdrawnAt(simTime)
                    || !slot.isActive())) {
                continue;
            }
            WarehouseFastIndex.LiveContainerRef liveRef = WarehouseFastIndex.resolveLiveContainer(src.containerId());
            if (liveRef == null || liveRef.container() == null) {
                continue;
            }
            ItemContainer ic = liveRef.container();
            List<InventoryItem> items = ic.getItems();
            if (items == null || items.isEmpty()) {
                continue;
            }

            boolean powered = ic.isPowered();
            double dist = Math.hypot((src.x() + 0.5) - npcX, (src.y() + 0.5) - npcY)
                    + Math.abs(src.z() - npcZ) * 10.0;

            for (int ii = 0, in = items.size(); ii < in; ii++) {
                InventoryItem item = items.get(ii);
                if (item == null || item.isEquipped() || !fullType.equals(item.getFullType())) {
                    continue;
                }
                int key = itemLockKey(item);
                if (isItemSoftLockedByOther(key, npcId, simTime)) {
                    continue;
                }
                double score = scoreCandidateItem(item, powered, dist, src.count(), neededCount);
                if (!Double.isFinite(score) || score == Double.NEGATIVE_INFINITY) {
                    continue;
                }
                candidates.add(new ScoredCandidate(src.containerId(), src.x(), src.y(), src.z(), item, key, score));
            }
        }

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));

        List<PickedItemRef> picked = new ArrayList<>(Math.min(neededCount, candidates.size()));
        for (int i = 0, n = candidates.size(); i < n && picked.size() < neededCount; i++) {
            ScoredCandidate cand = candidates.get(i);
            if (!acquireItemSoftLock(
                    cand.lockKey(), settlementId, npcId, simTime, DEFAULT_SOFT_LOCK_TTL_SEC, scopeRegistrar)) {
                continue;
            }
            picked.add(new PickedItemRef(
                    settlementId, cand.containerId(), cand.x(), cand.y(), cand.z(), cand.item()));
        }
        return picked;
    }

    private static double scoreCandidateItem(
            InventoryItem item, boolean inPoweredContainer, double distTiles, int stackCountInContainer, int neededCount) {
        if (item.isBroken() || item.isBurnt()) {
            return Double.NEGATIVE_INFINITY;
        }
        if (item instanceof Food food) {
            if (food.isRotten()) {
                return Double.NEGATIVE_INFINITY;
            }
            if (food.getOffAgeMax() < 1_000_000_000) {
                return scorePerishableFood(
                        inPoweredContainer, food.getAge(), food.getOffAgeMax(), food.isRotten(), food.isBurnt());
            }
            return scoreBulkMaterial(distTiles, stackCountInContainer, neededCount);
        }
        if (item instanceof DrainableComboItem) {
            return scoreToolOrDrainable(
                    item.getCondition(), item.getConditionMax(), item.getCurrentUsesFloat(), true, item.isBroken());
        }
        String catNode = WarehouseFastIndex.classifyItem(item);
        if ("tools".equals(catNode) || catNode.startsWith("weapons.")) {
            return scoreToolOrDrainable(
                    item.getCondition(), item.getConditionMax(), 1.0F, false, item.isBroken());
        }
        return scoreBulkMaterial(distTiles, stackCountInContainer, neededCount);
    }
}
