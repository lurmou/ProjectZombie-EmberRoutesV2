package emberroutes.npc;

import emberroutes.warehouse.FurnitureClassifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 纯 Java 家具就坐与床铺睡眠几何解析器（零 {@code zombie.*} 依赖，支持脱机 JUnit 严密回归）：
 * <ul>
 *   <li>严格对齐原版 B42 {@code PlayerSitOnFurnitureState}、{@code PlayerOnBedState} 与 {@code PlayerGetUpState}；</li>
 *   <li>避开 {@code SeatingManager.getFacingDirection(bed)} 会把 {@code seating.txt} 侧坐位当成床朝向的 90° 偏移坑；</li>
 *   <li>禁止生成 {@code OnBedDirection = "Foot"}（原版 {@code fromOnBed.xml} 条件错写为 {@code Front} 会永久卡死起身）。</li>
 * </ul>
 */
public final class EmberPostureMath {
    public static final float BED_EDGE_OVERLAP = 0.3F;
    public static final float BED_SIT_HANDOFF_FRAC = 0.50F;
    public static final float WORLD_Z_UNITS_PER_STORY = 2.44949F;
    public static final float BED_LYING_UNDERSIDE_WORLD_Y = 0.44F;
    public static final float SEATING_BASE_OFFSET_WORLD_Y = 0.35F;
    public static final float MAX_BED_RENDER_LIFT_Z = 0.45F;

    public enum BedFacing {
        N(0, -1),
        S(0, 1),
        W(-1, 0),
        E(1, 0);

        public final int dx;
        public final int dy;

        BedFacing(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        public static BedFacing normalize(String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim().toUpperCase(Locale.ROOT);
            return switch (trimmed) {
                case "N" -> N;
                case "S" -> S;
                case "W" -> W;
                case "E" -> E;
                default -> null;
            };
        }

        public static BedFacing inferFromGrid(int gridW, int gridH) {
            if (gridW >= 2 && gridH == 1) {
                return E;
            }
            return S;
        }

        public BedFacing rotLeft90() {
            return switch (this) {
                case N -> W;
                case W -> S;
                case S -> E;
                case E -> N;
            };
        }

        public BedFacing rotRight90() {
            return switch (this) {
                case N -> E;
                case E -> S;
                case S -> W;
                case W -> N;
            };
        }

        public BedFacing opposite() {
            return switch (this) {
                case N -> S;
                case S -> N;
                case W -> E;
                case E -> W;
            };
        }
    }

    public record BedApproach(
            int slotIndex,
            int headGridX,
            int headGridY,
            int footGridX,
            int footGridY,
            float approachX,
            float approachY,
            int adjTileX,
            int adjTileY,
            int adjacentTargetTileX,
            int adjacentTargetTileY,
            BedFacing beforeDir,
            BedFacing lieDir,
            String onBedDirection,
            boolean headEntry) {}

    public record BedSlot(
            int slotIndex,
            int headGridX,
            int headGridY,
            int footGridX,
            int footGridY,
            List<BedApproach> approaches) {}

    private EmberPostureMath() {}

    /**
     * 严格区分真实床铺与带打盹标志（{@code IsoFlagType.bed}）的椅子/沙发：
     * <ul>
     *   <li>排除座椅专有图集（{@code furniture_seating_*} 等）与座椅类名；</li>
     *   <li>多格物体必须满足长边与 {@code Facing} 一致（{@code N/S -> gridH >= 2}，{@code W/E -> gridW >= 2}），排除横向朝前的双/三人沙发。</li>
     * </ul>
     */
    public static boolean isRealBedObject(
            String spriteOrTileset, String customName, BedFacing facing, int gridW, int gridH) {
        return FurnitureClassifier.isRealBedObject(
                spriteOrTileset, customName, facing != null ? facing.name() : null, gridW, gridH);
    }

    /** 标准三次平滑步进（[0, 1] 钳位），用于上下床动画期间的温和补位。 */
    public static float smoothstep01(float t) {
        float c = Math.max(0.0F, Math.min(1.0F, t));
        return c * c * (3.0F - 2.0F * c);
    }

