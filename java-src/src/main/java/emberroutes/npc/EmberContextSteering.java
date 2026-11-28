package emberroutes.npc;

import java.util.ArrayList;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.pathfind.PolygonalMap2;

/**
 * 双层零 GC 局部多智能体错身避障转向器：
 * <ol>
 *   <li><b>真人玩家走廊避让（层 1）</b>：1.45m 前瞻 / 0.62m 半宽走廊检测，静止玩家单侧锁定 {@code staticPass}
 *       直至沿基准方向越过 {@code >= 0.62m}，{@code 900ms} 侧向保持防摇摆；楼梯或窄门双侧不可绕时先原地礼让
 *       {@code 1400ms} 再低速挤过；避让期触发 {@code 450ms} 精细步行降档；</li>
 *   <li><b>队友 NPC 上下文方向场（层 2）</b>：16 向环形兴趣-危险场，带靠右偏置与 {@code 1100ms} 窄口礼让。</li>
 * </ol>
 */
public final class EmberContextSteering {
    public static final float PLAYER_LOOKAHEAD = PlayerAvoidanceMath.PLAYER_LOOKAHEAD;
    public static final float PLAYER_CORRIDOR_HALF_WIDTH = PlayerAvoidanceMath.PLAYER_CORRIDOR_HALF_WIDTH;
    public static final float PLAYER_BODY_CLEARANCE = PlayerAvoidanceMath.PLAYER_BODY_CLEARANCE;
    public static final float PLAYER_PROBE_DISTANCE = PlayerAvoidanceMath.PLAYER_PROBE_DISTANCE;
    public static final float STATIC_PASS_RELEASE_FORWARD = PlayerAvoidanceMath.STATIC_PASS_RELEASE_FORWARD;
    public static final float STATIC_PASS_MAX_DISTANCE = PlayerAvoidanceMath.STATIC_PASS_MAX_DISTANCE;
    public static final long SIDE_HOLD_MS = PlayerAvoidanceMath.SIDE_HOLD_MS;
    public static final long YIELD_BEFORE_SQUEEZE_MS = PlayerAvoidanceMath.YIELD_BEFORE_SQUEEZE_MS;
    public static final long PRECISION_HOLD_MS = PlayerAvoidanceMath.PRECISION_HOLD_MS;

    private static final int SLOTS = 16;
    private static final float[] DIR_X = new float[SLOTS];
    private static final float[] DIR_Y = new float[SLOTS];
    private static final float DETECT_RADIUS = 1.65F;
    private static final float PERSONAL_SPACE = 0.56F;
    private static final float WALL_PROBE = 0.68F;
    private static final long YIELD_DURATION_MS = 1100L;

    static {
        for (int i = 0; i < SLOTS; i++) {
            double a = (Math.PI * 2.0 * i) / SLOTS;
            DIR_X[i] = (float) Math.cos(a);
            DIR_Y[i] = (float) Math.sin(a);
        }
    }

    private final float[] interest = new float[SLOTS];
    private final float[] danger = new float[SLOTS];
    private long blockedSinceMs;
    private int latchedSlot = -1;
    private long latchUntilMs;

    private int playerSide = 1;
    private long sideHoldUntilMs;
    private long playerBlockedSinceMs;
    private long precisionHoldUntilMs;
    private IsoPlayer staticPassPlayer;
    private float staticPassDirX;
    private float staticPassDirY;

    public record SteerOutput(
            float dirX, float dirY, float speedCap, boolean yielding, boolean precisionWalk) {}

    public void reset() {
        blockedSinceMs = 0L;
        latchedSlot = -1;
        latchUntilMs = 0L;
        sideHoldUntilMs = 0L;
        playerBlockedSinceMs = 0L;
        precisionHoldUntilMs = 0L;
        staticPassPlayer = null;
        staticPassDirX = 0.0F;
        staticPassDirY = 0.0F;
    }

    public SteerOutput evaluate(IsoPlayer self, float desiredX, float desiredY) {
        return evaluate(self, desiredX, desiredY, null, false, false);
    }

