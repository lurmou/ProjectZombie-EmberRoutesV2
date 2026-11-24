package emberroutes.npc.combat;

/**
 * 集中式小队战术导演 {@code EmberSquadDirector}（GDC AI Director + Token System + FiringCorridor，零 {@code zombie.*} 依赖）。
 *
 * <ol>
 *   <li><b>80ms 单遍零 GC 粗筛扫描</b>：由 {@code B42CombatRuntime} 做 {@code |dx|<=22 && |dy|<=22 && |dz|<=1.5} 剪枝并写入 {@code ThreatEntry[64]}
 *       （含 {@link CombatTargetState} 姿态掩码、EMA 速度估计与受击硬直上沿时间戳）及 {@code HordeCluster[8]}；</li>
 *   <li><b>双令牌池协同</b>：{@link MeleeRingTokenTable}（1 主攻 + 2 侧翼 + 外圈预备，{@code 550ms} 承诺锁与 {@code -18%} 粘滞偏置）
 *       与 {@link ReloadCoverTokenPool}（并发上限 {@code max(1, ⌊R_active / 2⌋)}，{@code 5.0s} 租约）；</li>
 *   <li><b>2D 射界走廊与窄走廊横向净空门控</b>：{@link FiringCorridor} 仅在垂直枪线至少一侧 {@code 0.8} 格可通行时施压；
 *       两侧皆墙的窄走廊内关闭侧推（{@code < 2.2} 格近战顶上射手停火，{@code >= 2.2} 格近战退半格让射界）。</li>
 * </ol>
 */
public final class EmberSquadDirector {
    public static final long SCAN_INTERVAL_MS = 80L;
    public static final float COARSE_PRUNE_RANGE = 22.0F;
    public static final float COARSE_Z_TOL = 1.5F;
    public static final int MAX_THREATS = 64;
    public static final int MAX_CLUSTERS = 8;
    public static final int MAX_CORRIDORS = 16;
    public static final int MAX_ANCHORS = 32;

    public static final int HORDE_MIN_COUNT = 4;
    public static final float HORDE_SCAN_RADIUS = 3.0F;
    public static final float HORDE_INTER_DIST_THRESHOLD = 0.5F;

    public static final long MELEE_COMMIT_LOCK_MS = 550L;
    public static final float MELEE_STICKY_BONUS_FACTOR = 0.82F;
    public static final float MELEE_CLAIM_PENALTY = 1000.0F;

    public static final long RELOAD_LEASE_MS = 5000L;

    public static final float CORRIDOR_HALF_WIDTH = 0.65F;
    public static final float LATERAL_CLEARANCE_DIST = 0.8F;
    public static final float NARROW_CORRIDOR_MELEE_PRESS_DIST = 2.2F;
    public static final float NARROW_CORRIDOR_YIELD_STEP = 0.5F;

    @FunctionalInterface
    public interface PassabilityProbe {
        boolean isLineClear(float x1, float y1, float x2, float y2, int z);
    }

    private static final PassabilityProbe OPEN_PROBE = (x1, y1, x2, y2, z) -> true;

    /** 近战围攻槽位仲裁表：槽 0=主攻(0°)，槽 1/2=侧翼(±30°/±60°)，槽 3+=外圈等待。 */
    public static final class MeleeRingTokenTable {
        public static final int SLOT_PRIMARY = 0;
        public static final int SLOT_FLANK_LEFT = 1;
        public static final int SLOT_FLANK_RIGHT = 2;
        public static final int ACTIVE_CAPACITY = 3;

        private final String[] holders = new String[ACTIVE_CAPACITY];
        private final long[] commitUntilMs = new long[ACTIVE_CAPACITY];

        public void reset() {
            for (int i = 0; i < ACTIVE_CAPACITY; i++) {
                holders[i] = null;
                commitUntilMs[i] = 0L;
            }
        }

        public void release(String npcId) {
            if (npcId == null) return;
            for (int i = 0; i < ACTIVE_CAPACITY; i++) {
                if (npcId.equals(holders[i])) {
                    holders[i] = null;
                    commitUntilMs[i] = 0L;
                }
            }
        }

