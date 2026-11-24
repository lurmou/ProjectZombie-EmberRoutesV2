package emberroutes.npc;

import java.util.Arrays;

/**
 * 小队级“连通分量扩张 + 弹性宽度挤压 + 互斥补位”联合阵型槽位解算器（纯 Java 零引擎依赖，稳态 0-GC）。
 *
 * <p>三层设计对齐现代 RTS / 战术小队编队管线：
 * <ol>
 *   <li><b>有限范围同层连通分量扫描</b>：围绕锚点/车外安全种子点做半径 6 格、非穿墙/非越栏/避载具的有向拓扑距离扩展；</li>
 *   <li><b>全局弹性宽度挤压</b>：窄巷/壁障下对 {@code WEDGE / LINE} 先按 {@code 1.0 → 0.70 → 0.40 → 0.0} 自适应收窄横向宽度；</li>
 *   <li><b>四级互斥落位</b>：理想开阔位 → 局部横向挤压与向锚点射线回缩 → 连通分量内综合代价最优补位（优先无灌木开阔地面）→ 极限狭小室退让。
 *       所有输出槽位强制满足两两间距 {@code >= 0.85} 格（严格大于 B42 {@code IsoMovingObject.separate} 物理推挤半径）。</li>
 * </ol>
 */
public final class SquadSlotSolver {
    public static final int BFS_RADIUS = 6;
    public static final int GRID_DIM = BFS_RADIUS * 2 + 1;
    public static final int GRID_CELLS = GRID_DIM * GRID_DIM;

    public static final float MIN_SLOT_SEPARATION = 0.85F;
    public static final float TIGHT_SLOT_SEPARATION = 0.64F;
    public static final float DEFAULT_ANCHOR_CLEARANCE = 0.90F;
    public static final float OBSTACLE_CROSSING_PENALTY = 2.20F;

    private static final float[] WIDTH_SCALES = {1.0F, 0.70F, 0.40F, 0.0F};
    private static final float[] SIDE_SQUEEZE_FRACS = {0.50F, 0.0F};
    private static final float[] RAY_PULLBACK_T = {0.82F, 0.64F, 0.46F};
    private static final int[] DIR_DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DIR_DY = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final float[] DIR_COST = {
        1.0F, 1.0F, 1.0F, 1.0F, 1.4142135F, 1.4142135F, 1.4142135F, 1.4142135F
    };

    public interface TerrainProbe {
        boolean canStandSafe(float x, float y);

        boolean isOpenGround(float x, float y);

        boolean isSegmentClear(float x1, float y1, float x2, float y2);

        boolean canStepTile(int fromTx, int fromTy, int toTx, int toTy);
    }

    /** 0-GC 预分配工作区（供 {@link NpcManager} 静态常驻复用或单测隔离使用）。 */
    public static final class Workspace {
        final boolean[] reachable = new boolean[GRID_CELLS];
        final boolean[] settled = new boolean[GRID_CELLS];
        final float[] topoDist = new float[GRID_CELLS];
        final boolean[] openGround = new boolean[GRID_CELLS];
        final int[] clearance = new int[GRID_CELLS];
        final int[] frontier = new int[GRID_CELLS];
        final int[] reachableList = new int[GRID_CELLS];
        final float[] offsetScratch = new float[2];
        int minTx;
        int minTy;
        int reachableCount;
        float lastWidthScale = 1.0F;

        public Workspace() {}

        public float getLastWidthScale() {
            return lastWidthScale;
        }

        public int getReachableCount() {
            return reachableCount;
        }

        void resetGrid(int centerTx, int centerTy) {
            this.minTx = centerTx - BFS_RADIUS;
            this.minTy = centerTy - BFS_RADIUS;
            this.reachableCount = 0;
            this.lastWidthScale = 1.0F;
            Arrays.fill(reachable, false);
            Arrays.fill(settled, false);
            Arrays.fill(topoDist, Float.POSITIVE_INFINITY);
            Arrays.fill(openGround, false);
            Arrays.fill(clearance, 0);
        }
    }

    private SquadSlotSolver() {}

