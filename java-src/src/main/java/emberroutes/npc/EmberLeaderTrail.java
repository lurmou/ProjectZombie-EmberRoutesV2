package emberroutes.npc;

import java.lang.ref.WeakReference;

/**
 * 领队移动轨迹环形缓冲（100% 纯几何 + 零每帧分配）：
 * - 按 0.25 格间距采样领队实际走过的脚印坐标（楼层切换或 > 2.5 格瞬移/上下车跳变自动断段清零）；
 * - 所有跟随同一玩家槽位的 NPC 共享同一份轨迹，从几何上确保“玩家原地转鼠标不公转、进出房门与拐角沿脚印走”。
 */
public final class EmberLeaderTrail {
    public static final int CAPACITY = 96;
    /** 采样最小间距平方：0.25^2 = 0.0625 格，保证门框级别精度且不浪费缓冲。 */
    public static final float SPACING_SQ = 0.0625F;
    /** 断段阈值平方：2.5^2 = 6.25 格（瞬移/传送/载具位移不插值连线）。 */
    public static final float JUMP_SQ = 6.25F;

    private static final int MAX_LOCAL_PLAYERS = 4;
    private static final EmberLeaderTrail[] SHARED = new EmberLeaderTrail[MAX_LOCAL_PLAYERS];

    private final float[] xs = new float[CAPACITY];
    private final float[] ys = new float[CAPACITY];
    private final int[] zs = new int[CAPACITY];
    private int head = -1;
    private int count;
    private float lastX;
    private float lastY;
    private int lastZ;
    private WeakReference<Object> ownerRef;

    public static synchronized EmberLeaderTrail forLeader(int playerIndex, Object ownerIdentity) {
        int idx = (playerIndex >= 0 && playerIndex < MAX_LOCAL_PLAYERS) ? playerIndex : 0;
        EmberLeaderTrail trail = SHARED[idx];
        if (trail == null) {
            trail = new EmberLeaderTrail();
            SHARED[idx] = trail;
        }
        Object prevOwner = trail.ownerRef != null ? trail.ownerRef.get() : null;
        if (ownerIdentity != null && prevOwner != ownerIdentity) {
            trail.reset();
            trail.ownerRef = new WeakReference<>(ownerIdentity);
        }
        return trail;
    }

    public static synchronized void resetAll() {
        for (int i = 0; i < SHARED.length; i++) {
            if (SHARED[i] != null) {
                SHARED[i].reset();
                SHARED[i].ownerRef = null;
            }
        }
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public int getCount() {
        return count;
    }

    public int getFloor() {
        return lastZ;
    }

    public void reset() {
        head = -1;
        count = 0;
    }

    public void record(float x, float y, int floor) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return;
        }
        if (count == 0) {
            push(x, y, floor);
            return;
        }
        float dx = x - lastX;
        float dy = y - lastY;
        float distSq = dx * dx + dy * dy;
        if (floor != lastZ || distSq > JUMP_SQ) {
            count = 0;
            push(x, y, floor);
            return;
        }
        if (distSq < SPACING_SQ) {
            return;
        }
        push(x, y, floor);
    }

    /**
     * 从最新位置回溯 {@code backDistance} 格，写出 [x, y, dirX, dirY] 至 {@code outXYDir}（长度 >= 4）。
     * 若缓冲不足以回溯到该距离则返回 {@code false}，由调用方做投影兜底而非向历史外推。
     */
    public boolean sample(float backDistance, float[] outXYDir) {
        if (outXYDir == null || outXYDir.length < 4 || count == 0) {
            return false;
        }
        float remaining = backDistance > 0.0F ? backDistance : 0.0F;
        float curX = xs[head];
        float curY = ys[head];

        if (remaining <= 0.0005F) {
            outXYDir[0] = curX;
            outXYDir[1] = curY;
            if (count > 1) {
                int prevIdx = indexBack(1);
                writeDir(curX - xs[prevIdx], curY - ys[prevIdx], outXYDir);
            } else {
                outXYDir[2] = 0.0F;
                outXYDir[3] = 0.0F;
            }
            return true;
        }

        for (int i = 1; i < count; i++) {
            int olderIdx = indexBack(i);
            float px = xs[olderIdx];
            float py = ys[olderIdx];
            float dx = curX - px;
            float dy = curY - py;
            float segLen = (float) Math.hypot(dx, dy);
            if (segLen <= 0.001F) {
                continue;
            }
            if (remaining <= segLen) {
                float t = remaining / segLen;
                outXYDir[0] = curX + (px - curX) * t;
                outXYDir[1] = curY + (py - curY) * t;
                writeDir(dx, dy, outXYDir);
                return true;
            }
            remaining -= segLen;
            curX = px;
            curY = py;
        }
        return false;
    }

    private int indexBack(int steps) {
        return (head - steps + CAPACITY) % CAPACITY;
    }

    private void push(float x, float y, int floor) {
        head = (head + 1) % CAPACITY;
        xs[head] = x;
        ys[head] = y;
        zs[head] = floor;
        if (count < CAPACITY) {
            count++;
        }
        lastX = x;
        lastY = y;
        lastZ = floor;
    }

    private static void writeDir(float dx, float dy, float[] outXYDir) {
        float len = (float) Math.hypot(dx, dy);
        if (len <= 0.001F) {
            outXYDir[2] = 0.0F;
            outXYDir[3] = 0.0F;
            return;
        }
        outXYDir[2] = dx / len;
        outXYDir[3] = dy / len;
    }
}