        public void expireStale(long nowMs) {
            for (int i = 0; i < ACTIVE_CAPACITY; i++) {
                if (holders[i] != null && nowMs - commitUntilMs[i] > 1800L) {
                    holders[i] = null;
                    commitUntilMs[i] = 0L;
                }
            }
        }

        public int slotOf(String npcId) {
            if (npcId == null) return -1;
            for (int i = 0; i < ACTIVE_CAPACITY; i++) {
                if (npcId.equals(holders[i])) {
                    return i;
                }
            }
            return -1;
        }

        public int activeCount() {
            int c = 0;
            for (int i = 0; i < ACTIVE_CAPACITY; i++) {
                if (holders[i] != null) c++;
            }
            return c;
        }

        public boolean isLockedFor(String npcId, long nowMs) {
            int slot = slotOf(npcId);
            return slot >= 0 && nowMs < commitUntilMs[slot];
        }

        /** 为 NPC 分配主攻(0) 或 侧翼(1/2) 令牌；若 3 个近战席已满则返回 -1（触发转火罚分 1000.0）。 */
        public int tryClaim(String npcId, long nowMs) {
            if (npcId == null || npcId.isEmpty()) return -1;
            int existing = slotOf(npcId);
            if (existing >= 0) {
                commitUntilMs[existing] = nowMs + MELEE_COMMIT_LOCK_MS;
                return existing;
            }
            for (int i = 0; i < ACTIVE_CAPACITY; i++) {
                if (holders[i] == null) {
                    holders[i] = npcId;
                    commitUntilMs[i] = nowMs + MELEE_COMMIT_LOCK_MS;
                    return i;
                }
            }
            return -1;
        }
    }

    /** 单个预处理威胁条目（预分配定长池元素）。 */
    public static final class ThreatEntry {
        public final int slotIndex;
        public boolean active;
        public int zombieId;
        public float x;
        public float y;
        public float z;
        public float prevX;
        public float prevY;
        public float velX;
        public float velY;
        public float speed;
        public boolean hasVelSample;
        public int stateMask;
        public long staggerStartMs;
        public long lastSeenMs;
        public final MeleeRingTokenTable ringTable = new MeleeRingTokenTable();

        ThreatEntry(int slotIndex) {
            this.slotIndex = slotIndex;
        }

        void clear() {
            active = false;
            zombieId = -1;
            x = y = z = 0.0F;
            prevX = prevY = 0.0F;
            velX = velY = speed = 0.0F;
            hasVelSample = false;
            stateMask = 0;
            staggerStartMs = 0L;
            lastSeenMs = 0L;
            ringTable.reset();
        }
    }

    /** 聚类尸潮质心条目。 */
    public static final class HordeCluster {
        public boolean active;
        public float centroidX;
        public float centroidY;
        public int zLevel;
        public int count;
        public float avgInterDist;

        void clear() {
            active = false;
            centroidX = 0.0F;
            centroidY = 0.0F;
            zLevel = 0;
            count = 0;
            avgInterDist = 0.0F;
        }
    }

    /** 小队战术换弹掩护令牌池：常规战术换弹并发上限 {@code max(1, floor(R_active / 2))}，租约 5s。 */
    public static final class ReloadCoverTokenPool {
        private static final int MAX_LEASES = 16;
        private final String[] holders = new String[MAX_LEASES];
        private final long[] expiresAtMs = new long[MAX_LEASES];
        private int activeShooters;

        public void reset() {
            activeShooters = 0;
            for (int i = 0; i < MAX_LEASES; i++) {
                holders[i] = null;
                expiresAtMs[i] = 0L;
            }
        }

        public void setActiveShooterCount(int count) {
            this.activeShooters = Math.max(0, count);
        }

        public int maxConcurrentReloads() {
            return Math.max(1, activeShooters / 2);
        }

        public int activeLeaseCount(long nowMs) {
            purgeExpired(nowMs);
            int c = 0;
            for (int i = 0; i < MAX_LEASES; i++) {
                if (holders[i] != null) c++;
            }
            return c;
        }

