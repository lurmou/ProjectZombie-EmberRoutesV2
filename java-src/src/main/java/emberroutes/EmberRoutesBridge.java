package emberroutes;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.NpcManager;
import emberroutes.warehouse.ContainerDirtyQueue;
import emberroutes.warehouse.TerritoryContour;
import emberroutes.warehouse.TerritoryRenderer;
import emberroutes.warehouse.WarehouseFastIndex;
import java.util.ArrayList;
import java.util.List;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.vm.KahluaTable;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.areas.isoregion.IsoRegions;
import zombie.iso.areas.isoregion.regions.IWorldRegion;
import zombie.iso.areas.isoregion.regions.IsoWorldRegion;

/**
 * 暴露给 Lua 的精简静态门面 {@code EmberRoutesJava}。
 *
 * <p>模块一职责：据点空间网格、AE2 真仓储增量索引与 3D 地面轮廓渲染；
 * <p>模块二职责：非玩家槽 {@code IsoNPC} 躯壳生命周期、{@code ByteBuffer} 原件快照与 EMBER 运动解耦控制。
 */
public final class EmberRoutesBridge {
    private EmberRoutesBridge() {}

    /** 清空全部内存缓存（仅限退回主菜单/切档总清理时调用，禁止单个业务域私自调用）。 */
    public static void reset() {
        resetWarehouse();
        resetNpcs();
    }

    /** 仅清空仓储与领地渲染缓存（据点全量重扫/重挂载专用，绝不影响在场与离屏 IsoNPC）。 */
    public static void resetWarehouse() {
        ContainerDirtyQueue.reset();
        WarehouseFastIndex.reset();
        TerritoryRenderer.reset();
    }

    /** 仅清空 NPC 运行态与离屏快照缓存。 */
    public static void resetNpcs() {
        NpcManager.reset();
    }

    /** 单调高精度诊断时钟（纳秒，用于单入口 Tick 管线慢 Stage 零分配点名统计）。 */
    public static double diagnosticTimeNanos() {
        return (double) System.nanoTime();
    }

    // ==================== 1. 据点空间与真仓储索引 ====================

    public static double syncSettlementAreas(String settlementId, KahluaTable flatRects) {
        return WarehouseFastIndex.syncSettlementAreas(settlementId, flatRects);
    }

    public static String findSettlementIdAt(int x, int y) {
        return WarehouseFastIndex.findSettlementIdAt(x, y);
    }

    public static boolean tickWarehouse() {
        return WarehouseFastIndex.tickWarehouse();
    }

    public static boolean flushDirtyContainers() {
        return WarehouseFastIndex.flushDirtyQueue();
    }

    public static double getDirtyEventCount() {
        return ContainerDirtyQueue.getTotalEvents();
    }

    public static double getAwakeTickerCount() {
        return WarehouseFastIndex.getAwakeTickerCount();
    }

    public static void onSquareLoaded(IsoGridSquare sq) {
        WarehouseFastIndex.onSquareLoaded(sq);
    }

    public static void onSquareUnloaded(IsoGridSquare sq) {
        WarehouseFastIndex.onSquareUnloaded(sq);
    }

    public static KahluaTable getWarehouseSnapshot(String settlementId) {
        return WarehouseFastIndex.getSnapshot(settlementId);
    }

    public static double getWarehouseRevision(String settlementId) {
        return WarehouseFastIndex.getRevision(settlementId);
    }

    public static double getWarehouseTypeCount(String settlementId, String fullType) {
        return WarehouseFastIndex.typeCount(settlementId, fullType);
    }

    public static double getWarehouseUsableTypeCount(String settlementId, String fullType) {
        return WarehouseFastIndex.usableTypeCount(settlementId, fullType);
    }

    public static void invalidateSettlementWarehouse(String settlementId) {
        WarehouseFastIndex.invalidateSettlement(settlementId);
    }

