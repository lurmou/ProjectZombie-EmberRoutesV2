package emberroutes.warehouse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.VBORenderer;
import zombie.core.sprite.SpriteRenderState;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.SpriteRenderer;
import zombie.debug.LineDrawer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;

/**
 * 零 GC 不规则领地世界渲染器：
 *
 * <ol>
 *   <li>外轮廓矢量线：预计算去除了全部内部拼接缝的 {@link TerritoryContour#extractOutlineSegments}，
 *       在世界相机离屏 FBO 阶段（{@code LineDrawer.render @OnExit}）绘制，任意滚轮缩放比例 100% 贴合地板且每帧 0 Lua 调用；</li>
 *   <li>编辑态 3D 地面深度半透明铺底：在 {@code FBORenderAreaHighlights.render @OnExit} 阶段通过
 *       {@link VBORenderer#addQuadDepth} 批量绘制无 T 型裂缝的不重叠地面色块，人物、墙体、箱子拥有原生 3D 深度遮挡，
 *       且绕开原版强制画白色子矩形内框的缺陷。</li>
 * </ol>
 */
public final class TerritoryRenderer {
    private TerritoryRenderer() {}

    static final class RenderShape {
        final String settlementId;
        final int[] fillQuads;         // [x1, y1, x2, y2, ...]
        final float[] outlineSegments; // [x1, y1, x2, y2, ...]
        final int minX, minY, maxX, maxY;

        RenderShape(String settlementId, List<int[]> rects) {
            this.settlementId = settlementId;
            this.fillQuads = TerritoryContour.buildSeamlessFillQuads(rects);
            this.outlineSegments = TerritoryContour.extractOutlineSegments(rects);
            int lx = Integer.MAX_VALUE, ly = Integer.MAX_VALUE;
            int hx = Integer.MIN_VALUE, hy = Integer.MIN_VALUE;
            for (int i = 0; i + 3 < fillQuads.length; i += 4) {
                if (fillQuads[i] < lx) lx = fillQuads[i];
                if (fillQuads[i + 1] < ly) ly = fillQuads[i + 1];
                if (fillQuads[i + 2] > hx) hx = fillQuads[i + 2];
                if (fillQuads[i + 3] > hy) hy = fillQuads[i + 3];
            }
            this.minX = lx;
            this.minY = ly;
            this.maxX = hx;
            this.maxY = hy;
        }

        boolean isEmpty() {
            return fillQuads.length == 0;
        }

        boolean intersectsChunkMap(int cmMinX, int cmMinY, int cmMaxX, int cmMaxY) {
            return !isEmpty() && minX < cmMaxX && maxX > cmMinX && minY < cmMaxY && maxY > cmMinY;
        }
    }

    private static final class EditorSnapshot {
        final String editingSettlementId;
        final int mode; // 0 = add, 1 = erase
        final RenderShape previewShape;
        final boolean hasDrag;
        final int dragX1, dragY1, dragX2, dragY2;
        final boolean hasCursor;
        final int cursorX, cursorY;

        EditorSnapshot(
                String editingSettlementId,
                int mode,
                RenderShape previewShape,
                boolean hasDrag,
                int dragX1, int dragY1, int dragX2, int dragY2,
                boolean hasCursor,
                int cursorX, int cursorY) {
            this.editingSettlementId = editingSettlementId;
            this.mode = mode;
            this.previewShape = previewShape;
            this.hasDrag = hasDrag;
            this.dragX1 = dragX1;
            this.dragY1 = dragY1;
            this.dragX2 = dragX2;
            this.dragY2 = dragY2;
            this.hasCursor = hasCursor;
            this.cursorX = cursorX;
            this.cursorY = cursorY;
        }
    }

    private static final Map<String, RenderShape> SETTLEMENT_SHAPES = new ConcurrentHashMap<>();
    private static volatile RenderShape[] savedShapesSnapshot = new RenderShape[0];
    private static volatile boolean overlayVisible = true;
    private static volatile EditorSnapshot activeEditor = null;

