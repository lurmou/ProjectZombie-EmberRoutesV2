package emberroutes.warehouse;

import emberroutes.warehouse.SmartSlot.ContainerLease;
import emberroutes.warehouse.SmartSlot.LeaseDirection;
import emberroutes.warehouse.SmartSlot.LeaseStatus;
import emberroutes.warehouse.SmartSlot.SlotKind;
import emberroutes.warehouse.SmartSlot.SmartSlotLease;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteGrid;
import zombie.seating.SeatingManager;
import zombie.util.list.PZArrayList;

/**
 * UE5 SmartObjectRegistry 逆向广播注册表（0 扫图、0 轮询、动态世界自愈）：
 * <ul>
 *   <li>直接寄生 {@link WarehouseFastIndex} 的区块/方块扫描与卸载链路；</li>
 *   <li>多格家具（{@code IsoSpriteGrid != null}）以网格原点 {@code (0,0)} 为唯一键，跨 Chunk 全子格齐备才激活、任一子格卸载即下架；</li>
 *   <li>容器与工位支持 8 邻格 {@code standDirMask} 预计算（30s 局部退避换侧）、{@code WORKSTATION_IO} 3 格双向对称绑定、
 *       以及 {@code BUFFER_IN} $O(1)$ 侵入式逆向广播表 {@code advertisedHaulOutSlots}。</li>
 * </ul>
 */
public final class SmartObjectRegistry {
    public static final int[] DIR_DX = {0, 1, 1, 1, 0, -1, -1, -1};
    public static final int[] DIR_DY = {-1, -1, 0, 1, 1, 1, 0, -1};
    public static final double STAND_DIR_BACKOFF_SEC = 30.0;
    public static final double WITHDRAWN_PROTECTION_SEC = 60.0;
    public static final double FULL_CONTAINER_COOLDOWN_SEC = 60.0;
    public static final int WORKSTATION_BIND_CHEBYSHEV_RADIUS = 3;

    private record TileRef(String settlementId, String slotId, SlotKind kind, int x, int y, int z, long bitMask) {}

    private static final class SettlementSlots {
        final Map<String, SmartSlot> byId = new HashMap<>();
        final Map<String, SmartSlot> containersById = new HashMap<>();
        final EnumMap<SlotKind, List<SmartSlot>> activeByKind = new EnumMap<>(SlotKind.class);
        final List<SmartSlot> advertisedHaulOutSlots = new ArrayList<>();
        final Map<Long, List<TileRef>> refsBySquare = new HashMap<>();
        boolean auctionDirty;

        SettlementSlots() {
            for (SlotKind kind : SlotKind.values()) {
                activeByKind.put(kind, new ArrayList<>());
            }
        }
    }

    private static final Map<String, SettlementSlots> SETTLEMENTS = new HashMap<>();
    private static final Map<Long, List<TileRef>> REFS_BY_CHUNK = new HashMap<>();
    private static long nextLeaseToken = 1L;
    private static volatile double currentSimTime = 0.0;

    private SmartObjectRegistry() {}

    public static void setCurrentSimTime(double simTime) {
        currentSimTime = Math.max(0.0, simTime);
    }

    public static double getCurrentSimTime() {
        return currentSimTime;
    }

    public static synchronized void reset() {
        for (SettlementSlots ss : SETTLEMENTS.values()) {
            for (SmartSlot slot : ss.byId.values()) {
                invalidateActiveLease(slot);
                slot.active = false;
                slot.advertisedHaulOutListIdx = -1;
            }
            ss.advertisedHaulOutSlots.clear();
            ss.containersById.clear();
            ss.auctionDirty = false;
        }
        SETTLEMENTS.clear();
        REFS_BY_CHUNK.clear();
        RoomConnectivityIndex.reset();
        nextLeaseToken = 1L;
        currentSimTime = 0.0;
    }

    public static synchronized void invalidateSettlement(String settlementId) {
        if (settlementId == null) {
            return;
        }
        RoomConnectivityIndex.invalidateSettlement(settlementId);
        SettlementSlots ss = SETTLEMENTS.remove(settlementId);
        if (ss == null) {
            return;
        }
        for (SmartSlot slot : ss.byId.values()) {
            invalidateActiveLease(slot);
            slot.active = false;
            slot.advertisedHaulOutListIdx = -1;
        }
        ss.advertisedHaulOutSlots.clear();
        ss.containersById.clear();
        for (Iterator<Map.Entry<Long, List<TileRef>>> it = REFS_BY_CHUNK.entrySet().iterator(); it.hasNext(); ) {
            List<TileRef> refs = it.next().getValue();
            refs.removeIf(r -> settlementId.equals(r.settlementId()));
            if (refs.isEmpty()) {
                it.remove();
            }
        }
    }

    /** 当据点领地矩形变更时，自动摘除已落在领地之外的床/椅注册信息。 */
    public static synchronized void trimOutsideRects(String settlementId, List<int[]> rects) {
        if (settlementId == null) {
            return;
        }
        if (rects == null || rects.isEmpty()) {
            invalidateSettlement(settlementId);
            return;
        }
        SettlementSlots ss = SETTLEMENTS.get(settlementId);
        if (ss == null || ss.refsBySquare.isEmpty()) {
            return;
        }
        List<long[]> toRemove = null;
        for (Map.Entry<Long, List<TileRef>> entry : ss.refsBySquare.entrySet()) {
            long sqKey = entry.getKey().longValue();
            int sx = ContainerDirtyQueue.unpackX(sqKey);
            int sy = ContainerDirtyQueue.unpackY(sqKey);
            if (!TerritoryContour.containsPoint(rects, sx, sy)) {
                if (toRemove == null) {
                    toRemove = new ArrayList<>();
                }
                toRemove.add(new long[]{sx, sy, ContainerDirtyQueue.unpackZ(sqKey)});
            }
        }
        if (toRemove != null) {
            for (int i = 0; i < toRemove.size(); i++) {
                long[] c = toRemove.get(i);
                unregisterSquare(settlementId, (int) c[0], (int) c[1], (int) c[2]);
            }
        }
    }

    public static String buildSlotId(int originX, int originY, int z, SlotKind kind) {
        return originX + ":" + originY + ":" + z + "#" + kind.name();
    }

    private static long packChunkCoord(int wx, int wy) {
        return (((long) wx) << 32) ^ (wy & 0xFFFFFFFFL);
    }