    public static void registerCategoryOverride(String key, String categoryNode) {
        WarehouseFastIndex.registerCategoryOverride(key, categoryNode);
    }

    public static void clearCategoryOverrides() {
        WarehouseFastIndex.clearCategoryOverrides();
    }

    public static boolean isEligibleStorageObject(IsoObject obj) {
        return WarehouseFastIndex.isEligibleStorageObject(obj);
    }

    public static boolean isContainerObjectPrivate(IsoObject obj) {
        return WarehouseFastIndex.isObjectPrivate(obj);
    }

    public static boolean setContainerObjectPrivate(IsoObject obj, boolean makePrivate) {
        return WarehouseFastIndex.setObjectPrivate(obj, makePrivate);
    }

    public static String getStorageObjectDisplayName(IsoObject obj) {
        return WarehouseFastIndex.resolveObjectDisplayName(obj);
    }

    // ==================== 2. IsoRegions 室内与自建屋顶查询 ====================

    private static int toInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /**
     * 查询扁平矩形组 {@code {x1, y1, w1, h1, ...}} 在指定楼层 Z 的封闭室内与屋顶覆盖统计（严格去重不重复计格）。
     */
    public static KahluaTable inspectAreasEnclosure(KahluaTable flatRects, int z) {
        KahluaTable out = J2SEPlatform.getInstance().newTable();
        int total = 0, enclosed = 0, roofed = 0;
        boolean playerRoom = false;

        if (flatRects != null) {
            List<int[]> raw = new ArrayList<>();
            int len = flatRects.len();
            for (int i = 1; i + 3 <= len; i += 4) {
                int rx = toInt(flatRects.rawget(Double.valueOf(i)));
                int ry = toInt(flatRects.rawget(Double.valueOf(i + 1)));
                int rw = toInt(flatRects.rawget(Double.valueOf(i + 2)));
                int rh = toInt(flatRects.rawget(Double.valueOf(i + 3)));
                if (rw > 0 && rh > 0) raw.add(new int[]{rx, ry, rw, rh});
            }
            List<int[]> disjoint = TerritoryContour.normalizeDisjoint(raw);
            for (int ri = 0; ri < disjoint.size(); ri++) {
                int[] r = disjoint.get(ri);
                int rx = r[0], ry = r[1], rw = r[2], rh = r[3];
                total += rw * rh;
                for (int x = rx; x < rx + rw; x++) {
                    for (int y = ry; y < ry + rh; y++) {
                        IWorldRegion reg = IsoRegions.getIsoWorldRegion(x, y, z);
                        if (reg != null) {
                            if (reg.isPlayerRoom()) playerRoom = true;
                            if ((reg instanceof IsoWorldRegion wr) && wr.isEnclosed()) {
                                enclosed++;
                            }
                            if (reg.isFullyRoofed() || ((IsoRegions.getSquareFlags(x, y, z) & IsoRegions.BIT_HAS_ROOF) != 0)) {
                                roofed++;
                            }
                        } else if ((IsoRegions.getSquareFlags(x, y, z) & IsoRegions.BIT_HAS_ROOF) != 0) {
                            roofed++;
                        }
                    }
                }
            }
        }

        out.rawset("total", Double.valueOf(total));
        out.rawset("enclosed", Double.valueOf(enclosed));
        out.rawset("roofed", Double.valueOf(roofed));
        out.rawset("playerBuilt", Boolean.valueOf(playerRoom));
        return out;
    }

    // ==================== 3. 不规则领地世界渲染状态同步 ====================

    public static void setZoneOverlayVisible(boolean visible) {
        TerritoryRenderer.setOverlayVisible(visible);
    }

    public static boolean isZoneOverlayVisible() {
        return TerritoryRenderer.isOverlayVisible();
    }