        public boolean hasToken(String npcId, long nowMs) {
            if (npcId == null) return false;
            purgeExpired(nowMs);
            for (int i = 0; i < MAX_LEASES; i++) {
                if (npcId.equals(holders[i])) return true;
            }
            return false;
        }

        public boolean acquire(String npcId, long nowMs) {
            if (npcId == null || npcId.isEmpty()) return false;
            purgeExpired(nowMs);
            for (int i = 0; i < MAX_LEASES; i++) {
                if (npcId.equals(holders[i])) {
                    expiresAtMs[i] = nowMs + RELOAD_LEASE_MS;
                    return true;
                }
            }
            int active = 0;
            int freeSlot = -1;
            for (int i = 0; i < MAX_LEASES; i++) {
                if (holders[i] != null) {
                    active++;
                } else if (freeSlot < 0) {
                    freeSlot = i;
                }
            }
            if (active >= maxConcurrentReloads() || freeSlot < 0) {
                return false;
            }
            holders[freeSlot] = npcId;
            expiresAtMs[freeSlot] = nowMs + RELOAD_LEASE_MS;
            return true;
        }

        public void release(String npcId) {
            if (npcId == null) return;
            for (int i = 0; i < MAX_LEASES; i++) {
                if (npcId.equals(holders[i])) {
                    holders[i] = null;
                    expiresAtMs[i] = 0L;
                }
            }
        }

        private void purgeExpired(long nowMs) {
            for (int i = 0; i < MAX_LEASES; i++) {
                if (holders[i] != null && nowMs >= expiresAtMs[i]) {
                    holders[i] = null;
                    expiresAtMs[i] = 0L;
                }
            }
        }
    }

    /** 2D 射界走廊线段（活跃射手及持枪瞄准玩家）。 */
    public static final class FiringCorridor {
        public boolean active;
        public String ownerId;
        public float ax;
        public float ay;
        public float bx;
        public float by;
        public int zLevel;
        public long expiresAtMs;

        void clear() {
            active = false;
            ownerId = null;
            ax = ay = bx = by = 0.0F;
            zLevel = 0;
            expiresAtMs = 0L;
        }
    }

    /** 射界走廊斥力与「窄走廊横向净空门控」计算结果（可预分配复用）。 */
    public static final class CorridorAvoidanceResult {
        public boolean inCorridor;
        public boolean narrowHallwayConfined;
        public float targetDistAlongLine;
        public float pushX;
        public float pushY;
        public boolean yieldBackAlongLine;
        public boolean meleePressAhead;

        public void reset() {
            inCorridor = false;
            narrowHallwayConfined = false;
            targetDistAlongLine = 0.0F;
            pushX = 0.0F;
            pushY = 0.0F;
            yieldBackAlongLine = false;
            meleePressAhead = false;
        }
    }

    private final ThreatEntry[] threats = new ThreatEntry[MAX_THREATS];
    private final HordeCluster[] clusters = new HordeCluster[MAX_CLUSTERS];
    private final FiringCorridor[] corridors = new FiringCorridor[MAX_CORRIDORS];
    private final ReloadCoverTokenPool reloadPool = new ReloadCoverTokenPool();

    private final float[] anchorX = new float[MAX_ANCHORS];
    private final float[] anchorY = new float[MAX_ANCHORS];
    private final float[] anchorZ = new float[MAX_ANCHORS];
    private int anchorCount;
    private final boolean[] clusterVisited = new boolean[MAX_THREATS];
    private final int[] clusterMembers = new int[MAX_THREATS];

    private long lastScanMs;

    private static final EmberSquadDirector INSTANCE = new EmberSquadDirector();

    public EmberSquadDirector() {
        for (int i = 0; i < MAX_THREATS; i++) threats[i] = new ThreatEntry(i);
        for (int i = 0; i < MAX_CLUSTERS; i++) clusters[i] = new HordeCluster();
        for (int i = 0; i < MAX_CORRIDORS; i++) corridors[i] = new FiringCorridor();
    }

    public static EmberSquadDirector getInstance() {
        return INSTANCE;
    }

