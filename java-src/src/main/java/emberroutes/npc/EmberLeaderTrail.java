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
    /** 领队停步判据超时（ms）：单一权威停步时钟，消除多模块各自计时导致的错相改标。 */
    public static final long LEADER_STOP_TIMEOUT_MS = 240L;
    /** 领队位移触发刷新停步时钟的最小步长平方（0.04^2 格）。 */
    public static final float MOTION_EPS_SQ = 0.0016F;
    /** 稳定航向弦回溯距离（格）：过滤斜走松键最后单帧 0.05m 刹车步长造成的 45° 甩尾。 */
    public static final float STABLE_HEADING_LOOKBACK = 0.85F;

    private static final int MAX_LOCAL_PLAYERS = 4;
    private static final EmberLeaderTrail[] SHARED = new EmberLeaderTrail[MAX_LOCAL_PLAYERS];

    private final float[] xs = new float[CAPACITY];
    private final float[] ys = new float[CAPACITY];
    private final int[] zs = new int[CAPACITY];
    private final float[] headingSampleScratch = new float[4];
    private int head = -1;
    private int count;
    private float lastX;
    private float lastY;
    private int lastZ;
    private float motionRefX;
    private float motionRefY;
    private int motionRefZ = Integer.MIN_VALUE;
    private long lastMovedAtMs;
    private float headingX;
    private float headingY = 1.0F;
    private boolean hasHeading;
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
        motionRefX = 0.0F;
        motionRefY = 0.0F;
        motionRefZ = Integer.MIN_VALUE;
        lastMovedAtMs = 0L;
        headingX = 0.0F;
        headingY = 1.0F;
        hasHeading = false;
    }

    public void record(float x, float y, int floor) {
        record(x, y, floor, System.currentTimeMillis());
    }

    public void record(float x, float y, int floor, long nowMs) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return;
        }
        if (motionRefZ == Integer.MIN_VALUE) {
            motionRefX = x;
            motionRefY = y;
            motionRefZ = floor;
        } else if (floor != motionRefZ) {
            motionRefX = x;
            motionRefY = y;
            motionRefZ = floor;
            lastMovedAtMs = nowMs;
            hasHeading = false;
        } else {
            float mdx = x - motionRefX;
            float mdy = y - motionRefY;
            float mSq = mdx * mdx + mdy * mdy;
            if (mSq > MOTION_EPS_SQ) {
                if (!hasHeading && mSq <= JUMP_SQ && mSq > 1.0E-5F) {
                    float len = (float) Math.sqrt(mSq);
                    headingX = mdx / len;
                    headingY = mdy / len;
                    hasHeading = true;
                }
                motionRefX = x;
                motionRefY = y;
                lastMovedAtMs = nowMs;
            }
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
            hasHeading = false;
            push(x, y, floor);
            return;
        }
        if (distSq < SPACING_SQ) {
            return;
        }
        push(x, y, floor);
        updateStableHeading(x, y);
    }

    public boolean isLeaderMoving(long nowMs) {
        return lastMovedAtMs > 0L && (nowMs - lastMovedAtMs) < LEADER_STOP_TIMEOUT_MS;
    }

    public void forceStopped() {
        lastMovedAtMs = 0L;
    }

    public void seedHeadingIfUnset(float fx, float fy) {
        if (hasHeading) {
            return;
        }
        float len = (float) Math.hypot(fx, fy);
        if (len > 1.0E-3F) {
            headingX = fx / len;
            headingY = fy / len;
            hasHeading = true;
        }
    }

    public boolean getStableHeading(float[] outDir) {
        if (!hasHeading || outDir == null || outDir.length < 2) {
            return false;
        }
        outDir[0] = headingX;
        outDir[1] = headingY;
        return true;
    }

    private void updateStableHeading(float curX, float curY) {
        if (sample(STABLE_HEADING_LOOKBACK, headingSampleScratch)) {
            float cx = curX - headingSampleScratch[0];
            float cy = curY - headingSampleScratch[1];
            float lenSq = cx * cx + cy * cy;
            if (lenSq >= 0.25F * 0.25F) {
                float len = (float) Math.sqrt(lenSq);
                headingX = cx / len;
                headingY = cy / len;
                hasHeading = true;
                return;
            }
        }
        if (sample(0.35F, headingSampleScratch)) {
            float cx = curX - headingSampleScratch[0];
            float cy = curY - headingSampleScratch[1];
            float lenSq = cx * cx + cy * cy;
            if (lenSq >= 0.20F * 0.20F) {
                float len = (float) Math.sqrt(lenSq);
                headingX = cx / len;
                headingY = cy / len;
                hasHeading = true;
                return;
            }
        }
        if (count >= 2) {
            int oldestIdx = indexBack(count - 1);
            float cx = curX - xs[oldestIdx];
            float cy = curY - ys[oldestIdx];
            float lenSq = cx * cx + cy * cy;
            if (lenSq >= 0.25F * 0.25F) {
                float len = (float) Math.sqrt(lenSq);
                headingX = cx / len;
                headingY = cy / len;
                hasHeading = true;
            }
        }
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
