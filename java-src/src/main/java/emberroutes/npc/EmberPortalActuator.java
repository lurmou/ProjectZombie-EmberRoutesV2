package emberroutes.npc;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import zombie.ai.State;
import zombie.ai.states.ClimbOverFenceState;
import zombie.ai.states.ClimbOverWallState;
import zombie.ai.states.ClimbThroughWindowState;
import zombie.ai.states.IdleState;
import zombie.ai.states.OpenWindowState;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;

/**
 * EMBER 门窗栅栏前馈执行器与自动关门纪律：
 * 1. 前馈推门（1.15 格提前量）：NPC 在接近门户对准点前即顺手推开未锁房门，不断步、不停顿；
 * 2. 原生翻窗 / 翻矮栏状态机交接（先转向正对障碍再触发动作，4 秒开窗超时保护防死锁）；
 * 3. 安全屋关门礼仪：穿过自己推开的门 &gt; 1.12 格且确认门洞两侧无其他角色时，自动随手带上门。
 */
public final class EmberPortalActuator {
    private static final float DOOR_OPEN_ADVANCE_DIST = 1.15F;
    private static final float INTERACT_TRIGGER_DIST = 0.85F;
    private static final float AUTO_CLOSE_MIN_DIST = 1.12F;
    private static final float AUTO_CLOSE_MAX_DIST = 2.35F;
    private static final long WINDOW_TIMEOUT_MS = 4000L;

    public enum ActStatus {
        CLEAR,
        TRAVERSING_NATIVE,
        BLOCKED
    }

    private record OpenedDoorEntry(IsoObject door, int ax, int ay, int bx, int by, int z, long openedMs) {}

    private final Deque<OpenedDoorEntry> openedDoors = new ArrayDeque<>(4);
    private IsoWindow pendingWindow;
    private long windowStartMs;

    public void reset() {
        openedDoors.clear();
        pendingWindow = null;
        windowStartMs = 0L;
    }

    public static boolean isInNativeTraversal(IsoPlayer actor) {
        if (actor == null) return false;
        State st = actor.getCurrentState();
        return st == ClimbOverFenceState.instance()
                || st == ClimbThroughWindowState.instance()
                || st == ClimbOverWallState.instance()
                || st == OpenWindowState.instance();
    }