    public synchronized void resetAll() {
        lastScanMs = 0L;
        anchorCount = 0;
        for (int i = 0; i < MAX_THREATS; i++) threats[i].clear();
        for (int i = 0; i < MAX_CLUSTERS; i++) clusters[i].clear();
        for (int i = 0; i < MAX_CORRIDORS; i++) corridors[i].clear();
        reloadPool.reset();
    }

    public static void reset() {
        INSTANCE.resetAll();
    }

    public ReloadCoverTokenPool getReloadPool() {
        return reloadPool;
    }

    /** 释放指定 NPC 持有的全部近战槽位、换弹掩护令牌与射界走廊（StateScope/死亡/离屏/切档统一收口）。 */
    public synchronized void releaseAllForNpc(String npcId) {
        if (npcId == null || npcId.isEmpty()) return;
        for (int i = 0; i < MAX_THREATS; i++) {
            if (threats[i].active) {
                threats[i].ringTable.release(npcId);
            }
        }
        reloadPool.release(npcId);
        unregisterCorridor(npcId);
    }

    public void unregisterCorridor(String ownerId) {
        if (ownerId == null) return;
        for (int i = 0; i < MAX_CORRIDORS; i++) {
            if (corridors[i].active && ownerId.equals(corridors[i].ownerId)) {
                corridors[i].clear();
            }
        }
    }

    public synchronized void purgeExpiredCorridors(long nowMs) {
        for (int i = 0; i < MAX_CORRIDORS; i++) {
            if (corridors[i].active && nowMs > corridors[i].expiresAtMs) {
                corridors[i].clear();
            }
        }
    }

    public void registerCorridor(String ownerId, float ax, float ay, float bx, float by, int z, long nowMs) {
        if (ownerId == null || ownerId.isEmpty()) return;
        if ((bx - ax) * (bx - ax) + (by - ay) * (by - ay) < 0.25F) return;
        int freeIdx = -1;
        for (int i = 0; i < MAX_CORRIDORS; i++) {
            if (corridors[i].active && ownerId.equals(corridors[i].ownerId)) {
                freeIdx = i;
                break;
            }
            if (!corridors[i].active && freeIdx < 0) {
                freeIdx = i;
            }
        }
        if (freeIdx < 0) {
            // 替换最旧过期或首个条目
            freeIdx = 0;
        }
        FiringCorridor c = corridors[freeIdx];
        c.active = true;
        c.ownerId = ownerId;
        c.ax = ax;
        c.ay = ay;
        c.bx = bx;
        c.by = by;
        c.zLevel = z;
        c.expiresAtMs = nowMs + 220L;
    }

    /** 节流触发 80ms 扫图周期：未到期返回 {@code -1}，否则返回本轮扫图间隔秒数并重置锚点缓冲。 */
    public synchronized float beginScanIfDue(long nowMs) {
        if (lastScanMs != 0L && nowMs - lastScanMs < SCAN_INTERVAL_MS) {
            return -1.0F;
        }
        float scanDt = lastScanMs > 0L ? Math.max(0.02F, Math.min(0.4F, (nowMs - lastScanMs) / 1000.0F)) : 0.08F;
        lastScanMs = nowMs;
        anchorCount = 0;
        return scanDt;
    }

    public synchronized void resetScanAnchors() {
        anchorCount = 0;
    }

    public synchronized void addScanAnchor(float x, float y, float z) {
        if (anchorCount < MAX_ANCHORS) {
            anchorX[anchorCount] = x;
            anchorY[anchorCount] = y;
            anchorZ[anchorCount] = z;
            anchorCount++;
        }
    }

    public synchronized int getAnchorCount() {
        return anchorCount;
    }

    public synchronized boolean isNearAnyAnchor(float zx, float zy, float zz) {
        for (int i = 0; i < anchorCount; i++) {
            if (Math.abs(zz - anchorZ[i]) <= COARSE_Z_TOL
                    && Math.abs(zx - anchorX[i]) <= COARSE_PRUNE_RANGE
                    && Math.abs(zy - anchorY[i]) <= COARSE_PRUNE_RANGE) {
                return true;
            }
        }
        return false;
    }