    /** 检查世界对象是否为真实床铺（与 {@code NpcPostureController} 共用同一分类内核，避免假沙发误判）。 */
    public static boolean isBedObject(IsoObject obj) {
        if (obj == null || obj.getObjectIndex() == -1 || obj.getProperties() == null
                || !obj.getProperties().has(IsoFlagType.bed)) {
            return false;
        }
        String spriteOrTileset;
        int gridW = 1;
        int gridH = 1;
        if (obj.getSprite() != null) {
            spriteOrTileset = obj.getSprite().tilesetName != null
                    ? obj.getSprite().tilesetName
                    : obj.getSprite().getName();
            IsoSpriteGrid grid = obj.getSprite().getSpriteGrid();
            if (grid != null) {
                gridW = Math.max(1, grid.getWidth());
                gridH = Math.max(1, grid.getHeight());
            }
        } else {
            spriteOrTileset = obj.getTextureName();
        }
        String customName = obj.getProperties().get("CustomName");
        String facing = obj.getProperties().get("Facing");
        return FurnitureClassifier.isRealBedObject(spriteOrTileset, customName, facing, gridW, gridH);
    }

    /** 检查世界对象是否为原版 {@code SeatingManager} 支持的可坐家具。 */
    public static boolean isSittableFurniture(IsoObject obj) {
        if (obj == null || obj.getObjectIndex() == -1 || obj.getSprite() == null) {
            return false;
        }
        SeatingManager sm = SeatingManager.getInstance();
        return sm != null && sm.getTilePositionCount(obj) > 0;
    }

    /**
     * 寄生于 {@link WarehouseFastIndex} 的单格扫描入口：单次遍历 {@code sq.getObjects()} 同步床/椅 {@link SmartSlot}，
     * 并在原格家具被拆毁时即时移除对应位掩码。
     */
    public static synchronized void onSquareScanned(String settlementId, IsoGridSquare sq) {
        if (settlementId == null || sq == null) {
            return;
        }
        int tx = sq.getX();
        int ty = sq.getY();
        int tz = sq.getZ();
        long sqKey = ContainerDirtyQueue.packCoord(tx, ty, tz);

        Set<String> seenSlotIds = null;
        PZArrayList<IsoObject> objects = sq.getObjects();
        if (objects != null && !objects.isEmpty()) {
            IsoCell cell = sq.getCell();
            for (int i = 0, n = objects.size(); i < n; i++) {
                IsoObject obj = objects.get(i);
                SlotKind kind = classifySmartFurnitureKind(obj);
                if (kind == null) {
                    continue;
                }
                int gridW = 1;
                int gridH = 1;
                int gpx = 0;
                int gpy = 0;
                IsoSprite sprite = obj.getSprite();
                IsoSpriteGrid grid = sprite != null ? sprite.getSpriteGrid() : null;
                if (grid != null) {
                    int rawX = grid.getSpriteGridPosX(sprite);
                    int rawY = grid.getSpriteGridPosY(sprite);
                    if (rawX >= 0 && rawY >= 0) {
                        gridW = Math.max(1, grid.getWidth());
                        gridH = Math.max(1, grid.getHeight());
                        gpx = rawX;
                        gpy = rawY;
                    }
                }
                SmartSlot slot = registerTileInternal(settlementId, kind, tx, ty, tz, gpx, gpy, gridW, gridH);
                if (slot != null) {
                    if (seenSlotIds == null) {
                        seenSlotIds = new HashSet<>(2);
                    }
                    seenSlotIds.add(slot.slotId);
                    if ((gridW > 1 || gridH > 1) && cell != null && !slot.active) {
                        probeLoadedSiblingTiles(settlementId, slot, cell, gpx, gpy);
                    }
                }
            }
        }

        RoomConnectivityIndex.scanSquareDoors(settlementId, sq);

        // 剔除该格原先存在但本次重扫已消失的床/椅家具位掩码（CONTAINER 与 WORKSTATION 由各自扫描/移除入口维护）
        SettlementSlots ss = SETTLEMENTS.get(settlementId);
        if (ss == null) {
            return;
        }
        List<TileRef> existing = ss.refsBySquare.get(sqKey);
        if (existing != null && !existing.isEmpty()) {
            for (int i = existing.size() - 1; i >= 0; i--) {
                TileRef ref = existing.get(i);
                if (ref.kind() != SlotKind.BED && ref.kind() != SlotKind.SEAT) {
                    continue;
                }
                if (seenSlotIds == null || !seenSlotIds.contains(ref.slotId())) {
                    detachTileRef(ss, existing, i, sqKey);
                }
            }
            if (existing.isEmpty()) {
                ss.refsBySquare.remove(sqKey);
            }
        }

        // O(1) 刷新本格及 8 邻格上已加载容器/工位插槽的可达站位掩码 standDirMask
        refreshStandDirMasksAround(ss, sq);
    }

    private static void refreshStandDirMasksAround(SettlementSlots ss, IsoGridSquare sq) {
        IsoCell cell = sq.getCell();
        if (cell == null || ss.refsBySquare.isEmpty()) {
            return;
        }
        int cx = sq.getX();
        int cy = sq.getY();
        int cz = sq.getZ();
        refreshStandDirMaskAtSquare(ss, sq);
        for (int d = 0; d < 8; d++) {
            int nx = cx + DIR_DX[d];
            int ny = cy + DIR_DY[d];
            long nbKey = ContainerDirtyQueue.packCoord(nx, ny, cz);
            if (!ss.refsBySquare.containsKey(nbKey)) {
                continue;
            }
            IsoGridSquare nbSq = cell.getGridSquare(nx, ny, cz);
            if (nbSq != null) {
                refreshStandDirMaskAtSquare(ss, nbSq);
            }
        }
    }

    private static void refreshStandDirMaskAtSquare(SettlementSlots ss, IsoGridSquare sq) {
        long sqKey = ContainerDirtyQueue.packCoord(sq.getX(), sq.getY(), sq.getZ());
        List<TileRef> refs = ss.refsBySquare.get(sqKey);
        if (refs == null || refs.isEmpty()) {
            return;
        }
        int computedMask = -1;
        long roomId = sq.getRoomID();
        for (int i = 0, n = refs.size(); i < n; i++) {
            TileRef ref = refs.get(i);
            if (ref.kind() != SlotKind.CONTAINER && ref.kind() != SlotKind.WORKSTATION) {
                continue;
            }
            SmartSlot slot = ss.byId.get(ref.slotId());
            if (slot != null) {
                if (computedMask < 0) {
                    computedMask = computeStandDirMask(sq);
                }
                slot.standDirMask = computedMask;
                slot.roomId = roomId;
            }
        }
    }

