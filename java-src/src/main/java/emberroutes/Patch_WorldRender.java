package emberroutes;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * 在原版世界相机离屏 FBO 渲染阶段织入不规则领地绘制（直接位于根包 {@code emberroutes}）：
 *
 * <ol>
 *   <li>{@code FBORenderAreaHighlights.render @OnExit}：提交无内框线、无 T 型裂缝的 3D 地面深度半透明铺底色块；</li>
 *   <li>{@code LineDrawer.render @OnExit}：在相机 FBO 内直接绘制去除了全部内部拼接缝的纯外轮廓矢量线，
 *       任意镜头缩放比例（50%–250%）100% 像素级锁死地面网格。</li>
 * </ol>
 */
public final class Patch_WorldRender {
    private Patch_WorldRender() {}

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderAreaHighlights", methodName = "render")
    public static final class OnAreaHighlightsRender {
        @Patch.OnExit
        public static void exit() {
            TerritoryRenderer.renderGroundFills();
        }
    }

    @Patch(className = "zombie.debug.LineDrawer", methodName = "render")
    public static final class OnLineDrawerRender {
        @Patch.OnExit
        public static void exit() {
            TerritoryRenderer.renderOutlines();
        }
    }
}