    // SpriteRenderer 最多三缓冲并发，4 槽环形 Drawer 完全无锁且零每帧对象分配
    private static final GroundFillDrawer[] DRAWER_RING = new GroundFillDrawer[]{
        new GroundFillDrawer(),
        new GroundFillDrawer(),
        new GroundFillDrawer(),
        new GroundFillDrawer()
    };
    private static int drawerCursor = 0;

    public static void reset() {
        SETTLEMENT_SHAPES.clear();
        savedShapesSnapshot = new RenderShape[0];
        activeEditor = null;
        overlayVisible = true;
    }

    public static void setOverlayVisible(boolean visible) {
        overlayVisible = visible;
    }

    public static boolean isOverlayVisible() {
        return overlayVisible;
    }

    public static void syncSettlement(String settlementId, List<int[]> rects) {
        if (settlementId == null) return;
        if (rects == null || rects.isEmpty()) {
            SETTLEMENT_SHAPES.remove(settlementId);
        } else {
            SETTLEMENT_SHAPES.put(settlementId, new RenderShape(settlementId, rects));
        }
        rebuildSavedSnapshot();
    }

    public static void removeSettlement(String settlementId) {
        if (settlementId != null && SETTLEMENT_SHAPES.remove(settlementId) != null) {
            rebuildSavedSnapshot();
        }
    }

    private static void rebuildSavedSnapshot() {
        savedShapesSnapshot = SETTLEMENT_SHAPES.values().toArray(new RenderShape[0]);
    }

    private static int toInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    public static void syncEditorState(
            String editingSettlementId,
            int mode,
            KahluaTable flatPreviewRects,
            boolean hasDrag,
            int dragX, int dragY, int dragW, int dragH,
            boolean hasCursor,
            int cursorX, int cursorY) {
        List<int[]> rects = new ArrayList<>();
        if (flatPreviewRects != null) {
            int len = flatPreviewRects.len();
            for (int i = 1; i + 3 <= len; i += 4) {
                int rx = toInt(flatPreviewRects.rawget(Double.valueOf(i)));
                int ry = toInt(flatPreviewRects.rawget(Double.valueOf(i + 1)));
                int rw = toInt(flatPreviewRects.rawget(Double.valueOf(i + 2)));
                int rh = toInt(flatPreviewRects.rawget(Double.valueOf(i + 3)));
                if (rw > 0 && rh > 0) {
                    rects.add(new int[]{rx, ry, rw, rh});
                }
            }
        }
        String normEditId = (editingSettlementId != null && !editingSettlementId.isEmpty()) ? editingSettlementId : null;
        RenderShape preview = new RenderShape("__preview__", rects);
        activeEditor = new EditorSnapshot(
                normEditId,
                mode,
                preview,
                hasDrag && dragW > 0 && dragH > 0,
                dragX, dragY, dragX + dragW, dragY + dragH,
                hasCursor,
                cursorX, cursorY
        );
    }

    public static void clearEditorState() {
        activeEditor = null;
    }

    // ==================== 1. 编辑态 3D 地面深度半透明铺底 ====================