    public ActStatus update(
            IsoNPC npc,
            EmberCorridor.CorridorNode activePortal,
            float steerX, float steerY) {
        if (npc == null) return ActStatus.CLEAR;

        State st = npc.getCurrentState();
        if (st == OpenWindowState.instance()) {
            return monitorWindowOpening(npc);
        }
        if (st == ClimbOverFenceState.instance()
                || st == ClimbThroughWindowState.instance()
                || st == ClimbOverWallState.instance()) {
            return ActStatus.TRAVERSING_NATIVE;
        }

        tickAutoCloseDoors(npc);

        IsoGridSquare curSq = npc.getCurrentSquare();
        if (curSq == null || curSq.HasStairs()) return ActStatus.CLEAR;

        IsoGridSquare fromSq = null;
        IsoGridSquare toSq = null;
        int z = curSq.getZ();

        if (activePortal != null && activePortal.z == z) {
            fromSq = EmberTargetProjector.getSquare(activePortal.fromTileX, activePortal.fromTileY, z);
            toSq = EmberTargetProjector.getSquare(activePortal.toTileX, activePortal.toTileY, z);
        }
        if (fromSq == null || toSq == null) {
            int tx = (int) Math.floor(npc.getX() + steerX * 0.75F);
            int ty = (int) Math.floor(npc.getY() + steerY * 0.75F);
            if (Math.abs(tx - curSq.getX()) + Math.abs(ty - curSq.getY()) == 1) {
                fromSq = curSq;
                toSq = EmberTargetProjector.getSquare(tx, ty, z);
            }
        }
        if (fromSq == null || toSq == null) return ActStatus.CLEAR;

        int dx = toSq.getX() - fromSq.getX();
        int dy = toSq.getY() - fromSq.getY();
        if (Math.abs(dx) + Math.abs(dy) != 1) return ActStatus.CLEAR;

        float edgeMidX = (fromSq.getX() + toSq.getX() + 1) * 0.5F;
        float edgeMidY = (fromSq.getY() + toSq.getY() + 1) * 0.5F;
        float distToEdge = (float) Math.hypot(npc.getX() - edgeMidX, npc.getY() - edgeMidY);

        IsoObject doorObj = fromSq.getDoorTo(toSq);
        if (doorObj instanceof IsoDoor door) {
            if (distToEdge > DOOR_OPEN_ADVANCE_DIST) return ActStatus.CLEAR;
            if (door.IsOpen()) return ActStatus.CLEAR;
            if (door.isBarricaded() || door.isObstructed() || !door.couldBeOpen(npc)) {
                return ActStatus.BLOCKED;
            }
            door.ToggleDoor(npc);
            if (door.IsOpen()) {
                trackOpenedDoor(door, fromSq, toSq);
                return ActStatus.CLEAR;
            }
            return ActStatus.BLOCKED;
        }
        if (doorObj instanceof IsoThumpable thump && thump.isDoor()) {
            if (distToEdge > DOOR_OPEN_ADVANCE_DIST) return ActStatus.CLEAR;
            if (thump.IsOpen()) return ActStatus.CLEAR;
            if (thump.isBarricaded() || thump.isObstructed() || !thump.couldBeOpen(npc)) {
                return ActStatus.BLOCKED;
            }
            thump.ToggleDoor(npc);
            if (thump.IsOpen()) {
                trackOpenedDoor(thump, fromSq, toSq);
                return ActStatus.CLEAR;
            }
            return ActStatus.BLOCKED;
        }

        IsoObject garage = dy != 0 ? fromSq.getGarageDoor(true) : fromSq.getGarageDoor(false);
        if (garage instanceof IsoDoor gd && distToEdge <= DOOR_OPEN_ADVANCE_DIST && !gd.IsOpen()) {
            if (gd.isBarricaded() || gd.isObstructed() || !gd.couldBeOpen(npc)) return ActStatus.BLOCKED;
            IsoDoor.toggleGarageDoor(gd, true);
            return gd.IsOpen() ? ActStatus.CLEAR : ActStatus.BLOCKED;
        }

        if (curSq != fromSq || distToEdge > INTERACT_TRIGGER_DIST) {
            return ActStatus.CLEAR;
        }

        IsoWindow win = fromSq.getWindowTo(toSq);
        if (win != null) {
            if (win.isBarricaded() || (win.isSmashed() && !win.isGlassRemoved())) {
                return ActStatus.BLOCKED;
            }
            if (win.IsOpen() || win.isDestroyed()) {
                npc.climbThroughWindow(win);
                return ActStatus.TRAVERSING_NATIVE;
            }
            if (!win.isLocked() && !win.isPermaLocked()) {
                pendingWindow = win;
                windowStartMs = System.currentTimeMillis();
                npc.openWindow(win);
                return ActStatus.TRAVERSING_NATIVE;
            }
            return ActStatus.BLOCKED;
        }
        IsoWindowFrame frame = fromSq.getWindowFrameTo(toSq);
        if (frame != null && frame.canClimbThrough(npc)) {
            npc.climbThroughWindowFrame(frame);
            return ActStatus.TRAVERSING_NATIVE;
        }

        IsoGridSquare edgeSq = (dy > 0 || dx > 0) ? toSq : fromSq;
        boolean hopLow = (dy != 0 && edgeSq.getProperties().has(IsoFlagType.HoppableN))
                || (dx != 0 && edgeSq.getProperties().has(IsoFlagType.HoppableW));
        boolean hopTall = (dy != 0 && edgeSq.getProperties().has(IsoFlagType.TallHoppableN))
                || (dx != 0 && edgeSq.getProperties().has(IsoFlagType.TallHoppableW));

        if (hopLow || hopTall) {
            IsoDirections card = dy < 0 ? IsoDirections.N : (dy > 0 ? IsoDirections.S : (dx < 0 ? IsoDirections.W : IsoDirections.E));
            npc.faceDirection(card);
            if (npc.shouldBeTurning()) {
                return ActStatus.TRAVERSING_NATIVE;
            }
            if (hopLow) {
                npc.climbOverFence(card);
                return ActStatus.TRAVERSING_NATIVE;
            }
            if (npc.climbOverWall(card)) {
                return ActStatus.TRAVERSING_NATIVE;
            }
            return ActStatus.BLOCKED;
        }

        return ActStatus.CLEAR;
    }