    public synchronized ThreatEntry recordScannedThreat(
            int zombieId, float zx, float zy, float zz, int stateMask, long nowMs, float scanDt) {
        ThreatEntry entry = findById(zombieId);
        boolean existed = (entry != null);
        if (entry == null) {
            entry = allocThreatSlot(zx, zy, zz, anchorCount);
        }
        if (entry == null) {
            return null;
        }
        boolean wasStaggered = existed && CombatTargetState.isStaggered(entry.stateMask);
        entry.active = true;
        entry.zombieId = zombieId;
        if (existed && entry.hasVelSample) {
            float instVx = (zx - entry.x) / scanDt;
            float instVy = (zy - entry.y) / scanDt;
            float alpha = 0.35F;
            entry.velX = entry.velX * (1.0F - alpha) + instVx * alpha;
            entry.velY = entry.velY * (1.0F - alpha) + instVy * alpha;
        } else {
            entry.velX = 0.0F;
            entry.velY = 0.0F;
        }
        entry.prevX = entry.x;
        entry.prevY = entry.y;
        entry.x = zx;
        entry.y = zy;
        entry.z = zz;
        entry.speed = (float) Math.hypot(entry.velX, entry.velY);
        entry.hasVelSample = true;
        entry.stateMask = stateMask;
        entry.lastSeenMs = nowMs;
        updateStaggerTimestamp(entry, wasStaggered, stateMask, nowMs);
        return entry;
    }

    public synchronized void clearAllThreats() {
        for (int i = 0; i < MAX_THREATS; i++) {
            threats[i].clear();
        }
    }

    public synchronized void pruneUnseenThreats(long nowMs) {
        for (int i = 0; i < MAX_THREATS; i++) {
            ThreatEntry t = threats[i];
            if (t.active) {
                if (t.lastSeenMs != nowMs) {
                    t.clear();
                } else {
                    t.ringTable.expireStale(nowMs);
                }
            }
        }
    }

    /** 纯 Java 测试/手动注入入口：在固定坐标注入一个威胁（自动归入空闲槽或覆盖匹配 ID）。 */
    public synchronized ThreatEntry upsertManualThreat(
            int zombieId, float x, float y, float z, float vx, float vy, int stateMask, long nowMs) {
        ThreatEntry slot = findById(zombieId);
        boolean existed = (slot != null);
        if (slot == null) {
            for (int i = 0; i < MAX_THREATS; i++) {
                if (!threats[i].active) {
                    slot = threats[i];
                    break;
                }
            }
        }
        if (slot == null) {
            slot = threats[MAX_THREATS - 1];
            slot.clear();
        }
        boolean wasStaggered = existed && CombatTargetState.isStaggered(slot.stateMask);
        slot.active = true;
        slot.zombieId = zombieId;
        slot.prevX = existed ? slot.x : x;
        slot.prevY = existed ? slot.y : y;
        slot.x = x;
        slot.y = y;
        slot.z = z;
        slot.velX = vx;
        slot.velY = vy;
        slot.speed = (float) Math.hypot(vx, vy);
        slot.hasVelSample = true;
        slot.stateMask = stateMask;
        slot.lastSeenMs = nowMs;
        updateStaggerTimestamp(slot, wasStaggered, stateMask, nowMs);
        return slot;
    }

    private static void updateStaggerTimestamp(ThreatEntry entry, boolean wasStaggered, int stateMask, long nowMs) {
        boolean nowStaggered = CombatTargetState.isStaggered(stateMask);
        if (!wasStaggered && nowStaggered) {
            entry.staggerStartMs = nowMs;
        } else if (!nowStaggered
                && !CombatTargetState.isPostureTransition(stateMask)
                && !CombatTargetState.isProneOrCrawl(stateMask)) {
            entry.staggerStartMs = 0L;
        }
    }

    public ThreatEntry findById(int zombieId) {
        for (int i = 0; i < MAX_THREATS; i++) {
            if (threats[i].active && threats[i].zombieId == zombieId) {
                return threats[i];
            }
        }
        return null;
    }

