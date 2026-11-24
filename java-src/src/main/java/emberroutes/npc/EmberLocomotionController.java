package emberroutes.npc;

import java.util.List;
import zombie.GameTime;
import zombie.ai.AIBrainPlayerControlVars;
import zombie.ai.astar.Mover;
import zombie.ai.states.PlayerStrafeState;
import zombie.characters.IsoPlayer;
import zombie.characters.component.AIComponent;
import zombie.iso.IsoGridSquare;
import zombie.iso.Vector2;
import zombie.pathfind.IPathfinder;
import zombie.pathfind.Path;
import zombie.pathfind.PathFindBehavior2;
import zombie.pathfind.PathNode;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.nativeCode.PathFindRequest;
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

    public enum Gait {
        SNEAK,
        WALK,
        RUN,
        SPRINT
    }

    /**
     * 精密锚点停靠候选目标：
     * {@code (pathX, pathY)} 为可见性图可达站位（实心家具按原版外推 0.30 格），
     * {@code (exactX, exactY)} 为 {@code SeatingManager} 等原版状态进入时的真实精确落点。
     */
    public record PrecisionTarget(
            float pathX, float pathY, float exactX, float exactY, int z, int tag, IsoGridSquare ignoreSquare) {}

    private static final float STUCK_CHECK_WINDOW_SEC = 0.55F;
    private static final float STUCK_MIN_DISP = 0.14F;
    private static final int MAX_REPATH_RETRIES = 3;
    private static final int MAX_PATH_SUBMITS_PER_FRAME = 2;
    private static final long FAILED_GOAL_COOLDOWN_MS = 800L;
    private static final long PRECISION_STOP_MAX_MS = 350L;

    private static volatile int framePathSubmitsRemaining = MAX_PATH_SUBMITS_PER_FRAME;
    private static volatile long frameBudgetResetMs;

    private final Object asyncLock = new Object();
    private long requestGen;
    private IsoNPC pendingNpc;
    private float[] readyRawXyz;
    private boolean asyncFailed;
    private boolean asyncInFlight;
    private float asyncGoalX;
    private float asyncGoalY;
    private int asyncGoalZ;

    private final EmberContextSteering contextSteering = new EmberContextSteering();
    private final EmberPortalActuator portalActuator = new EmberPortalActuator();

    private boolean active;
    private boolean arrivedLatched;
    private long arrivedAtMs;
    private float goalX;
    private float goalY;
    private int goalZ;
    private Gait desiredGait = Gait.WALK;
    private float desiredWalkSpeedCap = -1.0F;
    private float desiredTrackSpeedScale = 1.0F;
    private boolean cruiseMode;
    private boolean cruiseHoldOpen;
    private boolean pendingDeferredRetarget;
    private IsoPlayer steerLeader;
    private boolean steerLeaderMoving;
    private int failedGoalTileX = Integer.MIN_VALUE;
    private int failedGoalTileY = Integer.MIN_VALUE;
    private int failedGoalZ = Integer.MIN_VALUE;
    private long failedGoalUntilMs;
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

    private float diagPrevSteerX;
    private float diagPrevSteerY;
    private boolean diagHasPrevSteer;
    private boolean diagPrevTurningAround;
    private float diagStallSec;
    private float diagStallX;
    private float diagStallY;
    private long diagLastStallMs;
    private long diagLastYieldMs;
    private long diagLastZMismatchMs;
    private long diagLastReversalMs;

    private Status status = Status.IDLE;
    private String lastReason = "IDLE";
    private long moveSeq;
    private long settledSeq;

    private boolean precisionMode;
    private PrecisionTarget[] precisionCandidates;
    private PrecisionTarget activePrecisionTarget;
    private int resolvedPrecisionTag = -1;
    private IsoGridSquare ignoreCollisionSquare;
    private boolean precisionShortStrafing;
    private boolean precisionStopping;
    private long precisionStopStartedMs;
    private final Vector2 scratchDeferred = new Vector2();

    String formatCorridorNode() {
        return corridor != null ? corridor.formatCurrentNode() : "NONE";
    }

    private void resetDiagObservers() {
        diagHasPrevSteer = false;
        diagPrevTurningAround = false;
        diagStallSec = 0.0F;
    }

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

    public synchronized int getResolvedPrecisionTag() {
        return resolvedPrecisionTag;
    }

    public boolean shouldIgnoreCollisionWithSquare(IsoGridSquare square) {
        return square != null && precisionMode && ignoreCollisionSquare != null && ignoreCollisionSquare == square;
    }

    public boolean isPrecisionShortStrafing() {
        return active && precisionMode && precisionShortStrafing && !precisionStopping;
    }

    public static void beginFramePathBudget() {
        framePathSubmitsRemaining = MAX_PATH_SUBMITS_PER_FRAME;
        frameBudgetResetMs = System.currentTimeMillis();
    }

    private static synchronized boolean tryConsumeFramePathBudget() {
        long now = System.currentTimeMillis();
        if (now - frameBudgetResetMs >= 15L) {
            framePathSubmitsRemaining = MAX_PATH_SUBMITS_PER_FRAME;
            frameBudgetResetMs = now;
        }
        if (framePathSubmitsRemaining > 0) {
            framePathSubmitsRemaining--;
            return true;
        }
        return false;
    }

    public boolean isMoving() {
        return active && !arrivedLatched;
    }

    public void setPortalPolicy(boolean allowWindows, boolean allowTallWalls, float autoCloseClearRadius) {
        portalActuator.setPortalPolicy(allowWindows, allowTallWalls, autoCloseClearRadius);
    }

    public void setLeaderPortalGate(boolean active, float lx, float ly, int lz) {
        portalActuator.setLeaderPortalGate(active, lx, ly, lz);
    }

    public void setFollowSteerHint(IsoPlayer leader, boolean leaderMoving) {
        this.steerLeader = leader;
        this.steerLeaderMoving = leaderMoving;
    }

    public void setLiveGait(IsoNPC npc, Gait gait, float walkSpeedCap) {
        setLiveVelocityControl(npc, gait, walkSpeedCap, 1.0F, cruiseHoldOpen);
    }

    public void setLiveVelocityControl(
            IsoNPC npc, Gait gait, float walkSpeedCap, float trackSpeedScale, boolean cruiseOpen) {
        this.desiredGait = gait != null ? gait : Gait.WALK;
        this.desiredWalkSpeedCap = (Float.isFinite(walkSpeedCap) && walkSpeedCap >= 0.0F)
                ? Math.min(1.0F, walkSpeedCap) : -1.0F;
        this.desiredTrackSpeedScale = (Float.isFinite(trackSpeedScale) && trackSpeedScale > 0.0F)
                ? clamp(trackSpeedScale, 0.45F, 1.15F) : 1.0F;
        this.cruiseHoldOpen = cruiseOpen;
        boolean fast = this.desiredGait == Gait.RUN || this.desiredGait == Gait.SPRINT;
        if (npc != null) {
            npc.setRunningMode(fast);
        }
        if (!fast) {
            inRunGear = false;
            smoothedThrottle = Math.min(smoothedThrottle, 0.50F);
        }
    }

    public boolean updateCruiseTargetInPlace(IsoNPC npc, float tx, float ty, int tz) {
        if (npc == null || !active || !cruiseMode || corridor == null || !corridor.isAtFinalGoal()) {
            return false;
        }
        int curZ = EmberTargetProjector.levelOf(npc.getZ());
        if (curZ != tz || PolygonalMap2.instance == null) {
            return false;
        }
        if (PolygonalMap2.instance.lineClearCollide(npc.getX(), npc.getY(), tx, ty, curZ, npc, false, true)) {
            return false;
        }
        corridor.updateFinalGoal(npc.getX(), npc.getY(), tx, ty, tz);
        this.goalX = tx;
        this.goalY = ty;
        this.goalZ = tz;
        this.pendingDeferredRetarget = false;
        return true;
    }

    public void holdFollowIdle(IsoNPC npc, boolean sneaking) {
        clearPrecisionState(npc, true);
        active = false;
        arrivedLatched = false;
        cruiseMode = false;
        cruiseHoldOpen = false;
        pendingDeferredRetarget = false;
        steerLeader = null;
        steerLeaderMoving = false;
        corridor = null;
        pendingEscapeStart = null;
        smoothedThrottle = 0.0F;
        inRunGear = false;
        turn180Latched = false;
        repathStreak = 0;
        desiredGait = sneaking ? Gait.SNEAK : Gait.WALK;
        desiredWalkSpeedCap = -1.0F;
        desiredTrackSpeedScale = 1.0F;
        resetDiagObservers();
        cancelAsyncRequest(npc);
        if (npc != null) {
            npc.setRunningMode(false);
            npc.setSneaking(sneaking);
            npc.setSprinting(false);
            npc.setEmberDesiredRunning(false);
            npc.setEmberDesiredSprinting(false);
            npc.setEmberWalkSpeedCap(-1.0F);
            npc.setEmberTrackSpeedScale(1.0F);
            applyIdleHold(npc, false);
            npc.setEmberControlActive(false);
        }
        synchronized (this) {
            status = Status.IDLE;
            lastReason = "FOLLOW_HOLD";
        }
        liveReasonTag = "HOLD";
    }

    private void prepareExplicitTripVars(IsoNPC npc, Gait gait, boolean running) {
        npc.setRunningMode(running);
        if (!running) {
            npc.setSneaking(false);
        }
        this.desiredGait = gait;
        this.desiredWalkSpeedCap = -1.0F;
        this.desiredTrackSpeedScale = 1.0F;
        this.cruiseMode = false;
        this.cruiseHoldOpen = false;
        this.pendingDeferredRetarget = false;
        this.steerLeader = null;
        this.steerLeaderMoving = false;
        portalActuator.setPortalPolicy(true, true, 0.0F);
        portalActuator.setLeaderPortalGate(false, 0.0F, 0.0F, 0);
    }

    public long moveTo(IsoNPC npc, double rawX, double rawY, double rawZ, boolean running) {
        if (npc == null || npc.isDead()) {
            return -1L;
        }
        clearPrecisionState(npc, true);

        final long seq;
        synchronized (this) {
            seq = ++moveSeq;
        }

        prepareExplicitTripVars(npc, running ? Gait.RUN : Gait.WALK, running);

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
        this.corridor = null;
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
                startProj != null ? startProj : new EmberTargetProjector.ProjectedPoint(npc.getX(), npc.getY(), curZ, false),
                goalX, goalY, goalZ);
        return seq;
    }

    /**
     * 1:1 对齐原版 {@code PathFindBehavior2.pathToSitOnFurniture} 的精密锚点停靠：
     * <ol>
     *   <li>支持向后台 {@code PathfindNative} 提交多候选可达点 {@code targetXyz}，自动选择实际可达且路网最短一侧；</li>
     *   <li>若目标位于实心家具格内，自动经过外部可达点再由 {@code shouldIgnoreCollisionWithSquare} 放行进座；</li>
     *   <li>终段支持原版 {@code < 0.50F} 无瞄准短距侧移步，并按 {@code 0.05F} 到达半径 + {@code 0.005F} 停步收敛。</li>
     * </ol>
     */
    public long moveToExact(
            IsoNPC npc, List<PrecisionTarget> candidates, IsoGridSquare ignoreSquare) {
        if (npc == null || npc.isDead() || candidates == null || candidates.isEmpty()) {
            return -1L;
        }

        final long seq;
        synchronized (this) {
            seq = ++moveSeq;
        }

        clearPrecisionState(npc, true);
        PrecisionTarget[] candArray = candidates.toArray(new PrecisionTarget[0]);
        PrecisionTarget primary = candArray[0];

        prepareExplicitTripVars(npc, Gait.WALK, false);

        this.precisionMode = true;
        this.precisionCandidates = candArray;
        lockActivePrecisionTarget(primary);
        this.corridor = null;
        this.active = true;
        this.arrivedLatched = false;
        this.repathStreak = 0;

        npc.setEmberControlActive(true);
        npc.setNativeSmoothPathUnlock(true);
        cancelVanillaPathBehavior(npc);
        contextSteering.reset();

        synchronized (this) {
            this.status = Status.MOVING;
            this.lastReason = "MOVING_EXACT";
        }
        resetStuckMonitor(npc);

        int curZ = EmberTargetProjector.levelOf(npc.getZ());
        PrecisionTarget alreadyDocked = null;
        float bestDockDist = EmberPostureMath.PRECISION_ARRIVE_RADIUS;
        for (PrecisionTarget c : candArray) {
            if (c.z() == curZ) {
                float d = (float) Math.hypot(c.exactX() - npc.getX(), c.exactY() - npc.getY());
                if (d <= bestDockDist) {
                    bestDockDist = d;
                    alreadyDocked = c;
                }
            }
        }
        if (alreadyDocked != null) {
            lockActivePrecisionTarget(alreadyDocked);
            latchArrived(npc);
            return seq;
        }

        EmberTargetProjector.ProjectedPoint startProj = EmberTargetProjector.projectStartIfTrapped(npc);
        this.pendingEscapeStart = startProj;

        if ((startProj == null || !startProj.adjusted()) && PolygonalMap2.instance != null) {
            PrecisionTarget bestDirect = null;
            float bestDirectDist = Float.MAX_VALUE;
            for (PrecisionTarget c : candArray) {
                if (c.z() != curZ) {
                    continue;
                }
                if (!PolygonalMap2.instance.lineClearCollide(
                        npc.getX(), npc.getY(), c.pathX(), c.pathY(), curZ, npc, false, true)) {
                    float d = (float) Math.hypot(c.exactX() - npc.getX(), c.exactY() - npc.getY());
                    if (d < bestDirectDist) {
                        bestDirectDist = d;
                        bestDirect = c;
                    }
                }
            }
            if (bestDirect != null) {
                lockActivePrecisionTarget(bestDirect);
                cancelAsyncRequest(npc);
                this.corridor = EmberCorridor.buildDirectPrecision(
                        npc.getX(), npc.getY(), curZ, null,
                        bestDirect.pathX(), bestDirect.pathY(),
                        bestDirect.exactX(), bestDirect.exactY(), bestDirect.z());
                activateShortStrafeIfEligible(npc);
                return seq;
            }
        }

        EmberTargetProjector.ProjectedPoint effectiveStart =
                startProj != null ? startProj : new EmberTargetProjector.ProjectedPoint(npc.getX(), npc.getY(), curZ, false);
        submitAsyncPathRequestInternal(npc, effectiveStart, primary.pathX(), primary.pathY(), primary.z(), candArray);
        return seq;
    }

    /**
     * 双缓冲动态改标（专用于连续跟随）：
     * 发起新异步路径请求时不清空现有 corridor，等后台 C++ PathfindNative 回包再原子热切，避免改标顿步；
     * 不递增 moveSeq（不触发静默点对点回调误报）。
     */
    public boolean retarget(
            IsoNPC npc,
            float rawX, float rawY, float rawZ,
            Gait gait, float walkSpeedCap, boolean cruise) {
        return retarget(npc, rawX, rawY, rawZ, gait, walkSpeedCap, 1.0F, cruise, cruise);
    }

    public boolean retarget(
            IsoNPC npc,
            float rawX, float rawY, float rawZ,
            Gait gait, float walkSpeedCap, float trackSpeedScale, boolean cruise, boolean cruiseOpen) {
        if (npc == null || npc.isDead()) {
            return false;
        }
        clearPrecisionState(npc, true);

        EmberTargetProjector.ProjectedPoint goalProj =
                EmberTargetProjector.projectGoal(npc, rawX, rawY, rawZ);
        if (goalProj == null) {
            return false;
        }

        float tx = goalProj.x();
        float ty = goalProj.y();
        int tz = goalProj.z();
        int curZ = EmberTargetProjector.levelOf(npc.getZ());
        float distToGoal = (float) Math.hypot(tx - npc.getX(), ty - npc.getY());
        if (!cruise && curZ == tz && distToGoal <= EmberParams.ARRIVE_RADIUS) {
            setLiveVelocityControl(npc, gait, walkSpeedCap, trackSpeedScale, false);
            this.cruiseMode = false;
            npc.setEmberControlActive(true);
            npc.setNativeSmoothPathUnlock(true);
            cancelVanillaPathBehavior(npc);
            this.goalX = tx;
            this.goalY = ty;
            this.goalZ = tz;
            cancelAsyncRequest(npc);
            this.corridor = null;
            this.pendingDeferredRetarget = false;
            latchArrived(npc);
            return true;
        }

        EmberTargetProjector.ProjectedPoint startProj = EmberTargetProjector.projectStartIfTrapped(npc);
        boolean directClear = curZ == tz
                && (startProj == null || !startProj.adjusted())
                && PolygonalMap2.instance != null
                && !PolygonalMap2.instance.lineClearCollide(npc.getX(), npc.getY(), tx, ty, curZ, npc, false, true);
        boolean sameAsyncInFlight = !directClear && asyncInFlight && asyncGoalZ == tz
                && Math.hypot(tx - asyncGoalX, ty - asyncGoalY) <= 0.80;

        if (!directClear && !sameAsyncInFlight) {
            int gTileX = (int) Math.floor(tx);
            int gTileY = (int) Math.floor(ty);
            if (System.currentTimeMillis() < failedGoalUntilMs
                    && gTileX == failedGoalTileX && gTileY == failedGoalTileY && tz == failedGoalZ) {
                return false;
            }
        }

        setLiveVelocityControl(npc, gait, walkSpeedCap, trackSpeedScale, cruise && cruiseOpen);
        this.cruiseMode = cruise;
        npc.setEmberControlActive(true);
        npc.setNativeSmoothPathUnlock(true);
        cancelVanillaPathBehavior(npc);
        this.goalX = tx;
        this.goalY = ty;
        this.goalZ = tz;
        this.active = true;
        this.arrivedLatched = false;
        this.repathStreak = 0;
        synchronized (this) {
            this.status = Status.MOVING;
            this.lastReason = cruise ? "FOLLOW" : "RETARGET";
        }

        if (directClear) {
            cancelAsyncRequest(npc);
            this.corridor = EmberCorridor.buildDirect(npc.getX(), npc.getY(), curZ, null, tx, ty, tz);
            this.pendingDeferredRetarget = false;
            return true;
        }
        if (sameAsyncInFlight) {
            return true;
        }

        EmberTargetProjector.ProjectedPoint effectiveStart =
                startProj != null ? startProj : new EmberTargetProjector.ProjectedPoint(npc.getX(), npc.getY(), curZ, false);
        if (tryConsumeFramePathBudget()) {
            this.pendingDeferredRetarget = false;
            submitAsyncPathRequest(npc, effectiveStart, tx, ty, tz);
        } else {
            this.pendingDeferredRetarget = true;
        }
        return true;
    }

    public void beforeUpdate(IsoNPC npc) {
        if (npc == null) return;
        if (npc.getPosture().isBlockingLocomotion()) {
            npc.setEmberControlActive(false);
            applyZeroMoveInput(npc);
            resetStuckMonitor(npc);
            return;
        }
        npc.setEmberControlActive(true);
        npc.setNativeSmoothPathUnlock(true);

        if (npc.getVehicle() != null || !npc.getCharacterActions().isEmpty()) {
            npc.clearManualMovementClamp();
            npc.setEmberDesiredRunning(false);
            npc.setEmberDesiredSprinting(false);
            npc.setEmberWalkSpeedCap(-1.0F);
            npc.setEmberTrackSpeedScale(1.0F);
            npc.setEmberMotionState(false, 1.0F, EmberParams.TURN_GAIN);
            applyZeroMoveInput(npc);
            resetStuckMonitor(npc);
            return;
        }

        pollAsyncPathResult(npc);

        if (precisionStopping && active && precisionMode) {
            npc.setPrecisionDockClamp(goalX, goalY, goalZ, true);
            npc.setEmberDesiredRunning(false);
            npc.setEmberDesiredSprinting(false);
            npc.setEmberWalkSpeedCap(-1.0F);
            npc.setEmberTrackSpeedScale(1.0F);
            applyZeroMoveInput(npc);
            npc.setEmberMotionState(false, 1.0F, EmberParams.TURN_GAIN);
            npc.getDeferredMovement(scratchDeferred);
            float remDist = (float) Math.hypot(goalX - npc.getX(), goalY - npc.getY());
            long elapsed = System.currentTimeMillis() - precisionStopStartedMs;
            if ((scratchDeferred.getLength() <= 1.0E-4F && remDist <= EmberPostureMath.PRECISION_ARRIVE_RADIUS)
                    || elapsed >= PRECISION_STOP_MAX_MS) {
                precisionStopping = false;
                precisionShortStrafing = false;
                npc.clearPrecisionDockClamp();
                latchArrived(npc);
            }
            return;
        }

        if (pendingDeferredRetarget && active && !asyncInFlight && tryConsumeFramePathBudget()) {
            pendingDeferredRetarget = false;
            EmberTargetProjector.ProjectedPoint sp = EmberTargetProjector.projectStartIfTrapped(npc);
            if (sp == null) {
                sp = new EmberTargetProjector.ProjectedPoint(
                        npc.getX(), npc.getY(), EmberTargetProjector.levelOf(npc.getZ()), false);
            }
            submitAsyncPathRequest(npc, sp, goalX, goalY, goalZ);
        }

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
            submitAsyncPathRequest(npc, EmberTargetProjector.projectStartIfTrapped(npc), goalX, goalY, goalZ);
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
            npc.setEmberDesiredSprinting(false);
            npc.setEmberWalkSpeedCap(-1.0F);
            npc.setEmberTrackSpeedScale(1.0F);
            npc.setEmberMotionState(false, 1.0F, EmberParams.TURN_GAIN);
            resetStuckMonitor(npc);
            portalActuator.update(npc, null, 0.0F, 0.0F);
            return;
        }

        if (corridor.advanceAndCheckGoal(npc, cruiseMode && cruiseHoldOpen)) {
            if (cruiseMode) {
                active = false;
                arrivedLatched = false;
                cruiseHoldOpen = false;
                corridor = null;
                smoothedThrottle = 0.0F;
                inRunGear = false;
                liveReasonTag = "CRUISE_END";
                applyIdleHold(npc, false);
            } else if (precisionMode) {
                npc.getDeferredMovement(scratchDeferred);
                precisionShortStrafing = false;
                if (scratchDeferred.getLength() > 1.0E-4F) {
                    precisionStopping = true;
                    precisionStopStartedMs = System.currentTimeMillis();
                    npc.setPrecisionDockClamp(goalX, goalY, goalZ, true);
                    npc.setEmberDesiredRunning(false);
                    npc.setEmberDesiredSprinting(false);
                    applyZeroMoveInput(npc);
                    npc.setEmberMotionState(false, 1.0F, EmberParams.TURN_GAIN);
                } else {
                    npc.clearPrecisionDockClamp();
                    latchArrived(npc);
                }
            } else {
                latchArrived(npc);
            }
            return;
        }

        float dt = deltaSeconds();

        boolean wantSneak = desiredGait == Gait.SNEAK;
        boolean fastForward = isTimeAccelerated();
        boolean wantSprint = !wantSneak && !fastForward && desiredGait == Gait.SPRINT
                && !npc.isDraggingCorpse() && npc.canSprint();
        boolean wantRun = !wantSneak && !fastForward
                && (desiredGait == Gait.RUN || (desiredGait == Gait.SPRINT && !wantSprint))
                && !npc.isDraggingCorpse();
        boolean allowFastGait = wantRun || wantSprint;

        float lMin = EmberParams.LOOKAHEAD_MIN;
        float lMax = Math.max(lMin, EmberParams.LOOKAHEAD_MAX);
        float lookDist = lMin + (lMax - lMin) * Math.max(0.0F, Math.min(1.0F, smoothedThrottle));
        float estTurnRadius = allowFastGait ? 1.46F : 0.45F;
        EmberCorridor.CarrotSample sample = corridor.sampleCarrot(npc, lookDist, estTurnRadius);

        float rawDirX = sample.carrotX() - npc.getX();
        float rawDirY = sample.carrotY() - npc.getY();
        float rawLen = (float) Math.hypot(rawDirX, rawDirY);
        if (rawLen < 1.0E-4F) {
            if (cruiseMode && cruiseHoldOpen) {
                float[] fwd = worldSkeletonForward(npc);
                rawDirX = fwd[0];
                rawDirY = fwd[1];
                rawLen = 1.0F;
            } else {
                if (cruiseMode) {
                    active = false;
                    cruiseHoldOpen = false;
                    corridor = null;
                    applyIdleHold(npc, false);
                } else {
                    latchArrived(npc);
                }
                return;
            }
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
            npc.setEmberDesiredSprinting(false);
            npc.setEmberWalkSpeedCap(-1.0F);
            npc.setEmberTrackSpeedScale(1.0F);
            npc.setEmberMotionState(false, 1.0F, EmberParams.TURN_GAIN);
            applyZeroMoveInput(npc);
            resetStuckMonitor(npc);
            return;
        }

        boolean nearStairs = EmberStairDiag.ENABLED && EmberStairDiag.isNearStairs(npc);
        if (nearStairs) {
            IsoGridSquare diagSq = npc.getCurrentSquare();
            if (diagSq != null && EmberTargetProjector.levelOf(npc.getZ()) != diagSq.getZ()) {
                long nowMs = System.currentTimeMillis();
                if (nowMs - diagLastZMismatchMs >= 400L) {
                    diagLastZMismatchMs = nowMs;
                    EmberStairDiag.log("Z_MISMATCH", String.format(java.util.Locale.US,
                            "%s %s follow=%s node=%s",
                            npc.getNpcId(), EmberStairDiag.fmtPosAndSq(npc),
                            npc.getFollow().getState().name(), formatCorridorNode()));
                }
            }
        }

        boolean bypassAvoidForPrecisionDock = precisionMode && corridor.isAtFinalGoal() && sample.distToGoal() <= 0.65F;
        EmberContextSteering.SteerOutput avoid = bypassAvoidForPrecisionDock
                ? new EmberContextSteering.SteerOutput(rawDirX, rawDirY, 1.0F, false)
                : contextSteering.evaluate(npc, rawDirX, rawDirY, steerLeader, steerLeaderMoving);
        if (avoid.yielding()) {
            if (nearStairs) {
                long nowMs = System.currentTimeMillis();
                if (nowMs - diagLastYieldMs >= 600L) {
                    diagLastYieldMs = nowMs;
                    EmberStairDiag.log("AVOID_YIELD", String.format(java.util.Locale.US,
                            "%s %s carrot=(%.2f,%.2f) node=%s follow=%s near=%s",
                            npc.getNpcId(), EmberStairDiag.fmtPosAndSq(npc),
                            sample.carrotX(), sample.carrotY(),
                            formatCorridorNode(), npc.getFollow().getState().name(),
                            EmberStairDiag.fmtNearNpcs(npc, 1.35F)));
                }
            }
            turn180Latched = false;
            smoothedThrottle = 0.0F;
            inRunGear = false;
            npc.faceLocationF(npc.getX() + rawDirX, npc.getY() + rawDirY);
            applyZeroMoveInput(npc);
            npc.setEmberDesiredRunning(false);
            npc.setEmberDesiredSprinting(false);
            npc.setEmberWalkSpeedCap(-1.0F);
            npc.setEmberTrackSpeedScale(1.0F);
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

        if (nearStairs) {
            boolean reversed = diagHasPrevSteer && (diagPrevSteerX * steerX + diagPrevSteerY * steerY) < -0.35F;
            boolean enteredTurn180 = !diagPrevTurningAround && turningAround;
            if (reversed || enteredTurn180) {
                long nowMs = System.currentTimeMillis();
                if (nowMs - diagLastReversalMs >= 250L) {
                    diagLastReversalMs = nowMs;
                    EmberStairDiag.log("REVERSAL", String.format(java.util.Locale.US,
                            "%s %s prev=(%.2f,%.2f)->steer=(%.2f,%.2f) raw=(%.2f,%.2f) carrot=(%.2f,%.2f) turn180=%s node=%s avoidCap=%.2f follow=%s near=%s",
                            npc.getNpcId(), EmberStairDiag.fmtPosAndSq(npc),
                            diagPrevSteerX, diagPrevSteerY, steerX, steerY, rawDirX, rawDirY,
                            sample.carrotX(), sample.carrotY(), turningAround,
                            formatCorridorNode(), avoid.speedCap(),
                            npc.getFollow().getState().name(), EmberStairDiag.fmtNearNpcs(npc, 1.35F)));
                }
            }
            diagPrevSteerX = steerX;
            diagPrevSteerY = steerY;
            diagHasPrevSteer = true;
            diagPrevTurningAround = turningAround;
        } else {
            resetDiagObservers();
        }

        int curZ = EmberTargetProjector.levelOf(npc.getZ());
        IsoGridSquare curSq = npc.getCurrentSquare();
        boolean onStairs = curSq != null && curSq.HasStairs();
        if (onStairs && allowFastGait) {
            wantSprint = false;
            wantRun = false;
            allowFastGait = false;
        }

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
            if (allowFastGait && absErrRad > Math.toRadians(110.0)) {
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
        float brakeDist = cruiseMode ? 0.0F : EmberParams.BRAKE_DIST;
        boolean inBrakeZone = brakeDist > 0.05F && sample.distToGoal() < brakeDist;
        if (inBrakeZone) {
            alphaBrake = clamp((float) Math.sqrt(sample.distToGoal() / brakeDist), 0.22F, 1.0F);
            reasonTag = "BRAKE";
        }

        float maxThrottle = allowFastGait ? 1.0F : 0.50F;
        float targetThrottle = maxThrottle * Math.min(avoid.speedCap(), Math.min(alphaCurv, Math.min(alphaProx, alphaBrake)));

        if (allowFastGait && smoothedThrottle < 0.58F && !inBrakeZone && avoid.speedCap() > 0.60F) {
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
        if (!allowFastGait) {
            inRunGear = false;
            animSpeedScalar = clamp(smoothedThrottle / 0.50F, 0.10F, 1.0F);
        } else if (!cruiseMode && sample.distToGoal() < Math.min(0.65F, brakeDist) && smoothedThrottle < 0.42F) {
            inRunGear = false;
            animSpeedScalar = clamp(smoothedThrottle / 0.50F, 0.10F, 1.0F);
        } else {
            inRunGear = true;
            float minRunScalar = (inBrakeZone || avoid.speedCap() < 0.60F) ? 0.08F : 0.50F;
            animSpeedScalar = clamp((smoothedThrottle - 0.15F) / 0.85F, minRunScalar, 1.0F);
        }

        boolean inSprintGear = wantSprint && inRunGear && !tightSpace;
        boolean inRunOnlyGear = inRunGear && !inSprintGear;

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

        if (precisionMode && corridor.isAtFinalGoal()) {
            if (!precisionShortStrafing && sample.distToGoal() <= 0.45F && absErrRad > Math.toRadians(35.0)) {
                precisionShortStrafing = true;
            }
            if (precisionShortStrafing || sample.distToGoal() <= 0.60F) {
                npc.setPrecisionDockClamp(goalX, goalY, goalZ, false);
            } else {
                npc.clearPrecisionDockClamp();
            }
        } else if (precisionMode) {
            npc.clearPrecisionDockClamp();
        }

        driveNativeCharacter(
                npc, steerX, steerY, wantSneak, inRunOnlyGear, inSprintGear,
                animSpeedScalar, turnGain, desiredWalkSpeedCap, desiredTrackSpeedScale);
        if (precisionMode) {
            npc.clearManualMovementClamp();
        } else if (EmberParams.END_CLAMP_ENABLED) {
            npc.setManualMovementClamp(sample.clampX(), sample.clampY(), sample.clampZ(), sample.clampIsGoal());
        } else {
            npc.clearManualMovementClamp();
        }

        if (nearStairs) {
            if (diagStallSec <= 0.0F) {
                diagStallX = npc.getX();
                diagStallY = npc.getY();
            }
            diagStallSec += dt;
            if (diagStallSec >= 0.60F) {
                float movedDiag = (float) Math.hypot(npc.getX() - diagStallX, npc.getY() - diagStallY);
                if (movedDiag < 0.08F) {
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - diagLastStallMs >= 700L) {
                        diagLastStallMs = nowMs;
                        EmberStairDiag.log("STALL", String.format(java.util.Locale.US,
                                "%s %s moved=%.3f tag=%s avoidCap=%.2f node=%s carrot=(%.2f,%.2f) follow=%s near=%s",
                                npc.getNpcId(), EmberStairDiag.fmtPosAndSq(npc),
                                movedDiag, liveReasonTag, avoid.speedCap(), formatCorridorNode(),
                                sample.carrotX(), sample.carrotY(), npc.getFollow().getState().name(),
                                EmberStairDiag.fmtNearNpcs(npc, 1.35F)));
                    }
                }
                diagStallSec = 0.0F;
                diagStallX = npc.getX();
                diagStallY = npc.getY();
            }
        }

        if (!onStairs && checkStuck(npc, dt)) {
            handleBlockedOrStuck(npc, "STUCK_REPATH");
        }
    }

    public void onRunningChanged(IsoNPC npc, boolean running) {
        if (npc != null) {
            npc.setRunningMode(running);
        }
        this.desiredGait = running ? Gait.RUN : Gait.WALK;
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
        clearPrecisionState(npc, false);
        active = false;
        arrivedLatched = false;
        cruiseMode = false;
        cruiseHoldOpen = false;
        pendingDeferredRetarget = false;
        steerLeader = null;
        steerLeaderMoving = false;
        desiredGait = Gait.WALK;
        desiredWalkSpeedCap = -1.0F;
        desiredTrackSpeedScale = 1.0F;
        corridor = null;
        pendingEscapeStart = null;
        smoothedThrottle = 0.0F;
        inRunGear = false;
        turn180Latched = false;
        repathStreak = 0;
        resetDiagObservers();
        contextSteering.reset();
        portalActuator.reset();
        cancelAsyncRequest(npc);
        if (npc != null) {
            npc.clearManualMovementClamp();
            npc.setSneaking(false);
            npc.setSprinting(false);
            npc.setEmberDesiredRunning(false);
            npc.setEmberDesiredSprinting(false);
            npc.setEmberWalkSpeedCap(-1.0F);
            npc.setEmberTrackSpeedScale(1.0F);
            npc.setEmberMotionState(false, 1.0F, EmberParams.TURN_GAIN);
            npc.setEmberControlActive(false);
            npc.setNativeSmoothPathUnlock(false);
            applyIdleHold(npc, false);
            npc.discardRootMotion();
        }
    }

    private void submitAsyncPathRequest(
            IsoNPC npc, EmberTargetProjector.ProjectedPoint startPt,
            float targetX, float targetY, int targetZ) {
        submitAsyncPathRequestInternal(npc, startPt, targetX, targetY, targetZ, null);
    }

    private void submitAsyncPathRequestInternal(
            IsoNPC npc, EmberTargetProjector.ProjectedPoint startPt,
            float targetX, float targetY, int targetZ, PrecisionTarget[] candidates) {
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
            asyncInFlight = true;
            asyncGoalX = targetX;
            asyncGoalY = targetY;
            asyncGoalZ = targetZ;
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
            PathFindRequest req = PathfindNative.instance.addRequest(cb, npc, sx, sy, sz, targetX, targetY, targetZ);
            if (req != null && candidates != null && candidates.length > 1) {
                for (int i = 1; i < candidates.length; i++) {
                    req.targetXyz.add(candidates[i].pathX());
                    req.targetXyz.add(candidates[i].pathY());
                    req.targetXyz.add((float) candidates[i].z());
                }
            }
        } else {
            PolygonalMap2.instance.addRequest(cb, npc, sx, sy, sz, targetX, targetY, targetZ);
        }
    }

    private void pollAsyncPathResult(IsoNPC npc) {
        float[] raw = null;
        boolean failed = false;
        float reqGoalX = goalX;
        float reqGoalY = goalY;
        int reqGoalZ = goalZ;
        synchronized (asyncLock) {
            if (npc == pendingNpc) {
                if (readyRawXyz != null) {
                    raw = readyRawXyz;
                    reqGoalX = asyncGoalX;
                    reqGoalY = asyncGoalY;
                    reqGoalZ = asyncGoalZ;
                    readyRawXyz = null;
                    pendingNpc = null;
                    asyncInFlight = false;
                } else if (asyncFailed) {
                    failed = true;
                    reqGoalX = asyncGoalX;
                    reqGoalY = asyncGoalY;
                    reqGoalZ = asyncGoalZ;
                    asyncFailed = false;
                    pendingNpc = null;
                    asyncInFlight = false;
                }
            }
        }
        if (raw != null) {
            EmberCorridor built;
            if (precisionMode && precisionCandidates != null && precisionCandidates.length > 0 && raw.length >= 3) {
                float endX = raw[raw.length - 3];
                float endY = raw[raw.length - 2];
                PrecisionTarget matched = resolveMatchedPrecisionCandidate(endX, endY, precisionCandidates);
                lockActivePrecisionTarget(matched);
                built = EmberCorridor.fromRawCoordsPrecision(
                        npc, raw, pendingEscapeStart,
                        matched.pathX(), matched.pathY(),
                        matched.exactX(), matched.exactY(), matched.z());
                if (built != null) {
                    this.corridor = built;
                    activateShortStrafeIfEligible(npc);
                }
            } else {
                built = EmberCorridor.fromRawCoords(npc, raw, pendingEscapeStart, reqGoalX, reqGoalY, reqGoalZ);
            }
            if (built != null) {
                if (EmberStairDiag.ENABLED && EmberStairDiag.isNearStairs(npc)) {
                    String oldNode = this.corridor != null ? this.corridor.formatCurrentNode() : "NONE";
                    boolean escAdj = pendingEscapeStart != null && pendingEscapeStart.adjusted();
                    EmberStairDiag.log("CORRIDOR_SWAP", String.format(java.util.Locale.US,
                            "%s %s esc=%s follow=%s old=%s new=[%s]",
                            npc.getNpcId(), EmberStairDiag.fmtPosAndSq(npc), escAdj,
                            npc.getFollow().getState().name(), oldNode, built.formatHeadSummary()));
                }
                this.corridor = built;
                this.repathStreak = 0;
                this.failedGoalUntilMs = 0L;
                resetStuckMonitor(npc);
            } else if (!cruiseMode || this.corridor == null) {
                failTrip(npc, Status.NO_PATH, "EMPTY_CORRIDOR");
            }
        } else if (failed) {
            this.failedGoalTileX = (int) Math.floor(reqGoalX);
            this.failedGoalTileY = (int) Math.floor(reqGoalY);
            this.failedGoalZ = reqGoalZ;
            this.failedGoalUntilMs = System.currentTimeMillis() + FAILED_GOAL_COOLDOWN_MS;
            if (!cruiseMode || this.corridor == null) {
                failTrip(npc, Status.NO_PATH, "NO_PATH");
            }
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
        precisionShortStrafing = false;
        precisionStopping = false;
        if (npc != null) {
            npc.clearPrecisionDockClamp();
        }
        applyZeroMoveInput(npc);
        npc.setEmberTrackSpeedScale(1.0F);
        npc.setEmberMotionState(false, 0.30F, EmberParams.TURN_GAIN);
        EmberTargetProjector.ProjectedPoint startPt = EmberTargetProjector.projectStartIfTrapped(npc);
        if (precisionMode && precisionCandidates != null && precisionCandidates.length > 0) {
            PrecisionTarget primary = precisionCandidates[0];
            submitAsyncPathRequestInternal(npc, startPt, primary.pathX(), primary.pathY(), primary.z(), precisionCandidates);
        } else {
            submitAsyncPathRequest(npc, startPt, goalX, goalY, goalZ);
        }
    }

    private void latchArrived(IsoNPC npc) {
        active = false;
        arrivedLatched = true;
        arrivedAtMs = System.currentTimeMillis();
        cruiseHoldOpen = false;
        steerLeader = null;
        steerLeaderMoving = false;
        desiredWalkSpeedCap = -1.0F;
        desiredTrackSpeedScale = 1.0F;
        corridor = null;
        smoothedThrottle = 0.0F;
        inRunGear = false;
        precisionShortStrafing = false;
        precisionStopping = false;
        resetDiagObservers();
        liveReasonTag = "ARRIVED";
        applyIdleHold(npc, true);
        synchronized (this) {
            status = Status.ARRIVED;
            lastReason = "REACHED";
            settledSeq = moveSeq;
        }
    }

    private void failTrip(IsoNPC npc, Status failStatus, String reason) {
        clearPrecisionState(npc, false);
        active = false;
        arrivedLatched = false;
        cruiseMode = false;
        cruiseHoldOpen = false;
        steerLeader = null;
        steerLeaderMoving = false;
        desiredWalkSpeedCap = -1.0F;
        desiredTrackSpeedScale = 1.0F;
        corridor = null;
        smoothedThrottle = 0.0F;
        inRunGear = false;
        resetDiagObservers();
        liveReasonTag = "FAILED:" + reason;
        applyIdleHold(npc, false);
        synchronized (this) {
            status = failStatus;
            lastReason = reason;
            settledSeq = moveSeq;
        }
    }

    private void clearPrecisionState(IsoNPC npc, boolean resetResolvedTag) {
        this.precisionMode = false;
        this.precisionCandidates = null;
        this.activePrecisionTarget = null;
        this.ignoreCollisionSquare = null;
        this.precisionShortStrafing = false;
        this.precisionStopping = false;
        this.precisionStopStartedMs = 0L;
        if (resetResolvedTag) {
            synchronized (this) {
                this.resolvedPrecisionTag = -1;
            }
        }
        if (npc != null) {
            npc.clearPrecisionDockClamp();
        }
    }

    private void lockActivePrecisionTarget(PrecisionTarget target) {
        this.activePrecisionTarget = target;
        if (target.ignoreSquare() != null) {
            this.ignoreCollisionSquare = target.ignoreSquare();
        }
        this.goalX = target.exactX();
        this.goalY = target.exactY();
        this.goalZ = target.z();
        synchronized (this) {
            this.resolvedPrecisionTag = target.tag();
        }
    }

    private void activateShortStrafeIfEligible(IsoNPC npc) {
        if (!precisionMode || corridor == null || npc == null) {
            return;
        }
        if (corridor.getNodeCount() == 1
                && corridor.isAtFinalGoal()
                && Math.hypot(goalX - npc.getX(), goalY - npc.getY()) < EmberPostureMath.PRECISION_SHORT_STRAFE_DIST) {
            this.precisionShortStrafing = true;
            npc.setPrecisionDockClamp(goalX, goalY, goalZ, false);
        }
    }

    private static PrecisionTarget resolveMatchedPrecisionCandidate(
            float endX, float endY, PrecisionTarget[] candidates) {
        int n = candidates.length;
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = candidates[i].pathX();
            ys[i] = candidates[i].pathY();
        }
        int matched = EmberPostureMath.matchNearestPrecisionCandidate(endX, endY, xs, ys, n, 2.25F);
        return candidates[matched >= 0 ? matched : 0];
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
        npc.setEmberDesiredSprinting(false);
        npc.setEmberWalkSpeedCap(-1.0F);
        npc.setEmberTrackSpeedScale(1.0F);
        applyZeroMoveInput(npc);
        npc.setEmberMotionState(false, 1.0F, EmberParams.TURN_GAIN);
        if (clampGoal && EmberParams.END_CLAMP_ENABLED) {
            npc.setManualMovementClamp(goalX, goalY, goalZ, true);
        } else {
            npc.clearManualMovementClamp();
        }
        try { npc.setVariable("bMoving", false); } catch (Throwable ignored) {}
        try { npc.setVariable("bPathfind", false); } catch (Throwable ignored) {}
    }

    private static void driveNativeCharacter(
            IsoNPC npc, float dirX, float dirY,
            boolean sneak, boolean runGear, boolean sprintGear,
            float animScalar, float turnGain, float walkSpeedCap, float trackSpeedScale) {
        cancelVanillaPathBehavior(npc);
        if (!npc.isAiming() && !npc.isStrafing()) {
            npc.setDirectionAngle((float) Math.toDegrees(Math.atan2(dirY, dirX)));
        }
        if (npc.isStrafing()) {
            npc.set(PlayerStrafeState.STRAFE_SPEED, 1.0F);
            npc.setVariable("StrafeSpeed", 1.0F);
        }
        npc.playerMoveDir.set(dirX, dirY);
        npc.setJustMoved(true);
        npc.setMoving(true);
        npc.setSneaking(sneak);
        npc.setEmberDesiredRunning(runGear);
        npc.setEmberDesiredSprinting(sprintGear);
        npc.setEmberWalkSpeedCap(walkSpeedCap);
        npc.setEmberTrackSpeedScale(trackSpeedScale);
        npc.setEmberMotionState(true, animScalar, turnGain);
        npc.setRunning(runGear);
        npc.setSprinting(sprintGear);

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
            asyncInFlight = false;
        }
        if (npc != null) {
            if (PathfindNative.useNativeCode && PathfindNative.instance != null) {
                PathfindNative.instance.cancelRequest(npc);
            } else if (PolygonalMap2.instance != null) {
                PolygonalMap2.instance.cancelRequest(npc);
            }
        }
    }

    private static boolean isTimeAccelerated() {
        try {
            return GameTime.instance != null && GameTime.instance.getMultiplier() > 1.25F;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean checkStuck(IsoNPC npc, float dt) {
        stuckTimerSec += dt;
        if (stuckTimerSec < STUCK_CHECK_WINDOW_SEC) {
            return false;
        }
        float moved = (float) Math.hypot(npc.getX() - stuckAnchorX, npc.getY() - stuckAnchorY);
        float minDisp = STUCK_MIN_DISP
                * Math.max(0.45F, desiredTrackSpeedScale)
                * ((desiredWalkSpeedCap >= 0.0F && desiredWalkSpeedCap < 0.25F) ? 0.75F : 1.0F);
        resetStuckMonitor(npc);
        return moved < minDisp;
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

    static float deltaSeconds() {
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
