package emberroutes.npc;

import java.util.Arrays;

/**
 * 全战（Total War）式 2D 世界阵位匈牙利动态匹配器。
 *
 * <p>核心职责（纯 Java 无引擎依赖，支持 JUnit 离线单测）：
 * <ul>
 *   <li>根据领袖坐标与当前（或平滑历史）朝向实时投影 N 个跟随者在世界系中的 V 形梯队目标阵位；</li>
 *   <li>使用 1-indexed 精确 \(O(N^3)\) Kuhn-Munkres（匈牙利算法）求全局最小二维欧氏距离（含微幅共线防并列正则项）双射，从几何上杜绝“换向/转弯时队友轨迹交叉打架”；</li>
 *   <li>引入同层优先 + 楼梯高程递减拓扑阶梯代价（submodular 耦合项），确保已上楼队友稳拿前排、楼梯上高位先出、楼下后进；</li>
 *   <li>引入 `-0.65F` 粘滞迟滞奖赏，消除微小位置抖动下的频繁换位鬼畜。</li>
 * </ul>
 */
public final class EmberFormationMatcher {

    /**
     * 层间/楼梯阶梯代价耦合斜率：
     * 差值乘以 (count - 1 - slot) * TIER_SLOPE_WEIGHT，使更低 progressDeficit（更高拓扑进度）
     * 的跟随者严格获得更靠前（slot 编号更小）的阵位。
     */
    static final float TIER_SLOPE_WEIGHT = 80.0F;

    /** 保持上一次已分配阵位时的粘滞代价奖励（负值表示偏好保留原阵位）。 */
    static final float STICKY_BONUS = -0.65F;

    /** 职能代价偏置上限（必须远小于 {@link #TIER_SLOPE_WEIGHT}，且不足以触发跨半场的无谓长距离交叉换位）。 */
    static final float MAX_ROLE_BIAS = 1.80F;

    /** 矩形匈牙利算法中虚拟填充行/列与不可达边使用的统一高成本哨兵值。 */
    public static final double DUMMY_COST = 1.0e6;

    /** 0-GC 矩形匈牙利算法预分配工作区（稳态下复用全部数组零堆分配）。 */
    public static final class RectangularHungarianWorkspace {
        private int capacity;
        private double[][] workCost;
        private float[] slotX;
        private float[] slotY;
        private float[] deficits;
        private final float[] offsetScratch = new float[2];
        private double[][] paddedCost;
        private double[] u;
        private double[] v;
        private double[] minv;
        private int[] p;
        private int[] way;
        private boolean[] used;

        public RectangularHungarianWorkspace() {
            this(16);
        }

        public RectangularHungarianWorkspace(int initialCapacity) {
            ensureCapacity(Math.max(4, initialCapacity));
        }

        void ensureCapacity(int dim) {
            if (dim <= capacity && paddedCost != null) {
                return;
            }
            int nextCap = Math.max(dim, capacity == 0 ? 16 : capacity * 2);
            this.capacity = nextCap;
            this.workCost = new double[nextCap][nextCap];
            this.slotX = new float[nextCap];
            this.slotY = new float[nextCap];
            this.deficits = new float[nextCap];
            this.paddedCost = new double[nextCap + 1][nextCap + 1];
            this.u = new double[nextCap + 1];
            this.v = new double[nextCap + 1];
            this.minv = new double[nextCap + 1];
            this.p = new int[nextCap + 1];
            this.way = new int[nextCap + 1];
            this.used = new boolean[nextCap + 1];
        }
    }

    private EmberFormationMatcher() {}