    public SteerOutput evaluate(
            IsoPlayer self,
            float desiredX,
            float desiredY,
            IsoGameCharacter followLeader,
            boolean leaderMoving) {
        return evaluate(self, desiredX, desiredY, followLeader, leaderMoving, false);
    }

    public SteerOutput evaluate(
            IsoPlayer self,
            float desiredX,
            float desiredY,
            IsoGameCharacter followLeader,
            boolean leaderMoving,
            boolean urgent) {
        float dLen = (float) Math.hypot(desiredX, desiredY);
        if (self == null || dLen < 1.0E-4F) {
            return new SteerOutput(0.0F, 0.0F, 0.0F, false, false);
        }
        float ux = desiredX / dLen;
        float uy = desiredY / dLen;
        long now = System.currentTimeMillis();
        int z = EmberTargetProjector.levelOf(self.getZ());

        SteerOutput playerAvoid = evaluateHumanPlayers(self, ux, uy, z, followLeader, leaderMoving, urgent, now);
        if (playerAvoid != null) {
            return playerAvoid;
        }

        boolean precisionActive = now < precisionHoldUntilMs;
        float npcResultCap = precisionActive ? 0.62F : 1.0F;

        float sx = self.getX();
        float sy = self.getY();
        int centerTileX = (int) Math.floor(sx);
        int centerTileY = (int) Math.floor(sy);

        boolean selfNearStairs = (self instanceof IsoNPC selfNpc) && EmberStairDiag.isNearStairs(selfNpc);
        boolean hasNearbyAgent = false;
        for (int i = 0; i < SLOTS; i++) {
            danger[i] = 0.0F;
        }

        for (int gy = -2; gy <= 2; gy++) {
            for (int gx = -2; gx <= 2; gx++) {
                IsoGridSquare sq = EmberTargetProjector.getSquare(centerTileX + gx, centerTileY + gy, z);
                if (sq == null) {
                    continue;
                }
                ArrayList<IsoMovingObject> objs = sq.getMovingObjects();
                for (int idx = 0, sz = objs.size(); idx < sz; idx++) {
                    IsoMovingObject mo = objs.get(idx);
                    if (mo == self || !(mo instanceof IsoNPC otherNpc) || otherNpc.isDead()) {
                        continue;
                    }
                    if (otherNpc.isSittingOnFurniture() || otherNpc.isOnBed()) {
                        continue;
                    }
                    if (selfNearStairs || EmberStairDiag.isNearStairs(otherNpc)) {
                        continue;
                    }
                    float rx = otherNpc.getX() - sx;
                    float ry = otherNpc.getY() - sy;
                    float distSq = rx * rx + ry * ry;
                    if (distSq > DETECT_RADIUS * DETECT_RADIUS || distSq < 1.0E-5F) {
                        continue;
                    }
                    float fwd = rx * ux + ry * uy;
                    if (fwd < -0.15F) {
                        continue;
                    }

                    float dist = (float) Math.sqrt(distSq);
                    if (dist > 0.80F && otherNpc.isJustMoved() && otherNpc.playerMoveDir != null) {
                        float mvX = otherNpc.playerMoveDir.x;
                        float mvY = otherNpc.playerMoveDir.y;
                        float mvLen = (float) Math.hypot(mvX, mvY);
                        if (mvLen > 1.0E-4F && ((mvX / mvLen) * ux + (mvY / mvLen) * uy) > 0.45F) {
                            continue;
                        }
                    }

                    hasNearbyAgent = true;
                    float obX = rx / dist;
                    float obY = ry / dist;
                    float weight = Math.max(0.0F, (DETECT_RADIUS - dist) / (DETECT_RADIUS - 0.30F));
                    weight = Math.min(1.0F, weight);

                    for (int k = 0; k < SLOTS; k++) {
                        float align = DIR_X[k] * obX + DIR_Y[k] * obY;
                        float perp = Math.abs(rx * DIR_Y[k] - ry * DIR_X[k]);
                        if (align > 0.10F && perp < PERSONAL_SPACE) {
                            float d = weight * (1.0F - (perp / PERSONAL_SPACE) * 0.65F);
                            if (d > danger[k]) {
                                danger[k] = d;
                            }
                        }
                    }
                }
            }
        }

        if (!hasNearbyAgent) {
            blockedSinceMs = 0L;
            latchedSlot = -1;
            return new SteerOutput(ux, uy, npcResultCap, false, precisionActive);
        }

        int bestSlot = -1;
        float bestScore = -1.0F;

        for (int k = 0; k < SLOTS; k++) {
            float dot = DIR_X[k] * ux + DIR_Y[k] * uy;
            if (dot <= 0.05F) {
                interest[k] = 0.0F;
                continue;
            }
            float starboard = ux * DIR_Y[k] - uy * DIR_X[k];
            float rightBias = starboard > 0.05F ? 0.07F : 0.0F;
            float hystBias = (k == latchedSlot && now < latchUntilMs) ? 0.09F : 0.0F;
            interest[k] = dot + rightBias + hystBias;

            if (danger[k] >= 0.88F) {
                continue;
            }

            float px = sx + DIR_X[k] * WALL_PROBE;
            float py = sy + DIR_Y[k] * WALL_PROBE;
            if (PolygonalMap2.instance != null
                    && PolygonalMap2.instance.lineClearCollide(sx, sy, px, py, z, self, false, true)) {
                continue;
            }

            float score = interest[k] - danger[k] * 0.95F;
            if (score > bestScore) {
                bestScore = score;
                bestSlot = k;
            }
        }

        if (bestSlot < 0) {
            if (blockedSinceMs == 0L) {
                blockedSinceMs = now;
            }
            if (now - blockedSinceMs < YIELD_DURATION_MS) {
                return new SteerOutput(ux, uy, 0.0F, true, precisionActive);
            }
            return new SteerOutput(ux, uy, 0.32F, false, precisionActive);
        }

        blockedSinceMs = 0L;
        latchedSlot = bestSlot;
        latchUntilMs = now + 550L;
        return new SteerOutput(DIR_X[bestSlot], DIR_Y[bestSlot], 0.62F, false, precisionActive);
    }

