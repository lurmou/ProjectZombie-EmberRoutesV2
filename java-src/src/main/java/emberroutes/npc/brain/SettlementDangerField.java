package emberroutes.npc.brain;

import emberroutes.npc.EmberTargetProjector;
import emberroutes.npc.IsoNPC;
import java.util.ArrayList;
import zombie.characters.IsoZombie;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.areas.isoregion.IsoRegions;
import zombie.iso.areas.isoregion.regions.IWorldRegion;
import zombie.iso.areas.isoregion.regions.IsoWorldRegion;

/**
 * 轻量据点危险场评估器（250ms 共享采样缓存 + 14 格粗筛 + {@code IsoRegions} {@code isEnclosed()} 室内避险锚点定位）。
 */
public final class SettlementDangerField {
    private static final long SCAN_INTERVAL_MS = 250L;
    private static final float THREAT_RADIUS = 11.5F;
    private static final float THREAT_Z_TOL = 1.5F;
    private static final int MAX_ZOMBIE_SAMPLES = 256;

    private static final float[] Z_X = new float[MAX_ZOMBIE_SAMPLES];
    private static final float[] Z_Y = new float[MAX_ZOMBIE_SAMPLES];
    private static final float[] Z_Z = new float[MAX_ZOMBIE_SAMPLES];
    private static int cachedCount;
    private static long lastScanMs;

    private static float latestClusterX;
    private static float latestClusterY;
    private static float latestClusterZ;
    private static int latestClusterCount;
    private static long directorFeedUntilMs;

    private SettlementDangerField() {}

    public static synchronized void reset() {
        cachedCount = 0;
        lastScanMs = 0L;
        latestClusterCount = 0;
        directorFeedUntilMs = 0L;
    }

    /** 由集中式战术导演每 80ms 写入粗筛与聚类的存活威胁点，避免据点平民状态树重复扫全图丧尸。 */
    public static synchronized void ingestDirectorThreats(
            long nowMs,
            float[] xs,
            float[] ys,
            float[] zs,
            int count,
            boolean hasCluster,
            float clusterX,
            float clusterY,
            float clusterZ,
            int clusterCount) {
        int n = Math.max(0, Math.min(MAX_ZOMBIE_SAMPLES, count));
        for (int i = 0; i < n; i++) {
            Z_X[i] = xs[i];
            Z_Y[i] = ys[i];
            Z_Z[i] = zs[i];
        }
        cachedCount = n;
        lastScanMs = nowMs;
        directorFeedUntilMs = nowMs + 260L;
        if (hasCluster) {
            latestClusterX = clusterX;
            latestClusterY = clusterY;
            latestClusterZ = clusterZ;
            latestClusterCount = clusterCount;
        } else {
            latestClusterCount = 0;
        }
    }

    public static synchronized int getLatestClusterCount() {
        return latestClusterCount;
    }

    public static synchronized float getLatestClusterX() {
        return latestClusterX;
    }

    public static synchronized float getLatestClusterY() {
        return latestClusterY;
    }

    public static synchronized float getLatestClusterZ() {
        return latestClusterZ;
    }