    /** 预计算世界格 8 邻格可达站位掩码（位 {@code d = 0..7} 对应 {@link #DIR_DX}/{@link #DIR_DY}）。 */
    public static int computeStandDirMask(IsoGridSquare sq) {
        if (sq == null) {
            return 0;
        }
        IsoCell cell = sq.getCell();
        if (cell == null) {
            return 0;
        }
        int x = sq.getX();
        int y = sq.getY();
        int z = sq.getZ();
        int mask = 0;
        for (int d = 0; d < 8; d++) {
            int nx = x + DIR_DX[d];
            int ny = y + DIR_DY[d];
            IsoGridSquare nb = cell.getGridSquare(nx, ny, z);
            if (nb == null || !nb.TreatAsSolidFloor() || nb.isSolid() || nb.isSolidTrans()) {
                continue;
            }
            if ((d & 1) == 0) {
                if (!sq.isBlockedTo(nb)) {
                    mask |= (1 << d);
                }
            } else {
                IsoGridSquare c1 = cell.getGridSquare(nx, y, z);
                IsoGridSquare c2 = cell.getGridSquare(x, ny, z);
                boolean via1 = c1 != null && !sq.isBlockedTo(c1) && !c1.isBlockedTo(nb);
                boolean via2 = c2 != null && !sq.isBlockedTo(c2) && !c2.isBlockedTo(nb);
                if (via1 || via2) {
                    mask |= (1 << d);
                }
            }
        }
        return mask;
    }

    /** 选出距发起点最近的非退避可达站位，返回 {@code {standX, standY, dirIdx}}（若无侧位则回退原点）。 */
    public static int[] pickBestStandTile(SmartSlot slot, float fromX, float fromY, double simTime) {
        if (slot == null) {
            return new int[]{0, 0, -1};
        }
        int bestDir = -1;
        double bestDistSq = Double.MAX_VALUE;
        int fallbackDir = -1;
        double fallbackDistSq = Double.MAX_VALUE;

        for (int d = 0; d < 8; d++) {
            if ((slot.standDirMask & (1 << d)) == 0) {
                continue;
            }
            int sx = slot.originX + DIR_DX[d];
            int sy = slot.originY + DIR_DY[d];
            double dx = (sx + 0.5) - fromX;
            double dy = (sy + 0.5) - fromY;
            double dSq = dx * dx + dy * dy;
            if (dSq < fallbackDistSq) {
                fallbackDistSq = dSq;
                fallbackDir = d;
            }
            if (!slot.isStandDirBackedOff(d, simTime) && dSq < bestDistSq) {
                bestDistSq = dSq;
                bestDir = d;
            }
        }

        int chosen = bestDir >= 0 ? bestDir : fallbackDir;
        if (chosen < 0) {
            return new int[]{slot.originX, slot.originY, -1};
        }
        return new int[]{slot.originX + DIR_DX[chosen], slot.originY + DIR_DY[chosen], chosen};
    }

    /** 寻路遇到特定方位 {@code NO_PATH / STUCK} 时记 30s 局部退避，驱动下次自动换侧。 */
    public static synchronized void markStandDirFailed(SmartSlot slot, int dirIdx, double simTime) {
        if (slot != null && dirIdx >= 0 && dirIdx < 8) {
            slot.standDirBackoffUntil[dirIdx] = simTime + STAND_DIR_BACKOFF_SEC;
        }
    }

    private static SlotKind classifySmartFurnitureKind(IsoObject obj) {
        if (isBedObject(obj)) {
            return SlotKind.BED;
        }
        if (isSittableFurniture(obj)) {
            return SlotKind.SEAT;
        }
        return null;
    }

    private static void probeLoadedSiblingTiles(
            String settlementId, SmartSlot slot, IsoCell cell, int selfGx, int selfGy) {
        for (int gx = 0; gx < slot.gridW; gx++) {
            for (int gy = 0; gy < slot.gridH; gy++) {
                if (gx == selfGx && gy == selfGy) {
                    continue;
                }
                int sibX = slot.originX + gx;
                int sibY = slot.originY + gy;
                IsoGridSquare sibSq = cell.getGridSquare(sibX, sibY, slot.originZ);
                if (sibSq == null) {
                    continue;
                }
                PZArrayList<IsoObject> objs = sibSq.getObjects();
                if (objs == null) {
                    continue;
                }
                for (int i = 0, n = objs.size(); i < n; i++) {
                    IsoObject sibObj = objs.get(i);
                    if (classifySmartFurnitureKind(sibObj) != slot.kind) {
                        continue;
                    }
                    IsoSprite spr = sibObj.getSprite();
                    IsoSpriteGrid g = spr != null ? spr.getSpriteGrid() : null;
                    if (g != null
                            && g.getSpriteGridPosX(spr) == gx
                            && g.getSpriteGridPosY(spr) == gy
                            && g.getWidth() == slot.gridW
                            && g.getHeight() == slot.gridH) {
                        registerTileInternal(
                                settlementId, slot.kind, sibX, sibY, slot.originZ, gx, gy, slot.gridW, slot.gridH);
                        break;
                    }
                }
            }
        }
    }

    /**
     * 核心纯数据注册接口（按网格原点主格 {@code originX = tileX - gridPosX, originY = tileY - gridPosY} 建键）：
     * 多格家具必须全部子格齐备（{@code loadedMask == fullMask}）才进入活跃广播列表。
     */
    public static synchronized SmartSlot registerTile(
            String settlementId,
            SlotKind kind,
            int tileX,
            int tileY,
            int z,
            int gridPosX,
            int gridPosY,
            int gridW,
            int gridH) {
        return registerTileInternal(settlementId, kind, tileX, tileY, z, gridPosX, gridPosY, gridW, gridH);
    }

