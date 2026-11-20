import emberroutes.warehouse.TerritoryContour;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
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
 * 同时打开新版的「左键拖框划区编辑器 ZoneEditorPanel」与「实时仓储与据点面板 DebugWarehousePanel」，
 * 导出 CN 与 EN 真实帧缓冲截图核验无压字、无重叠、无裁剪溢出。
 */
public final class PreviewModule1 {
    private static final int WIDTH = 980;
    private static final int HEIGHT = 540;

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
        for (String jf : List.of("ContextMenu.json", "IG_UI.json")) {
            JSONObject sub = new JSONObject(Files.readString(transDir.resolve(jf), StandardCharsets.UTF_8));
            for (String k : sub.keySet()) dict.put(k, sub.getString(k));
        }

        Object origGetText = LuaManager.env.rawget("getText");
        LuaManager.env.rawset("getText", (JavaFunction) (frame, count) -> {
            String key = String.valueOf(frame.get(0));
            if (dict.has(key)) {
                String v = dict.getString(key);
                for (int i = 1; i < count; i++) {
                    v = v.replace("%" + i, String.valueOf(frame.get(i)));
                }
                return frame.push("CN".equals(lang) ? previewS2T(v) : v);
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
                String v = dict.getString(key);
                for (int i = 1; i < count; i++) {
                    v = v.replace("%" + i, String.valueOf(frame.get(i)));
                }
                return frame.push("CN".equals(lang) ? previewS2T(v) : v);
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
        jb.rawset("getWarehouseSnapshot", (JavaFunction) (cf, n) -> {
            KahluaTable snap = LuaManager.platform.newTable();
            KahluaTable counts = LuaManager.platform.newTable();
            counts.rawset("loaded", Double.valueOf(8));
            counts.rawset("unloaded", Double.valueOf(2));
            counts.rawset("privateCount", Double.valueOf(1));
            counts.rawset("items", Double.valueOf(128));
            counts.rawset("usedWeight", Double.valueOf(184.6));
            counts.rawset("totalCapacity", Double.valueOf(480.0));
            snap.rawset("counts", counts);

            KahluaTable rows = LuaManager.platform.newTable();
            String[][] sampleRows = {
                {"tools", "Base.Axe", "3", "3", "0", "2"},
                {"materials", "Base.Plank", "48", "48", "0", "3"},
                {"food.perishable", "Base.Apple", "24", "18", "6", "2"},
                {"food.stable", "Base.TinnedBeans", "24", "24", "0", "2"},
                {"medical", "Base.Bandage", "15", "15", "0", "1"},
                {"weapons.ammo", "Base.Bullets9mm", "14", "14", "0", "1"}
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
        LuaManager.env.rawset("EmberRoutesJava", jb);

        String sceneCode = """
            local EmberUI = require "EmberUI"
            EmberUI.Theme.applyPreset("ae2")
            local Kernel = require "EmberRoutes/Kernel"
            local Store = require "EmberRoutes/Settlement/SettlementStore"
            local Geo = require "EmberRoutes/Settlement/SettlementGeometry"
            local Panel = require "EmberRoutes/UI/DebugWarehousePanel"
            local Editor = require "EmberRoutes/UI/ZoneEditorPanel"
            Kernel.onWorldReady()
            local s = Store.createSettlement("Rosewood Outpost", 100, 100, 114, 114)
            Store.expandTerritory(s.id, 115, 100, 120, 108)
            Store.trimTerritory(s.id, 104, 104, 108, 108)
            s = Store.get(s.id)
            local win = Panel.open(s.id)
            win:setX(466)
            win:setY(36)
            local ed = Editor.open(s)
            ed:setX(20)
            ed:setY(36)
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
                drawIrregularZonePreview(r);
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
                    capturePng(outDir.resolve("preview-" + lang + ".png"));
                }
                Display.update();
            });
            Display.sync(30);
        }

        pcallLua("""
            local Kernel = require "EmberRoutes/Kernel"
            local Editor = require "EmberRoutes/UI/ZoneEditorPanel"
            assert(ISWorldObjectContextMenu.disableWorldMenu == true, "Editor must lock disableWorldMenu")
            Kernel.teardown()
            assert(_G.__previewEd.win.disposed == true, "Kernel.teardown must close ZoneEditorPanel via attachExternalScope")
            assert(_G.__previewWin.win.disposed == true, "Kernel.teardown must close DebugWarehousePanel via attachExternalScope")
            assert(not ISWorldObjectContextMenu.disableWorldMenu, "Kernel.teardown must restore disableWorldMenu")
            assert(Editor.getActive() == nil, "Editor active handle must clear on teardown")
            _G.__previewWin = nil
            _G.__previewEd = nil
            """);

        renderNpcInventoryPreview(outDir, lang);
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
                    capturePng(outDir.resolve("preview-npc-inv-" + lang + ".png"));
                }
                Display.update();
            });
            Display.sync(30);
        }

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
        Map.entry('备', '備')
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
