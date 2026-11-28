package emberroutes;

import emberroutes.npc.FormationSpacing;
import emberroutes.npc.FormationType;
import emberroutes.npc.IsoNPC;
import emberroutes.npc.NpcManager;
import emberroutes.npc.NpcPortraitBakery;
import emberroutes.npc.NpcPostureController;
import emberroutes.npc.RulesOfEngagement;
import emberroutes.npc.SquadTacticalConfig;
import emberroutes.npc.SquadTacticalConfig.OrderMode;
import emberroutes.npc.SquadTacticalConfig.VehicleCommandMode;
import emberroutes.npc.brain.DeliveryOrderManager;
import emberroutes.npc.brain.LogisticsWorkDutyProvider;
import emberroutes.npc.combat.CombatDiagLog;
import emberroutes.npc.combat.EmberSquadDirector;
import emberroutes.warehouse.ContainerDirtyQueue;
import emberroutes.warehouse.ContainerRole;
import emberroutes.warehouse.SmartObjectRegistry;
import emberroutes.warehouse.SmartZoningPolicy;
import emberroutes.warehouse.TerritoryContour;
import emberroutes.warehouse.TerritoryRenderer;
import emberroutes.warehouse.WarehouseFastIndex;
import java.util.ArrayList;
import java.util.List;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import zombie.core.textures.Texture;
import zombie.inventory.ItemContainer;
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
        out.rawset("postureState", info.postureState());
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

    // ==================== 5. M3-A 原生坐卧睡眠动作控制 ====================

    public static boolean sitNpcOnFurniture(String npcId, IsoObject furniture) {
        return NpcManager.sitNpcOnFurniture(npcId, furniture);
    }

    public static boolean sleepNpcOnBed(String npcId, IsoObject bed) {
        return NpcManager.sleepNpcOnBed(npcId, bed);
    }

    public static boolean standUpNpc(String npcId, boolean urgent) {
        return NpcManager.standUpNpc(npcId, urgent);
    }

    public static double standUpAllNpcs(boolean urgent) {
        return NpcManager.standUpAllNpcs(urgent);
    }

    public static String getNpcPostureState(String npcId) {
        return NpcManager.getPostureState(npcId);
    }

    public static boolean isNpcAnimRecorderActive(String npcId) {
        return NpcManager.isNpcAnimRecorderActive(npcId);
    }

    public static String toggleNpcAnimRecorder(String npcId) {
        return NpcManager.toggleNpcAnimRecorder(npcId);
    }

    public static boolean isAllNpcAnimRecorderEnabled() {
        return NpcManager.isAllNpcAnimRecorderEnabled();
    }

    public static String setAllNpcAnimRecorderEnabled(boolean enabled) {
        return NpcManager.setAllNpcAnimRecorderEnabled(enabled);
    }

    public static boolean isSittableFurniture(IsoObject obj) {
        return NpcPostureController.isSittableFurniture(obj);
    }

    public static boolean isBedObject(IsoObject obj) {
        return NpcPostureController.isBedObject(obj);
    }

    // ==================== 6. M3-B 双大脑、EmberStateTree 与 SmartSlot 端口 ====================

    public static void tickNpcBrains(double frameTick, double dtSim, double simTime) {
        NpcManager.tickBrains(Math.round(frameTick), dtSim, simTime);
    }

    public static boolean setNpcBrainMode(String npcId, String modeName) {
        return NpcManager.setBrainMode(npcId, modeName);
    }

    public static String getNpcBrainMode(String npcId) {
        return NpcManager.getBrainMode(npcId);
    }

    public static String getNpcBrainStatePath(String npcId) {
        return NpcManager.getBrainStatePath(npcId);
    }

    public static String getSmartSlotSummary(String settlementId) {
        return SmartObjectRegistry.getSummary(settlementId);
    }

    public static String debugApplyNpcNeed(String npcId, String needKind) {
        return NpcManager.debugApplyNeed(npcId, needKind);
    }

    public static boolean assignNpcToSettlement(String npcId, String settlementId) {
        return NpcManager.assignToSettlement(npcId, settlementId);
    }

    public static String getNpcAssignedSettlement(String npcId) {
        return NpcManager.getAssignedSettlementId(npcId);
    }

    public static boolean setNpcGuardDuty(String npcId, boolean guard) {
        return NpcManager.setGuardDuty(npcId, guard);
    }

    public static boolean isNpcGuardDuty(String npcId) {
        return NpcManager.isGuardDuty(npcId);
    }

    public static double clearSettlementNpcAssignments(String settlementId) {
        return NpcManager.clearSettlementAssignments(settlementId);
    }

    // ==================== 7. M3-② SmartZoning 预设、容器角色与实体配送 ====================

    public static void registerSmartZoningPreset(String id, KahluaTable allowGroups, double defaultPriority) {
        List<String> groups = new ArrayList<>();
        if (allowGroups != null) {
            KahluaTableIterator it = allowGroups.iterator();
            while (it.advance()) {
                Object val = it.getValue();
                if (val instanceof String s && !s.isBlank()) {
                    groups.add(s);
                } else if (Boolean.TRUE.equals(val) && it.getKey() instanceof String k && !k.isBlank()) {
                    groups.add(k);
                }
            }
        }
        SmartZoningPolicy.registerPreset(id, groups, (int) Math.round(defaultPriority));
    }

    public static String getContainerRole(IsoObject obj) {
        return SmartZoningPolicy.readRule(obj).role.name();
    }

    public static String setContainerRole(IsoObject obj, String roleName) {
        if (obj == null) {
            return ContainerRole.STORAGE.name();
        }
        SmartZoningPolicy.ContainerFilterRule rule = SmartZoningPolicy.readRule(obj);
        rule.role = ContainerRole.fromString(roleName);
        SmartZoningPolicy.writeRule(obj, rule);
        return rule.role.name();
    }

    public static String autoConfigureContainer(IsoObject obj) {
        if (obj == null) {
            return "INVALID";
        }
        ItemContainer ic = obj.getContainer();
        if (ic == null && obj.getContainerCount() > 0) {
            ic = obj.getContainerByIndex(0);
        }
        if (ic == null) {
            return "NO_CONTAINER";
        }
        SmartZoningPolicy.ContainerFilterRule evaluated = SmartZoningPolicy.evaluateAutoRule(obj, ic);
        SmartZoningPolicy.ContainerFilterRule existing = SmartZoningPolicy.readRule(obj);
        if (existing.role != ContainerRole.STORAGE) {
            evaluated.role = existing.role;
        }
        SmartZoningPolicy.writeRule(obj, evaluated);
        return evaluated.role.name() + ":P" + evaluated.priority + ":" + String.join(",", evaluated.allowGroups);
    }

    public static String submitDeliveryOrder(
            String settlementId, double playerIndex, String fullType, double count) {
        return DeliveryOrderManager.submitOrder(
                settlementId,
                (int) Math.round(playerIndex),
                fullType,
                (int) Math.round(count),
                SmartObjectRegistry.getCurrentSimTime());
    }

    public static String submitDeliveryOrderToBox(
            String settlementId, double playerIndex, String fullType, double count, String targetContainerId) {
        return DeliveryOrderManager.submitOrder(
                settlementId,
                (int) Math.round(playerIndex),
                fullType,
                (int) Math.round(count),
                SmartObjectRegistry.getCurrentSimTime(),
                targetContainerId);
    }

    public static boolean cancelDeliveryOrder(String orderId) {
        return DeliveryOrderManager.cancelOrder(orderId);
    }

    public static KahluaTable listDeliveryOrders(String settlementId) {
        List<DeliveryOrderManager.DeliveryOrder> list = DeliveryOrderManager.listOrders(settlementId);
        J2SEPlatform platform = J2SEPlatform.getInstance();
        KahluaTable out = platform.newTable();
        for (int i = 0; i < list.size(); i++) {
            DeliveryOrderManager.DeliveryOrder o = list.get(i);
            KahluaTable row = platform.newTable();
            row.rawset("orderId", o.orderId);
            row.rawset("settlementId", o.settlementId);
            row.rawset("fullType", o.fullType);
            row.rawset("requestedCount", Double.valueOf(o.requestedCount));
            row.rawset("deliveredCount", Double.valueOf(o.deliveredCount));
            row.rawset("status", o.status.name());
            if (o.assignedNpcId != null) {
                row.rawset("assignedNpcId", o.assignedNpcId);
            }
            if (o.targetPickupOutContainerId != null) {
                row.rawset("targetPickupOutContainerId", o.targetPickupOutContainerId);
            }
            out.rawset(Double.valueOf(i + 1), row);
        }
        return out;
    }

    public static String getLogisticsSummary(String settlementId) {
        return LogisticsWorkDutyProvider.getDebugSummary(settlementId);
    }

    // ==================== 8. EMB-18 据点总控、容器配置向导与 3D 定位 ====================

    public static KahluaTable listSettlementContainers(String settlementId) {
        return WarehouseFastIndex.listSettlementContainers(settlementId);
    }

    public static String resolveObjectContainerId(IsoObject obj) {
        return WarehouseFastIndex.resolveObjectContainerId(obj);
    }

    public static KahluaTable getContainerRule(IsoObject obj) {
        return SmartZoningPolicy.ruleToKahluaTable(
                SmartZoningPolicy.readRule(obj),
                WarehouseFastIndex.isObjectPrivate(obj));
    }

    public static boolean setContainerRule(IsoObject obj, KahluaTable ruleTable) {
        if (obj == null || ruleTable == null) {
            return false;
        }
        SmartZoningPolicy.ContainerFilterRule rule = SmartZoningPolicy.kahluaTableToRule(ruleTable);
        boolean ok = SmartZoningPolicy.writeRule(obj, rule);
        Object priv = ruleTable.rawget("isPrivate");
        if (priv instanceof Boolean b) {
            WarehouseFastIndex.setObjectPrivate(obj, b.booleanValue());
        }
        WarehouseFastIndex.flushDirtyQueue();
        return ok;
    }

    public static KahluaTable getContainerRuleById(String settlementId, String containerId) {
        IsoObject obj = WarehouseFastIndex.resolveContainerObject(containerId);
        if (obj != null) {
            return getContainerRule(obj);
        }
        return null;
    }

    public static boolean setContainerRuleById(String settlementId, String containerId, KahluaTable ruleTable) {
        IsoObject obj = WarehouseFastIndex.resolveContainerObject(containerId);
        return setContainerRule(obj, ruleTable);
    }

    public static boolean setContainerPrivateById(String settlementId, String containerId, boolean makePrivate) {
        IsoObject obj = WarehouseFastIndex.resolveContainerObject(containerId);
        if (obj == null) {
            return false;
        }
        WarehouseFastIndex.setObjectPrivate(obj, makePrivate);
        WarehouseFastIndex.flushDirtyQueue();
        return true;
    }

    public static String autoConfigureContainerById(String settlementId, String containerId) {
        IsoObject obj = WarehouseFastIndex.resolveContainerObject(containerId);
        if (obj == null) {
            return "UNLOADED";
        }
        String res = autoConfigureContainer(obj);
        WarehouseFastIndex.flushDirtyQueue();
        return res;
    }

    public static KahluaTable getContainerSampleItems(IsoObject obj) {
        return WarehouseFastIndex.getContainerSampleItems(obj);
    }

    public static KahluaTable getContainerSampleItemsById(String settlementId, String containerId) {
        IsoObject obj = WarehouseFastIndex.resolveContainerObject(containerId);
        return WarehouseFastIndex.getContainerSampleItems(obj);
    }

    public static void setContainerHighlight(double x, double y, double z) {
        TerritoryRenderer.setContainerHighlight(
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public static void clearContainerHighlight() {
        TerritoryRenderer.clearContainerHighlight();
    }

    public static KahluaTable listNpcRoster() {
        List<NpcManager.NpcRosterEntry> entries = NpcManager.listRoster();
        J2SEPlatform platform = J2SEPlatform.getInstance();
        KahluaTable out = platform.newTable();
        for (int i = 0; i < entries.size(); i++) {
            NpcManager.NpcRosterEntry e = entries.get(i);
            KahluaTable row = platform.newTable();
            row.rawset("npcId", e.npcId());
            row.rawset("displayName", e.displayName());
            row.rawset("phasedIn", Boolean.valueOf(e.phasedIn()));
            row.rawset("dead", Boolean.valueOf(e.dead()));
            row.rawset("assignedSettlementId", e.assignedSettlementId());
            row.rawset("brainMode", e.brainMode());
            row.rawset("brainStatePath", e.brainStatePath());
            row.rawset("postureState", e.postureState());
            row.rawset("health", Double.valueOf(e.health()));
            row.rawset("hunger", Double.valueOf(e.hunger()));
            row.rawset("thirst", Double.valueOf(e.thirst()));
            row.rawset("fatigue", Double.valueOf(e.fatigue()));
            row.rawset("bleeding", Boolean.valueOf(e.bleeding()));
            row.rawset("x", Double.valueOf(e.x()));
            row.rawset("y", Double.valueOf(e.y()));
            row.rawset("z", Double.valueOf(e.z()));
            out.rawset(Double.valueOf(i + 1), row);
        }
        return out;
    }

    // ==================== 9. EMB-19 小队战术指挥、编队与载具协同 ====================

    public static KahluaTable getSquadTacticalConfig() {
        KahluaTable out = J2SEPlatform.getInstance().newTable();
        out.rawset("formation", SquadTacticalConfig.getFormation().name());
        out.rawset("spacing", SquadTacticalConfig.getSpacing().name());
        out.rawset("roe", SquadTacticalConfig.getDefaultRoe().name());
        out.rawset("cqb", Boolean.valueOf(SquadTacticalConfig.isDefaultCqb()));
        out.rawset("orderMode", SquadTacticalConfig.getOrderMode().name());
        out.rawset("autoVehicle", Boolean.valueOf(SquadTacticalConfig.isAutoVehicle()));
        out.rawset("vehicleOverride", SquadTacticalConfig.getVehicleOverride().name());
        out.rawset("version", Double.valueOf(SquadTacticalConfig.getVersion()));
        out.rawset("anyInVehicle", Boolean.valueOf(NpcManager.isAnySquadMemberInVehicle()));
        out.rawset("hasHoldAnchor", Boolean.valueOf(SquadTacticalConfig.hasHoldAnchor()));
        return out;
    }

    public static boolean setSquadFormation(String raw) {
        FormationType parsed = FormationType.fromString(raw, null);
        return parsed != null && SquadTacticalConfig.setFormation(parsed);
    }

    public static boolean setSquadSpacing(String raw) {
        FormationSpacing parsed = FormationSpacing.fromString(raw, null);
        return parsed != null && SquadTacticalConfig.setSpacing(parsed);
    }

    public static boolean setSquadRoe(String raw) {
        RulesOfEngagement parsed = RulesOfEngagement.fromString(raw, null);
        return parsed != null && SquadTacticalConfig.setDefaultRoe(parsed);
    }

    public static boolean setSquadCqb(boolean enabled) {
        return SquadTacticalConfig.setDefaultCqb(enabled);
    }

    public static boolean setSquadOrderMode(String raw, double playerIndex) {
        OrderMode parsed = OrderMode.fromString(raw, null);
        if (parsed == null) {
            return false;
        }
        if (parsed == OrderMode.HOLD_POSITION) {
            NpcManager.computeAndSetHoldAnchor((int) Math.round(playerIndex));
            return true;
        }
        if (SquadTacticalConfig.getVehicleOverride() == VehicleCommandMode.FORCE_EXIT) {
            SquadTacticalConfig.clearManualVehicleOverride();
        }
        return SquadTacticalConfig.setOrderMode(OrderMode.FOLLOW);
    }

    public static boolean setSquadAutoVehicle(boolean enabled) {
        return SquadTacticalConfig.setAutoVehicle(enabled);
    }

    public static boolean setSquadMemberRoe(String npcId, String rawOrNil) {
        if (npcId == null || npcId.isEmpty()) {
            return false;
        }
        if (rawOrNil == null || rawOrNil.isBlank() || "INHERIT".equalsIgnoreCase(rawOrNil.trim())) {
            SquadTacticalConfig.setMemberRoeOverride(npcId, null);
            return true;
        }
        RulesOfEngagement parsed = RulesOfEngagement.fromString(rawOrNil, null);
        if (parsed == null) {
            return false;
        }
        SquadTacticalConfig.setMemberRoeOverride(npcId, parsed);
        return true;
    }

    public static boolean setSquadMemberCqb(String npcId, Object boolOrNil) {
        if (npcId == null || npcId.isEmpty()) {
            return false;
        }
        if (boolOrNil == null) {
            SquadTacticalConfig.setMemberCqbOverride(npcId, null);
            return true;
        }
        if (boolOrNil instanceof Boolean b) {
            SquadTacticalConfig.setMemberCqbOverride(npcId, b);
            return true;
        }
        return false;
    }

    public static String commandSquadVehicleToggle(double playerIndex) {
        return NpcManager.commandSquadVehicleToggle((int) Math.round(playerIndex));
    }

    public static boolean commandMemberExitVehicle(String npcId) {
        return NpcManager.commandMemberExitVehicle(npcId);
    }

    public static String dismissSquadMember(String npcId) {
        return NpcManager.requestDismissFromSquad(npcId);
    }

    public static KahluaTable listSquadMembers() {
        List<NpcManager.SquadMemberView> list = NpcManager.listSquadMembers();
        J2SEPlatform platform = J2SEPlatform.getInstance();
        KahluaTable out = platform.newTable();
        for (int i = 0; i < list.size(); i++) {
            NpcManager.SquadMemberView m = list.get(i);
            KahluaTable row = platform.newTable();
            row.rawset("npcId", m.npcId());
            row.rawset("displayName", m.displayName());
            row.rawset("phasedIn", Boolean.valueOf(m.phasedIn()));
            row.rawset("health", Double.valueOf(m.health()));
            row.rawset("bleeding", Boolean.valueOf(m.bleeding()));
            row.rawset("injured", Boolean.valueOf(m.injured()));
            row.rawset("tacticalRole", m.tacticalRole());
            row.rawset("weaponType", m.weaponType());
            row.rawset("weaponName", m.weaponName());
            row.rawset("effectiveRoe", m.effectiveRoe());
            row.rawset("roeOverridden", Boolean.valueOf(m.roeOverridden()));
            row.rawset("effectiveCqb", Boolean.valueOf(m.effectiveCqb()));
            row.rawset("cqbOverridden", Boolean.valueOf(m.cqbOverridden()));
            row.rawset("activityState", m.activityState());
            row.rawset("inVehicle", Boolean.valueOf(m.inVehicle()));
            row.rawset("vehicleSeat", Double.valueOf(m.vehicleSeat()));
            row.rawset("pendingDismiss", Boolean.valueOf(m.pendingDismiss()));
            row.rawset("x", Double.valueOf(m.x()));
            row.rawset("y", Double.valueOf(m.y()));
            row.rawset("z", Double.valueOf(m.z()));
            out.rawset(Double.valueOf(i + 1), row);
        }
        return out;
    }

    public static Texture getNpcPortraitTexture(String npcId) {
        return NpcPortraitBakery.getPortraitTexture(npcId);
    }

    // ==================== 10. 战斗只读遥测与诊断端口 ====================

    public static boolean isCombatDiagEnabled() {
        return CombatDiagLog.isEnabled();
    }

    public static boolean setCombatDiagEnabled(boolean enabled) {
        return CombatDiagLog.setEnabled(enabled);
    }

    public static KahluaTable getCombatDebugSnapshot() {
        J2SEPlatform platform = J2SEPlatform.getInstance();
        KahluaTable out = platform.newTable();
        EmberSquadDirector dir = EmberSquadDirector.getInstance();
        out.rawset("combatDiagEnabled", Boolean.valueOf(CombatDiagLog.isEnabled()));
        out.rawset("allAnimRecEnabled", Boolean.valueOf(NpcManager.isAllNpcAnimRecorderEnabled()));
        out.rawset("threatCount", Double.valueOf(dir.getActiveThreatCount()));
        out.rawset("clusterCount", Double.valueOf(dir.getActiveClusterCount()));
        out.rawset("corridorCount", Double.valueOf(dir.getActiveCorridorCount()));

        List<CombatDiagLog.NpcCombatDiagStatus> rows = CombatDiagLog.snapshotNpcStatuses();
        KahluaTable npcs = platform.newTable();
        for (int i = 0; i < rows.size(); i++) {
            CombatDiagLog.NpcCombatDiagStatus s = rows.get(i);
            KahluaTable row = platform.newTable();
            row.rawset("npcId", s.npcId);
            row.rawset("state", s.state);
            row.rawset("activity", s.activity);
            row.rawset("targetId", Double.valueOf(s.targetId));
            row.rawset("targetPosture", s.targetPosture);
            row.rawset("dist", Double.valueOf(s.dist));
            row.rawset("minRange", Double.valueOf(s.minRange));
            row.rawset("maxRange", Double.valueOf(s.maxRange));
            row.rawset("aimFloor", Boolean.valueOf(s.aimFloor));
            row.rawset("stomp", Boolean.valueOf(s.stomp));
            row.rawset("radialMode", s.radialMode);
            npcs.rawset(Double.valueOf(i + 1), row);
        }
        out.rawset("npcs", npcs);
        return out;
    }

    public static String dumpCombatDebugSnapshot() {
        EmberSquadDirector dir = EmberSquadDirector.getInstance();
        int threats = dir.getActiveThreatCount();
        int clusters = dir.getActiveClusterCount();
        int corridors = dir.getActiveCorridorCount();
        List<CombatDiagLog.NpcCombatDiagStatus> rows = CombatDiagLog.snapshotNpcStatuses();
        String summary = String.format(
                java.util.Locale.ROOT,
                "diag=%s rec=%s threats=%d clusters=%d corridors=%d npcs=%d",
                CombatDiagLog.isEnabled(),
                NpcManager.isAllNpcAnimRecorderEnabled(),
                threats,
                clusters,
                corridors,
                rows.size());
        System.out.println("[EmberRoutes][COMBAT_SNAPSHOT] " + summary);
        for (int i = 0; i < rows.size(); i++) {
            CombatDiagLog.NpcCombatDiagStatus s = rows.get(i);
            System.out.println(String.format(
                    java.util.Locale.ROOT,
                    "[EmberRoutes][COMBAT_SNAPSHOT]   %s state=%s act=%s tgt=%d(%s) dist=%.2f [%.2f..%.2f] rad=%s flr=%s stp=%s",
                    s.npcId,
                    s.state,
                    s.activity,
                    s.targetId,
                    s.targetPosture,
                    s.dist,
                    s.minRange,
                    s.maxRange,
                    s.radialMode,
                    s.aimFloor,
                    s.stomp));
        }
        return summary;
    }
}
