package emberroutes.npc;

import java.util.ArrayList;
import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.pathfind.Path;
import zombie.pathfind.PathNode;
import zombie.pathfind.PolygonalMap2;

/**
 * EMBER 走廊与门户半平面几何层（Portal Funnel + Half-Plane Crossing）：
 * 1. 将 C++ PathfindNative 产出的原始折线升级为带法向量的门户序列（Portal Entry 对准点 + Portal Exit 肩宽越界平面）；
 * 2. 对普通折点执行胶囊净空动态拉直，同时严格保留门户屏障（杜绝切内角撞门柱）；
 * 3. 所有门户与终点均采用单向半平面点积判据推进，从数学上彻底消灭 180° 回头折返（极限环振荡）。
 */
public final class EmberCorridor {
    /** 门前中轴线对准锚点距门槛平面的后退距离（单位：格）。 */
    private static final float PORTAL_ENTRY_OFFSET = 0.42F;
    /** 门后释放平面距门槛平面的前推距离（0.34 > 人物碰撞半径 0.30，确保肩膀完全出框后再拐大弯）。 */
    private static final float PORTAL_EXIT_CLEARANCE = 0.34F;

    public enum NodeKind {
        ESCAPE,
        WAYPOINT,
        PORTAL_ENTRY,
        PORTAL_EXIT,
        STAIR,
        GOAL
    }

    public static final class CorridorNode {
        public final float x;
        public final float y;
        public final int z;
        public final NodeKind kind;
        public final float normalX;
        public final float normalY;
        public final int fromTileX, fromTileY;
        public final int toTileX, toTileY;

        public CorridorNode(
                float x, float y, int z, NodeKind kind,
                float normalX, float normalY,
                int fromTileX, int fromTileY, int toTileX, int toTileY) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.kind = kind;
            this.normalX = normalX;
            this.normalY = normalY;
            this.fromTileX = fromTileX;
            this.fromTileY = fromTileY;
            this.toTileX = toTileX;
            this.toTileY = toTileY;
        }

