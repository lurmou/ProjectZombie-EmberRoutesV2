package emberroutes.npc;

import emberroutes.npc.brain.VehicleCoRideHelper;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;
import zombie.pathfind.PolygonalMap2;
import zombie.vehicles.BaseVehicle;

/**
 * 纯内存瞬态步行跟随控制器（零持久账本、不跨帧缓存 IsoPlayer 引用）：
 * - 基于 {@link SquadTacticalConfig} 与 {@link EmberFormationMatcher} 支持四阵型（楔形/纵队/横排/环卫）× 三间距实时切换；
 * - 滞回停靠死区 + 视线净空校验（绝不隔墙傻停）、静止堵门迎面让路、楼梯前单人排队；
 * - 全步态跟随（Sneak / Walk / Run / Sprint）+ 同态 WalkSpeed 对齐消橡皮筋；
 * - 支持原地驻守（HOLD_POSITION）与车辆停稳/慢行时溢出队员车外安全环形警戒（超速自动切换 WAIT_VEHICLE）。
 */
public final class EmberFollowController {
    public enum FollowState {
        INACTIVE,
        FOLLOWING,
        WAITING,
        YIELDING,
        WAIT_VEHICLE,
        STAIR_QUEUE,
        BLOCKED
    }

    private static final float HOLD_ENTER_DIST = 0.60F;
    private static final float HOLD_EXIT_DIST = 1.10F;
    private static final long PLAN_INTERVAL_MS = 100L;
    private static final long RETARGET_INTERVAL_MS = 220L;
    private static final float RETARGET_SHIFT_DIST = 0.50F;
    private static final float STATIC_YIELD_TRIGGER_DIST = 1.05F;
    private static final long STATIC_YIELD_HOLD_MS = 1000L;
    private static final float PORTAL_AUTOCLOSE_TEAM_RADIUS = 2.60F;

    private final EmberFollowGaitControl gaitControl = new EmberFollowGaitControl();

    private boolean enabled;
    private int leaderPlayerIndex;
    private int requestedSlot = -1;
    private int assignedRank = -1;
    private FollowState state = FollowState.INACTIVE;

    private boolean holding;
    private boolean catchUpDirectLatched;

    private float lastLeaderX;
    private float lastLeaderY;
    private int lastLeaderZ = Integer.MIN_VALUE;
    private float prevSampleLeaderX;
    private float prevSampleLeaderY;
    private int prevSampleLeaderZ = Integer.MIN_VALUE;
    private float prevSampleNpcX;
    private float prevSampleNpcY;
    private int prevSampleNpcZ = Integer.MIN_VALUE;
    private float speedWindowDt;
    private float accumLeaderStep;
    private float accumNpcStep;
    private float lastDirX;
    private float lastDirY = 1.0F;
    private long leaderMovedAtMs;
    private boolean leaderMoving;

    private long nextPlanMs;
    private long lastRetargetMs;
    private float plannedTargetX;
    private float plannedTargetY;
    private int plannedTargetZ = Integer.MIN_VALUE;
    private boolean hasPlannedTarget;
    private float lastSentX;
    private float lastSentY;
    private int lastSentZ = Integer.MIN_VALUE;

    private long yieldUntilMs;
    private float yieldGoalX;
    private float yieldGoalY;
    private int yieldGoalZ;

    private static final float[] TRAIL_SIDE_SQUEEZE = {1.0F, 0.50F, 0.0F};

    private long lastAppliedConfigVersion = -1L;
    private boolean outsideVehicleForming;
    private float assignedSlotX;
    private float assignedSlotY;
    private int assignedSlotZ = Integer.MIN_VALUE;
    private boolean hasAssignedWorldSlot;
    private boolean plannedSlotValidated;
    private boolean congestionSettled;
    private float settledAnchorX;
    private float settledAnchorY;
    private int settledAnchorZ = Integer.MIN_VALUE;
    private float settleWatchX;
    private float settleWatchY;
    private float settleStallSec;
    private boolean settleWatchValid;
    private final float[] trailScratch = new float[4];
    private final float[] slotScratch = new float[2];

    private long diagLastQueueMs;
    private long diagLastHoldMs;
    private long diagLastRetargetMs;

    private void resetCongestionSettle() {
        congestionSettled = false;
        settledAnchorZ = Integer.MIN_VALUE;
        settleStallSec = 0.0F;
        settleWatchValid = false;
    }

    static boolean updateHysteresis(boolean current, float dist, float enterDist, float exitDist) {
        if (!Float.isFinite(dist)) {
            return false;
        }
        return current ? (dist > exitDist) : (dist >= enterDist);
    }

    static boolean updateHoldHysteresis(boolean currentHolding, float dist, float enterDist, float exitDist) {
        if (!Float.isFinite(dist)) {
            return false;
        }
        return currentHolding ? (dist <= exitDist) : (dist <= enterDist);
    }