    public static void syncZoneEditorState(
            String editingSettlementId,
            int mode,
            KahluaTable flatPreviewRects,
            boolean hasDrag,
            int dragX, int dragY, int dragW, int dragH,
            boolean hasCursor,
            int cursorX, int cursorY) {
        TerritoryRenderer.syncEditorState(
                editingSettlementId,
                mode,
                flatPreviewRects,
                hasDrag, dragX, dragY, dragW, dragH,
                hasCursor, cursorX, cursorY
        );
    }

    public static void clearZoneEditorState() {
        TerritoryRenderer.clearEditorState();
    }

    // ==================== 4. M2 非玩家槽 IsoNPC 与 EMBER 运动控制 ====================

    public static String spawnNpc(double x, double y, double z, boolean female, String optionalName) {
        return NpcManager.spawnNew(x, y, z, female, optionalName);
    }

    public static boolean despawnNpc(String npcId) {
        return NpcManager.despawn(npcId);
    }

    public static double despawnAllNpcs() {
        return NpcManager.despawnAll();
    }

    public static KahluaTable listNpcIds() {
        KahluaTable out = J2SEPlatform.getInstance().newTable();
        List<String> ids = NpcManager.listIds();
        for (int i = 0; i < ids.size(); i++) {
            out.rawset(Double.valueOf(i + 1), ids.get(i));
        }
        return out;
    }

    public static String getNpcId(Object obj) {
        return NpcManager.getNpcId(obj);
    }

    public static boolean isNpcAttached(String npcId) {
        return NpcManager.isNpcAttached(npcId);
    }

    public static String getNpcDisplayName(String npcId) {
        return NpcManager.getNpcDisplayName(npcId);
    }

    public static IsoNPC getNpcObject(String npcId) {
        return NpcManager.getLiveNpc(npcId);
    }

    public static KahluaTable getNpcInfo(String npcId) {
        NpcManager.NpcInfoView info = NpcManager.getInfo(npcId);
        if (info == null) return null;
        KahluaTable out = J2SEPlatform.getInstance().newTable();
        out.rawset("npcId", info.npcId());
        out.rawset("displayName", info.displayName());
        out.rawset("phasedIn", Boolean.valueOf(info.phasedIn()));
        out.rawset("dead", Boolean.valueOf(info.dead()));
        out.rawset("isLocalPlayer", Boolean.valueOf(info.isLocalPlayer()));
        out.rawset("playerNumPlayers", Double.valueOf(info.playerNumPlayers()));
        out.rawset("x", Double.valueOf(info.x()));
        out.rawset("y", Double.valueOf(info.y()));
        out.rawset("z", Double.valueOf(info.z()));
        out.rawset("running", Boolean.valueOf(info.running()));
        out.rawset("moveStatus", info.moveStatus());
        out.rawset("moveReason", info.moveReason());
        out.rawset("moveSeq", Double.valueOf(info.moveSeq()));
        out.rawset("settledSeq", Double.valueOf(info.settledSeq()));
        out.rawset("itemCount", Double.valueOf(info.itemCount()));
        out.rawset("debugLine", info.debugLine());
        return out;
    }

    public static boolean phaseOutNpc(String npcId) {
        return NpcManager.phaseOut(npcId);
    }

    public static boolean phaseInNpc(String npcId) {
        return NpcManager.phaseIn(npcId);
    }

    public static boolean killNpc(String npcId) {
        return NpcManager.killNpc(npcId);
    }

    public static String giveNpcTestKit(String npcId) {
        return NpcManager.giveTestKit(npcId);
    }

    public static String inspectNpcInventoryIds(String npcId) {
        return NpcManager.inspectInventoryIds(npcId);
    }

    public static void tickNpcs() {
        NpcManager.tick();
    }

    public static void onNpcSquareLoaded(IsoGridSquare sq) {
        NpcManager.onSquareLoaded(sq);
    }

    public static boolean saveNpcsToSave() {
        return NpcManager.saveAllToDisk();
    }

