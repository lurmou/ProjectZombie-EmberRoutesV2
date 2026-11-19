import emberroutes.EmberRoutesBridge;
import emberroutes.npc.NpcManager;
import emberroutes.npc.NpcSnapshot;
import emberroutes.warehouse.ContainerDirtyQueue;
import emberroutes.warehouse.TerritoryContour;
import emberroutes.warehouse.WarehouseFastIndex;
import emberroutes.warehouse.WarehouseFingerprint;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import me.zed_0xff.zombie_buddy.Patch;
import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;
import zombie.GameWindow;
import zombie.Lua.LuaManager;
import zombie.core.Core;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.Food;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoStove;
import zombie.network.GameServer;
import zombie.scripting.objects.Item;

/**
 * 脱机 Kahlua2 + B42 原版实例 + AE2 契约回归检查：
 * 1. 全量编译校验所有模组 .lua 文件（同时覆盖 Core.debug = false 与 Core.debug = true 双模式符号表防溢出）；
 * 2. 验证 ContainerDirtyQueue 有符号负坐标（含地下室 Z=-2）打包解压与零分配 drain；
 * 3. 验证 WarehouseFingerprint 槽位顺序敏感性、重量量化与原位状态哈希（腐烂/次数/耐久/液量变化检出）；
 * 4. 在真实 Kahlua VM 中验证 SettlementStore 增扩裁删与不规则轮廓提取；
 * 5. 用真实 B42 IsoGridSquare + IsoObject + Food + DrainableComboItem 验证：
 *    - 非储物设施（IsoStove / bin）自动排除；
 *    - 单箱私密开关 ModData.ER_Private 零空表污染（未设私密时 hasModData() == false）；
 *    - 腐烂/耗尽两级健康度统计（KeyCounter usable vs degraded）；
 *    - 区块卸载 OFFLINE_CACHED 保留最后快照不归零 + 停掉节拍器 + 重回区块无缝复核；
 *    - 懒惰快照物化（变动时仅递增 revision，调用 getSnapshot 才编译缓存）；
 * 6. 核验全部 21 个 @Patch 补丁类位于根包 emberroutes 且目标方法在当前 projectzomboid.jar 真实存在。
 */
public final class VerifyModule1 {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("VERIFY FAILED: " + msg);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Path luaRoot = root.resolve("Contents/mods/EmberRoutes/42/media/lua");

        J2SEPlatform platform = J2SEPlatform.getInstance();
        LuaManager.platform = platform;
        KahluaTable env = platform.newEnvironment();
        LuaManager.env = env;

        // 1. 真实 Kahlua 编译器双模式核验（Core.debug=false 与 Core.debug=true）
        List<Path> luaFiles = new ArrayList<>();
        try (var stream = Files.walk(luaRoot)) {
            stream.filter(p -> p.toString().endsWith(".lua")).sorted().forEach(luaFiles::add);
        }
        check(luaFiles.size() >= 7, "Expected >= 7 lua files, got " + luaFiles.size());
        boolean prevDebug = Core.debug;
        try {
            for (boolean dbg : new boolean[]{false, true}) {
                Core.debug = dbg;
                for (Path lf : luaFiles) {
                    String src = Files.readString(lf, StandardCharsets.UTF_8);
                    LuaClosure closure = LuaCompiler.loadstring(src, lf.getFileName().toString(), env);
                    check(closure != null, "Failed to compile " + lf + " (debug=" + dbg + ")");
                }
            }
        } finally {
            Core.debug = prevDebug;
        }
        System.out.println("[VerifyModule1] 1/7 All " + luaFiles.size() + " Lua files compiled cleanly in Kahlua2 (normal + debug).");

        // 2. ContainerDirtyQueue 坐标位打包与零分配排空测试（含负坐标与地下室 Z=-2）
        ContainerDirtyQueue.reset();
        check(!ContainerDirtyQueue.hasDirty(), "Queue initially empty");
        AtomicInteger drainedCount = new AtomicInteger();
        check(ContainerDirtyQueue.drain(v -> drainedCount.incrementAndGet()) == 0, "Idle drain must return 0");

        int[][] testCoords = { {0, 0, 0}, {10542, 9811, 2}, {-350, -1204, -2}, {8388600, -8388600, 7} };
        for (int[] c : testCoords) {
            long p = ContainerDirtyQueue.packCoord(c[0], c[1], c[2]);
            check(ContainerDirtyQueue.unpackX(p) == c[0], "unpackX mismatch for " + c[0]);
            check(ContainerDirtyQueue.unpackY(p) == c[1], "unpackY mismatch for " + c[1]);
            check(ContainerDirtyQueue.unpackZ(p) == c[2], "unpackZ mismatch for " + c[2]);
        }
        System.out.println("[VerifyModule1] 2/7 ContainerDirtyQueue signed coordinate packing & zero-alloc drain passed.");

        // 3. WarehouseFingerprint 槽位敏感性、浮点量化与原位状态哈希校验
        WarehouseFingerprint fp1 = new WarehouseFingerprint(new int[]{101, 202, 303}, 12.341, 999L);
        WarehouseFingerprint fp2 = new WarehouseFingerprint(new int[]{101, 202, 303}, 12.344, 999L);
        WarehouseFingerprint fpSwapped = new WarehouseFingerprint(new int[]{101, 303, 202}, 12.341, 999L);
        WarehouseFingerprint fpStateDiff = new WarehouseFingerprint(new int[]{101, 202, 303}, 12.341, 1000L);
        check(fp1.matches(fp2), "Quantized 0.01kg weight should match");
        check(!fp1.matches(fpSwapped), "Swapped middle slot must be detected");
        check(!fp1.matches(fpStateDiff), "In-place state change (uses/rotten/fluid/condition) must be detected");
        System.out.println("[VerifyModule1] 3/7 WarehouseFingerprint slot identity, weight quantization & state hash passed.");