    public void start(int playerIndex, int slot) {
        this.enabled = true;
        this.leaderPlayerIndex = (playerIndex >= 0 && playerIndex < IsoPlayer.MAX) ? playerIndex : 0;
        this.requestedSlot = slot;
        this.assignedRank = -1;
        this.hasAssignedWorldSlot = false;
        this.assignedSlotZ = Integer.MIN_VALUE;
        this.plannedSlotValidated = false;
        this.state = FollowState.FOLLOWING;
        this.holding = false;
        this.catchUpDirectLatched = false;
        this.lastLeaderZ = Integer.MIN_VALUE;
        this.prevSampleLeaderZ = Integer.MIN_VALUE;
        this.prevSampleNpcZ = Integer.MIN_VALUE;
        this.speedWindowDt = 0.0F;
        this.accumLeaderStep = 0.0F;
        this.accumNpcStep = 0.0F;
        this.leaderMoving = false;
        this.nextPlanMs = 0L;
        this.lastRetargetMs = 0L;
        this.hasPlannedTarget = false;
        this.plannedTargetZ = Integer.MIN_VALUE;
        this.lastSentZ = Integer.MIN_VALUE;
        this.yieldUntilMs = 0L;
        this.lastAppliedConfigVersion = -1L;
        this.outsideVehicleForming = false;
        resetCongestionSettle();
        this.gaitControl.reset();
    }

    public void stop(IsoNPC npc, String reason) {
        stop(npc, true, reason);
    }