    private static SmartSlot registerTileInternal(
            String settlementId,
            SlotKind kind,
            int tileX,
            int tileY,
            int z,
            int gridPosX,
            int gridPosY,
            int gridW,
            int gridH) {
        if (settlementId == null || kind == null) {
            return null;
        }
        int safeW = Math.max(1, gridW);
        int safeH = Math.max(1, gridH);
        int gx = Math.max(0, Math.min(safeW - 1, gridPosX));
        int gy = Math.max(0, Math.min(safeH - 1, gridPosY));
        int originX = tileX - gx;
        int originY = tileY - gy;
        String slotId = buildSlotId(originX, originY, z, kind);

        SettlementSlots ss = SETTLEMENTS.computeIfAbsent(settlementId, k -> new SettlementSlots());
        SmartSlot slot = ss.byId.get(slotId);
        if (slot == null || slot.gridW != safeW || slot.gridH != safeH) {
            if (slot != null) {
                removeSlotFromActiveList(ss, slot);
                invalidateActiveLease(slot);
            }
            slot = new SmartSlot(slotId, settlementId, kind, originX, originY, z, safeW, safeH);
            ss.byId.put(slotId, slot);
        }

        int bitIdx = Math.min(62, gy * safeW + gx);
        long bitMask = 1L << bitIdx;
        if ((slot.loadedMask & bitMask) == 0L) {
            slot.loadedMask |= bitMask;
            TileRef ref = new TileRef(settlementId, slotId, kind, tileX, tileY, z, bitMask);
            long sqKey = ContainerDirtyQueue.packCoord(tileX, tileY, z);
            ss.refsBySquare.computeIfAbsent(sqKey, k -> new ArrayList<>(2)).add(ref);
            long chunkKey = packChunkCoord(Math.floorDiv(tileX, 8), Math.floorDiv(tileY, 8));
            REFS_BY_CHUNK.computeIfAbsent(chunkKey, k -> new ArrayList<>(4)).add(ref);
        }

        if (!slot.active && (slot.loadedMask & slot.fullMask) == slot.fullMask) {
            slot.active = true;
            ss.activeByKind.get(kind).add(slot);
        }
        return slot;
    }

    /** 注册/刷新一个容器物流插槽（寄生 {@link WarehouseFastIndex}，实机扫格直入）。 */
    public static synchronized SmartSlot onContainerScanned(
            String settlementId,
            String containerId,
            IsoGridSquare sq,
            SmartZoningPolicy.ContainerFilterRule rule,
            int availableItemCount,
            double capacity,
            double usedWeight) {
        if (sq == null) {
            return null;
        }
        int mask = computeStandDirMask(sq);
        long roomId = sq.getRoomID();
        return registerContainerSlotInternal(
                settlementId,
                containerId,
                sq.getX(),
                sq.getY(),
                sq.getZ(),
                roomId,
                mask,
                rule,
                availableItemCount,
                capacity,
                usedWeight,
                currentSimTime);
    }

    /** 纯数据容器插槽注册入口（脱机 JUnit 与运行时共享统一逻辑）。 */
    public static synchronized SmartSlot registerContainerSlot(
            String settlementId,
            String containerId,
            int x,
            int y,
            int z,
            long roomId,
            int standDirMask,
            SmartZoningPolicy.ContainerFilterRule rule,
            int availableItemCount,
            double capacity,
            double usedWeight,
            double simTime) {
        return registerContainerSlotInternal(
                settlementId, containerId, x, y, z, roomId, standDirMask, rule,
                availableItemCount, capacity, usedWeight, simTime);
    }

    private static SmartSlot registerContainerSlotInternal(
            String settlementId,
            String containerId,
            int x,
            int y,
            int z,
            long roomId,
            int standDirMask,
            SmartZoningPolicy.ContainerFilterRule rule,
            int availableItemCount,
            double capacity,
            double usedWeight,
            double simTime) {
        if (settlementId == null || containerId == null || containerId.isEmpty()) {
            return null;
        }
        SettlementSlots ss = SETTLEMENTS.computeIfAbsent(settlementId, k -> new SettlementSlots());
        SmartSlot slot = ss.byId.get(containerId);
        boolean isNew = false;
        if (slot == null) {
            slot = new SmartSlot(containerId, settlementId, SlotKind.CONTAINER, x, y, z, 1, 1);
            slot.containerId = containerId;
            ss.byId.put(containerId, slot);
            ss.containersById.put(containerId, slot);
            isNew = true;
        }

        if ((slot.loadedMask & 1L) == 0L) {
            slot.loadedMask = 1L;
            TileRef ref = new TileRef(settlementId, containerId, SlotKind.CONTAINER, x, y, z, 1L);
            long sqKey = ContainerDirtyQueue.packCoord(x, y, z);
            ss.refsBySquare.computeIfAbsent(sqKey, k -> new ArrayList<>(2)).add(ref);
            long chunkKey = packChunkCoord(Math.floorDiv(x, 8), Math.floorDiv(y, 8));
            REFS_BY_CHUNK.computeIfAbsent(chunkKey, k -> new ArrayList<>(4)).add(ref);
        }

        boolean wasActive = slot.active;
        if (!slot.active) {
            slot.active = true;
            ss.activeByKind.get(SlotKind.CONTAINER).add(slot);
        }

        ContainerRole prevRole = slot.containerRole;
        int prevAvailable = slot.availableItemCount;
        SmartZoningPolicy.ContainerFilterRule safeRule = rule != null
                ? rule.copy()
                : new SmartZoningPolicy.ContainerFilterRule();

        slot.standDirMask = standDirMask;
        slot.roomId = roomId;
        slot.filterRule = safeRule;
        slot.containerRole = safeRule.role;
        slot.priority = safeRule.priority;
        slot.filterEnabled = safeRule.enabled;
        slot.availableItemCount = Math.max(0, availableItemCount);
        slot.capacity = Math.max(0.0, capacity);
        slot.usedWeight = Math.max(0.0, usedWeight);

        if (isNew || !wasActive || prevRole != slot.containerRole) {
            syncWorkstationBindings(ss, slot);
        }

        updateHaulOutAdvertisement(ss, slot, simTime);
        if (slot.advertisedHaulOutListIdx >= 0
                && (slot.availableItemCount > prevAvailable || prevRole != slot.containerRole)) {
            ss.auctionDirty = true;
        }
        return slot;
    }