    private ActStatus monitorWindowOpening(IsoNPC npc) {
        long now = System.currentTimeMillis();
        if (pendingWindow != null && pendingWindow.IsOpen()) {
            pendingWindow = null;
            return ActStatus.CLEAR;
        }
        String stopVar = npc.getVariableString("StopAfterAnimLooped");
        if ("success".equalsIgnoreCase(stopVar) && pendingWindow != null) {
            try { pendingWindow.ToggleWindow(npc); } catch (Throwable ignored) {}
            boolean ok = pendingWindow.IsOpen();
            abortWindowState(npc);
            return ok ? ActStatus.CLEAR : ActStatus.BLOCKED;
        }
        if (windowStartMs > 0L && now - windowStartMs > WINDOW_TIMEOUT_MS) {
            abortWindowState(npc);
            return ActStatus.BLOCKED;
        }
        return ActStatus.TRAVERSING_NATIVE;
    }

    private void abortWindowState(IsoNPC npc) {
        pendingWindow = null;
        windowStartMs = 0L;
        try {
            npc.setVariable("bOpenWindow", false);
            npc.clearVariable("StopAfterAnimLooped");
            if (npc.getCurrentState() == OpenWindowState.instance()) {
                npc.changeState(IdleState.instance());
            }
        } catch (Throwable ignored) {}
    }

    private void trackOpenedDoor(IsoObject door, IsoGridSquare a, IsoGridSquare b) {
        if (openedDoors.size() >= 4) openedDoors.pollFirst();
        openedDoors.addLast(new OpenedDoorEntry(
                door, a.getX(), a.getY(), b.getX(), b.getY(), a.getZ(), System.currentTimeMillis()));
    }

    private void tickAutoCloseDoors(IsoNPC npc) {
        if (openedDoors.isEmpty()) return;
        long now = System.currentTimeMillis();
        int curZ = EmberTargetProjector.levelOf(npc.getZ());
        Iterator<OpenedDoorEntry> it = openedDoors.iterator();
        while (it.hasNext()) {
            OpenedDoorEntry e = it.next();
            if (now - e.openedMs > 8000L || curZ != e.z) {
                it.remove();
                continue;
            }
            boolean isOpen = (e.door instanceof IsoDoor d && d.IsOpen())
                    || (e.door instanceof IsoThumpable t && t.isDoor() && t.IsOpen());
            if (!isOpen) {
                it.remove();
                continue;
            }
            float mx = (e.ax + e.bx + 1) * 0.5F;
            float my = (e.ay + e.by + 1) * 0.5F;
            float dist = (float) Math.hypot(npc.getX() - mx, npc.getY() - my);
            if (dist > AUTO_CLOSE_MAX_DIST) {
                it.remove();
                continue;
            }
            if (dist >= AUTO_CLOSE_MIN_DIST && !isDoorwayOccupied(npc, e)) {
                if (e.door instanceof IsoDoor d) d.ToggleDoor(npc);
                else if (e.door instanceof IsoThumpable t) t.ToggleDoor(npc);
                it.remove();
            }
        }
    }

    private static boolean isDoorwayOccupied(IsoPlayer self, OpenedDoorEntry e) {
        return hasOtherCharacter(self, EmberTargetProjector.getSquare(e.ax, e.ay, e.z))
                || hasOtherCharacter(self, EmberTargetProjector.getSquare(e.bx, e.by, e.z));
    }

    private static boolean hasOtherCharacter(IsoPlayer self, IsoGridSquare sq) {
        if (sq == null) return false;
        List<IsoMovingObject> list = sq.getMovingObjects();
        for (int i = 0, sz = list.size(); i < sz; i++) {
            IsoMovingObject mo = list.get(i);
            if (mo != self && mo instanceof IsoGameCharacter ch && !ch.isDead()) {
                return true;
            }
        }
        return false;
    }
}