    public static int resolveWorldSlots(
            int count,
            FormationType formation,
            FormationSpacing spacing,
            float anchorX,
            float anchorY,
            float seedX,
            float seedY,
            float headingX,
            float headingY,
            float minAnchorDist,
            TerrainProbe probe,
            float[] outSlotX,
            float[] outSlotY,
            Workspace workspace) {
        if (count <= 0 || probe == null || outSlotX == null || outSlotY == null
                || outSlotX.length < count || outSlotY.length < count) {
            return 0;
        }

        Workspace ws = workspace != null ? workspace : new Workspace();
        FormationType fmt = formation != null ? formation : FormationType.WEDGE;
        float lam = (spacing != null && Float.isFinite(spacing.multiplier())) ? spacing.multiplier() : 1.0F;
        double hLen = Math.hypot(headingX, headingY);
        float ux = hLen > 1.0E-4 ? (float) (headingX / hLen) : 0.0F;
        float uy = hLen > 1.0E-4 ? (float) (headingY / hLen) : 1.0F;
        float effAnchorDist = Math.max(DEFAULT_ANCHOR_CLEARANCE, Float.isFinite(minAnchorDist) ? minAnchorDist : 0.0F);
        boolean enforceOuterAnchor = Float.isFinite(minAnchorDist) && minAnchorDist > DEFAULT_ANCHOR_CLEARANCE;

        buildConnectedComponent(seedX, seedY, probe, ws);

        float widthScale = chooseElasticWidthScale(
                count, fmt, lam, anchorX, anchorY, ux, uy, enforceOuterAnchor ? effAnchorDist : 0.0F, probe, ws);
        ws.lastWidthScale = widthScale;

        int resolved = 0;
        for (int s = 0; s < count; s++) {
            EmberFormationMatcher.slotOffset(fmt, s, count, lam, ws.offsetScratch);
            float rawBack = ws.offsetScratch[0];
            float rawSide = ws.offsetScratch[1] * widthScale;
            if (enforceOuterAnchor) {
                float r = (float) Math.hypot(rawBack, rawSide);
                if (r < 0.05F) {
                    rawBack = effAnchorDist;
                    rawSide = 0.0F;
                } else if (r < effAnchorDist) {
                    float scale = effAnchorDist / r;
                    rawBack *= scale;
                    rawSide *= scale;
                }
            }

            float ix = anchorX - ux * rawBack - uy * rawSide;
            float iy = anchorY - uy * rawBack + ux * rawSide;

            if (tryPlaceSingleSlot(
                    s, ix, iy, rawBack, rawSide, anchorX, anchorY, seedX, seedY,
                    ux, uy, effAnchorDist, probe, ws, outSlotX, outSlotY)) {
                resolved++;
            }
        }
        return resolved;
    }

    private static void buildConnectedComponent(float seedX, float seedY, TerrainProbe probe, Workspace ws) {
        int centerTx = floor(seedX);
        int centerTy = floor(seedY);
        ws.resetGrid(centerTx, centerTy);

        int seedTileIdx = findSeedTileIndex(centerTx, centerTy, seedX, seedY, probe, ws);
        if (seedTileIdx < 0) {
            return;
        }

        ws.reachable[seedTileIdx] = true;
        ws.topoDist[seedTileIdx] = 0.0F;
        ws.openGround[seedTileIdx] = probe.isOpenGround(tileCenterX(seedTileIdx, ws), tileCenterY(seedTileIdx, ws));

        int frontierLen = 1;
        ws.frontier[0] = seedTileIdx;

        while (frontierLen > 0) {
            int bestPos = 0;
            float bestDist = ws.topoDist[ws.frontier[0]];
            for (int i = 1; i < frontierLen; i++) {
                float d = ws.topoDist[ws.frontier[i]];
                if (d < bestDist) {
                    bestDist = d;
                    bestPos = i;
                }
            }

            int curIdx = ws.frontier[bestPos];
            ws.frontier[bestPos] = ws.frontier[frontierLen - 1];
            frontierLen--;

            if (ws.settled[curIdx]) {
                continue;
            }
            ws.settled[curIdx] = true;
            ws.reachableList[ws.reachableCount++] = curIdx;

            int cx = ws.minTx + (curIdx % GRID_DIM);
            int cy = ws.minTy + (curIdx / GRID_DIM);
            for (int d = 0; d < 8; d++) {
                int nx = cx + DIR_DX[d];
                int ny = cy + DIR_DY[d];
                int nIdx = gridIdx(nx, ny, ws);
                if (nIdx < 0 || ws.settled[nIdx]) {
                    continue;
                }
                if (!probe.canStepTile(cx, cy, nx, ny)) {
                    continue;
                }
                float candCost = bestDist + DIR_COST[d];
                if (!ws.reachable[nIdx]) {
                    ws.reachable[nIdx] = true;
                    ws.topoDist[nIdx] = candCost;
                    ws.openGround[nIdx] = probe.isOpenGround(nx + 0.5F, ny + 0.5F);
                    ws.frontier[frontierLen++] = nIdx;
                } else if (candCost + 1.0E-4F < ws.topoDist[nIdx]) {
                    ws.topoDist[nIdx] = candCost;
                }
            }
        }

        for (int i = 0; i < ws.reachableCount; i++) {
            int idx = ws.reachableList[i];
            int cx = ws.minTx + (idx % GRID_DIM);
            int cy = ws.minTy + (idx / GRID_DIM);
            int clr = 0;
            for (int d = 0; d < 4; d++) {
                int nx = cx + DIR_DX[d];
                int ny = cy + DIR_DY[d];
                int nIdx = gridIdx(nx, ny, ws);
                if (nIdx >= 0 && ws.reachable[nIdx] && probe.canStepTile(cx, cy, nx, ny)) {
                    clr++;
                }
            }
            ws.clearance[idx] = clr;
        }
    }

