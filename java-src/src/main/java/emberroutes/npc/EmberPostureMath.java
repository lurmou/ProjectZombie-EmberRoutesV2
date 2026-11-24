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
 *   <li>仅生成 {@code HeadLeft / HeadRight}（头睡床头为正向 {@code Facing}；床头不可达时支持头睡床尾并反转卧向为 {@code Facing.opposite()}），禁止生成原版起身卡死的 {@code Foot}。</li>
 * </ul>
 */
public final class EmberPostureMath {
    public static final float BED_EDGE_OVERLAP = 0.3F;
    public static final float BED_SIT_HANDOFF_FRAC = 0.50F;
    public static final float WORLD_Z_UNITS_PER_STORY = 2.44949F;
    public static final float BED_LYING_UNDERSIDE_WORLD_Y = 0.44F;
    public static final float SEATING_BASE_OFFSET_WORLD_Y = 0.35F;
    public static final float MAX_BED_RENDER_LIFT_Z = 0.45F;
    /** 对齐原版 {@code PathFindBehavior2.update()} 末段到达判定半径（格）。 */
    public static final float PRECISION_ARRIVE_RADIUS = 0.05F;
    /** 对齐原版 {@code PathFindBehavior2.isStrafing()} 无瞄准短距侧移步距离阈值（{@code distSq < 0.25F}）。 */
    public static final float PRECISION_SHORT_STRAFE_DIST = 0.50F;
    /** 对齐原版 {@code PathFindBehavior2.update()} 停步余量收敛基础步长（{@code 0.005F * mult}）。 */
    public static final float PRECISION_STOP_CREEP_BASE = 0.005F;

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

    /** 由床卧朝向与 {@code OnBedDirection} 解析床沿外侧面朝向（{@code HeadLeft/HeadRight} -> 垂直朝外坐向）。 */
    public static BedFacing resolveOutwardSitDir(BedFacing lieDir, String onBedDirection) {
        if (lieDir == null || onBedDirection == null) {
            return null;
        }
        if ("HeadLeft".equals(onBedDirection)) {
            return lieDir.rotLeft90();
        }
        if ("HeadRight".equals(onBedDirection)) {
            return lieDir.rotRight90();
        }
        return null;
    }

    /** 判断给定 {@code OnBedDirection} 是否处于左侧（对应 {@code Bob_GetOutBedLeft}）。 */
    public static boolean isLeftSideOnBed(String onBedDirection) {
        return "HeadLeft".equals(onBedDirection);
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
            addSideApproaches(out, slotIndex, slotIndex, headGy, slotIndex, footGy,
                    hx - BED_EDGE_OVERLAP, hy + 0.5F, hx - 1, hy, hx, hy,
                    hx - BED_EDGE_OVERLAP, fy + 0.5F, hx - 1, fy, hx, fy,
                    BedFacing.E, lieDir, hasSeparateFoot);
        }
        if (slotIndex == gridW - 1) {
            addSideApproaches(out, slotIndex, slotIndex, headGy, slotIndex, footGy,
                    hx + 1.0F + BED_EDGE_OVERLAP, hy + 0.5F, hx + 1, hy, hx, hy,
                    hx + 1.0F + BED_EDGE_OVERLAP, fy + 0.5F, hx + 1, fy, hx, fy,
                    BedFacing.W, lieDir, hasSeparateFoot);
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
            addSideApproaches(out, slotIndex, headGx, slotIndex, footGx, slotIndex,
                    hx + 0.5F, hy - BED_EDGE_OVERLAP, hx, hy - 1, hx, hy,
                    fx + 0.5F, hy - BED_EDGE_OVERLAP, fx, hy - 1, fx, hy,
                    BedFacing.S, lieDir, hasSeparateFoot);
        }
        if (slotIndex == gridH - 1) {
            addSideApproaches(out, slotIndex, headGx, slotIndex, footGx, slotIndex,
                    hx + 0.5F, hy + 1.0F + BED_EDGE_OVERLAP, hx, hy + 1, hx, hy,
                    fx + 0.5F, hy + 1.0F + BED_EDGE_OVERLAP, fx, hy + 1, fx, hy,
                    BedFacing.N, lieDir, hasSeparateFoot);
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
            boolean includeFoot) {
        BedFacing outwardSitDir = beforeDir.opposite();
        String headOnBedDir = resolveOnBedSideFromSitDir(lieDir, outwardSitDir);
        out.add(new BedApproach(
                slotIndex, headGx, headGy, footGx, footGy,
                headAppX, headAppY, headAdjX, headAdjY, headTargetX, headTargetY,
                beforeDir, lieDir, headOnBedDir, true));
        if (includeFoot) {
            // 头睡床尾：床尾直接作为等效头位并反转卧向，手性按新卧向自动镜像以复用 HeadLeft/HeadRight
            BedFacing footLieDir = lieDir.opposite();
            String footOnBedDir = resolveOnBedSideFromSitDir(footLieDir, outwardSitDir);
            out.add(new BedApproach(
                    slotIndex, headGx, headGy, footGx, footGy,
                    footAppX, footAppY, footAdjX, footAdjY, footTargetX, footTargetY,
                    beforeDir, footLieDir, footOnBedDir, false));
        }
    }