    public int getActiveThreatCount() {
        int c = 0;
        for (int i = 0; i < MAX_THREATS; i++) {
            if (threats[i].active) c++;
        }
        return c;
    }

    public ThreatEntry getThreatAt(int idx) {
        return threats[idx];
    }

    public int getActiveClusterCount() {
        int c = 0;
        for (int i = 0; i < MAX_CLUSTERS; i++) {
            if (clusters[i].active) c++;
        }
        return c;
    }

    public HordeCluster getClusterAt(int idx) {
        return clusters[idx];
    }

    public int getActiveCorridorCount() {
        int c = 0;
        for (int i = 0; i < MAX_CORRIDORS; i++) {
            if (corridors[i].active) c++;
        }
        return c;
    }

    /** 查找最近危险尸潮质心；若无有效聚类返回 null。 */
    public HordeCluster findNearestCluster(float x, float y, int zLevel, float maxRadius) {
        HordeCluster best = null;
        float bestDistSq = maxRadius * maxRadius;
        for (int i = 0; i < MAX_CLUSTERS; i++) {
            HordeCluster c = clusters[i];
            if (!c.active || c.zLevel != zLevel) continue;
            float dx = c.centroidX - x;
            float dy = c.centroidY - y;
            float dSq = dx * dx + dy * dy;
            if (dSq <= bestDistSq) {
                bestDistSq = dSq;
                best = c;
            }
        }
        return best;
    }

    /**
     * 查找距离参考点最近的次级站立威胁（排除 {@code excludeZombieId}）抵达参考点的预估毫秒数。
     */
    public float estimateNearestSecondaryArrivalMs(float x, float y, int zLevel, int excludeZombieId) {
        float bestMs = Float.POSITIVE_INFINITY;
        for (int i = 0; i < MAX_THREATS; i++) {
            ThreatEntry t = threats[i];
            if (!t.active || t.zombieId == excludeZombieId || Math.abs(t.z - zLevel) > 0.6F) {
                continue;
            }
            if (!CombatTargetState.isStanding(t.stateMask)) {
                continue;
            }
            float dx = t.x - x;
            float dy = t.y - y;
            float dist = (float) Math.hypot(dx, dy);
            float moveDist = Math.max(0.0F, dist - 1.1F);
            // 疾跑丧尸按 ~3.4 格/s，普通蹒跚丧尸按 ~1.35 格/s（叠实际观测速度上限）
            float baseSpeed = CombatTargetState.isSprinter(t.stateMask) ? 3.4F : 1.35F;
            float approachSpeed = Math.max(baseSpeed, t.speed);
            float etaMs = (moveDist / approachSpeed) * 1000.0F;
            if (etaMs < bestMs) {
                bestMs = etaMs;
            }
        }
        return bestMs;
    }