    public static void renderGroundFills() {
        EditorSnapshot editor = activeEditor;
        if (editor == null) return;
        if (IsoWorld.instance == null || IsoWorld.instance.currentCell == null || SpriteRenderer.instance == null) return;

        int playerIndex = IsoCamera.frameState.playerIndex;
        if (playerIndex != 0) return;
        IsoPlayer player = IsoPlayer.players[playerIndex];
        if (player == null) return;

        IsoChunkMap cm = IsoWorld.instance.currentCell.getChunkMap(playerIndex);
        if (cm == null || cm.ignore) return;
        int minX = cm.getWorldXMinTiles();
        int minY = cm.getWorldYMinTiles();
        int maxX = cm.getWorldXMaxTiles();
        int maxY = cm.getWorldYMaxTiles();
        int pz = PZMath.fastfloor(player.getZ());

        GroundFillDrawer drawer = DRAWER_RING[(drawerCursor++) & 3];
        drawer.reset(pz);

        // 其他已存据点淡金色底色提示（防止误重叠）
        RenderShape[] saved = savedShapesSnapshot;
        for (int i = 0; i < saved.length; i++) {
            RenderShape s = saved[i];
            if (s.settlementId.equals(editor.editingSettlementId)) continue;
            if (!s.intersectsChunkMap(minX, minY, maxX, maxY)) continue;
            drawer.appendShape(s.fillQuads, minX, minY, maxX, maxY, 0.95f, 0.72f, 0.22f, 0.12f);
        }

        // 当前不规则领地实时布尔预览底色（翠绿半透明，无任何内部分割缝）
        if (editor.previewShape.intersectsChunkMap(minX, minY, maxX, maxY)) {
            drawer.appendShape(editor.previewShape.fillQuads, minX, minY, maxX, maxY, 0.18f, 0.85f, 0.54f, 0.22f);
        }

        // 擦除模式下额外给橡皮擦拉框铺淡珊瑚红提示；悬停时高亮鼠标下 1x1 单格
        if (editor.hasDrag && editor.mode == 1) {
            drawer.appendClampedQuad(editor.dragX1, editor.dragY1, editor.dragX2, editor.dragY2,
                    minX, minY, maxX, maxY, 0.96f, 0.26f, 0.26f, 0.22f);
        } else if (!editor.hasDrag && editor.hasCursor) {
            if (editor.mode == 1) {
                drawer.appendClampedQuad(editor.cursorX, editor.cursorY, editor.cursorX + 1, editor.cursorY + 1,
                        minX, minY, maxX, maxY, 0.96f, 0.32f, 0.32f, 0.22f);
            } else {
                drawer.appendClampedQuad(editor.cursorX, editor.cursorY, editor.cursorX + 1, editor.cursorY + 1,
                        minX, minY, maxX, maxY, 0.38f, 0.88f, 1.00f, 0.20f);
            }
        }

        if (drawer.quadCount > 0) {
            SpriteRenderer.instance.drawGeneric(drawer);
        }
    }

    // ==================== 2. 缩放锁定的纯外轮廓等轴测矢量线 ====================

    public static void renderOutlines() {
        EditorSnapshot editor = activeEditor;
        if (!overlayVisible && editor == null) return;
        if (IsoWorld.instance == null || IsoWorld.instance.currentCell == null) return;

        int playerIndex = IsoCamera.frameState.playerIndex;
        if (playerIndex != 0) return;
        IsoPlayer player = IsoPlayer.players[playerIndex];
        if (player == null) return;

        IsoChunkMap cm = IsoWorld.instance.currentCell.getChunkMap(playerIndex);
        if (cm == null || cm.ignore) return;
        int minX = cm.getWorldXMinTiles();
        int minY = cm.getWorldYMinTiles();
        int maxX = cm.getWorldXMaxTiles();
        int maxY = cm.getWorldYMaxTiles();
        float fz = PZMath.fastfloor(player.getZ());

        RenderShape[] saved = savedShapesSnapshot;
        float savedAlpha = editor != null ? 0.50f : 0.85f;
        for (int i = 0; i < saved.length; i++) {
            RenderShape s = saved[i];
            if (editor != null && s.settlementId.equals(editor.editingSettlementId)) continue;
            if (!s.intersectsChunkMap(minX, minY, maxX, maxY)) continue;
            drawSegments(s.outlineSegments, fz, 0.95f, 0.72f, 0.22f, savedAlpha, 2);
        }

        if (editor != null) {
            if (!editor.previewShape.isEmpty()) {
                drawSegments(editor.previewShape.outlineSegments, fz, 0.25f, 0.95f, 0.58f, 0.96f, 2);
            }
            if (editor.hasDrag) {
                if (editor.mode == 1) {
                    drawRectBox(editor.dragX1, editor.dragY1, editor.dragX2, editor.dragY2, fz, 0.98f, 0.30f, 0.30f, 0.98f, 2);
                } else {
                    drawRectBox(editor.dragX1, editor.dragY1, editor.dragX2, editor.dragY2, fz, 1.00f, 0.94f, 0.42f, 0.92f, 1);
                }
            } else if (editor.hasCursor) {
                if (editor.mode == 1) {
                    drawRectBox(editor.cursorX, editor.cursorY, editor.cursorX + 1, editor.cursorY + 1, fz, 0.98f, 0.36f, 0.36f, 0.88f, 1);
                } else {
                    drawRectBox(editor.cursorX, editor.cursorY, editor.cursorX + 1, editor.cursorY + 1, fz, 0.45f, 0.90f, 1.00f, 0.88f, 1);
                }
            }
        }
    }

