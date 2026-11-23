package emberroutes.npc.brain;

import emberroutes.warehouse.ContainerRole;
import emberroutes.warehouse.SmartItemPicker;
import emberroutes.warehouse.SmartObjectRegistry;
import emberroutes.warehouse.SmartSlot;
import emberroutes.warehouse.SmartSlot.ContainerLease;
import emberroutes.warehouse.SmartZoningPolicy;
import emberroutes.warehouse.WarehouseFastIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;

/**
 * 批量巡回理货路径构造器（{@code BatchTourBuilder}）：
 * <ul>
 *   <li>同源 + 3 格同层邻域 {@code BUFFER_IN} 一次装包（最多 2 源箱 + 3 目标箱）；</li>
 *   <li>批量读条阻尼曲线：{@code T(N) = BASE + k * sqrt(N)}；</li>
 *   <li>同层最近邻巡回卸货排序，并将末尾目标站位写入 {@code tourTerminalAnchor} 触发下轮 Lookahead 接力。</li>
 * </ul>
 */
public final class BatchTourBuilder {
    public static final int MAX_SOURCE_CONTAINERS = 2;
    public static final int MAX_DEST_CONTAINERS = 3;
    public static final int SOURCE_NEIGHBOR_CHEBYSHEV_RADIUS = 3;
    public static final double BASE_TRANSFER_SEC = 0.65;
    public static final double SQRT_TRANSFER_COEFF = 0.35;
    public static final double DEFAULT_HAUL_LEASE_TTL_SEC = 30.0;
    public static final double FULL_CONTAINER_COOLDOWN_SEC = 60.0;

    public record TourAnchor(int x, int y, int z, long roomId) {}

    public static final class TourStop {
        public final String slotId;
        public final String containerId;
        public final int x;
        public final int y;
        public final int z;
        public final int standX;
        public final int standY;
        public final int standDirIdx;
        public final long roomId;
        public final List<InventoryItem> items;
        public final double totalWeight;
        public final ContainerLease lease;

        public TourStop(
                String slotId,
                String containerId,
                int x,
                int y,
                int z,
                int standX,
                int standY,
                int standDirIdx,
                long roomId,
                List<InventoryItem> items,
                double totalWeight,
                ContainerLease lease) {
            this.slotId = slotId;
            this.containerId = containerId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.standX = standX;
            this.standY = standY;
            this.standDirIdx = standDirIdx;
            this.roomId = roomId;
            this.items = items;
            this.totalWeight = totalWeight;
            this.lease = lease;
        }
    }

    public static final class TourPlan {
        public final String settlementId;
        public final List<TourStop> pickups;
        public final List<TourStop> dropOffs;
        public final TourAnchor tourTerminalAnchor;
        public final double totalWeight;
        public final Map<InventoryItem, ItemContainer> loadTargetsByItem;

        public TourPlan(
                String settlementId,
                List<TourStop> pickups,
                List<TourStop> dropOffs,
                TourAnchor tourTerminalAnchor,
                double totalWeight) {
            this(settlementId, pickups, dropOffs, tourTerminalAnchor, totalWeight, Collections.emptyMap());
        }

        public TourPlan(
                String settlementId,
                List<TourStop> pickups,
                List<TourStop> dropOffs,
                TourAnchor tourTerminalAnchor,
                double totalWeight,
                Map<InventoryItem, ItemContainer> loadTargetsByItem) {
            this.settlementId = settlementId;
            this.pickups = pickups;
            this.dropOffs = dropOffs;
            this.tourTerminalAnchor = tourTerminalAnchor;
            this.totalWeight = totalWeight;
            if (loadTargetsByItem != null && !loadTargetsByItem.isEmpty()) {
                this.loadTargetsByItem = new IdentityHashMap<>(loadTargetsByItem);
            } else {
                this.loadTargetsByItem = Collections.emptyMap();
            }
        }

        public int totalItemCount() {
            int n = 0;
            for (int i = 0; i < pickups.size(); i++) {
                n += pickups.get(i).items.size();
            }
            return n;
        }
    }

