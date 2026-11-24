package emberroutes.npc.brain;

import emberroutes.npc.EmberLocomotionController;
import emberroutes.npc.EmberTargetProjector;
import emberroutes.npc.IsoNPC;
import emberroutes.npc.NpcManager;
import emberroutes.npc.SquadTacticalConfig;
import emberroutes.npc.SquadTacticalConfig.OrderMode;
import emberroutes.npc.SquadTacticalConfig.VehicleCommandMode;
import emberroutes.npc.brain.VehicleCoRideHelper.ApproachTarget;
import emberroutes.npc.brain.VehicleSeatRegistry.SeatAllocation;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

/**
 * {@link SquadBrainBuilder} 最高优抢占状态机节点 {@code VEHICLE_CO_RIDE}（100% Java 原生控制）：
 * <ul>
 *   <li>进入前严格经过 {@code NpcPostureController} 起身屏障；</li>
 *   <li>严禁调用会污染 {@code playerNum=0} UI 的 Lua {@code ISTimedActionQueue}，直接复刻原版 {@link BaseVehicle} Java 门控与动画变量；</li>
 *   <li>支持深座优先双锁与门口让座、出口被堵自动换座、停稳才离车与高速中解散延迟释放。</li>
 * </ul>
 */
public final class VehicleCoRideNode extends StateNode {
    private enum SubState {
        POSTURE_WAIT,
        APPROACH_DOOR,
        OPEN_DOOR,
        ENTERING,
        SWITCHING_SEAT,
        RIDING,
        EXIT_SWITCH_SEAT,
        EXIT_OPEN_DOOR,
        EXITING
    }

    private static final float BOARD_ENTER_MAX_DIST = 32.0F;
    private static final float BOARD_ENTER_MAX_SPEED_KMH = 15.0F;
    private static final float DOOR_INTERACT_RANGE = 2.0F;
    private static final float STOPPED_SPEED_KMH = 0.80F;
    private static final float RETARGET_SHIFT_DIST = 0.65F;
    private static final double OPEN_DOOR_SEC = 0.25;
    private static final double ANIM_TIMEOUT_SEC = 2.50;

    private SubState subState = SubState.POSTURE_WAIT;
    private BaseVehicle activeVehicle;
    private int finalSeat = -1;
    private int entrySeat = -1;
    private boolean useDoor2 = false;
    private VehiclePart activeDoorPart;
    private double subStateElapsedSec = 0.0;
    private float lastApproachGoalX;
    private float lastApproachGoalY;
    private int lastApproachGoalZ = Integer.MIN_VALUE;
    private double lastApproachRetargetTime = -10.0;

    // 复用扫描缓冲区减少热点分配（主线程单大脑按序执行，但按节点实例化仍每 NPC 独立）
    private boolean[] tmpInstalled = new boolean[16];
    private boolean[] tmpOccupied = new boolean[16];
    private String[] tmpOccupantNpcIds = new String[16];
    private boolean[] tmpDirectClear = new boolean[16];
    private boolean[][] tmpCanSwitch = new boolean[16][16];
    private float[] tmpDoorDistSq = new float[16];

    public VehicleCoRideNode() {
        super("VEHICLE_CO_RIDE", null, null, 0.0, true);
    }

    @Override
    public boolean canEnter(BrainContext ctx) {
        if (ctx == null || ctx.npc == null || ctx.npc.isDead()) {
            return false;
        }
        IsoNPC npc = ctx.npc;
        if (npc.getVehicle() != null) {
            return true;
        }
        IsoPlayer leader = resolveLeader(ctx.followPlayerIndex);
        if (leader == null || leader.isDead()) {
            return false;
        }
        BaseVehicle desired = resolveDesiredBoardVehicle(npc, leader);
        if (desired == null || !isVehicleValidInWorld(desired)) {
            return false;
        }
        if (Math.abs(desired.getCurrentSpeedKmHour()) > BOARD_ENTER_MAX_SPEED_KMH) {
            return false;
        }
        if (EmberTargetProjector.levelOf(npc.getZ()) != EmberTargetProjector.levelOf(desired.getZ())) {
            return false;
        }
        if (VehicleCoRideHelper.distSq(npc.getX(), npc.getY(), desired.getX(), desired.getY())
                > BOARD_ENTER_MAX_DIST * BOARD_ENTER_MAX_DIST) {
            return false;
        }
        return scanAndCheckCandidate(desired, npc, leader);
    }

