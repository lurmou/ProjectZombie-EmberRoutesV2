package emberroutes.npc.combat;

/**
 * 远程失视线主动切角走位内核（零 {@code zombie.*} 依赖）：
 * 无 LOS 或友军挡枪线时，主动侧移 1.2–3.2 格或斜前翼切角寻找清晰射界，彻底杜绝射手持枪直接冲去贴脸。
 */
public final class RangedTacticsController {
    @FunctionalInterface
    public interface LosProbe {
        boolean isLosClear(float x1, float y1, float x2, float y2, int z);
    }

    public static final class RepositionCommand {
        public boolean shouldMove;
        public boolean run;
        public boolean aimWhileMoving;
        public float goalX;
        public float goalY;
        public float dirX;
        public float dirY;
        public boolean directVector;
        public boolean foundClearLos;
        public boolean retreatBlocked;

        public void reset() {
            shouldMove = false;
            run = false;
            aimWhileMoving = false;
            goalX = 0.0F;
            goalY = 0.0F;
            dirX = 0.0F;
            dirY = 0.0F;
            directVector = false;
            foundClearLos = false;
            retreatBlocked = false;
        }
    }

    private static final int NUM_ESCAPE_DIRS = 16;
    private static final float[] ESCAPE_COS = new float[NUM_ESCAPE_DIRS];
    private static final float[] ESCAPE_SIN = new float[NUM_ESCAPE_DIRS];
    private static final float[] LATERAL_OFFSETS = {1.2F, -1.2F, 2.2F, -2.2F, 3.2F, -3.2F};
    private static final float[] DIAGONAL_ANGLES_RAD = {
        (float) Math.toRadians(35.0),
        (float) Math.toRadians(-35.0),
        (float) Math.toRadians(55.0),
        (float) Math.toRadians(-55.0)
    };

    static {
        for (int i = 0; i < NUM_ESCAPE_DIRS; i++) {
            double ang = i * (Math.PI * 2.0 / NUM_ESCAPE_DIRS);
            ESCAPE_COS[i] = (float) Math.cos(ang);
            ESCAPE_SIN[i] = (float) Math.sin(ang);
        }
    }

    private RangedTacticsController() {}

    /**
     * 远程战术走位与失视线主动切角重定位（0-GC）：
     * <ol>
     *   <li>若无 LOS 或被友军安全锥阻塞：先在左右法向 {@code ±1.2/±2.2/±3.2m} 与斜前 {@code ±35°/±55°} 搜索既有路又通 LOS 的侧移点；
     *       找不到局部直达侧移点且距离 > 4m（非驻守锁定）时，下发 A* 绕行至最优侧面射击角；</li>
     *   <li>若贴近危险距离 {@code < minSafeDist}：边瞄准边沿切向避墙后撤；</li>
     *   <li>若超出 {@code 0.9 * maxRange}（且未开启驻守锁定）：非瞄准快步前推至 {@code 0.8 * maxRange}。</li>
     * </ol>
     */
    public static void computeRangedReposition(
            float sx,
            float sy,
            int sz,
            float tx,
            float ty,
            float maxRange,
            float minSafeDist,
            boolean hasLos,
            boolean friendlyBlocked,
            boolean holdActive,
            float holdX,
            float holdY,
            float holdRadius,
            EmberSquadDirector.PassabilityProbe walkProbe,
            LosProbe losProbe,
            RepositionCommand out) {
        computeRangedReposition(
                sx, sy, sz, tx, ty, maxRange, minSafeDist,
                hasLos, friendlyBlocked, holdActive, holdX, holdY, holdRadius,
                null, walkProbe, losProbe, out);
    }