    /** 容器被拆毁或设为私密时即时撤出广播、解绑工位并失效关联租约。 */
    public static synchronized void onContainerRemoved(String settlementId, String containerId) {
        if (settlementId == null || containerId == null) {
            return;
        }
        SettlementSlots ss = SETTLEMENTS.get(settlementId);
        if (ss == null) {
            return;
        }
        SmartSlot slot = ss.containersById.remove(containerId);
        if (slot == null) {
            return;
        }
        long sqKey = ContainerDirtyQueue.packCoord(slot.originX, slot.originY, slot.originZ);
        List<TileRef> sqRefs = ss.refsBySquare.get(sqKey);
        if (sqRefs != null) {
            for (int i = sqRefs.size() - 1; i >= 0; i--) {
                if (containerId.equals(sqRefs.get(i).slotId())) {
                    detachTileRef(ss, sqRefs, i, sqKey);
                }
            }
            if (sqRefs.isEmpty()) {
                ss.refsBySquare.remove(sqKey);
            }
        } else {
            applyBitClear(ss, containerId, 1L);
        }
    }

    /** 注册工位插槽并自动完成同层 Chebyshev ≤ 3 格 {@code WORKSTATION_IO} 双向对称绑定。 */
    public static synchronized SmartSlot registerWorkstationSlot(
            String settlementId, int x, int y, int z, long roomId, int standDirMask) {
        if (settlementId == null) {
            return null;
        }
        SmartSlot slot = registerTileInternal(settlementId, SlotKind.WORKSTATION, x, y, z, 0, 0, 1, 1);
        if (slot == null) {
            return null;
        }
        slot.roomId = roomId;
        slot.standDirMask = standDirMask;
        SettlementSlots ss = SETTLEMENTS.get(settlementId);
        if (ss != null) {
            syncWorkstationBindings(ss, slot);
        }
        return slot;
    }

    private static void syncWorkstationBindings(SettlementSlots ss, SmartSlot slot) {
        unbindWorkstationLinks(ss, slot);
        if (slot == null || !slot.active) {
            return;
        }
        if (slot.kind == SlotKind.WORKSTATION) {
            List<SmartSlot> containers = ss.activeByKind.get(SlotKind.CONTAINER);
            for (int i = 0, n = containers.size(); i < n; i++) {
                SmartSlot c = containers.get(i);
                if (c.active
                        && c.containerRole == ContainerRole.WORKSTATION_IO
                        && isWithinWorkstationRadius(slot, c)) {
                    slot.boundWorkstationIoIds.add(c.slotId);
                    c.boundWorkstationIds.add(slot.slotId);
                }
            }
        } else if (slot.kind == SlotKind.CONTAINER && slot.containerRole == ContainerRole.WORKSTATION_IO) {
            List<SmartSlot> stations = ss.activeByKind.get(SlotKind.WORKSTATION);
            for (int i = 0, n = stations.size(); i < n; i++) {
                SmartSlot w = stations.get(i);
                if (w.active && isWithinWorkstationRadius(slot, w)) {
                    slot.boundWorkstationIds.add(w.slotId);
                    w.boundWorkstationIoIds.add(slot.slotId);
                }
            }
        }
    }

    private static boolean isWithinWorkstationRadius(SmartSlot a, SmartSlot b) {
        if (a.originZ != b.originZ) {
            return false;
        }
        int dx = Math.abs(a.originX - b.originX);
        int dy = Math.abs(a.originY - b.originY);
        return Math.max(dx, dy) <= WORKSTATION_BIND_CHEBYSHEV_RADIUS;
    }

    private static void unbindWorkstationLinks(SettlementSlots ss, SmartSlot slot) {
        if (slot == null) {
            return;
        }
        if (!slot.boundWorkstationIds.isEmpty()) {
            for (String wid : slot.boundWorkstationIds) {
                SmartSlot w = ss.byId.get(wid);
                if (w != null) {
                    w.boundWorkstationIoIds.remove(slot.slotId);
                }
            }
            slot.boundWorkstationIds.clear();
        }
        if (!slot.boundWorkstationIoIds.isEmpty()) {
            for (String cid : slot.boundWorkstationIoIds) {
                SmartSlot c = ss.byId.get(cid);
                if (c != null) {
                    c.boundWorkstationIds.remove(slot.slotId);
                }
            }
            slot.boundWorkstationIoIds.clear();
        }
    }

    /** $O(1)$ 侵入式维护 {@code BUFFER_IN} 逆向广播表 {@code advertisedHaulOutSlots}。 */
    private static void updateHaulOutAdvertisement(SettlementSlots ss, SmartSlot slot, double simTime) {
        boolean shouldAdvertise = slot.active
                && slot.kind == SlotKind.CONTAINER
                && slot.containerRole == ContainerRole.BUFFER_IN
                && slot.filterEnabled
                && slot.getUnreservedOutItemCount() > 0
                && !slot.isWithdrawnAt(simTime);
        if (shouldAdvertise) {
            if (slot.advertisedHaulOutListIdx < 0) {
                slot.advertisedHaulOutListIdx = ss.advertisedHaulOutSlots.size();
                ss.advertisedHaulOutSlots.add(slot);
                ss.auctionDirty = true;
            }
        } else {
            removeFromAdvertisedHaulOut(ss, slot);
        }
    }

    private static void removeFromAdvertisedHaulOut(SettlementSlots ss, SmartSlot slot) {
        int idx = slot.advertisedHaulOutListIdx;
        if (idx < 0) {
            return;
        }
        int lastIdx = ss.advertisedHaulOutSlots.size() - 1;
        if (idx <= lastIdx && ss.advertisedHaulOutSlots.get(idx) == slot) {
            if (idx != lastIdx) {
                SmartSlot tail = ss.advertisedHaulOutSlots.get(lastIdx);
                ss.advertisedHaulOutSlots.set(idx, tail);
                tail.advertisedHaulOutListIdx = idx;
            }
            ss.advertisedHaulOutSlots.remove(lastIdx);
        }
        slot.advertisedHaulOutListIdx = -1;
    }

    /**
     * 申请容器出库软预约租约（绑定进 NPC 状态树 {@code StateScope}）；
     * 余量归零时 $O(1)$ swap-and-pop 自动从 {@code advertisedHaulOutSlots} 撤下广播。
     */
    public static synchronized ContainerLease acquireContainerOutLease(
            String settlementId,
            String slotId,
            String npcId,
            int itemCount,
            double simTime,
            double ttlSec,
            Consumer<Runnable> scopeRegistrar) {
        return acquireContainerLeaseInternal(
                settlementId, slotId, npcId, LeaseDirection.OUTBOUND, itemCount, 0.0, simTime, ttlSec, scopeRegistrar);
    }