    private static void drawSegments(float[] segs, float fz, float r, float g, float b, float a, int thickness) {
        for (int i = 0; i + 3 < segs.length; i += 4) {
            LineDrawer.DrawIsoLine(segs[i], segs[i + 1], fz, segs[i + 2], segs[i + 3], fz, r, g, b, a, thickness);
        }
    }

    private static void drawRectBox(float x1, float y1, float x2, float y2, float fz, float r, float g, float b, float a, int thickness) {
        LineDrawer.DrawIsoLine(x1, y1, fz, x2, y1, fz, r, g, b, a, thickness);
        LineDrawer.DrawIsoLine(x2, y1, fz, x2, y2, fz, r, g, b, a, thickness);
        LineDrawer.DrawIsoLine(x2, y2, fz, x1, y2, fz, r, g, b, a, thickness);
        LineDrawer.DrawIsoLine(x1, y2, fz, x1, y1, fz, r, g, b, a, thickness);
    }

    // ==================== 3. RenderThread 3D 深度地面色块批次绘制器 ====================

    private static final class GroundFillDrawer extends TextureDraw.GenericDrawer {
        private static final int FLOATS_PER_QUAD = 8; // x1, y1, x2, y2, r, g, b, a
        float[] data = new float[64 * FLOATS_PER_QUAD];
        int quadCount = 0;
        int z = 0;

        void reset(int levelZ) {
            this.quadCount = 0;
            this.z = levelZ;
        }

        void appendShape(int[] quads, int minX, int minY, int maxX, int maxY, float r, float g, float b, float a) {
            for (int i = 0; i + 3 < quads.length; i += 4) {
                appendClampedQuad(quads[i], quads[i + 1], quads[i + 2], quads[i + 3], minX, minY, maxX, maxY, r, g, b, a);
            }
        }