    /**
     * 计算指定阵型、阵位序号与间距倍率下的局部偏移 {@code [back, side]}（后方为正 {@code back}，右侧为正 {@code side}）。
     */
    public static void slotOffset(
            FormationType formation, int rank, int count, float spacingMul, float[] outBackSide) {
        if (outBackSide == null || outBackSide.length < 2) {
            return;
        }
        FormationType fmt = formation != null ? formation : FormationType.WEDGE;
        int r = Math.max(0, rank);
        int n = Math.max(1, count);
        float lam = (Float.isFinite(spacingMul) && spacingMul > 0.0F) ? spacingMul : 1.0F;

        switch (fmt) {
            case COLUMN -> {
                outBackSide[0] = (1.60F + 1.15F * r) * lam;
                outBackSide[1] = 0.0F;
            }
            case LINE -> {
                int cols = 6;
                int row = r / cols;
                int idx = r % cols;
                int rowCount = Math.min(cols, Math.max(1, n - row * cols));
                float back = (1.65F + 1.30F * row) * lam;
                float step;
                if ((rowCount & 1) == 1) {
                    step = (idx == 0) ? 0.0F : (((idx & 1) == 1 ? -1.0F : 1.0F) * ((idx + 1) / 2));
                } else {
                    step = ((idx & 1) == 0 ? -1.0F : 1.0F) * (0.5F + (idx / 2));
                }
                outBackSide[0] = back;
                outBackSide[1] = step * 1.25F * lam;
            }
            case RING -> {
                float radius = Math.max(1.85F, 1.55F + 0.14F * n) * lam;
                double theta = (2.0 * Math.PI * r) / n;
                outBackSide[0] = -(float) (radius * Math.cos(theta));
                outBackSide[1] = (float) (radius * Math.sin(theta));
            }
            case WEDGE -> {
                if (r <= 0) {
                    outBackSide[0] = 1.60F * lam;
                    outBackSide[1] = 0.0F;
                } else {
                    int tier = (r - 1) / 2;
                    outBackSide[0] = (2.00F + 1.15F * tier) * lam;
                    float mag = (0.95F + 0.35F * tier) * lam;
                    outBackSide[1] = ((r & 1) == 1 ? -1.0F : 1.0F) * mag;
                }
            }
        }
    }

    /**
     * 计算指定阵型阵位的标称距领袖/锚点半径，供速度匹配与追赶门控统一调用。
     */
    public static float slotNominalRadius(
            FormationType formation, int rank, int count, float spacingMul, float[] scratch2) {
        if (scratch2 != null && scratch2.length >= 2) {
            slotOffset(formation, rank, count, spacingMul, scratch2);
            return (float) Math.hypot(scratch2[0], scratch2[1]);
        }
        return slotNominalRadius(formation, rank, count, spacingMul);
    }

    public static float slotNominalRadius(FormationType formation, int rank, int count, float spacingMul) {
        float[] tmp = new float[2];
        slotOffset(formation, rank, count, spacingMul, tmp);
        return (float) Math.hypot(tmp[0], tmp[1]);
    }

    /**
     * 有界战术职能代价偏置（单位等效格距，严格限定在 {@code [0, MAX_ROLE_BIAS]}）。
     */
    public static float roleBias(FormationType formation, TacticalRole role, int rank, int count) {
        if (role == null || role == TacticalRole.SUPPORT || count <= 1) {
            return 0.0F;
        }
        FormationType fmt = formation != null ? formation : FormationType.WEDGE;
        int r = Math.max(0, Math.min(count - 1, rank));
        float t = r / (float) (count - 1);
        return switch (fmt) {
            case LINE -> switch (role) {
                case MELEE -> (1.0F - t) * 1.40F;
                case RANGED -> t * 1.00F;
                case MEDIC -> t * 1.50F;
                default -> 0.0F;
            };
            case RING -> {
                double cosA = Math.cos((2.0 * Math.PI * r) / count);
                yield switch (role) {
                    case MELEE -> (float) ((1.0 - cosA) * 0.65);
                    case RANGED -> (float) ((1.0 + cosA) * 0.50);
                    case MEDIC -> (float) ((1.0 + cosA) * 0.70);
                    default -> 0.0F;
                };
            }
            case WEDGE, COLUMN -> switch (role) {
                case MELEE -> t * 1.40F;
                case RANGED -> (1.0F - t) * 1.10F;
                case MEDIC -> (1.0F - t) * 1.50F;
                default -> 0.0F;
            };
        };
    }

