package emberroutes.npc.combat;

/**
 * 第 5 号战斗微操内核：{@code HordeRetreatPolicy}（二段尸潮质心撤退状态机）。
 *
 * <ul>
 *   <li>当活跃 {@code HordeCluster}（≥4 只聚类丧尸）进入 {@code < 3.0} 格警戒半径时立即触发撤退；</li>
 *   <li>沿背离质心方向外推 {@code 3.0} 格确立安全退避点（遇到墙体时通过探测回调沿切向/侧翼钳位）；</li>
 *   <li>强制最小驻留 {@code minDwell = 1.35s} 与撤退冷却 {@code cooldown = 4.0s}，杜绝尸潮边缘高频往复抽搐。</li>
 * </ul>
 */
public final class HordeRetreatPolicy {
    public static final float TRIGGER_RADIUS_TILES = 3.0F;
    public static final float RETREAT_DISTANCE_TILES = 3.0F;
    public static final double MIN_DWELL_SIM_SEC = 1.35;
    public static final double COOLDOWN_SIM_SEC = 4.0;

    private boolean active;
    private double enteredAtSimSec;
    private double cooldownUntilSimSec;
    private float targetX;
    private float targetY;

    public boolean isActive() {
        return active;
    }

    public float getTargetX() {
        return targetX;
    }

    public float getTargetY() {
        return targetY;
    }

    public void reset() {
        active = false;
        enteredAtSimSec = 0.0;
        cooldownUntilSimSec = 0.0;
        targetX = 0.0F;
        targetY = 0.0F;
    }

    /**
     * 每帧更新撤退状态机并返回当前是否应维持撤退动作。
     *
     * @param selfX NPC 当前 X
     * @param selfY NPC 当前 Y
     * @param selfZ NPC 当前楼层
     * @param hasCluster 周边是否存在满足条件的活跃尸潮聚类
     * @param clusterX 最近危险尸潮质心 X
     * @param clusterY 最近危险尸潮质心 Y
     * @param simTimeSec 当前仿真时钟（秒）
     * @param probe 可通行性探针（为 null 时按开阔地外推）
     */
    public boolean update(
            float selfX,
            float selfY,
            int selfZ,
            boolean hasCluster,
            float clusterX,
            float clusterY,
            double simTimeSec,
            EmberSquadDirector.PassabilityProbe probe) {
        if (active) {
            double elapsed = simTimeSec - enteredAtSimSec;
            float distToGoalSq = (targetX - selfX) * (targetX - selfX) + (targetY - selfY) * (targetY - selfY);
            float distFromClusterSq = (selfX - clusterX) * (selfX - clusterX) + (selfY - clusterY) * (selfY - clusterY);
            boolean dwellMet = elapsed >= MIN_DWELL_SIM_SEC;
            boolean safeDist = !hasCluster || distFromClusterSq >= (TRIGGER_RADIUS_TILES + 1.4F) * (TRIGGER_RADIUS_TILES + 1.4F);
            boolean arrivedGoal = distToGoalSq <= 0.35F * 0.35F;
            if (dwellMet && (arrivedGoal || safeDist || elapsed >= 3.0)) {
                active = false;
                cooldownUntilSimSec = simTimeSec + COOLDOWN_SIM_SEC;
                return false;
            }
            return true;
        }

        if (!hasCluster || simTimeSec < cooldownUntilSimSec) {
            return false;
        }
        float dx = selfX - clusterX;
        float dy = selfY - clusterY;
        float distSq = dx * dx + dy * dy;
        if (distSq >= TRIGGER_RADIUS_TILES * TRIGGER_RADIUS_TILES) {
            return false;
        }

        float dirX;
        float dirY;
        if (distSq > 1.0E-4F) {
            float d = (float) Math.sqrt(distSq);
            dirX = dx / d;
            dirY = dy / d;
        } else {
            dirX = 1.0F;
            dirY = 0.0F;
        }

        float[] chosen = pickRetreatGoal(selfX, selfY, selfZ, dirX, dirY, probe);
        this.targetX = chosen[0];
        this.targetY = chosen[1];
        this.enteredAtSimSec = simTimeSec;
        this.active = true;
        return true;
    }

    private static float[] pickRetreatGoal(
            float selfX,
            float selfY,
            int selfZ,
            float awayX,
            float awayY,
            EmberSquadDirector.PassabilityProbe probe) {
        if (probe == null) {
            return new float[]{selfX + awayX * RETREAT_DISTANCE_TILES, selfY + awayY * RETREAT_DISTANCE_TILES};
        }
        double baseAng = Math.atan2(awayY, awayX);
        double[] offsetsRad = {
            0.0,
            Math.PI * 0.25,
            -Math.PI * 0.25,
            Math.PI * 0.5,
            -Math.PI * 0.5,
            Math.PI * 0.75,
            -Math.PI * 0.75
        };
        float[] scales = {RETREAT_DISTANCE_TILES, 2.0F, 1.2F};
        for (int si = 0; si < scales.length; si++) {
            float dist = scales[si];
            for (int oi = 0; oi < offsetsRad.length; oi++) {
                double ang = baseAng + offsetsRad[oi];
                float gx = selfX + (float) Math.cos(ang) * dist;
                float gy = selfY + (float) Math.sin(ang) * dist;
                if (probe.isLineClear(selfX, selfY, gx, gy, selfZ)) {
                    return new float[]{gx, gy};
                }
            }
        }
        return new float[]{selfX + awayX * 1.2F, selfY + awayY * 1.2F};
    }
}
