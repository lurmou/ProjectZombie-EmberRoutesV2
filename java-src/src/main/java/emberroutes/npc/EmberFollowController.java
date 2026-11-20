package emberroutes.npc;

import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;
import zombie.pathfind.PolygonalMap2;

/**
 * 纯内存瞬态步行跟随控制器（零持久账本、不跨帧缓存 IsoPlayer 引用）：
 * - 基于共享 {@link EmberLeaderTrail} 计算自适应队形槽位（开阔倒 V -> 窄道单列纵队 -> 几何兜底）；
 * - 滞回停靠死区 + 视线净空校验（绝不隔墙傻停）、静止堵门迎面让路、楼梯前单人排队；
 * - 全步态跟随（Sneak / Walk / Run / Sprint）+ 同态 WalkSpeed 对齐消橡皮筋、玩家乘车暂待。
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
    private static final float CATCHUP_DIRECT_ENTER = 8.0F;
    private static final float CATCHUP_DIRECT_EXIT = 6.0F;
    private static final float STATIC_YIELD_TRIGGER_DIST = 1.05F;
    private static final long STATIC_YIELD_HOLD_MS = 1000L;
    private static final float PORTAL_AUTOCLOSE_TEAM_RADIUS = 2.60F;

    private final EmberFollowGaitControl gaitControl = new EmberFollowGaitControl();

    private boolean enabled;
    private int leaderPlayerIndex;
    private int requestedSlot = -1;
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

    private final float[] trailScratch = new float[4];

    static float slotBack(int rank) {
        if (rank <= 0) {
            return 1.60F;
        }
        return 2.00F + ((rank - 1) / 2) * 1.15F;
    }

    static float slotSide(int rank) {
        if (rank <= 0) {
            return 0.0F;
        }
        return (rank & 1) == 1 ? -0.95F : 0.95F;
    }

    static float columnBack(int rank) {
        int r = Math.max(0, rank);
        return 1.60F + r * 1.15F;
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
        this.gaitControl.reset();
    }

    public void stop(IsoNPC npc, String reason) {
        stop(npc, true, reason);
    }

    public void stop(IsoNPC npc, boolean stopMotion, String reason) {
        boolean wasEnabled = this.enabled || this.state != FollowState.INACTIVE;
        this.enabled = false;
        this.holding = false;
        this.catchUpDirectLatched = false;
        this.hasPlannedTarget = false;
        this.yieldUntilMs = 0L;
        this.prevSampleLeaderZ = Integer.MIN_VALUE;
        this.prevSampleNpcZ = Integer.MIN_VALUE;
        this.speedWindowDt = 0.0F;
        this.accumLeaderStep = 0.0F;
        this.accumNpcStep = 0.0F;
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

    public FollowState getState() {
        return state;
    }

    public void beforeLocomotion(IsoNPC npc) {
        if (!enabled || npc == null || npc.isDead()) {
            return;
        }

        EmberLocomotionController loco = npc.getLocomotion();
        IsoPlayer leader = resolveLeader(leaderPlayerIndex);
        if (leader == null || leader.isDead() || leader.getCurrentSquare() == null || leader.getCell() != npc.getCell()) {
            holding = false;
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

        if (leader.getVehicle() != null) {
            holding = false;
            yieldUntilMs = 0L;
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

        if (npc.getVehicle() != null || !npc.getCharacterActions().isEmpty()) {
            prevSampleLeaderZ = Integer.MIN_VALUE;
            prevSampleNpcZ = Integer.MIN_VALUE;
            speedWindowDt = 0.0F;
            accumLeaderStep = 0.0F;
            accumNpcStep = 0.0F;
            loco.setFollowSteerHint(leader, false);
            return;
        }

        float lx = leader.getX();
        float ly = leader.getY();
        int lz = EmberTargetProjector.levelOf(leader.getZ());
        long now = System.currentTimeMillis();

        float simDt = EmberLocomotionController.deltaSeconds();
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

        if (leaderMoving && (now - leaderMovedAtMs) >= 240L) {
            leaderMoving = false;
            gaitControl.resetLeaderSpeed();
        }
        loco.setFollowSteerHint(leader, leaderMoving);

        boolean sneakIntent = gaitControl.updateSneakIntent(leader.isSneaking(), now);

        if (EmberPortalActuator.isInNativeTraversal(npc)) {
            state = FollowState.FOLLOWING;
            return;
        }

        int nz = EmberTargetProjector.levelOf(npc.getZ());
        boolean sameFloor = (nz == lz) && Math.abs(npc.getZ() - leader.getZ()) <= 0.20F;
        float distToLeader = (float) Math.hypot(lx - npc.getX(), ly - npc.getY());
        boolean clearLosToLeader = sameFloor && !isLineBlocked(npc, npc.getX(), npc.getY(), lx, ly, lz);
        boolean allowLeaderVault = !clearLosToLeader;

        loco.setPortalPolicy(allowLeaderVault, allowLeaderVault, PORTAL_AUTOCLOSE_TEAM_RADIUS);
        loco.setLeaderPortalGate(allowLeaderVault, lx, ly, lz);

        int myRank = (requestedSlot >= 0) ? requestedSlot : NpcManager.resolveEffectiveFollowRank(npc, leaderPlayerIndex);

        IsoGridSquare curSq = npc.getCurrentSquare();
        boolean onStairs = curSq != null && curSq.HasStairs();
        if (!onStairs && NpcManager.isHigherRankFollowerOnStairsAhead(npc, leaderPlayerIndex, myRank)) {
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

        boolean yieldCandidate = (holding && sameFloor && leaderMoving && distToLeader <= STATIC_YIELD_TRIGGER_DIST)
                || (sameFloor && leaderMoving && distToLeader <= 0.95F);
        if (yieldCandidate) {
            float minApproachDot = holding ? 0.45F : 0.55F;
            if (tryStartStaticYield(npc, loco, lx, ly, nz, sneakIntent, now, minApproachDot)) {
                return;
            }
        }

        if (!hasPlannedTarget || now >= nextPlanMs || plannedTargetZ != lz) {
            nextPlanMs = now + PLAN_INTERVAL_MS;
            planTarget(npc, leader, trail, lx, ly, lz, sameFloor, distToLeader, myRank);
        }

        float dxSlot = hasPlannedTarget ? (plannedTargetX - npc.getX()) : (lx - npc.getX());
        float dySlot = hasPlannedTarget ? (plannedTargetY - npc.getY()) : (ly - npc.getY());
        float distToSlot = (float) Math.hypot(dxSlot, dySlot);
        float alongLag = dxSlot * lastDirX + dySlot * lastDirY;
        float signedErr = (!sameFloor || catchUpDirectLatched || !leaderMoving)
                ? distToSlot
                : (alongLag >= 0.0F ? distToSlot : alongLag);

        float vCmd = gaitControl.computeCommandSpeed(signedErr);
        EmberFollowGaitControl.Gear gear = gaitControl.updateGear(sneakIntent, distToLeader, vCmd, now);
        EmberLocomotionController.Gait desiredGait = mapGearToGait(gear);

        boolean wasHolding = holding;
        if (!leaderMoving && sameFloor && hasPlannedTarget && plannedTargetZ == nz
                && updateHoldHysteresis(holding, distToSlot, HOLD_ENTER_DIST, HOLD_EXIT_DIST)
                && clearLosToLeader) {
            holding = true;
            state = FollowState.WAITING;
            loco.holdFollowIdle(npc, sneakIntent);
            return;
        }
        if (!leaderMoving && sameFloor && hasPlannedTarget && plannedTargetZ == nz
                && !loco.isMoving() && distToSlot <= EmberParams.ARRIVE_RADIUS) {
            state = FollowState.WAITING;
            loco.holdFollowIdle(npc, sneakIntent);
            return;
        }
        if (leaderMoving && sameFloor && clearLosToLeader) {
            boolean stayHeldOnTinyNudge = holding
                    && distToSlot <= 0.55F
                    && distToLeader <= slotBack(myRank) + 0.55F;
            boolean creepPause = stayHeldOnTinyNudge || (holding
                    ? (alongLag < -0.15F && vCmd < 0.35F)
                    : (alongLag < -0.55F && vCmd < 0.25F));
            if (creepPause) {
                holding = true;
                state = FollowState.WAITING;
                loco.holdFollowIdle(npc, sneakIntent);
                return;
            }
        }

        holding = false;
        if (wasHolding) {
            gaitControl.seedDriveOnTakeoff();
        }
        state = FollowState.FOLLOWING;

        boolean closeMatch = sameFloor && leaderMoving && !catchUpDirectLatched
                && distToLeader <= 5.0F && distToSlot <= 2.20F;
        boolean canIntegrate = loco.isMoving() && !npc.isTurningAround();
        gaitControl.updateSpeedDrive(closeMatch, canIntegrate, vCmd, simDt);
        float speedCap = gaitControl.getWalkSpeedCap(closeMatch);
        float trackScale = gaitControl.getTrackSpeedScale(closeMatch);

        float steerX = hasPlannedTarget ? plannedTargetX : lx;
        float steerY = hasPlannedTarget ? plannedTargetY : ly;
        int steerZ = hasPlannedTarget ? plannedTargetZ : lz;
        if (hasPlannedTarget && sameFloor && leaderMoving && !catchUpDirectLatched
                && clearLosToLeader && alongLag < 0.45F) {
            float desiredLead = Math.max(0.0F, 0.90F - alongLag);
            float leaderProjection = (lx - plannedTargetX) * lastDirX + (ly - plannedTargetY) * lastDirY;
            float maxSafeLead = Math.max(0.0F, leaderProjection - 0.85F);
            float forwardLead = Math.min(desiredLead, maxSafeLead);
            if (forwardLead > 0.05F) {
                float cx = plannedTargetX + lastDirX * forwardLead;
                float cy = plannedTargetY + lastDirY * forwardLead;
                if (!isLineBlocked(npc, npc.getX(), npc.getY(), cx, cy, lz)
                        && EmberTargetProjector.canStandClean(npc, cx, cy, lz)) {
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
            boolean needsRetarget = !loco.isMoving()
                    || lastSentZ != steerZ
                    || (now - lastRetargetMs >= RETARGET_INTERVAL_MS
                    && Math.hypot(steerX - lastSentX, steerY - lastSentY) > RETARGET_SHIFT_DIST);

            if (needsRetarget && hasPlannedTarget) {
                if (loco.retarget(npc, steerX, steerY, steerZ, desiredGait, speedCap, trackScale, true, cruiseOpen)) {
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

    private void planTarget(
            IsoNPC npc, IsoPlayer leader, EmberLeaderTrail trail,
            float lx, float ly, int lz,
            boolean sameFloor, float distToLeader, int myRank) {
        catchUpDirectLatched = updateHysteresis(catchUpDirectLatched, distToLeader, CATCHUP_DIRECT_ENTER, CATCHUP_DIRECT_EXIT);

        if (!sameFloor || catchUpDirectLatched) {
            EmberTargetProjector.ProjectedPoint direct = EmberTargetProjector.projectGoal(npc, lx, ly, lz);
            if (direct != null) {
                plannedTargetX = direct.x();
                plannedTargetY = direct.y();
                plannedTargetZ = direct.z();
                hasPlannedTarget = true;
            }
            return;
        }

        boolean requireLosOnStop = !leaderMoving;
        if (trail.getFloor() == lz && trail.sample(slotBack(myRank), trailScratch)) {
            float bx = trailScratch[0];
            float by = trailScratch[1];
            float dirX = trailScratch[2];
            float dirY = trailScratch[3];
            if (dirX * dirX + dirY * dirY < 0.0001F) {
                dirX = lastDirX;
                dirY = lastDirY;
            }
            float side = slotSide(myRank);
            float wx = bx - dirY * side;
            float wy = by + dirX * side;
            if ((Math.abs(side) < 0.01F || !isLineBlocked(npc, bx, by, wx, wy, lz))
                    && EmberTargetProjector.canStandClean(npc, wx, wy, lz)
                    && (!requireLosOnStop || !isLineBlocked(npc, wx, wy, lx, ly, lz))) {
                plannedTargetX = wx;
                plannedTargetY = wy;
                plannedTargetZ = lz;
                hasPlannedTarget = true;
                return;
            }
        }

        if (trail.getFloor() == lz && trail.sample(columnBack(myRank), trailScratch)) {
            float cx = trailScratch[0];
            float cy = trailScratch[1];
            if (EmberTargetProjector.canStandClean(npc, cx, cy, lz)
                    && (!requireLosOnStop || !isLineBlocked(npc, cx, cy, lx, ly, lz))) {
                plannedTargetX = cx;
                plannedTargetY = cy;
                plannedTargetZ = lz;
                hasPlannedTarget = true;
                return;
            }
        }

        if (requireLosOnStop && trail.getFloor() == lz) {
            float[] shortBacks = { 1.10F, 0.75F };
            for (float sb : shortBacks) {
                if (trail.sample(sb, trailScratch)) {
                    float sx = trailScratch[0];
                    float sy = trailScratch[1];
                    if (EmberTargetProjector.canStandClean(npc, sx, sy, lz)
                            && !isLineBlocked(npc, sx, sy, lx, ly, lz)) {
                        plannedTargetX = sx;
                        plannedTargetY = sy;
                        plannedTargetZ = lz;
                        hasPlannedTarget = true;
                        return;
                    }
                }
            }
        }

        float back = requireLosOnStop ? 1.0F : columnBack(myRank);
        EmberTargetProjector.ProjectedPoint fallback = EmberTargetProjector.projectGoal(
                npc, lx - lastDirX * back, ly - lastDirY * back, lz);
        if (fallback == null
                || (requireLosOnStop && isLineBlocked(npc, fallback.x(), fallback.y(), lx, ly, lz))) {
            fallback = EmberTargetProjector.projectGoal(npc, lx, ly, lz);
        }
        if (fallback != null) {
            plannedTargetX = fallback.x();
            plannedTargetY = fallback.y();
            plannedTargetZ = fallback.z();
            hasPlannedTarget = true;
        }
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
