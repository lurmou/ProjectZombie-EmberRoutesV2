package emberroutes.npc;

import zombie.GameTime;
import zombie.ai.AIBrainPlayerControlVars;
import zombie.ai.astar.Mover;
import zombie.characters.component.AIComponent;
import zombie.iso.IsoGridSquare;
import zombie.pathfind.IPathfinder;
import zombie.pathfind.Path;
import zombie.pathfind.PathFindBehavior2;
import zombie.pathfind.PathNode;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.nativeCode.PathfindNative;

/**
 * EMBER 机器人级运动控制器（ROS 2 Nav2 Regulated Pure Pursuit + B42 双混合树无级变速 CVT）：
 * - 全局规划：复用原版 C++ PathfindNative 多线程分层可见性图 + 连续起终点投影；
 * - 几何走廊：门户半平面单向越界推进 + 胶囊净空动态拉直（0 直角死折、0 回头折返）；
 * - 速度控制：速度自适应前瞻 L(v) + 曲率/门柱近距/平方根终点刹车三重无级油门（WalkSpeed CVT）；
 * - 停靠防抖：双阈值滞回死区（R_in / R_out）+ 终点有界点积越界锁死 + 根位移精确钳制。
 */
public final class EmberLocomotionController {
    public enum Status {
        IDLE,
        MOVING,
        ARRIVED,
        NO_PATH,
        STUCK,
        INTERRUPTED
    }

    private static final float STUCK_CHECK_WINDOW_SEC = 0.55F;
    private static final float STUCK_MIN_DISP = 0.14F;
    private static final int MAX_REPATH_RETRIES = 3;

    private final Object asyncLock = new Object();
    private long requestGen;
    private IsoNPC pendingNpc;
    private float[] readyRawXyz;
    private boolean asyncFailed;

    private final EmberContextSteering contextSteering = new EmberContextSteering();
    private final EmberPortalActuator portalActuator = new EmberPortalActuator();

    private boolean active;
    private boolean arrivedLatched;
    private long arrivedAtMs;
    private float goalX;
    private float goalY;
    private int goalZ;
    private EmberTargetProjector.ProjectedPoint pendingEscapeStart;
    private EmberCorridor corridor;

    private float smoothedThrottle;
    private boolean inRunGear;
    private boolean turn180Latched;
    private float turn180TargetDeg;
    private String liveReasonTag = "IDLE";
    private float liveAnimScalar = 1.0F;
    private float liveTurnGain = EmberParams.TURN_GAIN;
    private float stuckTimerSec;
    private float stuckAnchorX;
    private float stuckAnchorY;
    private int repathStreak;

    private Status status = Status.IDLE;
    private String lastReason = "IDLE";
    private long moveSeq;
    private long settledSeq;

    public synchronized long getMoveSeq() {
        return moveSeq;
    }

    public synchronized long getSettledSeq() {
        return settledSeq;
    }

    public synchronized Status getStatus() {
        return status;
    }

    public synchronized String getLastReason() {
        return lastReason;
    }

    public boolean isMoving() {
        return active && !arrivedLatched;
    }