    /**
     * 按当前已走进度比例将剩余位置差收敛到目标点：即使引擎原版根位移同期推进或发生漂移，
     * 只要 {@code nextU -> 1.0F}，最终位置仍严格收敛至 {@code target}，避免终帧突变。
     */
    public static float computeSmoothBlendStep(float current, float target, float prevU, float nextU) {
        float p = Math.max(0.0F, Math.min(1.0F, prevU));
        float n = Math.max(0.0F, Math.min(1.0F, nextU));
        if (n >= 0.9999F) {
            return target;
        }
        if (n <= p) {
            return current;
        }
        float alpha = Math.min(1.0F, (n - p) / Math.max(0.0001F, 1.0F - p));
        return current + (target - current) * alpha;
    }

    /**
     * 计算卧床渲染垂直抬升（B42 格层 Z 单位）：
     * 根据 {@code tileGeometry.txt} 床垫顶面高度与原版 {@code Bob_Asleep/Awake} 贴床下沿（{@code 0.44}）之差换算，
     * 仅作用于渲染 {@code z} 与 {@code squareDepth}，不改动实体物理坐标。
     */
    public static float computeBedRenderLiftZ(float mattressTopWorldY) {
        if (!Float.isFinite(mattressTopWorldY) || mattressTopWorldY <= BED_LYING_UNDERSIDE_WORLD_Y) {
            return 0.0F;
        }
        return Math.min(
                MAX_BED_RENDER_LIFT_Z,
                (mattressTopWorldY - BED_LYING_UNDERSIDE_WORLD_Y) / WORLD_Z_UNITS_PER_STORY);
    }

    /**
     * 计算上下床全周期内床垫抬升插值系数（[0, 1]）：
     * 对齐原版 {@code Bob_GetInBed*} {@code [0.20, 0.50]} 落床与 {@code Bob_GetOutBed*} {@code [0.60, 0.85]} 离床轨迹。
     */
    public static float computeBedElevationLerp(
            boolean entering, boolean fullyOnBed, boolean leaving, float trackFraction) {
        if (fullyOnBed) {
            return 1.0F;
        }
        if (entering) {
            return smoothstep01((trackFraction - 0.20F) / 0.30F);
        }
        if (leaving) {
            return 1.0F - smoothstep01((trackFraction - 0.60F) / 0.25F);
        }
        return 0.0F;
    }

    /** 若角色当前正朝床外侧垂直坐姿（相对于床卧方向 {@code lieDir}），映射对应侧 {@code HeadLeft/HeadRight}，否则返回 {@code null}。 */
    public static String resolveOnBedSideFromSitDir(BedFacing lieDir, BedFacing sitDir) {
        if (lieDir == null || sitDir == null) {
            return null;
        }
        if (sitDir == lieDir.rotLeft90()) {
            return "HeadLeft";
        }
        if (sitDir == lieDir.rotRight90()) {
            return "HeadRight";
        }
        return null;
    }

    /** 由床卧朝向与 {@code OnBedDirection} 解析床沿外侧面朝向（{@code Left/Right} -> 垂直朝外坐向）。 */
    public static BedFacing resolveOutwardSitDir(BedFacing lieDir, String onBedDirection) {
        if (lieDir == null || onBedDirection == null) {
            return null;
        }
        if ("HeadLeft".equals(onBedDirection) || "FootLeft".equals(onBedDirection)) {
            return lieDir.rotLeft90();
        }
        if ("HeadRight".equals(onBedDirection) || "FootRight".equals(onBedDirection)) {
            return lieDir.rotRight90();
        }
        return null;
    }

    /** 判断给定 {@code OnBedDirection} 是否处于左侧（对应 {@code Bob_GetOutBedLeft}）。 */
    public static boolean isLeftSideOnBed(String onBedDirection) {
        return "HeadLeft".equals(onBedDirection) || "FootLeft".equals(onBedDirection);
    }

    /** 在床沿坐高 {@code fromLiftZ} 与床垫卧高 {@code toLiftZ} 间按平滑曲线插值渲染 Z 抬升。 */
    public static float computeSitAdapterLiftZ(float fromLiftZ, float toLiftZ, float progress01) {
        float safeFrom = Float.isFinite(fromLiftZ) ? Math.max(0.0F, fromLiftZ) : 0.0F;
        float safeTo = Float.isFinite(toLiftZ) ? Math.max(0.0F, toLiftZ) : 0.0F;
        float u = smoothstep01(progress01);
        return Math.max(0.0F, safeFrom + (safeTo - safeFrom) * u);
    }