    /** M2 兼容便捷入口（默认 WEDGE + STANDARD 间距）。 */
    public static int[] assignRanks(
            int count,
            float[] followerX,
            float[] followerY,
            float[] followerZ,
            boolean[] followerOnStairs,
            int[] prevAssignedRank,
            float leaderX,
            float leaderY,
            float leaderZ,
            float headingX,
            float headingY) {
        if (count <= 0) {
            return new int[0];
        }
        int[] out = new int[count];
        assignRanks(
                count, followerX, followerY, followerZ, followerOnStairs, prevAssignedRank,
                null, FormationType.WEDGE, FormationSpacing.STANDARD, false,
                leaderX, leaderY, leaderZ, headingX, headingY, out, null);
        return out;
    }

    /** 完整四阵型、三间距与战术职能偏置求解入口（返回新数组供测试/离散调用）。 */
    public static int[] assignRanks(
            int count,
            float[] followerX,
            float[] followerY,
            float[] followerZ,
            boolean[] followerOnStairs,
            int[] prevAssignedRank,
            TacticalRole[] followerRoles,
            FormationType formation,
            FormationSpacing spacing,
            boolean ignoreSticky,
            float leaderX,
            float leaderY,
            float leaderZ,
            float headingX,
            float headingY) {
        if (count <= 0) {
            return new int[0];
        }
        int[] out = new int[count];
        assignRanks(
                count, followerX, followerY, followerZ, followerOnStairs, prevAssignedRank,
                followerRoles, formation, spacing, ignoreSticky,
                leaderX, leaderY, leaderZ, headingX, headingY, out, null);
        return out;
    }

    /**
     * 0-GC 原位四阵型动态双射求解器。
     */
    public static void assignRanks(
            int count,
            float[] followerX,
            float[] followerY,
            float[] followerZ,
            boolean[] followerOnStairs,
            int[] prevAssignedRank,
            TacticalRole[] followerRoles,
            FormationType formation,
            FormationSpacing spacing,
            boolean ignoreSticky,
            float leaderX,
            float leaderY,
            float leaderZ,
            float headingX,
            float headingY,
            int[] outRanks,
            RectangularHungarianWorkspace workspace) {
        if (count <= 0 || outRanks == null || outRanks.length < count) {
            return;
        }
        if (count == 1) {
            outRanks[0] = 0;
            return;
        }

        double hLen = Math.hypot(headingX, headingY);
        float ux = hLen > 1.0E-4 ? (float) (headingX / hLen) : 0.0F;
        float uy = hLen > 1.0E-4 ? (float) (headingY / hLen) : 1.0F;

        RectangularHungarianWorkspace ws = workspace != null ? workspace : new RectangularHungarianWorkspace(count);
        ws.ensureCapacity(count);

        FormationType fmt = formation != null ? formation : FormationType.WEDGE;
        float lam = spacing != null ? spacing.multiplier() : 1.0F;
        for (int s = 0; s < count; s++) {
            slotOffset(fmt, s, count, lam, ws.offsetScratch);
            float back = ws.offsetScratch[0];
            float side = ws.offsetScratch[1];
            ws.slotX[s] = leaderX - ux * back - uy * side;
            ws.slotY[s] = leaderY - uy * back + ux * side;
        }

        int leaderFloor = levelOf(leaderZ);
        for (int i = 0; i < count; i++) {
            ws.deficits[i] = computeProgressDeficit(
                    followerZ[i],
                    followerOnStairs != null && followerOnStairs[i],
                    leaderZ,
                    leaderFloor);
        }

        for (int i = 0; i < count; i++) {
            float fx = followerX[i];
            float fy = followerY[i];
            float deficit = ws.deficits[i];
            int prevRank = (!ignoreSticky && prevAssignedRank != null && i < prevAssignedRank.length)
                    ? prevAssignedRank[i] : -1;
            TacticalRole role = (followerRoles != null && i < followerRoles.length) ? followerRoles[i] : null;
            double[] row = ws.workCost[i];
            for (int s = 0; s < count; s++) {
                double dx = fx - ws.slotX[s];
                double dy = fy - ws.slotY[s];
                double distSq = dx * dx + dy * dy;
                double distCost = Math.sqrt(distSq) + 1.0E-5 * distSq;
                double tierCost = (double) deficit * (double) (count - 1 - s) * TIER_SLOPE_WEIGHT;
                double sticky = (prevRank == s) ? STICKY_BONUS : 0.0;
                double rBias = roleBias(fmt, role, s, count);
                row[s] = distCost + tierCost + sticky + rBias;
            }
        }

        solveRectangularHungarian(count, count, ws.workCost, outRanks, ws);
    }