    public long moveTo(IsoNPC npc, double rawX, double rawY, double rawZ, boolean running) {
        if (npc == null || npc.isDead()) {
            return -1L;
        }

        final long seq;
        synchronized (this) {
            seq = ++moveSeq;
        }

        npc.setRunningMode(running);
        EmberTargetProjector.ProjectedPoint goalProj =
                EmberTargetProjector.projectGoal(npc, (float) rawX, (float) rawY, (float) rawZ);
        if (goalProj == null) {
            failTrip(npc, Status.NO_PATH, "INVALID_TARGET");
            return seq;
        }

        npc.setEmberControlActive(true);
        npc.setNativeSmoothPathUnlock(true);
        cancelVanillaPathBehavior(npc);
        contextSteering.reset();

        this.goalX = goalProj.x();
        this.goalY = goalProj.y();
        this.goalZ = goalProj.z();
        this.active = true;
        this.arrivedLatched = false;
        this.repathStreak = 0;
        synchronized (this) {
            this.status = Status.MOVING;
            this.lastReason = "MOVING";
        }
        resetStuckMonitor(npc);

        int curZ = EmberTargetProjector.levelOf(npc.getZ());
        float distToGoal = (float) Math.hypot(goalX - npc.getX(), goalY - npc.getY());
        if (curZ == goalZ && distToGoal <= EmberParams.ARRIVE_RADIUS) {
            latchArrived(npc);
            return seq;
        }

        EmberTargetProjector.ProjectedPoint startProj = EmberTargetProjector.projectStartIfTrapped(npc);
        this.pendingEscapeStart = startProj;

        if (curZ == goalZ
                && (startProj == null || !startProj.adjusted())
                && PolygonalMap2.instance != null
                && !PolygonalMap2.instance.lineClearCollide(npc.getX(), npc.getY(), goalX, goalY, curZ, npc, false, true)) {
            cancelAsyncRequest(npc);
            this.corridor = EmberCorridor.buildDirect(npc.getX(), npc.getY(), curZ, null, goalX, goalY, goalZ);
            return seq;
        }

        submitAsyncPathRequest(
                npc,
                startProj != null ? startProj : new EmberTargetProjector.ProjectedPoint(npc.getX(), npc.getY(), curZ, false));
        return seq;
    }