    @Override
    public boolean canContinue(BrainContext ctx) {
        if (ctx == null || ctx.npc == null || ctx.npc.isDead()) {
            return false;
        }
        if (ctx.npc.getVehicle() != null) {
            return true;
        }
        if (ctx.pendingDismissAfterExit) {
            return false;
        }
        IsoPlayer leader = resolveLeader(ctx.followPlayerIndex);
        if (leader == null || leader.isDead()) {
            return false;
        }
        BaseVehicle desired = resolveDesiredBoardVehicle(ctx.npc, leader);
        if (desired == null || !isVehicleValidInWorld(desired)) {
            return false;
        }
        if (Math.abs(desired.getCurrentSpeedKmHour()) > BOARD_ENTER_MAX_SPEED_KMH) {
            return false;
        }
        return scanAndCheckCandidate(desired, ctx.npc, leader);
    }

    @Override
    public void onEnter(BrainContext ctx, StateScope scope) {
        ctx.working = false;
        resetLocalState();
        IsoNPC npc = ctx.npc;
        if (npc == null) {
            return;
        }
        if (npc.getVehicle() != null) {
            activeVehicle = npc.getVehicle();
            int curSeat = activeVehicle.getSeat(npc);
            finalSeat = curSeat;
            entrySeat = curSeat;
            setSubState(SubState.RIDING);
        } else if (npc.getPosture().isBlockingLocomotion() || npc.getPosture().isActive()) {
            npc.getPosture().clearPendingContinuation();
            npc.getPosture().commandStandUp(npc, true);
            setSubState(SubState.POSTURE_WAIT);
        } else {
            setSubState(SubState.APPROACH_DOOR);
        }

        scope.defer(() -> {
            VehicleSeatRegistry.releaseForNpc(ctx.npcId);
            if (ctx.npc != null) {
                ctx.npc.getLocomotion().interrupt(ctx.npc, "VEHICLE_NODE_EXIT");
                if (ctx.npc.getVehicle() != null) {
                    VehicleCoRideHelper.forceDetachFromVehicle(ctx.npc, "SCOPE_SHUTDOWN");
                }
            }
            resetLocalState();
        });
    }

    @Override
    public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
        IsoNPC npc = ctx.npc;
        if (npc == null || npc.isDead()) {
            return;
        }
        subStateElapsedSec += Math.max(0.0, dtSim);
        IsoPlayer leader = resolveLeader(ctx.followPlayerIndex);

        // 兜底检查：若当前声称在车内但车辆被卸载或座位号丢失，立刻安全脱车
        if (npc.getVehicle() != null) {
            BaseVehicle curVeh = npc.getVehicle();
            if (!isVehicleValidInWorld(curVeh) || curVeh.getSeat(npc) == -1) {
                VehicleCoRideHelper.forceDetachFromVehicle(npc, "VEHICLE_INVALID");
                resetLocalState();
                return;
            }
            activeVehicle = curVeh;
        }

