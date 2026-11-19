package emberroutes;

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
 * <p>模块一职责：
 * 1. 据点领地不规则几何规约与全楼层容器增量索引（{@link WarehouseFastIndex}）；
 * 2. {@code @Patch} 无锁世界容器脏队列排空 + AE2 最小堆动态退避巡检驱动；
 * 3. 单方块容器准入校验与私密排除标记（{@code ModData.ER_Private}）；
 * 4. 原版 {@link IsoRegions} 室内封闭与玩家自建屋顶状态查询；
 * 5. 零每帧 Lua 开销的不规则领地外轮廓线 + 3D 地面深度半透明铺底渲染（{@link TerritoryRenderer}）。
 */
public final class EmberRoutesBridge {
    private EmberRoutesBridge() {}

    /** 清空全部内存缓存（读档/返回主菜单时调用，杜绝跨存档串数据）。 */
    public static void reset() {
        ContainerDirtyQueue.reset();
        WarehouseFastIndex.reset();
        TerritoryRenderer.reset();
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
}