    public void beforeUpdate(IsoNPC npc) {
        if (npc == null) return;
        npc.setEmberControlActive(true);
        npc.setNativeSmoothPathUnlock(true);

        pollAsyncPathResult(npc);

        if (arrivedLatched) {
            int curZ = EmberTargetProjector.levelOf(npc.getZ());
            long sinceArrived = System.currentTimeMillis() - arrivedAtMs;
            float rOut = Math.max(1.45F, EmberParams.ARRIVE_RADIUS * 4.0F);
            if (sinceArrived < 1200L || (curZ == goalZ && Math.hypot(goalX - npc.getX(), goalY - npc.getY()) <= rOut)) {
                applyIdleHold(npc, true);
                portalActuator.update(npc, null, 0.0F, 0.0F);
                return;
            }
            arrivedLatched = false;
            active = true;
            synchronized (this) {
                status = Status.MOVING;
                lastReason = "REACQUIRE_GOAL";
            }
            submitAsyncPathRequest(npc, EmberTargetProjector.projectStartIfTrapped(npc));
        }

        if (!active) {
            portalActuator.update(npc, null, 0.0F, 0.0F);
            return;
        }

        if (corridor == null) {
            return;
        }

        if (EmberPortalActuator.isInNativeTraversal(npc)) {
            turn180Latched = false;
            npc.clearManualMovementClamp();
            npc.setEmberDesiredRunning(false);
            npc.setEmberMotionState(false, 1.0F, EmberParams.TURN_GAIN);
            resetStuckMonitor(npc);
            portalActuator.update(npc, null, 0.0F, 0.0F);
            return;
        }

        if (corridor.advanceAndCheckGoal(npc)) {
            latchArrived(npc);
            return;
        }

        float dt = deltaSeconds();

        boolean wantRun = npc.isRunningMode() && !npc.isDraggingCorpse();
        float lMin = EmberParams.LOOKAHEAD_MIN;
        float lMax = Math.max(lMin, EmberParams.LOOKAHEAD_MAX);
        float lookDist = lMin + (lMax - lMin) * Math.max(0.0F, Math.min(1.0F, smoothedThrottle));
        float estTurnRadius = wantRun ? 1.46F : 0.45F;
        EmberCorridor.CarrotSample sample = corridor.sampleCarrot(npc, lookDist, estTurnRadius);

        float rawDirX = sample.carrotX() - npc.getX();
        float rawDirY = sample.carrotY() - npc.getY();
        float rawLen = (float) Math.hypot(rawDirX, rawDirY);
        if (rawLen < 1.0E-4F) {
            latchArrived(npc);
            return;
        }
        rawDirX /= rawLen;
        rawDirY /= rawLen;

        EmberPortalActuator.ActStatus act = portalActuator.update(npc, sample.activePortal(), rawDirX, rawDirY);
        if (act == EmberPortalActuator.ActStatus.BLOCKED) {
            handleBlockedOrStuck(npc, "PORTAL_BLOCKED");
            return;
        }
        if (act == EmberPortalActuator.ActStatus.TRAVERSING_NATIVE) {
            turn180Latched = false;
            npc.clearManualMovementClamp();
            npc.setEmberDesiredRunning(false);
            npc.setEmberMotionState(false, 1.0F, EmberParams.TURN_GAIN);
            applyZeroMoveInput(npc);
            resetStuckMonitor(npc);
            return;
        }

        EmberContextSteering.SteerOutput avoid = contextSteering.evaluate(npc, rawDirX, rawDirY);
        if (avoid.yielding()) {
            turn180Latched = false;
            smoothedThrottle = 0.0F;
            inRunGear = false;
            npc.faceLocationF(npc.getX() + rawDirX, npc.getY() + rawDirY);
            applyZeroMoveInput(npc);
            npc.setEmberDesiredRunning(false);
            npc.setEmberMotionState(false, 0.25F, EmberParams.TURN_GAIN);
            resetStuckMonitor(npc);
            return;
        }

        float steerX = avoid.dirX();
        float steerY = avoid.dirY();

        float[] bodyFwd = worldSkeletonForward(npc);
        float cosErr = clamp(bodyFwd[0] * steerX + bodyFwd[1] * steerY, -1.0F, 1.0F);
        float absErrRad = (float) Math.acos(cosErr);

        boolean turningAround = npc.isTurningAround();
        if (turningAround) {
            if (!turn180Latched) {
                turn180Latched = true;
                turn180TargetDeg = (float) Math.toDegrees(Math.atan2(steerY, steerX));
            }
            double rad = Math.toRadians(turn180TargetDeg);
            steerX = (float) Math.cos(rad);
            steerY = (float) Math.sin(rad);
        } else {
            turn180Latched = false;
        }

        int curZ = EmberTargetProjector.levelOf(npc.getZ());
        IsoGridSquare curSq = npc.getCurrentSquare();
        boolean onStairs = curSq != null && curSq.HasStairs();

        boolean tightSpace = false;
        float alphaProx = 1.0F;
        String reasonTag = sample.filletArcClear() ? "FILLET_ARC" : "OPEN_RUN";
        if (!sample.filletArcClear()) {
            float proxDist = EmberParams.PROX_DIST;
            if (proxDist > 0.05F && !onStairs && PolygonalMap2.instance != null) {
                boolean wallAhead = PolygonalMap2.instance.lineClearCollide(
                        npc.getX(), npc.getY(),
                        npc.getX() + bodyFwd[0] * proxDist,
                        npc.getY() + bodyFwd[1] * proxDist,
                        curZ, npc, false, true);
                if (wallAhead) {
                    tightSpace = true;
                    alphaProx = 0.65F;
                    reasonTag = "WALL_PROX";
                }
            }
            if (sample.distToBarrier() < 1.35F && sample.upcomingTurnRad() > 0.55F) {
                tightSpace = true;
                alphaProx = Math.min(alphaProx, 0.65F);
                reasonTag = "PORTAL_PROX";
            } else if (sample.distToBarrier() < 0.78F && sample.upcomingTurnRad() > 0.40F) {
                tightSpace = true;
                alphaProx = Math.min(alphaProx, 0.68F);
                reasonTag = "PORTAL_PROX";
            }
        }

        float alphaCurv = 1.0F;
        float rotGateRad = (float) Math.toRadians(EmberParams.ROTATE_HEADING_DEG);
        if (turningAround) {
            reasonTag = "TURN_180";
        } else {
            if (wantRun && absErrRad > Math.toRadians(110.0)) {
                alphaCurv = 1.0F;
                reasonTag = "TURN_180";
            } else if (tightSpace) {
                if (absErrRad >= rotGateRad) {
                    alphaCurv = 0.58F;
                    reasonTag = "TIGHT_CURV";
                } else {
                    float radius = lookDist / (2.0F * (float) Math.sin(absErrRad) + 1.0E-3F);
                    float minR = EmberParams.MIN_TURN_RADIUS;
                    if (minR > 0.05F && radius < minR) {
                        alphaCurv = clamp(radius / minR, 0.60F, 1.0F);
                        reasonTag = "TIGHT_CURV";
                    }
                }
            }
        }

        float alphaBrake = 1.0F;
        float brakeDist = EmberParams.BRAKE_DIST;
        boolean inBrakeZone = brakeDist > 0.05F && sample.distToGoal() < brakeDist;
        if (inBrakeZone) {
            alphaBrake = clamp((float) Math.sqrt(sample.distToGoal() / brakeDist), 0.22F, 1.0F);
            reasonTag = "BRAKE";
        }

        float maxThrottle = wantRun ? 1.0F : 0.50F;
        float targetThrottle = maxThrottle * Math.min(avoid.speedCap(), Math.min(alphaCurv, Math.min(alphaProx, alphaBrake)));

        if (wantRun && smoothedThrottle < 0.58F && !inBrakeZone && avoid.speedCap() > 0.60F) {
            smoothedThrottle = Math.max(smoothedThrottle, 0.58F);
        }

        float rate = targetThrottle < smoothedThrottle ? 3.8F : 3.2F;
        float maxDelta = rate * dt;
        if (Math.abs(targetThrottle - smoothedThrottle) <= maxDelta) {
            smoothedThrottle = targetThrottle;
        } else {
            smoothedThrottle += Math.signum(targetThrottle - smoothedThrottle) * maxDelta;
        }
        smoothedThrottle = clamp(smoothedThrottle, 0.08F, maxThrottle);

        float animSpeedScalar;
        if (!wantRun) {
            inRunGear = false;
            animSpeedScalar = clamp(smoothedThrottle / 0.50F, 0.10F, 1.0F);
        } else if (sample.distToGoal() < Math.min(0.65F, brakeDist) && smoothedThrottle < 0.42F) {
            inRunGear = false;
            animSpeedScalar = clamp(smoothedThrottle / 0.50F, 0.10F, 1.0F);
        } else {
            inRunGear = true;
            float minRunScalar = (inBrakeZone || avoid.speedCap() < 0.60F) ? 0.08F : 0.50F;
            animSpeedScalar = clamp((smoothedThrottle - 0.15F) / 0.85F, minRunScalar, 1.0F);
        }

        float turnGain;
        if (!inRunGear) {
            turnGain = 1.00F;
        } else if (tightSpace && !turningAround && !inBrakeZone) {
            turnGain = Math.max(EmberParams.TURN_GAIN, 1.35F);
        } else {
            turnGain = EmberParams.TURN_GAIN;
        }

        this.liveReasonTag = reasonTag;
        this.liveAnimScalar = animSpeedScalar;
        this.liveTurnGain = turnGain;

        driveNativeCharacter(npc, steerX, steerY, inRunGear, animSpeedScalar, turnGain);
        npc.setManualMovementClamp(sample.clampX(), sample.clampY(), sample.clampZ(), sample.clampIsGoal());

        if (!onStairs && checkStuck(npc, dt)) {
            handleBlockedOrStuck(npc, "STUCK_REPATH");
        }
    }

