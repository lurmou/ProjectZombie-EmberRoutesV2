import emberroutes.warehouse.TerritoryContour;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.json.JSONObject;
import org.lwjgl.opengl.GL11;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.DisplayMode;
import org.lwjglx.opengl.PixelFormat;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import zombie.DummySoundManager;
import zombie.GameWindow;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaHookManager;
import zombie.Lua.LuaManager;
import zombie.SoundManager;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.Languages;
import zombie.core.SceneShaderStore;
import zombie.core.SpriteRenderer;
import zombie.core.Translator;
import zombie.core.VBO.GLVertexBufferObject;
import zombie.core.fonts.AngelCodeFont;
import zombie.core.opengl.RenderThread;
import zombie.core.random.RandLua;
import zombie.core.random.RandStandard;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureAssetManager;
import zombie.core.textures.TextureID;
import zombie.core.textures.TextureIDAssetManager;
import zombie.ui.PreviewFrame;
import zombie.ui.TextManager;
import zombie.ui.UIManager;

/**
 * 脱机原生 OpenGL UI 帧缓冲渲染自检器：
 * 同时验证 ZoneEditorPanel、SettlementControlPanel（仓储提货 / 容器分拣 / 人员名册）、ContainerConfigWizard 与 NpcInventoryPanel，
 * 导出 CN 与 EN 真实帧缓冲截图核验无压字、无重叠、无裁剪溢出。
 */
public final class PreviewModule1 {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 640;

    public static void main(String[] args) {
        int code = 0;
        try {
            run(Path.of(args[0]).toAbsolutePath(), Path.of(args[1]).toAbsolutePath());
        } catch (Throwable t) {
            t.printStackTrace();
            code = 1;
        }
        System.exit(code);
    }

    private static void run(Path repo, Path outDir) throws Exception {
        Files.createDirectories(outDir);
        ZomboidFileSystem.instance.setCacheDir(outDir.resolve("cache").toString());
        try {
            Display.init();
            Display.setDisplayMode(new DisplayMode(WIDTH, HEIGHT));
            Display.create(new PixelFormat(32, 0, 24, 8, 0));

            ZomboidFileSystem.instance.init();
            Path emberUiDir = repo.getParent().resolve("EmberUI/Contents/mods/EmberUI");
            ZomboidFileSystem.instance.setModIdToDir("EmberUI", emberUiDir.toString());
            ZomboidFileSystem.instance.loadMod("EmberUI");
            Path modDir = repo.resolve("Contents/mods/EmberRoutes");
            ZomboidFileSystem.instance.setModIdToDir("EmberRoutes", modDir.toString());
            ZomboidFileSystem.instance.loadMod("EmberRoutes");

            TextureIDAssetManager.instance.create(TextureID.ASSET_TYPE, GameWindow.assetManagers);
            TextureAssetManager.instance.create(Texture.ASSET_TYPE, GameWindow.assetManagers);
            GameWindow.LoadTexturePack("UI", 2);
            GameWindow.LoadTexturePack("UI2", 2);
            GameWindow.setTexturePackLookup();

            GLVertexBufferObject.init();
            Core.getInstance().initGlobalShader();
            Display.makeCurrent();
            SpriteRenderer.instance.create();
            Core.getInstance().setScreenSize(WIDTH, HEIGHT);
            Languages.instance.init();
            RandStandard.INSTANCE.init();
            RandLua.INSTANCE.init();
            SoundManager.instance = new DummySoundManager();

            for (String lang : List.of("CN", "EN")) {
                renderLanguage(repo, outDir, lang);
            }
        } finally {
            if (Display.isCreated()) {
                try {
                    RenderThread.invokeOnRenderContext(() -> {
                        Set<AngelCodeFont> fonts = new HashSet<>();
                        Collections.addAll(fonts, TextManager.instance.enumToFont);
                        Collections.addAll(fonts, TextManager.instance.normal);
                        fonts.remove(null);
                        for (AngelCodeFont f : fonts) f.destroy();
                        TextureAssetManager.instance.destroy();
                        TextureIDAssetManager.instance.destroy();
                    });
                } finally {
                    Display.destroy();
                }
            }
        }
    }

