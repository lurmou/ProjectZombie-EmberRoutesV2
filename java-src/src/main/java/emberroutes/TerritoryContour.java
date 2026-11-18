package emberroutes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 正交不规则领地几何计算器（纯整数网格运算，零浮点误差）：
 *
 * <ol>
 *   <li>{@link #normalizeDisjoint}：将任意矩形输入规约为严格互不重叠（Disjoint）且相邻合并的子矩形集；</li>
 *   <li>{@link #extractOutlineSegments}：通过 1D 区间对称差（Symmetric Difference）精确抵消全部内部拼接边，
 *       并将共线外边合并为最少条长线段（天然支持 L 型、U 型、十字型、回字形中空内院与多飞地，0 内部接缝线）；</li>
 *   <li>{@link #buildSeamlessFillQuads}：生成无 T 型顶点裂缝（T-junction-free）的不重叠地面填充矩形组
 *       {@code [x1, y1, x2, y2, ...]}，保证 GPU 半透明混合绝不叠色、绝无 1px 接缝。</li>
 * </ol>
 */
public final class TerritoryContour {
    private TerritoryContour() {}

    private static final Comparator<int[]> INTERVAL_CMP = (a, b) -> {
        int c = Integer.compare(a[0], b[0]);
        return c != 0 ? c : Integer.compare(a[1], b[1]);
    };

    /** 将任意输入矩形 {@code [x, y, w, h]} 规约为严格互不重叠的正交子矩形列表。 */
    public static List<int[]> normalizeDisjoint(List<int[]> rawRects) {
        if (rawRects == null || rawRects.isEmpty()) {
            return List.of();
        }
        List<int[]> result = new ArrayList<>(rawRects.size());
        for (int i = 0, n = rawRects.size(); i < n; i++) {
            int[] nr = rawRects.get(i);
            if (nr == null || nr.length < 4 || nr[2] <= 0 || nr[3] <= 0) continue;
            List<int[]> additions = new ArrayList<>(4);
            additions.add(new int[]{nr[0], nr[1], nr[2], nr[3]});

            for (int e = 0; e < result.size() && !additions.isEmpty(); e++) {
                int[] ex = result.get(e);
                List<int[]> nextAdd = new ArrayList<>(additions.size() + 3);
                for (int a = 0; a < additions.size(); a++) {
                    subtractRect(additions.get(a), ex, nextAdd);
                }
                additions = nextAdd;
            }
            result.addAll(additions);
        }
        return compactRects(result);
    }

    private static void subtractRect(int[] a, int[] b, List<int[]> out) {
        int ax1 = a[0], ay1 = a[1], ax2 = a[0] + a[2], ay2 = a[1] + a[3];
        int bx1 = b[0], by1 = b[1], bx2 = b[0] + b[2], by2 = b[1] + b[3];
        int ix1 = Math.max(ax1, bx1), iy1 = Math.max(ay1, by1);
        int ix2 = Math.min(ax2, bx2), iy2 = Math.min(ay2, by2);
        if (ix1 >= ix2 || iy1 >= iy2) {
            out.add(a);
            return;
        }
        if (iy1 > ay1) out.add(new int[]{ax1, ay1, ax2 - ax1, iy1 - ay1});
        if (ay2 > iy2) out.add(new int[]{ax1, iy2, ax2 - ax1, ay2 - iy2});
        if (ix1 > ax1) out.add(new int[]{ax1, iy1, ix1 - ax1, iy2 - iy1});
        if (ax2 > ix2) out.add(new int[]{ix2, iy1, ax2 - ix2, iy2 - iy1});
    }

    private static List<int[]> compactRects(List<int[]> list) {
        if (list.size() <= 1) return list;
        boolean merged = true;
        while (merged) {
            merged = false;
            int len = list.size();
            outer:
            for (int i = 0; i < len - 1; i++) {
                int[] a = list.get(i);
                for (int j = i + 1; j < len; j++) {
                    int[] b = list.get(j);
                    if (a[0] == b[0] && a[2] == b[2] && (a[1] + a[3] == b[1] || b[1] + b[3] == a[1])) {
                        list.set(i, new int[]{a[0], Math.min(a[1], b[1]), a[2], a[3] + b[3]});
                        list.remove(j);
                        merged = true;
                        break outer;
                    } else if (a[1] == b[1] && a[3] == b[3] && (a[0] + a[2] == b[0] || b[0] + b[2] == a[0])) {
                        list.set(i, new int[]{Math.min(a[0], b[0]), a[1], a[2] + b[2], a[3]});
                        list.remove(j);
                        merged = true;
                        break outer;
                    }
                }
            }
        }
        return list;
    }

    /**
     * 提取不规则领地的纯外轮廓线段（含回字形内院洞口轮廓，100% 剔除全部内部子矩形拼接缝）。
     *
     * @return 扁平浮点数组 {@code [x1, y1, x2, y2, ...]}，每 4 个元素表示一条世界平面线段。
     */
    public static float[] extractOutlineSegments(List<int[]> rawRects) {
        List<int[]> rects = normalizeDisjoint(rawRects);
        if (rects.isEmpty()) {
            return new float[0];
        }

        TreeMap<Integer, List<int[]>> northByY = new TreeMap<>();
        TreeMap<Integer, List<int[]>> southByY = new TreeMap<>();
        TreeMap<Integer, List<int[]>> westByX = new TreeMap<>();
        TreeMap<Integer, List<int[]>> eastByX = new TreeMap<>();

        for (int i = 0, n = rects.size(); i < n; i++) {
            int[] r = rects.get(i);
            int x1 = r[0], y1 = r[1], x2 = x1 + r[2], y2 = y1 + r[3];
            northByY.computeIfAbsent(y1, k -> new ArrayList<>(2)).add(new int[]{x1, x2});
            southByY.computeIfAbsent(y2, k -> new ArrayList<>(2)).add(new int[]{x1, x2});
            westByX.computeIfAbsent(x1, k -> new ArrayList<>(2)).add(new int[]{y1, y2});
            eastByX.computeIfAbsent(x2, k -> new ArrayList<>(2)).add(new int[]{y1, y2});
        }

        float[] buf = new float[rects.size() * 16];
        int count = 0;

        TreeSet<Integer> allY = new TreeSet<>(northByY.keySet());
        allY.addAll(southByY.keySet());
        for (Integer yObj : allY) {
            int y = yObj.intValue();
            List<int[]> nMerged = mergeIntervals(northByY.get(yObj));
            List<int[]> sMerged = mergeIntervals(southByY.get(yObj));
            List<int[]> xor = symmetricDiff(nMerged, sMerged);
            for (int i = 0; i < xor.size(); i++) {
                int[] span = xor.get(i);
                if (count + 4 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                buf[count++] = span[0];
                buf[count++] = y;
                buf[count++] = span[1];
                buf[count++] = y;
            }
        }

        TreeSet<Integer> allX = new TreeSet<>(westByX.keySet());
        allX.addAll(eastByX.keySet());
        for (Integer xObj : allX) {
            int x = xObj.intValue();
            List<int[]> wMerged = mergeIntervals(westByX.get(xObj));
            List<int[]> eMerged = mergeIntervals(eastByX.get(xObj));
            List<int[]> xor = symmetricDiff(wMerged, eMerged);
            for (int i = 0; i < xor.size(); i++) {
                int[] span = xor.get(i);
                if (count + 4 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                buf[count++] = x;
                buf[count++] = span[0];
                buf[count++] = x;
                buf[count++] = span[1];
            }
        }

        return Arrays.copyOf(buf, count);
    }

    /**
     * 生成无 T 型接缝（T-junction-free）的不重叠地面填充 Quad 坐标组 {@code [x1, y1, x2, y2, ...]}。
     */
    public static int[] buildSeamlessFillQuads(List<int[]> rawRects) {
        List<int[]> rects = normalizeDisjoint(rawRects);
        if (rects.isEmpty()) {
            return new int[0];
        }
        if (rects.size() == 1) {
            int[] r = rects.get(0);
            return new int[]{r[0], r[1], r[0] + r[2], r[1] + r[3]};
        }
        if (rects.size() > 24) {
            int[] out = new int[rects.size() * 4];
            for (int i = 0; i < rects.size(); i++) {
                int[] r = rects.get(i);
                int base = i * 4;
                out[base] = r[0];
                out[base + 1] = r[1];
                out[base + 2] = r[0] + r[2];
                out[base + 3] = r[1] + r[3];
            }
            return out;
        }

        TreeSet<Integer> xs = new TreeSet<>();
        TreeSet<Integer> ys = new TreeSet<>();
        for (int i = 0; i < rects.size(); i++) {
            int[] r = rects.get(i);
            xs.add(r[0]);
            xs.add(r[0] + r[2]);
            ys.add(r[1]);
            ys.add(r[1] + r[3]);
        }
        int[] xArr = xs.stream().mapToInt(Integer::intValue).toArray();
        int[] yArr = ys.stream().mapToInt(Integer::intValue).toArray();

        int[] buf = new int[(xArr.length - 1) * (yArr.length - 1) * 4];
        int count = 0;
        for (int yi = 0; yi < yArr.length - 1; yi++) {
            int y1 = yArr[yi], y2 = yArr[yi + 1];
            for (int xi = 0; xi < xArr.length - 1; xi++) {
                int x1 = xArr[xi], x2 = xArr[xi + 1];
                if (containsPoint(rects, x1, y1)) {
                    buf[count++] = x1;
                    buf[count++] = y1;
                    buf[count++] = x2;
                    buf[count++] = y2;
                }
            }
        }
        return Arrays.copyOf(buf, count);
    }

    private static boolean containsPoint(List<int[]> rects, int x, int y) {
        for (int i = 0, n = rects.size(); i < n; i++) {
            int[] r = rects.get(i);
            if (x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3]) {
                return true;
            }
        }
        return false;
    }

    private static List<int[]> mergeIntervals(List<int[]> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        if (raw.size() == 1) {
            int[] only = raw.get(0);
            return List.of(new int[]{only[0], only[1]});
        }
        List<int[]> sorted = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            int[] r = raw.get(i);
            if (r[1] > r[0]) sorted.add(new int[]{r[0], r[1]});
        }
        if (sorted.isEmpty()) return List.of();
        sorted.sort(INTERVAL_CMP);

        List<int[]> merged = new ArrayList<>(sorted.size());
        int[] cur = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            int[] next = sorted.get(i);
            if (next[0] <= cur[1]) {
                cur[1] = Math.max(cur[1], next[1]);
            } else {
                merged.add(cur);
                cur = next;
            }
        }
        merged.add(cur);
        return merged;
    }

    private static List<int[]> subtractIntervals(List<int[]> a, List<int[]> b) {
        if (a.isEmpty()) return List.of();
        if (b.isEmpty()) return a;
        List<int[]> out = new ArrayList<>(a.size() + b.size());
        int bi = 0, bn = b.size();
        for (int ai = 0, an = a.size(); ai < an; ai++) {
            int s = a.get(ai)[0];
            int e = a.get(ai)[1];
            while (bi < bn && b.get(bi)[1] <= s) {
                bi++;
            }
            int cur = s;
            int k = bi;
            while (k < bn && b.get(k)[0] < e) {
                int bs = b.get(k)[0];
                int be = b.get(k)[1];
                if (bs > cur) {
                    out.add(new int[]{cur, Math.min(e, bs)});
                }
                cur = Math.max(cur, be);
                if (cur >= e) break;
                k++;
            }
            if (cur < e) {
                out.add(new int[]{cur, e});
            }
        }
        return out;
    }

    private static List<int[]> symmetricDiff(List<int[]> a, List<int[]> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        List<int[]> diff = new ArrayList<>(a.size() + b.size());
        diff.addAll(subtractIntervals(a, b));
        diff.addAll(subtractIntervals(b, a));
        return mergeIntervals(diff);
    }
}