    public void onRunningChanged(IsoNPC npc, boolean running) {
        if (npc != null) {
            npc.setRunningMode(running);
        }
        if (!running) {
            inRunGear = false;
            smoothedThrottle = Math.min(smoothedThrottle, 0.50F);
        }
    }

    public void interrupt(IsoNPC npc, String reason) {
        boolean wasActive = this.active;
        stopInternal(npc);
        synchronized (this) {
            if (wasActive || this.moveSeq > this.settledSeq) {
                this.status = Status.INTERRUPTED;
                this.lastReason = reason != null ? reason : "INTERRUPTED";
                this.settledSeq = this.moveSeq;
            } else {
                this.status = Status.IDLE;
                this.lastReason = "IDLE";
            }
        }
        this.liveReasonTag = this.status.name();
    }

    public void stop(IsoNPC npc) {
        interrupt(npc, "STOPPED");
    }

    private void stopInternal(IsoNPC npc) {
        active = false;
        arrivedLatched = false;
        corridor = null;
        pendingEscapeStart = null;
        smoothedThrottle = 0.0F;
        inRunGear = false;
        turn180Latched = false;
        repathStreak = 0;
        contextSteering.reset();
        portalActuator.reset();
        cancelAsyncRequest(npc);
        if (npc != null) {
            npc.clearManualMovementClamp();
            npc.setEmberDesiredRunning(false);
            npc.setEmberMotionState(false, 1.0F, EmberParams.TURN_GAIN);
            npc.setEmberControlActive(false);
            npc.setNativeSmoothPathUnlock(false);
            applyIdleHold(npc, false);
            npc.discardRootMotion();
        }
    }