    public static double loadNpcsFromSave() {
        return NpcManager.loadFromCurrentSave();
    }

    public static double moveNpcTo(String npcId, double x, double y, double z, boolean running) {
        return NpcManager.moveTo(npcId, x, y, z, running);
    }

    public static boolean stopNpcMove(String npcId) {
        return NpcManager.stopMove(npcId);
    }

    public static boolean startNpcFollow(String npcId, double playerIndex, double slot) {
        return NpcManager.startFollow(npcId, (int) Math.round(playerIndex), (int) Math.round(slot));
    }

    public static boolean stopNpcFollow(String npcId) {
        return NpcManager.stopFollow(npcId);
    }

    public static boolean isNpcFollowing(String npcId) {
        return NpcManager.isFollowing(npcId);
    }

    public static String getNpcFollowState(String npcId) {
        return NpcManager.getFollowState(npcId);
    }

    public static boolean isEndClampEnabled() {
        return emberroutes.npc.EmberParams.END_CLAMP_ENABLED;
    }

    public static void setEndClampEnabled(boolean enabled) {
        emberroutes.npc.EmberParams.END_CLAMP_ENABLED = enabled;
    }

    public static boolean setNpcRunning(String npcId, boolean running) {
        return NpcManager.setRunning(npcId, running);
    }

    public static boolean isNpcMoving(String npcId) {
        return NpcManager.isMoving(npcId);
    }

    public static KahluaTable getNpcMoveStatus(String npcId) {
        KahluaTable out = J2SEPlatform.getInstance().newTable();
        NpcManager.NpcInfoView info = NpcManager.getInfo(npcId);
        if (info == null) {
            out.rawset("exists", Boolean.FALSE);
            out.rawset("status", "MISSING");
            out.rawset("reason", "MISSING");
            out.rawset("seq", Double.valueOf(0.0));
            out.rawset("settledSeq", Double.valueOf(0.0));
            out.rawset("moving", Boolean.FALSE);
            return out;
        }
        out.rawset("exists", Boolean.TRUE);
        out.rawset("status", info.moveStatus());
        out.rawset("reason", info.moveReason());
        out.rawset("seq", Double.valueOf(info.moveSeq()));
        out.rawset("settledSeq", Double.valueOf(info.settledSeq()));
        out.rawset("moving", Boolean.valueOf("MOVING".equals(info.moveStatus())));
        out.rawset("x", Double.valueOf(info.x()));
        out.rawset("y", Double.valueOf(info.y()));
        out.rawset("z", Double.valueOf(info.z()));
        out.rawset("phasedIn", Boolean.valueOf(info.phasedIn()));
        out.rawset("dead", Boolean.valueOf(info.dead()));
        return out;
    }

    /**
     * 零分配批拉取本帧移动终结事件（ARRIVED / NO_PATH / STUCK / INTERRUPTED）：
     * 无事件时直接返回 {@code null}，热路径 0 {@code KahluaTable} 分配。
     */
    public static KahluaTable drainNpcMoveEvents() {
        List<NpcManager.MoveEvent> events = NpcManager.drainMoveEvents();
        if (events == null || events.isEmpty()) {
            return null;
        }
        J2SEPlatform platform = J2SEPlatform.getInstance();
        KahluaTable out = platform.newTable();
        for (int i = 0; i < events.size(); i++) {
            NpcManager.MoveEvent ev = events.get(i);
            KahluaTable row = platform.newTable();
            row.rawset("npcId", ev.npcId());
            row.rawset("seq", Double.valueOf(ev.seq()));
            row.rawset("status", ev.status());
            row.rawset("reason", ev.reason());
            out.rawset(Double.valueOf(i + 1), row);
        }
        return out;
    }

    public static String getNpcDebugLine(String npcId) {
        NpcManager.NpcInfoView info = NpcManager.getInfo(npcId);
        return info != null ? info.debugLine() : "";
    }
}
