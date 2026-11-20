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

    private EmberFormationMatcher() {}

    /**
     * 计算跟随者到阵位序号 {@code 0 .. count-1} 的全局最优双射分配。
     *
     * @param count              参与分配的活跃跟随者数量
     * @param followerX          各跟随者世界 X 坐标
     * @param followerY          各跟随者世界 Y 坐标
     * @param followerZ          各跟随者世界连续 Z 高程（含楼梯斜面高度）
     * @param followerOnStairs   各跟随者当前是否踩在楼梯格子上
     * @param prevAssignedRank   各跟随者上次分配的阵位序号（无历史时传 &lt; 0，可为 null）
     * @param leaderX            领袖世界 X 坐标
     * @param leaderY            领袖世界 Y 坐标
     * @param leaderZ            领袖世界连续 Z 高程
     * @param headingX           阵型朝向分量 X
     * @param headingY           阵型朝向分量 Y
     * @return 长度为 {@code count} 的分配结果数组，{@code result[i]} 为第 {@code i} 名跟随者获得的阵位 rank
     */
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
        if (count == 1) {
            out[0] = 0;
            return out;
        }

        double hLen = Math.hypot(headingX, headingY);
        float ux = hLen > 1.0E-4 ? (float) (headingX / hLen) : 0.0F;
        float uy = hLen > 1.0E-4 ? (float) (headingY / hLen) : 1.0F;

        float[] slotX = new float[count];
        float[] slotY = new float[count];
        for (int s = 0; s < count; s++) {
            float back = EmberFollowGaitControl.slotBack(s);
            float side = EmberFollowGaitControl.slotSide(s);
            slotX[s] = leaderX - ux * back - uy * side;
            slotY[s] = leaderY - uy * back + ux * side;
        }

        int leaderFloor = levelOf(leaderZ);
        float[] deficits = new float[count];
        for (int i = 0; i < count; i++) {
            deficits[i] = computeProgressDeficit(
                    followerZ[i],
                    followerOnStairs != null && followerOnStairs[i],
                    leaderZ,
                    leaderFloor);
        }

        // 1-indexed 矩阵 [1..count][1..count]
        double[][] cost = new double[count + 1][count + 1];
        for (int i = 0; i < count; i++) {
            float fx = followerX[i];
            float fy = followerY[i];
            float deficit = deficits[i];
            int prevRank = (prevAssignedRank != null && i < prevAssignedRank.length) ? prevAssignedRank[i] : -1;
            for (int s = 0; s < count; s++) {
                double dx = fx - slotX[s];
                double dy = fy - slotY[s];
                double distSq = dx * dx + dy * dy;
                double distCost = Math.sqrt(distSq) + 1.0E-5 * distSq;
                double tierCost = (double) deficit * (double) (count - 1 - s) * TIER_SLOPE_WEIGHT;
                double sticky = (prevRank == s) ? STICKY_BONUS : 0.0;
                cost[i + 1][s + 1] = distCost + tierCost + sticky;
            }
        }

        return solveHungarian1Indexed(count, cost);
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
     * 标准 1-indexed \(O(N^3)\) Kuhn-Munkres / 匈牙利最小权完备匹配求解器。
     */
    private static int[] solveHungarian1Indexed(int n, double[][] cost) {
        double[] u = new double[n + 1];
        double[] v = new double[n + 1];
        int[] p = new int[n + 1];
        int[] way = new int[n + 1];
        double[] minv = new double[n + 1];
        boolean[] used = new boolean[n + 1];

        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            Arrays.fill(minv, Double.POSITIVE_INFINITY);
            Arrays.fill(used, false);
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

        int[] rowToCol0Indexed = new int[n];
        for (int j = 1; j <= n; j++) {
            if (p[j] >= 1 && p[j] <= n) {
                rowToCol0Indexed[p[j] - 1] = j - 1;
            }
        }
        return rowToCol0Indexed;
    }
}