    static float computeProgressDeficit(float fZ, boolean onStairs, float leaderZ, int leaderFloor) {
        if (onStairs) {
            return 1.0F + Math.abs(leaderZ - fZ) * 2.5F;
        }
        int fFloor = levelOf(fZ);
        if (fFloor == leaderFloor) {
            return 0.0F;
        }
        return 1.0F + Math.abs(leaderZ - fZ) * 2.5F + 1.0F;
    }

    private static int levelOf(float z) {
        return Math.max(0, (int) Math.floor(z + 1.0E-4F));
    }

    /**
     * 0-GC 矩形匈牙利最小权匹配求解器（自动以 {@link #DUMMY_COST} 补齐虚拟行/列并剔除不可达配对）：
     * <ul>
     *   <li>{@code outRowToCol[r]} 写入第 {@code r} 行匹配到的真实列号 {@code 0..cols-1}，未匹配或被不可达哨兵剔除则为 {@code -1}；</li>
     *   <li>传入可复用的 {@link RectangularHungarianWorkspace} 时稳态 0 堆分配。</li>
     * </ul>
     */
    public static void solveRectangularHungarian(
            int rows,
            int cols,
            double[][] costMatrix,
            int[] outRowToCol,
            RectangularHungarianWorkspace workspace) {
        if (rows <= 0) {
            return;
        }
        if (outRowToCol != null) {
            Arrays.fill(outRowToCol, 0, Math.min(rows, outRowToCol.length), -1);
        }
        if (cols <= 0 || costMatrix == null || outRowToCol == null) {
            return;
        }

        int dim = Math.max(rows, cols);
        RectangularHungarianWorkspace ws = workspace != null ? workspace : new RectangularHungarianWorkspace(dim);
        ws.ensureCapacity(dim);

        double threshold = DUMMY_COST * 0.5;
        for (int r = 0; r < dim; r++) {
            double[] srcRow = (r < rows && r < costMatrix.length) ? costMatrix[r] : null;
            double[] dstRow = ws.paddedCost[r + 1];
            for (int c = 0; c < dim; c++) {
                double val = DUMMY_COST;
                if (srcRow != null && c < cols && c < srcRow.length) {
                    double raw = srcRow[c];
                    if (Double.isFinite(raw) && raw < threshold) {
                        val = raw;
                    }
                }
                dstRow[c + 1] = val;
            }
        }

        Arrays.fill(ws.u, 0, dim + 1, 0.0);
        Arrays.fill(ws.v, 0, dim + 1, 0.0);
        Arrays.fill(ws.p, 0, dim + 1, 0);
        Arrays.fill(ws.way, 0, dim + 1, 0);

        runHungarianCore(dim, ws.paddedCost, ws.u, ws.v, ws.p, ws.way, ws.used, ws.minv);

        for (int j = 1; j <= dim; j++) {
            int r = ws.p[j] - 1;
            int c = j - 1;
            if (r >= 0 && r < rows && r < outRowToCol.length) {
                if (c >= 0 && c < cols && ws.paddedCost[r + 1][c + 1] < threshold) {
                    outRowToCol[r] = c;
                } else {
                    outRowToCol[r] = -1;
                }
            }
        }
    }

    private static void runHungarianCore(
            int n,
            double[][] cost,
            double[] u,
            double[] v,
            int[] p,
            int[] way,
            boolean[] used,
            double[] minv) {
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            Arrays.fill(minv, 0, n + 1, Double.POSITIVE_INFINITY);
            Arrays.fill(used, 0, n + 1, false);
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = 0;
                for (int j = 1; j <= n; j++) {
                    if (used[j]) {
                        continue;
                    }
                    double cur = cost[i0][j] - u[i0] - v[j];
                    if (cur < minv[j]) {
                        minv[j] = cur;
                        way[j] = j0;
                    }
                    if (minv[j] < delta) {
                        delta = minv[j];
                        j1 = j;
                    }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);

            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
    }
}