        public boolean isBarrier() {
            return kind == NodeKind.PORTAL_ENTRY
                    || kind == NodeKind.PORTAL_EXIT
                    || kind == NodeKind.STAIR
                    || kind == NodeKind.GOAL;
        }
    }

    public record CarrotSample(
            float carrotX,
            float carrotY,
            float clampX,
            float clampY,
            int clampZ,
            boolean clampIsGoal,
            float distToGoal,
            float distToBarrier,
            float upcomingTurnRad,
            boolean filletArcClear,
            CorridorNode activePortal) {}

    private final List<CorridorNode> nodes;
    private int index;
    private float segStartX;
    private float segStartY;
    private int segStartZ;
    private int lastLevel = Integer.MIN_VALUE;

    private EmberCorridor(List<CorridorNode> nodes, float startX, float startY, int startZ) {
        this.nodes = nodes;
        this.index = 0;
        this.segStartX = startX;
        this.segStartY = startY;
        this.segStartZ = startZ;
    }

    /** 构造同层净空直达走廊（0 帧延迟快通）。 */
    public static EmberCorridor buildDirect(
            float startX, float startY, int startZ,
            EmberTargetProjector.ProjectedPoint escapeStart,
            float goalX, float goalY, int goalZ) {
        List<CorridorNode> list = new ArrayList<>(3);
        if (escapeStart != null && escapeStart.adjusted()) {
            list.add(simpleNode(escapeStart.x(), escapeStart.y(), escapeStart.z(), NodeKind.ESCAPE));
        }
        float dx = goalX - startX;
        float dy = goalY - startY;
        float len = (float) Math.hypot(dx, dy);
        float nx = len > 1.0E-4F ? dx / len : 1.0F;
        float ny = len > 1.0E-4F ? dy / len : 0.0F;
        list.add(new CorridorNode(goalX, goalY, goalZ, NodeKind.GOAL, nx, ny, 0, 0, 0, 0));
        return new EmberCorridor(list, startX, startY, startZ);
    }

    /** 从后台线程安全拷贝出的纯浮点坐标数组 [x0, y0, z0, ...] 编译门户走廊。 */
    public static EmberCorridor fromRawCoords(
            IsoPlayer actor,
            float[] rawXyz,
            EmberTargetProjector.ProjectedPoint escapeStart,
            float exactGoalX, float exactGoalY, int exactGoalZ) {
        if (rawXyz == null || rawXyz.length < 3) return null;
        Path scratch = new Path();
        for (int i = 0; i + 2 < rawXyz.length; i += 3) {
            scratch.addNode(rawXyz[i], rawXyz[i + 1], rawXyz[i + 2]);
        }
        return fromNativePath(actor, scratch, escapeStart, exactGoalX, exactGoalY, exactGoalZ);
    }

    /** 将原版 C++ PathfindNative 返回的节点序列编译为门户走廊。 */
    public static EmberCorridor fromNativePath(
            IsoPlayer actor,
            Path rawPath,
            EmberTargetProjector.ProjectedPoint escapeStart,
            float exactGoalX, float exactGoalY, int exactGoalZ) {
        if (rawPath == null || rawPath.isEmpty()) return null;
        int n = rawPath.size();
        List<CorridorNode> out = new ArrayList<>(n + 4);

        float actorX = actor != null ? actor.getX() : rawPath.getNode(0).x;
        float actorY = actor != null ? actor.getY() : rawPath.getNode(0).y;
        int actorZ = actor != null ? EmberTargetProjector.levelOf(actor.getZ()) : EmberTargetProjector.levelOf(rawPath.getNode(0).z);

        if (escapeStart != null && escapeStart.adjusted()) {
            out.add(simpleNode(escapeStart.x(), escapeStart.y(), escapeStart.z(), NodeKind.ESCAPE));
        }

        int i = 0;
        if (n > 1) {
            PathNode p0 = rawPath.getNode(0);
            int z0 = EmberTargetProjector.levelOf(p0.z);
            if (z0 == actorZ && Math.hypot(p0.x - actorX, p0.y - actorY) < 0.20) {
                i = 1;
            }
        }

        while (i < n) {
            PathNode cur = rawPath.getNode(i);
            int cz = EmberTargetProjector.levelOf(cur.z);
            IsoGridSquare sqCur = EmberTargetProjector.getSquare((int) Math.floor(cur.x), (int) Math.floor(cur.y), cz);

            if (i + 1 < n) {
                PathNode nxt = rawPath.getNode(i + 1);
                int nz = EmberTargetProjector.levelOf(nxt.z);
                IsoGridSquare sqNxt = EmberTargetProjector.getSquare((int) Math.floor(nxt.x), (int) Math.floor(nxt.y), nz);

                if (cz == nz && sqCur != null && sqNxt != null && isPortalEdge(sqCur, sqNxt)) {
                    int dx = sqNxt.getX() - sqCur.getX();
                    int dy = sqNxt.getY() - sqCur.getY();
                    float midX = (sqCur.getX() + sqNxt.getX() + 1) * 0.5F;
                    float midY = (sqCur.getY() + sqNxt.getY() + 1) * 0.5F;
                    float entryX = midX - dx * PORTAL_ENTRY_OFFSET;
                    float entryY = midY - dy * PORTAL_ENTRY_OFFSET;
                    float exitX = midX + dx * PORTAL_EXIT_CLEARANCE;
                    float exitY = midY + dy * PORTAL_EXIT_CLEARANCE;

                    out.add(new CorridorNode(
                            entryX, entryY, cz, NodeKind.PORTAL_ENTRY, dx, dy,
                            sqCur.getX(), sqCur.getY(), sqNxt.getX(), sqNxt.getY()));
                    out.add(new CorridorNode(
                            exitX, exitY, cz, NodeKind.PORTAL_EXIT, dx, dy,
                            sqCur.getX(), sqCur.getY(), sqNxt.getX(), sqNxt.getY()));

                    if (i + 2 < n) {
                        PathNode after = rawPath.getNode(i + 2);
                        int az = EmberTargetProjector.levelOf(after.z);
                        IsoGridSquare sqAfter = EmberTargetProjector.getSquare((int) Math.floor(after.x), (int) Math.floor(after.y), az);
                        boolean chainPortal = (az != nz) || (sqAfter != null && (sqNxt.HasStairs() || sqAfter.HasStairs() || isPortalEdge(sqNxt, sqAfter)));
                        if (!chainPortal) {
                            i += 2;
                            continue;
                        }
                    } else {
                        appendGoal(out, exactGoalX, exactGoalY, exactGoalZ, exitX, exitY);
                        break;
                    }
                    i += 2;
                    continue;
                }

                if (cz != nz || (sqCur != null && sqCur.HasStairs())) {
                    out.add(simpleNode(cur.x, cur.y, cz, NodeKind.STAIR));
                    i++;
                    continue;
                }
            }

            if (i == n - 1) {
                float refX = out.isEmpty() ? actorX : out.get(out.size() - 1).x;
                float refY = out.isEmpty() ? actorY : out.get(out.size() - 1).y;
                appendGoal(out, exactGoalX, exactGoalY, exactGoalZ, refX, refY);
            } else {
                float dx = rawPath.getNode(i + 1).x - cur.x;
                float dy = rawPath.getNode(i + 1).y - cur.y;
                float len = (float) Math.hypot(dx, dy);
                float nx = len > 1.0E-4F ? dx / len : 0.0F;
                float ny = len > 1.0E-4F ? dy / len : 0.0F;
                NodeKind kind = (sqCur != null && sqCur.HasStairs()) ? NodeKind.STAIR : NodeKind.WAYPOINT;
                out.add(new CorridorNode(cur.x, cur.y, cz, kind, nx, ny, 0, 0, 0, 0));
            }
            i++;
        }

        if (out.isEmpty()) {
            appendGoal(out, exactGoalX, exactGoalY, exactGoalZ, actorX, actorY);
        }
        return new EmberCorridor(out, actorX, actorY, actorZ);
    }

    public int getIndex() {
        return index;
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public boolean isComplete() {
        return index >= nodes.size();
    }

    /** 推进已跨越的走廊节点/半平面，并评估是否已满足终点到达（含点积越界保护）。 */
    public boolean advanceAndCheckGoal(IsoPlayer actor) {
        if (actor == null || isComplete()) return true;
        float x = actor.getX();
        float y = actor.getY();
        int z = EmberTargetProjector.levelOf(actor.getZ());

        if (lastLevel != Integer.MIN_VALUE && lastLevel != z && nodes.get(index).z != z) {
            for (int k = index + 1; k < nodes.size(); k++) {
                if (nodes.get(k).z == z) {
                    setIndexAndAnchor(k, x, y, z);
                    break;
                }
            }
        }
        lastLevel = z;

        while (index < nodes.size()) {
            CorridorNode cur = nodes.get(index);
            if (cur.kind == NodeKind.GOAL) {
                if (z != cur.z) return false;
                float dx = cur.x - x;
                float dy = cur.y - y;
                float dist = (float) Math.hypot(dx, dy);
                float rIn = EmberParams.ARRIVE_RADIUS;
                if (dist <= rIn) {
                    index = nodes.size();
                    return true;
                }
                float segDx = cur.x - segStartX;
                float segDy = cur.y - segStartY;
                float segLen = (float) Math.hypot(segDx, segDy);
                if (segStartZ == z && segLen > 0.08F) {
                    float ux = segDx / segLen;
                    float uy = segDy / segLen;
                    float remAlong = dx * ux + dy * uy;
                    float latErr = Math.abs(dx * uy - dy * ux);
                    if (remAlong <= 0.0F && dist <= Math.max(0.75F, rIn * 2.5F) && latErr <= 0.65F) {
                        index = nodes.size();
                        return true;
                    }
                }
                return false;
            }

            if (cur.z != z) {
                return false;
            }

            if (cur.kind == NodeKind.WAYPOINT && index + 1 < nodes.size()) {
                CorridorNode nxt = nodes.get(index + 1);
                IsoGridSquare curSq = actor.getCurrentSquare();
                if (nxt.z == z && (curSq == null || !curSq.HasStairs()) && isCapsuleLineClear(actor, x, y, nxt.x, nxt.y, z)) {
                    advanceOne(cur);
                    continue;
                }
            }

            if (hasPassedIntermediate(x, y, cur)) {
                advanceOne(cur);
            } else {
                break;
            }
        }
        return isComplete();
    }

    /** 按当前速度缩放的前瞻距离 L(v)，沿走廊折线提取连续胡萝卜引导点（遇到门户屏障自动截断防切门框）。 */
    public CarrotSample sampleCarrot(IsoPlayer actor, float lookaheadDist, float turnRadius) {
        float x = actor.getX();
        float y = actor.getY();
        int z = EmberTargetProjector.levelOf(actor.getZ());
        if (isComplete()) {
            return new CarrotSample(x, y, x, y, z, true, 0.0F, 0.0F, 0.0F, false, null);
        }

        CorridorNode first = nodes.get(index);
        float carrotX = first.x;
        float carrotY = first.y;
        boolean filletArcClear = false;
        boolean usedFillet = false;

        if (first.kind == NodeKind.WAYPOINT && index + 1 < nodes.size()) {
            CorridorNode nxt = nodes.get(index + 1);
            if (nxt.z == z) {
                float dx1 = first.x - x;
                float dy1 = first.y - y;
                float distToCorner = (float) Math.hypot(dx1, dy1);
                float dx2 = nxt.x - first.x;
                float dy2 = nxt.y - first.y;
                float seg2Len = (float) Math.hypot(dx2, dy2);

                if (distToCorner > 0.05F && seg2Len > 0.10F) {
                    float u1x = dx1 / distToCorner;
                    float u1y = dy1 / distToCorner;
                    float u2x = dx2 / seg2Len;
                    float u2y = dy2 / seg2Len;
                    float dot = Math.max(-1.0F, Math.min(1.0F, u1x * u2x + u1y * u2y));
                    float turnRad = (float) Math.acos(dot);

                    if (turnRad > 0.25F) {
                        float rawLead = turnRadius * (float) Math.tan(Math.min(turnRad, 2.20F) * 0.5F);
                        float dLead = Math.max(0.30F, Math.min(1.80F, rawLead));
                        float dExit = Math.min(dLead, seg2Len * 0.85F);
                        float exitX = first.x + u2x * dExit;
                        float exitY = first.y + u2y * dExit;

                        if (isCapsuleLineClear(actor, x, y, exitX, exitY, z)) {
                            filletArcClear = true;
                            if (distToCorner <= dLead) {
                                float blend = Math.max(0.20F, Math.min(1.0F, 1.0F - (distToCorner / dLead) * 0.65F));
                                float tx = first.x + (exitX - first.x) * blend;
                                float ty = first.y + (exitY - first.y) * blend;
                                float tdx = tx - x;
                                float tdy = ty - y;
                                float tLen = (float) Math.hypot(tdx, tdy);
                                if (tLen > 0.05F) {
                                    carrotX = x + (tdx / tLen) * Math.max(lookaheadDist, tLen);
                                    carrotY = y + (tdy / tLen) * Math.max(lookaheadDist, tLen);
                                    usedFillet = true;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!usedFillet) {
            float remLook = Math.max(0.12F, lookaheadDist);
            float px = x;
            float py = y;

            for (int k = index; k < nodes.size(); k++) {
                CorridorNode nd = nodes.get(k);
                if (nd.z != z && k > index) break;
                float segDx = nd.x - px;
                float segDy = nd.y - py;
                float segLen = (float) Math.hypot(segDx, segDy);
                if (segLen >= remLook && segLen > 1.0E-4F) {
                    float t = remLook / segLen;
                    carrotX = px + segDx * t;
                    carrotY = py + segDy * t;
                    break;
                }
                carrotX = nd.x;
                carrotY = nd.y;
                remLook -= segLen;
                px = nd.x;
                py = nd.y;
                if (nd.isBarrier()) {
                    break;
                }
            }
        }

        float[] aligned = alignStairTarget(x, y, z, carrotX, carrotY);
        carrotX = aligned[0];
        carrotY = aligned[1];

        float totalGoalDist = 0.0F;
        float barrierDist = Float.POSITIVE_INFINITY;
        float upcomingTurnRad = 0.0F;
        CorridorNode portalAhead = null;
        float sx = x, sy = y;
        for (int k = index; k < nodes.size(); k++) {
            CorridorNode nd = nodes.get(k);
            float d = (float) Math.hypot(nd.x - sx, nd.y - sy);
            totalGoalDist += d;
            if (portalAhead == null && (nd.kind == NodeKind.PORTAL_ENTRY || nd.kind == NodeKind.PORTAL_EXIT) && totalGoalDist <= 1.65F) {
                portalAhead = nd;
            }
            if (barrierDist == Float.POSITIVE_INFINITY && nd.isBarrier() && nd.kind != NodeKind.GOAL) {
                barrierDist = totalGoalDist;
            }
            if (k == index && k + 1 < nodes.size() && d > 0.05F) {
                CorridorNode nxt = nodes.get(k + 1);
                float d2 = (float) Math.hypot(nxt.x - nd.x, nxt.y - nd.y);
                if (d2 > 0.05F) {
                    float dot = ((nd.x - sx) * (nxt.x - nd.x) + (nd.y - sy) * (nxt.y - nd.y)) / (d * d2);
                    dot = Math.max(-1.0F, Math.min(1.0F, dot));
                    upcomingTurnRad = (float) Math.acos(dot);
                    if (upcomingTurnRad > 0.65F && barrierDist == Float.POSITIVE_INFINITY) {
                        barrierDist = d;
                    }
                }
            }
            sx = nd.x;
            sy = nd.y;
        }

        boolean clampGoal = (first.kind == NodeKind.GOAL);
        return new CarrotSample(
                carrotX, carrotY,
                first.x, first.y, first.z, clampGoal,
                totalGoalDist, barrierDist, upcomingTurnRad, filletArcClear, portalAhead);
    }

    private boolean hasPassedIntermediate(float x, float y, CorridorNode node) {
        float dx = x - node.x;
        float dy = y - node.y;
        float dist = (float) Math.hypot(dx, dy);

        return switch (node.kind) {
            case ESCAPE -> dist <= 0.18F;
            case PORTAL_ENTRY -> {
                float fwd = dx * node.normalX + dy * node.normalY;
                float lat = Math.abs(dx * node.normalY - dy * node.normalX);
                yield dist <= 0.20F || (fwd >= -0.04F && lat <= 0.24F);
            }
            case PORTAL_EXIT -> {
                float fwd = dx * node.normalX + dy * node.normalY;
                float lat = Math.abs(dx * node.normalY - dy * node.normalX);
                yield dist <= 0.20F || (fwd >= 0.0F && lat <= 0.60F);
            }
            case STAIR -> dist <= 0.28F;
            case WAYPOINT -> {
                if (dist <= 0.30F) yield true;
                float segDx = node.x - segStartX;
                float segDy = node.y - segStartY;
                float segLen = (float) Math.hypot(segDx, segDy);
                if (segLen > 0.10F) {
                    float passed = (dx * segDx + dy * segDy) / segLen;
                    float lat = Math.abs(dx * segDy - dy * segDx) / segLen;
                    yield passed >= 0.0F && lat <= 0.55F;
                }
                yield false;
            }
            case GOAL -> false;
        };
    }

    private void advanceOne(CorridorNode passed) {
        segStartX = passed.x;
        segStartY = passed.y;
        segStartZ = passed.z;
        index++;
    }

    private void setIndexAndAnchor(int newIdx, float x, float y, int z) {
        this.index = newIdx;
        this.segStartX = x;
        this.segStartY = y;
        this.segStartZ = z;
    }

    private static boolean isCapsuleLineClear(IsoPlayer actor, float x1, float y1, float x2, float y2, int z) {
        if (PolygonalMap2.instance == null) return false;
        return !PolygonalMap2.instance.lineClearCollide(x1, y1, x2, y2, z, actor, false, true);
    }

    private static float[] alignStairTarget(float x, float y, int z, float tx, float ty) {
        IsoGridSquare sq = EmberTargetProjector.getSquare((int) Math.floor(x), (int) Math.floor(y), z);
        if (sq == null || !sq.HasStairs()) return new float[]{tx, ty};
        if (sq.HasStairsNorth()) {
            float centerX = sq.getX() + 0.5F;
            return new float[]{centerX, ty};
        }
        if (sq.HasStairsWest()) {
            float centerY = sq.getY() + 0.5F;
            return new float[]{tx, centerY};
        }
        return new float[]{tx, ty};
    }

    public static boolean isPortalEdge(IsoGridSquare a, IsoGridSquare b) {
        if (a == null || b == null || a.getZ() != b.getZ()) return false;
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        if (Math.abs(dx) + Math.abs(dy) != 1) return false;

        IsoObject door = a.getDoorTo(b);
        if (door instanceof IsoDoor || (door instanceof IsoThumpable th && th.isDoor())) return true;
        if (dy != 0 && a.getGarageDoor(true) != null) return true;
        if (dx != 0 && a.getGarageDoor(false) != null) return true;

        IsoWindow win = a.getWindowTo(b);
        if (win != null || a.getWindowFrameTo(b) != null) return true;
        IsoThumpable winTh = a.getWindowThumpableTo(b);
        if (winTh != null && winTh.isWindow()) return true;

        if (dy != 0) {
            IsoGridSquare edge = dy > 0 ? b : a;
            return edge.getProperties().has(IsoFlagType.HoppableN)
                    || edge.getProperties().has(IsoFlagType.TallHoppableN);
        }
        IsoGridSquare edge = dx > 0 ? b : a;
        return edge.getProperties().has(IsoFlagType.HoppableW)
                || edge.getProperties().has(IsoFlagType.TallHoppableW);
    }

    private static CorridorNode simpleNode(float x, float y, int z, NodeKind kind) {
        return new CorridorNode(x, y, z, kind, 0.0F, 0.0F, 0, 0, 0, 0);
    }

    private static void appendGoal(
            List<CorridorNode> out, float gx, float gy, int gz, float refX, float refY) {
        float dx = gx - refX;
        float dy = gy - refY;
        float len = (float) Math.hypot(dx, dy);
        float nx = len > 1.0E-4F ? dx / len : 1.0F;
        float ny = len > 1.0E-4F ? dy / len : 0.0F;
        out.add(new CorridorNode(gx, gy, gz, NodeKind.GOAL, nx, ny, 0, 0, 0, 0));
    }
}