    /**
     * 聚类当前存活威胁为 {@code HordeCluster[8]}：
     * 每当某站立丧尸半径 {@code 3.0} 格内聚集 {@code >= 4} 只站立丧尸，且这些丧尸到各自最近同类的平均距离 {@code < 0.5} 格时成簇。
     */
    public synchronized void rebuildClusters() {
        for (int i = 0; i < MAX_CLUSTERS; i++) clusters[i].clear();
        for (int i = 0; i < MAX_THREATS; i++) clusterVisited[i] = false;

        int clusterIdx = 0;
        for (int i = 0; i < MAX_THREATS && clusterIdx < MAX_CLUSTERS; i++) {
            ThreatEntry seed = threats[i];
            if (!seed.active || clusterVisited[i] || !CombatTargetState.isStanding(seed.stateMask)) {
                continue;
            }
            int seedZ = (int) Math.floor(seed.z);
            int memberCount = 0;
            for (int j = 0; j < MAX_THREATS && memberCount < 16; j++) {
                ThreatEntry cand = threats[j];
                if (!cand.active || clusterVisited[j] || !CombatTargetState.isStanding(cand.stateMask)) {
                    continue;
                }
                if ((int) Math.floor(cand.z) != seedZ) {
                    continue;
                }
                float dx = cand.x - seed.x;
                float dy = cand.y - seed.y;
                if (dx * dx + dy * dy <= HORDE_SCAN_RADIUS * HORDE_SCAN_RADIUS) {
                    clusterMembers[memberCount++] = j;
                }
            }
            if (memberCount < HORDE_MIN_COUNT) {
                continue;
            }

            float sumNearest = 0.0F;
            float sumX = 0.0F;
            float sumY = 0.0F;
            for (int a = 0; a < memberCount; a++) {
                ThreatEntry ta = threats[clusterMembers[a]];
                sumX += ta.x;
                sumY += ta.y;
                float nearest = Float.MAX_VALUE;
                for (int b = 0; b < memberCount; b++) {
                    if (a == b) continue;
                    ThreatEntry tb = threats[clusterMembers[b]];
                    float d = (float) Math.hypot(ta.x - tb.x, ta.y - tb.y);
                    if (d < nearest) nearest = d;
                }
                sumNearest += nearest;
            }
            float avgNearest = sumNearest / memberCount;
            if (avgNearest < HORDE_INTER_DIST_THRESHOLD) {
                HordeCluster c = clusters[clusterIdx++];
                c.active = true;
                c.centroidX = sumX / memberCount;
                c.centroidY = sumY / memberCount;
                c.zLevel = seedZ;
                c.count = memberCount;
                c.avgInterDist = avgNearest;
                for (int a = 0; a < memberCount; a++) {
                    clusterVisited[clusterMembers[a]] = true;
                }
            }
        }
    }

    /**
     * 计算 2D 射界走廊避让斥力 + <b>窄走廊横向净空门控（Lateral Clearance Gate）</b>。
     *
     * <p>规则：
     * <ul>
     *   <li>仅对距离有效走廊线段 {@code < 0.65} 格内的友军生效；</li>
     *   <li>探测垂直枪线左右两侧各 {@code 0.8} 格净空：</li>
     *   <li>若至少一侧畅通 → 施加平滑侧向让路斥力（优先推向畅通/外侧）；</li>
     *   <li>若两侧均受墙阻挡（窄走廊）→ <b>关闭侧向推力（0 斥力）</b>，置 {@code narrowHallwayConfined=true}：
     *       距枪口到目标近距 {@code < 2.2} 格时近战顶上（{@code meleePressAhead=true}，射手停火），
     *       远距 {@code >= 2.2} 格时近战退 {@code 0.5} 格让开射界（{@code yieldBackAlongLine=true}）。</li>
     * </ul>
     */
    public void evaluateCorridorAvoidance(
            float npcX,
            float npcY,
            int npcZ,
            String selfNpcId,
            float targetDistFromShooter,
            PassabilityProbe probe,
            CorridorAvoidanceResult out) {
        out.reset();
        PassabilityProbe p = probe != null ? probe : OPEN_PROBE;
        for (int i = 0; i < MAX_CORRIDORS; i++) {
            FiringCorridor c = corridors[i];
            if (!c.active || c.zLevel != npcZ || (selfNpcId != null && selfNpcId.equals(c.ownerId))) {
                continue;
            }
            float segX = c.bx - c.ax;
            float segY = c.by - c.ay;
            float segLenSq = segX * segX + segY * segY;
            if (segLenSq < 1.0E-4F) continue;

            float segLen = (float) Math.sqrt(segLenSq);
            float dirX = segX / segLen;
            float dirY = segY / segLen;
            float relX = npcX - c.ax;
            float relY = npcY - c.ay;
            float along = relX * dirX + relY * dirY;
            if (along < 0.15F || along > segLen + 0.35F) {
                continue;
            }
            float perpSigned = -relX * dirY + relY * dirX; // 左侧法向为 (-dirY, dirX)
            float absPerp = Math.abs(perpSigned);
            if (absPerp >= CORRIDOR_HALF_WIDTH) {
                continue;
            }

            out.inCorridor = true;
            out.targetDistAlongLine = along;

            float leftNx = -dirY;
            float leftNy = dirX;
            float rightNx = dirY;
            float rightNy = -dirX;

            boolean leftClear = p.isLineClear(
                    npcX, npcY, npcX + leftNx * LATERAL_CLEARANCE_DIST, npcY + leftNy * LATERAL_CLEARANCE_DIST, npcZ);
            boolean rightClear = p.isLineClear(
                    npcX, npcY, npcX + rightNx * LATERAL_CLEARANCE_DIST, npcY + rightNy * LATERAL_CLEARANCE_DIST, npcZ);

            if (!leftClear && !rightClear) {
                out.narrowHallwayConfined = true;
                out.pushX = 0.0F;
                out.pushY = 0.0F;
                float effTargetDist = targetDistFromShooter > 0.0F ? targetDistFromShooter : segLen;
                if (effTargetDist < NARROW_CORRIDOR_MELEE_PRESS_DIST) {
                    out.meleePressAhead = true;
                } else {
                    out.yieldBackAlongLine = true;
                    out.pushX = -dirX * NARROW_CORRIDOR_YIELD_STEP;
                    out.pushY = -dirY * NARROW_CORRIDOR_YIELD_STEP;
                }
                return;
            }

            float chosenSide;
            if (leftClear && rightClear) {
                chosenSide = perpSigned >= 0.0F ? 1.0F : -1.0F;
            } else {
                chosenSide = leftClear ? 1.0F : -1.0F;
            }
            float strength = 1.0F - (absPerp / CORRIDOR_HALF_WIDTH);
            float mag = Math.max(0.35F, Math.min(1.0F, 0.45F + strength * 0.65F));
            out.pushX += leftNx * chosenSide * mag;
            out.pushY += leftNy * chosenSide * mag;
        }
    }