        void appendClampedQuad(int x1, int y1, int x2, int y2, int minX, int minY, int maxX, int maxY, float r, float g, float b, float a) {
            int cx1 = Math.max(x1, minX);
            int cy1 = Math.max(y1, minY);
            int cx2 = Math.min(x2, maxX);
            int cy2 = Math.min(y2, maxY);
            if (cx1 >= cx2 || cy1 >= cy2) return;

            int base = quadCount * FLOATS_PER_QUAD;
            if (base + FLOATS_PER_QUAD > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[base] = cx1;
            data[base + 1] = cy1;
            data[base + 2] = cx2;
            data[base + 3] = cy2;
            data[base + 4] = r;
            data[base + 5] = g;
            data[base + 6] = b;
            data[base + 7] = a;
            quadCount++;
        }

        @Override
        public void render() {
            if (quadCount <= 0) return;
            SpriteRenderState state = SpriteRenderer.instance.getRenderingState();
            if (state == null || state.playerCamera == null) return;
            PlayerCamera cam = state.playerCamera[state.playerIndex];
            if (cam == null) return;

            Core core = Core.getInstance();
            Float p0 = core.floatParamMap.get(Integer.valueOf(0));
            Float p1 = core.floatParamMap.get(Integer.valueOf(1));
            Float p2 = core.floatParamMap.get(Integer.valueOf(2));
            if (p0 == null || p1 == null || p2 == null) return;

            float playerX = p0.floatValue();
            float playerY = p1.floatValue();
            float playerZ = p2.floatValue();
            int pFloorX = PZMath.fastfloor(playerX);
            int pFloorY = PZMath.fastfloor(playerY);

            float rcx = cam.rightClickX;
            float rcy = cam.rightClickY;
            float toffx = cam.getTOffX();
            float toffy = cam.getTOffY();
            float camX = playerX - cam.XToIso(-toffx - rcx, -toffy - rcy, 0.0f) + cam.deferedX;
            float camY = playerY - cam.YToIso(-toffx - rcx, -toffy - rcy, 0.0f) + cam.deferedY;

            double orthoW = cam.offscreenWidth / 1920.0;
            double orthoH = cam.offscreenHeight / 1920.0;
            Matrix4f proj = core.projectionMatrixStack.alloc();
            proj.setOrtho(-(float) orthoW / 2.0f, (float) orthoW / 2.0f, -(float) orthoH / 2.0f, (float) orthoH / 2.0f, -10.0f, 10.0f);
            core.projectionMatrixStack.push(proj);

            try {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(false);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                VBORenderer vbo = VBORenderer.getInstance();
                Matrix4f mv = core.modelViewMatrixStack.alloc();
                mv.scaling(Core.scale);
                mv.scale(Core.tileScale / 2.0f);
                mv.rotate(0.5235988f, 1.0f, 0.0f, 0.0f);
                mv.rotate(2.3561945f, 0.0f, 1.0f, 0.0f);
                float dy = (this.z - playerZ) * 2.44949f;
                mv.translate(0.0f, dy, 0.0f);
                mv.scale(-1.0f, 1.0f, -1.0f);
                mv.translate(0.0f, -0.71999997f, 0.0f);

                vbo.cmdPushAndLoadMatrix(GL11.GL_MODELVIEW, mv);
                vbo.startRun(vbo.formatPositionColorUvDepth);
                vbo.setMode(GL11.GL_QUADS);
                vbo.setDepthTest(true);
                vbo.setTextureID(Texture.getWhite().getTextureId());

                final float fz = this.z;
                final float depthBias = -1.2E-4f;
                for (int i = 0; i < quadCount; i++) {
                    int base = i * FLOATS_PER_QUAD;
                    float x1 = data[base];
                    float y1 = data[base + 1];
                    float x2 = data[base + 2];
                    float y2 = data[base + 3];
                    float r = data[base + 4];
                    float g = data[base + 5];
                    float b = data[base + 6];
                    float a = data[base + 7];

                    float d0 = IsoDepthHelper.getSquareDepthData(pFloorX, pFloorY, x1, y1, fz).depthStart + depthBias;
                    float d1 = IsoDepthHelper.getSquareDepthData(pFloorX, pFloorY, x2, y1, fz).depthStart + depthBias;
                    float d2 = IsoDepthHelper.getSquareDepthData(pFloorX, pFloorY, x2, y2, fz).depthStart + depthBias;
                    float d3 = IsoDepthHelper.getSquareDepthData(pFloorX, pFloorY, x1, y2, fz).depthStart + depthBias;

                    float vx1 = x1 - camX;
                    float vx2 = x2 - camX;
                    float vz1 = y1 - camY;
                    float vz2 = y2 - camY;

                    vbo.addQuadDepth(
                            vx1, 0.0f, vz1, 0.0f, 0.0f, d0,
                            vx2, 0.0f, vz1, 1.0f, 0.0f, d1,
                            vx2, 0.0f, vz2, 1.0f, 1.0f, d2,
                            vx1, 0.0f, vz2, 0.0f, 1.0f, d3,
                            r, g, b, a
                    );
                }

                vbo.endRun();
                vbo.cmdPopMatrix(GL11.GL_MODELVIEW);
                vbo.flush();
            } finally {
                core.projectionMatrixStack.pop();
                GLStateRenderThread.restore();
            }
        }
    }
}