    private SteerOutput evaluateHumanPlayers(
            IsoPlayer self,
            float ux,
            float uy,
            int z,
            IsoGameCharacter followLeader,
            boolean leaderMoving,
            boolean urgent,
            long now) {
        IsoPlayer activeBlocker = null;
        float baseX = ux;
        float baseY = uy;

        // 1. 若仍在静止玩家绕行锁定期内，先校验是否满足延续条件
        if (staticPassPlayer != null) {
            if (!isEligibleHumanBlocker(self, staticPassPlayer, z)
                    || isPlayerMoving(staticPassPlayer)
                    || (ux * staticPassDirX + uy * staticPassDirY) < 0.15F) {
                staticPassPlayer = null;
            } else {
                float dx = staticPassPlayer.getX() - self.getX();
                float dy = staticPassPlayer.getY() - self.getY();
                float distSq = dx * dx + dy * dy;
                if (distSq > STATIC_PASS_MAX_DISTANCE * STATIC_PASS_MAX_DISTANCE
                        || hasPassedStaticBlocker(dx, dy, staticPassDirX, staticPassDirY)) {
                    staticPassPlayer = null;
                } else {
                    activeBlocker = staticPassPlayer;
                    baseX = staticPassDirX;
                    baseY = staticPassDirY;
                }
            }
        }

        // 2. 否则扫描前方走廊内的最近真人玩家
        if (activeBlocker == null) {
            float bestDistSq = Float.MAX_VALUE;
            IsoPlayer[] players = IsoPlayer.players;
            for (int i = 0; i < players.length; i++) {
                IsoPlayer p = players[i];
                if (!isEligibleHumanBlocker(self, p, z)) {
                    continue;
                }
                float dx = p.getX() - self.getX();
                float dy = p.getY() - self.getY();
                float distSq = dx * dx + dy * dy;
                if (distSq > PLAYER_LOOKAHEAD * PLAYER_LOOKAHEAD) {
                    continue;
                }
                // 仅当跟随移动中的队长且距离 > 0.95m 并同向行进时免除额外绕行，其余（静止/近身/迎面/战斗）均主动避让
                if (followLeader != null && p == followLeader && leaderMoving && distSq > 0.95F * 0.95F) {
                    if (p.playerMoveDir != null) {
                        float mvLen = (float) Math.hypot(p.playerMoveDir.x, p.playerMoveDir.y);
                        if (mvLen > 1.0E-4F
                                && ((p.playerMoveDir.x / mvLen) * ux + (p.playerMoveDir.y / mvLen) * uy) > 0.45F) {
                            continue;
                        }
                    }
                }
                if (!blocksCorridor(dx, dy, ux, uy)) {
                    continue;
                }
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    activeBlocker = p;
                }
            }
            if (activeBlocker != null && !isPlayerMoving(activeBlocker)) {
                staticPassPlayer = activeBlocker;
                staticPassDirX = ux;
                staticPassDirY = uy;
                baseX = ux;
                baseY = uy;
            }
        }