    private void submitAsyncPathRequest(IsoNPC npc, EmberTargetProjector.ProjectedPoint startPt) {
        boolean useNative = PathfindNative.useNativeCode && PathfindNative.instance != null;
        if (!useNative && PolygonalMap2.instance == null) {
            failTrip(npc, Status.NO_PATH, "NO_PATHFINDER");
            return;
        }
        cancelAsyncRequest(npc);
        final long gen;
        synchronized (asyncLock) {
            gen = ++requestGen;
            pendingNpc = npc;
            readyRawXyz = null;
            asyncFailed = false;
        }
        this.pendingEscapeStart = startPt;
        float sx = startPt != null ? startPt.x() : npc.getX();
        float sy = startPt != null ? startPt.y() : npc.getY();
        int sz = startPt != null ? startPt.z() : EmberTargetProjector.levelOf(npc.getZ());

        IPathfinder cb = new IPathfinder() {
            @Override
            public void Succeeded(Path path, Mover mover) {
                if (path == null || path.isEmpty() || !(mover instanceof IsoNPC m)) {
                    Failed(mover);
                    return;
                }
                int n = path.size();
                float[] copy = new float[n * 3];
                for (int i = 0; i < n; i++) {
                    PathNode nd = path.getNode(i);
                    copy[i * 3] = nd.x;
                    copy[i * 3 + 1] = nd.y;
                    copy[i * 3 + 2] = nd.z;
                }
                synchronized (asyncLock) {
                    if (gen != requestGen || m != pendingNpc) return;
                    readyRawXyz = copy;
                    asyncFailed = false;
                }
            }

            @Override
            public void Failed(Mover mover) {
                synchronized (asyncLock) {
                    if (gen != requestGen || mover != pendingNpc) return;
                    readyRawXyz = null;
                    asyncFailed = true;
                }
            }
        };

        if (useNative) {
            PathfindNative.instance.addRequest(cb, npc, sx, sy, sz, goalX, goalY, goalZ);
        } else {
            PolygonalMap2.instance.addRequest(cb, npc, sx, sy, sz, goalX, goalY, goalZ);
        }
    }

    private void pollAsyncPathResult(IsoNPC npc) {
        float[] raw = null;
        boolean failed = false;
        synchronized (asyncLock) {
            if (npc == pendingNpc) {
                if (readyRawXyz != null) {
                    raw = readyRawXyz;
                    readyRawXyz = null;
                    pendingNpc = null;
                } else if (asyncFailed) {
                    failed = true;
                    asyncFailed = false;
                    pendingNpc = null;
                }
            }
        }
        if (raw != null) {
            this.corridor = EmberCorridor.fromRawCoords(npc, raw, pendingEscapeStart, goalX, goalY, goalZ);
            this.repathStreak = 0;
            resetStuckMonitor(npc);
            if (this.corridor == null) {
                failTrip(npc, Status.NO_PATH, "EMPTY_CORRIDOR");
            }
        } else if (failed) {
            failTrip(npc, Status.NO_PATH, "NO_PATH");
        }
    }

