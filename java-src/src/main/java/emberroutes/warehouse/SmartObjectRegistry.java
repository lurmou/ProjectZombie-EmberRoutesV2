package emberroutes.warehouse;

import emberroutes.warehouse.SmartSlot.LeaseStatus;
import emberroutes.warehouse.SmartSlot.SlotKind;
import emberroutes.warehouse.SmartSlot.SmartSlotLease;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li>支持脱机无引擎纯数据单元测试与在场物理占座门控 {@code extraFilter}。</li>
 * </ul>
 */
public final class SmartObjectRegistry {
    private record TileRef(String settlementId, String slotId, int x, int y, int z, long bitMask) {}

    private static final class SettlementSlots {
        final Map<String, SmartSlot> byId = new HashMap<>();
        final EnumMap<SlotKind, List<SmartSlot>> activeByKind = new EnumMap<>(SlotKind.class);
        final Map<Long, List<TileRef>> refsBySquare = new HashMap<>();

        SettlementSlots() {
            for (SlotKind kind : SlotKind.values()) {
                activeByKind.put(kind, new ArrayList<>());
            }
        }
    }

    private static final Map<String, SettlementSlots> SETTLEMENTS = new HashMap<>();
    private static final Map<Long, List<TileRef>> REFS_BY_CHUNK = new HashMap<>();
    private static long nextLeaseToken = 1L;

    private SmartObjectRegistry() {}

    public static synchronized void reset() {
        for (SettlementSlots ss : SETTLEMENTS.values()) {
            for (SmartSlot slot : ss.byId.values()) {
                invalidateActiveLease(slot);
                slot.active = false;
            }
        }
        SETTLEMENTS.clear();
        REFS_BY_CHUNK.clear();
        nextLeaseToken = 1L;
    }

    public static synchronized void invalidateSettlement(String settlementId) {
        if (settlementId == null) {
            return;
        }
        SettlementSlots ss = SETTLEMENTS.remove(settlementId);
        if (ss == null) {
            return;
        }
        for (SmartSlot slot : ss.byId.values()) {
            invalidateActiveLease(slot);
            slot.active = false;
        }
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

        // 剔除该格原先存在但本次重扫已消失的家具位掩码（如大锤拆床/拆椅）
        SettlementSlots ss = SETTLEMENTS.get(settlementId);
        if (ss == null) {
            return;
        }
        List<TileRef> existing = ss.refsBySquare.get(sqKey);
        if (existing == null || existing.isEmpty()) {
            return;
        }
        for (int i = existing.size() - 1; i >= 0; i--) {
            TileRef ref = existing.get(i);
            if (seenSlotIds == null || !seenSlotIds.contains(ref.slotId())) {
                detachTileRef(ss, existing, i, sqKey);
            }
        }
        if (existing.isEmpty()) {
            ss.refsBySquare.remove(sqKey);
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
            TileRef ref = new TileRef(settlementId, slotId, tileX, tileY, z, bitMask);
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
            invalidateActiveLease(slot);
        }
        if (slot.loadedMask == 0L) {
            ss.byId.remove(slotId);
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
            return "none(beds=0,seats=0)";
        }
        int beds = ss.activeByKind.get(SlotKind.BED).size();
        int bedsOccupied = countOccupied(ss.activeByKind.get(SlotKind.BED));
        int seats = ss.activeByKind.get(SlotKind.SEAT).size();
        int seatsOccupied = countOccupied(ss.activeByKind.get(SlotKind.SEAT));
        return String.format("%s(beds=%d/%d,seats=%d/%d)", settlementId, bedsOccupied, beds, seatsOccupied, seats);
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
