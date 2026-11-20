package emberroutes.npc;

import org.joml.Vector2f;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.pathfind.PolygonalMap2;

/**
 * 连续空间起终点投影器（复用原版 PolygonalMap2.canStandAt 与 resolveCollision 几何原语）：
 * 1. 解决终点点击在半透明家具（solidTrans）、柜台边缘或贴墙 &lt; 0.30f 处导致 C++ PathfindNative 直接判 Failed 的问题；
 * 2. 解决 NPC 出生或被挤到半透明家具边缘/墙角时起点无法连边脱困的问题。
 */
public final class EmberTargetProjector {
    private static final float[] SEARCH_RADII = {0.18F, 0.34F, 0.52F, 0.72F, 0.95F, 1.20F};
    private static final int ANGLE_SAMPLES = 16;
    private static final double TWO_PI = Math.PI * 2.0;

    private EmberTargetProjector() {}

    public record ProjectedPoint(float x, float y, int z, boolean adjusted) {}

    public static int levelOf(float z) {
        return Float.isFinite(z) ? (int) Math.floor(z) : 0;
    }

    public static IsoGridSquare getSquare(int x, int y, int z) {
        IsoWorld w = IsoWorld.instance;
        if (w == null) return null;
        IsoCell c = w.currentCell != null ? w.currentCell : w.getCell();
        return c == null ? null : c.getGridSquare(x, y, z);
    }

    /** 将任意目标浮点坐标投影到最近的原版连续可站立点（保留合法亚格精度）。 */
    public static ProjectedPoint projectGoal(IsoPlayer actor, float rawX, float rawY, float rawZ) {
        if (!Float.isFinite(rawX) || !Float.isFinite(rawY)) return null;
        int z = levelOf(rawZ);
        IsoGridSquare sq = getSquare((int) Math.floor(rawX), (int) Math.floor(rawY), z);
        if (sq == null) return null;

        if (canStandClean(actor, rawX, rawY, z)) {
            return new ProjectedPoint(rawX, rawY, z, false);
        }

        if (sq.HasStairs()) {
            float cx = sq.getX() + 0.5F;
            float cy = sq.getY() + 0.5F;
            return new ProjectedPoint(cx, cy, z, true);
        }

        double baseAngle = 0.0;
        if (actor != null && levelOf(actor.getZ()) == z) {
            baseAngle = Math.atan2(actor.getY() - rawY, actor.getX() - rawX);
        }

        for (float r : SEARCH_RADII) {
            ProjectedPoint bestInRing = null;
            float bestScore = Float.MAX_VALUE;
            for (int i = 0; i < ANGLE_SAMPLES; i++) {
                double offset = ((i + 1) / 2) * (TWO_PI / ANGLE_SAMPLES) * ((i % 2 == 0) ? 1.0 : -1.0);
                double ang = baseAngle + offset;
                float cx = rawX + (float) Math.cos(ang) * r;
                float cy = rawY + (float) Math.sin(ang) * r;
                IsoGridSquare candSq = getSquare((int) Math.floor(cx), (int) Math.floor(cy), z);
                if (candSq == null || !candSq.TreatAsSolidFloor()) continue;
                if (candSq != sq && isWallBetween(sq, candSq)) continue;
                if (canStandClean(actor, cx, cy, z)) {
                    float dSq = (cx - rawX) * (cx - rawX) + (cy - rawY) * (cy - rawY);
                    if (dSq < bestScore) {
                        bestScore = dSq;
                        bestInRing = new ProjectedPoint(cx, cy, z, true);
                    }
                }
            }
            if (bestInRing != null) {
                return bestInRing;
            }
        }
        return null;
    }

    /**
     * 若角色当前脚下因贴墙过近或踩在半透明家具（solidTrans）上不被 C++ 寻路器接受，
     * 计算一个无穿墙的最近连续脱困点供 PathfindNative 作为合法起点。
     */
    public static ProjectedPoint projectStartIfTrapped(IsoPlayer actor) {
        if (actor == null) return null;
        float x = actor.getX();
        float y = actor.getY();
        int z = levelOf(actor.getZ());
        IsoGridSquare curSq = getSquare((int) Math.floor(x), (int) Math.floor(y), z);
        if (curSq == null) return null;
        if (curSq.HasStairs() || canStandClean(actor, x, y, z)) {
            return new ProjectedPoint(x, y, z, false);
        }

        if (PolygonalMap2.instance != null) {
            Vector2f resolved = new Vector2f();
            if (PolygonalMap2.instance.resolveCollision(actor, 0.31F, resolved)) {
                IsoGridSquare resSq = getSquare((int) Math.floor(resolved.x), (int) Math.floor(resolved.y), z);
                if (resSq != null && !isWallBetween(curSq, resSq) && canStandClean(actor, resolved.x, resolved.y, z)) {
                    return new ProjectedPoint(resolved.x, resolved.y, z, true);
                }
            }
        }

        for (float r : SEARCH_RADII) {
            for (int i = 0; i < ANGLE_SAMPLES; i++) {
                double ang = i * (TWO_PI / ANGLE_SAMPLES);
                float cx = x + (float) Math.cos(ang) * r;
                float cy = y + (float) Math.sin(ang) * r;
                IsoGridSquare candSq = getSquare((int) Math.floor(cx), (int) Math.floor(cy), z);
                if (candSq == null || !candSq.TreatAsSolidFloor()) continue;
                if (candSq != curSq && isWallBetween(curSq, candSq)) continue;
                if (canStandClean(actor, cx, cy, z)) {
                    return new ProjectedPoint(cx, cy, z, true);
                }
            }
        }
        return new ProjectedPoint(x, y, z, false);
    }

    public static boolean canStandClean(IsoPlayer actor, float x, float y, int z) {
        IsoGridSquare sq = getSquare((int) Math.floor(x), (int) Math.floor(y), z);
        if (sq == null || !sq.TreatAsSolidFloor() || sq.isSolid() || sq.isSolidTrans()) {
            return false;
        }
        if (PolygonalMap2.instance == null) {
            return true;
        }
        return PolygonalMap2.instance.canStandAt(x, y, z, actor, false, true);
    }

    private static boolean isWallBetween(IsoGridSquare a, IsoGridSquare b) {
        if (a == null || b == null || a.getZ() != b.getZ()) return true;
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1) return true;
        if (dx == 0 || dy == 0) {
            return a.isWallTo(b) || a.isWindowBlockedTo(b);
        }
        IsoGridSquare sx = getSquare(a.getX() + dx, a.getY(), a.getZ());
        IsoGridSquare sy = getSquare(a.getX(), a.getY() + dy, a.getZ());
        boolean viaX = sx != null && !a.isWallTo(sx) && !sx.isWallTo(b);
        boolean viaY = sy != null && !a.isWallTo(sy) && !sy.isWallTo(b);
        return !viaX && !viaY;
    }
}