    public static void computeRangedReposition(
            float sx,
            float sy,
            int sz,
            float tx,
            float ty,
            float maxRange,
            float minSafeDist,
            boolean hasLos,
            boolean friendlyBlocked,
            boolean holdActive,
            float holdX,
            float holdY,
            float holdRadius,
            EmberSquadDirector director,
            EmberSquadDirector.PassabilityProbe walkProbe,
            LosProbe losProbe,
            RepositionCommand out) {
        out.reset();
        float dx = tx - sx;
        float dy = ty - sy;
        float dist = (float) Math.hypot(dx, dy);
        if (dist < 1.0E-4F) {
            return;
        }
        float dirToTargetX = dx / dist;
        float dirToTargetY = dy / dist;

        // 1. 无视线或友军遮挡枪线：主动侧移/斜切寻找清晰射界
        if (!hasLos || friendlyBlocked) {
            float perpX = -dirToTargetY;
            float perpY = dirToTargetX;

            float bestX = 0.0F;
            float bestY = 0.0F;
            float bestCost = Float.MAX_VALUE;
            boolean found = false;

            for (int i = 0; i < LATERAL_OFFSETS.length; i++) {
                float off = LATERAL_OFFSETS[i];
                float cx = sx + perpX * off;
                float cy = sy + perpY * off;
                float cost = evaluateSidestepCandidate(
                        sx, sy, sz, tx, ty, cx, cy, minSafeDist, maxRange,
                        holdActive, holdX, holdY, holdRadius, director, walkProbe, losProbe);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestX = cx;
                    bestY = cy;
                    found = true;
                }
            }

            double baseAng = Math.atan2(dirToTargetY, dirToTargetX);
            for (int i = 0; i < DIAGONAL_ANGLES_RAD.length; i++) {
                double ang = baseAng + DIAGONAL_ANGLES_RAD[i];
                float cx = sx + (float) Math.cos(ang) * 2.0F;
                float cy = sy + (float) Math.sin(ang) * 2.0F;
                float cost = evaluateSidestepCandidate(
                        sx, sy, sz, tx, ty, cx, cy, minSafeDist, maxRange,
                        holdActive, holdX, holdY, holdRadius, director, walkProbe, losProbe);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestX = cx;
                    bestY = cy;
                    found = true;
                }
            }

            if (found) {
                float mdx = bestX - sx;
                float mdy = bestY - sy;
                float ml = (float) Math.hypot(mdx, mdy);
                if (ml > 0.05F) {
                    out.shouldMove = true;
                    out.run = false;
                    out.aimWhileMoving = true;
                    out.directVector = true;
                    out.foundClearLos = true;
                    out.goalX = bestX;
                    out.goalY = bestY;
                    out.dirX = mdx / ml;
                    out.dirY = mdy / ml;
                }
                return;
            }