        // 4. 在真实 Kahlua VM 中执行 SettlementGeometry + SettlementStore + Java WarehouseFastIndex 闭环测试
        {
            EmberRoutesBridge.reset();
            KahluaConverterManager conv = new KahluaConverterManager();
            KahluaThread thread = new KahluaThread(platform, env);
            thread.debugOwnerThread = Thread.currentThread();
            LuaCaller caller = new LuaCaller(conv);

            KahluaTable loaded = platform.newTable();
            env.rawset("require", (JavaFunction) (callFrame, nArguments) -> {
                String modName = (String) callFrame.get(0);
                Object cached = loaded.rawget(modName);
                if (cached != null) {
                    callFrame.push(cached);
                    return 1;
                }
                if ("EmberUI".equals(modName) || modName.startsWith("EmberUI/")) {
                    KahluaTable stub = platform.newTable();
                    KahluaTable themeStub = platform.newTable();
                    themeStub.rawset("registerModule", (JavaFunction) (cf, n) -> 0);
                    themeStub.rawset("getModuleColor", (JavaFunction) (cf, n) -> {
                        KahluaTable col = platform.newTable();
                        col.rawset("r", 1.0); col.rawset("g", 1.0); col.rawset("b", 1.0); col.rawset("a", 1.0);
                        cf.push(col);
                        return 1;
                    });
                    themeStub.rawset("panel", platform.newTable());
                    stub.rawset("UI", platform.newTable());
                    stub.rawset("Theme", themeStub);
                    stub.rawset("Chrome", platform.newTable());
                    stub.rawset("WindowHost", platform.newTable());
                    stub.rawset("PageLifecycle", platform.newTable());
                    stub.rawset("openThemeSettings", (JavaFunction) (cf, n) -> 0);
                    loaded.rawset(modName, stub);
                    callFrame.push(stub);
                    return 1;
                }
                Path luaFile = luaRoot.resolve("shared").resolve(modName + ".lua");
                if (!Files.isRegularFile(luaFile)) {
                    luaFile = luaRoot.resolve("client").resolve(modName + ".lua");
                }
                try {
                    String code = Files.readString(luaFile, StandardCharsets.UTF_8);
                    LuaClosure fn = LuaCompiler.loadstring(code, modName, env);
                    LuaReturn ret = caller.protectedCall(thread, fn);
                    if (!ret.isSuccess()) throw new RuntimeException("require failed: " + ret.getErrorString());
                    Object val = ret.isEmpty() ? Boolean.TRUE : ret.getFirst();
                    loaded.rawset(modName, val);
                    callFrame.push(val);
                    return 1;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            KahluaTable modDataStore = platform.newTable();
            KahluaTable modDataApi = platform.newTable();
            modDataApi.rawset("getOrCreate", (JavaFunction) (cf, n) -> {
                String k = (String) cf.get(0);
                Object t = modDataStore.rawget(k);
                if (t == null) {
                    t = platform.newTable();
                    modDataStore.rawset(k, t);
                }
                cf.push(t);
                return 1;
            });
            env.rawset("ModData", modDataApi);

            AtomicInteger resetCount = new AtomicInteger();
            AtomicInteger warehouseResetCount = new AtomicInteger();
            AtomicInteger npcResetCount = new AtomicInteger();
            AtomicInteger loadNpcCount = new AtomicInteger();
            AtomicInteger mockMoveSeq = new AtomicInteger(0);
            java.util.List<KahluaTable> mockMoveEventQueue = new java.util.ArrayList<>();
            java.util.Map<String, KahluaTable> mockMoveStates = new java.util.HashMap<>();
            KahluaTable javaBridge = platform.newTable();
            javaBridge.rawset("reset", (JavaFunction) (cf, n) -> {
                resetCount.incrementAndGet();
                mockMoveStates.clear();
                mockMoveEventQueue.clear();
                EmberRoutesBridge.reset();
                return 0;
            });
            javaBridge.rawset("resetWarehouse", (JavaFunction) (cf, n) -> {
                warehouseResetCount.incrementAndGet();
                EmberRoutesBridge.resetWarehouse();
                return 0;
            });
            javaBridge.rawset("resetNpcs", (JavaFunction) (cf, n) -> {
                npcResetCount.incrementAndGet();
                mockMoveStates.clear();
                mockMoveEventQueue.clear();
                EmberRoutesBridge.resetNpcs();
                return 0;
            });
            javaBridge.rawset("diagnosticTimeNanos", (JavaFunction) (cf, n) -> {
                cf.push(Double.valueOf((double) System.nanoTime()));
                return 1;
            });
            javaBridge.rawset("syncSettlementAreas", (JavaFunction) (cf, n) -> {
                String sid = (String) cf.get(0);
                KahluaTable flat = (KahluaTable) cf.get(1);
                double rev = EmberRoutesBridge.syncSettlementAreas(sid, flat);
                cf.push(Double.valueOf(rev));
                return 1;
            });
            javaBridge.rawset("invalidateSettlementWarehouse", (JavaFunction) (cf, n) -> {
                EmberRoutesBridge.invalidateSettlementWarehouse((String) cf.get(0));
                return 0;
            });
            javaBridge.rawset("getResetCount", (JavaFunction) (cf, n) -> {
                cf.push(Double.valueOf(resetCount.get()));
                return 1;
            });
            javaBridge.rawset("getWarehouseResetCount", (JavaFunction) (cf, n) -> {
                cf.push(Double.valueOf(warehouseResetCount.get()));
                return 1;
            });
            javaBridge.rawset("getNpcResetCount", (JavaFunction) (cf, n) -> {
                cf.push(Double.valueOf(npcResetCount.get()));
                return 1;
            });
            javaBridge.rawset("getLoadNpcCount", (JavaFunction) (cf, n) -> {
                cf.push(Double.valueOf(loadNpcCount.get()));
                return 1;
            });
            javaBridge.rawset("tickWarehouse", (JavaFunction) (cf, n) -> {
                cf.push(Boolean.valueOf(EmberRoutesBridge.tickWarehouse()));
                return 1;
            });
            javaBridge.rawset("flushDirtyContainers", (JavaFunction) (cf, n) -> {
                cf.push(Boolean.valueOf(EmberRoutesBridge.flushDirtyContainers()));
                return 1;
            });
            javaBridge.rawset("loadNpcsFromSave", (JavaFunction) (cf, n) -> {
                loadNpcCount.incrementAndGet();
                cf.push(Double.valueOf(0.0));
                return 1;
            });
            javaBridge.rawset("tickNpcs", (JavaFunction) (cf, n) -> 0);
            javaBridge.rawset("moveNpcTo", (JavaFunction) (cf, n) -> {
                String npcId = (String) cf.get(0);
                double seq = mockMoveSeq.incrementAndGet();
                KahluaTable st = platform.newTable();
                st.rawset("exists", Boolean.TRUE);
                st.rawset("status", "MOVING");
                st.rawset("reason", "MOVING");
                st.rawset("seq", Double.valueOf(seq));
                st.rawset("settledSeq", Double.valueOf(0.0));
                st.rawset("moving", Boolean.TRUE);
                mockMoveStates.put(npcId, st);
                cf.push(Double.valueOf(seq));
                return 1;
            });
            javaBridge.rawset("stopNpcMove", (JavaFunction) (cf, n) -> {
                String npcId = (String) cf.get(0);
                KahluaTable st = mockMoveStates.get(npcId);
                if (st != null) {
                    st.rawset("status", "INTERRUPTED");
                    st.rawset("reason", "STOPPED");
                    st.rawset("settledSeq", st.rawget("seq"));
                    st.rawset("moving", Boolean.FALSE);
                }
                cf.push(Boolean.TRUE);
                return 1;
            });
            javaBridge.rawset("setMockNpcSettled", (JavaFunction) (cf, n) -> {
                String npcId = (String) cf.get(0);
                String status = (String) cf.get(1);
                String reason = (String) cf.get(2);
                KahluaTable st = mockMoveStates.get(npcId);
                if (st != null) {
                    st.rawset("status", status);
                    st.rawset("reason", reason);
                    st.rawset("settledSeq", st.rawget("seq"));
                    st.rawset("moving", Boolean.FALSE);
                    KahluaTable ev = platform.newTable();
                    ev.rawset("npcId", npcId);
                    ev.rawset("seq", st.rawget("seq"));
                    ev.rawset("status", status);
                    ev.rawset("reason", reason);
                    mockMoveEventQueue.add(ev);
                }
                return 0;
            });
            javaBridge.rawset("drainNpcMoveEvents", (JavaFunction) (cf, n) -> {
                if (mockMoveEventQueue.isEmpty()) {
                    cf.push(null);
                    return 1;
                }
                KahluaTable out = platform.newTable();
                for (int i = 0; i < mockMoveEventQueue.size(); i++) {
                    out.rawset(Double.valueOf(i + 1), mockMoveEventQueue.get(i));
                }
                mockMoveEventQueue.clear();
                cf.push(out);
                return 1;
            });
            javaBridge.rawset("getNpcMoveStatus", (JavaFunction) (cf, n) -> {
                String npcId = (String) cf.get(0);
                KahluaTable st = mockMoveStates.get(npcId);
                if (st == null) {
                    st = platform.newTable();
                    st.rawset("exists", Boolean.FALSE);
                    st.rawset("status", "MISSING");
                    st.rawset("reason", "MISSING");
                    st.rawset("seq", Double.valueOf(0.0));
                    st.rawset("settledSeq", Double.valueOf(0.0));
                    st.rawset("moving", Boolean.FALSE);
                }
                cf.push(st);
                return 1;
            });
            env.rawset("EmberRoutesJava", javaBridge);
            env.rawset("next", null);

            String testLua = """
                local haloCalls = {}
                HaloTextHelper = {
                    addText = function(chr, txt) haloCalls[#haloCalls + 1] = { m = "addText", c = chr, t = txt } end,
                    addTextWithArrow = function(chr, txt) haloCalls[#haloCalls + 1] = { m = "addTextWithArrow", c = chr, t = txt } end,
                    addGoodText = function(chr, txt) haloCalls[#haloCalls + 1] = { m = "addGoodText", c = chr, t = txt } end,
                    addBadText = function(chr, txt) haloCalls[#haloCalls + 1] = { m = "addBadText", c = chr, t = txt } end,
                }
                local function makeEvent()
                    local ev = { _handlers = {} }
                    function ev.Add(fn) ev._handlers[#ev._handlers + 1] = fn end
                    function ev.Remove(fn)
                        for i = #ev._handlers, 1, -1 do
                            if ev._handlers[i] == fn then table.remove(ev._handlers, i) end
                        end
                    end
                    function ev.fire(...)
                        for i = 1, #ev._handlers do ev._handlers[i](...) end
                    end
                    return ev
                end
                Events = {
                    OnInitGlobalModData = makeEvent(),
                    OnGameStart = makeEvent(),
                    OnCreatePlayer = makeEvent(),
                    OnPreMapLoad = makeEvent(),
                    OnMainMenuEnter = makeEvent(),
                    OnTick = makeEvent(),
                    OnTickEvenPaused = makeEvent(),
                    LoadGridsquare = makeEvent(),
                    ReuseGridsquare = makeEvent(),
                    OnFillWorldObjectContextMenu = makeEvent(),
                }
                local mockSimDt = 0.25
                getGameTime = function()
                    return {
                        getGameWorldSecondsSinceLastUpdate = function() return mockSimDt end,
                    }
                end

                local Kernel = require "EmberRoutes/Kernel"
                local Store = require "EmberRoutes/Settlement/SettlementStore"
                local Geo = require "EmberRoutes/Settlement/SettlementGeometry"
                local NpcLoco = require "EmberRoutes/Npc/NpcLocomotionPort"
                require "EmberRoutes/Boot"

                -- Test standalone Scope before session boot (required by offline preview-ui.ps1)
                local preBootClosed = false
                local standaloneSc = Kernel.scope("preview-standalone")
                standaloneSc:defer(function() preBootClosed = true end)
                standaloneSc:dispose()
                assert(preBootClosed == true, "standalone scope must work before world ready")

                -- Test SettlementStore.onChanged idempotent dispose & snapshot iteration
                local storeEvents = 0
                local unsubSelf
                unsubSelf = Store.onChanged(function()
                    storeEvents = storeEvents + 1
                    unsubSelf()
                    unsubSelf()
                end)
                local secondCalls = 0
                local unsubSecond = Store.onChanged(function()
                    secondCalls = secondCalls + 1
                end)

                local s1, err = Store.createSettlement("Alpha", 100, 200, 103, 206)
                assert(s1 ~= nil and err == nil, "createSettlement failed")
                assert(storeEvents == 1 and secondCalls == 1, "both store listeners must fire without skipping")
                assert(Geo.totalTiles(s1.areas) == 28, "expected exact 28 tiles")

                local sOverlap, err2 = Store.createSettlement("Beta", 102, 205, 110, 210)
                assert(sOverlap == nil and err2 == "TERRITORY_OVERLAP", "overlap should reject")
                local conflict = Store.findOverlappingSettlement({ Geo.rectangle(102, 205, 110, 210) }, nil)
                assert(conflict ~= nil and conflict.id == s1.id, "findOverlappingSettlement must return s1")
                assert(Store.findOverlappingSettlement({ Geo.rectangle(102, 205, 110, 210) }, s1.id) == nil, "ignoreId must ignore self")
                assert(Store.findOverlappingSettlement({ Geo.rectangle(500, 500, 505, 505) }, nil) == nil, "disjoint area must return nil")

                local exp, expErr = Store.expandTerritory(s1.id, 104, 200, 107, 202)
                assert(exp ~= nil and Geo.totalTiles(exp.areas) == 40, "expanded tiles should be 40")
                assert(storeEvents == 1 and secondCalls == 2, "unsubscribed listener must not fire again")
                unsubSecond()
                assert(#Geo.extractOutlineSegments(exp.areas) == 6, "L-shape must have exact 6 outer segments with 0 inner seams")

                local trimmed, trimErr = Store.trimTerritory(s1.id, 100, 200, 101, 206)
                assert(trimmed ~= nil and Geo.totalTiles(trimmed.areas) == 26, "trimmed tiles should be 26")
                assert(#Geo.extractOutlineSegments(trimmed.areas) == 6, "trimmed L-shape must have 6 outer segments")

                local base10 = { Geo.rectangle(0, 0, 9, 9) }
                local donut = Geo.subtractFromList(base10, Geo.rectangle(3, 3, 6, 6))
                assert(Geo.totalTiles(donut) == 84, "donut must have 84 tiles")
                assert(#Geo.extractOutlineSegments(donut) == 8, "donut must have exact 8 contour segments")

                assert(Store.deleteSettlement(s1.id) == true, "deleteSettlement failed")
                assert(#Store.list() == 0, "store should be empty")

                -- Test Staged Kernel: ModData vs WorldReady, 5-stage deterministic pipeline, dual clock, fault isolation, Compat/Addon isolation
                local stageLog, cleanLog, playerCreatedLog, addonRan = {}, {}, {}, false
                Kernel.registerModule({
                    id = "test-stage-probe",
                    onModDataReady = function()
                        stageLog[#stageLog + 1] = "moddata"
                    end,
                    onWorldReady = function(scope)
                        stageLog[#stageLog + 1] = "world"
                        scope:effect(function()
                            return function() cleanLog[#cleanLog + 1] = "c1" end
                        end)
                        scope:effect(function()
                            return function()
                                cleanLog[#cleanLog + 1] = "c2-throws"
                                error("simulated cleanup failure")
                            end
                        end)
                    end,
                    onPlayerCreated = function(idx, chr)
                        playerCreatedLog[#playerCreatedLog + 1] = tostring(idx) .. ":" .. tostring(chr.tag)
                    end,
                    ticks = {
                        { stage = "MACRO_WORLD", fn = function(ft, dt, st) stageLog[#stageLog + 1] = "MACRO:" .. tostring(ft) .. ":" .. string.format("%.2f", st) end },
                        { stage = "SOFT_LOCKS", fn = function() stageLog[#stageLog + 1] = "SOFT_THROWS"; error("boom-in-soft") end },
                        { stage = "AGENT_BRAIN", fn = function(ft, dt, st) stageLog[#stageLog + 1] = "BRAIN:" .. string.format("%.2f", dt) end },
                    },
                })
                Kernel.registerCompat({
                    id = "test-broken-compat",
                    when = function() return true end,
                    apply = function() error("isolated compat boom") end,
                })
                EmberRoutes.registerAddon({
                    id = "test-addon",
                    apply = function(scope, api)
                        assert(api and api.settlements == Store and api.npcLocomotion == NpcLoco, "addon must receive public EmberRoutes.api")
                        addonRan = true
                    end,
                })

                -- Before boot: 1 single OnTick + 1 OnTickEvenPaused + 0 LoadGridsquare/ReuseGridsquare Lua hooks
                assert(not Kernel.isModDataReady() and not Kernel.isWorldReady(), "Kernel must not boot on require")
                assert(#Events.OnTick._handlers == 1, "Must register exact 1 single-entry OnTick handler")
                assert(#Events.OnTickEvenPaused._handlers == 1, "Must register exact 1 OnTickEvenPaused handler")
                assert(#Events.LoadGridsquare._handlers == 0 and #Events.ReuseGridsquare._handlers == 0,
                    "LoadGridsquare/ReuseGridsquare must stay in Java Patch_IsoChunk with 0 Lua hooks")

                -- Phase 1: OnInitGlobalModData (background loader thread) must NOT trigger world mount or NPC load
                Events.OnInitGlobalModData.fire(false)
                assert(Kernel.isModDataReady() and not Kernel.isWorldReady(), "OnInitGlobalModData must only boot moddata phase")
                assert(EmberRoutesJava.getWarehouseResetCount() == 0 and EmberRoutesJava.getLoadNpcCount() == 0,
                    "World mount & NPC load must wait until OnGameStart")
                assert(stageLog[1] == "moddata" and #stageLog == 1, "Only onModDataReady should run on OnInitGlobalModData")

                -- Phase 2: OnGameStart (main thread) runs onWorldReady exactly once; warehouse rescan NEVER resets NPCs
                Events.OnGameStart.fire()
                assert(Kernel.isWorldReady(), "OnGameStart must activate world phase")
                assert(addonRan == true, "Addon must activate after core onWorldReady")
                assert(EmberRoutesJava.getWarehouseResetCount() == 1, "Warehouse must mount exactly once on OnGameStart")
                assert(EmberRoutesJava.getResetCount() == 0 and EmberRoutesJava.getNpcResetCount() == 0,
                    "Warehouse mount must NOT call global reset() or resetNpcs()")
                assert(EmberRoutesJava.getLoadNpcCount() == 1, "NPC load must run exactly once on OnGameStart")
                assert(#Events.OnFillWorldObjectContextMenu._handlers == 1, "DebugContextMenu must attach 1 menu handler")

                -- Idempotent OnGameStart + warehouse rescan isolation check
                Events.OnGameStart.fire()
                assert(EmberRoutesJava.getWarehouseResetCount() == 1 and EmberRoutesJava.getLoadNpcCount() == 1,
                    "Repeated OnGameStart must remain idempotent")
                Store.reconcileAndMountAll()
                assert(EmberRoutesJava.getWarehouseResetCount() == 2 and EmberRoutesJava.getResetCount() == 0 and EmberRoutesJava.getNpcResetCount() == 0,
                    "Warehouse rescan must only call resetWarehouse() and never wipe NPCs")

                -- Same-save respawn OnCreatePlayer dispatch
                Events.OnCreatePlayer.fire(0, { tag = "RespawnedHero" })
                assert(#playerCreatedLog == 1 and playerCreatedLog[1] == "0:RespawnedHero", "OnCreatePlayer must dispatch to modules")

                -- Single-entry 5-stage deterministic pipeline + fault isolation + dual clock
                stageLog = {}
                Events.OnTick.fire(10)
                assert(stageLog[1] == "SOFT_THROWS" and stageLog[2] == "BRAIN:0.25" and stageLog[3] == "MACRO:10:0.25",
                    "5-stage order, fault isolation & dual clock failed: " .. tostring(stageLog[1]) .. "," .. tostring(stageLog[2]) .. "," .. tostring(stageLog[3]))
                Events.OnTickEvenPaused.fire(0)

                -- M2 HaloTextGuard test: suppress non-local player overhead text, allow local player
                local npcChar = { isLocalPlayer = function() return false end }
                local localChar = { isLocalPlayer = function() return true end }
                HaloTextHelper.addText(npcChar, "npc-xp")
                HaloTextHelper.addTextWithArrow(npcChar, "npc-arrow")
                HaloTextHelper.addGoodText(npcChar, "npc-good")
                HaloTextHelper.addBadText(npcChar, "npc-bad")
                assert(#haloCalls == 0, "HaloTextHelper must suppress non-local NPC calls")
                HaloTextHelper.addText(localChar, "player-xp")
                HaloTextHelper.addTextWithArrow(localChar, "player-arrow")
                assert(#haloCalls == 2, "HaloTextHelper must allow local player calls")

                -- M2 NpcLocomotionPort batch-drain contract test: zero-alloc nil when idle -> batch fire on settle
                local arrivalLog = {}
                local unsubPersist = NpcLoco.onArrival("npc_1", function(id, status, reason)
                    arrivalLog[#arrivalLog + 1] = "persist:" .. id .. ":" .. status .. ":" .. tostring(reason)
                end)
                NpcLoco.moveTo("npc_1", 10.5, 20.5, 0, {
                    run = true,
                    onArrival = function(id, status, reason)
                        arrivalLog[#arrivalLog + 1] = "oneshot:" .. id .. ":" .. status .. ":" .. tostring(reason)
                    end,
                })
                Events.OnTick.fire(11)
                assert(#arrivalLog == 0, "callback must wait until batch move event arrives")
                EmberRoutesJava.setMockNpcSettled("npc_1", "ARRIVED", "REACHED")
                Events.OnTick.fire(12)
                assert(#arrivalLog == 2, "both oneshot & persistent arrival callbacks must fire once")
                assert(arrivalLog[1] == "oneshot:npc_1:ARRIVED:REACHED", "unexpected oneshot log")
                assert(arrivalLog[2] == "persist:npc_1:ARRIVED:REACHED", "unexpected persistent log")
                Events.OnTick.fire(13)
                assert(#arrivalLog == 2, "arrival callbacks must not refire on later ticks")
                unsubPersist()

                local stopLog = {}
                NpcLoco.moveTo("npc_2", 30, 40, 0, {
                    onArrival = function(id, status, reason)
                        stopLog[#stopLog + 1] = id .. ":" .. status .. ":" .. tostring(reason)
                    end,
                })
                NpcLoco.stop("npc_2")
                assert(#stopLog == 1 and stopLog[1] == "npc_2:INTERRUPTED:STOPPED", "stop must settle with INTERRUPTED")

                -- Ref-counted acquireGlobalFlag (under Kahlua2 where global `next` is nil)
                assert(next == nil, "sandbox must mirror B42 Kahlua2 where global `next` is nil")
                local flagTbl = { disableWorldMenu = "externalModValue" }
                local scA = Kernel.scope("A")
                local scB = Kernel.scope("B")
                local cleanupErrs = {}
                local origPrint = print
                print = function(msg)
                    if tostring(msg):find("cleanup error", 1, true) then
                        cleanupErrs[#cleanupErrs + 1] = tostring(msg)
                    end
                    return origPrint(msg)
                end
                scA:acquireGlobalFlag(flagTbl, "disableWorldMenu", true)
                assert(flagTbl.disableWorldMenu == true, "scA must set flag")
                scB:acquireGlobalFlag(flagTbl, "disableWorldMenu", true)
                scA:dispose()
                assert(flagTbl.disableWorldMenu == true, "flag must stay true while scB holds ref")
                scB:dispose()
                print = origPrint
                assert(flagTbl.disableWorldMenu == "externalModValue", "flag must restore original value on zero refs")
                assert(#cleanupErrs == 0, "acquireGlobalFlag dispose must produce zero cleanup errors without `next`")

                -- Safe ctx:wrap with secondary mod wrap bypass + direct restore
                local wrapHost = {
                    calc = function(x) return x + 1 end,
                    plain = function(x) return x * 2 end,
                }
                local scW = Kernel.scope("Wrap")
                scW:wrap(wrapHost, "calc", function(base)
                    return function(x) return base(x) + 10 end
                end)
                scW:wrap(wrapHost, "plain", function(base)
                    return function(x) return base(x) + 5 end
                end)
                local origCalc = wrapHost.calc
                wrapHost.calc = function(x) return origCalc(x) * 3 end
                assert(wrapHost.calc(2) == 39, "wrapped + secondary mod must compute (2+1+10)*3")
                assert(wrapHost.plain(4) == 13, "plain wrapped must compute 4*2+5")
                local savedBefore = EmberRoutesJava.getResetCount()
                Events.OnMainMenuEnter.fire()
                assert(not Kernel.isWorldReady() and not Kernel.isModDataReady(), "OnMainMenuEnter must tear down Kernel")
                assert(#Events.OnFillWorldObjectContextMenu._handlers == 0, "teardown must unbind session-scoped menu handler")
                assert(cleanLog[1] == "c2-throws" and cleanLog[2] == "c1", "effects must dispose LIFO despite cleanup error")
                assert(wrapHost.calc(2) == 9, "secondary mod must bypass disabled wrapper and call base -> (2+1)*3")
                assert(wrapHost.plain(4) == 8, "uncontested wrapper must restore exact original function")
                assert(EmberRoutesJava.getResetCount() > savedBefore, "teardown must reset Java bridge")
                HaloTextHelper.addText(npcChar, "restored-after-teardown")
                assert(#haloCalls == 3, "HaloTextGuard must cleanly unwrap on teardown")
                """;

            LuaClosure testFn = LuaCompiler.loadstring(testLua, "VerifyModule1Test.lua", env);
            LuaReturn res = caller.protectedCall(thread, testFn);
            if (!res.isSuccess()) {
                if (res.getJavaException() != null) res.getJavaException().printStackTrace();
                throw new AssertionError("Lua lifecycle test failed: " + res.getErrorString() + "\n" + res.getLuaStackTrace());
            }
            check(WarehouseFastIndex.findSettlementIdAt(102, 202) == null, "Java spatial index must be cleared after delete");
        }

        // 5. 真实 B42 IsoGridSquare + IsoObject + AE2 仓储语义端到端校验
        {
            EmberRoutesBridge.reset();
            KahluaTable flat = platform.newTable();
            flat.rawset(1.0, 10.0);
            flat.rawset(2.0, 20.0);
            flat.rawset(3.0, 5.0);
            flat.rawset(4.0, 5.0);
            EmberRoutesBridge.syncSettlementAreas("S_AE2", flat);

            IsoGridSquare sq = IsoGridSquare.getNew(null, null, 12, 22, -1); // 地下室 Z=-1

            // (a) 合法储物木箱 #1（装 1 个新鲜苹果 + 1 个腐烂苹果 + 1 卷满胶带）
            IsoObject crate1 = new IsoObject((IsoCell) null);
            crate1.setSquare(sq);
            ItemContainer ic1 = new ItemContainer("crate", sq, crate1);
            ic1.setCapacity(50);
            crate1.setContainer(ic1);

            Item appleScript = new Item();
            Food freshApple = new Food("Base", "Apple", "Apple", appleScript);
            freshApple.setScriptItem(appleScript);
            freshApple.id = 1001;
            freshApple.setOffAge(5);
            freshApple.setOffAgeMax(8);
            freshApple.setAge(1.0f);
            ic1.getItems().add(freshApple);

            Food rottenApple = new Food("Base", "Apple", "Apple", appleScript);
            rottenApple.setScriptItem(appleScript);
            rottenApple.id = 1002;
            rottenApple.setOffAge(5);
            rottenApple.setOffAgeMax(8);
            rottenApple.setAge(12.0f);
            rottenApple.setRotten(true);
            ic1.getItems().add(rottenApple);

            Item tapeScript = new Item();
            DrainableComboItem ductTape = new DrainableComboItem("Base", "DuctTape", "DuctTape", tapeScript);
            ductTape.setScriptItem(tapeScript);
            ductTape.id = 1003;
            ductTape.setUseDelta(0.25f);
            ductTape.setCurrentUses(4);
            ic1.getItems().add(ductTape);

            // (b) 合法储物柜 #2（用于测试单箱私密排除 ER_Private）
            IsoObject crate2 = new IsoObject((IsoCell) null);
            crate2.setSquare(sq);
            ItemContainer ic2 = new ItemContainer("locker", sq, crate2);
            ic2.setCapacity(30);
            crate2.setContainer(ic2);
            Item gunScript = new Item();
            InventoryItem secretGun = new InventoryItem("Base", "Pistol", "Pistol", gunScript);
            secretGun.setScriptItem(gunScript);
            secretGun.id = 2001;
            ic2.getItems().add(secretGun);

            // (c) 非储物设施：炉灶 IsoStove 与垃圾桶 bin（必须被默认黑名单自动跳过）
            IsoStove stove = new IsoStove((IsoCell) null);
            stove.setSquare(sq);
            ItemContainer stoveCont = new ItemContainer("stove", sq, stove);
            stove.setContainer(stoveCont);
            Food soup = new Food("Base", "SoupBowl", "SoupBowl", appleScript);
            soup.setScriptItem(appleScript);
            stoveCont.getItems().add(soup);

            IsoObject trashObj = new IsoObject((IsoCell) null);
            trashObj.setSquare(sq);
            ItemContainer binCont = new ItemContainer("bin", sq, trashObj);
            trashObj.setContainer(binCont);
            binCont.getItems().add(new InventoryItem("Base", "TinCanEmpty", "TinCanEmpty", new Item()));

            sq.getObjects().add(crate1);
            sq.getObjects().add(crate2);
            sq.getObjects().add(stove);
            sq.getObjects().add(trashObj);

            check(EmberRoutesBridge.isEligibleStorageObject(crate1), "crate1 must be eligible");
            check(!EmberRoutesBridge.isEligibleStorageObject(stove), "IsoStove must be blacklisted");
            check(!EmberRoutesBridge.isEligibleStorageObject(trashObj), "trash bin must be blacklisted");

            // 挂载区块：应只纳管 crate1 + crate2（共 4 件物品），且未设私密时 hasModData() 保持 false（0 空表分配）
            EmberRoutesBridge.onSquareLoaded(sq);
            check(!crate1.hasModData() && !crate2.hasModData(), "Scanning untouched objects MUST NOT allocate ModData");
            check(EmberRoutesBridge.getAwakeTickerCount() == 1.0, "Loaded storage square must register 1 AE2 ticker");
            check(EmberRoutesBridge.getWarehouseTypeCount("S_AE2", "Base.Apple") == 2.0, "Total apples must be 2");
            check(EmberRoutesBridge.getWarehouseUsableTypeCount("S_AE2", "Base.Apple") == 1.0, "Usable fresh apples must be 1");
            check(EmberRoutesBridge.getWarehouseTypeCount("S_AE2", "Base.SoupBowl") == 0.0, "Stove contents must be excluded");
            check(EmberRoutesBridge.getWarehouseTypeCount("S_AE2", "Base.TinCanEmpty") == 0.0, "Bin contents must be excluded");

            // 原位状态变化测试：未变时 zero-alloc matchesContainer 必须命中；扣掉 2 次后必须立即检出
            WarehouseFingerprint beforeUse = WarehouseFingerprint.of(ic1);
            check(beforeUse.matchesContainer(ic1), "Unchanged container must hit zero-alloc matchesContainer");
            ductTape.setCurrentUses(2);
            check(!beforeUse.matchesContainer(ic1), "Drainable in-place setCurrentUses must miss zero-alloc matchesContainer");
            WarehouseFingerprint afterUse = WarehouseFingerprint.of(ic1);
            check(!beforeUse.matches(afterUse), "Drainable in-place setCurrentUses must change WarehouseFingerprint");
            ductTape.setCurrentUses(0);
            EmberRoutesBridge.onSquareLoaded(sq);
            check(EmberRoutesBridge.getWarehouseUsableTypeCount("S_AE2", "Base.DuctTape") == 0.0,
                    "Depleted drainable with 0 uses must not count as usable");

            // 单箱设为私密测试：crate2 设为私密后，手枪从公共仓消失，privateCount == 1
            check(EmberRoutesBridge.setContainerObjectPrivate(crate2, true), "crate2 should become private");
            EmberRoutesBridge.onSquareLoaded(sq);
            check(EmberRoutesBridge.getWarehouseTypeCount("S_AE2", "Base.Pistol") == 0.0, "Private crate items must be excluded");
            KahluaTable snapPriv = EmberRoutesBridge.getWarehouseSnapshot("S_AE2");
            KahluaTable countsPriv = (KahluaTable) snapPriv.rawget("counts");
            check(((Double) countsPriv.rawget("loaded")).intValue() == 1, "Loaded public containers should be 1");
            check(((Double) countsPriv.rawget("privateCount")).intValue() == 1, "Private count should be 1");

            // 恢复 crate2 为公共仓：手枪回归，且空 ModData 不再判定为 hasModData()
            check(!EmberRoutesBridge.setContainerObjectPrivate(crate2, false), "crate2 should return to public");
            check(!crate2.hasModData(), "Clearing ER_Private on otherwise empty ModData must leave hasModData()==false");
            EmberRoutesBridge.onSquareLoaded(sq);
            check(EmberRoutesBridge.getWarehouseTypeCount("S_AE2", "Base.Pistol") == 1.0, "Restored public crate items must return");

            // 离屏卸载 OFFLINE_CACHED 测试：卸载区块后停掉节拍器，但总库存保留最后快照绝不归零！
            EmberRoutesBridge.onSquareUnloaded(sq);
            check(EmberRoutesBridge.getAwakeTickerCount() == 0.0, "Unloaded square must sleep/remove AE2 ticker");
            KahluaTable snapOff = EmberRoutesBridge.getWarehouseSnapshot("S_AE2");
            KahluaTable countsOff = (KahluaTable) snapOff.rawget("counts");
            check(((Double) countsOff.rawget("loaded")).intValue() == 0, "Loaded count must become 0 when away");
            check(((Double) countsOff.rawget("unloaded")).intValue() == 2, "Unloaded cached count must be 2");
            check(((Double) countsOff.rawget("items")).intValue() == 4, "Total items must be preserved in OFFLINE_CACHED (4)");
            check(EmberRoutesBridge.getWarehouseTypeCount("S_AE2", "Base.Apple") == 2.0, "Offline cached apples must remain queryable");

            // 重回基地加载：无缝从 OFFLINE_CACHED 切回 LOADED，绝不双倍累加
            EmberRoutesBridge.onSquareLoaded(sq);
            KahluaTable snapReload = EmberRoutesBridge.getWarehouseSnapshot("S_AE2");
            KahluaTable countsReload = (KahluaTable) snapReload.rawget("counts");
            check(((Double) countsReload.rawget("loaded")).intValue() == 2, "Reloaded public containers must be 2");
            check(((Double) countsReload.rawget("unloaded")).intValue() == 0, "Unloaded count must return to 0");
            check(((Double) countsReload.rawget("items")).intValue() == 4, "Reloaded items must stay exact 4 (no double add)");

            // Lua 自定义分类规则链验证
            EmberRoutesBridge.registerCategoryOverride("Base.Pistol", "custom.sidearm");
            InventoryItem probePistol = new InventoryItem("Base", "Pistol", "Pistol", new Item());
            check("custom.sidearm".equals(WarehouseFastIndex.classifyItem(probePistol)), "Custom category override must take priority");
            EmberRoutesBridge.clearCategoryOverrides();
            EmberRoutesBridge.reset();
        }
        System.out.println("[VerifyModule1] 4/7 & 5/7 B42 live container blacklist, ER_Private, OFFLINE_CACHED & AE2 ticker tests passed.");

        // 6. TerritoryContour 与 ZombieBuddy @Patch 根包归属及目标方法真实性门禁
        {
            List<int[]> donutRects = List.of(
                    new int[]{0, 0, 10, 3},
                    new int[]{0, 7, 10, 3},
                    new int[]{0, 3, 3, 4},
                    new int[]{7, 3, 3, 4}
            );
            check(TerritoryContour.extractOutlineSegments(donutRects).length == 8 * 4, "Donut contour must have 8 segments");

            Path modJar = root.resolve("Contents/mods/EmberRoutes/42/media/java/client/EmberRoutes.jar");
            int patchCount = 0;
            try (JarFile jf = new JarFile(modJar.toFile())) {
                var entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry je = entries.nextElement();
                    String name = je.getName();
                    if (!name.endsWith(".class")) continue;
                    String clsName = name.substring(0, name.length() - 6).replace('/', '.');
                    Class<?> cls = Class.forName(clsName);
                    Patch p = cls.getAnnotation(Patch.class);
                    if (p != null) {
                        String pkg = cls.getPackage() != null ? cls.getPackage().getName() : "";
                        check("emberroutes".equals(pkg),
                                "@Patch class " + clsName + " MUST be in root package 'emberroutes' (got '" + pkg + "')");
                        Class<?> targetCls = Class.forName(p.className(), false, VerifyModule1.class.getClassLoader());
                        boolean methodFound = false;
                        for (Method m : targetCls.getDeclaredMethods()) {
                            if (m.getName().equals(p.methodName())) {
                                methodFound = true;
                                break;
                            }
                        }
                        check(methodFound, "@Patch target method not found: " + p.className() + "." + p.methodName());
                        patchCount++;
                    }
                }
            }
            check(patchCount == 25, "Expected exact 25 @Patch classes in root package 'emberroutes', got " + patchCount);
            // 验证 NpcManager 防空写存档门闩：未成功 loadFromCurrentSave 前，saveAllToDisk 必须拒绝覆写返回 false
            NpcManager.reset();
            check(!NpcManager.isLoadedForCurrentSave(), "After reset, loadedForCurrentSave must be false");
            check(!NpcManager.saveAllToDisk(), "saveAllToDisk MUST refuse to overwrite save before loadFromCurrentSave");
            check(EmberRoutesBridge.drainNpcMoveEvents() == null, "Idle drainNpcMoveEvents must return null (zero alloc)");
            verifyLuaLayerBoundaries(luaRoot);
            System.out.println("[VerifyModule1] 6/7 Verified all " + patchCount + " @Patch classes in root package 'emberroutes', save latch, zero-alloc drain & Lua layer boundaries.");
        }

        // 7. M2 NpcSnapshot 二进制存读档与 100% InventoryItem.id / 嵌套背包原件守恒契约校验
        {
            Item appleScript = new Item();
            Food apple = new Food("Base", "Apple", "Apple", appleScript);
            apple.setScriptItem(appleScript);
            apple.id = 77002;
            apple.setAge(3.5f);
            apple.getModData().rawset("ER_Origin", "Farm-7");

            Item tapeScript = new Item();
            DrainableComboItem tape = new DrainableComboItem("Base", "DuctTape", "DuctTape", tapeScript);
            tape.setScriptItem(tapeScript);
            tape.id = 77003;
            tape.setUseDelta(0.25f);
            tape.setCurrentUses(2);

            Item bagScript = new Item();
            InventoryContainer bag;
            boolean prevServer = GameServer.server;
            try {
                GameServer.server = true;
                bag = new InventoryContainer("Base", "Bag_Schoolbag", "Bag_Schoolbag", "Item_Bag_Schoolbag");
            } finally {
                GameServer.server = prevServer;
            }
            bag.setScriptItem(bagScript);
            bag.id = 77001;
            InventoryItem childItem = new InventoryItem("Base", "Hammer", "Hammer", new Item());
            childItem.id = 77004;
            bag.getInventory().getItems().add(childItem);

            ItemContainer srcContainer = new ItemContainer();
            srcContainer.getItems().add(apple);
            srcContainer.getItems().add(tape);
            srcContainer.getItems().add(bag);

            NpcSnapshot.InventorySnapshot invSnap = NpcSnapshot.captureInventory(
                    srcContainer, null, tape, apple, null, null);
            NpcSnapshot src = new NpcSnapshot();
            src.npcId = "npc_verify_1";
            src.displayName = "RunnerAlpha";
            src.x = 1234.5f;
            src.y = 5678.25f;
            src.z = 1.0f;
            src.dirAngleDeg = 45.0f;
            src.worldVersion = 249;
            src.applyInventorySnapshot(invSnap);
            src.wornLocations = new String[]{"base:back"};
            src.wornItemIndexes = new int[]{2};
            src.attachedLocations = new String[]{"Belt Left"};
            src.attachedItemIndexes = new int[]{1};

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.writeInt(NpcSnapshot.FILE_MAGIC);
                dos.writeInt(NpcSnapshot.FILE_VERSION);
                dos.writeInt(1);
                src.writeTo(dos);
            }

            NpcSnapshot dst;
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
                check(dis.readInt() == NpcSnapshot.FILE_MAGIC, "EMBN magic mismatch");
                check(dis.readInt() == NpcSnapshot.FILE_VERSION, "EMBN version mismatch");
                check(dis.readInt() == 1, "Record count mismatch");
                dst = NpcSnapshot.readFrom(dis);
            }

            check("npc_verify_1".equals(dst.npcId), "npcId mismatch");
            check("RunnerAlpha".equals(dst.displayName), "displayName mismatch");
            check(Math.abs(dst.x - 1234.5f) < 1e-4f && Math.abs(dst.y - 5678.25f) < 1e-4f && dst.z == 1.0f, "coords mismatch");
            check(Math.abs(dst.dirAngleDeg - 45.0f) < 1e-4f && dst.worldVersion == 249, "dir/version mismatch");
            check(Arrays.equals(src.inventoryData, dst.inventoryData), "Serialized ByteBuffer payload must round-trip bit-for-bit");
            check(dst.primaryItemIndex == src.primaryItemIndex && dst.primaryItemIndex >= 0, "Primary hand index mismatch");
            check(dst.secondaryItemIndex == src.secondaryItemIndex && dst.secondaryItemIndex >= 0, "Secondary hand index mismatch");
            check(Arrays.equals(src.wornLocations, dst.wornLocations) && Arrays.equals(src.wornItemIndexes, dst.wornItemIndexes),
                    "Worn slot indices must round-trip exactly");
            check(Arrays.equals(src.attachedLocations, dst.attachedLocations) && Arrays.equals(src.attachedItemIndexes, dst.attachedItemIndexes),
                    "Attached slot indices must round-trip exactly");

            // 验证 ByteBuffer 内部 ItemContainer 头与 compress group 计数
            ByteBuffer invBuf = ByteBuffer.wrap(dst.inventoryData);
            String containerType = GameWindow.ReadString(invBuf);
            check(containerType != null, "Container type header missing");
            invBuf.get(); // explored byte
            short distinctEntries = invBuf.getShort();
            check(distinctEntries == 3, "Expected 3 serialized CompressIdenticalItems groups, got " + distinctEntries);

            // 直接验证真实 B42 Food 与 DrainableComboItem saveWithSize -> loadItem id/ModData/uses 原件守恒
            ByteBuffer appleBuf = ByteBuffer.allocate(2048);
            apple.saveWithSize(appleBuf, false);
            appleBuf.flip();
            Food loadedApple = new Food("Base", "Apple", "Apple", appleScript);
            loadedApple.setScriptItem(appleScript);
            InventoryItem resApple = InventoryItem.loadItem(appleBuf, 249, false, loadedApple);
            check(resApple == loadedApple && loadedApple.id == 77002, "Food InventoryItem.id 77002 must survive ByteBuffer round-trip");
            check(loadedApple.hasModData() && "Farm-7".equals(loadedApple.getModData().rawget("ER_Origin")),
                    "Food ModData ER_Origin must survive ByteBuffer round-trip");

            ByteBuffer tapeBuf = ByteBuffer.allocate(2048);
            tape.saveWithSize(tapeBuf, false);
            tapeBuf.flip();
            DrainableComboItem loadedTape = new DrainableComboItem("Base", "DuctTape", "DuctTape", tapeScript);
            loadedTape.setScriptItem(tapeScript);
            InventoryItem resTape = InventoryItem.loadItem(tapeBuf, 249, false, loadedTape);
            check(resTape == loadedTape && loadedTape.id == 77003, "Drainable InventoryItem.id 77003 must survive ByteBuffer round-trip");
            check(loadedTape.getCurrentUses() == 2, "Drainable currentUses must remain 2, got " + loadedTape.getCurrentUses());

            System.out.println("[VerifyModule1] 7/7 NpcSnapshot ByteBuffer binary format, InventoryItem.id & nested container conservation verified.");
        }
        System.out.println("[VerifyModule1] ALL CHECKS PASSED.");
    }

    private static void verifyLuaLayerBoundaries(Path luaRoot) throws Exception {
        Path clientRoot = luaRoot.resolve("client");
        List<String> clientMods = new ArrayList<>();
        if (Files.isDirectory(clientRoot)) {
            try (var s = Files.walk(clientRoot)) {
                s.filter(p -> p.toString().endsWith(".lua")).forEach(p -> {
                    String rel = clientRoot.relativize(p).toString().replace('\\', '/');
                    clientMods.add(rel.substring(0, rel.length() - 4));
                });
            }
        }
        var blockComment = java.util.regex.Pattern.compile("--\\[(=*)\\[.*?\\]\\1\\]", java.util.regex.Pattern.DOTALL);
        var lineComment = java.util.regex.Pattern.compile("--[^\\n]*");
        var reqPat = java.util.regex.Pattern.compile("require\\s*\\(?\\s*[\"']([^\"']+)[\"']\\s*\\)?");
        var eventsAddPat = java.util.regex.Pattern.compile("\\bEvents\\s*\\.\\s*[A-Za-z0-9_]+\\s*\\.\\s*Add\\s*\\(");
        var rawDisablePat = java.util.regex.Pattern.compile("\\bdisableWorldMenu\\s*=(?!=)");
        var globalResetPat = java.util.regex.Pattern.compile("\\bEmberRoutesJava\\s*\\.\\s*reset\\s*\\(");

        try (var s = Files.walk(luaRoot)) {
            List<Path> files = s.filter(p -> p.toString().endsWith(".lua")).sorted().toList();
            for (Path lf : files) {
                String rel = luaRoot.relativize(lf).toString().replace('\\', '/');
                if (rel.contains("/Translate/")) continue;
                String[] parts = rel.split("/");
                String side = parts[0];
                String domain = parts.length >= 4 ? parts[2] : "";
                String src = Files.readString(lf, StandardCharsets.UTF_8);
                src = blockComment.matcher(src).replaceAll("");
                src = lineComment.matcher(src).replaceAll("");

                var rm = reqPat.matcher(src);
                while (rm.find()) {
                    String mod = rm.group(1);
                    if ("shared".equals(side)) {
                        check(!clientMods.contains(mod) && !mod.startsWith("EmberUI") && !mod.startsWith("EmberRoutes/UI") && !mod.startsWith("EmberRoutes/Warehouse"),
                                "shared->client require forbidden: " + rel + " -> " + mod);
                    }
                    if ("shared/EmberRoutes/Kernel.lua".equals(rel)) {
                        check(!mod.startsWith("EmberRoutes/") && !mod.startsWith("EmberUI"),
                                "Kernel.lua must remain domain-agnostic: " + mod);
                    }
                    if ("Settlement".equals(domain) || "Warehouse".equals(domain) || "Npc".equals(domain) || "Work".equals(domain) || "Squad".equals(domain)) {
                        check(!mod.startsWith("EmberRoutes/UI") && !mod.startsWith("EmberRoutes/Compat") && !mod.startsWith("EmberUI"),
                                "Domain must not depend on UI/Compat: " + rel + " -> " + mod);
                    }
                }
                if (!"Compat".equals(domain)) {
                    check(!src.contains("getActivatedMods"), "getActivatedMods forbidden outside Compat/: " + rel);
                }
                if (!"shared/EmberRoutes/Kernel.lua".equals(rel)) {
                    check(!globalResetPat.matcher(src).find(), "Direct EmberRoutesJava.reset() forbidden outside Kernel.lua: " + rel);
                }
                if (!"shared/EmberRoutes/Kernel.lua".equals(rel) && !"client/EmberRoutes/Boot.lua".equals(rel)) {
                    check(!eventsAddPat.matcher(src).find(), "Direct Events.*.Add forbidden outside Boot/Kernel: " + rel);
                    check(!rawDisablePat.matcher(src).find(), "Direct disableWorldMenu write forbidden: " + rel);
                }
            }
        }
    }
}