        switch (subState) {
            case POSTURE_WAIT -> tickPostureWait(npc);
            case APPROACH_DOOR -> tickApproachDoor(ctx, npc, leader);
            case OPEN_DOOR -> tickOpenDoor(npc, leader);
            case ENTERING -> tickEntering(npc);
            case SWITCHING_SEAT -> tickSwitchingSeat(npc);
            case RIDING -> tickRiding(ctx, npc, leader);
            case EXIT_SWITCH_SEAT -> tickExitSwitchSeat(npc);
            case EXIT_OPEN_DOOR -> tickExitOpenDoor(npc);
            case EXITING -> tickExiting(ctx, npc);
        }
    }

    private void tickPostureWait(IsoNPC npc) {
        if (!npc.getPosture().isBlockingLocomotion() && !npc.getPosture().isActive()) {
            setSubState(SubState.APPROACH_DOOR);
        } else if (npc.getPosture().isActive()) {
            npc.getPosture().commandStandUp(npc, true);
        }
    }

    private void tickApproachDoor(BrainContext ctx, IsoNPC npc, IsoPlayer leader) {
        if (npc.getVehicle() != null) {
            setSubState(SubState.RIDING);
            return;
        }
        BaseVehicle target = resolveDesiredBoardVehicle(npc, leader);
        if (target == null || !isVehicleValidInWorld(target)) {
            VehicleSeatRegistry.releaseForNpc(ctx.npcId);
            return;
        }

        SeatAllocation alloc = allocateSeat(target, npc, leader);
        if (alloc == null) {
            npc.getLocomotion().stop(npc);
            return;
        }
        if (alloc.shiftRequested()) {
            // 等待门口已坐 NPC 向深座挪移让道，同时走近车辆外沿等待
            int waitSeat = alloc.transitEntrySeat() >= 1 ? alloc.transitEntrySeat() : 1;
            ApproachTarget waitPt = VehicleCoRideHelper.resolveDoorApproachPoint(target, waitSeat, npc, leader);
            VehiclePart waitDoor = VehicleCoRideHelper.resolveDoorPart(
                    target, waitSeat, waitPt != null && waitPt.useDoor2());
            if (waitPt != null && VehicleCoRideHelper.distSq(npc.getX(), npc.getY(), waitPt.x(), waitPt.y()) > 4.0F) {
                driveApproachMove(ctx, npc, waitPt);
            } else {
                npc.getLocomotion().stop(npc);
            }
            activeDoorPart = waitDoor;
            return;
        }

        activeVehicle = target;
        finalSeat = alloc.finalSeat();
        entrySeat = alloc.transitEntrySeat();

        ApproachTarget doorPt = VehicleCoRideHelper.resolveDoorApproachPoint(target, entrySeat, npc, leader);
        if (doorPt == null) {
            VehicleSeatRegistry.releaseForNpc(ctx.npcId);
            return;
        }
        useDoor2 = doorPt.useDoor2();
        activeDoorPart = VehicleCoRideHelper.resolveDoorPart(target, entrySeat, useDoor2);

        float speed = Math.abs(target.getCurrentSpeedKmHour());
        float doorDistSq = VehicleCoRideHelper.distSq(npc.getX(), npc.getY(), doorPt.x(), doorPt.y());
        if (doorDistSq <= DOOR_INTERACT_RANGE * DOOR_INTERACT_RANGE && speed <= STOPPED_SPEED_KMH) {
            npc.getLocomotion().stop(npc);
            if (target.isSeatOccupied(entrySeat)
                    || VehicleSeatRegistry.isSeatReservedByOther(target, entrySeat, ctx.npcId)) {
                VehicleSeatRegistry.releaseForNpc(ctx.npcId);
                return;
            }
            if (!VehicleCoRideHelper.canAccessOrUnlockDoor(target, activeDoorPart, npc, leader)) {
                VehicleSeatRegistry.releaseForNpc(ctx.npcId);
                return;
            }
            VehicleCoRideHelper.dropHeavyHandItems(npc);
            if (activeDoorPart == null || activeDoorPart.getInventoryItem() == null
                    || (activeDoorPart.getDoor() != null && activeDoorPart.getDoor().isOpen()
                            && !activeDoorPart.getDoor().isLocked())) {
                beginEnterVehicle(npc);
            } else if (VehicleCoRideHelper.unlockAndOpenDoor(target, activeDoorPart, entrySeat, npc, leader)) {
                setSubState(SubState.OPEN_DOOR);
            } else {
                VehicleSeatRegistry.releaseForNpc(ctx.npcId);
            }
            return;
        }

        driveApproachMove(ctx, npc, doorPt);
    }

    private void driveApproachMove(BrainContext ctx, IsoNPC npc, ApproachTarget pt) {
        boolean needMove = !npc.getLocomotion().isMoving()
                || lastApproachGoalZ != pt.z()
                || (ctx.simTime - lastApproachRetargetTime >= 0.30
                        && VehicleCoRideHelper.distSq(pt.x(), pt.y(), lastApproachGoalX, lastApproachGoalY)
                                > RETARGET_SHIFT_DIST * RETARGET_SHIFT_DIST);
        if (needMove) {
            lastApproachGoalX = pt.x();
            lastApproachGoalY = pt.y();
            lastApproachGoalZ = pt.z();
            lastApproachRetargetTime = ctx.simTime;
            npc.faceLocationF(pt.x(), pt.y());
            npc.getLocomotion().moveTo(npc, pt.x(), pt.y(), pt.z(), true);
        }
    }

    private void tickOpenDoor(IsoNPC npc, IsoPlayer leader) {
        if (!isVehicleValidInWorld(activeVehicle) || entrySeat < 1) {
            VehicleSeatRegistry.releaseForNpc(npc.getNpcId());
            setSubState(SubState.APPROACH_DOOR);
            return;
        }
        ApproachTarget doorPt = VehicleCoRideHelper.resolveDoorApproachPoint(activeVehicle, entrySeat, npc, leader);
        float doorDistSq = doorPt != null
                ? VehicleCoRideHelper.distSq(npc.getX(), npc.getY(), doorPt.x(), doorPt.y())
                : Float.MAX_VALUE;
        if (Math.abs(activeVehicle.getCurrentSpeedKmHour()) > 2.0F
                || doorDistSq > (DOOR_INTERACT_RANGE + 0.45F) * (DOOR_INTERACT_RANGE + 0.45F)) {
            // 车开走或距离被拉开，回退接近阶段
            setSubState(SubState.APPROACH_DOOR);
            return;
        }
        if (subStateElapsedSec >= OPEN_DOOR_SEC) {
            beginEnterVehicle(npc);
        }
    }

    private void beginEnterVehicle(IsoNPC npc) {
        if (!isVehicleValidInWorld(activeVehicle) || entrySeat < 1 || activeVehicle.isSeatOccupied(entrySeat)
                || Math.abs(activeVehicle.getCurrentSpeedKmHour()) > 1.5F) {
            VehicleSeatRegistry.releaseForNpc(npc.getNpcId());
            setSubState(SubState.APPROACH_DOOR);
            return;
        }
        VehicleCoRideHelper.dropHeavyHandItems(npc);
        boolean entered;
        if (useDoor2 && activeVehicle.getPassengerPosition(entrySeat, "outside2") != null) {
            entered = activeVehicle.enter(entrySeat, npc,
                    activeVehicle.getPassengerPosition(entrySeat, "outside2").offset);
        } else {
            entered = activeVehicle.enter(entrySeat, npc);
        }
        if (!entered) {
            VehicleSeatRegistry.releaseForNpc(npc.getNpcId());
            setSubState(SubState.APPROACH_DOOR);
            return;
        }
        activeVehicle.playPassengerSound(entrySeat, "enter");
        npc.setVariable("bEnteringVehicle", true);
        npc.clearVariable("EnterAnimationFinished");
        npc.checkActionGroup();
        activeVehicle.playPassengerAnim(entrySeat, "enter");
        setSubState(SubState.ENTERING);
    }

    private void tickEntering(IsoNPC npc) {
        if (npc.getVehicle() != activeVehicle || activeVehicle.getSeat(npc) == -1) {
            VehicleCoRideHelper.forceDetachFromVehicle(npc, "ENTER_ABORTED");
            setSubState(SubState.APPROACH_DOOR);
            return;
        }
        activeVehicle.playPassengerAnim(entrySeat, "enter");
        boolean animDone = "true".equalsIgnoreCase(npc.getVariableString("EnterAnimationFinished"));
        if (!animDone && subStateElapsedSec < ANIM_TIMEOUT_SEC) {
            return;
        }
        npc.clearVariable("EnterAnimationFinished");
        npc.clearVariable("bEnteringVehicle");
        activeVehicle.setCharacterPosition(npc, entrySeat, "inside");
        activeVehicle.playPassengerAnim(entrySeat, "idle");
        VehicleCoRideHelper.closeDoorIfOpen(activeVehicle, activeDoorPart, npc);

        if (finalSeat >= 1 && finalSeat != entrySeat && activeVehicle.canSwitchSeat(entrySeat, finalSeat)
                && !activeVehicle.isSeatOccupied(finalSeat)) {
            beginSeatSwitch(npc, entrySeat, finalSeat, SubState.SWITCHING_SEAT);
        } else {
            finalSeat = entrySeat;
            VehicleSeatRegistry.releaseTransitSeat(npc.getNpcId());
            setSubState(SubState.RIDING);
        }
    }

    private void beginSeatSwitch(IsoNPC npc, int fromSeat, int toSeat, SubState targetSub) {
        npc.setVariable("bSwitchingSeat", true);
        npc.clearVariable("SwitchSeatAnimationFinished");
        String snd = activeVehicle.getSwitchSeatSound(fromSeat, toSeat);
        if (snd != null && !snd.isEmpty()) {
            activeVehicle.playSound(snd);
        }
        activeVehicle.playSwitchSeatAnim(fromSeat, toSeat);
        finalSeat = toSeat;
        entrySeat = fromSeat;
        setSubState(targetSub);
    }

    private void tickSwitchingSeat(IsoNPC npc) {
        if (npc.getVehicle() != activeVehicle || !isVehicleValidInWorld(activeVehicle)) {
            VehicleCoRideHelper.forceDetachFromVehicle(npc, "SWITCH_ABORTED");
            return;
        }
        int curSeat = activeVehicle.getSeat(npc);
        if (curSeat == -1 || finalSeat < 1) {
            setSubState(SubState.RIDING);
            return;
        }
        if (curSeat != finalSeat) {
            if (activeVehicle.isSeatOccupied(finalSeat)) {
                npc.clearVariable("bSwitchingSeat");
                npc.clearVariable("SwitchSeatAnimationFinished");
                activeVehicle.playPassengerAnim(curSeat, "idle");
                finalSeat = curSeat;
                VehicleSeatRegistry.releaseTransitSeat(npc.getNpcId());
                setSubState(SubState.RIDING);
                return;
            }
            activeVehicle.playSwitchSeatAnim(curSeat, finalSeat);
        }
        boolean animDone = "true".equalsIgnoreCase(npc.getVariableString("SwitchSeatAnimationFinished"));
        if (!animDone && subStateElapsedSec < 2.0) {
            return;
        }
        npc.clearVariable("SwitchSeatAnimationFinished");
        npc.clearVariable("bSwitchingSeat");
        if (curSeat != finalSeat && !activeVehicle.isSeatOccupied(finalSeat)) {
            activeVehicle.switchSeat(npc, finalSeat);
        }
        int settledSeat = activeVehicle.getSeat(npc);
        if (settledSeat >= 0) {
            activeVehicle.setCharacterPosition(npc, settledSeat, "inside");
            activeVehicle.playPassengerAnim(settledSeat, "idle");
            finalSeat = settledSeat;
            entrySeat = settledSeat;
        }
        VehicleSeatRegistry.releaseTransitSeat(npc.getNpcId());
        setSubState(SubState.RIDING);
    }

    private void tickRiding(BrainContext ctx, IsoNPC npc, IsoPlayer leader) {
        syncInVehicleSquare(npc);
        int curSeat = activeVehicle.getSeat(npc);
        if (curSeat < 0) {
            VehicleCoRideHelper.forceDetachFromVehicle(npc, "SEAT_LOST");
            return;
        }
        finalSeat = curSeat;

        // 响应门口让道请求（1 跳切入更深空座让出外门给后续队友）
        int shiftTarget = VehicleSeatRegistry.consumeDoorwayShiftTarget(activeVehicle, ctx.npcId);
        if (shiftTarget >= 1 && shiftTarget != curSeat
                && activeVehicle.isSeatInstalled(shiftTarget)
                && !activeVehicle.isSeatOccupied(shiftTarget)
                && activeVehicle.canSwitchSeat(curSeat, shiftTarget)) {
            beginSeatSwitch(npc, curSeat, shiftTarget, SubState.SWITCHING_SEAT);
            return;
        }

        // 检查是否应下车（包括领袖离车、领袖换乘另一辆车、驻守命令、手动下车或延迟解散）
        boolean shouldExit = false;
        if (ctx.pendingDismissAfterExit || ctx.manualExitVehicleRequested) {
            shouldExit = true;
        } else if (SquadTacticalConfig.getOrderMode() == OrderMode.HOLD_POSITION) {
            shouldExit = true;
        } else if (SquadTacticalConfig.getVehicleOverride() == VehicleCommandMode.FORCE_EXIT
                && (SquadTacticalConfig.getOverrideVehicle() == null
                        || SquadTacticalConfig.matchesOverrideVehicle(activeVehicle))) {
            shouldExit = true;
        } else if (SquadTacticalConfig.getVehicleOverride() == VehicleCommandMode.FORCE_BOARD) {
            Object ovVeh = SquadTacticalConfig.getOverrideVehicle();
            if (ovVeh instanceof BaseVehicle bv && bv != activeVehicle && isVehicleValidInWorld(bv)) {
                shouldExit = true;
            }
        } else if (leader != null) {
            BaseVehicle leaderVeh = leader.getVehicle();
            if (leaderVeh != activeVehicle) {
                shouldExit = true;
            }
        }

        if (!shouldExit) {
            return;
        }
        if (Math.abs(activeVehicle.getCurrentSpeedKmHour()) > STOPPED_SPEED_KMH) {
            return; // 严禁在高速行驶途中甩下车，等待车停稳再安全下车
        }

        int exitSeat = VehicleCoRideHelper.findBestExitSeat(activeVehicle, npc, curSeat);
        if (exitSeat != curSeat && exitSeat >= 1 && !activeVehicle.isSeatOccupied(exitSeat)
                && activeVehicle.canSwitchSeat(curSeat, exitSeat)) {
            beginSeatSwitch(npc, curSeat, exitSeat, SubState.EXIT_SWITCH_SEAT);
            return;
        }

        beginExitDoorAndAnim(npc, curSeat);
    }

    private void tickExitSwitchSeat(IsoNPC npc) {
        if (npc.getVehicle() != activeVehicle || !isVehicleValidInWorld(activeVehicle)) {
            VehicleCoRideHelper.forceDetachFromVehicle(npc, "EXIT_SWITCH_ABORTED");
            return;
        }
        int curSeat = activeVehicle.getSeat(npc);
        if (curSeat >= 0 && curSeat != finalSeat && !activeVehicle.isSeatOccupied(finalSeat)) {
            activeVehicle.playSwitchSeatAnim(curSeat, finalSeat);
        }
        boolean animDone = "true".equalsIgnoreCase(npc.getVariableString("SwitchSeatAnimationFinished"));
        if (!animDone && subStateElapsedSec < 2.0) {
            return;
        }
        npc.clearVariable("SwitchSeatAnimationFinished");
        npc.clearVariable("bSwitchingSeat");
        if (curSeat >= 0 && curSeat != finalSeat && !activeVehicle.isSeatOccupied(finalSeat)) {
            activeVehicle.switchSeat(npc, finalSeat);
        }
        int exitSeat = activeVehicle.getSeat(npc);
        if (exitSeat < 0) {
            VehicleCoRideHelper.forceDetachFromVehicle(npc, "EXIT_SWITCH_LOST");
            return;
        }
        beginExitDoorAndAnim(npc, exitSeat);
    }

    private void beginExitDoorAndAnim(IsoNPC npc, int seat) {
        finalSeat = seat;
        entrySeat = seat;
        useDoor2 = activeVehicle.isPassengerUseDoor2(npc, seat);
        activeDoorPart = VehicleCoRideHelper.resolveDoorPart(activeVehicle, seat, useDoor2);
        if (activeDoorPart != null && activeDoorPart.getInventoryItem() != null
                && activeDoorPart.getDoor() != null && !activeDoorPart.getDoor().isOpen()) {
            VehicleCoRideHelper.unlockAndOpenDoor(activeVehicle, activeDoorPart, seat, npc, null);
            setSubState(SubState.EXIT_OPEN_DOOR);
        } else {
            startExitAnimation(npc, seat);
        }
    }

    private void tickExitOpenDoor(IsoNPC npc) {
        if (!isVehicleValidInWorld(activeVehicle) || Math.abs(activeVehicle.getCurrentSpeedKmHour()) > 2.0F) {
            setSubState(SubState.RIDING);
            return;
        }
        if (subStateElapsedSec >= OPEN_DOOR_SEC) {
            startExitAnimation(npc, finalSeat);
        }
    }

    private void startExitAnimation(IsoNPC npc, int seat) {
        if (!isVehicleValidInWorld(activeVehicle) || seat < 0) {
            VehicleCoRideHelper.forceDetachFromVehicle(npc, "EXIT_START_INVALID");
            return;
        }
        npc.setVariable("bExitingVehicle", true);
        npc.clearVariable("ExitAnimationFinished");
        activeVehicle.playPassengerSound(seat, "exit");
        activeVehicle.playPassengerAnim(seat, "exit");
        setSubState(SubState.EXITING);
    }

    private void tickExiting(BrainContext ctx, IsoNPC npc) {
        if (npc.getVehicle() != activeVehicle || !isVehicleValidInWorld(activeVehicle)) {
            VehicleCoRideHelper.forceDetachFromVehicle(npc, "EXIT_INTERRUPTED");
            finishExitAndCheckDismiss(ctx);
            return;
        }
        int curSeat = activeVehicle.getSeat(npc);
        if (curSeat >= 0) {
            activeVehicle.playPassengerAnim(curSeat, "exit");
        }
        boolean animDone = "true".equalsIgnoreCase(npc.getVariableString("ExitAnimationFinished"));
        if (!animDone && subStateElapsedSec < ANIM_TIMEOUT_SEC) {
            return;
        }
        VehicleCoRideHelper.forceDetachFromVehicle(npc, "EXIT_COMPLETE");
        ctx.manualExitVehicleRequested = false;
        finishExitAndCheckDismiss(ctx);
    }

    private void finishExitAndCheckDismiss(BrainContext ctx) {
        resetLocalState();
        if (ctx.pendingDismissAfterExit) {
            ctx.pendingDismissAfterExit = false;
            NpcManager.completePendingDismissToSettlement(ctx.npcId);
        }
    }

    private void resetLocalState() {
        subState = SubState.POSTURE_WAIT;
        activeVehicle = null;
        finalSeat = -1;
        entrySeat = -1;
        useDoor2 = false;
        activeDoorPart = null;
        subStateElapsedSec = 0.0;
        lastApproachGoalZ = Integer.MIN_VALUE;
    }

    private void setSubState(SubState next) {
        this.subState = next;
        this.subStateElapsedSec = 0.0;
    }

    private void syncInVehicleSquare(IsoNPC npc) {
        try {
            npc.setCurrentSquareFromPosition();
            if (npc.getCurrentSquare() != null) {
                npc.setMovingSquareNow();
            }
        } catch (Throwable ignored) {}
    }

    private BaseVehicle resolveDesiredBoardVehicle(IsoNPC npc, IsoPlayer leader) {
        OrderMode orderMode = SquadTacticalConfig.getOrderMode();
        if (orderMode == OrderMode.HOLD_POSITION) {
            return null;
        }
        VehicleCommandMode ov = SquadTacticalConfig.getVehicleOverride();
        if (ov == VehicleCommandMode.FORCE_EXIT) {
            return null;
        }
        if (ov == VehicleCommandMode.FORCE_BOARD) {
            Object ovObj = SquadTacticalConfig.getOverrideVehicle();
            if (ovObj instanceof BaseVehicle bv && isVehicleValidInWorld(bv)) {
                return bv;
            }
            if (leader != null && leader.getVehicle() != null) {
                return leader.getVehicle();
            }
            return VehicleCoRideHelper.findNearestBoardableVehicle(leader, 12.0F);
        }
        if (!SquadTacticalConfig.isAutoVehicle() || leader == null) {
            return null;
        }
        return leader.getVehicle();
    }

    private static boolean isVehicleValidInWorld(BaseVehicle veh) {
        return veh != null && !veh.isRemovedFromWorld() && veh.getSquare() != null;
    }

    private boolean scanAndCheckCandidate(BaseVehicle veh, IsoNPC npc, IsoPlayer leader) {
        int max = prepareSeatScanBuffers(veh, npc, leader);
        return VehicleSeatRegistry.hasCandidateSeat(
                veh, npc.getNpcId(), max, tmpInstalled, tmpOccupied, tmpOccupantNpcIds, tmpDirectClear, tmpCanSwitch);
    }

    private SeatAllocation allocateSeat(BaseVehicle veh, IsoNPC npc, IsoPlayer leader) {
        int max = prepareSeatScanBuffers(veh, npc, leader);
        return VehicleSeatRegistry.chooseAndReserve(
                veh, npc.getNpcId(), max, tmpInstalled, tmpOccupied, tmpOccupantNpcIds,
                tmpDirectClear, tmpCanSwitch, tmpDoorDistSq);
    }

    private int prepareSeatScanBuffers(BaseVehicle veh, IsoNPC npc, IsoPlayer leader) {
        int max = Math.max(0, veh.getMaxPassengers());
        if (tmpInstalled.length < max) {
            int cap = Math.max(16, max);
            tmpInstalled = new boolean[cap];
            tmpOccupied = new boolean[cap];
            tmpOccupantNpcIds = new String[cap];
            tmpDirectClear = new boolean[cap];
            tmpCanSwitch = new boolean[cap][cap];
            tmpDoorDistSq = new float[cap];
        }
        for (int s = 0; s < max; s++) {
            tmpInstalled[s] = veh.isSeatInstalled(s);
            IsoGameCharacter occupant = veh.getCharacter(s);
            tmpOccupied[s] = veh.isSeatOccupied(s);
            tmpOccupantNpcIds[s] = (occupant instanceof IsoNPC onpc) ? onpc.getNpcId() : null;
            tmpDirectClear[s] = VehicleCoRideHelper.isSeatDirectlyEnterable(veh, s, npc, leader);
            ApproachTarget pt = tmpDirectClear[s]
                    ? VehicleCoRideHelper.resolveDoorApproachPoint(veh, s, npc, leader)
                    : null;
            tmpDoorDistSq[s] = pt != null
                    ? VehicleCoRideHelper.distSq(npc.getX(), npc.getY(), pt.x(), pt.y())
                    : 1.0e6F;
            for (int t = 0; t < max; t++) {
                tmpCanSwitch[s][t] = (s != t) && veh.canSwitchSeat(s, t);
            }
        }
        return max;
    }

    private static IsoPlayer resolveLeader(int idx) {
        if (IsoPlayer.players == null || idx < 0 || idx >= IsoPlayer.players.length) {
            return null;
        }
        return IsoPlayer.players[idx];
    }
}
