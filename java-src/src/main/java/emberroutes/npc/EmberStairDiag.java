package emberroutes.npc;

import java.util.List;
import java.util.Locale;
import zombie.debug.DebugLog;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;

/**
 * 纯只读观察型楼梯诊断记录器（零控制流干预、仅在楼梯附近出现异常事件时限频输出）：
 * 用于实机抓拍 10 人爬梯场景下的通道堵塞、航向反转、楼梯排队列与悬空层判定。
 */
final class EmberStairDiag {
    static final boolean ENABLED = false;

    private EmberStairDiag() {}

    static boolean isNearStairs(IsoNPC npc) {
        if (npc == null) {
            return false;
        }
        IsoGridSquare curSq = npc.getCurrentSquare();
        if (curSq == null) {
            return false;
        }
        return isStairOrExitTile(curSq.getX(), curSq.getY(), curSq.getZ());
    }

    static boolean isStairOrExitTile(int cx, int cy, int cz) {
        IsoGridSquare curSq = EmberTargetProjector.getSquare(cx, cy, cz);
        if (curSq != null && (curSq.HasStairs() || curSq.HasStairsBelow())) {
            return true;
        }
        return hasStairsAt(cx + 1, cy, cz) || hasStairsAt(cx - 1, cy, cz)
                || hasStairsAt(cx, cy + 1, cz) || hasStairsAt(cx, cy - 1, cz)
                || (cz > 0 && (hasStairsAt(cx, cy, cz - 1)
                || hasStairsAt(cx + 1, cy, cz - 1) || hasStairsAt(cx - 1, cy, cz - 1)
                || hasStairsAt(cx, cy + 1, cz - 1) || hasStairsAt(cx, cy - 1, cz - 1)));
    }

    static String fmtPosAndSq(IsoNPC npc) {
        if (npc == null) {
            return "npc=null";
        }
        int lvl = EmberTargetProjector.levelOf(npc.getZ());
        IsoGridSquare sq = npc.getCurrentSquare();
        if (sq == null) {
            return String.format(Locale.US, "pos=(%.2f,%.2f,%.3f) lvl=%d curSq=null",
                    npc.getX(), npc.getY(), npc.getZ(), lvl);
        }
        return String.format(Locale.US, "pos=(%.2f,%.2f,%.3f) lvl=%d curSq=(%d,%d,%d,st=%s,top=%s,below=%s)",
                npc.getX(), npc.getY(), npc.getZ(), lvl,
                sq.getX(), sq.getY(), sq.getZ(),
                sq.HasStairs() ? "1" : "0",
                sq.HasStairTop() ? "1" : "0",
                sq.HasStairsBelow() ? "1" : "0");
    }

    static String fmtNearNpcs(IsoNPC self, float radius) {
        if (self == null || self.getCurrentSquare() == null) {
            return "[]";
        }
        IsoGridSquare center = self.getCurrentSquare();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        float rSq = radius * radius;
        StringBuilder sb = new StringBuilder("[");
        int count = 0;

        for (int dz = -1; dz <= 1; dz++) {
            int z = cz + dz;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    IsoGridSquare sq = EmberTargetProjector.getSquare(cx + dx, cy + dy, z);
                    if (sq == null) continue;
                    List<IsoMovingObject> objs = sq.getMovingObjects();
                    for (int i = 0, sz = objs.size(); i < sz; i++) {
                        IsoMovingObject mo = objs.get(i);
                        if (mo == self || !(mo instanceof IsoNPC other) || other.isDead()) {
                            continue;
                        }
                        float rx = other.getX() - self.getX();
                        float ry = other.getY() - self.getY();
                        float dSq = rx * rx + ry * ry;
                        if (dSq > rSq) {
                            continue;
                        }
                        if (count > 0) sb.append(", ");
                        IsoGridSquare oSq = other.getCurrentSquare();
                        sb.append(String.format(Locale.US, "%s@%.2f(z=%.2f,st=%s,f=%s)",
                                other.getNpcId(),
                                (float) Math.sqrt(dSq),
                                other.getZ(),
                                (oSq != null && oSq.HasStairs()) ? "1" : "0",
                                other.getFollow().getState().name()));
                        count++;
                        if (count >= 6) {
                            sb.append(']');
                            return sb.toString();
                        }
                    }
                }
            }
        }
        sb.append(']');
        return sb.toString();
    }

    static void log(String tag, String body) {
        if (!ENABLED) {
            return;
        }
        DebugLog.log("[EmberRoutes][STAIR_DIAG][" + tag + "] " + body);
    }

    private static boolean hasStairsAt(int x, int y, int z) {
        IsoGridSquare sq = EmberTargetProjector.getSquare(x, y, z);
        return sq != null && sq.HasStairs();
    }
}