    private void handleBlockedOrStuck(IsoNPC npc, String reason) {
        repathStreak++;
        if (repathStreak > MAX_REPATH_RETRIES) {
            Status st = "STUCK_REPATH".equals(reason) ? Status.STUCK : Status.NO_PATH;
            failTrip(npc, st, reason);
            return;
        }
        corridor = null;
        applyZeroMoveInput(npc);
        npc.setEmberMotionState(false, 0.30F, EmberParams.TURN_GAIN);
        submitAsyncPathRequest(npc, EmberTargetProjector.projectStartIfTrapped(npc));
    }

    private void latchArrived(IsoNPC npc) {
        active = false;
        arrivedLatched = true;
        arrivedAtMs = System.currentTimeMillis();
        corridor = null;
        smoothedThrottle = 0.0F;
        inRunGear = false;
        liveReasonTag = "ARRIVED";
        applyIdleHold(npc, true);
        synchronized (this) {
            status = Status.ARRIVED;
            lastReason = "REACHED";
            settledSeq = moveSeq;
        }
    }

    private void failTrip(IsoNPC npc, Status failStatus, String reason) {
        active = false;
        arrivedLatched = false;
        corridor = null;
        smoothedThrottle = 0.0F;
        inRunGear = false;
        liveReasonTag = "FAILED:" + reason;
        applyIdleHold(npc, false);
        synchronized (this) {
            status = failStatus;
            lastReason = reason;
            settledSeq = moveSeq;
        }
    }

    public String getLiveDebugLine(IsoNPC npc) {
        if (npc == null) return "";
        String st = npc.getCurrentState() != null ? npc.getCurrentState().getClass().getSimpleName() : "?";
        String act = (npc.getActionContext() != null && npc.getActionContext().getCurrentState() != null)
                ? npc.getActionContext().getCurrentState().getName() : "?";
        float wSpd = npc.getVariableFloat("WalkSpeed", 0.0F);
        return String.format(java.util.Locale.US,
                "[%s] status=%s st=%s act=%s run=%s wSpd=%.2f td=%.2f tw=%.1f t90=%s t180=%s",
                liveReasonTag, status.name(), st, act, npc.isRunning() ? "1" : "0",
                wSpd, liveTurnGain, npc.getTwist(),
                npc.isTurning90() ? "1" : "0", npc.isTurningAround() ? "1" : "0");
    }

    private void applyIdleHold(IsoNPC npc, boolean clampGoal) {
        turn180Latched = false;
        npc.setEmberDesiredRunning(false);
        applyZeroMoveInput(npc);
        npc.setEmberMotionState(false, 1.0F, EmberParams.TURN_GAIN);
        if (clampGoal) {
            npc.setManualMovementClamp(goalX, goalY, goalZ, true);
        } else {
            npc.clearManualMovementClamp();
        }
        try { npc.setVariable("bMoving", false); } catch (Throwable ignored) {}
        try { npc.setVariable("bPathfind", false); } catch (Throwable ignored) {}
    }