    /** 推进坐/卧桥接动画归一化进度（限制单帧 {@code animDeltaT <= 0.10s} 防切屏或卡顿瞬间跳变）。 */
    public static float advanceAdapterProgress(float currentProgress01, float animDeltaT, float durationSec) {
        float cur = Float.isFinite(currentProgress01) ? Math.max(0.0F, Math.min(1.0F, currentProgress01)) : 0.0F;
        float dt = Float.isFinite(animDeltaT) ? Math.max(0.0F, Math.min(0.10F, animDeltaT)) : 0.0F;
        float dur = Float.isFinite(durationSec) ? Math.max(0.05F, durationSec) : 0.05F;
        return Math.max(0.0F, Math.min(1.0F, cur + dt / dur));
    }

    /**
     * 对齐原版 {@code PlayerSitOnFurnitureState.enter()} 与 {@code ISRestAction:setBeforeSitDirection()}：
     * 进入椅子前角色面向家具方向（{@code Left -> RotRight(2)}, {@code Right -> RotLeft(2)}, {@code Front -> sitDir}）。
     */
    public static BedFacing resolveSeatBeforeDir(BedFacing sitDir, String sideStr) {
        BedFacing dir = sitDir != null ? sitDir : BedFacing.S;
        if ("Left".equals(sideStr)) {
            return dir.rotRight90();
        }
        if ("Right".equals(sideStr)) {
            return dir.rotLeft90();
        }
        return dir;
    }

    /**
     * 计算被点击的 SpriteGrid 局部坐标属于床的哪一个睡位索引（单人床恒 0，双人床返回 0 或 1）。
     */
    public static int resolveClickedSlotIndex(BedFacing facing, int gridW, int gridH, int clickedGx, int clickedGy) {
        int safeW = Math.max(1, gridW);
        int safeH = Math.max(1, gridH);
        BedFacing dir = facing != null ? facing : BedFacing.inferFromGrid(safeW, safeH);
        if (dir == BedFacing.N || dir == BedFacing.S) {
            return Math.max(0, Math.min(safeW - 1, clickedGx));
        }
        return Math.max(0, Math.min(safeH - 1, clickedGy));
    }

    /**
     * 枚举一张单人床（1×2 / 2×1）或双人床（2×2）的全部睡位与合法侧面切入点。
     *
     * @param facing 床属性 {@code Facing}（由床头指向床尾）
     * @param gridW SpriteGrid 宽度（格）
     * @param gridH SpriteGrid 高度（格）
     * @param originWorldX SpriteGrid (0, 0) 所在世界格 X
     * @param originWorldY SpriteGrid (0, 0) 所在世界格 Y
     */
    public static List<BedSlot> enumerateBedSlots(
            BedFacing facing, int gridW, int gridH, int originWorldX, int originWorldY) {
        int safeW = Math.max(1, gridW);
        int safeH = Math.max(1, gridH);
        BedFacing dir = facing != null ? facing : BedFacing.inferFromGrid(safeW, safeH);

        List<BedSlot> slots = new ArrayList<>();
        if (dir == BedFacing.N || dir == BedFacing.S) {
            int headGy = (dir == BedFacing.N) ? (safeH - 1) : 0;
            int footGy = (dir == BedFacing.N) ? Math.max(0, safeH - 2) : Math.min(safeH - 1, 1);
            for (int gx = 0; gx < safeW; gx++) {
                List<BedApproach> approaches = buildVerticalSlotApproaches(
                        gx, dir, safeW, originWorldX, originWorldY, headGy, footGy);
                slots.add(new BedSlot(gx, gx, headGy, gx, footGy, Collections.unmodifiableList(approaches)));
            }
        } else {
            int headGx = (dir == BedFacing.W) ? (safeW - 1) : 0;
            int footGx = (dir == BedFacing.W) ? Math.max(0, safeW - 2) : Math.min(safeW - 1, 1);
            for (int gy = 0; gy < safeH; gy++) {
                List<BedApproach> approaches = buildHorizontalSlotApproaches(
                        gy, dir, safeH, originWorldX, originWorldY, headGx, footGx);
                slots.add(new BedSlot(gy, headGx, gy, footGx, gy, Collections.unmodifiableList(approaches)));
            }
        }
        return Collections.unmodifiableList(slots);
    }