        if (activeBlocker == null) {
            playerBlockedSinceMs = 0L;
            return null;
        }

        precisionHoldUntilMs = now + PRECISION_HOLD_MS;
        IsoGridSquare sq = self.getCurrentSquare();
        if (sq != null && sq.HasStairs()) {
            return waitOrSqueeze(baseX, baseY, urgent, now);
        }

        float sx = self.getX();
        float sy = self.getY();
        float bx = activeBlocker.getX();
        float by = activeBlocker.getY();
        float dx = bx - sx;
        float dy = by - sy;
        float distSq = dx * dx + dy * dy;

        int preferredSide = (now < sideHoldUntilMs) ? playerSide : deterministicSide(self, activeBlocker);
        int otherSide = -preferredSide;

        float[] prefDetour = computePlayerDetour(dx, dy, baseX, baseY, preferredSide);
        boolean prefSafe = isSafeDetour(self, sx, sy, z, bx, by, distSq, prefDetour[0], prefDetour[1]);
        float[] altDetour = computePlayerDetour(dx, dy, baseX, baseY, otherSide);
        boolean altSafe = isSafeDetour(self, sx, sy, z, bx, by, distSq, altDetour[0], altDetour[1]);

        if (prefSafe && altSafe) {
            if (now >= sideHoldUntilMs) {
                float prefClear = staticClearanceScore(self, sx, sy, z, prefDetour[0], prefDetour[1]);
                float altClear = staticClearanceScore(self, sx, sy, z, altDetour[0], altDetour[1]);
                if (altClear > prefClear + 0.05F) {
                    preferredSide = otherSide;
                    prefDetour = altDetour;
                }
                playerSide = preferredSide;
                sideHoldUntilMs = now + SIDE_HOLD_MS;
            }
            playerBlockedSinceMs = 0L;
            return new SteerOutput(prefDetour[0], prefDetour[1], 0.55F, false, true);
        }
        if (prefSafe) {
            playerSide = preferredSide;
            sideHoldUntilMs = now + SIDE_HOLD_MS;
            playerBlockedSinceMs = 0L;
            return new SteerOutput(prefDetour[0], prefDetour[1], 0.55F, false, true);
        }
        if (altSafe) {
            playerSide = otherSide;
            sideHoldUntilMs = now + SIDE_HOLD_MS;
            playerBlockedSinceMs = 0L;
            return new SteerOutput(altDetour[0], altDetour[1], 0.55F, false, true);
        }