    /** 申请容器入库预留负重租约（绑定进 NPC 状态树 {@code StateScope}）。 */
    public static synchronized ContainerLease acquireContainerInLease(
            String settlementId,
            String slotId,
            String npcId,
            double weight,
            double simTime,
            double ttlSec,
            Consumer<Runnable> scopeRegistrar) {
        return acquireContainerLeaseInternal(
                settlementId, slotId, npcId, LeaseDirection.INBOUND, 0, weight, simTime, ttlSec, scopeRegistrar);
    }

    private static ContainerLease acquireContainerLeaseInternal(
            String settlementId,
            String slotId,
            String npcId,
            LeaseDirection direction,
            int itemCount,
            double weight,
            double simTime,
            double ttlSec,
            Consumer<Runnable> scopeRegistrar) {
        if (settlementId == null || slotId == null || npcId == null) {
            return null;
        }
        SettlementSlots ss = SETTLEMENTS.get(settlementId);
        if (ss == null) {
            return null;
        }
        SmartSlot slot = ss.byId.get(slotId);
        if (slot == null || !slot.active || slot.kind != SlotKind.CONTAINER) {
            return null;
        }

        int reservedItems = 0;
        double reservedWeight = 0.0;
        if (direction == LeaseDirection.OUTBOUND) {
            if (itemCount <= 0 || slot.isWithdrawnAt(simTime)) {
                return null;
            }
            int avail = slot.getUnreservedOutItemCount();
            if (avail <= 0) {
                return null;
            }
            reservedItems = Math.min(itemCount, avail);
            slot.reservedOutItemCount += reservedItems;
        } else {
            if (weight <= 0.0 || slot.isFullCooldownAt(simTime) || slot.getFreeInWeight() + 1.0E-4 < weight) {
                return null;
            }
            reservedWeight = weight;
            slot.reservedInWeight += reservedWeight;
        }

        double expires = ttlSec > 0.0 ? (simTime + ttlSec) : 0.0;
        ContainerLease lease = new ContainerLease(
                slot, nextLeaseToken++, npcId, direction, reservedItems, reservedWeight, expires);
        slot.containerLeases.add(lease);
        if (direction == LeaseDirection.OUTBOUND) {
            updateHaulOutAdvertisement(ss, slot, simTime);
        }
        if (scopeRegistrar != null) {
            scopeRegistrar.accept(() -> releaseContainerLease(lease));
        }
        return lease;
    }

    /** 幂等释放容器物流软租约（通常由 {@code StateScope.close()} 自动触发），若有余量自动恢复广播。 */
    public static synchronized void releaseContainerLease(ContainerLease lease) {
        if (lease == null || lease.getStatus() != LeaseStatus.ACTIVE) {
            return;
        }
        SmartSlot slot = lease.getSlot();
        slot.containerLeases.remove(lease);
        if (lease.getDirection() == LeaseDirection.OUTBOUND) {
            slot.reservedOutItemCount = Math.max(0, slot.reservedOutItemCount - lease.getReservedItems());
        } else {
            slot.reservedInWeight = Math.max(0.0, slot.reservedInWeight - lease.getReservedWeight());
        }
        lease.setStatus(slot.active ? LeaseStatus.RELEASED : LeaseStatus.INVALIDATED);
        SettlementSlots ss = SETTLEMENTS.get(slot.settlementId);
        if (ss != null) {
            updateHaulOutAdvertisement(ss, slot, currentSimTime);
        }
    }

    /** 清理超时物流软租约并刷新 60s 提货保护窗到期后的 {@code BUFFER_IN} 广播。 */
    public static synchronized void refreshTimeBasedStates(String settlementId, double simTime) {
        currentSimTime = Math.max(currentSimTime, simTime);
        if (settlementId == null) {
            return;
        }
        SettlementSlots ss = SETTLEMENTS.get(settlementId);
        if (ss == null) {
            return;
        }
        List<SmartSlot> containers = ss.activeByKind.get(SlotKind.CONTAINER);
        for (int i = 0, n = containers.size(); i < n; i++) {
            SmartSlot slot = containers.get(i);
            if (!slot.containerLeases.isEmpty()) {
                for (int li = slot.containerLeases.size() - 1; li >= 0; li--) {
                    ContainerLease lease = slot.containerLeases.get(li);
                    if (lease.isExpiredAt(simTime)) {
                        releaseContainerLease(lease);
                    }
                }
            }
            if (slot.containerRole == ContainerRole.BUFFER_IN) {
                updateHaulOutAdvertisement(ss, slot, simTime);
            }
        }
    }

    public static synchronized void markWithdrawnWindow(String settlementId, String containerId, double untilSimTime) {
        SmartSlot slot = getContainerSlot(settlementId, containerId);
        if (slot == null) {
            return;
        }
        slot.withdrawnUntilSimTime = Math.max(slot.withdrawnUntilSimTime, untilSimTime);
        SettlementSlots ss = SETTLEMENTS.get(settlementId);
        if (ss != null) {
            updateHaulOutAdvertisement(ss, slot, currentSimTime);
        }
    }

    public static synchronized void markFullCooldown(String settlementId, String containerId, double untilSimTime) {
        SmartSlot slot = getContainerSlot(settlementId, containerId);
        if (slot != null) {
            slot.fullCooldownUntilSimTime = Math.max(slot.fullCooldownUntilSimTime, untilSimTime);
        }
    }

    public static synchronized boolean isAuctionDirty(String settlementId) {
        SettlementSlots ss = settlementId != null ? SETTLEMENTS.get(settlementId) : null;
        return ss != null && ss.auctionDirty;
    }

    public static synchronized boolean consumeAuctionDirty(String settlementId) {
        SettlementSlots ss = settlementId != null ? SETTLEMENTS.get(settlementId) : null;
        if (ss == null || !ss.auctionDirty) {
            return false;
        }
        ss.auctionDirty = false;
        return true;
    }

    public static synchronized void markAuctionDirty(String settlementId) {
        SettlementSlots ss = settlementId != null ? SETTLEMENTS.get(settlementId) : null;
        if (ss != null) {
            ss.auctionDirty = true;
        }
    }

