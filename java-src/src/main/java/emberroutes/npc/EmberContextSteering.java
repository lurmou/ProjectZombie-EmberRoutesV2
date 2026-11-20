package emberroutes.npc;

import java.util.ArrayList;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.pathfind.PolygonalMap2;

/**
 * 16 向环形上下文方向场（Context Steering）局部多智能体错身避障：
 * - 零每帧对象分配（复用预计算单位方向环与评分缓冲）；
 * - 同时避让本地真玩家与其他存活人类 NPC，默认带靠右通行偏置，走廊迎面自动顺滑错身；
 * - 1 格宽窄门被其他角色完全堵死且两侧墙壁不可绕行时，进入短时礼让驻留（Yield），超时后再慢步挤过。
 */
public final class EmberContextSteering {
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

    public record SteerOutput(float dirX, float dirY, float speedCap, boolean yielding) {}

    public void reset() {
        blockedSinceMs = 0L;
        latchedSlot = -1;
        latchUntilMs = 0L;
    }

    public SteerOutput evaluate(IsoPlayer self, float desiredX, float desiredY) {
        return evaluate(self, desiredX, desiredY, null, false);
    }

    public SteerOutput evaluate(
            IsoPlayer self, float desiredX, float desiredY,
            IsoGameCharacter followLeader, boolean leaderMoving) {
        float dLen = (float) Math.hypot(desiredX, desiredY);
        if (self == null || dLen < 1.0E-4F) {
            return new SteerOutput(0.0F, 0.0F, 0.0F, false);
        }
        float ux = desiredX / dLen;
        float uy = desiredY / dLen;

        int z = EmberTargetProjector.levelOf(self.getZ());
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
                if (sq == null) continue;
                ArrayList<IsoMovingObject> objs = sq.getMovingObjects();
                for (int idx = 0, sz = objs.size(); idx < sz; idx++) {
                    IsoMovingObject mo = objs.get(idx);
                    if (mo == self || !(mo instanceof IsoGameCharacter ch) || ch instanceof IsoZombie || ch.isDead()) {
                        continue;
                    }
                    if (mo instanceof IsoNPC otherNpc && (selfNearStairs || EmberStairDiag.isNearStairs(otherNpc))) {
                        continue;
                    }
                    float rx = ch.getX() - sx;
                    float ry = ch.getY() - sy;
                    float distSq = rx * rx + ry * ry;
                    if (distSq > DETECT_RADIUS * DETECT_RADIUS || distSq < 1.0E-5F) continue;
                    float fwd = rx * ux + ry * uy;
                    if (fwd < -0.15F) continue;

                    float dist = (float) Math.sqrt(distSq);
                    if (dist > 0.80F) {
                        if (followLeader != null && ch == followLeader && leaderMoving) {
                            continue;
                        }
                        if (ch instanceof IsoNPC otherNpc && otherNpc.isJustMoved() && otherNpc.playerMoveDir != null) {
                            float mvX = otherNpc.playerMoveDir.x;
                            float mvY = otherNpc.playerMoveDir.y;
                            float mvLen = (float) Math.hypot(mvX, mvY);
                            if (mvLen > 1.0E-4F && ((mvX / mvLen) * ux + (mvY / mvLen) * uy) > 0.45F) {
                                continue;
                            }
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
                            if (d > danger[k]) danger[k] = d;
                        }
                    }
                }
            }
        }

        if (!hasNearbyAgent) {
            blockedSinceMs = 0L;
            latchedSlot = -1;
            return new SteerOutput(ux, uy, 1.0F, false);
        }

        long now = System.currentTimeMillis();
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

            if (danger[k] >= 0.88F) continue;

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
                return new SteerOutput(ux, uy, 0.0F, true);
            }
            return new SteerOutput(ux, uy, 0.32F, false);
        }

        blockedSinceMs = 0L;
        latchedSlot = bestSlot;
        latchUntilMs = now + 550L;
        return new SteerOutput(DIR_X[bestSlot], DIR_Y[bestSlot], 0.62F, false);
    }
}