        return waitOrSqueeze(baseX, baseY, urgent, now);
    }

    private SteerOutput waitOrSqueeze(float baseX, float baseY, boolean urgent, long now) {
        if (urgent) {
            playerBlockedSinceMs = 0L;
            return new SteerOutput(baseX, baseY, 0.45F, false, true);
        }
        if (playerBlockedSinceMs == 0L) {
            playerBlockedSinceMs = now;
        }
        if (now - playerBlockedSinceMs < YIELD_BEFORE_SQUEEZE_MS) {
            return new SteerOutput(baseX, baseY, 0.0F, true, true);
        }
        return new SteerOutput(baseX, baseY, 0.32F, false, true);
    }

    private static boolean isEligibleHumanBlocker(IsoPlayer self, IsoPlayer p, int selfZ) {
        if (p == null || p == self || p.isNpc() || p.isDead() || p.isZombie() || p.getVehicle() != null) {
            return false;
        }
        return EmberTargetProjector.levelOf(p.getZ()) == selfZ;
    }

    private static boolean isPlayerMoving(IsoPlayer p) {
        if (p == null) {
            return false;
        }
        if (p.isJustMoved()) {
            return true;
        }
        return p.playerMoveDir != null && p.playerMoveDir.getLengthSquared() > 4.0E-4F;
    }

    private static int deterministicSide(IsoPlayer a, IsoPlayer b) {
        int h = System.identityHashCode(a) ^ Integer.rotateLeft(System.identityHashCode(b), 13);
        return (h & 1) == 0 ? 1 : -1;
    }

    private static boolean isSafeDetour(
            IsoPlayer self,
            float sx,
            float sy,
            int z,
            float bx,
            float by,
            float curDistSq,
            float dirX,
            float dirY) {
        float ex = sx + dirX * PLAYER_PROBE_DISTANCE;
        float ey = sy + dirY * PLAYER_PROBE_DISTANCE;
        if (PolygonalMap2.instance != null
                && PolygonalMap2.instance.lineClearCollide(sx, sy, ex, ey, z, self, false, true)) {
            return false;
        }
        float clearanceSq = PLAYER_BODY_CLEARANCE * PLAYER_BODY_CLEARANCE;
        if (curDistSq < clearanceSq) {
            float endDistSq = (ex - bx) * (ex - bx) + (ey - by) * (ey - by);
            return endDistSq > curDistSq + 0.02F;
        }
        return pointToSegmentDistSq(bx, by, sx, sy, ex, ey) >= clearanceSq;
    }

    private static float staticClearanceScore(
            IsoPlayer self, float sx, float sy, int z, float dirX, float dirY) {
        if (PolygonalMap2.instance == null) {
            return 2.0F;
        }
        float score = 0.0F;
        if (!PolygonalMap2.instance.lineClearCollide(
                sx, sy, sx + dirX * 0.55F, sy + dirY * 0.55F, z, self, false, true)) {
            score += 1.0F;
        }
        if (!PolygonalMap2.instance.lineClearCollide(
                sx, sy, sx + dirX * 1.15F, sy + dirY * 1.15F, z, self, false, true)) {
            score += 1.0F;
        }
        return score;
    }

    /** 走廊阻塞判定：相对向量落入前方 {@code [-0.05, 1.45]m} 且横向垂直偏移 {@code <= 0.62m}。 */
    public static boolean blocksCorridor(float dx, float dy, float dirX, float dirY) {
        return PlayerAvoidanceMath.blocksCorridor(dx, dy, dirX, dirY);
    }

    /** 当 NPC 沿锁存基准方向已超前静止阻挡者 {@code >= 0.62m} 时释放 {@code staticPass}。 */
    public static boolean hasPassedStaticBlocker(
            float dxPlayerMinusNpc, float dyPlayerMinusNpc, float passDirX, float passDirY) {
        return PlayerAvoidanceMath.hasPassedStaticBlocker(dxPlayerMinusNpc, dyPlayerMinusNpc, passDirX, passDirY);
    }

    /** 合成侧移绕行单位向量：{@code 0.85*base + (0.35+0.55*t)*perp + 0.25*t*away}。 */
    public static float[] computePlayerDetour(
            float dxPlayerMinusNpc, float dyPlayerMinusNpc, float baseDirX, float baseDirY, int side) {
        return PlayerAvoidanceMath.computePlayerDetour(dxPlayerMinusNpc, dyPlayerMinusNpc, baseDirX, baseDirY, side);
    }

    public static float pointToSegmentDistSq(float px, float py, float ax, float ay, float bx, float by) {
        return PlayerAvoidanceMath.pointToSegmentDistSq(px, py, ax, ay, bx, by);
    }

    /** 零 B42 运行时依赖的纯几何玩家避让数学内核（供单测与运行时共用）。 */
    public static final class PlayerAvoidanceMath {
        public static final float PLAYER_LOOKAHEAD = 1.45F;
        public static final float PLAYER_CORRIDOR_HALF_WIDTH = 0.62F;
        public static final float PLAYER_BODY_CLEARANCE = 0.48F;
        public static final float PLAYER_PROBE_DISTANCE = 0.95F;
        public static final float STATIC_PASS_RELEASE_FORWARD = 0.62F;
        public static final float STATIC_PASS_MAX_DISTANCE = 3.0F;
        public static final long SIDE_HOLD_MS = 900L;
        public static final long YIELD_BEFORE_SQUEEZE_MS = 1400L;
        public static final long PRECISION_HOLD_MS = 450L;

        private PlayerAvoidanceMath() {}

        public static boolean blocksCorridor(float dx, float dy, float dirX, float dirY) {
            float distSq = dx * dx + dy * dy;
            if (distSq > PLAYER_LOOKAHEAD * PLAYER_LOOKAHEAD) {
                return false;
            }
            float forward = dx * dirX + dy * dirY;
            if (forward < -0.05F || forward > PLAYER_LOOKAHEAD) {
                return false;
            }
            float lateral = Math.abs(dx * (-dirY) + dy * dirX);
            return lateral <= PLAYER_CORRIDOR_HALF_WIDTH;
        }

        public static boolean hasPassedStaticBlocker(
                float dxPlayerMinusNpc, float dyPlayerMinusNpc, float passDirX, float passDirY) {
            float npcForwardPastBlocker = -(dxPlayerMinusNpc * passDirX + dyPlayerMinusNpc * passDirY);
            return npcForwardPastBlocker >= STATIC_PASS_RELEASE_FORWARD;
        }

        public static float[] computePlayerDetour(
                float dxPlayerMinusNpc, float dyPlayerMinusNpc, float baseDirX, float baseDirY, int side) {
            float dist = (float) Math.hypot(dxPlayerMinusNpc, dyPlayerMinusNpc);
            float closeness = (PLAYER_LOOKAHEAD - dist) / (PLAYER_LOOKAHEAD - PLAYER_BODY_CLEARANCE);
            if (closeness < 0.0F) {
                closeness = 0.0F;
            } else if (closeness > 1.0F) {
                closeness = 1.0F;
            }
            float perpX = -baseDirY * side;
            float perpY = baseDirX * side;
            float awayX = dist > 1.0E-4F ? -(dxPlayerMinusNpc / dist) : perpX;
            float awayY = dist > 1.0E-4F ? -(dyPlayerMinusNpc / dist) : perpY;
            float wForward = 0.85F;
            float wLateral = 0.35F + 0.55F * closeness;
            float wAway = 0.25F * closeness;
            float rx = baseDirX * wForward + perpX * wLateral + awayX * wAway;
            float ry = baseDirY * wForward + perpY * wLateral + awayY * wAway;
            float len = (float) Math.hypot(rx, ry);
            if (len < 1.0E-4F) {
                return new float[] {perpX, perpY};
            }
            return new float[] {rx / len, ry / len};
        }

        public static float pointToSegmentDistSq(float px, float py, float ax, float ay, float bx, float by) {
            float vx = bx - ax;
            float vy = by - ay;
            float lenSq = vx * vx + vy * vy;
            if (lenSq <= 1.0E-8F) {
                return (px - ax) * (px - ax) + (py - ay) * (py - ay);
            }
            float t = ((px - ax) * vx + (py - ay) * vy) / lenSq;
            if (t < 0.0F) {
                t = 0.0F;
            } else if (t > 1.0F) {
                t = 1.0F;
            }
            float cx = ax + t * vx;
            float cy = ay + t * vy;
            return (px - cx) * (px - cx) + (py - cy) * (py - cy);
        }
    }
}