    public void stop(IsoNPC npc, boolean stopMotion, String reason) {
        boolean wasEnabled = this.enabled || this.state != FollowState.INACTIVE;
        this.enabled = false;
        this.assignedRank = -1;
        this.hasAssignedWorldSlot = false;
        this.assignedSlotZ = Integer.MIN_VALUE;
        this.plannedSlotValidated = false;
        this.holding = false;
        this.catchUpDirectLatched = false;
        this.hasPlannedTarget = false;
        this.yieldUntilMs = 0L;
        this.prevSampleLeaderZ = Integer.MIN_VALUE;
        this.prevSampleNpcZ = Integer.MIN_VALUE;
        this.speedWindowDt = 0.0F;
        this.accumLeaderStep = 0.0F;
        this.accumNpcStep = 0.0F;
        this.outsideVehicleForming = false;
        this.lastAppliedConfigVersion = -1L;
        resetCongestionSettle();
        this.gaitControl.reset();
        this.state = FollowState.INACTIVE;
        if (wasEnabled && stopMotion && npc != null) {
            EmberLocomotionController loco = npc.getLocomotion();
            loco.setFollowSteerHint(null, false);
            loco.setPortalPolicy(true, true, 0.0F);
            loco.setLeaderPortalGate(false, 0.0F, 0.0F, 0);
            loco.holdFollowIdle(npc, false);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getLeaderPlayerIndex() {
        return leaderPlayerIndex;
    }

    public int getRequestedSlot() {
        return requestedSlot;
    }

    int getAssignedRank() {
        return assignedRank;
    }

    void setAssignedRank(int assignedRank) {
        this.assignedRank = assignedRank;
    }

    boolean hasAssignedWorldSlot() {
        return hasAssignedWorldSlot;
    }

    float getAssignedSlotX() {
        return assignedSlotX;
    }

    float getAssignedSlotY() {
        return assignedSlotY;
    }

    int getAssignedSlotZ() {
        return assignedSlotZ;
    }

    void setAssignedSlot(int assignedRank, float x, float y, int z, boolean valid) {
        this.assignedRank = assignedRank;
        this.assignedSlotX = x;
        this.assignedSlotY = y;
        this.assignedSlotZ = z;
        this.hasAssignedWorldSlot = valid;
    }

    float getLastDirX() {
        return lastDirX;
    }

    float getLastDirY() {
        return lastDirY;
    }

    public FollowState getState() {
        return state;
    }

    public void beforeLocomotion(IsoNPC npc) {
        if (!enabled || npc == null || npc.isDead()) {
            return;
        }

        long cfgVer = SquadTacticalConfig.getVersion();
        if (cfgVer != lastAppliedConfigVersion) {
            lastAppliedConfigVersion = cfgVer;
            holding = false;
            hasPlannedTarget = false;
            plannedSlotValidated = false;
            hasAssignedWorldSlot = false;
            nextPlanMs = 0L;
            lastRetargetMs = 0L;
            resetCongestionSettle();
        }

        EmberLocomotionController loco = npc.getLocomotion();
        IsoPlayer leader = resolveLeader(leaderPlayerIndex);

        if (npc.getVehicle() != null || !npc.getCharacterActions().isEmpty()) {
            prevSampleLeaderZ = Integer.MIN_VALUE;
            prevSampleNpcZ = Integer.MIN_VALUE;
            speedWindowDt = 0.0F;
            accumLeaderStep = 0.0F;
            accumNpcStep = 0.0F;
            resetCongestionSettle();
            loco.setFollowSteerHint(leader, false);
            return;
        }

        SquadTacticalConfig.OrderMode orderMode = SquadTacticalConfig.getOrderMode();
        boolean isHoldMode = (orderMode == SquadTacticalConfig.OrderMode.HOLD_POSITION && SquadTacticalConfig.hasHoldAnchor());
        FormationType formation = SquadTacticalConfig.getFormation();
        FormationSpacing spacing = SquadTacticalConfig.getSpacing();

        float lx;
        float ly;
        int lz;
        boolean outsideVehMode = false;
        float minVehSafeRadius = 0.0F;

        if (isHoldMode) {
            outsideVehicleForming = false;
            if (npc.getCurrentSquare() == null) {
                holding = false;
                resetCongestionSettle();
                gaitControl.reset();
                loco.setFollowSteerHint(null, false);
                state = FollowState.WAITING;
                loco.holdFollowIdle(npc, false);
                return;
            }
            lx = SquadTacticalConfig.getHoldX();
            ly = SquadTacticalConfig.getHoldY();
            lz = SquadTacticalConfig.getHoldZ();
            lastDirX = SquadTacticalConfig.getHoldDirX();
            lastDirY = SquadTacticalConfig.getHoldDirY();
            leaderMoving = false;
            gaitControl.resetLeaderSpeed();
        } else {
            if (leader == null || leader.isDead() || leader.getCell() != npc.getCell()) {
                holding = false;
                outsideVehicleForming = false;
                resetCongestionSettle();
                prevSampleLeaderZ = Integer.MIN_VALUE;
                prevSampleNpcZ = Integer.MIN_VALUE;
                speedWindowDt = 0.0F;
                accumLeaderStep = 0.0F;
                accumNpcStep = 0.0F;
                gaitControl.reset();
                loco.setFollowSteerHint(null, false);
                state = FollowState.WAITING;
                loco.holdFollowIdle(npc, false);
                return;
            }
            BaseVehicle leaderVeh = leader.getVehicle();
            if (leaderVeh != null) {
                float vehSpeed = Math.abs(leaderVeh.getCurrentSpeedKmHour());
                outsideVehicleForming = outsideVehicleForming ? (vehSpeed <= 18.0F) : (vehSpeed <= 15.0F);
                if (!outsideVehicleForming || leaderVeh.getSquare() == null) {
                    holding = false;
                    yieldUntilMs = 0L;
                    resetCongestionSettle();
                    prevSampleLeaderZ = Integer.MIN_VALUE;
                    prevSampleNpcZ = Integer.MIN_VALUE;
                    speedWindowDt = 0.0F;
                    accumLeaderStep = 0.0F;
                    accumNpcStep = 0.0F;
                    gaitControl.reset();
                    loco.setFollowSteerHint(null, false);
                    state = FollowState.WAIT_VEHICLE;
                    loco.holdFollowIdle(npc, false);
                    return;
                }
                outsideVehMode = true;
                minVehSafeRadius = VehicleCoRideHelper.computeVehicleSafeRadius(leaderVeh) + 1.0F;
                lx = leaderVeh.getX();
                ly = leaderVeh.getY();
                lz = EmberTargetProjector.levelOf(leaderVeh.getZ());
            } else {
                outsideVehicleForming = false;
                if (leader.getCurrentSquare() == null) {
                    holding = false;
                    resetCongestionSettle();
                    gaitControl.reset();
                    loco.setFollowSteerHint(null, false);
                    state = FollowState.WAITING;
                    loco.holdFollowIdle(npc, false);
                    return;
                }
                lx = leader.getX();
                ly = leader.getY();
                lz = EmberTargetProjector.levelOf(leader.getZ());
            }
        }

        long now = System.currentTimeMillis();
        float simDt = EmberLocomotionController.deltaSeconds();
        if (!isHoldMode) {
            float leaderStep = 0.0F;
            if (prevSampleLeaderZ == lz) {
                leaderStep = (float) Math.hypot(lx - prevSampleLeaderX, ly - prevSampleLeaderY);
                if (leaderStep > 2.5F) {
                    gaitControl.resetLeaderSpeed();
                    speedWindowDt = 0.0F;
                    accumLeaderStep = 0.0F;
                    accumNpcStep = 0.0F;
                    leaderStep = 0.0F;
                }
            } else if (prevSampleLeaderZ != Integer.MIN_VALUE) {
                gaitControl.resetLeaderSpeed();
                speedWindowDt = 0.0F;
                accumLeaderStep = 0.0F;
                accumNpcStep = 0.0F;
            }
            prevSampleLeaderX = lx;
            prevSampleLeaderY = ly;
            prevSampleLeaderZ = lz;

            int npcLvl = EmberTargetProjector.levelOf(npc.getZ());
            float npcStep = 0.0F;
            if (prevSampleNpcZ == npcLvl) {
                npcStep = (float) Math.hypot(npc.getX() - prevSampleNpcX, npc.getY() - prevSampleNpcY);
                if (npcStep > 2.5F) {
                    npcStep = 0.0F;
                }
            }
            prevSampleNpcX = npc.getX();
            prevSampleNpcY = npc.getY();
            prevSampleNpcZ = npcLvl;

            speedWindowDt += simDt;
            accumLeaderStep += leaderStep;
            accumNpcStep += npcStep;
            if (speedWindowDt >= 0.06F) {
                gaitControl.sampleSpeeds(accumLeaderStep, accumNpcStep, speedWindowDt);
                speedWindowDt = 0.0F;
                accumLeaderStep = 0.0F;
                accumNpcStep = 0.0F;
            }

            EmberLeaderTrail trail = EmberLeaderTrail.forLeader(leaderPlayerIndex, leader);
            trail.record(lx, ly, lz);
            updateLeaderMotion(leader, lx, ly, lz, now);

            if (outsideVehMode && leader.getVehicle() != null
                    && Math.abs(leader.getVehicle().getCurrentSpeedKmHour()) <= 0.80F) {
                leaderMoving = false;
                gaitControl.resetLeaderSpeed();
            } else if (leaderMoving && (now - leaderMovedAtMs) >= 240L) {
                leaderMoving = false;
                gaitControl.resetLeaderSpeed();
            }
            loco.setFollowSteerHint(leader, leaderMoving);
        } else {
            prevSampleLeaderZ = Integer.MIN_VALUE;
            prevSampleNpcZ = Integer.MIN_VALUE;
            speedWindowDt = 0.0F;
            accumLeaderStep = 0.0F;
            accumNpcStep = 0.0F;
            loco.setFollowSteerHint(null, false);
        }

        EmberLeaderTrail trail = (!isHoldMode && leader != null)
                ? EmberLeaderTrail.forLeader(leaderPlayerIndex, leader) : null;
        boolean sneakIntent = !isHoldMode && !outsideVehMode && leader != null
                && gaitControl.updateSneakIntent(leader.isSneaking(), now);

        if (EmberPortalActuator.isInNativeTraversal(npc)) {
            state = FollowState.FOLLOWING;
            return;
        }

        int nz = EmberTargetProjector.levelOf(npc.getZ());
        boolean sameFloor = (nz == lz) && (isHoldMode || outsideVehMode || Math.abs(npc.getZ() - leader.getZ()) <= 0.20F);
        float distToAnchor = (float) Math.hypot(lx - npc.getX(), ly - npc.getY());
        boolean clearLosToAnchor = sameFloor && (outsideVehMode
                ? (!hasPlannedTarget || !isLineBlocked(npc, npc.getX(), npc.getY(), plannedTargetX, plannedTargetY, lz))
                : !isLineBlocked(npc, npc.getX(), npc.getY(), lx, ly, lz));
        boolean allowLeaderVault = !clearLosToAnchor;

        loco.setPortalPolicy(allowLeaderVault, allowLeaderVault, PORTAL_AUTOCLOSE_TEAM_RADIUS);
        loco.setLeaderPortalGate(allowLeaderVault, lx, ly, lz);

        int myRank = (requestedSlot >= 0) ? requestedSlot : NpcManager.resolveEffectiveFollowRank(npc, leaderPlayerIndex);
        int squadCount = Math.max(1, NpcManager.getActiveFollowCount(leaderPlayerIndex));
        float nominalRadius = EmberFormationMatcher.slotNominalRadius(
                formation, myRank, squadCount, spacing.multiplier(), slotScratch);
        if (outsideVehMode) {
            nominalRadius = Math.max(nominalRadius, minVehSafeRadius);
        }

        IsoGridSquare curSq = npc.getCurrentSquare();
        boolean onStairs = curSq != null && curSq.HasStairs();
        boolean nearStairs = EmberStairDiag.isNearStairs(npc);
        String stairBlocker = (!onStairs && !sameFloor && !isHoldMode)
                ? NpcManager.describeHigherRankFollowerOnStairsAhead(npc, leaderPlayerIndex, myRank)
                : null;
        if (stairBlocker != null) {
            if (EmberStairDiag.ENABLED && now - diagLastQueueMs >= 800L) {
                diagLastQueueMs = now;
                EmberStairDiag.log("STAIR_QUEUE", String.format(java.util.Locale.US,
                        "%s(r%d) blockedBy=%s %s near=%s",
                        npc.getNpcId(), myRank, stairBlocker,
                        EmberStairDiag.fmtPosAndSq(npc), EmberStairDiag.fmtNearNpcs(npc, 1.35F)));
            }
            holding = false;
            state = FollowState.STAIR_QUEUE;
            loco.holdFollowIdle(npc, sneakIntent);
            return;
        }

        if (yieldUntilMs > now) {
            float dYield = (float) Math.hypot(yieldGoalX - npc.getX(), yieldGoalY - npc.getY());
            if (sameFloor && yieldGoalZ == nz && loco.isMoving() && dYield > 0.25F) {
                state = FollowState.YIELDING;
                loco.setLiveVelocityControl(
                        npc,
                        sneakIntent ? EmberLocomotionController.Gait.SNEAK : EmberLocomotionController.Gait.WALK,
                        -1.0F, 1.0F, false);
                return;
            }
            yieldUntilMs = 0L;
        }

        boolean realLeaderOnFoot = leader != null && !leader.isDead() && leader.getVehicle() == null
                && leader.getCell() == npc.getCell()
                && EmberTargetProjector.levelOf(leader.getZ()) == nz;
        if (!onStairs && realLeaderOnFoot && !outsideVehMode) {
            float rlx = leader.getX();
            float rly = leader.getY();
            float distReal = (float) Math.hypot(rlx - npc.getX(), rly - npc.getY());
            boolean yieldCandidate = (!isHoldMode && holding && sameFloor && leaderMoving && distReal <= STATIC_YIELD_TRIGGER_DIST)
                    || distReal <= 0.95F;
            if (yieldCandidate) {
                float minApproachDot = (!isHoldMode && holding) ? 0.45F : 0.55F;
                if (tryStartStaticYield(npc, loco, rlx, rly, nz, sneakIntent, now, minApproachDot)) {
                    return;
                }
            }
        }

        boolean staleStairExitSlot = sameFloor && hasPlannedTarget && plannedTargetZ == lz
                && EmberStairDiag.isStairOrExitTile((int) Math.floor(plannedTargetX), (int) Math.floor(plannedTargetY), lz)
                && Math.hypot(plannedTargetX - lx, plannedTargetY - ly) > 1.15F;
        if (!hasPlannedTarget || now >= nextPlanMs || plannedTargetZ != lz || staleStairExitSlot) {
            nextPlanMs = now + PLAN_INTERVAL_MS;
            planTarget(
                    npc, trail, lx, ly, lz, sameFloor, distToAnchor, myRank,
                    squadCount, formation, spacing, isHoldMode, outsideVehMode, minVehSafeRadius, nominalRadius);
        }

        float dxSlot = hasPlannedTarget ? (plannedTargetX - npc.getX()) : (lx - npc.getX());
        float dySlot = hasPlannedTarget ? (plannedTargetY - npc.getY()) : (ly - npc.getY());
        float distToSlot = (float) Math.hypot(dxSlot, dySlot);
        float alongLag = dxSlot * lastDirX + dySlot * lastDirY;
        float signedErr = (!sameFloor || catchUpDirectLatched || !leaderMoving)
                ? distToSlot
                : (alongLag >= 0.0F ? distToSlot : alongLag);

        float vCmd = gaitControl.computeCommandSpeed(signedErr);
        float effectiveDistToLeader = EmberFollowGaitControl.effectiveDistToLeader(nominalRadius, distToAnchor);
        EmberFollowGaitControl.Gear gear = gaitControl.updateGear(sneakIntent, effectiveDistToLeader, vCmd, now);
        EmberLocomotionController.Gait desiredGait = mapGearToGait(gear);

        if (congestionSettled) {
            boolean staySettled = !leaderMoving && sameFloor && !onStairs
                    && settledAnchorZ == lz
                    && Math.hypot(lx - settledAnchorX, ly - settledAnchorY) <= 0.60F
                    && EmberTargetProjector.canStandClean(npc, npc.getX(), npc.getY(), nz);
            if (staySettled) {
                holding = true;
                state = FollowState.WAITING;
                loco.holdFollowIdle(npc, sneakIntent);
                return;
            }
            resetCongestionSettle();
        }

        boolean wasHolding = holding;
        boolean allowStairAreaHold = !onStairs && (!nearStairs || distToAnchor <= 1.25F);
        boolean canHoldSlot = plannedSlotValidated || clearLosToAnchor;
        if (allowStairAreaHold && !leaderMoving && sameFloor && hasPlannedTarget && plannedTargetZ == nz
                && updateHoldHysteresis(holding, distToSlot, HOLD_ENTER_DIST, HOLD_EXIT_DIST)
                && canHoldSlot) {
            logHoldNearStair(npc, myRank, "STOP_HOLD", lx, ly, lz, distToSlot, clearLosToAnchor, now);
            holding = true;
            state = FollowState.WAITING;
            loco.holdFollowIdle(npc, sneakIntent);
            return;
        }
        if (allowStairAreaHold && !leaderMoving && sameFloor && hasPlannedTarget && plannedTargetZ == nz
                && !loco.isMoving() && distToSlot <= EmberParams.ARRIVE_RADIUS) {
            logHoldNearStair(npc, myRank, "ARRIVED_HOLD", lx, ly, lz, distToSlot, clearLosToAnchor, now);
            state = FollowState.WAITING;
            loco.holdFollowIdle(npc, sneakIntent);
            return;
        }
        if (allowStairAreaHold && leaderMoving && sameFloor && clearLosToAnchor) {
            boolean stayHeldOnTinyNudge = holding
                    && distToSlot <= 0.55F
                    && distToAnchor <= nominalRadius + 0.55F;
            boolean creepPause = stayHeldOnTinyNudge || (holding
                    ? (alongLag < -0.15F && vCmd < 0.35F)
                    : (alongLag < -0.55F && vCmd < 0.25F));
            if (creepPause) {
                logHoldNearStair(npc, myRank, "CREEP_PAUSE", lx, ly, lz, distToSlot, clearLosToAnchor, now);
                holding = true;
                state = FollowState.WAITING;
                loco.holdFollowIdle(npc, sneakIntent);
                return;
            }
        }

        if (!leaderMoving && sameFloor && !onStairs) {
            if (!settleWatchValid) {
                settleWatchX = npc.getX();
                settleWatchY = npc.getY();
                settleStallSec = 0.0F;
                settleWatchValid = true;
            } else {
                float moved = (float) Math.hypot(npc.getX() - settleWatchX, npc.getY() - settleWatchY);
                if (moved >= 0.18F) {
                    settleWatchX = npc.getX();
                    settleWatchY = npc.getY();
                    settleStallSec = 0.0F;
                } else {
                    settleStallSec += simDt;
                }
            }
            EmberLocomotionController.Status locoSt = loco.getStatus();
            boolean locoFailed = (locoSt == EmberLocomotionController.Status.STUCK
                    || locoSt == EmberLocomotionController.Status.NO_PATH) && !loco.isMoving();
            boolean shouldSettle = (locoFailed && settleStallSec >= 0.35F && distToAnchor <= 6.50F)
                    || (settleStallSec >= 1.20F && (distToSlot <= 3.00F || distToAnchor <= 5.50F));
            int curTx = (int) Math.floor(npc.getX());
            int curTy = (int) Math.floor(npc.getY());
            if (shouldSettle
                    && !EmberStairDiag.isStairOrExitTile(curTx, curTy, nz)
                    && EmberTargetProjector.canStandClean(npc, npc.getX(), npc.getY(), nz)) {
                congestionSettled = true;
                settledAnchorX = lx;
                settledAnchorY = ly;
                settledAnchorZ = lz;
                holding = true;
                state = FollowState.WAITING;
                loco.holdFollowIdle(npc, sneakIntent);
                return;
            }
        } else {
            resetCongestionSettle();
        }

        holding = false;
        if (wasHolding) {
            gaitControl.seedDriveOnTakeoff();
        }
        state = FollowState.FOLLOWING;

        boolean closeMatch = sameFloor && leaderMoving && !catchUpDirectLatched
                && effectiveDistToLeader <= 5.0F && distToSlot <= 2.20F;
        boolean canIntegrate = loco.isMoving() && !npc.isTurningAround();
        gaitControl.updateSpeedDrive(closeMatch, canIntegrate, vCmd, simDt);
        float speedCap = gaitControl.getWalkSpeedCap(closeMatch);
        float trackScale = gaitControl.getTrackSpeedScale(closeMatch);

        float steerX = hasPlannedTarget ? plannedTargetX : lx;
        float steerY = hasPlannedTarget ? plannedTargetY : ly;
        int steerZ = hasPlannedTarget ? plannedTargetZ : lz;
        if (!isHoldMode && !outsideVehMode && hasPlannedTarget && sameFloor && leaderMoving && !catchUpDirectLatched
                && clearLosToAnchor && alongLag < 0.45F) {
            float desiredLead = Math.max(0.0F, 0.90F - alongLag);
            float leaderProjection = (lx - plannedTargetX) * lastDirX + (ly - plannedTargetY) * lastDirY;
            float maxSafeLead = Math.max(0.0F, leaderProjection - 0.85F);
            float forwardLead = Math.min(desiredLead, maxSafeLead);
            if (forwardLead > 0.05F) {
                float cx = plannedTargetX + lastDirX * forwardLead;
                float cy = plannedTargetY + lastDirY * forwardLead;
                if (!isLineBlocked(npc, npc.getX(), npc.getY(), cx, cy, lz)
                        && isCleanFollowSlot(npc, cx, cy, lz, lx, ly)) {
                    steerX = cx;
                    steerY = cy;
                    steerZ = lz;
                }
            }
        }

        boolean cruiseOpen = leaderMoving && sameFloor;
        if (loco.isMoving() && cruiseOpen && loco.updateCruiseTargetInPlace(npc, steerX, steerY, steerZ)) {
            loco.setLiveVelocityControl(npc, desiredGait, speedCap, trackScale, true);
            lastSentX = steerX;
            lastSentY = steerY;
            lastSentZ = steerZ;
        } else {
            boolean lockOnStairsRetarget = onStairs && loco.isMoving() && lastSentZ == steerZ;
            boolean needsRetarget = !lockOnStairsRetarget && (!loco.isMoving()
                    || lastSentZ != steerZ
                    || (now - lastRetargetMs >= RETARGET_INTERVAL_MS
                    && Math.hypot(steerX - lastSentX, steerY - lastSentY) > RETARGET_SHIFT_DIST));

            if (needsRetarget && hasPlannedTarget) {
                if (loco.retarget(npc, steerX, steerY, steerZ, desiredGait, speedCap, trackScale, true, cruiseOpen)) {
                    if (EmberStairDiag.ENABLED && EmberStairDiag.isNearStairs(npc) && now - diagLastRetargetMs >= 350L) {
                        diagLastRetargetMs = now;
                        EmberStairDiag.log("FOLLOW_RETARGET", String.format(java.util.Locale.US,
                                "%s(r%d) onStairs=%s %s steer=(%.2f,%.2f,%d) leader=(%.2f,%.2f,%d) catchUp=%s node=%s",
                                npc.getNpcId(), myRank, onStairs, EmberStairDiag.fmtPosAndSq(npc),
                                steerX, steerY, steerZ, lx, ly, lz,
                                catchUpDirectLatched, loco.formatCorridorNode()));
                    }
                    lastRetargetMs = now;
                    lastSentX = steerX;
                    lastSentY = steerY;
                    lastSentZ = steerZ;
                }
            } else {
                loco.setLiveVelocityControl(npc, desiredGait, speedCap, trackScale, cruiseOpen);
            }
        }
    }

    private void logHoldNearStair(
            IsoNPC npc, int myRank, String reason,
            float lx, float ly, int lz,
            float distToSlot, boolean clearLosToLeader, long nowMs) {
        if (!EmberStairDiag.ENABLED || !EmberStairDiag.isNearStairs(npc) || nowMs - diagLastHoldMs < 1000L) {
            return;
        }
        diagLastHoldMs = nowMs;
        EmberStairDiag.log("HOLD_NEAR_STAIR", String.format(java.util.Locale.US,
                "%s(r%d) %s %s slot=(%.2f,%.2f,%d) distSlot=%.2f leader=(%.2f,%.2f,%d) lMov=%s los=%s",
                npc.getNpcId(), myRank, reason, EmberStairDiag.fmtPosAndSq(npc),
                plannedTargetX, plannedTargetY, plannedTargetZ, distToSlot,
                lx, ly, lz, leaderMoving, clearLosToLeader));
    }

    private void planTarget(
            IsoNPC npc, EmberLeaderTrail trail,
            float lx, float ly, int lz,
            boolean sameFloor, float distToAnchor, int myRank, int squadCount,
            FormationType formation, FormationSpacing spacing,
            boolean isHoldMode, boolean outsideVehMode, float minVehSafeRadius, float nominalRadius) {
        plannedSlotValidated = false;
        float catchUpEnter = EmberFollowGaitControl.catchUpEnterDist(nominalRadius);
        float catchUpExit = catchUpEnter - (EmberFollowGaitControl.CATCHUP_DIRECT_ENTER - EmberFollowGaitControl.CATCHUP_DIRECT_EXIT);
        catchUpDirectLatched = updateHysteresis(catchUpDirectLatched, distToAnchor, catchUpEnter, catchUpExit);

        if (!sameFloor || (!isHoldMode && !outsideVehMode && catchUpDirectLatched)) {
            EmberTargetProjector.ProjectedPoint direct = EmberTargetProjector.projectGoal(npc, lx, ly, lz);
            if (direct != null) {
                plannedTargetX = direct.x();
                plannedTargetY = direct.y();
                plannedTargetZ = direct.z();
                hasPlannedTarget = true;
            }
            return;
        }

        // Priority 1: 小队联合解算器产出的互斥、连通、严苛壁距世界实位（覆盖所有静止站位、驻守、车外护卫及 LINE/RING）
        if (hasAssignedWorldSlot && assignedSlotZ == lz
                && (!leaderMoving || isHoldMode || outsideVehMode
                        || formation == FormationType.LINE || formation == FormationType.RING)) {
            plannedTargetX = assignedSlotX;
            plannedTargetY = assignedSlotY;
            plannedTargetZ = assignedSlotZ;
            hasPlannedTarget = true;
            plannedSlotValidated = true;
            return;
        }

        float lam = spacing != null ? spacing.multiplier() : 1.0F;
        EmberFormationMatcher.slotOffset(formation, myRank, squadCount, lam, slotScratch);
        float slotBack = slotScratch[0];
        float slotSide = slotScratch[1];

        // Priority 2: WEDGE / COLUMN 在行进态沿领袖历史轨迹尾随，两侧遇阻时弹性收缩横向间距为纵队
        if (!isHoldMode && !outsideVehMode && trail != null
                && (formation == FormationType.WEDGE || formation == FormationType.COLUMN)
                && trail.getFloor() == lz && trail.sample(slotBack, trailScratch)) {
            float bx = trailScratch[0];
            float by = trailScratch[1];
            float dirX = trailScratch[2];
            float dirY = trailScratch[3];
            if (dirX * dirX + dirY * dirY < 0.0001F) {
                dirX = lastDirX;
                dirY = lastDirY;
            }
            int tries = Math.abs(slotSide) >= 0.05F ? TRAIL_SIDE_SQUEEZE.length : 1;
            for (int t = 0; t < tries; t++) {
                float sqSide = slotSide * TRAIL_SIDE_SQUEEZE[t];
                float wx = bx - dirY * sqSide;
                float wy = by + dirX * sqSide;
                if ((Math.abs(sqSide) < 0.01F || !isLineBlocked(npc, bx, by, wx, wy, lz))
                        && isCleanFollowSlot(npc, wx, wy, lz, lx, ly)) {
                    plannedTargetX = wx;
                    plannedTargetY = wy;
                    plannedTargetZ = lz;
                    hasPlannedTarget = true;
                    plannedSlotValidated = true;
                    return;
                }
            }
        }

        // Priority 3: 轨迹尾随遇障时，优先切入小队联合解算实位
        if (hasAssignedWorldSlot && assignedSlotZ == lz) {
            plannedTargetX = assignedSlotX;
            plannedTargetY = assignedSlotY;
            plannedTargetZ = assignedSlotZ;
            hasPlannedTarget = true;
            plannedSlotValidated = true;
            return;
        }

        boolean requireLosOnStop = !leaderMoving;
        float directX = lx - lastDirX * slotBack - lastDirY * slotSide;
        float directY = ly - lastDirY * slotBack + lastDirX * slotSide;
        if (isCleanFollowSlot(npc, directX, directY, lz, lx, ly)
                && !isLineBlocked(npc, lx, ly, directX, directY, lz)) {
            plannedTargetX = directX;
            plannedTargetY = directY;
            plannedTargetZ = lz;
            hasPlannedTarget = true;
            plannedSlotValidated = true;
            return;
        }

        float columnBackDist = (1.60F + myRank * 1.15F) * lam;
        if (!isHoldMode && trail != null && trail.getFloor() == lz && trail.sample(columnBackDist, trailScratch)) {
            float cx = trailScratch[0];
            float cy = trailScratch[1];
            if (isCleanFollowSlot(npc, cx, cy, lz, lx, ly)
                    && (!requireLosOnStop || !isLineBlocked(npc, cx, cy, lx, ly, lz))) {
                plannedTargetX = cx;
                plannedTargetY = cy;
                plannedTargetZ = lz;
                hasPlannedTarget = true;
                plannedSlotValidated = true;
                return;
            }
        }

        EmberTargetProjector.ProjectedPoint fallback = EmberTargetProjector.projectGoal(
                npc, lx - lastDirX * 1.0F, ly - lastDirY * 1.0F, lz);
        if (fallback == null || !isCleanFollowSlot(npc, fallback.x(), fallback.y(), fallback.z(), lx, ly)) {
            fallback = EmberTargetProjector.projectGoal(npc, lx, ly, lz);
        }
        if (fallback != null) {
            plannedTargetX = fallback.x();
            plannedTargetY = fallback.y();
            plannedTargetZ = fallback.z();
            hasPlannedTarget = true;
        }
    }

    private static boolean isCleanFollowSlot(IsoNPC npc, float x, float y, int z, float lx, float ly) {
        int tx = (int) Math.floor(x);
        int ty = (int) Math.floor(y);
        if (EmberStairDiag.isStairOrExitTile(tx, ty, z)) {
            return Math.hypot(x - lx, y - ly) <= 1.15F && EmberTargetProjector.canStandClean(npc, x, y, z);
        }
        return EmberTargetProjector.canStandFormationSlot(npc, x, y, z);
    }

    private static EmberLocomotionController.Gait mapGearToGait(EmberFollowGaitControl.Gear gear) {
        return switch (gear) {
            case SNEAK -> EmberLocomotionController.Gait.SNEAK;
            case RUN -> EmberLocomotionController.Gait.RUN;
            case SPRINT -> EmberLocomotionController.Gait.SPRINT;
            default -> EmberLocomotionController.Gait.WALK;
        };
    }

    private boolean tryStartStaticYield(
            IsoNPC npc, EmberLocomotionController loco,
            float lx, float ly, int z, boolean sneaking, long now, float minApproachDot) {
        float toNpcX = npc.getX() - lx;
        float toNpcY = npc.getY() - ly;
        float dist = (float) Math.hypot(toNpcX, toNpcY);
        if (dist < 1.0E-4F) {
            return false;
        }
        float pushX = toNpcX / dist;
        float pushY = toNpcY / dist;
        float approachDot = pushX * lastDirX + pushY * lastDirY;
        if (approachDot < minApproachDot) {
            return false;
        }

        float px = -pushY;
        float py = pushX;
        float[][] offsets = {
            {px * 0.95F, py * 0.95F},
            {-px * 0.95F, -py * 0.95F},
            {pushX * 1.05F, pushY * 1.05F}
        };
        for (float[] off : offsets) {
            float candX = npc.getX() + off[0];
            float candY = npc.getY() + off[1];
            if (EmberTargetProjector.canStandClean(npc, candX, candY, z)
                    && !isLineBlocked(npc, npc.getX(), npc.getY(), candX, candY, z)) {
                EmberLocomotionController.Gait g = sneaking
                        ? EmberLocomotionController.Gait.SNEAK
                        : EmberLocomotionController.Gait.WALK;
                if (loco.retarget(npc, candX, candY, z, g, -1.0F, 1.0F, false, false)) {
                    yieldGoalX = candX;
                    yieldGoalY = candY;
                    yieldGoalZ = z;
                    yieldUntilMs = now + STATIC_YIELD_HOLD_MS;
                    holding = false;
                    state = FollowState.YIELDING;
                    return true;
                }
            }
        }
        return false;
    }

    private void updateLeaderMotion(IsoPlayer leader, float lx, float ly, int lz, long now) {
        if (lastLeaderZ == Integer.MIN_VALUE || lastLeaderZ != lz) {
            lastLeaderX = lx;
            lastLeaderY = ly;
            lastLeaderZ = lz;
            leaderMovedAtMs = now;
            leaderMoving = false;
            seedDirFromLeader(leader);
            return;
        }
        float dx = lx - lastLeaderX;
        float dy = ly - lastLeaderY;
        float movedSq = dx * dx + dy * dy;
        if (movedSq > 0.0016F) {
            float len = (float) Math.sqrt(movedSq);
            lastDirX = dx / len;
            lastDirY = dy / len;
            lastLeaderX = lx;
            lastLeaderY = ly;
            leaderMovedAtMs = now;
            leaderMoving = true;
        }
    }

    private void seedDirFromLeader(IsoPlayer leader) {
        float fx = leader.getForwardDirectionX();
        float fy = leader.getForwardDirectionY();
        float len = (float) Math.hypot(fx, fy);
        if (len > 0.001F) {
            lastDirX = fx / len;
            lastDirY = fy / len;
        }
    }

    private static IsoPlayer resolveLeader(int idx) {
        if (IsoPlayer.players == null || idx < 0 || idx >= IsoPlayer.players.length) {
            return null;
        }
        return IsoPlayer.players[idx];
    }

    private static boolean isLineBlocked(IsoNPC npc, float x1, float y1, float x2, float y2, int z) {
        return PolygonalMap2.instance != null
                && PolygonalMap2.instance.lineClearCollide(x1, y1, x2, y2, z, npc, false, true);
    }
}