    private static List<BedApproach> buildVerticalSlotApproaches(
            int slotIndex, BedFacing lieDir, int gridW,
            int originX, int originY, int headGy, int footGy) {
        List<BedApproach> out = new ArrayList<>(4);
        int hx = originX + slotIndex;
        int hy = originY + headGy;
        int fy = originY + footGy;
        boolean hasSeparateFoot = (footGy != headGy);

        // 仅允许从外侧纵边上下床（双人床中间禁止穿越另一半床体）
        if (slotIndex == 0) {
            String headDir = (lieDir == BedFacing.N) ? "HeadLeft" : "HeadRight";
            String footDir = (lieDir == BedFacing.N) ? "FootLeft" : "FootRight";
            addSideApproaches(out, slotIndex, slotIndex, headGy, slotIndex, footGy,
                    hx - BED_EDGE_OVERLAP, hy + 0.5F, hx - 1, hy, hx, hy,
                    hx - BED_EDGE_OVERLAP, fy + 0.5F, hx - 1, fy, hx, fy,
                    BedFacing.E, lieDir, headDir, footDir, hasSeparateFoot);
        }
        if (slotIndex == gridW - 1) {
            String headDir = (lieDir == BedFacing.N) ? "HeadRight" : "HeadLeft";
            String footDir = (lieDir == BedFacing.N) ? "FootRight" : "FootLeft";
            addSideApproaches(out, slotIndex, slotIndex, headGy, slotIndex, footGy,
                    hx + 1.0F + BED_EDGE_OVERLAP, hy + 0.5F, hx + 1, hy, hx, hy,
                    hx + 1.0F + BED_EDGE_OVERLAP, fy + 0.5F, hx + 1, fy, hx, fy,
                    BedFacing.W, lieDir, headDir, footDir, hasSeparateFoot);
        }
        return out;
    }

    private static List<BedApproach> buildHorizontalSlotApproaches(
            int slotIndex, BedFacing lieDir, int gridH,
            int originX, int originY, int headGx, int footGx) {
        List<BedApproach> out = new ArrayList<>(4);
        int hx = originX + headGx;
        int fx = originX + footGx;
        int hy = originY + slotIndex;
        boolean hasSeparateFoot = (footGx != headGx);

        if (slotIndex == 0) {
            String headDir = (lieDir == BedFacing.W) ? "HeadRight" : "HeadLeft";
            String footDir = (lieDir == BedFacing.W) ? "FootRight" : "FootLeft";
            addSideApproaches(out, slotIndex, headGx, slotIndex, footGx, slotIndex,
                    hx + 0.5F, hy - BED_EDGE_OVERLAP, hx, hy - 1, hx, hy,
                    fx + 0.5F, hy - BED_EDGE_OVERLAP, fx, hy - 1, fx, hy,
                    BedFacing.S, lieDir, headDir, footDir, hasSeparateFoot);
        }
        if (slotIndex == gridH - 1) {
            String headDir = (lieDir == BedFacing.W) ? "HeadLeft" : "HeadRight";
            String footDir = (lieDir == BedFacing.W) ? "FootLeft" : "FootRight";
            addSideApproaches(out, slotIndex, headGx, slotIndex, footGx, slotIndex,
                    hx + 0.5F, hy + 1.0F + BED_EDGE_OVERLAP, hx, hy + 1, hx, hy,
                    fx + 0.5F, hy + 1.0F + BED_EDGE_OVERLAP, fx, hy + 1, fx, hy,
                    BedFacing.N, lieDir, headDir, footDir, hasSeparateFoot);
        }
        return out;
    }

    private static void addSideApproaches(
            List<BedApproach> out,
            int slotIndex,
            int headGx, int headGy, int footGx, int footGy,
            float headAppX, float headAppY, int headAdjX, int headAdjY, int headTargetX, int headTargetY,
            float footAppX, float footAppY, int footAdjX, int footAdjY, int footTargetX, int footTargetY,
            BedFacing beforeDir, BedFacing lieDir,
            String headOnBedDir, String footOnBedDir,
            boolean includeFoot) {
        out.add(new BedApproach(
                slotIndex, headGx, headGy, footGx, footGy,
                headAppX, headAppY, headAdjX, headAdjY, headTargetX, headTargetY,
                beforeDir, lieDir, headOnBedDir, true));
        if (includeFoot) {
            out.add(new BedApproach(
                    slotIndex, headGx, headGy, footGx, footGy,
                    footAppX, footAppY, footAdjX, footAdjY, footTargetX, footTargetY,
                    beforeDir, lieDir, footOnBedDir, false));
        }
    }
}