    public static synchronized List<SmartSlot> getAdvertisedHaulOutSlots(String settlementId) {
        SettlementSlots ss = settlementId != null ? SETTLEMENTS.get(settlementId) : null;
        if (ss == null || ss.advertisedHaulOutSlots.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(ss.advertisedHaulOutSlots);
    }

    public static synchronized List<SmartSlot> getActiveContainerSlots(String settlementId) {
        SettlementSlots ss = settlementId != null ? SETTLEMENTS.get(settlementId) : null;
        if (ss == null) {
            return Collections.emptyList();
        }
        List<SmartSlot> list = ss.activeByKind.get(SlotKind.CONTAINER);
        return list == null || list.isEmpty() ? Collections.emptyList() : new ArrayList<>(list);
    }

    public static synchronized SmartSlot getContainerSlot(String settlementId, String containerId) {
        if (settlementId == null || containerId == null) {
            return null;
        }
        SettlementSlots ss = SETTLEMENTS.get(settlementId);
        return ss != null ? ss.containersById.get(containerId) : null;
    }

    public static RoomConnectivityIndex.RoomTransition classifyRoomTransition(
            String settlementId, int zA, long roomA, int zB, long roomB) {
        return RoomConnectivityIndex.classify(settlementId, zA, roomA, zB, roomB);
    }

    /** 区块/单格卸载下架：任一子格卸载立即令 {@link SmartSlot} 下架并置当前持有租约 {@code INVALIDATED}。 */
    public static synchronized void unregisterSquare(String settlementId, int tileX, int tileY, int z) {
        if (settlementId == null) {
            return;
        }
        SettlementSlots ss = SETTLEMENTS.get(settlementId);
        if (ss == null) {
            return;
        }
        long sqKey = ContainerDirtyQueue.packCoord(tileX, tileY, z);
        List<TileRef> refs = ss.refsBySquare.remove(sqKey);
        if (refs == null || refs.isEmpty()) {
            return;
        }
        for (int i = refs.size() - 1; i >= 0; i--) {
            detachTileRef(ss, refs, i, sqKey);
        }
    }

    /**
     * Chunk 级卸载通知：O(1) 哈希查表；即便多格家具跨 Chunk 甚至横跨据点边界，
     * 只要有任一子格落于该卸载 Chunk 内即刻触发对应 {@link SmartSlot} 整体下架。
     */
    public static synchronized void onChunkUnloaded(IsoChunk chunk) {
        if (chunk == null || REFS_BY_CHUNK.isEmpty()) {
            return;
        }
        long chunkKey = packChunkCoord(chunk.wx, chunk.wy);
        List<TileRef> chunkRefs = REFS_BY_CHUNK.remove(chunkKey);
        if (chunkRefs == null || chunkRefs.isEmpty()) {
            return;
        }
        for (int i = 0, n = chunkRefs.size(); i < n; i++) {
            TileRef ref = chunkRefs.get(i);
            SettlementSlots ss = SETTLEMENTS.get(ref.settlementId());
            if (ss == null) {
                continue;
            }
            applyBitClear(ss, ref.slotId(), ref.bitMask());
            long sqKey = ContainerDirtyQueue.packCoord(ref.x(), ref.y(), ref.z());
            List<TileRef> sqRefs = ss.refsBySquare.get(sqKey);
            if (sqRefs != null) {
                sqRefs.removeIf(r -> r.slotId().equals(ref.slotId()) && r.bitMask() == ref.bitMask());
                if (sqRefs.isEmpty()) {
                    ss.refsBySquare.remove(sqKey);
                }
            }
        }
    }

    private static void detachTileRef(SettlementSlots ss, List<TileRef> sqRefs, int index, long sqKey) {
        TileRef ref = sqRefs.remove(index);
        long chunkKey = packChunkCoord(Math.floorDiv(ref.x(), 8), Math.floorDiv(ref.y(), 8));
        List<TileRef> chunkRefs = REFS_BY_CHUNK.get(chunkKey);
        if (chunkRefs != null) {
            chunkRefs.removeIf(r ->
                    r.settlementId().equals(ref.settlementId())
                            && r.slotId().equals(ref.slotId())
                            && r.x() == ref.x()
                            && r.y() == ref.y()
                            && r.z() == ref.z());
            if (chunkRefs.isEmpty()) {
                REFS_BY_CHUNK.remove(chunkKey);
            }
        }
        applyBitClear(ss, ref.slotId(), ref.bitMask());
    }

    private static void applyBitClear(SettlementSlots ss, String slotId, long bitMask) {
        SmartSlot slot = ss.byId.get(slotId);
        if (slot == null) {
            return;
        }
        slot.loadedMask &= ~bitMask;
        if (slot.active && (slot.loadedMask & slot.fullMask) != slot.fullMask) {
            slot.active = false;
            removeSlotFromActiveList(ss, slot);
            removeFromAdvertisedHaulOut(ss, slot);
            unbindWorkstationLinks(ss, slot);
            invalidateActiveLease(slot);
        }
        if (slot.loadedMask == 0L) {
            ss.byId.remove(slotId);
            if (slot.containerId != null) {
                ss.containersById.remove(slot.containerId);
            }
        }
    }

    private static void removeSlotFromActiveList(SettlementSlots ss, SmartSlot slot) {
        List<SmartSlot> list = ss.activeByKind.get(slot.kind);
        if (list == null) {
            return;
        }
        int idx = list.indexOf(slot);
        if (idx >= 0) {
            int last = list.size() - 1;
            if (idx != last) {
                list.set(idx, list.get(last));
            }
            list.remove(last);
        }
    }

    private static void invalidateActiveLease(SmartSlot slot) {
        slot.occupiedByNpcId = null;
        slot.leaseExpiresSimTime = 0.0;
        slot.leaseToken = 0L;
        if (slot.activeLease != null) {
            slot.activeLease.setStatus(LeaseStatus.INVALIDATED);
            slot.activeLease = null;
        }
        if (!slot.containerLeases.isEmpty()) {
            for (int i = 0, n = slot.containerLeases.size(); i < n; i++) {
                ContainerLease cl = slot.containerLeases.get(i);
                cl.setStatus(LeaseStatus.INVALIDATED);
            }
            slot.containerLeases.clear();
        }
        slot.reservedOutItemCount = 0;
        slot.reservedInWeight = 0.0;
    }

    /** 查询指定据点当前是否存在满足物理门控的空闲 {@link SmartSlot}（零副作用预览）。 */
    public static synchronized boolean hasAvailableSlot(
            String settlementId,
            SlotKind kind,
            String npcId,
            double simTime,
            Predicate<SmartSlot> extraFilter) {
        return findNearestCandidate(settlementId, kind, npcId, 0.0F, 0.0F, 0, simTime, extraFilter) != null;
    }

    /**
     * 从指定据点按就近原则申领一个活跃且未占用的 {@link SmartSlot}，生成租约句柄；
     * {@code extraFilter} 用于接入物理防碰撞门控（如确认上一名 NPC 已完成起身动作）。
     */
    public static synchronized SmartSlotLease acquireNearest(
            String settlementId,
            SlotKind kind,
            String npcId,
            float npcX,
            float npcY,
            int npcZ,
            double simTime,
            double ttlSimSeconds,
            Predicate<SmartSlot> extraFilter) {
        SmartSlot best = findNearestCandidate(
                settlementId, kind, npcId, npcX, npcY, npcZ, simTime, extraFilter);
        if (best == null) {
            return null;
        }

        // 若同一 NPC 复租或前租已过期，先干净关闭旧租约对象
        if (best.activeLease != null && best.activeLease.getStatus() == LeaseStatus.ACTIVE) {
            best.activeLease.setStatus(LeaseStatus.RELEASED);
        }
        long token = nextLeaseToken++;
        best.occupiedByNpcId = npcId;
        best.leaseToken = token;
        best.leaseExpiresSimTime = ttlSimSeconds > 0.0 ? (simTime + ttlSimSeconds) : 0.0;
        SmartSlotLease lease = new SmartSlotLease(best, token);
        best.activeLease = lease;
        return lease;
    }

    private static SmartSlot findNearestCandidate(
            String settlementId,
            SlotKind kind,
            String npcId,
            float npcX,
            float npcY,
            int npcZ,
            double simTime,
            Predicate<SmartSlot> extraFilter) {
        if (settlementId == null || kind == null || npcId == null) {
            return null;
        }
        SettlementSlots ss = SETTLEMENTS.get(settlementId);
        if (ss == null) {
            return null;
        }
        List<SmartSlot> candidates = ss.activeByKind.get(kind);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        SmartSlot best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0, n = candidates.size(); i < n; i++) {
            SmartSlot slot = candidates.get(i);
            if (!slot.active) {
                continue;
            }
            if (slot.isOccupiedAt(simTime) && !npcId.equals(slot.occupiedByNpcId)) {
                continue;
            }
            if (extraFilter != null && !extraFilter.test(slot)) {
                continue;
            }
            double cx = slot.originX + slot.gridW * 0.5;
            double cy = slot.originY + slot.gridH * 0.5;
            double dx = cx - npcX;
            double dy = cy - npcY;
            double dz = Math.abs(slot.originZ - npcZ) * 10.0;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestScore) {
                bestScore = distSq;
                best = slot;
            }
        }
        return best;
    }

    /** 释放逻辑租约句柄（通常由 {@code StateScope.defer(...)} 自动触发）。 */
    public static synchronized void releaseLease(SmartSlotLease lease) {
        if (lease == null) {
            return;
        }
        SmartSlot slot = lease.getSlot();
        if (slot.leaseToken == lease.getLeaseToken()) {
            slot.occupiedByNpcId = null;
            slot.leaseExpiresSimTime = 0.0;
            slot.leaseToken = 0L;
            slot.activeLease = null;
            if (lease.getStatus() == LeaseStatus.ACTIVE) {
                lease.setStatus(LeaseStatus.RELEASED);
            }
        } else if (lease.getStatus() == LeaseStatus.ACTIVE) {
            lease.setStatus(LeaseStatus.INVALIDATED);
        }
    }

    /** 主线程按实时 {@link IsoCell} 解析当前 {@link SmartSlot} 原点主格对应的真实世界 {@link IsoObject}。 */
    public static IsoObject resolveWorldObject(SmartSlot slot, IsoCell cell) {
        if (slot == null || cell == null || !slot.active) {
            return null;
        }
        IsoGridSquare sq = cell.getGridSquare(slot.originX, slot.originY, slot.originZ);
        if (sq == null) {
            return null;
        }
        PZArrayList<IsoObject> objs = sq.getObjects();
        if (objs == null) {
            return null;
        }
        for (int i = 0, n = objs.size(); i < n; i++) {
            IsoObject obj = objs.get(i);
            if (classifySmartFurnitureKind(obj) == slot.kind) {
                return obj;
            }
        }
        return null;
    }

    public static synchronized SmartSlot getSlot(String settlementId, String slotId) {
        SettlementSlots ss = settlementId != null ? SETTLEMENTS.get(settlementId) : null;
        return ss != null ? ss.byId.get(slotId) : null;
    }

    public static synchronized int getActiveCount(String settlementId, SlotKind kind) {
        SettlementSlots ss = settlementId != null ? SETTLEMENTS.get(settlementId) : null;
        if (ss == null || kind == null) {
            return 0;
        }
        List<SmartSlot> list = ss.activeByKind.get(kind);
        return list != null ? list.size() : 0;
    }

    public static synchronized String getSummary(String settlementId) {
        SettlementSlots ss = settlementId != null ? SETTLEMENTS.get(settlementId) : null;
        if (ss == null) {
            return "none(beds=0,seats=0,containers=0,haulOut=0)";
        }
        int beds = ss.activeByKind.get(SlotKind.BED).size();
        int bedsOccupied = countOccupied(ss.activeByKind.get(SlotKind.BED));
        int seats = ss.activeByKind.get(SlotKind.SEAT).size();
        int seatsOccupied = countOccupied(ss.activeByKind.get(SlotKind.SEAT));
        int containers = ss.activeByKind.get(SlotKind.CONTAINER).size();
        int haulOut = ss.advertisedHaulOutSlots.size();
        return String.format(
                "%s(beds=%d/%d,seats=%d/%d,containers=%d,haulOut=%d)",
                settlementId, bedsOccupied, beds, seatsOccupied, seats, containers, haulOut);
    }

    private static int countOccupied(List<SmartSlot> list) {
        int cnt = 0;
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i).occupiedByNpcId != null) {
                cnt++;
            }
        }
        return cnt;
    }
}