    private static int findSeedTileIndex(
            int centerTx, int centerTy, float seedX, float seedY, TerrainProbe probe, Workspace ws) {
        if (probe.canStandSafe(centerTx + 0.5F, centerTy + 0.5F)) {
            return gridIdx(centerTx, centerTy, ws);
        }
        int bestIdx = -1;
        float bestDistSq = Float.MAX_VALUE;
        boolean foundClear = false;
        for (int r = 1; r <= 3; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) {
                        continue;
                    }
                    int tx = centerTx + dx;
                    int ty = centerTy + dy;
                    int idx = gridIdx(tx, ty, ws);
                    if (idx < 0) {
                        continue;
                    }
                    float cx = tx + 0.5F;
                    float cy = ty + 0.5F;
                    if (!probe.canStandSafe(cx, cy)) {
                        continue;
                    }
                    boolean segClear = probe.isSegmentClear(seedX, seedY, cx, cy);
                    float dSq = (cx - seedX) * (cx - seedX) + (cy - seedY) * (cy - seedY);
                    if (segClear && (!foundClear || dSq < bestDistSq)) {
                        foundClear = true;
                        bestDistSq = dSq;
                        bestIdx = idx;
                    } else if (!foundClear && dSq < bestDistSq) {
                        bestDistSq = dSq;
                        bestIdx = idx;
                    }
                }
            }
            if (bestIdx >= 0 && foundClear) {
                return bestIdx;
            }
        }
        return bestIdx;
    }

    private static float chooseElasticWidthScale(
            int count, FormationType fmt, float lam,
            float anchorX, float anchorY, float ux, float uy, float outerMinDist,
            TerrainProbe probe, Workspace ws) {
        if (count < 2 || (fmt != FormationType.WEDGE && fmt != FormationType.LINE) || ws.reachableCount == 0) {
            return 1.0F;
        }
        int probeCount = Math.min(count, 4);
        int keepThreshold = probeCount >= 4 ? (probeCount - 1) : probeCount;

        int fullValid = countValidScaledSlots(probeCount, fmt, count, lam, 1.0F, anchorX, anchorY, ux, uy, outerMinDist, probe, ws);
        if (fullValid >= keepThreshold) {
            return 1.0F;
        }

        float bestScale = 1.0F;
        int bestValid = fullValid;
        for (int i = 1; i < WIDTH_SCALES.length; i++) {
            float sc = WIDTH_SCALES[i];
            int valid = countValidScaledSlots(probeCount, fmt, count, lam, sc, anchorX, anchorY, ux, uy, outerMinDist, probe, ws);
            if (valid > bestValid) {
                bestValid = valid;
                bestScale = sc;
            }
        }
        return bestScale;
    }

    private static int countValidScaledSlots(
            int probeCount, FormationType fmt, int totalCount, float lam, float wScale,
            float anchorX, float anchorY, float ux, float uy, float outerMinDist,
            TerrainProbe probe, Workspace ws) {
        int valid = 0;
        for (int s = 0; s < probeCount; s++) {
            EmberFormationMatcher.slotOffset(fmt, s, totalCount, lam, ws.offsetScratch);
            float b = ws.offsetScratch[0];
            float sd = ws.offsetScratch[1] * wScale;
            if (outerMinDist > 0.0F) {
                float r = (float) Math.hypot(b, sd);
                if (r < 0.05F) {
                    b = outerMinDist;
                    sd = 0.0F;
                } else if (r < outerMinDist) {
                    float scale = outerMinDist / r;
                    b *= scale;
                    sd *= scale;
                }
            }
            float x = anchorX - ux * b - uy * sd;
            float y = anchorY - uy * b + ux * sd;
            if (isTileReachable(x, y, ws) && probe.canStandSafe(x, y)) {
                valid++;
            }
        }
        return valid;
    }

    private static boolean tryPlaceSingleSlot(
            int slotIdx, float ix, float iy, float rawBack, float rawSide,
            float anchorX, float anchorY, float seedX, float seedY,
            float ux, float uy, float effAnchorDist,
            TerrainProbe probe, Workspace ws, float[] outX, float[] outY) {
        // Tier 1: Ideal compressed position on open walkable reachable ground (without excessive wall-wrap detour)
        if (isAcceptable(ix, iy, slotIdx, MIN_SLOT_SEPARATION, effAnchorDist, anchorX, anchorY, seedX, seedY, true, true, probe, ws, outX, outY)) {
            outX[slotIdx] = ix;
            outY[slotIdx] = iy;
            return true;
        }

        // Tier 2a: Lateral squeeze toward centerline
        if (Math.abs(rawSide) > 0.15F) {
            for (float sFrac : SIDE_SQUEEZE_FRACS) {
                float sSide = rawSide * sFrac;
                float cx = anchorX - ux * rawBack - uy * sSide;
                float cy = anchorY - uy * rawBack + ux * sSide;
                if (isAcceptable(cx, cy, slotIdx, MIN_SLOT_SEPARATION, effAnchorDist, anchorX, anchorY, seedX, seedY, true, false, probe, ws, outX, outY)) {
                    outX[slotIdx] = cx;
                    outY[slotIdx] = cy;
                    return true;
                }
            }
        }

        // Tier 2b: Ray pullback along vector from ideal slot toward anchor
        for (float t : RAY_PULLBACK_T) {
            float cx = anchorX + (ix - anchorX) * t;
            float cy = anchorY + (iy - anchorY) * t;
            if (isAcceptable(cx, cy, slotIdx, MIN_SLOT_SEPARATION, effAnchorDist, anchorX, anchorY, seedX, seedY, true, false, probe, ws, outX, outY)) {
                outX[slotIdx] = cx;
                outY[slotIdx] = cy;
                return true;
            }
        }

        // Tier 3: BFS connected-component optimal fill (prefer open ground first, then any standable ground)
        if (pickBestReachableCell(slotIdx, ix, iy, anchorX, anchorY, MIN_SLOT_SEPARATION, effAnchorDist, true, probe, ws, outX, outY)
                || pickBestReachableCell(slotIdx, ix, iy, anchorX, anchorY, MIN_SLOT_SEPARATION, effAnchorDist, false, probe, ws, outX, outY)) {
            return true;
        }

        // Tier 4: Extreme cramped-room fallback (tighter separation -> max min-dist reachable tile -> seed fallback)
        if (pickBestReachableCell(slotIdx, ix, iy, anchorX, anchorY, TIGHT_SLOT_SEPARATION, effAnchorDist, true, probe, ws, outX, outY)
                || pickBestReachableCell(slotIdx, ix, iy, anchorX, anchorY, TIGHT_SLOT_SEPARATION, effAnchorDist, false, probe, ws, outX, outY)
                || pickBestReachableCell(slotIdx, ix, iy, anchorX, anchorY, TIGHT_SLOT_SEPARATION, 0.35F, false, probe, ws, outX, outY)
                || pickMaxMinDistReachableCell(slotIdx, anchorX, anchorY, ws, outX, outY)) {
            return true;
        }

        outX[slotIdx] = seedX;
        outY[slotIdx] = seedY;
        return false;
    }

    private static boolean isAcceptable(
            float cx, float cy, int placedCount, float minSep, float minAnchorDist,
            float anchorX, float anchorY, float seedX, float seedY,
            boolean requireOpen, boolean checkDetour,
            TerrainProbe probe, Workspace ws, float[] placedX, float[] placedY) {
        if ((cx - anchorX) * (cx - anchorX) + (cy - anchorY) * (cy - anchorY) < minAnchorDist * minAnchorDist) {
            return false;
        }
        int idx = tileIdxAt(cx, cy, ws);
        if (idx < 0 || !ws.reachable[idx]) {
            return false;
        }
        if (checkDetour) {
            float directDist = (float) Math.hypot(cx - seedX, cy - seedY);
            if (ws.topoDist[idx] > directDist + 3.5F) {
                return false;
            }
        }
        if (!probe.canStandSafe(cx, cy)) {
            return false;
        }
        if (requireOpen && !probe.isOpenGround(cx, cy)) {
            return false;
        }
        float sepSq = minSep * minSep;
        for (int p = 0; p < placedCount; p++) {
            float dx = cx - placedX[p];
            float dy = cy - placedY[p];
            if (dx * dx + dy * dy < sepSq) {
                return false;
            }
        }
        return true;
    }

    private static boolean pickBestReachableCell(
            int slotIdx, float ix, float iy, float anchorX, float anchorY,
            float minSep, float minAnchorDist, boolean requireOpen,
            TerrainProbe probe, Workspace ws, float[] placedX, float[] placedY) {
        float bestScore = Float.MAX_VALUE;
        int bestIdx = -1;
        float minAnchSq = minAnchorDist * minAnchorDist;
        float sepSq = minSep * minSep;

        for (int i = 0; i < ws.reachableCount; i++) {
            int idx = ws.reachableList[i];
            if (requireOpen && !ws.openGround[idx]) {
                continue;
            }
            float cx = tileCenterX(idx, ws);
            float cy = tileCenterY(idx, ws);
            if ((cx - anchorX) * (cx - anchorX) + (cy - anchorY) * (cy - anchorY) < minAnchSq) {
                continue;
            }
            boolean overlaps = false;
            for (int p = 0; p < slotIdx; p++) {
                float dx = cx - placedX[p];
                float dy = cy - placedY[p];
                if (dx * dx + dy * dy < sepSq) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) {
                continue;
            }
            float idealDist = (float) Math.hypot(cx - ix, cy - iy);
            float losPenalty = probe.isSegmentClear(anchorX, anchorY, cx, cy) ? 0.0F : 0.60F;
            float score = idealDist + 0.45F * ws.topoDist[idx] - 0.25F * ws.clearance[idx] + losPenalty;
            if (score < bestScore) {
                bestScore = score;
                bestIdx = idx;
            }
        }
        if (bestIdx >= 0) {
            placedX[slotIdx] = tileCenterX(bestIdx, ws);
            placedY[slotIdx] = tileCenterY(bestIdx, ws);
            return true;
        }
        return false;
    }

    private static boolean pickMaxMinDistReachableCell(
            int slotIdx, float anchorX, float anchorY, Workspace ws, float[] placedX, float[] placedY) {
        if (ws.reachableCount == 0) {
            return false;
        }
        int bestIdx = ws.reachableList[0];
        float bestMinSq = -1.0F;
        for (int i = 0; i < ws.reachableCount; i++) {
            int idx = ws.reachableList[i];
            float cx = tileCenterX(idx, ws);
            float cy = tileCenterY(idx, ws);
            float minSq = (cx - anchorX) * (cx - anchorX) + (cy - anchorY) * (cy - anchorY);
            for (int p = 0; p < slotIdx; p++) {
                float dx = cx - placedX[p];
                float dy = cy - placedY[p];
                float dSq = dx * dx + dy * dy;
                if (dSq < minSq) {
                    minSq = dSq;
                }
            }
            if (minSq > bestMinSq) {
                bestMinSq = minSq;
                bestIdx = idx;
            }
        }
        placedX[slotIdx] = tileCenterX(bestIdx, ws);
        placedY[slotIdx] = tileCenterY(bestIdx, ws);
        return true;
    }

    private static boolean isTileReachable(float x, float y, Workspace ws) {
        int idx = tileIdxAt(x, y, ws);
        return idx >= 0 && ws.reachable[idx];
    }

    private static int tileIdxAt(float x, float y, Workspace ws) {
        return gridIdx(floor(x), floor(y), ws);
    }

    private static int gridIdx(int tx, int ty, Workspace ws) {
        int lx = tx - ws.minTx;
        int ly = ty - ws.minTy;
        if (lx < 0 || lx >= GRID_DIM || ly < 0 || ly >= GRID_DIM) {
            return -1;
        }
        return ly * GRID_DIM + lx;
    }

    private static float tileCenterX(int idx, Workspace ws) {
        return ws.minTx + (idx % GRID_DIM) + 0.5F;
    }

    private static float tileCenterY(int idx, Workspace ws) {
        return ws.minTy + (idx / GRID_DIM) + 0.5F;
    }

    private static int floor(float v) {
        return (int) Math.floor(v);
    }
}