    private BatchTourBuilder() {}

    /** 拍卖阶段转移耗时估算（按原版 {@code ISInventoryTransferAction} 平均单件 50 刻 / 30 刻·s⁻¹ 线性累加）。 */
    public static double computeDampedTransferDuration(int itemCount) {
        int n = Math.max(1, itemCount);
        return n * (50.0 / VanillaActionTiming.TICKS_PER_SIM_SEC);
    }

    /** 贪心最近邻巡回排序（纯数据可单测，确保卸货折返总距离最短）。 */
    public static <T> List<T> orderStopsNearestNeighbor(
            int startX, int startY, List<T> stops, ToIntFunction<T> xFn, ToIntFunction<T> yFn) {
        if (stops == null || stops.size() <= 1) {
            return stops == null ? List.of() : new ArrayList<>(stops);
        }
        List<T> remaining = new ArrayList<>(stops);
        List<T> ordered = new ArrayList<>(remaining.size());
        int curX = startX;
        int curY = startY;
        while (!remaining.isEmpty()) {
            int bestIdx = 0;
            double bestDistSq = Double.MAX_VALUE;
            for (int i = 0, n = remaining.size(); i < n; i++) {
                T cand = remaining.get(i);
                double dx = xFn.applyAsInt(cand) - curX;
                double dy = yFn.applyAsInt(cand) - curY;
                double dSq = dx * dx + dy * dy;
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    bestIdx = i;
                }
            }
            T chosen = remaining.remove(bestIdx);
            ordered.add(chosen);
            curX = xFn.applyAsInt(chosen);
            curY = yFn.applyAsInt(chosen);
        }
        return ordered;
    }

    private static double travelDist3D(double ax, double ay, int az, double bx, double by, int bz) {
        return Math.hypot(bx - ax, by - ay) + Math.abs(bz - az) * 10.0;
    }

    /**
     * 固定起点 {@code start} 与终点 {@code end} 的最短多点巡回路径（s-t Path TSP）：
     * <ul>
     *   <li>{@code n <= 7} 使用 Held-Karp 精确动态规划求全局最优；</li>
     *   <li>{@code n > 7} 先贪心最近邻构造初解，再结合首尾边界进行 2-opt 局部消除交叉折返。</li>
     * </ul>
     */
    public static <T> List<T> orderStopsShortestPath(
            double startX, double startY, int startZ,
            double endX, double endY, int endZ,
            List<T> stops,
            ToIntFunction<T> xFn,
            ToIntFunction<T> yFn,
            ToIntFunction<T> zFn) {
        if (stops == null || stops.size() <= 1) {
            return stops == null ? List.of() : new ArrayList<>(stops);
        }
        int n = stops.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        int[] zs = new int[n];
        for (int i = 0; i < n; i++) {
            T s = stops.get(i);
            xs[i] = xFn.applyAsInt(s) + 0.5;
            ys[i] = yFn.applyAsInt(s) + 0.5;
            zs[i] = zFn.applyAsInt(s);
        }

        double[] fromStart = new double[n];
        double[] toEnd = new double[n];
        double[][] pair = new double[n][n];
        for (int i = 0; i < n; i++) {
            fromStart[i] = travelDist3D(startX, startY, startZ, xs[i], ys[i], zs[i]);
            toEnd[i] = travelDist3D(xs[i], ys[i], zs[i], endX, endY, endZ);
            for (int j = i + 1; j < n; j++) {
                double d = travelDist3D(xs[i], ys[i], zs[i], xs[j], ys[j], zs[j]);
                pair[i][j] = d;
                pair[j][i] = d;
            }
        }

        int[] order = (n <= 7)
                ? solveExactPathTsp(n, fromStart, toEnd, pair)
                : solveTwoOptPathTsp(n, fromStart, toEnd, pair);

        List<T> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(stops.get(order[i]));
        }
        return out;
    }

    private static int[] solveExactPathTsp(int n, double[] fromStart, double[] toEnd, double[][] pair) {
        int size = 1 << n;
        int fullMask = size - 1;
        double[][] dp = new double[size][n];
        int[][] parent = new int[size][n];
        for (int m = 0; m < size; m++) {
            for (int i = 0; i < n; i++) {
                dp[m][i] = Double.POSITIVE_INFINITY;
                parent[m][i] = -1;
            }
        }
        for (int i = 0; i < n; i++) {
            dp[1 << i][i] = fromStart[i];
        }
        for (int mask = 1; mask <= fullMask; mask++) {
            for (int last = 0; last < n; last++) {
                if ((mask & (1 << last)) == 0) {
                    continue;
                }
                double cur = dp[mask][last];
                if (!Double.isFinite(cur)) {
                    continue;
                }
                for (int nxt = 0; nxt < n; nxt++) {
                    if ((mask & (1 << nxt)) != 0) {
                        continue;
                    }
                    int nextMask = mask | (1 << nxt);
                    double cand = cur + pair[last][nxt];
                    if (cand < dp[nextMask][nxt]) {
                        dp[nextMask][nxt] = cand;
                        parent[nextMask][nxt] = last;
                    }
                }
            }
        }

        int bestLast = 0;
        double bestTotal = Double.POSITIVE_INFINITY;
        for (int last = 0; last < n; last++) {
            double total = dp[fullMask][last] + toEnd[last];
            if (total < bestTotal) {
                bestTotal = total;
                bestLast = last;
            }
        }
        int[] order = new int[n];
        int curMask = fullMask;
        int curNode = bestLast;
        for (int pos = n - 1; pos >= 0; pos--) {
            order[pos] = curNode;
            int prev = parent[curMask][curNode];
            curMask ^= (1 << curNode);
            curNode = prev;
        }
        return order;
    }

    private static int[] solveTwoOptPathTsp(int n, double[] fromStart, double[] toEnd, double[][] pair) {
        boolean[] used = new boolean[n];
        int[] order = new int[n];
        for (int step = 0; step < n; step++) {
            int bestIdx = -1;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (used[i]) {
                    continue;
                }
                double d = (step == 0) ? fromStart[i] : pair[order[step - 1]][i];
                if (d < bestDist) {
                    bestDist = d;
                    bestIdx = i;
                }
            }
            order[step] = bestIdx;
            used[bestIdx] = true;
        }

        for (int pass = 0; pass < 64; pass++) {
            boolean improved = false;
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    double before = (i == 0 ? fromStart[order[0]] : pair[order[i - 1]][order[i]])
                            + (j == n - 1 ? toEnd[order[n - 1]] : pair[order[j]][order[j + 1]]);
                    double after = (i == 0 ? fromStart[order[j]] : pair[order[i - 1]][order[j]])
                            + (j == n - 1 ? toEnd[order[i]] : pair[order[i]][order[j + 1]]);
                    if (after + 1.0E-6 < before) {
                        for (int l = i, r = j; l < r; l++, r--) {
                            int tmp = order[l];
                            order[l] = order[r];
                            order[r] = tmp;
                        }
                        improved = true;
                    }
                }
            }
            if (!improved) {
                break;
            }
        }
        return order;
    }

    /**
     * 为竞标获胜者构建「最多 2 源箱 + 3 同层目标箱」批巡回计划，并向 {@code scopeRegistrar}
     * 绑定出库租约、入库预留负重租约与 30s 物品软锁。
     */
    public static TourPlan buildHaulTour(
            String settlementId,
            SmartSlot primarySource,
            float npcCarryCapacityRemaining,
            String npcId,
            float npcX,
            float npcY,
            double simTime,
            Consumer<Runnable> scopeRegistrar) {
        double remainingCarry = Math.max(0.5, npcCarryCapacityRemaining);
        SmartItemPicker.CarryBudget fallbackBudget = new SmartItemPicker.CarryBudget(
                0.0, remainingCarry / SmartItemPicker.NO_DAMAGE_OVERLOAD_RATIO, Collections.emptyList(), false);
        return buildHaulTour(
                settlementId, primarySource, fallbackBudget, Collections.emptyList(),
                npcId, npcX, npcY, simTime, scopeRegistrar);
    }

    /**
     * 支持已装备减重背包分配的批量巡回计划构造器：优先按减重率将拾取物品指派入随身背包，并校验 50.0 主包瞬时硬上限。
     */
    public static TourPlan buildHaulTour(
            String settlementId,
            SmartSlot primarySource,
            SmartItemPicker.CarryBudget carryBudget,
            List<ItemContainer> bagContainers,
            String npcId,
            float npcX,
            float npcY,
            double simTime,
            Consumer<Runnable> scopeRegistrar) {
        if (settlementId == null || primarySource == null || !primarySource.isActive()
                || npcId == null || carryBudget == null) {
            return null;
        }
        if (primarySource.getContainerRole() != ContainerRole.BUFFER_IN
                || primarySource.getUnreservedOutItemCount() <= 0
                || primarySource.isWithdrawnAt(simTime)) {
            return null;
        }
        if (!carryBudget.canFitAnyMinWeight()) {
            return null;
        }

        List<SmartSlot> sourceSlots = collectNeighborSourceSlots(settlementId, primarySource, simTime);
        List<SmartSlot> allContainers = SmartObjectRegistry.getActiveContainerSlots(settlementId);
        if (allContainers.isEmpty()) {
            return null;
        }

        Map<String, List<InventoryItem>> dropItemsByDestId = new LinkedHashMap<>();
        Map<String, Double> dropWeightByDestId = new LinkedHashMap<>();
        Map<String, SmartSlot> destSlotById = new LinkedHashMap<>();
        Map<InventoryItem, ItemContainer> loadTargets = new IdentityHashMap<>();
        List<TourStop> provisionalPickups = new ArrayList<>(MAX_SOURCE_CONTAINERS);

        double totalTourWeight = 0.0;

        for (int si = 0; si < sourceSlots.size(); si++) {
            SmartSlot srcSlot = sourceSlots.get(si);
            WarehouseFastIndex.LiveContainerRef liveSrc =
                    WarehouseFastIndex.resolveLiveContainer(srcSlot.getContainerId());
            if (liveSrc == null || liveSrc.container() == null) {
                continue;
            }
            ItemContainer srcCont = liveSrc.container();
            List<InventoryItem> rawItems = srcCont.getItems();
            if (rawItems == null || rawItems.isEmpty()) {
                continue;
            }

            int maxPickForSlot = srcSlot.getUnreservedOutItemCount();
            List<InventoryItem> pickedForSrc = new ArrayList<>();
            double srcWeight = 0.0;

            for (int ii = 0, in = rawItems.size(); ii < in && pickedForSrc.size() < maxPickForSlot; ii++) {
                InventoryItem item = rawItems.get(ii);
                if (item == null || item.isEquipped()) {
                    continue;
                }
                int itemKey = SmartItemPicker.itemLockKey(item);
                if (SmartItemPicker.isItemSoftLockedByOther(itemKey, npcId, simTime)) {
                    continue;
                }
                double itemW = Math.max(0.05, item.getUnequippedWeight());
                if (!carryBudget.canFit(itemW)) {
                    continue;
                }

                SmartSlot dest = findBestDestinationForItem(
                        item,
                        itemW,
                        srcSlot,
                        allContainers,
                        destSlotById,
                        dropWeightByDestId,
                        simTime);
                if (dest == null) {
                    continue;
                }

                if (!SmartItemPicker.acquireItemSoftLock(
                        itemKey, settlementId, npcId, simTime,
                        SmartItemPicker.DEFAULT_SOFT_LOCK_TTL_SEC, scopeRegistrar)) {
                    continue;
                }

                int targetBagIdx = carryBudget.tryAssign(itemW);
                if (targetBagIdx == Integer.MIN_VALUE) {
                    continue;
                }
                if (targetBagIdx >= 0 && bagContainers != null && targetBagIdx < bagContainers.size()) {
                    loadTargets.put(item, bagContainers.get(targetBagIdx));
                }

                pickedForSrc.add(item);
                srcWeight += itemW;
                totalTourWeight += itemW;

                destSlotById.putIfAbsent(dest.slotId, dest);
                dropItemsByDestId.computeIfAbsent(dest.slotId, k -> new ArrayList<>()).add(item);
                dropWeightByDestId.merge(dest.slotId, Double.valueOf(itemW), Double::sum);
            }

            if (!pickedForSrc.isEmpty()) {
                ContainerLease outLease = SmartObjectRegistry.acquireContainerOutLease(
                        settlementId,
                        srcSlot.slotId,
                        npcId,
                        pickedForSrc.size(),
                        simTime,
                        DEFAULT_HAUL_LEASE_TTL_SEC,
                        scopeRegistrar);
                if (outLease == null) {
                    continue;
                }
                int[] stand = SmartObjectRegistry.pickBestStandTile(srcSlot, npcX, npcY, simTime);
                provisionalPickups.add(new TourStop(
                        srcSlot.slotId,
                        srcSlot.getContainerId(),
                        srcSlot.originX,
                        srcSlot.originY,
                        srcSlot.originZ,
                        stand[0],
                        stand[1],
                        stand[2],
                        srcSlot.getRoomId(),
                        pickedForSrc,
                        srcWeight,
                        outLease));
            }
        }

        if (provisionalPickups.isEmpty() || destSlotById.isEmpty()) {
            return null;
        }

        TourStop lastPickup = provisionalPickups.get(provisionalPickups.size() - 1);
        List<TourStop> rawDrops = new ArrayList<>(destSlotById.size());
        for (Map.Entry<String, SmartSlot> entry : destSlotById.entrySet()) {
            SmartSlot dest = entry.getValue();
            List<InventoryItem> items = dropItemsByDestId.get(dest.slotId);
            double w = dropWeightByDestId.getOrDefault(dest.slotId, Double.valueOf(0.0)).doubleValue();
            ContainerLease inLease = SmartObjectRegistry.acquireContainerInLease(
                    settlementId,
                    dest.slotId,
                    npcId,
                    w,
                    simTime,
                    DEFAULT_HAUL_LEASE_TTL_SEC,
                    scopeRegistrar);
            if (inLease == null) {
                continue;
            }
            int[] stand = SmartObjectRegistry.pickBestStandTile(
                    dest, lastPickup.standX + 0.5F, lastPickup.standY + 0.5F, simTime);
            rawDrops.add(new TourStop(
                    dest.slotId,
                    dest.getContainerId(),
                    dest.originX,
                    dest.originY,
                    dest.originZ,
                    stand[0],
                    stand[1],
                    stand[2],
                    dest.getRoomId(),
                    items,
                    w,
                    inLease));
        }

        if (rawDrops.isEmpty()) {
            return null;
        }

        List<TourStop> orderedDrops = orderStopsNearestNeighbor(
                lastPickup.standX, lastPickup.standY, rawDrops, d -> d.standX, d -> d.standY);
        TourStop lastDrop = orderedDrops.get(orderedDrops.size() - 1);
        TourAnchor terminalAnchor = new TourAnchor(lastDrop.standX, lastDrop.standY, lastDrop.z, lastDrop.roomId);
        return new TourPlan(
                settlementId, provisionalPickups, orderedDrops, terminalAnchor, totalTourWeight, loadTargets);
    }

    /** 寻找物品运行时备选存储箱（中途目标箱满仓时平滑切换用，排除指定跳过集合）。 */
    public static SmartSlot findFallbackDestinationForItem(
            String settlementId,
            InventoryItem item,
            int fromZ,
            float fromX,
            float fromY,
            String excludeContainerId,
            double simTime) {
        if (settlementId == null || item == null) {
            return null;
        }
        List<SmartSlot> all = SmartObjectRegistry.getActiveContainerSlots(settlementId);
        double w = Math.max(0.05, item.getUnequippedWeight());
        SmartSlot best = null;
        int bestPrio = Integer.MIN_VALUE;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0, n = all.size(); i < n; i++) {
            SmartSlot cand = all.get(i);
            if (!cand.isActive()
                    || cand.getContainerId().equals(excludeContainerId)
                    || cand.isFullCooldownAt(simTime)
                    || cand.isWithdrawnAt(simTime)
                    || !SmartZoningPolicy.matchesItem(cand.getFilterRule(), item)
                    || cand.getFreeInWeight() + 1.0E-4 < w) {
                continue;
            }
            int prio = cand.getPriority();
            double floorPen = cand.originZ == fromZ ? 0.0 : 400.0;
            double dx = cand.originX - fromX;
            double dy = cand.originY - fromY;
            double distSq = dx * dx + dy * dy + floorPen;
            if (prio > bestPrio || (prio == bestPrio && distSq < bestDistSq)) {
                bestPrio = prio;
                bestDistSq = distSq;
                best = cand;
            }
        }
        return best;
    }

    private static List<SmartSlot> collectNeighborSourceSlots(
            String settlementId, SmartSlot primary, double simTime) {
        List<SmartSlot> out = new ArrayList<>(MAX_SOURCE_CONTAINERS);
        out.add(primary);
        List<SmartSlot> advertised = SmartObjectRegistry.getAdvertisedHaulOutSlots(settlementId);
        SmartSlot bestNeighbor = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0, n = advertised.size(); i < n; i++) {
            SmartSlot cand = advertised.get(i);
            if (cand == primary || !cand.isActive() || cand.originZ != primary.originZ) {
                continue;
            }
            if (cand.getUnreservedOutItemCount() <= 0 || cand.isWithdrawnAt(simTime)) {
                continue;
            }
            int dx = Math.abs(cand.originX - primary.originX);
            int dy = Math.abs(cand.originY - primary.originY);
            if (Math.max(dx, dy) > SOURCE_NEIGHBOR_CHEBYSHEV_RADIUS) {
                continue;
            }
            double dSq = dx * dx + dy * dy;
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestNeighbor = cand;
            }
        }
        if (bestNeighbor != null) {
            out.add(bestNeighbor);
        }
        return out;
    }

    private static SmartSlot findBestDestinationForItem(
            InventoryItem item,
            double itemWeight,
            SmartSlot srcSlot,
            List<SmartSlot> allContainers,
            Map<String, SmartSlot> chosenDestMap,
            Map<String, Double> plannedWeightByDest,
            double simTime) {
        boolean destCapReached = chosenDestMap.size() >= MAX_DEST_CONTAINERS;
        SmartSlot best = null;
        int bestPrio = Integer.MIN_VALUE;
        double bestDistSq = Double.MAX_VALUE;

        for (int i = 0, n = allContainers.size(); i < n; i++) {
            SmartSlot cand = allContainers.get(i);
            if (!cand.isActive() || cand == srcSlot || cand.originZ != srcSlot.originZ) {
                continue;
            }
            if (destCapReached && !chosenDestMap.containsKey(cand.slotId)) {
                continue;
            }
            if (cand.isFullCooldownAt(simTime) || cand.isWithdrawnAt(simTime)) {
                continue;
            }
            if (!SmartZoningPolicy.matchesItem(cand.getFilterRule(), item)) {
                continue;
            }
            double alreadyPlanned = plannedWeightByDest.getOrDefault(cand.slotId, Double.valueOf(0.0)).doubleValue();
            if (cand.getFreeInWeight() - alreadyPlanned + 1.0E-4 < itemWeight) {
                continue;
            }

            int prio = cand.getPriority();
            double dx = cand.originX - srcSlot.originX;
            double dy = cand.originY - srcSlot.originY;
            double dSq = dx * dx + dy * dy;
            if (prio > bestPrio || (prio == bestPrio && dSq < bestDistSq)) {
                bestPrio = prio;
                bestDistSq = dSq;
                best = cand;
            }
        }
        return best;
    }
}