    // ================= 座椅休闲阅读抬眼注视数学内核（零 zombie.* 依赖） =================

    /** 触发检测最大水平距离（格）。 */
    public static final float GLANCE_TRIGGER_MAX_DIST = 4.5F;
    /** 触发检测最小水平距离（防重叠奇点）。 */
    public static final float GLANCE_TRIGGER_MIN_DIST = 0.35F;
    /** 保持在场状态的迟滞最大距离（格）。 */
    public static final float GLANCE_PRESENCE_HYSTERESIS_DIST = 5.5F;
    /** 座椅前方可视扇区最小点积（cos 75.5° ≈ 0.25，杜绝背后/正后侧触发）。 */
    public static final float GLANCE_TRIGGER_MIN_DOT = 0.25F;
    /** 保持在场状态的迟滞最小点积（cos 84° ≈ 0.10）。 */
    public static final float GLANCE_PRESENCE_HYSTERESIS_DOT = 0.10F;

    /** 样片阶段 1：抬眼转头时长（Frames 7..21 @ 24fps ≈ 0.60s）。 */
    public static final double GLANCE_RAISE_DURATION_SEC = 0.60;
    /** 样片阶段 2：停半拍注视时长（Frames 21..26 @ 24fps ≈ 0.25s）。 */
    public static final double GLANCE_HOLD_DURATION_SEC = 0.25;
    /** 样片阶段 3：低头收回时长（Frames 26..43 @ 24fps ≈ 0.70s）。 */
    public static final double GLANCE_RETURN_DURATION_SEC = 0.70;
    /** 单次抬眼总包络时长（1.55s）。 */
    public static final double GLANCE_TOTAL_DURATION_SEC =
            GLANCE_RAISE_DURATION_SEC + GLANCE_HOLD_DURATION_SEC + GLANCE_RETURN_DURATION_SEC;

    /**
     * 计算三段式注视包络权重 {@code [0.0, 1.0]}：
     * <ul>
     *   <li>{@code 0.00s ~ 0.60s}：smoothstep 抬眼升至 {@code 1.0}；</li>
     *   <li>{@code 0.60s ~ 0.85s}：停半拍保持 {@code 1.0}；</li>
     *   <li>{@code 0.85s ~ 1.55s}：smoothstep 收回降至 {@code 0.0}。</li>
     * </ul>
     */
    public static float computeGlanceEnvelope(double elapsedSec) {
        if (elapsedSec <= 0.0 || elapsedSec >= GLANCE_TOTAL_DURATION_SEC) {
            return 0.0F;
        }
        if (elapsedSec < GLANCE_RAISE_DURATION_SEC) {
            float u = (float) (elapsedSec / GLANCE_RAISE_DURATION_SEC);
            return smoothstep01(u);
        }
        if (elapsedSec <= GLANCE_RAISE_DURATION_SEC + GLANCE_HOLD_DURATION_SEC) {
            return 1.0F;
        }
        float u = (float) ((elapsedSec - GLANCE_RAISE_DURATION_SEC - GLANCE_HOLD_DURATION_SEC)
                / GLANCE_RETURN_DURATION_SEC);
        return 1.0F - smoothstep01(u);
    }