    /** 纯 Java 重载：按坐标数组检查窄走廊近距接战停火门控（供脱机 JUnit 直接覆盖）。 */
    public static boolean shouldShooterHoldInNarrowCorridor(
            float shooterX,
            float shooterY,
            float aimDirX,
            float aimDirY,
            float targetDist,
            float[] allyXs,
            float[] allyYs,
            int allyCount) {
        if (targetDist >= NARROW_CORRIDOR_MELEE_PRESS_DIST || allyXs == null || allyYs == null) {
            return false;
        }
        int n = Math.min(allyCount, Math.min(allyXs.length, allyYs.length));
        for (int i = 0; i < n; i++) {
            if (isAllyInsideCorridorHold(shooterX, shooterY, aimDirX, aimDirY, targetDist, allyXs[i], allyYs[i])) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAllyInsideCorridorHold(
            float shooterX,
            float shooterY,
            float aimDirX,
            float aimDirY,
            float targetDist,
            float allyX,
            float allyY) {
        float dx = allyX - shooterX;
        float dy = allyY - shooterY;
        float along = dx * aimDirX + dy * aimDirY;
        float perp = Math.abs(-dx * aimDirY + dy * aimDirX);
        return along > 0.2F && along <= targetDist + 0.5F && perp < CORRIDOR_HALF_WIDTH;
    }

    private ThreatEntry allocThreatSlot(float zx, float zy, float zz, int activeAnchors) {
        for (int i = 0; i < MAX_THREATS; i++) {
            if (!threats[i].active) {
                threats[i].clear();
                return threats[i];
            }
        }
        // 满池时挤出距所有友军锚点最远的次要威胁
        int worstIdx = -1;
        float worstMinDistSq = minAnchorDistSq(zx, zy, zz, activeAnchors);
        for (int i = 0; i < MAX_THREATS; i++) {
            float d = minAnchorDistSq(threats[i].x, threats[i].y, threats[i].z, activeAnchors);
            if (d > worstMinDistSq) {
                worstMinDistSq = d;
                worstIdx = i;
            }
        }
        if (worstIdx >= 0) {
            threats[worstIdx].clear();
            return threats[worstIdx];
        }
        return null;
    }

    private float minAnchorDistSq(float x, float y, float z, int activeAnchors) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < activeAnchors; i++) {
            float dx = x - anchorX[i];
            float dy = y - anchorY[i];
            float dz = (z - anchorZ[i]) * 3.0F;
            float d = dx * dx + dy * dy + dz * dz;
            if (d < best) best = d;
        }
        return best;
    }
}