            if (!holdActive && dist > 4.0F) {
                // 局部一步无直达视线：向目标前侧翼 35°（距敌约 0.72*maxRange）规划 A* 绕墙角寻射界，严禁进近战
                float desiredRing = Math.max(4.0F, Math.min(maxRange * 0.75F, dist - 2.0F));
                float gx = tx - dirToTargetX * desiredRing + perpX * 2.2F;
                float gy = ty - dirToTargetY * desiredRing + perpY * 2.2F;
                float gdx = gx - sx;
                float gdy = gy - sy;
                float gl = (float) Math.hypot(gdx, gdy);
                if (gl > 0.35F) {
                    out.shouldMove = true;
                    out.run = gl > 4.5F;
                    out.aimWhileMoving = false;
                    out.directVector = false;
                    out.foundClearLos = false;
                    out.goalX = gx;
                    out.goalY = gy;
                    out.dirX = gdx / gl;
                    out.dirY = gdy / gl;
                }
            }
            return;
        }

        // 2. 视线畅通但敌人逼入安全半径内：边瞄准边经 16 向安全场避墙、避背后尸群后撤
        if (dist < minSafeDist) {
            solveSafeEscape(
                    sx, sy, sz, -dirToTargetX, -dirToTargetY,
                    holdActive, holdX, holdY, holdRadius,
                    director, walkProbe, out);
            return;
        }

        // 3. 视线畅通但超过 90% 有效射程且允许机动：收拢至 80% 射程稳定开火
        if (!holdActive && dist > maxRange * 0.90F) {
            float idealStand = Math.max(minSafeDist + 1.0F, maxRange * 0.80F);
            float gx = tx - dirToTargetX * idealStand;
            float gy = ty - dirToTargetY * idealStand;
            float gdx = gx - sx;
            float gdy = gy - sy;
            float gl = (float) Math.hypot(gdx, gdy);
            if (gl > 0.35F) {
                out.shouldMove = true;
                out.run = gl > 4.0F;
                out.aimWhileMoving = false;
                out.directVector = walkProbe == null || walkProbe.isLineClear(sx, sy, gx, gy, sz);
                out.foundClearLos = true;
                out.goalX = gx;
                out.goalY = gy;
                out.dirX = gdx / gl;
                out.dirY = gdy / gl;
            }
        }
    }

    private static float evaluateSidestepCandidate(
            float sx,
            float sy,
            int sz,
            float tx,
            float ty,
            float cx,
            float cy,
            float minSafeDist,
            float maxRange,
            boolean holdActive,
            float holdX,
            float holdY,
            float holdRadius,
            EmberSquadDirector director,
            EmberSquadDirector.PassabilityProbe walkProbe,
            LosProbe losProbe) {
        if (holdActive && Math.hypot(cx - holdX, cy - holdY) > holdRadius) {
            return Float.MAX_VALUE;
        }
        if (walkProbe != null && !walkProbe.isLineClear(sx, sy, cx, cy, sz)) {
            return Float.MAX_VALUE;
        }
        float distFromCandToTarget = (float) Math.hypot(tx - cx, ty - cy);
        if (distFromCandToTarget > maxRange + 0.5F || distFromCandToTarget < minSafeDist * 0.65F) {
            return Float.MAX_VALUE;
        }
        if (losProbe != null && !losProbe.isLosClear(cx, cy, tx, ty, sz)) {
            return Float.MAX_VALUE;
        }
        float threatDanger = CombatLocomotionSolver.evaluatePointThreatDanger(cx, cy, sz, director);
        if (threatDanger >= 4.0F) {
            return Float.MAX_VALUE;
        }
        return (float) Math.hypot(cx - sx, cy - sy) + threatDanger * 0.85F;
    }

    private static void solveSafeEscape(
            float sx,
            float sy,
            int sz,
            float awayX,
            float awayY,
            boolean holdActive,
            float holdX,
            float holdY,
            float holdRadius,
            EmberSquadDirector director,
            EmberSquadDirector.PassabilityProbe walkProbe,
            RepositionCommand out) {
        final float step = 1.4F;
        int bestIdx = -1;
        float bestScore = -Float.MAX_VALUE;
        float bestDanger = Float.MAX_VALUE;
        float curHoldDist = holdActive ? (float) Math.hypot(sx - holdX, sy - holdY) : 0.0F;

        for (int i = 0; i < NUM_ESCAPE_DIRS; i++) {
            float dx = ESCAPE_COS[i];
            float dy = ESCAPE_SIN[i];
            float align = dx * awayX + dy * awayY;
            if (align <= 0.15F) {
                continue;
            }
            float candX = sx + dx * step;
            float candY = sy + dy * step;
            if (walkProbe != null && !walkProbe.isLineClear(sx, sy, candX, candY, sz)) {
                continue;
            }
            if (holdActive && holdRadius > 0.5F) {
                float candHoldDist = (float) Math.hypot(candX - holdX, candY - holdY);
                if (candHoldDist > holdRadius && candHoldDist > curHoldDist) {
                    continue;
                }
            }
            float danger = CombatLocomotionSolver.evaluatePointThreatDanger(candX, candY, sz, director);
            float score = align * 1.40F - danger * 1.65F;
            if (bestIdx < 0 || score > bestScore) {
                bestIdx = i;
                bestScore = score;
                bestDanger = danger;
            }
        }

        if (bestIdx < 0) {
            out.retreatBlocked = true;
            return;
        }
        float dirX = ESCAPE_COS[bestIdx];
        float dirY = ESCAPE_SIN[bestIdx];
        out.retreatBlocked = bestDanger >= 2.20F;
        out.shouldMove = true;
        out.run = false;
        out.aimWhileMoving = true;
        out.directVector = true;
        out.foundClearLos = true;
        out.goalX = sx + dirX * step;
        out.goalY = sy + dirY * step;
        out.dirX = dirX;
        out.dirY = dirY;
    }
}