    /**
     * 校验玩家是否处于同层、距离 {@code [minDist, maxDist]} 与座椅前方视野锥 {@code dot >= minDot} 内：
     * <ul>
     *   <li>若不满足任一条件，返回 {@link Float#NaN}；</li>
     *   <li>若满足，返回带符号水平偏角（弧度）：<b>左侧为负（对应 {@code Bob_LookLeft} {@code -1.0}），右侧为正（对应 {@code Bob_LookRight} {@code +1.0}）</b>。</li>
     * </ul>
     */
    public static float computeGlanceSignedAngleIfInFrontCone(
            float npcX, float npcY, int npcLevel,
            float forwardX, float forwardY,
            float playerX, float playerY, int playerLevel,
            float minDist, float maxDist, float minDot) {
        if (npcLevel != playerLevel) {
            return Float.NaN;
        }
        float fLen = (float) Math.hypot(forwardX, forwardY);
        if (fLen < 1.0E-5F) {
            return Float.NaN;
        }
        float fx = forwardX / fLen;
        float fy = forwardY / fLen;

        float dx = playerX - npcX;
        float dy = playerY - npcY;
        float dist = (float) Math.hypot(dx, dy);
        if (dist < minDist || dist > maxDist) {
            return Float.NaN;
        }
        float ux = dx / dist;
        float uy = dy / dist;

        float dot = fx * ux + fy * uy;
        if (dot < minDot) {
            return Float.NaN;
        }

        // PZ 等轴测屏幕坐标系（+X 向右下，+Y 向左下）：
        // cross = fx * uy - fy * ux > 0 代表目标在角色朝向右侧（顺时针），< 0 代表在角色朝向左侧（逆时针）。
        float cross = fx * uy - fy * ux;
        return (float) Math.atan2(cross, dot);
    }

    /**
     * 将带符号水平偏角映射为 {@code LookAround} 的 {@code lookHorizontal} 标量 {@code [-1.0, 1.0]}。
     */
    public static float computeGlanceNormalizedHorizontal(float signedAngleRad, float maxAngleRad) {
        float denom = Math.max(0.20F, maxAngleRad > 0.0F ? maxAngleRad : 0.348F);
        float raw = Math.max(-1.0F, Math.min(1.0F, signedAngleRad / denom));
        if (Math.abs(raw) < 0.22F && Math.abs(signedAngleRad) > 0.02F) {
            raw = Math.copySign(0.22F, signedAngleRad);
        }
        return raw;
    }

    /**
     * 1:1 对齐原版 {@code PathFindBehavior2.update()} 终段根位移对齐与停步收敛：
     * <ul>
     *   <li>行进阶段（{@code stopping=false}）：取本帧动画根位移模长，严格沿直指锚点方向推进 {@code min(rawLen, dist)}；</li>
     *   <li>停步余量阶段（{@code stopping=true}）：动画根位移未完全归零前，每帧按 {@code min(dist, 0.005F * mult)} 平稳抹平最后几厘米。</li>
     * </ul>
     */
    public static float[] computePrecisionRootMotion(
            float curX, float curY,
            float targetX, float targetY,
            float rawDx, float rawDy,
            boolean stopping,
            float gameTimeMultiplier) {
        float dx = targetX - curX;
        float dy = targetY - curY;
        float dist = (float) Math.hypot(dx, dy);
        if (!Float.isFinite(dist) || dist <= 1.0E-5F) {
            return new float[]{0.0F, 0.0F};
        }
        float rawLen = (float) Math.hypot(rawDx, rawDy);
        if (!Float.isFinite(rawLen) || rawLen <= 1.0E-5F) {
            return new float[]{0.0F, 0.0F};
        }
        float step = stopping
                ? Math.min(dist, PRECISION_STOP_CREEP_BASE * Math.max(0.1F, gameTimeMultiplier))
                : Math.min(rawLen, dist);
        return new float[]{(dx / dist) * step, (dy / dist) * step};
    }

    /**
     * 在多候选并发可见性图寻路回包后，按 C++ 路径终节点匹配最接近的合法入座/停靠候选索引；超出容差返回 {@code -1}。
     */
    public static int matchNearestPrecisionCandidate(
            float endX, float endY, float[] pathXs, float[] pathYs, int count, float maxDistSq) {
        if (pathXs == null || pathYs == null || count <= 0) {
            return -1;
        }
        int limit = Math.min(count, Math.min(pathXs.length, pathYs.length));
        int bestIdx = -1;
        float bestDistSq = maxDistSq;
        for (int i = 0; i < limit; i++) {
            float dx = pathXs[i] - endX;
            float dy = pathYs[i] - endY;
            float dSq = dx * dx + dy * dy;
            if (dSq <= bestDistSq) {
                bestDistSq = dSq;
                bestIdx = i;
            }
        }
        return bestIdx;
    }
}