    private static void driveNativeCharacter(
            IsoNPC npc, float dirX, float dirY, boolean runGear, float animScalar, float turnGain) {
        cancelVanillaPathBehavior(npc);
        if (!npc.isAiming() && !npc.isStrafing()) {
            npc.setDirectionAngle((float) Math.toDegrees(Math.atan2(dirY, dirX)));
        }
        npc.playerMoveDir.set(dirX, dirY);
        npc.setJustMoved(true);
        npc.setMoving(true);
        npc.setEmberDesiredRunning(runGear);
        npc.setEmberMotionState(true, animScalar, turnGain);
        npc.setRunning(runGear);
        npc.setSprinting(false);

        AIComponent ai = npc.tryGetECSComponent(AIComponent.class);
        if (ai != null && ai.getHumanControlVars() != null) {
            AIBrainPlayerControlVars vars = ai.getHumanControlVars();
            vars.justMoved = true;
            vars.running = runGear;
            float animRad = npc.getDirectionAngleRadians();
            try {
                if (npc.hasAnimationPlayer() && npc.getAnimationPlayer() != null) {
                    animRad = npc.getAnimationPlayer().getRenderedAngle();
                }
            } catch (Throwable ignored) {}
            float vx = dirX;
            float vy = -dirY;
            float cos = (float) Math.cos(animRad);
            float sin = (float) Math.sin(animRad);
            vars.strafeX = vx * cos - vy * sin;
            vars.strafeY = vx * sin + vy * cos;
        }
    }

    private static void applyZeroMoveInput(IsoNPC npc) {
        if (npc == null) return;
        npc.playerMoveDir.set(0.0F, 0.0F);
        npc.setJustMoved(false);
        npc.setMoving(false);
        npc.setRunning(false);
        npc.setSprinting(false);
        AIComponent ai = npc.tryGetECSComponent(AIComponent.class);
        if (ai != null && ai.getHumanControlVars() != null) {
            AIBrainPlayerControlVars vars = ai.getHumanControlVars();
            vars.justMoved = false;
            vars.running = false;
            vars.strafeX = 0.0F;
            vars.strafeY = 0.0F;
        }
    }

    private static void cancelVanillaPathBehavior(IsoNPC npc) {
        PathFindBehavior2 pfb = npc.getPathFindBehavior2();
        if (pfb != null && !pfb.getIsCancelled()) {
            pfb.cancel();
        }
        if (npc.getPath2() != null) {
            npc.setPath2(null);
        }
    }

    private void cancelAsyncRequest(IsoNPC npc) {
        synchronized (asyncLock) {
            requestGen++;
            pendingNpc = null;
            readyRawXyz = null;
            asyncFailed = false;
        }
        if (npc != null) {
            if (PathfindNative.useNativeCode && PathfindNative.instance != null) {
                PathfindNative.instance.cancelRequest(npc);
            } else if (PolygonalMap2.instance != null) {
                PolygonalMap2.instance.cancelRequest(npc);
            }
        }
    }

    private boolean checkStuck(IsoNPC npc, float dt) {
        stuckTimerSec += dt;
        if (stuckTimerSec < STUCK_CHECK_WINDOW_SEC) {
            return false;
        }
        float moved = (float) Math.hypot(npc.getX() - stuckAnchorX, npc.getY() - stuckAnchorY);
        resetStuckMonitor(npc);
        return moved < STUCK_MIN_DISP;
    }

    private void resetStuckMonitor(IsoNPC npc) {
        stuckTimerSec = 0.0F;
        if (npc != null) {
            stuckAnchorX = npc.getX();
            stuckAnchorY = npc.getY();
        }
    }

    private static float[] worldSkeletonForward(IsoNPC npc) {
        try {
            if (npc.hasAnimationPlayer() && npc.getAnimationPlayer() != null) {
                float animRad = npc.getAnimationPlayer().getRenderedAngle();
                if (Float.isFinite(animRad)) {
                    return new float[]{(float) Math.sin(animRad), -(float) Math.cos(animRad)};
                }
            }
        } catch (Throwable ignored) {}
        float fx = npc.getForwardDirectionX();
        float fy = npc.getForwardDirectionY();
        float len = (float) Math.hypot(fx, fy);
        return len > 1.0E-4F ? new float[]{fx / len, fy / len} : new float[]{1.0F, 0.0F};
    }

    private static float deltaSeconds() {
        try {
            float dt = GameTime.instance.getMultipliedSecondsSinceLastUpdate();
            if (Float.isFinite(dt) && dt > 0.0F) {
                return Math.max(0.002F, Math.min(0.10F, dt));
            }
        } catch (Throwable ignored) {}
        return 1.0F / 60.0F;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
