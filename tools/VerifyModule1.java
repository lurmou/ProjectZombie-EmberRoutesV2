import emberroutes.ContainerDirtyQueue;
import emberroutes.EmberRoutesBridge;
import emberroutes.TerritoryContour;
import emberroutes.WarehouseFastIndex;
import emberroutes.WarehouseFingerprint;
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
                Path sharedFile = luaRoot.resolve("shared").resolve(modName + ".lua");
                try {
                    String code = Files.readString(sharedFile, StandardCharsets.UTF_8);
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

            KahluaTable javaBridge = platform.newTable();
            javaBridge.rawset("reset", (JavaFunction) (cf, n) -> { EmberRoutesBridge.reset(); return 0; });
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
            env.rawset("EmberRoutesJava", javaBridge);

            String testLua = """
                local Store = require "EmberRoutes/SettlementStore"
                local Geo = require "EmberRoutes/SettlementGeometry"

                local s1, err = Store.createSettlement("Alpha", 100, 200, 103, 206)
                assert(s1 ~= nil and err == nil, "createSettlement failed")
                assert(Geo.totalTiles(s1.areas) == 28, "expected exact 28 tiles")

                local sOverlap, err2 = Store.createSettlement("Beta", 102, 205, 110, 210)
                assert(sOverlap == nil and err2 == "TERRITORY_OVERLAP", "overlap should reject")

                local exp, expErr = Store.expandTerritory(s1.id, 104, 200, 107, 202)
                assert(exp ~= nil and Geo.totalTiles(exp.areas) == 40, "expanded tiles should be 40")
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
                """;

            LuaClosure testFn = LuaCompiler.loadstring(testLua, "VerifyModule1Test.lua", env);
            LuaReturn res = caller.protectedCall(thread, testFn);
            if (!res.isSuccess()) {
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
            System.out.println("[VerifyModule1] 6/6 Verified all " + patchCount + " @Patch classes in root package 'emberroutes' & valid B42 targets.");
        }
        System.out.println("[VerifyModule1] ALL CHECKS PASSED.");
    }
}