    /** 刷新全据点周边存活丧尸缓存，并以 O(N_sample) 无堆分配判定当前 NPC 是否处于直接威胁半径内。 */
    public static void evaluate(BrainContext ctx) {
        if (ctx == null || ctx.npc == null || ctx.npc.isDead()) {
            if (ctx != null) {
                ctx.threatDetected = false;
                ctx.hasSafeAnchor = false;
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now > directorFeedUntilMs && now - lastScanMs >= SCAN_INTERVAL_MS) {
            refreshSamples(now);
        }

        IsoNPC npc = ctx.npc;
        float nx = npc.getX();
        float ny = npc.getY();
        float nz = npc.getZ();
        float bestDistSq = THREAT_RADIUS * THREAT_RADIUS;
        int bestIdx = -1;

        for (int i = 0; i < cachedCount; i++) {
            float dz = Math.abs(Z_Z[i] - nz);
            if (dz > THREAT_Z_TOL) {
                continue;
            }
            float dx = Z_X[i] - nx;
            float dy = Z_Y[i] - ny;
            if (Math.abs(dx) > THREAT_RADIUS || Math.abs(dy) > THREAT_RADIUS) {
                continue;
            }
            float dSq = dx * dx + dy * dy;
            if (dSq <= bestDistSq) {
                bestDistSq = dSq;
                bestIdx = i;
            }
        }

        if (bestIdx < 0) {
            ctx.threatDetected = false;
            ctx.hasSafeAnchor = false;
            return;
        }

        ctx.threatDetected = true;
        ctx.threatX = Z_X[bestIdx];
        ctx.threatY = Z_Y[bestIdx];
        ctx.threatZ = Z_Z[bestIdx];

        int npcZ = EmberTargetProjector.levelOf(nz);
        IWorldRegion curReg = IsoRegions.getIsoWorldRegion((int) Math.floor(nx), (int) Math.floor(ny), npcZ);
        boolean alreadyEnclosed = (curReg instanceof IsoWorldRegion wr) && wr.isEnclosed();
        boolean threatVeryClose = bestDistSq <= 36.0F; // 6 格以内即使室内也尝试远离
        if (alreadyEnclosed && !threatVeryClose) {
            ctx.hasSafeAnchor = true;
            ctx.safeAnchorX = nx;
            ctx.safeAnchorY = ny;
            ctx.safeAnchorZ = npcZ;
            return;
        }

        findSafeAnchor(npc, ctx);
    }

    private static synchronized void refreshSamples(long nowMs) {
        if (nowMs - lastScanMs < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanMs = nowMs;
        cachedCount = 0;
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) {
            return;
        }
        ArrayList<IsoZombie> zombies = cell.getZombieList();
        if (zombies == null || zombies.isEmpty()) {
            return;
        }
        for (int i = 0, n = zombies.size(); i < n && cachedCount < MAX_ZOMBIE_SAMPLES; i++) {
            IsoZombie z = zombies.get(i);
            if (z == null || z.isDead() || z.isFakeDead()) {
                continue;
            }
            Z_X[cachedCount] = z.getX();
            Z_Y[cachedCount] = z.getY();
            Z_Z[cachedCount] = z.getZ();
            cachedCount++;
        }
    }

    private static void findSafeAnchor(IsoNPC npc, BrainContext ctx) {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) {
            ctx.hasSafeAnchor = false;
            return;
        }
        int baseX = (int) Math.floor(npc.getX());
        int baseY = (int) Math.floor(npc.getY());
        int z = EmberTargetProjector.levelOf(npc.getZ());

        float bestEnclosedScore = -Float.MAX_VALUE;
        float bestEnclosedX = 0.0F;
        float bestEnclosedY = 0.0F;
        boolean foundEnclosed = false;

        float bestFleeScore = -Float.MAX_VALUE;
        float bestFleeX = 0.0F;
        float bestFleeY = 0.0F;
        boolean foundFlee = false;

        int[] radii = {3, 6, 9, 12};
        for (int ri = 0; ri < radii.length; ri++) {
            int r = radii[ri];
            for (int a = 0; a < 8; a++) {
                double ang = a * (Math.PI * 0.25);
                int cx = baseX + (int) Math.round(Math.cos(ang) * r);
                int cy = baseY + (int) Math.round(Math.sin(ang) * r);
                IsoGridSquare sq = cell.getGridSquare(cx, cy, z);
                if (sq == null || !sq.TreatAsSolidFloor()) {
                    continue;
                }
                float px = cx + 0.5F;
                float py = cy + 0.5F;
                if (!EmberTargetProjector.canStandClean(npc, px, py, z)) {
                    continue;
                }
                float distThreatSq = (px - ctx.threatX) * (px - ctx.threatX) + (py - ctx.threatY) * (py - ctx.threatY);
                float distSelf = (float) Math.hypot(px - npc.getX(), py - npc.getY());
                IWorldRegion reg = IsoRegions.getIsoWorldRegion(cx, cy, z);
                boolean enclosed = (reg instanceof IsoWorldRegion wr) && wr.isEnclosed();

                float score = distThreatSq - distSelf * 1.5F;
                if (enclosed) {
                    if (!foundEnclosed || score > bestEnclosedScore) {
                        foundEnclosed = true;
                        bestEnclosedScore = score;
                        bestEnclosedX = px;
                        bestEnclosedY = py;
                    }
                } else if (!foundFlee || score > bestFleeScore) {
                    foundFlee = true;
                    bestFleeScore = score;
                    bestFleeX = px;
                    bestFleeY = py;
                }
            }
            if (foundEnclosed) {
                break;
            }
        }

        if (foundEnclosed) {
            ctx.hasSafeAnchor = true;
            ctx.safeAnchorX = bestEnclosedX;
            ctx.safeAnchorY = bestEnclosedY;
            ctx.safeAnchorZ = z;
        } else if (foundFlee) {
            ctx.hasSafeAnchor = true;
            ctx.safeAnchorX = bestFleeX;
            ctx.safeAnchorY = bestFleeY;
            ctx.safeAnchorZ = z;
        } else {
            ctx.hasSafeAnchor = false;
        }
    }
}