    private static void waitForAssets() throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (GameWindow.fileSystem.hasWork() || TextManager.instance.isAnyFontLoading()) {
            GameWindow.fileSystem.updateAsyncTransactions();
            Display.processMessages();
            if (System.nanoTime() > deadline) throw new IllegalStateException("Asset load timed out");
            Thread.sleep(10);
        }
    }

    private static void renderLanguage(Path repo, Path outDir, String lang) throws Exception {
        Translator.setLanguage(Languages.instance.getByName(lang));
        TextManager.instance.Init();
        waitForAssets();

        UIManager.clearArrays();
        LuaEventManager.Reset();
        LuaHookManager.Reset();
        LuaManager.init();

        Path transDir = repo.resolve("Contents/mods/EmberRoutes/42/media/lua/shared/Translate/" + lang);
        JSONObject dict = new JSONObject();
        for (String jf : List.of("ContextMenu.json", "IG_UI.json", "UI.json")) {
            Path p = transDir.resolve(jf);
            if (Files.isRegularFile(p)) {
                JSONObject sub = new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
                for (String k : sub.keySet()) dict.put(k, sub.getString(k));
            }
        }

        Object origGetText = LuaManager.env.rawget("getText");
        LuaManager.env.rawset("getText", (JavaFunction) (frame, count) -> {
            String key = String.valueOf(frame.get(0));
            if (dict.has(key)) {
                return frame.push(formatPreviewText(dict.getString(key), lang, frame, count));
            }
            Object[] a = new Object[count];
            for (int i = 0; i < count; i++) a[i] = frame.get(i);
            Object r = LuaManager.thread.call(origGetText, a);
            if (r instanceof String s && "CN".equals(lang)) return frame.push(previewS2T(s));
            return frame.push(r);
        });

        LuaManager.env.rawset("getTextOrNull", (JavaFunction) (frame, count) -> {
            String key = String.valueOf(frame.get(0));
            if (dict.has(key)) {
                return frame.push(formatPreviewText(dict.getString(key), lang, frame, count));
            }
            return frame.push(null);
        });

        Map<String, Object> loaded = new HashMap<>();
        Path modLua = repo.resolve("Contents/mods/EmberRoutes/42/media/lua");
        Path emberUiLua = repo.getParent().resolve("EmberUI/Contents/mods/EmberUI/42/media/lua");
        LuaManager.env.rawset("require", (JavaFunction) (frame, count) -> {
            String mod = String.valueOf(frame.get(0));
            if (loaded.containsKey(mod)) return frame.push(loaded.get(mod));
            Path source = null;
            for (String side : List.of("shared", "client")) {
                Path base;
                if (mod.startsWith("EmberRoutes/")) {
                    base = modLua;
                } else if (mod.equals("EmberUI") || mod.startsWith("EmberUI/")) {
                    base = emberUiLua;
                } else {
                    base = Path.of("media/lua");
                }
                Path sideDir = base.resolve(side);
                Path cand = sideDir.resolve(mod + ".lua").toAbsolutePath().normalize();
                if (Files.isRegularFile(cand)) { source = cand; break; }
                if (!mod.startsWith("EmberRoutes/") && !mod.startsWith("EmberUI") && Files.isDirectory(sideDir)) {
                    try (var ds = Files.newDirectoryStream(sideDir)) {
                        for (Path sub : ds) {
                            if (Files.isDirectory(sub)) {
                                Path subCand = sub.resolve(mod + ".lua").toAbsolutePath().normalize();
                                if (Files.isRegularFile(subCand)) { source = subCand; break; }
                            }
                        }
                    } catch (IOException ignored) {}
                    if (source != null) break;
                }
            }
            if (source == null) throw new IllegalArgumentException("Missing Lua module: " + mod);
            try (Reader r = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                Object[] res = LuaManager.thread.pcall(LuaCompiler.loadis(r, source.toString(), LuaManager.env), new Object[0]);
                if (!Boolean.TRUE.equals(res[0])) {
                    throw new IllegalStateException("Lua error in " + mod + ": " + (res.length > 1 ? res[1] : ""));
                }
                Object val = res.length > 1 ? res[1] : null;
                loaded.put(mod, val);
                return frame.push(val);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        pcallLua("ISInventoryPage = ISInventoryPage or {}; ISWorldObjectContextMenu = ISWorldObjectContextMenu or {}; "
                + "require 'env'; require 'TimedActions/ISBaseTimedAction'; require 'ISUI/Gamepad/JoyPadSetup'; "
                + "require 'ISUI/ISRichTextPanel'; require 'ISUI/ISToolTip'; require 'ISUI/ISTextEntryBox'");

        KahluaTable modDataRoot = LuaManager.platform.newTable();
        KahluaTable modDataApi = LuaManager.platform.newTable();
        modDataApi.rawset("getOrCreate", (JavaFunction) (cf, n) -> {
            String k = String.valueOf(cf.get(0));
            Object t = modDataRoot.rawget(k);
            if (t == null) { t = LuaManager.platform.newTable(); modDataRoot.rawset(k, t); }
            return cf.push(t);
        });
        LuaManager.env.rawset("ModData", modDataApi);

        KahluaTable jb = LuaManager.platform.newTable();
        jb.rawset("reset", (JavaFunction) (cf, n) -> 0);
        jb.rawset("syncSettlementAreas", (JavaFunction) (cf, n) -> 0);
        jb.rawset("invalidateSettlementWarehouse", (JavaFunction) (cf, n) -> 0);
        jb.rawset("isZoneOverlayVisible", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));
        jb.rawset("setZoneOverlayVisible", (JavaFunction) (cf, n) -> 0);
        jb.rawset("syncZoneEditorState", (JavaFunction) (cf, n) -> 0);
        jb.rawset("clearZoneEditorState", (JavaFunction) (cf, n) -> 0);
        jb.rawset("getDirtyEventCount", (JavaFunction) (cf, n) -> cf.push(Double.valueOf(18)));
        jb.rawset("getAwakeTickerCount", (JavaFunction) (cf, n) -> cf.push(Double.valueOf(6)));
        jb.rawset("getWarehouseRevision", (JavaFunction) (cf, n) -> cf.push(Double.valueOf(9)));
        jb.rawset("inspectAreasEnclosure", (JavaFunction) (cf, n) -> {
            KahluaTable enc = LuaManager.platform.newTable();
            enc.rawset("total", Double.valueOf(279));
            enc.rawset("enclosed", Double.valueOf(96));
            enc.rawset("roofed", Double.valueOf(112));
            enc.rawset("playerBuilt", Boolean.TRUE);
            return cf.push(enc);
        });
        boolean isCnLang = "CN".equals(lang);
        Map<String, String[]> itemCatalog = new LinkedHashMap<>();
        itemCatalog.put("Base.Axe", new String[]{ isCnLang ? "消防斧" : "Fire Axe", "Axe" });
        itemCatalog.put("Base.Plank", new String[]{ isCnLang ? "木板" : "Plank", "Plank" });
        itemCatalog.put("Base.Apple", new String[]{ isCnLang ? "蘋果" : "Apple", "Apple" });
        itemCatalog.put("Base.TinnedBeans", new String[]{ isCnLang ? "罐頭豆子" : "Canned Beans", "Beans" });
        itemCatalog.put("Base.Bandage", new String[]{ isCnLang ? "急救繃帶" : "Bandage", "Bandage" });
        itemCatalog.put("Base.Antibiotics", new String[]{ isCnLang ? "抗生素" : "Antibiotics", "Antibiotics" });
        itemCatalog.put("Base.Bullets9mm", new String[]{ isCnLang ? "9mm子彈" : "9mm Rounds", "PistolAmmo" });
        itemCatalog.put("Base.Pistol", new String[]{ isCnLang ? "M9手槍" : "M9 Pistol", "HandGun3" });
        itemCatalog.put("Base.Bag_BigHikingBag", new String[]{ isCnLang ? "大登山包" : "Hiking Bag", "BigHiking_Blue" });
        itemCatalog.put("Base.Hammer", new String[]{ isCnLang ? "鐵錘" : "Hammer", "Hammer" });
        itemCatalog.put("Base.Saw", new String[]{ isCnLang ? "鋼鋸" : "Hacksaw", "Hacksaw" });
        itemCatalog.put("Base.Nails", new String[]{ isCnLang ? "鐵釘" : "Nails", "Nails" });
        itemCatalog.put("Base.WaterBottle", new String[]{ isCnLang ? "飲用水瓶" : "Water Bottle", "WaterBottle" });
        itemCatalog.put("Base.FirstAidKit", new String[]{ isCnLang ? "急救箱" : "First Aid Kit", "FirstAid" });
        itemCatalog.put("Base.Battery", new String[]{ isCnLang ? "乾電池" : "Battery", "Battery" });
        itemCatalog.put("Base.Book", new String[]{ isCnLang ? "技能書" : "Skill Book", "Book" });
        itemCatalog.put("Base.Crowbar", new String[]{ isCnLang ? "撬棍" : "Crowbar", "Crowbar" });
        itemCatalog.put("Base.Scalpel", new String[]{ isCnLang ? "手術刀" : "Scalpel", "Scalpel" });
        itemCatalog.put("Base.RottenApple", new String[]{ isCnLang ? "腐爛蘋果" : "Rotten Apple", "AppleRotten" });

        Map<String, KahluaTable> mockScriptItems = new HashMap<>();
        for (Map.Entry<String, String[]> entry : itemCatalog.entrySet()) {
            String dName = entry.getValue()[0];
            String icon = entry.getValue()[1];
            Texture tex = Texture.trygetTexture("Item_" + icon);
            if (tex == null) tex = Texture.getSharedTexture("Item_" + icon);
            final Texture fTex = tex;
            KahluaTable si = LuaManager.platform.newTable();
            si.rawset("getDisplayName", (JavaFunction) (cf, n) -> cf.push(dName));
            si.rawset("getNormalTexture", (JavaFunction) (cf, n) -> cf.push(fTex));
            si.rawset("getIcon", (JavaFunction) (cf, n) -> cf.push(icon));
            mockScriptItems.put(entry.getKey(), si);
        }
        KahluaTable mockSm = LuaManager.platform.newTable();
        mockSm.rawset("FindItem", (JavaFunction) (cf, n) -> {
            String ft = n >= 2 ? String.valueOf(cf.get(1)) : String.valueOf(cf.get(0));
            return cf.push(mockScriptItems.get(ft));
        });
        LuaManager.env.rawset("getScriptManager", (JavaFunction) (cf, n) -> cf.push(mockSm));

        jb.rawset("getWarehouseSnapshot", (JavaFunction) (cf, n) -> {
            KahluaTable snap = LuaManager.platform.newTable();
            KahluaTable counts = LuaManager.platform.newTable();
            counts.rawset("loaded", Double.valueOf(8));
            counts.rawset("unloaded", Double.valueOf(2));
            counts.rawset("privateCount", Double.valueOf(1));
            counts.rawset("items", Double.valueOf(345));
            counts.rawset("usedWeight", Double.valueOf(184.6));
            counts.rawset("totalCapacity", Double.valueOf(480.0));
            snap.rawset("counts", counts);

            KahluaTable rows = LuaManager.platform.newTable();
            String[][] sampleRows = {
                {"materials", "Base.Nails", "120", "120", "0", "2"},
                {"weapons.ammo", "Base.Bullets9mm", "64", "64", "0", "1"},
                {"materials", "Base.Plank", "48", "48", "0", "3"},
                {"food.perishable", "Base.Apple", "24", "18", "6", "2"},
                {"food.stable", "Base.TinnedBeans", "24", "24", "0", "2"},
                {"medical.bandages", "Base.Bandage", "15", "15", "0", "1"},
                {"medical.pills", "Base.Antibiotics", "8", "8", "0", "1"},
                {"fluids", "Base.WaterBottle", "6", "6", "0", "1"},
                {"materials", "Base.Battery", "6", "4", "2", "1"},
                {"literature", "Base.Book", "4", "4", "0", "1"},
                {"tools", "Base.Axe", "3", "3", "0", "2"},
                {"tools", "Base.Hammer", "3", "3", "0", "1"},
                {"tools", "Base.Saw", "2", "2", "0", "1"},
                {"weapons.guns", "Base.Pistol", "2", "2", "0", "1"},
                {"apparel.bags", "Base.Bag_BigHikingBag", "2", "2", "0", "1"},
                {"medical.bandages", "Base.FirstAidKit", "2", "2", "0", "1"},
                {"tools", "Base.Crowbar", "1", "1", "0", "1"},
                {"medical.bandages", "Base.Scalpel", "1", "1", "0", "1"}
            };
            for (int i = 0; i < sampleRows.length; i++) {
                KahluaTable r = LuaManager.platform.newTable();
                r.rawset("node", sampleRows[i][0]);
                r.rawset("fullType", sampleRows[i][1]);
                r.rawset("count", Double.valueOf(Double.parseDouble(sampleRows[i][2])));
                r.rawset("usableCount", Double.valueOf(Double.parseDouble(sampleRows[i][3])));
                r.rawset("degradedCount", Double.valueOf(Double.parseDouble(sampleRows[i][4])));
                KahluaTable srcs = LuaManager.platform.newTable();
                int sc = Integer.parseInt(sampleRows[i][5]);
                for (int j = 1; j <= sc; j++) srcs.rawset(Double.valueOf(j), LuaManager.platform.newTable());
                r.rawset("sources", srcs);
                rows.rawset(Double.valueOf(i + 1), r);
            }
            snap.rawset("rows", rows);
            return cf.push(snap);
        });
        Map<String, KahluaTable> mockRules = new LinkedHashMap<>();
        String[][] containerSpecs = {
            {"108:104:0#crate:crate:0#0", isCnLang ? "提货交接箱" : "Pickup Box", "PICKUP_OUT", "5", "true", "false", "", "", "4.2", "50", "108", "104", "0"},
            {"106:104:0#crate:crate:0#0", isCnLang ? "战利品卸货箱" : "Loot Buffer", "BUFFER_IN", "3", "true", "false", "", "", "18.5", "50", "106", "104", "0"},
            {"110:102:0#fridge:fridge:0#0", isCnLang ? "医药冷柜" : "Medical Fridge", "STORAGE", "4", "true", "false", "medical,food.perishable", "scrap.compost", "21.0", "65", "110", "102", "0"},
            {"112:102:0#locker:locker:0#0", isCnLang ? "私人枪械柜" : "Private Locker", "STORAGE", "5", "true", "true", "weapons.guns,weapons.ammo", "", "0.0", "0", "112", "102", "0"}
        };
        for (String[] cs : containerSpecs) {
            KahluaTable rt = LuaManager.platform.newTable();
            rt.rawset("containerId", cs[0]);
            rt.rawset("displayName", cs[1]);
            rt.rawset("role", cs[2]);
            rt.rawset("priority", Double.valueOf(Double.parseDouble(cs[3])));
            rt.rawset("enabled", Boolean.valueOf(Boolean.parseBoolean(cs[4])));
            rt.rawset("isPrivate", Boolean.valueOf(Boolean.parseBoolean(cs[5])));
            rt.rawset("allowGroups", cs[6]);
            rt.rawset("denyGroups", cs[7]);
            rt.rawset("allowTypes", "Base.Scalpel");
            rt.rawset("denyTypes", "Base.RottenApple");
            rt.rawset("usedWeight", Double.valueOf(Double.parseDouble(cs[8])));
            rt.rawset("capacity", Double.valueOf(Double.parseDouble(cs[9])));
            rt.rawset("x", Double.valueOf(Double.parseDouble(cs[10])));
            rt.rawset("y", Double.valueOf(Double.parseDouble(cs[11])));
            rt.rawset("z", Double.valueOf(Double.parseDouble(cs[12])));
            rt.rawset("loaded", Boolean.TRUE);
            mockRules.put(cs[0], rt);
        }

        jb.rawset("listSettlementContainers", (JavaFunction) (cf, n) -> {
            KahluaTable out = LuaManager.platform.newTable();
            int idx = 1;
            for (KahluaTable rt : mockRules.values()) {
                out.rawset(Double.valueOf(idx++), rt);
            }
            return cf.push(out);
        });
        jb.rawset("getContainerRuleById", (JavaFunction) (cf, n) -> {
            String cid = n >= 2 ? String.valueOf(cf.get(1)) : "";
            return cf.push(mockRules.get(cid));
        });
        jb.rawset("setContainerRuleById", (JavaFunction) (cf, n) -> {
            if (n >= 3 && cf.get(2) instanceof KahluaTable rt) {
                String cid = String.valueOf(cf.get(1));
                KahluaTable existing = mockRules.get(cid);
                if (existing != null) {
                    existing.rawset("role", rt.rawget("role"));
                    existing.rawset("priority", rt.rawget("priority"));
                    existing.rawset("enabled", rt.rawget("enabled"));
                    existing.rawset("isPrivate", rt.rawget("isPrivate"));
                    existing.rawset("allowGroups", rt.rawget("allowGroups"));
                    existing.rawset("denyGroups", rt.rawget("denyGroups"));
                    existing.rawset("allowTypes", rt.rawget("allowTypes"));
                    existing.rawset("denyTypes", rt.rawget("denyTypes"));
                }
            }
            return cf.push(Boolean.TRUE);
        });
        jb.rawset("autoConfigureContainerById", (JavaFunction) (cf, n) -> cf.push("STORAGE:P4:medical"));
        jb.rawset("getContainerSampleItemsById", (JavaFunction) (cf, n) -> {
            KahluaTable out = LuaManager.platform.newTable();
            String[][] samples = {
                {"Base.Scalpel", isCnLang ? "手術刀" : "Scalpel", "1", "medical.bandages"},
                {"Base.Bandage", isCnLang ? "繃帶" : "Bandage", "12", "medical.bandages"},
                {"Base.Antibiotics", isCnLang ? "抗生素" : "Antibiotics", "6", "medical.pills"},
                {"Base.RottenApple", isCnLang ? "腐爛蘋果" : "Rotten Apple", "2", "scrap.compost"}
            };
            for (int i = 0; i < samples.length; i++) {
                KahluaTable s = LuaManager.platform.newTable();
                s.rawset("fullType", samples[i][0]);
                s.rawset("displayName", samples[i][1]);
                s.rawset("count", Double.valueOf(Double.parseDouble(samples[i][2])));
                s.rawset("node", samples[i][3]);
                out.rawset(Double.valueOf(i + 1), s);
            }
            return cf.push(out);
        });
        jb.rawset("setContainerHighlight", (JavaFunction) (cf, n) -> 0);
        jb.rawset("clearContainerHighlight", (JavaFunction) (cf, n) -> 0);

        List<KahluaTable> mockOrders = new ArrayList<>();
        String[][] seedOrders = {
            {"ord_1", "Base.Bandage", "10", "4", "ASSIGNED", "npc_1", ""},
            {"ord_2", "Base.Plank", "24", "0", "PENDING", "", "108:104:0#crate:crate:0#0"},
            {"ord_3", "Base.Apple", "6", "6", "FALLBACK_PICKUP_OUT", "", ""},
            {"ord_4", "Base.Axe", "1", "1", "COMPLETED", "", ""}
        };
        for (String[] so : seedOrders) {
            KahluaTable ord = LuaManager.platform.newTable();
            ord.rawset("orderId", so[0]);
            ord.rawset("fullType", so[1]);
            ord.rawset("requestedCount", Double.valueOf(Double.parseDouble(so[2])));
            ord.rawset("deliveredCount", Double.valueOf(Double.parseDouble(so[3])));
            ord.rawset("status", so[4]);
            if (!so[5].isEmpty()) ord.rawset("assignedNpcId", so[5]);
            if (!so[6].isEmpty()) ord.rawset("targetPickupOutContainerId", so[6]);
            mockOrders.add(ord);
        }
        jb.rawset("listDeliveryOrders", (JavaFunction) (cf, n) -> {
            KahluaTable out = LuaManager.platform.newTable();
            for (int i = 0; i < mockOrders.size(); i++) {
                out.rawset(Double.valueOf(i + 1), mockOrders.get(i));
            }
            return cf.push(out);
        });
        jb.rawset("submitDeliveryOrder", (JavaFunction) (cf, n) -> cf.push("ord_new"));
        jb.rawset("submitDeliveryOrderToBox", (JavaFunction) (cf, n) -> cf.push("ord_box"));
        jb.rawset("cancelDeliveryOrder", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));

        jb.rawset("listNpcRoster", (JavaFunction) (cf, n) -> {
            KahluaTable out = LuaManager.platform.newTable();
            String[][] rosterData = {
                {"npc_1", isCnLang ? "艾莉絲" : "Alice Mercer", "true", "settlement_1", "SETTLEMENT", "ROOT/SETTLEMENT/WORK_DUTY", "98", "0.18", "0.22", "false"},
                {"npc_2", isCnLang ? "馬庫斯" : "Marcus Vance", "true", "settlement_1", "SQUAD", "ROOT/SQUAD/FOLLOW_LEADER", "84", "0.42", "0.35", "false"},
                {"npc_3", isCnLang ? "伊蓮娜" : "Elena Rostova", "true", "", "SETTLEMENT", "ROOT/SETTLEMENT/IDLE", "62", "0.74", "0.50", "true"},
                {"npc_4", isCnLang ? "薩米爾" : "Samir Patel", "false", "settlement_1", "SETTLEMENT", "OFFSCREEN", "100", "0.0", "0.0", "false"}
            };
            for (int i = 0; i < rosterData.length; i++) {
                KahluaTable r = LuaManager.platform.newTable();
                r.rawset("npcId", rosterData[i][0]);
                r.rawset("displayName", rosterData[i][1]);
                r.rawset("phasedIn", Boolean.valueOf(Boolean.parseBoolean(rosterData[i][2])));
                r.rawset("assignedSettlementId", rosterData[i][3]);
                r.rawset("brainMode", rosterData[i][4]);
                r.rawset("brainStatePath", rosterData[i][5]);
                r.rawset("health", Double.valueOf(Double.parseDouble(rosterData[i][6])));
                r.rawset("hunger", Double.valueOf(Double.parseDouble(rosterData[i][7])));
                r.rawset("fatigue", Double.valueOf(Double.parseDouble(rosterData[i][8])));
                r.rawset("bleeding", Boolean.valueOf(Boolean.parseBoolean(rosterData[i][9])));
                out.rawset(Double.valueOf(i + 1), r);
            }
            return cf.push(out);
        });
        jb.rawset("setNpcBrainMode", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));
        jb.rawset("assignNpcToSettlement", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));
        jb.rawset("getNpcId", (JavaFunction) (cf, n) -> cf.push(null));
        jb.rawset("isNpcAttached", (JavaFunction) (cf, n) -> cf.push(Boolean.FALSE));
        jb.rawset("getNpcDisplayName", (JavaFunction) (cf, n) -> cf.push(null));
        jb.rawset("getNpcObject", (JavaFunction) (cf, n) -> cf.push(null));
        jb.rawset("loadNpcsFromSave", (JavaFunction) (cf, n) -> cf.push(Double.valueOf(0)));
        jb.rawset("saveNpcsToSave", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));
        jb.rawset("tickNpcs", (JavaFunction) (cf, n) -> 0);
        jb.rawset("getNpcInfo", (JavaFunction) (cf, n) -> cf.push(null));
        jb.rawset("startNpcFollow", (JavaFunction) (cf, n) -> cf.push(Boolean.FALSE));
        jb.rawset("stopNpcFollow", (JavaFunction) (cf, n) -> cf.push(Boolean.FALSE));
        jb.rawset("isNpcFollowing", (JavaFunction) (cf, n) -> cf.push(Boolean.FALSE));
        jb.rawset("getNpcFollowState", (JavaFunction) (cf, n) -> cf.push("INACTIVE"));
        jb.rawset("getNpcBrainMode", (JavaFunction) (cf, n) -> cf.push("SETTLEMENT"));
        jb.rawset("getSquadTacticalConfig", (JavaFunction) (cf, n) -> {
            KahluaTable cfg = LuaManager.platform.newTable();
            cfg.rawset("formation", "WEDGE");
            cfg.rawset("spacing", "STANDARD");
            cfg.rawset("roe", "FREE_FIRE");
            cfg.rawset("cqb", Boolean.FALSE);
            cfg.rawset("orderMode", "FOLLOW");
            cfg.rawset("autoVehicle", Boolean.TRUE);
            cfg.rawset("vehicleOverride", "AUTO");
            cfg.rawset("version", Double.valueOf(1));
            cfg.rawset("anyInVehicle", Boolean.TRUE);
            cfg.rawset("hasHoldAnchor", Boolean.FALSE);
            return cf.push(cfg);
        });
        jb.rawset("setSquadFormation", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));
        jb.rawset("setSquadSpacing", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));
        jb.rawset("setSquadRoe", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));
        jb.rawset("setSquadCqb", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));
        jb.rawset("setSquadOrderMode", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));
        jb.rawset("setSquadAutoVehicle", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));
        jb.rawset("setSquadMemberRoe", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));
        jb.rawset("setSquadMemberCqb", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));
        jb.rawset("commandSquadVehicleToggle", (JavaFunction) (cf, n) -> cf.push("BOARDING"));
        jb.rawset("commandMemberExitVehicle", (JavaFunction) (cf, n) -> cf.push(Boolean.TRUE));
        jb.rawset("dismissSquadMember", (JavaFunction) (cf, n) -> cf.push("DISMISSED"));
        jb.rawset("listSquadMembers", (JavaFunction) (cf, n) -> cf.push(LuaManager.platform.newTable()));
        jb.rawset("getNpcPortraitTexture", (JavaFunction) (cf, n) -> cf.push(null));
        LuaManager.env.rawset("EmberRoutesJava", jb);

        String sceneCode = """
            local EmberUI = require "EmberUI"
            EmberUI.Theme.applyPreset("ae2")
            local Kernel = require "EmberRoutes/Kernel"
            local Store = require "EmberRoutes/Settlement/SettlementStore"
            local Geo = require "EmberRoutes/Settlement/SettlementGeometry"
            local Panel = require "EmberRoutes/UI/SettlementControlPanel"
            local Editor = require "EmberRoutes/UI/ZoneEditorPanel"
            Kernel.onWorldReady()
            local s = Store.createSettlement("Rosewood Outpost", 100, 100, 114, 114)
            Store.expandTerritory(s.id, 115, 100, 120, 108)
            Store.trimTerritory(s.id, 104, 104, 108, 108)
            s = Store.get(s.id)
            local win = Panel.open(s.id, "warehouse")
            win:setX(422)
            win:setY(50)
            local wTab = win.tabPanels and win.tabPanels.warehouse
            if wTab and wTab.ctrl and wTab.ctrl.filteredItems then
                if wTab.ctrl.filteredItems[1] then
                    wTab.ctrl:adjustBasketItem(wTab.ctrl.filteredItems[1], 10)
                end
                if wTab.ctrl.filteredItems[6] then
                    wTab.ctrl:adjustBasketItem(wTab.ctrl.filteredItems[6], 4)
                    wTab.ctrl:selectStorageItem(wTab.ctrl.filteredItems[6])
                end
            end
            local ed = Editor.open(s)
            ed:setX(18)
            ed:setY(30)
            ed:onModeErase()
            ed.dragging = true
            ed.dragRect = Geo.rectangle(100, 110, 104, 114)
            ed:pushEditorRenderState()
            ed.statusDirty = true
            _G.__previewWin = win
            _G.__previewEd = ed
            """;
        pcallLua(sceneCode);
        waitForAssets();

        renderPreviewFrames(outDir.resolve("preview-" + lang + ".png"), true);

        pcallLua("""
            local win = _G.__previewWin
            assert(win.win.contentHost.errorStage == false, "SettlementControlPanel root layout must solve cleanly: " .. tostring(win.win.contentHost.errorStage))
            local wTab = win.tabPanels and win.tabPanels.warehouse
            assert(wTab and wTab.errorStage == false, "Warehouse tab must solve cleanly")
            assert(wTab.ctrl.split.firstPane.errorStage == false, "AE2 left pane must solve at default 840x540")
            assert(wTab.ctrl.split.secondPane.errorStage == false, "AE2 right pane must solve cleanly at default 840x540")
            """);

        pcallLua("""
            local Kernel = require "EmberRoutes/Kernel"
            local Editor = require "EmberRoutes/UI/ZoneEditorPanel"
            assert(ISWorldObjectContextMenu.disableWorldMenu == true, "Editor must lock disableWorldMenu")
            Kernel.teardown()
            assert(_G.__previewEd.win.disposed == true, "Kernel.teardown must close ZoneEditorPanel via attachExternalScope")
            assert(_G.__previewWin.win.disposed == true, "Kernel.teardown must close SettlementControlPanel via attachExternalScope")
            assert(not ISWorldObjectContextMenu.disableWorldMenu, "Kernel.teardown must restore disableWorldMenu")
            assert(Editor.getActive() == nil, "Editor active handle must clear on teardown")
            _G.__previewWin = nil
            _G.__previewEd = nil
            """);

        // Scene 2: ContainerConfigWizard (centered, generous dimensions) + SettlementControlPanel Roster/Containers
        pcallLua("""
            local Kernel = require "EmberRoutes/Kernel"
            local Store = require "EmberRoutes/Settlement/SettlementStore"
            local Panel = require "EmberRoutes/UI/SettlementControlPanel"
            local Wizard = require "EmberRoutes/UI/ContainerConfigWizard"
            Kernel.onWorldReady()
            local existing = Store.list()
            local s = (existing and existing[1]) or Store.createSettlement("Rosewood Outpost", 100, 100, 114, 114)
            local panel = Panel.open(s.id, "roster")
            panel.win:setWidth(820)
            panel.win:setHeight(540)
            panel:setX(230)
            panel:setY(40)
            panel:forceRefresh()
            _G.__previewPanel = panel
            """);
        waitForAssets();
        renderPreviewFrames(outDir.resolve("preview-roster-" + lang + ".png"), false);

        pcallLua("""
            local Wizard = require "EmberRoutes/UI/ContainerConfigWizard"
            _G.__previewPanel:selectTab("containers")
            _G.__previewPanel:forceRefresh()
            """);
        renderPreviewFrames(outDir.resolve("preview-containers-" + lang + ".png"), false);

        pcallLua("""
            local Wizard = require "EmberRoutes/UI/ContainerConfigWizard"
            _G.__previewPanel.win:setVisible(false)
            local wiz = Wizard.openForSettlementContainer(_G.__previewPanel.cachedSettlementId, "110:102:0#fridge:fridge:0#0")
            wiz.win:setWidth(780)
            wiz.win:setHeight(540)
            wiz:setX(250)
            wiz:setY(40)
            _G.__previewWiz = wiz
            """);
        waitForAssets();
        renderPreviewFrames(outDir.resolve("preview-wizard-" + lang + ".png"), false);

        pcallLua("""
            local Kernel = require "EmberRoutes/Kernel"
            local Wizard = require "EmberRoutes/UI/ContainerConfigWizard"
            _G.__previewPanel.win:setVisible(true)
            Kernel.teardown()
            assert(_G.__previewWiz.win.disposed == true, "Kernel.teardown must close ContainerConfigWizard")
            assert(_G.__previewPanel.win.disposed == true, "Kernel.teardown must close SettlementControlPanel")
            assert(Wizard.getActive() == nil, "Wizard active handle must clear on teardown")
            _G.__previewWiz = nil
            _G.__previewPanel = nil
            """);

        renderNpcInventoryPreview(outDir, lang);
        renderSquadHudPreview(outDir, lang);
    }

    private static void renderPreviewFrames(Path outFile, boolean drawZonePolygon) throws Exception {
        for (int frame = 0; frame < 4; frame++) {
            Display.processMessages();
            GameWindow.fileSystem.updateAsyncTransactions();
            final int fIdx = frame;
            RenderThread.invokeOnRenderContext(() -> {
                GL11.glViewport(0, 0, WIDTH, HEIGHT);
                GL11.glClearColor(0.10f, 0.12f, 0.14f, 1f);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                SpriteRenderer r = SpriteRenderer.instance;
                r.prePopulating();
                PreviewFrame.resetStencilDepth();
                r.glDoStartFrame(WIDTH, HEIGHT, 1, -1);
                r.StartShader(SceneShaderStore.defaultShaderId, -1);

                PreviewFrame.flushPendingUI();
                if (drawZonePolygon) {
                    drawIrregularZonePreview(r);
                }
                for (var el : new ArrayList<>(UIManager.getUI())) {
                    if (el.isVisible()) {
                        el.update();
                        el.render();
                    }
                }

                r.glDoEndFrame();
                r.pushFrameDown();
                r.acquireStateForRendering(() -> false);
                r.postRender();

                if (fIdx == 3) {
                    capturePng(outFile);
                }
                Display.update();
            });
            Display.sync(30);
        }
    }

    private static void renderNpcInventoryPreview(Path outDir, String lang) throws Exception {
        boolean isCn = "CN".equals(lang);
        KahluaTable mock = LuaManager.platform.newTable();
        mock.rawset("hdrType", isCn ? "類型" : "Type");
        mock.rawset("hdrCat", isCn ? "分類" : "Category");
        mock.rawset("npc1", isCn ? "艾莉絲" : "Alice Mercer");
        mock.rawset("bag1", isCn ? "登山包" : "Hiking Bag");
        mock.rawset("npc2", isCn ? "馬庫斯" : "Marcus Vance");
        mock.rawset("bag2", isCn ? "主物品欄" : "Main Inventory");
        String[][] mockRowData = isCn ? new String[][] {
            {"消防斧", "武器", "0.75", "0.45", "0.25"},
            {"急救繃帶 x4", "醫療", "0.85", "0.32", "0.32"},
            {"消毒酒精", "醫療", "0.35", "0.72", "0.90"},
            {"罐頭豆子", "食物", "0.45", "0.80", "0.45"},
            {"手電筒", "裝備", "0.90", "0.78", "0.30"},
        } : new String[][] {
            {"Fire Axe", "Weapon", "0.75", "0.45", "0.25"},
            {"Bandage x4", "Medical", "0.85", "0.32", "0.32"},
            {"Disinfectant", "Medical", "0.35", "0.72", "0.90"},
            {"Canned Beans", "Food", "0.45", "0.80", "0.45"},
            {"Flashlight", "Gear", "0.90", "0.78", "0.30"},
        };
        KahluaTable mockRows = LuaManager.platform.newTable();
        for (int i = 0; i < mockRowData.length; i++) {
            KahluaTable r = LuaManager.platform.newTable();
            r.rawset("name", mockRowData[i][0]);
            r.rawset("cat", mockRowData[i][1]);
            r.rawset("r", Double.valueOf(Double.parseDouble(mockRowData[i][2])));
            r.rawset("g", Double.valueOf(Double.parseDouble(mockRowData[i][3])));
            r.rawset("b", Double.valueOf(Double.parseDouble(mockRowData[i][4])));
            mockRows.rawset(Double.valueOf(i + 1), r);
        }
        mock.rawset("rows", mockRows);
        LuaManager.env.rawset("__previewInvMock", mock);

        pcallLua("""
            local EmberUI = require "EmberUI"
            local Kernel = require "EmberRoutes/Kernel"
            local NpcInv = require "EmberRoutes/UI/NpcInventoryPanel"
            Kernel.onWorldReady()
            local mock = _G.__previewInvMock

            local function makeMockInventoryWidget(bagSelected)
                local p = ISPanel:new(0, 0, 380, 420)
                p.background = false
                p:initialise()
                local hdrType = mock.hdrType
                local hdrCat = mock.hdrCat
                local rows = mock.rows
                p.render = function(self)
                    local w, h = self:getWidth(), self:getHeight()
                    local btnSize = 36
                    local paneW = w - btnSize
                    self:drawRect(0, 0, w, h, 0.78, 0.04, 0.05, 0.07)
                    self:drawRect(paneW, 0, btnSize, h, 0.70, 0.03, 0.04, 0.05)
                    self:drawRect(0, 0, paneW, 20, 0.85, 0.09, 0.11, 0.14)
                    self:drawText(hdrType, 36, 3, 0.82, 0.85, 0.90, 1.0, UIFont.Small)
                    local catX = math.max(170, paneW - 92)
                    self:drawText(hdrCat, catX, 3, 0.82, 0.85, 0.90, 1.0, UIFont.Small)
                    self:drawRectBorder(0, 0, paneW, 20, 0.55, 0.32, 0.35, 0.42)
                    for idx = 1, #rows do
                        local row = rows[idx]
                        local ry = 20 + (idx - 1) * 24
                        if idx == 2 then
                            self:drawRect(1, ry, paneW - 2, 23, 0.32, 0.24, 0.48, 0.72)
                        elseif idx % 2 == 0 then
                            self:drawRect(1, ry, paneW - 2, 23, 0.35, 0.07, 0.09, 0.12)
                        end
                        self:drawRect(8, ry + 4, 16, 16, 0.90, row.r, row.g, row.b)
                        self:drawText(row.name, 32, ry + 4, 0.92, 0.94, 0.96, 1.0, UIFont.Small)
                        self:drawText(row.cat, catX, ry + 4, 0.65, 0.70, 0.78, 1.0, UIFont.Small)
                    end
                    for sIdx = 1, 2 do
                        local sy = (sIdx - 1) * btnSize + 4
                        if (bagSelected and sIdx == 2) or (not bagSelected and sIdx == 1) then
                            self:drawRect(paneW + 3, sy, btnSize - 6, btnSize - 6, 0.65, 0.45, 0.48, 0.54)
                        end
                        self:drawRectBorder(paneW + 3, sy, btnSize - 6, btnSize - 6, 0.60, 0.38, 0.42, 0.50)
                        local tag = sIdx == 1 and "INV" or "BAG"
                        self:drawText(tag, paneW + 7, sy + 9, 0.88, 0.90, 0.94, 1.0, UIFont.Small)
                    end
                    self:drawRectBorder(paneW, 0, btnSize, h, 0.55, 0.35, 0.38, 0.44)
                    self:drawRectBorder(0, 0, w, h, 0.75, 0.32, 0.35, 0.42)
                end
                return p
            end

            local c1 = NpcInv.displayPanel(nil, {}, {
                npcId = "npc_prev_1",
                displayName = mock.npc1,
                x = 50, y = 20, width = 400, height = 500,
                previewBlocked = false,
                previewContainerName = mock.bag1,
                previewContainerWeight = 12.4,
                previewContainerCapacity = 22,
                previewWidget = makeMockInventoryWidget(true),
            })
            local c2 = NpcInv.displayPanel(nil, {}, {
                npcId = "npc_prev_2",
                displayName = mock.npc2,
                x = 490, y = 20, width = 380, height = 500,
                previewBlocked = true,
                previewContainerName = mock.bag2,
                previewContainerWeight = 6.2,
                previewContainerCapacity = 50,
                previewWidget = makeMockInventoryWidget(false),
            })
            _G.__previewInv1 = c1
            _G.__previewInv2 = c2
            _G.__previewInvMock = nil
            """);

        renderPreviewFrames(outDir.resolve("preview-npc-inv-" + lang + ".png"), false);

        pcallLua("""
            local Kernel = require "EmberRoutes/Kernel"
            local NpcInv = require "EmberRoutes/UI/NpcInventoryPanel"
            Kernel.teardown()
            assert(_G.__previewInv1.win.disposed == true, "Kernel.teardown must close NpcInventoryPanel 1")
            assert(_G.__previewInv2.win.disposed == true, "Kernel.teardown must close NpcInventoryPanel 2")
            assert(NpcInv.getActive("npc_prev_1") == nil and NpcInv.getActive("npc_prev_2") == nil,
                "Active NPC inventory map must clear on teardown")
            _G.__previewInv1 = nil
            _G.__previewInv2 = nil
            """);
    }

    private static void renderSquadHudPreview(Path outDir, String lang) throws Exception {
        boolean isCn = "CN".equals(lang);
        KahluaTable mock = LuaManager.platform.newTable();
        mock.rawset("noticeFull", isCn ? "目標載具乘客位已滿，無法繼續登車" : "Vehicle seats are all occupied");
        KahluaTable members = LuaManager.platform.newTable();
        Object[][] rows = {
            {"npc_1", isCn ? "馬庫斯" : "Marcus Vance", isCn ? "尖兵壹號" : "Vanguard-1", true, 96.0, false, false, "MELEE", "MELEE", isCn ? "消防斧" : "Fire Axe", "FREE_FIRE", false, true, true, "FOLLOWING", false, -1},
            {"npc_2", isCn ? "伊蓮娜" : "Elena Rostova", isCn ? "伊蓮娜" : "Elena", true, 54.0, false, true, "MEDIC", "UNARMED", "", "RETURN_FIRE", true, false, false, "HOLDING", false, -1},
            {"npc_3", isCn ? "艾莉絲" : "Alice Mercer", isCn ? "渡鴉-2" : "Raven-2", true, 24.0, true, true, "RANGED", "FIREARM", isCn ? "M9手槍" : "M9 Pistol", "FREE_FIRE", false, false, false, "RIDING", true, 2},
            {"npc_4", isCn ? "薩米爾" : "Samir Patel", isCn ? "薩米爾" : "Samir", false, 100.0, false, false, "SUPPORT", "MELEE", isCn ? "撬棍" : "Crowbar", "HOLD_FIRE", true, false, false, "OFFSCREEN", false, -1},
            {"npc_5", isCn ? "諾亞" : "Noah Brooks", isCn ? "重錘" : "Anvil", true, 82.0, false, false, "MELEE", "MELEE", isCn ? "撬棍" : "Crowbar", "FREE_FIRE", false, true, false, "BOARDING", false, -1}
        };
        for (int i = 0; i < rows.length; i++) {
            Object[] rd = rows[i];
            KahluaTable m = LuaManager.platform.newTable();
            m.rawset("npcId", rd[0]);
            m.rawset("displayName", rd[1]);
            m.rawset("shownName", rd[2]);
            m.rawset("callsign", rd[2]);
            m.rawset("phasedIn", rd[3]);
            m.rawset("health", rd[4]);
            m.rawset("bleeding", rd[5]);
            m.rawset("injured", rd[6]);
            m.rawset("tacticalRole", rd[7]);
            m.rawset("weaponType", rd[8]);
            m.rawset("weaponName", rd[9]);
            m.rawset("effectiveRoe", rd[10]);
            m.rawset("roeOverridden", rd[11]);
            m.rawset("effectiveCqb", rd[12]);
            m.rawset("cqbOverridden", rd[13]);
            m.rawset("activityState", rd[14]);
            m.rawset("inVehicle", rd[15]);
            m.rawset("vehicleSeat", Double.valueOf(((Integer) rd[16]).doubleValue()));
            m.rawset("pendingDismiss", Boolean.valueOf(i == 2));
            members.rawset(Double.valueOf(i + 1), m);
        }
        mock.rawset("members", members);
        LuaManager.env.rawset("__previewSquadMock", mock);

        pcallLua("""
            local EmberUI = require "EmberUI"
            EmberUI.Theme.applyPreset("ae2")
            local Kernel = require "EmberRoutes/Kernel"
            require "EmberRoutes/Squad/SquadKeys"
            require "EmberRoutes/Squad/SquadService"
            local SquadHUD = require "EmberRoutes/UI/SquadHUD"
            require "EmberRoutes/UI/SquadWheel"
            Kernel.onWorldReady()

            local mock = _G.__previewSquadMock
            local fourMembers = { mock.members[1], mock.members[2], mock.members[3], mock.members[4] }
            local pExpanded = SquadHUD.openPreview({
                x = 60, y = 36,
                collapsed = false,
                config = {
                    formation = "WEDGE",
                    spacing = "STANDARD",
                    roe = "FREE_FIRE",
                    cqb = false,
                    orderMode = "FOLLOW",
                    autoVehicle = true,
                    anyInVehicle = true,
                },
                members = fourMembers,
            })
            local pCollapsed = SquadHUD.openPreview({
                x = 420, y = 36,
                collapsed = true,
                config = {
                    formation = "RING",
                    spacing = "WIDE",
                    roe = "RETURN_FIRE",
                    cqb = true,
                    orderMode = "HOLD_POSITION",
                    autoVehicle = false,
                    anyInVehicle = false,
                },
                members = mock.members,
            })
            local pScrollNotice = SquadHUD.openPreview({
                x = 780, y = 36,
                collapsed = false,
                noticeText = mock.noticeFull,
                noticeIsError = true,
                config = {
                    formation = "COLUMN",
                    spacing = "TIGHT",
                    roe = "HOLD_FIRE",
                    cqb = true,
                    orderMode = "HOLD_POSITION",
                    autoVehicle = true,
                    anyInVehicle = false,
                },
                members = mock.members,
            })
            _G.__previewSquad1 = pExpanded
            _G.__previewSquad2 = pCollapsed
            _G.__previewSquad3 = pScrollNotice
            _G.__previewSquadMock = nil
            """);
        waitForAssets();

        renderPreviewFrames(outDir.resolve("preview-squad-hud-" + lang + ".png"), false);

        pcallLua("""
            local Kernel = require "EmberRoutes/Kernel"
            Kernel.teardown()
            assert(_G.__previewSquad1.disposed == true, "Kernel.teardown must close expanded SquadHUD")
            assert(_G.__previewSquad2.disposed == true, "Kernel.teardown must close collapsed SquadHUD")
            assert(_G.__previewSquad3.disposed == true, "Kernel.teardown must close scroll SquadHUD")
            _G.__previewSquad1 = nil
            _G.__previewSquad2 = nil
            _G.__previewSquad3 = nil
            """);
    }

    private static final Map<Character, Character> PREVIEW_S2T = Map.ofEntries(
        Map.entry('余', '餘'), Map.entry('烬', '燼'), Map.entry('实', '實'), Map.entry('时', '時'),
        Map.entry('仓', '倉'), Map.entry('储', '儲'), Map.entry('与', '與'), Map.entry('划', '劃'),
        Map.entry('区', '區'), Map.entry('监', '監'), Map.entry('领', '領'), Map.entry('闭', '閉'),
        Map.entry('顶', '頂'), Map.entry('场', '場'), Map.entry('离', '離'), Map.entry('总', '總'),
        Map.entry('数', '數'), Map.entry('载', '載'), Map.entry('订', '訂'), Map.entry('号', '號'),
        Map.entry('计', '計'), Map.entry('脏', '髒'), Map.entry('静', '靜'), Map.entry('轮', '輪'),
        Map.entry('询', '詢'), Map.entry('暂', '暫'), Map.entry('无', '無'), Map.entry('据', '據'),
        Map.entry('点', '點'), Map.entry('检', '檢'), Map.entry('测', '測'), Map.entry('创', '創'),
        Map.entry('编', '編'), Map.entry('辑', '輯'), Map.entry('删', '刪'), Map.entry('扫', '掃'),
        Map.entry('块', '塊'), Map.entry('层', '層'), Map.entry('资', '資'), Map.entry('单', '單'),
        Map.entry('撤', '撤'), Map.entry('销', '銷'), Map.entry('标', '標'), Map.entry('鼠', '鼠'),
        Map.entry('按', '按'), Map.entry('键', '鍵'), Map.entry('动', '動'), Map.entry('框', '框'),
        Map.entry('开', '開'), Map.entry('落', '落'), Map.entry('楼', '樓'), Map.entry('盘', '盤'),
        Map.entry('当', '當'), Map.entry('积', '積'), Map.entry('贯', '貫'), Map.entry('预', '預'),
        Map.entry('览', '覽'), Map.entry('扩', '擴'), Map.entry('松', '鬆'), Map.entry('态', '態'),
        Map.entry('唤', '喚'), Map.entry('节', '節'), Map.entry('损', '損'), Map.entry('苹', '蘋'),
        Map.entry('随', '隨'), Map.entry('医', '醫'), Map.entry('疗', '療'), Map.entry('栏', '欄'),
        Map.entry('远', '遠'), Map.entry('内', '內'), Map.entry('绷', '繃'), Map.entry('带', '帶'),
        Map.entry('头', '頭'), Map.entry('丝', '絲'), Map.entry('马', '馬'), Map.entry('库', '庫'),
        Map.entry('类', '類'), Map.entry('别', '別'), Map.entry('电', '電'), Map.entry('装', '裝'),
        Map.entry('备', '備'), Map.entry('拣', '揀'), Map.entry('员', '員'),
        Map.entry('册', '冊'), Map.entry('边', '邊'), Map.entry('关', '關'), Map.entry('题', '題'),
        Map.entry('篮', '籃'), Map.entry('状', '狀'), Map.entry('选', '選'), Map.entry('项', '項'),
        Map.entry('药', '藥'), Map.entry('枪', '槍'), Map.entry('弹', '彈'), Map.entry('杂', '雜'),
        Map.entry('废', '廢'), Map.entry('烂', '爛'), Map.entry('冲', '衝'),
        Map.entry('险', '險'), Map.entry('转', '轉'), Map.entry('护', '護'), Map.entry('导', '導'),
        Map.entry('规', '規'), Map.entry('则', '則'), Map.entry('复', '複'), Map.entry('贴', '貼'),
        Map.entry('启', '啟'), Map.entry('设', '設'), Map.entry('优', '優'), Map.entry('树', '樹'),
        Map.entry('循', '循'), Map.entry('环', '環'), Map.entry('换', '換'), Map.entry('样', '樣'),
        Map.entry('饥', '飢'), Map.entry('满', '滿'), Map.entry('终', '終'), Map.entry('端', '端'),
        Map.entry('毁', '毀'), Map.entry('细', '細'), Map.entry('调', '調'), Map.entry('缝', '縫'),
        Map.entry('针', '針'), Map.entry('线', '線'), Map.entry('农', '農'), Map.entry('种', '種'),
        Map.entry('铁', '鐵'), Map.entry('处', '處'), Map.entry('属', '屬'), Map.entry('对', '對'),
        Map.entry('应', '應'), Map.entry('将', '將'), Map.entry('从', '從'), Map.entry('为', '為'),
        Map.entry('书', '書'), Map.entry('刊', '刊'), Map.entry('报', '報'), Map.entry('纸', '紙'),
        Map.entry('体', '體'), Map.entry('专', '專'), Map.entry('门', '門'), Map.entry('进', '進'),
        Map.entry('还', '還'), Map.entry('运', '運'), Map.entry('过', '過'), Map.entry('达', '達'),
        Map.entry('给', '給'), Map.entry('级', '級'), Map.entry('组', '組'), Map.entry('织', '織'),
        Map.entry('统', '統'), Map.entry('维', '維'), Map.entry('网', '網'), Map.entry('络', '絡'),
        Map.entry('称', '稱'), Map.entry('钉', '釘'), Map.entry('锯', '鋸'), Map.entry('锤', '錘'),
        Map.entry('严', '嚴'), Map.entry('仅', '僅'), Map.entry('会', '會'), Map.entry('伤', '傷'),
        Map.entry('准', '準'), Map.entry('击', '擊'), Map.entry('卫', '衛'), Map.entry('发', '發'),
        Map.entry('围', '圍'), Map.entry('战', '戰'), Map.entry('术', '術'), Map.entry('独', '獨'), Map.entry('现', '現'),
        Map.entry('紧', '緊'), Map.entry('纵', '縱'), Map.entry('续', '續'), Map.entry('职', '職'),
        Map.entry('补', '補'), Map.entry('记', '記'), Map.entry('请', '請'), Map.entry('车', '車'),
        Map.entry('辆', '輛'), Map.entry('阵', '陣'), Map.entry('隐', '隱'), Map.entry('显', '顯'),
        Map.entry('驶', '駛'), Map.entry('驻', '駐'), Map.entry('稳', '穩'), Map.entry('队', '隊'),
        Map.entry('悬', '懸'), Map.entry('义', '義'), Map.entry('莲', '蓮'), Map.entry('萨', '薩'),
        Map.entry('尔', '爾'), Map.entry('条', '條'), Map.entry('试', '試'), Map.entry('误', '誤'),
        Map.entry('认', '認'), Map.entry('许', '許'), Map.entry('词', '詞'), Map.entry('逻', '邏'),
        Map.entry('联', '聯'), Map.entry('团', '團'), Map.entry('压', '壓'), Map.entry('极', '極'),
        Map.entry('气', '氣'), Map.entry('简', '簡'), Map.entry('筛', '篩'), Map.entry('粮', '糧'),
        Map.entry('纳', '納'), Map.entry('结', '結'), Map.entry('继', '繼'), Map.entry('缓', '緩'),
        Map.entry('这', '這'), Map.entry('间', '間'), Map.entry('鲜', '鮮')
    );

    private static float isoX(float gx, float gy) {
        return 236f + (gx - gy) * 11.5f;
    }

    private static float isoY(float gx, float gy) {
        return 258f + (gx + gy) * 5.75f;
    }

    /** 在左下角渲染不规则 L 型 + 回字形天井挖空领地的无内缝填充与外轮廓预览。 */
    private static void drawIrregularZonePreview(SpriteRenderer r) {
        List<int[]> rects = List.of(
            new int[]{0, 0, 10, 4},
            new int[]{0, 8, 10, 4},
            new int[]{0, 4, 3, 4},
            new int[]{7, 4, 7, 4},
            new int[]{10, 0, 4, 4}
        );
        int[] quads = TerritoryContour.buildSeamlessFillQuads(rects);
        for (int i = 0; i + 3 < quads.length; i += 4) {
            float x1 = quads[i], y1 = quads[i + 1], x2 = quads[i + 2], y2 = quads[i + 3];
            r.renderPoly(
                isoX(x1, y1), isoY(x1, y1),
                isoX(x2, y1), isoY(x2, y1),
                isoX(x2, y2), isoY(x2, y2),
                isoX(x1, y2), isoY(x1, y2),
                0.18f, 0.85f, 0.54f, 0.25f
            );
        }
        float[] segs = TerritoryContour.extractOutlineSegments(rects);
        for (int i = 0; i + 3 < segs.length; i += 4) {
            r.renderlinef(
                null,
                isoX(segs[i], segs[i + 1]), isoY(segs[i], segs[i + 1]),
                isoX(segs[i + 2], segs[i + 3]), isoY(segs[i + 2], segs[i + 3]),
                0.25f, 0.95f, 0.58f, 0.96f, 2
            );
        }
    }

    private static final Pattern PREVIEW_FMT_TOKEN = Pattern.compile("%%|%([1-9])");

    private static String formatPreviewText(String raw, String lang, se.krka.kahlua.vm.LuaCallFrame frame, int count) {
        if (raw == null) return "";
        Matcher m = PREVIEW_FMT_TOKEN.matcher(raw);
        StringBuilder sb = new StringBuilder(raw.length());
        while (m.find()) {
            String g = m.group(1);
            if (g == null) {
                m.appendReplacement(sb, "%");
            } else {
                int idx = Integer.parseInt(g);
                String repl = idx < count ? String.valueOf(frame.get(idx)) : m.group(0);
                m.appendReplacement(sb, Matcher.quoteReplacement(repl));
            }
        }
        m.appendTail(sb);
        String out = sb.toString();
        return "CN".equals(lang) ? previewS2T(out) : out;
    }

    private static String previewS2T(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(PREVIEW_S2T.getOrDefault(c, c));
        }
        return sb.toString();
    }

    private static void pcallLua(String code) throws IOException {
        Object[] res = LuaManager.thread.pcall(LuaCompiler.loadstring(code, "preview-scene", LuaManager.env), new Object[0]);
        if (!Boolean.TRUE.equals(res[0])) {
            Throwable cause = (res.length > 3 && res[3] instanceof Throwable t) ? t : null;
            if (cause == null && res.length > 1 && res[1] instanceof Throwable t) cause = t;
            String msg = "Lua error: " + (res.length > 1 ? res[1] : "")
                + (res.length > 2 ? "\n" + res[2] : "")
                + (cause != null ? "\ncause=" + cause : "");
            throw new IllegalStateException(msg, cause);
        }
    }

    private static void capturePng(Path file) {
        try {
            ByteBuffer pixels = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
            GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int i = ((HEIGHT - 1 - y) * WIDTH + x) * 4;
                    int argb = ((pixels.get(i + 3) & 255) << 24)
                            | ((pixels.get(i) & 255) << 16)
                            | ((pixels.get(i + 1) & 255) << 8)
                            | (pixels.get(i + 2) & 255);
                    img.setRGB(x, y, argb);
                }
            }
            ImageIO.write(img, "png", file.toFile());
            System.out.println("[PreviewModule1] Captured " + file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
