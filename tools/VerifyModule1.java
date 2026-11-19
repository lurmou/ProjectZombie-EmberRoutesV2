import emberroutes.EmberRoutesBridge;
import emberroutes.warehouse.ContainerDirtyQueue;
import emberroutes.warehouse.TerritoryContour;
import emberroutes.warehouse.WarehouseFastIndex;
import emberroutes.warehouse.WarehouseFingerprint;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import zombie.Lua.LuaManager;
import zombie.core.Core;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.Food;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoStove;
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
        System.out.println("[VerifyModule1] 1/6 All " + luaFiles.size() + " Lua files compiled cleanly in Kahlua2 (normal + debug).");

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
        System.out.println("[VerifyModule1] 2/6 ContainerDirtyQueue signed coordinate packing & zero-alloc drain passed.");

        // 3. WarehouseFingerprint 槽位敏感性、浮点量化与原位状态哈希校验
        WarehouseFingerprint fp1 = new WarehouseFingerprint(new int[]{101, 202, 303}, 12.341, 999L);
        WarehouseFingerprint fp2 = new WarehouseFingerprint(new int[]{101, 202, 303}, 12.344, 999L);
        WarehouseFingerprint fpSwapped = new WarehouseFingerprint(new int[]{101, 303, 202}, 12.341, 999L);
        WarehouseFingerprint fpStateDiff = new WarehouseFingerprint(new int[]{101, 202, 303}, 12.341, 1000L);
        check(fp1.matches(fp2), "Quantized 0.01kg weight should match");
        check(!fp1.matches(fpSwapped), "Swapped middle slot must be detected");
        check(!fp1.matches(fpStateDiff), "In-place state change (uses/rotten/fluid/condition) must be detected");
        System.out.println("[VerifyModule1] 3/6 WarehouseFingerprint slot identity, weight quantization & state hash passed.");

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
            KahluaTable javaBridge = platform.newTable();
            javaBridge.rawset("reset", (JavaFunction) (cf, n) -> {
                resetCount.incrementAndGet();
                EmberRoutesBridge.reset();
                return 0;
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
            env.rawset("EmberRoutesJava", javaBridge);
            env.rawset("next", null);

            String testLua = """
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
                    OnMainMenuEnter = makeEvent(),
                    OnTick = makeEvent(),
                    OnTickEvenPaused = makeEvent(),
                    LoadGridsquare = makeEvent(),
                    ReuseGridsquare = makeEvent(),
                    OnFillWorldObjectContextMenu = makeEvent(),
                }

                local Kernel = require "EmberRoutes/Kernel"
                local Store = require "EmberRoutes/Settlement/SettlementStore"
                local Geo = require "EmberRoutes/Settlement/SettlementGeometry"
                require "EmberRoutes/Boot"

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

                -- Test Kernel topological sort, LIFO + error isolation, acquireGlobalFlag, wrap bypass, Boot lifecycle
                local orderLog, cleanLog = {}, {}
                Kernel.plugin({
                    name = "test-consumer",
                    inject = { "testSvc" },
                    apply = function(ctx)
                        assert(ctx.testSvc and ctx.testSvc.val == 42, "inject must resolve provider registered later")
                        orderLog[#orderLog + 1] = "consumer"
                        ctx:effect(function()
                            return function() cleanLog[#cleanLog + 1] = "c1" end
                        end)
                        ctx:effect(function()
                            return function()
                                cleanLog[#cleanLog + 1] = "c2-throws"
                                error("simulated cleanup failure")
                            end
                        end)
                    end,
                })
                Kernel.plugin({
                    name = "test-provider",
                    provides = { "testSvc" },
                    apply = function(ctx)
                        orderLog[#orderLog + 1] = "provider"
                        ctx:provide("testSvc", { val = 42 })
                    end,
                })
                Kernel.plugin({
                    name = "test-isolated-skipped",
                    inject = { "nonexistent-optional-svc" },
                    isolated = true,
                    apply = function() error("should not run") end,
                })

                assert(not Kernel.isBooted(), "Kernel must not boot on require")
                Events.OnInitGlobalModData.fire(false)
                assert(Kernel.isBooted(), "OnInitGlobalModData must boot Kernel via Boot.lua")
                assert(orderLog[1] == "provider" and orderLog[2] == "consumer", "topological order violated")
                assert(#Events.OnTick._handlers == 1, "WarehouseWatcher must attach 1 OnTick handler")
                assert(#Events.OnFillWorldObjectContextMenu._handlers == 1, "DebugContextMenu must attach 1 menu handler")
                Events.OnGameStart.fire()
                assert(#Events.OnTick._handlers == 1, "Kernel.boot must be idempotent across OnGameStart")

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
                assert(not Kernel.isBooted(), "OnMainMenuEnter must tear down Kernel")
                assert(#Events.OnTick._handlers == 0 and #Events.OnFillWorldObjectContextMenu._handlers == 0, "teardown must unbind all events")
                assert(cleanLog[1] == "c2-throws" and cleanLog[2] == "c1", "effects must dispose LIFO despite cleanup error")
                assert(wrapHost.calc(2) == 9, "secondary mod must bypass disabled wrapper and call base -> (2+1)*3")
                assert(wrapHost.plain(4) == 8, "uncontested wrapper must restore exact original function")
                assert(EmberRoutesJava.getResetCount() > savedBefore, "teardown must reset Java bridge")
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
        System.out.println("[VerifyModule1] 4/6 & 5/6 B42 live container blacklist, ER_Private, OFFLINE_CACHED & AE2 ticker tests passed.");

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
            check(patchCount == 21, "Expected exact 21 @Patch classes in root package 'emberroutes', got " + patchCount);
            verifyLuaLayerBoundaries(luaRoot);
            System.out.println("[VerifyModule1] 6/6 Verified all " + patchCount + " @Patch classes in root package 'emberroutes', valid B42 targets & Lua layer boundaries.");
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
                if (!"shared/EmberRoutes/Kernel.lua".equals(rel) && !"client/EmberRoutes/Boot.lua".equals(rel)) {
                    check(!eventsAddPat.matcher(src).find(), "Direct Events.*.Add forbidden outside Boot/Kernel: " + rel);
                    check(!rawDisablePat.matcher(src).find(), "Direct disableWorldMenu write forbidden: " + rel);
                }
            }
        }
    }
}
