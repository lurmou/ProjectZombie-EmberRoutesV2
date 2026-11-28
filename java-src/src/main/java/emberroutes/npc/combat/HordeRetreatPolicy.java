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

    private final float[] goalScratch = new float[2];
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
        goalScratch[0] = 0.0F;
        goalScratch[1] = 0.0F;
    }

    /** 只读判断当前是否满足进入撤退条件（不修改状态机或冷却时间戳）。 */
    public boolean wouldTrigger(
            float selfX, float selfY, boolean hasCluster, float clusterX, float clusterY, double simTimeSec) {
        if (active) {
            return true;
        }
        if (!hasCluster || simTimeSec < cooldownUntilSimSec) {
            return false;
        }
        float dx = selfX - clusterX;
        float dy = selfY - clusterY;
        return dx * dx + dy * dy < TRIGGER_RADIUS_TILES * TRIGGER_RADIUS_TILES;
    }

    /**
     * 每帧更新撤退状态机并返回当前是否应维持撤退动作。
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
        return update(selfX, selfY, selfZ, hasCluster, clusterX, clusterY, simTimeSec, probe, null);
    }

    /**
     * 每帧更新撤退状态机（接入导演威胁评价避免退进背后的其余散尸群）。
     */
    public boolean update(
            float selfX,
            float selfY,
            int selfZ,
            boolean hasCluster,
            float clusterX,
            float clusterY,
            double simTimeSec,
            EmberSquadDirector.PassabilityProbe probe,
            EmberSquadDirector director) {
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

        CombatLocomotionSolver.pickSafeRetreatGoal(
                selfX, selfY, selfZ, dirX, dirY, RETREAT_DISTANCE_TILES, director, probe, goalScratch);
        this.targetX = goalScratch[0];
        this.targetY = goalScratch[1];
        this.enteredAtSimSec = simTimeSec;
        this.active = true;
        return true;
    }
}
