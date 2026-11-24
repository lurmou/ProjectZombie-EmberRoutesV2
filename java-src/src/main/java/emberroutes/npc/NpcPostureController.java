package emberroutes.npc;

import emberroutes.npc.EmberPostureMath.BedApproach;
import emberroutes.npc.EmberPostureMath.BedFacing;
import emberroutes.npc.EmberPostureMath.BedSlot;
import emberroutes.npc.brain.TempPropHelper;
import emberroutes.warehouse.SmartObjectRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.joml.Vector3f;
import zombie.ai.states.IdleState;
import zombie.ai.states.PlayerGetUpState;
import zombie.ai.states.PlayerOnBedState;
import zombie.ai.states.PlayerSitOnFurnitureState;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.core.skinnedmodel.advancedanimation.AnimLayer;
import zombie.core.skinnedmodel.advancedanimation.LiveAnimNode;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.SkeletonBone;
import zombie.debug.DebugLog;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.iso.Vector3;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteGrid;
import zombie.seating.SeatingManager;
import zombie.tileDepth.TileGeometryFile;
import zombie.tileDepth.TileGeometryManager;

/**
 * M3 阶段 A 原生姿势动作控制器（椅子就坐 + 单双人床四向卧睡 + 起身恢复 + 3.0s 硬看门狗）：
 * <ul>
 *   <li>100% 复用原版 B42 {@code PlayerSitOnFurnitureState}、{@code PlayerOnBedState} 与 {@code PlayerGetUpState}；</li>
 *   <li>严禁调用 {@code SeatingManager.getFacingDirection(bed)} 解析床朝向，统一读取贴图 {@code Facing} 属性；</li>
 *   <li>进入床/椅或起身期间锁定 {@link EmberLocomotionController}，避免争抢根节点位移与转身。</li>
 * </ul>
 */
public final class NpcPostureController {
    public enum PostureState {
        STANDING,
        APPROACHING_SEAT,
        ENTERING_SEAT,
        SEAT_BRIDGE_WAIT,
        SEATED,
        APPROACHING_BED,
        ENTERING_BED,
        ON_BED_AWAKE,
        SLEEPING,
        WAKING_BED,
        EXITING_BED_TO_SIT,
        STANDING_UP
    }

    private enum PendingKind {
        NONE,
        MOVE_TO,
        FOLLOW,
        SIT,
        SLEEP
    }

    private static final String[] CARDINALS = {"N", "S", "W", "E"};
    private static final String[] SEAT_SIDES = {"Front", "Left", "Right"};
    private static final long TURN_WAIT_MAX_MS = 900L;
    private static final long PRE_ENTRY_SETTLE_MS = 200L;
    private static final float PRE_ENTRY_ANGLE_TOL_RAD = 0.14F;
    private static final long IDLE_READY_MAX_MS = 700L;
    private static final long RETRY_FIRE_MS = 500L;
    private static final long ENTER_WATCHDOG_MS = 4500L;
    private static final long WAKE_TO_STAND_MS = 1200L;
    private static final long STANDUP_WATCHDOG_MS = 3000L;
    private static final float PRECISION_ARRIVAL_MAX_ERR = 0.12F;
    private static final float BED_ASLEEP_SPEED_SCALE = 0.35F;
    private static final float BED_AWAKE_SPEED_SCALE = 0.48F;
    private static final float BED_FROM_SIT_DURATION_SEC = 0.85F;
    private static final float BED_TO_SIT_DURATION_SEC = 0.72F;
    private static final long SIT_AWAKE_TO_ASLEEP_MS = 650L;
    private static final long SIT_BRIDGE_SETTLE_MS = 160L;
    private static final long AWAKE_TO_ASLEEP_PREP_MS = 100L;
    private static final float HEAD_TILE_PRIORITY_BONUS = -100.0F;
    private static final float PRE_ENTRY_POS_TOL = 0.025F;
    private static final float PRE_ENTRY_GLIDE_SPEED = 1.65F;
    private static final long BED_TRACE_CLIMB_INTERVAL_MS = 120L;
    private static final long BED_TRACE_HANDOFF_INTERVAL_MS = 60L;
    private static final long BED_TRACE_HANDOFF_WINDOW_MS = 400L;

    private PostureState state = PostureState.STANDING;
    private IsoObject targetObj;
    private IsoObject bedSlotOtherTile;
    private long pendingMoveSeq;

    private float approachX;
    private float approachY;
    private int approachZ;
    private boolean hasSafeStandPos;
    private float safeStandX;
    private float safeStandY;
    private float safeStandZ;

    private IsoDirections sitDir = IsoDirections.S;
    private String seatSideStr = "Front";
    private IsoDirections beforeDir = IsoDirections.S;
    private IsoDirections lieDir = IsoDirections.S;
    private String onBedDirection = "HeadLeft";
    private String bedType = "averageBed";

    private long stateEnteredMs;
    private long turnStartedMs;
    private long turnSettleStartMs;
    private long eventFiredMs;
    private boolean eventFired;
    private boolean eventRetried;

    private float bedCenterX;
    private float bedCenterY;
    private float bedSlidePrevU;
    private boolean leavingBed;
    private float bedExitTargetX;
    private float bedExitTargetY;
    private float bedExitPrevU;
    private float bedMaxLiftZ;
    private volatile float cachedBedRenderLiftZ;
    private boolean sleepInSitState;
    private boolean enterBedFromSit;
    private long bedTraceUntilMs;
    private long bedTraceLastLogMs;
    private String bedTraceLastPhase = "";
    private final Vector3 scratchWorldVec = new Vector3();
    private float bedSitProgress01;
    private IsoObject bedHeadTargetForSitBridge;
    private IsoObject bedOtherTileForSitBridge;
    private BedFacing bedLieFacingForSitBridge;
    private boolean standUpAfterSitBridge;
    private boolean urgentAfterSitBridge;
    private IsoObject bedApproachFallbackBed;
    private IsoObject bedApproachExcludedTile;
    private boolean bedApproachFallbackUsed;
    private IsoObject bedSeatTile;
    private IsoDirections bedSeatDir = IsoDirections.S;
    private float bedSeatX;
    private float bedSeatY;
    private float bedSlideStartX;
    private float bedSlideStartY;
    private List<SeatCandidate> activeSeatCandidates;

    private PendingKind pendingKind = PendingKind.NONE;
    private double pendingMoveX;
    private double pendingMoveY;
    private double pendingMoveZ;
    private boolean pendingMoveRun;
    private int pendingFollowPlayerIndex;
    private int pendingFollowSlot;
    private IsoObject pendingTargetObj;

    public PostureState getState() {
        return state;
    }

    public float getCachedBedRenderLiftZ() {
        return cachedBedRenderLiftZ;
    }

    public IsoObject getTargetObject() {
        return targetObj;
    }

    public IsoObject getSecondaryBedTile() {
        return bedSlotOtherTile;
    }

    public boolean hasSafeStandPos() {
        return hasSafeStandPos;
    }

    public float getSafeStandX() {
        return safeStandX;
    }

    public float getSafeStandY() {
        return safeStandY;
    }

    public float getSafeStandZ() {
        return safeStandZ;
    }

    public boolean isActive() {
        return state != PostureState.STANDING;
    }

    /** 1:1 对齐原版 {@code PathFindBehavior2.shouldIgnoreCollisionWithSquare}，入座前对准微调期放行当前椅格碰撞。 */
    public boolean shouldIgnoreCollisionWithSquare(IsoGridSquare square) {
        return square != null
                && (state == PostureState.APPROACHING_SEAT || state == PostureState.ENTERING_SEAT)
                && targetObj != null
                && targetObj.getSquare() == square;
    }

    public boolean isBlockingLocomotion() {
        return switch (state) {
            case ENTERING_SEAT, SEAT_BRIDGE_WAIT, SEATED, ENTERING_BED,
                 ON_BED_AWAKE, SLEEPING, WAKING_BED, EXITING_BED_TO_SIT, STANDING_UP -> true;
            default -> false;
        };
    }

    /**
     * 适配器与驻留坐/卧阶段位置由控制器显式接管，屏蔽原版根位移（绕过 B42 AnimationPlayer.play 漏传 m_useDeferredMovement）。
     */
    public boolean isSuppressingDeferredMovement() {
        return switch (state) {
            case ENTERING_BED -> enterBedFromSit;
            case ON_BED_AWAKE, SLEEPING, WAKING_BED, EXITING_BED_TO_SIT,
                 SEAT_BRIDGE_WAIT, SEATED -> true;
            default -> false;
        };
    }

    public static boolean isBedObject(IsoObject obj) {
        return SmartObjectRegistry.isBedObject(obj);
    }

    public static boolean isSittableFurniture(IsoObject obj) {
        return SmartObjectRegistry.isSittableFurniture(obj);
    }

    public void queueMoveAfterStandUp(IsoNPC npc, double x, double y, double z, boolean running) {
        this.pendingKind = PendingKind.MOVE_TO;
        this.pendingMoveX = x;
        this.pendingMoveY = y;
        this.pendingMoveZ = z;
        this.pendingMoveRun = running;
        this.pendingTargetObj = null;
        commandStandUp(npc, true);
    }

    public void queueFollowAfterStandUp(IsoNPC npc, int playerIndex, int slot) {
        this.pendingKind = PendingKind.FOLLOW;
        this.pendingFollowPlayerIndex = playerIndex;
        this.pendingFollowSlot = slot;
        this.pendingTargetObj = null;
        commandStandUp(npc, true);
    }

    public void clearPendingContinuation() {
        this.pendingKind = PendingKind.NONE;
        this.pendingTargetObj = null;
    }

    public boolean commandSitOnFurniture(IsoNPC npc, IsoObject clickedFurniture) {
        if (npc == null || npc.isDead() || !isSittableFurniture(clickedFurniture) || !hasAnimReady(npc)) {
            return false;
        }
        TempPropHelper.cleanupAllTempProps(npc);
        boolean onBedPhase = state == PostureState.SLEEPING
                || state == PostureState.ON_BED_AWAKE
                || state == PostureState.WAKING_BED
                || state == PostureState.ENTERING_BED
                || state == PostureState.EXITING_BED_TO_SIT;
        if (onBedPhase && targetObj != null && containsObject(collectGridObjects(clickedFurniture), targetObj)) {
            if (state == PostureState.EXITING_BED_TO_SIT) {
                clearPendingContinuation();
                this.standUpAfterSitBridge = false;
                this.urgentAfterSitBridge = false;
                return true;
            }
            if (sleepInSitState && bedSeatTile != null && bedSeatTile.getObjectIndex() != -1) {
                clearPendingContinuation();
                this.standUpAfterSitBridge = false;
                this.urgentAfterSitBridge = false;
                long now = System.currentTimeMillis();
                if (state == PostureState.SLEEPING || state == PostureState.ON_BED_AWAKE) {
                    startExitingBedToSit(npc, now);
                } else if (state == PostureState.ENTERING_BED) {
                    this.enterBedFromSit = false;
                    startExitingBedToSit(npc, now);
                }
                DebugLog.log(String.format(Locale.US,
                        "[EmberRoutes][POSTURE_DIAG] BED_TO_SIT_CMD npc=%s state=%s seatDir=%s",
                        npc.getNpcId(), this.state.name(), bedSeatDir.name()));
                return true;
            }
        }
        if (isBlockingLocomotion()) {
            this.pendingKind = PendingKind.SIT;
            this.pendingTargetObj = clickedFurniture;
            commandStandUp(npc, false);
            return true;
        }
        if (state == PostureState.APPROACHING_SEAT || state == PostureState.APPROACHING_BED) {
            npc.getLocomotion().stop(npc);
            clearActiveFields();
            state = PostureState.STANDING;
        }

        IsoCell cell = currentCell();
        SeatingManager sm = SeatingManager.getInstance();
        if (cell == null || sm == null) {
            return false;
        }

        ArrayList<IsoObject> tiles = collectGridObjects(clickedFurniture);
        Vector3f exactPos = new Vector3f();
        Vector3f pathPos = new Vector3f();
        List<SeatCandidate> candidates = new ArrayList<>();

        for (int ti = 0; ti < tiles.size(); ti++) {
            IsoObject tile = tiles.get(ti);
            if (!isSittableFurniture(tile) || tile.getSquare() == null) {
                continue;
            }
            if (tile.isFurnitureOccupied(npc) || NpcManager.isFurnitureTileClaimedByOther(npc, tile)) {
                continue;
            }
            IsoGridSquare sq = tile.getSquare();
            for (String dirStr : CARDINALS) {
                for (String sideStr : SEAT_SIDES) {
                    if (!sm.getAdjacentPosition(npc, tile, dirStr, sideStr,
                            "sitonfurniture", "SitOnFurniture" + sideStr, exactPos)) {
                        continue;
                    }
                    if (!computeSeatApproachPair(npc, cell, sq, dirStr, sideStr, exactPos, pathPos)) {
                        continue;
                    }
                    float dist = dist3D(npc, pathPos.x, pathPos.y, sq.getZ());
                    float score = dist + (tile == clickedFurniture ? -0.5F : 0.0F);
                    BedFacing sitFacing = BedFacing.normalize(dirStr);
                    IsoDirections iSitDir = toIsoDir(sitFacing);
                    IsoDirections iBeforeDir = toIsoDir(EmberPostureMath.resolveSeatBeforeDir(sitFacing, sideStr));
                    candidates.add(new SeatCandidate(tile, iSitDir, sideStr, iBeforeDir,
                            pathPos.x, pathPos.y, exactPos.x, exactPos.y, sq.getZ(), score));
                }
            }
        }

        if (candidates.isEmpty()) {
            DebugLog.log(String.format(Locale.US,
                    "[EmberRoutes][POSTURE_DIAG] SIT_NO_SLOT npc=%s obj=%s",
                    npc.getNpcId(), safeSpriteName(clickedFurniture)));
            return false;
        }

        candidates.sort(Comparator.comparingDouble(c -> c.score));
        SeatCandidate best = candidates.get(0);

        npc.getFollow().stop(npc, false, "POSTURE_SIT");
        applySeatCandidate(best);
        this.bedSlotOtherTile = null;
        this.activeSeatCandidates = candidates;
        clearSitBridgeTargets();
        clearBedFallbackTargets();
        this.standUpAfterSitBridge = false;
        this.urgentAfterSitBridge = false;
        this.bedSeatTile = null;
        this.pendingMoveSeq = issuePrecisionSeatMove(npc, candidates, best.tile.getSquare());
        enterApproachingState(PostureState.APPROACHING_SEAT);

        DebugLog.log(String.format(Locale.US,
                "[EmberRoutes][POSTURE_DIAG] SIT_CMD npc=%s sprite=%s sitDir=%s side=%s beforeDir=%s path=(%.2f,%.2f) app=(%.2f,%.2f,%d) cands=%d seq=%d",
                npc.getNpcId(), safeSpriteName(best.tile), best.sitDir.name(), best.sideStr, best.beforeDir.name(),
                best.pathX, best.pathY, best.exactX, best.exactY, best.z, candidates.size(), pendingMoveSeq));
        return true;
    }

    public boolean commandSleepOnBed(IsoNPC npc, IsoObject clickedBed) {
        return commandSleepOnBedInternal(npc, clickedBed, null, false);
    }

    private boolean commandSleepOnBedInternal(
            IsoNPC npc, IsoObject clickedBed, IsoObject excludeTile, boolean fallbackUsed) {
        if (npc == null || npc.isDead() || !isBedObject(clickedBed) || clickedBed.getSquare() == null || !hasAnimReady(npc)) {
            return false;
        }
        IsoCell cell = currentCell();
        if (cell == null) {
            return false;
        }
        TempPropHelper.cleanupAllTempProps(npc);

        ArrayList<IsoObject> bedTiles = collectGridObjects(clickedBed);
        BedLayout layout = resolveBedLayout(clickedBed, bedTiles);

        // 1. 已坐在同一张床侧沿时，免起身直转入睡（坐在床尾则就地以床尾为头、反转卧向）
        if ((state == PostureState.SEATED || state == PostureState.SEAT_BRIDGE_WAIT)
                && targetObj != null && containsObject(bedTiles, targetObj)) {
            BedSlot matchingSlot = findSlotContainingTile(layout, targetObj);
            if (matchingSlot != null && slotHasApproachBeforeDir(matchingSlot, toBedFacing(sitDir).opposite())) {
                IsoObject headObj = layout.gridObjects[matchingSlot.headGridX()][matchingSlot.headGridY()];
                IsoObject footObj = layout.gridObjects[matchingSlot.footGridX()][matchingSlot.footGridY()];
                boolean reversedAtFoot = (footObj != null && footObj != headObj && targetObj == footObj);
                IsoObject effectiveHeadObj = reversedAtFoot ? footObj : headObj;
                IsoObject otherSlotObj = reversedAtFoot ? headObj : footObj;
                BedFacing effectiveLieFacing = reversedAtFoot ? layout.facing.opposite() : layout.facing;
                String sideOnBedDir = EmberPostureMath.resolveOnBedSideFromSitDir(effectiveLieFacing, toBedFacing(sitDir));
                if (sideOnBedDir != null && effectiveHeadObj != null && effectiveHeadObj.getObjectIndex() != -1
                        && !isBedSlotOccupied(npc, headObj, footObj, layout.originX, layout.originY, layout.bedZ, matchingSlot)) {
                    clearPendingContinuation();
                    clearSitBridgeTargets();
                    clearBedFallbackTargets();
                    this.standUpAfterSitBridge = false;
                    this.urgentAfterSitBridge = false;
                    beginDirectSitToBed(npc, targetObj, sitDir, effectiveHeadObj, otherSlotObj, bedTiles,
                            effectiveLieFacing, sideOnBedDir, layout.bedZ);
                    return true;
                }
            }
        }

        if (isBlockingLocomotion()) {
            this.pendingKind = PendingKind.SLEEP;
            this.pendingTargetObj = clickedBed;
            commandStandUp(npc, false);
            return true;
        }
        if (state == PostureState.APPROACHING_SEAT || state == PostureState.APPROACHING_BED) {
            npc.getLocomotion().stop(npc);
            clearActiveFields();
            state = PostureState.STANDING;
        }

        // 2. 站立走近睡眠：优先床沿坐姿适配层落座再躺平；床头不可达时自动退而头睡床尾（反转卧向，手性镜像复用 Left/Right）
        BedViaSitCandidate sitCand = findBestBedViaSitCandidate(npc, cell, layout, excludeTile);
        if (sitCand != null) {
            npc.getFollow().stop(npc, false, "POSTURE_SLEEP_VIA_SIT");
            SeatCandidate primary = sitCand.primary;
            applySeatCandidate(primary);
            this.bedSlotOtherTile = (sitCand.otherSlotObj != null && sitCand.otherSlotObj != primary.tile)
                    ? sitCand.otherSlotObj
                    : null;
            this.activeSeatCandidates = sitCand.sideCandidates;
            this.bedHeadTargetForSitBridge = sitCand.sleepHeadObj;
            this.bedOtherTileForSitBridge = this.bedSlotOtherTile;
            this.bedLieFacingForSitBridge = sitCand.lieFacing;
            this.bedApproachFallbackBed = (!fallbackUsed && sitCand.isHeadTile) ? clickedBed : null;
            this.bedApproachExcludedTile = (!fallbackUsed && sitCand.isHeadTile) ? primary.tile : null;
            this.bedApproachFallbackUsed = fallbackUsed;
            this.standUpAfterSitBridge = false;
            this.urgentAfterSitBridge = false;
            this.bedSeatTile = null;
            this.pendingMoveSeq = issuePrecisionSeatMove(npc, sitCand.sideCandidates, primary.tile.getSquare());
            enterApproachingState(PostureState.APPROACHING_SEAT);

            DebugLog.log(String.format(Locale.US,
                    "[EmberRoutes][POSTURE_DIAG] BED_VIA_SIT_CMD npc=%s seatSprite=%s sleepHead=%s headAtFoot=%s lieDir=%s sitDir=%s side=%s beforeDir=%s path=(%.2f,%.2f) app=(%.2f,%.2f,%d) seq=%d",
                    npc.getNpcId(), safeSpriteName(primary.tile), safeSpriteName(sitCand.sleepHeadObj),
                    !sitCand.isHeadTile, sitCand.lieFacing.name(),
                    primary.sitDir.name(), primary.sideStr, primary.beforeDir.name(),
                    primary.pathX, primary.pathY, primary.exactX, primary.exactY, primary.z, pendingMoveSeq));
            return true;
        }

        String propsFacingRaw = clickedBed.getProperties().get("Facing");
        String seatingFacingRaw = SeatingManager.getInstance() != null
                ? SeatingManager.getInstance().getFacingDirection(clickedBed)
                : "?";

        BedCandidate best = findBestDirectBedCandidate(npc, cell, layout, excludeTile);

        if (best == null) {
            DebugLog.log(String.format(Locale.US,
                    "[EmberRoutes][POSTURE_DIAG] BED_NO_SLOT npc=%s sprite=%s propsFacing=%s grid=%dx%d",
                    npc.getNpcId(), safeSpriteName(clickedBed), String.valueOf(propsFacingRaw), layout.gridW, layout.gridH));
            return false;
        }

        npc.getFollow().stop(npc, false, "POSTURE_SLEEP");
        BedApproach app = best.approach;
        this.targetObj = best.effectiveHeadObj;
        this.bedSlotOtherTile = (best.otherSlotObj != null && best.otherSlotObj != best.effectiveHeadObj)
                ? best.otherSlotObj
                : null;
        this.activeSeatCandidates = null;
        this.onBedDirection = app.onBedDirection();
        this.beforeDir = toIsoDir(app.beforeDir());
        this.lieDir = toIsoDir(app.lieDir());
        this.bedType = resolveBedType(best.effectiveHeadObj);
        this.approachX = app.approachX();
        this.approachY = app.approachY();
        this.approachZ = layout.bedZ;
        this.bedCenterX = best.effectiveHeadObj.getSquare().getX() + 0.5F;
        this.bedCenterY = best.effectiveHeadObj.getSquare().getY() + 0.5F;
        this.bedSlidePrevU = 0.0F;
        this.leavingBed = false;
        this.bedExitPrevU = 0.0F;
        this.enterBedFromSit = false;
        clearSitBridgeTargets();
        this.bedApproachFallbackBed = (!fallbackUsed && app.headEntry()) ? clickedBed : null;
        this.bedApproachExcludedTile = (!fallbackUsed && app.headEntry()) ? best.effectiveHeadObj : null;
        this.bedApproachFallbackUsed = fallbackUsed;
        this.standUpAfterSitBridge = false;
        this.urgentAfterSitBridge = false;
        this.bedSeatTile = null;
        this.bedMaxLiftZ = resolveBedMaxLiftZ(best.effectiveHeadObj, bedTiles);
        this.cachedBedRenderLiftZ = 0.0F;
        this.bedTraceUntilMs = 0L;
        this.bedTraceLastLogMs = 0L;
        this.bedTraceLastPhase = "";
        saveSafeStandPos(app.approachX(), app.approachY(), layout.bedZ);
        this.pendingMoveSeq = npc.getLocomotion().moveToExact(
                npc,
                List.of(new EmberLocomotionController.PrecisionTarget(
                        app.approachX(), app.approachY(), app.approachX(), app.approachY(), layout.bedZ, 0, null)),
                null);
        enterApproachingState(PostureState.APPROACHING_BED);

        DebugLog.log(String.format(Locale.US,
                "[EmberRoutes][POSTURE_DIAG] BED_CMD npc=%s sprite=%s propsFacing=%s resolved=%s seatingFacing=%s grid=%dx%d slot=%d/%d headAtFoot=%s onBedDir=%s beforeDir=%s lieDir=%s app=(%.2f,%.2f,%d) liftZ=%.3f seq=%d",
                npc.getNpcId(), safeSpriteName(best.effectiveHeadObj), String.valueOf(propsFacingRaw), layout.facing.name(),
                String.valueOf(seatingFacingRaw), layout.gridW, layout.gridH, best.slotIndex, layout.slots.size(),
                !app.headEntry(), onBedDirection, beforeDir.name(), lieDir.name(), app.approachX(), app.approachY(), layout.bedZ,
                bedMaxLiftZ, pendingMoveSeq));
        return true;
    }

    public boolean commandStandUp(IsoNPC npc, boolean urgent) {
        if (npc == null || state == PostureState.STANDING) {
            return false;
        }
        TempPropHelper.cleanupAllTempProps(npc);
        long now = System.currentTimeMillis();
        switch (state) {
            case APPROACHING_SEAT, APPROACHING_BED -> {
                npc.getLocomotion().stop(npc);
                clearActiveFields();
                state = PostureState.STANDING;
                return true;
            }
            case ENTERING_SEAT -> {
                clearSitBridgeTargets();
                if (!eventFired && !npc.isSittingOnFurniture()) {
                    clearActiveFields();
                    state = PostureState.STANDING;
                } else {
                    if (urgent) {
                        npc.setVariable("getUpQuick", true);
                    }
                    npc.setVariable("forceGetUp", true);
                    state = PostureState.STANDING_UP;
                    stateEnteredMs = now;
                }
                return true;
            }
            case SEAT_BRIDGE_WAIT, SEATED -> {
                clearSitBridgeTargets();
                this.standUpAfterSitBridge = false;
                this.urgentAfterSitBridge = false;
                if (urgent) {
                    npc.setVariable("getUpQuick", true);
                }
                npc.setVariable("forceGetUp", true);
                state = PostureState.STANDING_UP;
                stateEnteredMs = now;
                return true;
            }
            case ENTERING_BED -> {
                if (!eventFired && !npc.isOnBed() && !sleepInSitState) {
                    clearActiveFields();
                    state = PostureState.STANDING;
                } else if (enterBedFromSit && sleepInSitState) {
                    this.standUpAfterSitBridge = true;
                    this.urgentAfterSitBridge = urgent;
                    startExitingBedToSit(npc, now);
                } else {
                    prepareBedExit(npc);
                    npc.setAsleep(false);
                    npc.setVariable("OnBedAnim", "Awake");
                    npc.faceDirection(lieDir);
                    npc.setVariable("forceGetUp", true);
                    state = PostureState.STANDING_UP;
                    stateEnteredMs = now;
                }
                return true;
            }
            case ON_BED_AWAKE, SLEEPING -> {
                npc.setAsleep(false);
                if (sleepInSitState) {
                    this.standUpAfterSitBridge = true;
                    this.urgentAfterSitBridge = urgent;
                    if (bedSeatTile != null && bedSeatTile.getObjectIndex() != -1) {
                        startExitingBedToSit(npc, now);
                    } else {
                        npc.clearVariable("ER_SitSleepAnim");
                        npc.setBed(null);
                        npc.blockTurning = false;
                        this.sleepInSitState = false;
                        npc.setVariable("forceGetUp", true);
                        state = PostureState.STANDING_UP;
                        stateEnteredMs = now;
                    }
                    return true;
                }
                npc.setVariable("OnBedAnim", "Awake");
                this.standUpAfterSitBridge = false;
                if (urgent || state == PostureState.ON_BED_AWAKE) {
                    prepareBedExit(npc);
                    npc.faceDirection(lieDir);
                    npc.setVariable("forceGetUp", true);
                    state = PostureState.STANDING_UP;
                    stateEnteredMs = now;
                } else {
                    state = PostureState.WAKING_BED;
                    stateEnteredMs = now;
                }
                return true;
            }
            case WAKING_BED -> {
                if (urgent) {
                    prepareBedExit(npc);
                    npc.faceDirection(lieDir);
                    npc.setVariable("forceGetUp", true);
                    state = PostureState.STANDING_UP;
                    stateEnteredMs = now;
                }
                return true;
            }
            case EXITING_BED_TO_SIT -> {
                this.standUpAfterSitBridge = true;
                if (urgent) {
                    this.urgentAfterSitBridge = true;
                }
                return true;
            }
            case STANDING_UP -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public void beforeUpdate(IsoNPC npc) {
        if (npc == null) {
            return;
        }
        if (state == PostureState.STANDING) {
            this.cachedBedRenderLiftZ = 0.0F;
            return;
        }
        long now = System.currentTimeMillis();
        switch (state) {
            case APPROACHING_SEAT, APPROACHING_BED -> tickApproaching(npc, now);
            case ENTERING_SEAT -> tickEnteringSeat(npc, now);
            case SEAT_BRIDGE_WAIT -> tickSeatBridgeWait(npc, now);
            case SEATED -> tickSeated(npc);
            case ENTERING_BED -> tickEnteringBed(npc, now);
            case ON_BED_AWAKE, SLEEPING -> tickOnBed(npc, now);
            case WAKING_BED -> tickWakingBed(npc, now);
            case EXITING_BED_TO_SIT -> tickExitingBedToSit(npc, now);
            case STANDING_UP -> tickStandingUp(npc, now);
            default -> {}
        }
        updateBedRenderElevation(npc, now);
    }

    private void tickApproaching(IsoNPC npc, long now) {
        EmberLocomotionController loco = npc.getLocomotion();
        if (state == PostureState.APPROACHING_SEAT
                && activeSeatCandidates != null
                && !activeSeatCandidates.isEmpty()) {
            int tag = loco.getResolvedPrecisionTag();
            if (tag >= 0 && tag < activeSeatCandidates.size()) {
                SeatCandidate resolved = activeSeatCandidates.get(tag);
                if (resolved.tile != targetObj
                        || Math.abs(resolved.exactX - approachX) > 1.0E-4F
                        || Math.abs(resolved.exactY - approachY) > 1.0E-4F) {
                    applySeatCandidate(resolved);
                }
            }
        }
        if (targetObj == null || targetObj.getObjectIndex() == -1) {
            loco.stop(npc);
            clearActiveFields();
            state = PostureState.STANDING;
            return;
        }
        int curZ = EmberTargetProjector.levelOf(npc.getZ());
        float remDist = (float) Math.hypot(approachX - npc.getX(), approachY - npc.getY());
        boolean arrivedBySettled = loco.getSettledSeq() >= pendingMoveSeq
                && loco.getStatus() == EmberLocomotionController.Status.ARRIVED
                && curZ == approachZ
                && remDist <= PRECISION_ARRIVAL_MAX_ERR;

        if (arrivedBySettled) {
            if (state == PostureState.APPROACHING_SEAT
                    && (targetObj.isFurnitureOccupied(npc) || NpcManager.isFurnitureTileClaimedByOther(npc, targetObj))) {
                loco.stop(npc);
                clearActiveFields();
                state = PostureState.STANDING;
                return;
            }
            loco.stop(npc);
            this.activeSeatCandidates = null;
            eventFired = false;
            eventRetried = false;
            turnStartedMs = 0L;
            turnSettleStartMs = 0L;
            stateEnteredMs = now;
            state = (state == PostureState.APPROACHING_BED)
                    ? PostureState.ENTERING_BED
                    : PostureState.ENTERING_SEAT;
            if (state == PostureState.ENTERING_BED) {
                emitBedTrace(npc, "ARRIVED", now, true);
            }
            return;
        }
        if (loco.getSettledSeq() >= pendingMoveSeq) {
            if (bedApproachFallbackBed != null && !bedApproachFallbackUsed
                    && bedApproachFallbackBed.getObjectIndex() != -1) {
                IsoObject retryBed = this.bedApproachFallbackBed;
                IsoObject failedTile = this.bedApproachExcludedTile;
                DebugLog.log(String.format(Locale.US,
                        "[EmberRoutes][POSTURE_DIAG] APPROACH_FAILED_BED_FALLBACK npc=%s state=%s status=%s reason=%s exclude=%s rem=%.3f",
                        npc.getNpcId(), state.name(), loco.getStatus().name(), loco.getLastReason(),
                        safeSpriteName(failedTile), remDist));
                clearActiveFields();
                state = PostureState.STANDING;
                commandSleepOnBedInternal(npc, retryBed, failedTile, true);
                return;
            }
            DebugLog.log(String.format(Locale.US,
                    "[EmberRoutes][POSTURE_DIAG] APPROACH_FAILED npc=%s state=%s status=%s reason=%s rem=%.3f",
                    npc.getNpcId(), state.name(), loco.getStatus().name(), loco.getLastReason(), remDist));
            clearActiveFields();
            state = PostureState.STANDING;
        }
    }

    private void tickEnteringSeat(IsoNPC npc, long now) {
        if (now - stateEnteredMs >= ENTER_WATCHDOG_MS) {
            forceImmediateReset(npc, "ENTER_SEAT_TIMEOUT", true);
            return;
        }
        if (!eventFired) {
            if (!isIdleReady(npc) && now - stateEnteredMs < IDLE_READY_MAX_MS) {
                return;
            }
            if (targetObj == null || targetObj.getObjectIndex() == -1
                    || targetObj.isFurnitureOccupied(npc)
                    || NpcManager.isFurnitureTileClaimedByOther(npc, targetObj)) {
                clearActiveFields();
                state = PostureState.STANDING;
                return;
            }
            if (!awaitPreEntryTurn(npc, now, beforeDir)) {
                return;
            }
            targetObj.setSatChair(true);
            npc.setSitOnFurnitureObject(targetObj);
            npc.setSitOnFurnitureDirection(sitDir);
            npc.setVariable("SitOnFurnitureDirection", seatSideStr);
            clearSitVars(npc);
            npc.setVariable("ExerciseStarted", false);
            npc.setVariable("ExerciseEnded", true);

            ensureAlignedBeforeEntry(npc, beforeDir);
            float errApp = (float) Math.hypot(approachX - npc.getX(), approachY - npc.getY());
            npc.reportEvent("EventSitOnFurniture");
            npc.faceDirection(sitDir);
            eventFired = true;
            eventFiredMs = now;
            DebugLog.log(String.format(Locale.US,
                    "[EmberRoutes][POSTURE_DIAG] SIT_FIRE npc=%s sitDir=%s side=%s beforeDir=%s errApp=%.3f",
                    npc.getNpcId(), sitDir.name(), seatSideStr, beforeDir.name(), errApp));
            return;
        }

        if (npc.isCurrentState(PlayerSitOnFurnitureState.instance())
                && (npc.getVariableBoolean("SitOnFurnitureStarted") || now - eventFiredMs > 1800L)) {
            if (bedHeadTargetForSitBridge != null) {
                state = PostureState.SEAT_BRIDGE_WAIT;
                stateEnteredMs = now;
            } else {
                state = PostureState.SEATED;
                stateEnteredMs = now;
                DebugLog.log(String.format(Locale.US,
                        "[EmberRoutes][POSTURE_DIAG] SEATED npc=%s pos=(%.2f,%.2f,%.1f) animDeg=%.1f",
                        npc.getNpcId(), npc.getX(), npc.getY(), npc.getZ(),
                        Math.toDegrees(npc.getAnimAngleRadians())));
            }
        } else if (!eventRetried
                && now - eventFiredMs > RETRY_FIRE_MS
                && npc.isCurrentState(IdleState.instance())
                && !npc.isSittingOnFurniture()) {
            eventRetried = true;
            ensureAlignedBeforeEntry(npc, beforeDir);
            npc.reportEvent("EventSitOnFurniture");
            npc.faceDirection(sitDir);
            eventFiredMs = now;
        }
    }

    private void tickSeatBridgeWait(IsoNPC npc, long now) {
        if (now - stateEnteredMs >= STANDUP_WATCHDOG_MS) {
            forceImmediateReset(npc, "SEAT_BRIDGE_TIMEOUT", false);
            executePendingContinuation(npc);
            return;
        }
        if (now - stateEnteredMs < SIT_BRIDGE_SETTLE_MS) {
            return;
        }
        if (bedHeadTargetForSitBridge != null) {
            IsoObject effectiveHead = this.bedHeadTargetForSitBridge;
            IsoObject otherTile = this.bedOtherTileForSitBridge;
            BedFacing effectiveLieFacing = this.bedLieFacingForSitBridge;
            clearSitBridgeTargets();
            if (effectiveHead.getObjectIndex() != -1 && effectiveHead.getSquare() != null
                    && targetObj != null && targetObj.getObjectIndex() != -1) {
                ArrayList<IsoObject> bedTiles = collectGridObjects(effectiveHead);
                if (effectiveLieFacing == null) {
                    effectiveLieFacing = resolveBedLayout(effectiveHead, bedTiles).facing;
                }
                String sideOnBedDir = EmberPostureMath.resolveOnBedSideFromSitDir(
                        effectiveLieFacing, toBedFacing(sitDir));
                boolean claimed = NpcManager.isBedHeadClaimedByOther(npc, effectiveHead)
                        || (otherTile != null && NpcManager.isBedHeadClaimedByOther(npc, otherTile));
                if (sideOnBedDir != null && !claimed) {
                    beginDirectSitToBed(npc, targetObj, sitDir, effectiveHead, otherTile, bedTiles,
                            effectiveLieFacing, sideOnBedDir, effectiveHead.getSquare().getZ());
                    return;
                }
            }
            this.bedSlotOtherTile = null;
            this.state = PostureState.SEATED;
            this.stateEnteredMs = now;
            return;
        }
        if (standUpAfterSitBridge) {
            boolean urgent = this.urgentAfterSitBridge;
            this.standUpAfterSitBridge = false;
            this.urgentAfterSitBridge = false;
            if (urgent) {
                npc.setVariable("getUpQuick", true);
            }
            npc.setVariable("forceGetUp", true);
            this.state = PostureState.STANDING_UP;
            this.stateEnteredMs = now;
            return;
        }
        this.state = PostureState.SEATED;
        this.stateEnteredMs = now;
    }

    private void tickSeated(IsoNPC npc) {
        if (targetObj == null || targetObj.getObjectIndex() == -1) {
            commandStandUp(npc, true);
            return;
        }
        if (!npc.isSittingOnFurniture() && npc.isCurrentState(IdleState.instance())) {
            finishStandUpClean(npc, "EXTERNAL_EXIT");
        }
    }

    private void tickEnteringBed(IsoNPC npc, long now) {
        if (enterBedFromSit && sleepInSitState) {
            if (now - stateEnteredMs >= ENTER_WATCHDOG_MS) {
                forceImmediateReset(npc, "ENTER_BED_SIT_TIMEOUT", true);
                return;
            }
            if (!npc.isSittingOnFurniture() && npc.isCurrentState(IdleState.instance())) {
                finishStandUpClean(npc, "EXTERNAL_EXIT");
                return;
            }
            bedSitProgress01 = EmberPostureMath.advanceAdapterProgress(
                    bedSitProgress01, npc.getAnimationTimeDelta(), BED_FROM_SIT_DURATION_SEC);
            float frac = EmberPostureMath.BED_SIT_HANDOFF_FRAC
                    + (1.0F - EmberPostureMath.BED_SIT_HANDOFF_FRAC) * bedSitProgress01;
            npc.setVariable("ER_BedSitFrac", frac);

            float u = EmberPostureMath.smoothstep01(bedSitProgress01);
            float nx = bedSlideStartX + (bedCenterX - bedSlideStartX) * u;
            float ny = bedSlideStartY + (bedCenterY - bedSlideStartY) * u;
            smoothMovePosition(npc, nx, ny, approachZ);
            npc.faceDirection(lieDir);
            if (bedSitProgress01 >= 1.0F) {
                completeEnterBedAwake(npc, now);
            }
            return;
        }

        if (now - stateEnteredMs >= ENTER_WATCHDOG_MS) {
            forceImmediateReset(npc, "ENTER_BED_TIMEOUT", true);
            return;
        }
        if (!eventFired) {
            // ActionState to_onbed 仅存在于 idle 状态，必须等动作状态机回到 idle 再发 EventGetOnBed
            if (!isIdleReady(npc) && now - stateEnteredMs < IDLE_READY_MAX_MS) {
                return;
            }
            boolean claimed = targetObj != null && (NpcManager.isBedHeadClaimedByOther(npc, targetObj)
                    || (bedSlotOtherTile != null && NpcManager.isBedHeadClaimedByOther(npc, bedSlotOtherTile)));
            if (targetObj == null || targetObj.getObjectIndex() == -1 || claimed) {
                clearActiveFields();
                state = PostureState.STANDING;
                return;
            }
            if (!convergePreEntryPose(npc, now, approachX, approachY, approachZ, beforeDir)) {
                emitBedTrace(npc, "PRE_ALIGN", now, false);
                return;
            }

            if (Math.hypot(approachX - npc.getX(), approachY - npc.getY()) > 0.005F) {
                snapPosition(npc, approachX, approachY, approachZ);
            }
            ensureAlignedBeforeEntry(npc, beforeDir);
            this.bedSlidePrevU = 0.0F;
            this.leavingBed = false;

            npc.setSitOnFurnitureObject(targetObj);
            npc.setVariable("OnBedDirection", onBedDirection);
            clearBedVars(npc);
            npc.setVariable("ExerciseStarted", false);
            npc.setVariable("ExerciseEnded", true);

            npc.reportEvent("EventGetOnBed");
            npc.faceDirection(lieDir);
            eventFired = true;
            eventFiredMs = now;
            DebugLog.log(String.format(Locale.US,
                    "[EmberRoutes][POSTURE_DIAG] BED_FIRE npc=%s onBedDir=%s beforeDir=%s lieDir=%s snap=(%.2f,%.2f,%d)",
                    npc.getNpcId(), onBedDirection, beforeDir.name(), lieDir.name(),
                    npc.getX(), npc.getY(), approachZ));
            emitBedTrace(npc, "BED_FIRE", now, true);
            return;
        }

        if (eventFired && npc.isCurrentState(PlayerOnBedState.instance())) {
            applyBedEntrySmoothCentering(npc, now);
            emitBedTrace(npc, "CLIMB", now, false);
        }

        if (npc.isCurrentState(PlayerOnBedState.instance())
                && (npc.getVariableBoolean("OnBedStarted") || now - eventFiredMs > 2200L)) {
            completeEnterBedAwake(npc, now);
        } else if (!eventRetried
                && now - eventFiredMs > RETRY_FIRE_MS
                && npc.isCurrentState(IdleState.instance())
                && !npc.isOnBed()
                && "idle".equalsIgnoreCase(npc.getCurrentActionContextStateName())) {
            eventRetried = true;
            this.bedSlidePrevU = 0.0F;
            if (Math.hypot(approachX - npc.getX(), approachY - npc.getY()) > 0.005F) {
                snapPosition(npc, approachX, approachY, approachZ);
            }
            ensureAlignedBeforeEntry(npc, beforeDir);
            npc.reportEvent("EventGetOnBed");
            npc.faceDirection(lieDir);
            eventFiredMs = now;
            emitBedTrace(npc, "BED_RETRY", now, true);
        }
    }

    private void completeEnterBedAwake(IsoNPC npc, long now) {
        if (sleepInSitState) {
            // 冻结终端进度为 1.0F，避免淡出中的 ER_SitToBed* 轨道被清零倒回 0%
            npc.setVariable("ER_BedSitFrac", 1.0F);
            npc.blockTurning = false;
            this.enterBedFromSit = false;
            this.bedSlidePrevU = 1.0F;
            snapPosition(npc, bedCenterX, bedCenterY, approachZ);
            float desiredRad = AnimationPlayer.calculateAnimPlayerAngle(lieDir.dx(), lieDir.dy());
            if (Math.abs(PZMath.getClosestAngle(npc.getAnimAngleRadians(), desiredRad)) > 0.03F) {
                forceAnimFacing(npc, lieDir);
            } else {
                npc.faceDirection(lieDir);
            }
            npc.setVariable("ER_SitSleepAnim", "AwakeToAsleep");
            npc.setBed(targetObj);
            npc.setBedType(bedType);
            npc.setForceWakeUpTime(-1.0F);
            this.state = PostureState.ON_BED_AWAKE;
            this.stateEnteredMs = now;
            DebugLog.log(String.format(Locale.US,
                    "[EmberRoutes][POSTURE_DIAG] ON_BED_AWAKE npc=%s pos=(%.2f,%.2f,%.1f) lieDir=%s mode=IN_SIT",
                    npc.getNpcId(), npc.getX(), npc.getY(), npc.getZ(), lieDir.name()));
            return;
        }
        this.enterBedFromSit = false;
        this.bedSlidePrevU = 1.0F;
        if (Math.hypot(bedCenterX - npc.getX(), bedCenterY - npc.getY()) > 0.005F) {
            snapPosition(npc, bedCenterX, bedCenterY, approachZ);
        }
        float desiredRad = AnimationPlayer.calculateAnimPlayerAngle(lieDir.dx(), lieDir.dy());
        if (Math.abs(PZMath.getClosestAngle(npc.getAnimAngleRadians(), desiredRad)) > 0.02F) {
            forceAnimFacing(npc, lieDir);
        }
        npc.setVariable("OnBedStarted", true);
        npc.setVariable("OnBedAnim", "Awake");
        npc.setBed(targetObj);
        npc.setBedType(bedType);
        npc.setForceWakeUpTime(-1.0F);
        this.state = PostureState.ON_BED_AWAKE;
        this.stateEnteredMs = now;
        this.bedTraceUntilMs = now + BED_TRACE_HANDOFF_WINDOW_MS;
        DebugLog.log(String.format(Locale.US,
                "[EmberRoutes][POSTURE_DIAG] ON_BED_AWAKE npc=%s pos=(%.2f,%.2f,%.1f) lieDir=%s",
                npc.getNpcId(), npc.getX(), npc.getY(), npc.getZ(), lieDir.name()));
        emitBedTrace(npc, "ON_BED_AWAKE", now, true);
    }

    private void tickOnBed(IsoNPC npc, long now) {
        if (targetObj == null || targetObj.getObjectIndex() == -1) {
            commandStandUp(npc, true);
            return;
        }
        npc.setForceWakeUpTime(-1.0F);
        if (sleepInSitState) {
            npc.faceDirection(lieDir);
            if (state == PostureState.ON_BED_AWAKE && now - stateEnteredMs >= SIT_AWAKE_TO_ASLEEP_MS) {
                npc.setVariable("ER_SitSleepAnim", "Asleep");
                npc.setAsleepTime(0.0F);
                npc.setAsleep(true);
                this.state = PostureState.SLEEPING;
                this.stateEnteredMs = now;
                DebugLog.log(String.format(Locale.US,
                        "[EmberRoutes][POSTURE_DIAG] SLEEPING npc=%s pos=(%.2f,%.2f,%.1f) animDeg=%.1f dir=%s lieDir=%s mode=IN_SIT",
                        npc.getNpcId(), npc.getX(), npc.getY(), npc.getZ(),
                        Math.toDegrees(npc.getAnimAngleRadians()),
                        npc.getDir() != null ? npc.getDir().name() : "null",
                        lieDir.name()));
            }
            if (!npc.isSittingOnFurniture() && npc.isCurrentState(IdleState.instance())) {
                finishStandUpClean(npc, "EXTERNAL_EXIT");
            }
            return;
        }
        if (state == PostureState.ON_BED_AWAKE && now - stateEnteredMs >= AWAKE_TO_ASLEEP_PREP_MS) {
            npc.setVariable("OnBedAnim", "Asleep");
            npc.setAsleepTime(0.0F);
            npc.setAsleep(true);
            this.state = PostureState.SLEEPING;
            this.stateEnteredMs = now;
            DebugLog.log(String.format(Locale.US,
                    "[EmberRoutes][POSTURE_DIAG] SLEEPING npc=%s pos=(%.2f,%.2f,%.1f) animDeg=%.1f dir=%s lieDir=%s",
                    npc.getNpcId(), npc.getX(), npc.getY(), npc.getZ(),
                    Math.toDegrees(npc.getAnimAngleRadians()),
                    npc.getDir() != null ? npc.getDir().name() : "null",
                    lieDir.name()));
        }
        applyBedIdleSpeedScale(npc);
        if (this.bedTraceUntilMs > 0L) {
            if (now <= this.bedTraceUntilMs) {
                emitBedTrace(npc, "HANDOFF", now, false);
            } else {
                emitBedTrace(npc, "HANDOFF_END", now, true);
                this.bedTraceUntilMs = 0L;
            }
        }
        if (!npc.isOnBed() && npc.isCurrentState(IdleState.instance())) {
            finishStandUpClean(npc, "EXTERNAL_EXIT");
        }
    }

    private void tickWakingBed(IsoNPC npc, long now) {
        if (now - stateEnteredMs >= STANDUP_WATCHDOG_MS) {
            forceImmediateReset(npc, "WAKING_TIMEOUT", false);
            executePendingContinuation(npc);
            return;
        }
        applyBedIdleSpeedScale(npc);
        if (now - stateEnteredMs >= WAKE_TO_STAND_MS) {
            prepareBedExit(npc);
            npc.faceDirection(lieDir);
            npc.setVariable("forceGetUp", true);
            state = PostureState.STANDING_UP;
            stateEnteredMs = now;
        }
    }

    private void startExitingBedToSit(IsoNPC npc, long now) {
        if (bedSeatTile == null || bedSeatTile.getObjectIndex() == -1) {
            npc.clearVariable("ER_SitSleepAnim");
            npc.setAsleep(false);
            npc.setBed(null);
            npc.blockTurning = false;
            this.sleepInSitState = false;
            this.standUpAfterSitBridge = false;
            npc.setVariable("forceGetUp", true);
            this.state = PostureState.STANDING_UP;
            this.stateEnteredMs = now;
            return;
        }
        boolean leftSide = EmberPostureMath.isLeftSideOnBed(onBedDirection);
        String animKey = leftSide ? "ToSitLeft" : "ToSitRight";
        npc.setAsleep(false);
        npc.setBed(null);
        npc.blockTurning = true;
        npc.setVariable("ER_BedSitFrac", 0.0F);
        npc.setVariable("ER_SitSleepAnim", animKey);
        this.enterBedFromSit = false;
        this.leavingBed = false;
        this.bedSitProgress01 = 0.0F;
        this.bedSlideStartX = npc.getX();
        this.bedSlideStartY = npc.getY();
        npc.faceDirection(bedSeatDir);
        this.state = PostureState.EXITING_BED_TO_SIT;
        this.stateEnteredMs = now;
        DebugLog.log(String.format(Locale.US,
                "[EmberRoutes][POSTURE_DIAG] BED_TO_SIT_START npc=%s anim=%s mode=IN_SIT seat=(%.2f,%.2f,%d) dir=%s standAfter=%s",
                npc.getNpcId(), animKey, bedSeatX, bedSeatY, approachZ, bedSeatDir.name(), standUpAfterSitBridge));
    }

    private void tickExitingBedToSit(IsoNPC npc, long now) {
        if (now - stateEnteredMs >= STANDUP_WATCHDOG_MS) {
            forceImmediateReset(npc, "EXIT_BED_TO_SIT_TIMEOUT", false);
            executePendingContinuation(npc);
            return;
        }
        if (!npc.isSittingOnFurniture() && npc.isCurrentState(IdleState.instance())) {
            finishStandUpClean(npc, "EXTERNAL_EXIT");
            return;
        }
        if (bedSeatTile == null || bedSeatTile.getObjectIndex() == -1) {
            npc.clearVariable("ER_SitSleepAnim");
            npc.setAsleep(false);
            npc.setBed(null);
            npc.blockTurning = false;
            this.sleepInSitState = false;
            this.standUpAfterSitBridge = false;
            npc.setVariable("forceGetUp", true);
            this.state = PostureState.STANDING_UP;
            this.stateEnteredMs = now;
            return;
        }
        bedSitProgress01 = EmberPostureMath.advanceAdapterProgress(
                bedSitProgress01, npc.getAnimationTimeDelta(), BED_TO_SIT_DURATION_SEC);
        npc.setVariable("ER_BedSitFrac", EmberPostureMath.BED_SIT_HANDOFF_FRAC * bedSitProgress01);

        float u = EmberPostureMath.smoothstep01(bedSitProgress01);
        float nx = bedSlideStartX + (bedSeatX - bedSlideStartX) * u;
        float ny = bedSlideStartY + (bedSeatY - bedSlideStartY) * u;
        smoothMovePosition(npc, nx, ny, approachZ);
        npc.faceDirection(bedSeatDir);
        if (bedSitProgress01 >= 1.0F) {
            completeBedExitToSit(npc, now);
        }
    }

    private void completeBedExitToSit(IsoNPC npc, long now) {
        // 冻结终端进度为 0.50F：让 0.20s 淡出中的 ER_BedToSit* 停在坐姿帧无缝混入 SitOnFurnitureIdle
        npc.setVariable("ER_BedSitFrac", EmberPostureMath.BED_SIT_HANDOFF_FRAC);
        npc.clearVariable("ER_SitSleepAnim");
        npc.setAsleep(false);
        npc.setBed(null);
        npc.blockTurning = false;
        npc.setCollidable(true);
        this.sleepInSitState = false;
        this.cachedBedRenderLiftZ = 0.0F;
        snapPosition(npc, bedSeatX, bedSeatY, approachZ);
        float desiredRad = AnimationPlayer.calculateAnimPlayerAngle(bedSeatDir.dx(), bedSeatDir.dy());
        if (Math.abs(PZMath.getClosestAngle(npc.getAnimAngleRadians(), desiredRad)) > 0.06F) {
            forceAnimFacing(npc, bedSeatDir);
        } else {
            npc.faceDirection(bedSeatDir);
        }

        IsoObject seatObj = this.bedSeatTile;
        this.targetObj = seatObj;
        this.bedSlotOtherTile = null;
        this.sitDir = this.bedSeatDir;
        this.seatSideStr = "Front";
        this.beforeDir = this.bedSeatDir;

        this.stateEnteredMs = now;
        this.state = standUpAfterSitBridge ? PostureState.SEAT_BRIDGE_WAIT : PostureState.SEATED;
        DebugLog.log(String.format(Locale.US,
                "[EmberRoutes][POSTURE_DIAG] BED_TO_SIT_DONE npc=%s next=%s seat=(%.2f,%.2f,%d) dir=%s mode=IN_SIT",
                npc.getNpcId(), this.state.name(), bedSeatX, bedSeatY, approachZ, bedSeatDir.name()));
    }

    private void tickStandingUp(IsoNPC npc, long now) {
        if (npc.isCurrentState(PlayerSitOnFurnitureState.instance())
                || npc.isCurrentState(PlayerOnBedState.instance())) {
            npc.setVariable("forceGetUp", true);
        }
        if (leavingBed && npc.isCurrentState(PlayerGetUpState.instance())) {
            applyBedExitSmoothGuide(npc, now);
        }
        boolean backToIdle = npc.isCurrentState(IdleState.instance())
                && !npc.isSittingOnFurniture()
                && !npc.isOnBed()
                && !npc.isSitOnGround()
                && "idle".equalsIgnoreCase(npc.getCurrentActionContextStateName());
        if (backToIdle) {
            finishStandUpClean(npc, "NORMAL");
            executePendingContinuation(npc);
            return;
        }
        if (now - stateEnteredMs >= STANDUP_WATCHDOG_MS) {
            DebugLog.log(String.format(Locale.US,
                    "[EmberRoutes][POSTURE_DIAG] STANDUP_WATCHDOG_RESET npc=%s curState=%s ctx=%s",
                    npc.getNpcId(),
                    npc.getCurrentStateName(),
                    String.valueOf(npc.getCurrentActionContextStateName())));
            forceImmediateReset(npc, "STANDUP_WATCHDOG", false);
            executePendingContinuation(npc);
        }
    }

    private void finishStandUpClean(IsoNPC npc, String reason) {
        clearAllPostureVars(npc);
        npc.setAsleep(false);
        npc.setBed(null);
        npc.setCollidable(true);
        npc.setIgnoreMovement(false);
        npc.setBlockMovement(false);
        npc.blockTurning = false;
        npc.setHideWeaponModel(false);
        npc.setIgnoreAimingInput(false);
        npc.getGaze().forceReset(npc);
        npc.setHeadLookAround(false);
        if (targetObj != null && targetObj.getObjectIndex() != -1) {
            targetObj.setSatChair(false);
        }
        ensureSafeStandingPosition(npc);
        clearActiveFields();
        state = PostureState.STANDING;
        DebugLog.log(String.format(Locale.US,
                "[EmberRoutes][POSTURE_DIAG] STANDUP_DONE npc=%s reason=%s pos=(%.2f,%.2f,%.1f)",
                npc.getNpcId(), reason, npc.getX(), npc.getY(), npc.getZ()));
    }

    public void forceImmediateReset(IsoNPC npc, String reason, boolean clearPending) {
        if (npc == null) {
            return;
        }
        TempPropHelper.cleanupAllTempProps(npc);
        if (clearPending) {
            clearPendingContinuation();
        }
        IsoObject heldObj = npc.getSitOnFurnitureObject();
        if (heldObj != null && heldObj.getObjectIndex() != -1) {
            heldObj.setSatChair(false);
        }
        if (targetObj != null && targetObj.getObjectIndex() != -1) {
            targetObj.setSatChair(false);
        }
        clearAllPostureVars(npc);
        npc.setAsleep(false);
        npc.setBed(null);
        npc.setOnBed(false);
        npc.setSittingOnFurniture(false);
        npc.setSitOnGround(false);
        npc.setSitOnFurnitureObject(null);
        npc.setSitOnFurnitureDirection(null);
        npc.setCollidable(true);
        npc.setIgnoreMovement(false);
        npc.setBlockMovement(false);
        npc.blockTurning = false;
        npc.setHideWeaponModel(false);
        npc.setIgnoreAimingInput(false);
        npc.getGaze().forceReset(npc);
        npc.setHeadLookAround(false);

        if (npc.isCurrentState(PlayerOnBedState.instance())
                || npc.isCurrentState(PlayerSitOnFurnitureState.instance())
                || npc.isCurrentState(PlayerGetUpState.instance())) {
            try {
                npc.changeState(IdleState.instance());
            } catch (Throwable ignored) {}
        }
        ensureSafeStandingPosition(npc);
        clearActiveFields();
        state = PostureState.STANDING;
        DebugLog.log(String.format(Locale.US,
                "[EmberRoutes][POSTURE_DIAG] FORCE_RESET npc=%s reason=%s pos=(%.2f,%.2f,%.1f)",
                npc.getNpcId(), reason, npc.getX(), npc.getY(), npc.getZ()));
    }

    private void executePendingContinuation(IsoNPC npc) {
        PendingKind kind = this.pendingKind;
        IsoObject nextObj = this.pendingTargetObj;
        clearPendingContinuation();
        switch (kind) {
            case MOVE_TO -> npc.getLocomotion().moveTo(npc, pendingMoveX, pendingMoveY, pendingMoveZ, pendingMoveRun);
            case FOLLOW -> npc.getFollow().start(pendingFollowPlayerIndex, pendingFollowSlot);
            case SIT -> {
                if (nextObj != null) {
                    commandSitOnFurniture(npc, nextObj);
                }
            }
            case SLEEP -> {
                if (nextObj != null) {
                    commandSleepOnBed(npc, nextObj);
                }
            }
            default -> {}
        }
    }

    private void ensureSafeStandingPosition(IsoNPC npc) {
        if (!hasSafeStandPos) {
            return;
        }
        int curZ = EmberTargetProjector.levelOf(npc.getZ());
        if (!EmberTargetProjector.canStandClean(npc, npc.getX(), npc.getY(), curZ)) {
            snapPosition(npc, safeStandX, safeStandY, safeStandZ);
        }
    }

    private void saveSafeStandPos(float x, float y, float z) {
        this.hasSafeStandPos = true;
        this.safeStandX = x;
        this.safeStandY = y;
        this.safeStandZ = z;
    }

    private void clearSitBridgeTargets() {
        this.bedHeadTargetForSitBridge = null;
        this.bedOtherTileForSitBridge = null;
        this.bedLieFacingForSitBridge = null;
    }

    private void clearBedFallbackTargets() {
        this.bedApproachFallbackBed = null;
        this.bedApproachExcludedTile = null;
        this.bedApproachFallbackUsed = false;
    }

    private void clearActiveFields() {
        this.targetObj = null;
        this.bedSlotOtherTile = null;
        this.pendingMoveSeq = 0L;
        this.hasSafeStandPos = false;
        this.eventFired = false;
        this.eventRetried = false;
        this.turnStartedMs = 0L;
        this.turnSettleStartMs = 0L;
        this.bedCenterX = 0.0F;
        this.bedCenterY = 0.0F;
        this.bedSlidePrevU = 0.0F;
        this.leavingBed = false;
        this.bedExitTargetX = 0.0F;
        this.bedExitTargetY = 0.0F;
        this.bedExitPrevU = 0.0F;
        this.bedMaxLiftZ = 0.0F;
        this.cachedBedRenderLiftZ = 0.0F;
        this.sleepInSitState = false;
        this.enterBedFromSit = false;
        this.bedTraceUntilMs = 0L;
        this.bedTraceLastLogMs = 0L;
        this.bedTraceLastPhase = "";
        this.bedSitProgress01 = 0.0F;
        clearSitBridgeTargets();
        clearBedFallbackTargets();
        this.standUpAfterSitBridge = false;
        this.urgentAfterSitBridge = false;
        this.bedSeatTile = null;
        this.bedSeatDir = IsoDirections.S;
        this.bedSeatX = 0.0F;
        this.bedSeatY = 0.0F;
        this.bedSlideStartX = 0.0F;
        this.bedSlideStartY = 0.0F;
        this.activeSeatCandidates = null;
    }

    private void updateBedRenderElevation(IsoNPC npc, long now) {
        if (!npc.isOnBed()) {
            this.cachedBedRenderLiftZ = 0.0F;
            return;
        }
        if (bedMaxLiftZ <= 0.0F) {
            this.cachedBedRenderLiftZ = 0.0F;
            return;
        }
        boolean fullyOnBed = state == PostureState.ON_BED_AWAKE
                || state == PostureState.SLEEPING
                || state == PostureState.WAKING_BED
                || (state == PostureState.ENTERING_BED && npc.getVariableBoolean("OnBedStarted"));
        boolean entering = state == PostureState.ENTERING_BED && eventFired && !fullyOnBed;
        boolean leaving = state == PostureState.STANDING_UP && leavingBed;
        float frac = entering
                ? readActiveTrackFraction(npc, "GetInBed", now, eventFiredMs, 1350.0F)
                : (leaving ? readActiveTrackFraction(npc, "GetOutBed", now, stateEnteredMs, 1333.0F) : 0.0F);
        this.cachedBedRenderLiftZ = bedMaxLiftZ
                * EmberPostureMath.computeBedElevationLerp(entering, fullyOnBed, leaving, frac);
    }

    private static void applyBedIdleSpeedScale(IsoNPC npc) {
        AdvancedAnimator anim = npc.getAdvancedAnimator();
        AnimLayer root = anim != null ? anim.getRootLayer() : null;
        if (root == null) {
            return;
        }
        List<LiveAnimNode> nodes = root.getLiveAnimNodes();
        if (nodes == null) {
            return;
        }
        for (int i = 0, n = nodes.size(); i < n; i++) {
            LiveAnimNode node = nodes.get(i);
            if (node == null || !node.isActive() || node.getSourceNode() == null) {
                continue;
            }
            String nodeName = node.getSourceNode().name;
            float targetSpeed;
            if ("OnBedAsleep".equalsIgnoreCase(nodeName)) {
                targetSpeed = BED_ASLEEP_SPEED_SCALE;
            } else if ("OnBedAwake".equalsIgnoreCase(nodeName)) {
                targetSpeed = BED_AWAKE_SPEED_SCALE;
            } else {
                continue;
            }
            for (int j = 0, tc = node.getMainAnimationTracksCount(); j < tc; j++) {
                AnimationTrack track = node.getMainAnimationTrackAt(j);
                if (track != null && Math.abs(track.getSpeedDelta() - targetSpeed) > 0.01F) {
                    track.setSpeedDelta(targetSpeed);
                }
            }
        }
    }

    private static float resolveBedMaxLiftZ(IsoObject headObj, ArrayList<IsoObject> bedTiles) {
        try {
            TileGeometryManager tgm = TileGeometryManager.getInstance();
            if (tgm != null) {
                ArrayList<String> modIds = tgm.getModIDs();
                float topFromBox = findMattressTopFromBox(tgm, modIds, headObj);
                if (topFromBox <= 0.0F && bedTiles != null) {
                    for (int i = 0; i < bedTiles.size() && topFromBox <= 0.0F; i++) {
                        topFromBox = Math.max(topFromBox, findMattressTopFromBox(tgm, modIds, bedTiles.get(i)));
                    }
                }
                if (topFromBox > 0.0F) {
                    return EmberPostureMath.computeBedRenderLiftZ(topFromBox);
                }
            }
        } catch (Throwable ignored) {}

        try {
            SeatingManager sm = SeatingManager.getInstance();
            if (sm != null) {
                Vector3f tmp = new Vector3f();
                float seatH = findSeatingHeightOnObject(sm, headObj, tmp);
                if (seatH <= 0.05F && bedTiles != null) {
                    for (int i = 0; i < bedTiles.size(); i++) {
                        seatH = Math.max(seatH, findSeatingHeightOnObject(sm, bedTiles.get(i), tmp));
                    }
                }
                if (seatH > 0.05F) {
                    return EmberPostureMath.computeBedRenderLiftZ(
                            seatH + EmberPostureMath.SEATING_BASE_OFFSET_WORLD_Y);
                }
            }
        } catch (Throwable ignored) {}

        int surface = getSurfacePixels(headObj);
        if (surface < 12 && bedTiles != null) {
            for (int i = 0; i < bedTiles.size(); i++) {
                surface = Math.max(surface, getSurfacePixels(bedTiles.get(i)));
            }
        }
        if (surface >= 12) {
            return EmberPostureMath.computeBedRenderLiftZ(
                    (surface / 96.0F) * EmberPostureMath.WORLD_Z_UNITS_PER_STORY);
        }
        return 0.0F;
    }

    private static float findMattressTopFromBox(
            TileGeometryManager tgm, ArrayList<String> modIds, IsoObject obj) {
        if (obj == null || obj.getSprite() == null || obj.getSprite().tilesetName == null || modIds == null) {
            return 0.0F;
        }
        IsoSprite sprite = obj.getSprite();
        int col = sprite.tileSheetIndex % 8;
        int row = sprite.tileSheetIndex / 8;
        float best = 0.0F;
        for (int mi = modIds.size() - 1; mi >= 0; mi--) {
            String modId = modIds.get(mi);
            ArrayList<TileGeometryFile.Geometry> geoms = tgm.getGeometry(modId, sprite.tilesetName, col, row);
            if (geoms == null || geoms.isEmpty()) {
                continue;
            }
            for (int gi = 0; gi < geoms.size(); gi++) {
                TileGeometryFile.Geometry geom = geoms.get(gi);
                if (geom != null && geom.isBox()) {
                    TileGeometryFile.Box box = geom.asBox();
                    if (box != null) {
                        float topY = box.translate.y + box.max.y;
                        if (topY >= 0.20F && topY <= 1.05F && topY > best) {
                            best = topY;
                        }
                    }
                }
            }
            if (best > 0.0F) {
                return best;
            }
        }
        return best;
    }

    private static float findSeatingHeightOnObject(SeatingManager sm, IsoObject obj, Vector3f tmp) {
        if (obj == null || obj.getSprite() == null || obj.getSprite().tilesetName == null) {
            return 0.0F;
        }
        float best = 0.0F;
        for (String dir : CARDINALS) {
            sm.getTranslation(obj.getSprite(), dir, tmp);
            if (tmp.z > best) {
                best = tmp.z;
            }
        }
        return best;
    }

    private static int getSurfacePixels(IsoObject obj) {
        return (obj != null && obj.getProperties() != null) ? obj.getProperties().getSurface() : 0;
    }

    private void prepareBedExit(IsoNPC npc) {
        this.leavingBed = true;
        this.bedExitPrevU = 0.0F;
        int bz = EmberTargetProjector.levelOf(approachZ);
        float headSideX = bedCenterX - 0.80F * beforeDir.dx();
        float headSideY = bedCenterY - 0.80F * beforeDir.dy();
        IsoCell cell = currentCell();
        boolean headSideSafe = cell != null
                && EmberTargetProjector.canStandClean(npc, headSideX, headSideY, bz)
                && !isLosBlocked(cell, PZMath.fastfloor(headSideX), PZMath.fastfloor(headSideY), bz,
                        PZMath.fastfloor(bedCenterX), PZMath.fastfloor(bedCenterY));
        if (headSideSafe) {
            this.bedExitTargetX = headSideX;
            this.bedExitTargetY = headSideY;
            saveSafeStandPos(headSideX, headSideY, bz);
        } else if (hasSafeStandPos) {
            this.bedExitTargetX = safeStandX;
            this.bedExitTargetY = safeStandY;
        } else {
            this.bedExitTargetX = approachX;
            this.bedExitTargetY = approachY;
        }
    }

    // 温和导正原版 Bob_GetInBed_Left/Right（实测进床 0.43 格且偏床头 0.15 格）到当前等效头部睡格中心。
    private void applyBedEntrySmoothCentering(IsoNPC npc, long now) {
        float frac = readActiveTrackFraction(npc, "GetInBed", now, eventFiredMs, 1350.0F);
        float nextU = EmberPostureMath.smoothstep01((frac - 0.15F) / 0.85F);
        this.bedSlidePrevU = applySmoothSlideStep(npc, bedCenterX, bedCenterY, approachZ, this.bedSlidePrevU, nextU);
    }

    // TODO(BedAnim): 临时下床导正 — 与上床补位配套，确保原版 Bob_GetOutBed* 离开床垫后平稳落到安全站立点。
    private void applyBedExitSmoothGuide(IsoNPC npc, long now) {
        float frac = readActiveTrackFraction(npc, "GetOutBed", now, stateEnteredMs, 1333.0F);
        float nextU = EmberPostureMath.smoothstep01((frac - 0.25F) / 0.75F);
        int z = hasSafeStandPos ? EmberTargetProjector.levelOf(safeStandZ) : approachZ;
        this.bedExitPrevU = applySmoothSlideStep(npc, bedExitTargetX, bedExitTargetY, z, this.bedExitPrevU, nextU);
    }

    private static float applySmoothSlideStep(
            IsoNPC npc, float targetX, float targetY, int targetZ, float prevU, float nextU) {
        if (nextU <= prevU && nextU < 0.9999F) {
            return prevU;
        }
        float nx = EmberPostureMath.computeSmoothBlendStep(npc.getX(), targetX, prevU, nextU);
        float ny = EmberPostureMath.computeSmoothBlendStep(npc.getY(), targetY, prevU, nextU);
        if (Math.abs(nx - npc.getX()) > 1e-4F || Math.abs(ny - npc.getY()) > 1e-4F) {
            smoothMovePosition(npc, nx, ny, targetZ);
        }
        return nextU;
    }

    private static float readActiveTrackFraction(
            IsoNPC npc, String nameToken, long now, long startMs, float fallbackDurationMs) {
        AnimationTrack track = findActiveTrackByToken(npc, nameToken);
        if (track != null) {
            return Math.max(0.0F, Math.min(1.0F, track.getCurrentTimeFraction()));
        }
        float elapsedMs = Math.max(0.0F, (now - startMs) - 150.0F);
        return Math.max(0.0F, Math.min(1.0F, elapsedMs / Math.max(1.0F, fallbackDurationMs)));
    }

    private static AnimationTrack findActiveTrackByToken(IsoNPC npc, String nameToken) {
        AnimationPlayer anim = npc != null ? npc.getAnimationPlayer() : null;
        if (anim == null || anim.getMultiTrack() == null) {
            return null;
        }
        List<AnimationTrack> tracks = anim.getMultiTrack().getTracks();
        if (tracks == null) {
            return null;
        }
        for (int i = tracks.size() - 1; i >= 0; i--) {
            AnimationTrack track = tracks.get(i);
            if (track != null && track.hasClip()) {
                String tname = track.getName();
                if (tname != null && tname.contains(nameToken)) {
                    return track;
                }
            }
        }
        return null;
    }

    private boolean convergePreEntryPose(
            IsoNPC npc, long now, float targetX, float targetY, int targetZ, IsoDirections desiredDir) {
        boolean turnReady = awaitPreEntryTurn(npc, now, desiredDir);
        float dx = targetX - npc.getX();
        float dy = targetY - npc.getY();
        float remDist = (float) Math.hypot(dx, dy);
        if (remDist > 0.001F) {
            if (remDist <= PRE_ENTRY_POS_TOL) {
                smoothMovePosition(npc, targetX, targetY, targetZ);
                remDist = 0.0F;
            } else {
                float dt = Math.max(0.001F, Math.min(0.05F, npc.getAnimationTimeDelta()));
                float step = Math.min(remDist, PRE_ENTRY_GLIDE_SPEED * dt);
                float nx = npc.getX() + (dx / remDist) * step;
                float ny = npc.getY() + (dy / remDist) * step;
                smoothMovePosition(npc, nx, ny, targetZ);
                remDist = (float) Math.hypot(targetX - nx, targetY - ny);
            }
        }
        return now - turnStartedMs >= TURN_WAIT_MAX_MS || (turnReady && remDist <= PRE_ENTRY_POS_TOL);
    }

    private void emitBedTrace(IsoNPC npc, String phase, long now, boolean force) {
        long interval = "HANDOFF".equals(phase) ? BED_TRACE_HANDOFF_INTERVAL_MS : BED_TRACE_CLIMB_INTERVAL_MS;
        if (!force && phase.equals(bedTraceLastPhase) && (now - bedTraceLastLogMs) < interval) {
            return;
        }
        bedTraceLastLogMs = now;
        bedTraceLastPhase = phase;

        float errApp = (float) Math.hypot(approachX - npc.getX(), approachY - npc.getY());
        float errBed = (float) Math.hypot(bedCenterX - npc.getX(), bedCenterY - npc.getY());
        float bx = Float.NaN;
        float by = Float.NaN;
        float bz = Float.NaN;
        try {
            AnimationPlayer ap = npc.getAnimationPlayer();
            int bipIdx = SkeletonBone.Bip01.index();
            if (ap != null && ap.isReady() && bipIdx >= 0 && bipIdx < ap.getModelTransformsCount()
                    && ap.getModelTransformAt(bipIdx) != null) {
                scratchWorldVec.set(0.0F, 0.0F, 0.0F);
                Model.boneToWorldCoords(npc, bipIdx, scratchWorldVec);
                bx = scratchWorldVec.x;
                by = scratchWorldVec.y;
                bz = scratchWorldVec.z;
            }
        } catch (Throwable ignored) {}

        DebugLog.log(String.format(Locale.US,
                "[EmberRoutes][POSTURE_DIAG] BED_TRACE npc=%s phase=%s state=%s pos=(%.3f,%.3f,%.2f) errApp=%.3f errBed=%.3f animDeg=%.1f liftZ=%.3f bipW=(%.3f,%.3f,%.3f) tracks=[%s]",
                npc.getNpcId(),
                phase,
                state.name(),
                npc.getX(),
                npc.getY(),
                npc.getZ(),
                errApp,
                errBed,
                Math.toDegrees(npc.getAnimAngleRadians()),
                cachedBedRenderLiftZ,
                bx,
                by,
                bz,
                formatActiveTracks(npc)));
    }

    private static String formatActiveTracks(IsoNPC npc) {
        AnimationPlayer anim = npc != null ? npc.getAnimationPlayer() : null;
        if (anim == null || anim.getMultiTrack() == null) {
            return "";
        }
        List<AnimationTrack> tracks = anim.getMultiTrack().getTracks();
        if (tracks == null || tracks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0, n = tracks.size(); i < n; i++) {
            AnimationTrack track = tracks.get(i);
            if (track == null || !track.hasClip()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(track.getName())
                    .append('@')
                    .append(String.format(Locale.US, "%.2f/%.2f",
                            track.getCurrentTimeFraction(), track.getBlendWeight()));
        }
        return sb.toString();
    }

    private boolean awaitPreEntryTurn(IsoNPC npc, long now, IsoDirections desiredDir) {
        npc.faceDirection(desiredDir);
        if (turnStartedMs == 0L) {
            turnStartedMs = now;
            turnSettleStartMs = 0L;
            return false;
        }
        if (now - turnStartedMs >= TURN_WAIT_MAX_MS) {
            return true;
        }
        float desiredRad = AnimationPlayer.calculateAnimPlayerAngle(desiredDir.dx(), desiredDir.dy());
        float angleErr = Math.abs(PZMath.getClosestAngle(npc.getAnimAngleRadians(), desiredRad));
        if (npc.shouldBeTurning() || angleErr > PRE_ENTRY_ANGLE_TOL_RAD) {
            turnSettleStartMs = 0L;
            return false;
        }
        if (turnSettleStartMs == 0L) {
            turnSettleStartMs = now;
            return false;
        }
        return now - turnSettleStartMs >= PRE_ENTRY_SETTLE_MS;
    }

    private static void ensureAlignedBeforeEntry(IsoNPC npc, IsoDirections dir) {
        float desiredRad = AnimationPlayer.calculateAnimPlayerAngle(dir.dx(), dir.dy());
        float angleErr = Math.abs(PZMath.getClosestAngle(npc.getAnimAngleRadians(), desiredRad));
        if (angleErr > PRE_ENTRY_ANGLE_TOL_RAD) {
            forceAnimFacing(npc, dir);
        } else {
            npc.faceDirection(dir);
        }
    }

    private static void forceAnimFacing(IsoNPC npc, IsoDirections dir) {
        npc.faceDirection(dir);
        AnimationPlayer anim = npc.getAnimationPlayer();
        if (anim != null) {
            anim.setTargetAndCurrentDirection(dir.ToVector());
        }
    }

    private static void smoothMovePosition(IsoNPC npc, float x, float y, float z) {
        npc.setLastX(npc.getX());
        npc.setLastY(npc.getY());
        npc.setLastZ(npc.getZ());
        npc.setX(x);
        npc.setY(y);
        npc.setZ(z);
        npc.setNextX(x);
        npc.setNextY(y);
        refreshCurrentSquare(npc, x, y, z);
    }

    private static void snapPosition(IsoNPC npc, float x, float y, float z) {
        npc.setX(x);
        npc.setY(y);
        npc.setZ(z);
        npc.setNextX(x);
        npc.setNextY(y);
        npc.setLastX(x);
        npc.setLastY(y);
        npc.setLastZ(z);
        refreshCurrentSquare(npc, x, y, z);
    }

    private static void refreshCurrentSquare(IsoNPC npc, float x, float y, float z) {
        IsoCell cell = currentCell();
        if (cell != null) {
            IsoGridSquare sq = cell.getGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y), EmberTargetProjector.levelOf(z));
            if (sq != null) {
                npc.setCurrent(sq);
                npc.square = sq;
                npc.setMovingSquareNow();
            }
        }
    }

    private static boolean isIdleReady(IsoNPC npc) {
        return npc.isCurrentState(IdleState.instance())
                && "idle".equalsIgnoreCase(npc.getCurrentActionContextStateName());
    }

    private static boolean hasAnimReady(IsoNPC npc) {
        return npc.getAnimationPlayer() != null
                && npc.getAnimationPlayer().getModel() != null
                && npc.getAdvancedAnimator() != null
                && npc.getAdvancedAnimator().animSet != null;
    }

    private void enterApproachingState(PostureState nextState) {
        this.eventFired = false;
        this.eventRetried = false;
        this.turnStartedMs = 0L;
        this.turnSettleStartMs = 0L;
        this.state = nextState;
        this.stateEnteredMs = System.currentTimeMillis();
    }

    private void applySeatCandidate(SeatCandidate cand) {
        this.targetObj = cand.tile;
        this.sitDir = cand.sitDir;
        this.seatSideStr = cand.sideStr;
        this.beforeDir = cand.beforeDir;
        this.approachX = cand.exactX;
        this.approachY = cand.exactY;
        this.approachZ = cand.z;
        saveSafeStandPos(cand.pathX, cand.pathY, cand.z);
    }

    private static long issuePrecisionSeatMove(
            IsoNPC npc, List<SeatCandidate> candidates, IsoGridSquare fallbackIgnoreSquare) {
        List<EmberLocomotionController.PrecisionTarget> targets = new ArrayList<>(candidates.size());
        for (int i = 0, n = candidates.size(); i < n; i++) {
            SeatCandidate c = candidates.get(i);
            IsoGridSquare sq = c.tile != null ? c.tile.getSquare() : fallbackIgnoreSquare;
            targets.add(new EmberLocomotionController.PrecisionTarget(
                    c.pathX, c.pathY, c.exactX, c.exactY, c.z, i, sq));
        }
        return npc.getLocomotion().moveToExact(npc, targets, fallbackIgnoreSquare);
    }

    /**
     * 1:1 对齐原版 {@code PathFindBehavior2.pathToSitOnFurnitureNoSpriteGrid}：
     * 保留 {@code exactPos} 作为最终入座精确锚点，同时为实心椅计算外推 0.30F 的 {@code outPathPos} 供可见性图寻路。
     */
    private static boolean computeSeatApproachPair(
            IsoNPC npc, IsoCell cell, IsoGridSquare sq,
            String dirStr, String sideStr,
            Vector3f exactPos, Vector3f outPathPos) {
        int sx = sq.getX();
        int sy = sq.getY();
        int sz = sq.getZ();
        outPathPos.set(exactPos);
        boolean insideSq = exactPos.x >= sx && exactPos.x < sx + 1 && exactPos.y >= sy && exactPos.y < sy + 1;

        if ((sq.isSolid() || sq.isSolidTrans()) && insideSq) {
            pushSeatOutsideSolidSquare(sq, dirStr, sideStr, outPathPos);
            if (isLosBlocked(cell, PZMath.fastfloor(outPathPos.x), PZMath.fastfloor(outPathPos.y), sz,
                    PZMath.fastfloor(exactPos.x), PZMath.fastfloor(exactPos.y))) {
                return false;
            }
            IsoGridSquare appSq = cell.getGridSquare(PZMath.fastfloor(outPathPos.x), PZMath.fastfloor(outPathPos.y), sz);
            if (appSq == null || !appSq.TreatAsSolidFloor() || appSq.isSolid() || appSq.isSolidTrans()) {
                return false;
            }
            return npc.canStandAt(outPathPos.x, outPathPos.y, sz);
        }

        if (!insideSq) {
            if (isLosBlocked(cell, PZMath.fastfloor(outPathPos.x), PZMath.fastfloor(outPathPos.y), sz, sx, sy)) {
                return false;
            }
        }

        IsoGridSquare appSq = cell.getGridSquare(PZMath.fastfloor(outPathPos.x), PZMath.fastfloor(outPathPos.y), sz);
        if (appSq == null || !appSq.TreatAsSolidFloor() || appSq.isSolid() || appSq.isSolidTrans()) {
            return false;
        }
        return sq.isSolid() || sq.isSolidTrans() || npc.canStandAt(outPathPos.x, outPathPos.y, sz);
    }

    private static void pushSeatOutsideSolidSquare(IsoGridSquare sq, String dirStr, String sideStr, Vector3f pos) {
        float x = sq.getX();
        float y = sq.getY();
        switch (dirStr) {
            case "N" -> {
                if ("Front".equals(sideStr)) pos.y = y - 0.3F;
                else if ("Left".equals(sideStr)) pos.x = x - 0.3F;
                else if ("Right".equals(sideStr)) pos.x = x + 1.3F;
            }
            case "S" -> {
                if ("Front".equals(sideStr)) pos.y = y + 1.3F;
                else if ("Left".equals(sideStr)) pos.x = x + 1.3F;
                else if ("Right".equals(sideStr)) pos.x = x - 0.3F;
            }
            case "W" -> {
                if ("Front".equals(sideStr)) pos.x = x - 0.3F;
                else if ("Left".equals(sideStr)) pos.y = y + 1.3F;
                else if ("Right".equals(sideStr)) pos.y = y - 0.3F;
            }
            case "E" -> {
                if ("Front".equals(sideStr)) pos.x = x + 1.3F;
                else if ("Left".equals(sideStr)) pos.y = y - 0.3F;
                else if ("Right".equals(sideStr)) pos.y = y + 1.3F;
            }
            default -> {}
        }
    }

    private static boolean isLosBlocked(IsoCell cell, int x0, int y0, int z, int x1, int y1) {
        LosUtil.TestResults res = LosUtil.lineClear(cell, x0, y0, z, x1, y1, z, false);
        return res == LosUtil.TestResults.Blocked
                || res == LosUtil.TestResults.ClearThroughClosedDoor
                || res == LosUtil.TestResults.ClearThroughWindow;
    }

    private static boolean isBedSlotOccupied(
            IsoNPC self, IsoObject headObj, IsoObject footObj,
            int originX, int originY, int bedZ, BedSlot slot) {
        if (NpcManager.isBedHeadClaimedByOther(self, headObj)) {
            return true;
        }
        if (footObj != null && NpcManager.isBedHeadClaimedByOther(self, footObj)) {
            return true;
        }
        if (IsoPlayer.players == null) {
            return false;
        }
        int hx = originX + slot.headGridX();
        int hy = originY + slot.headGridY();
        int fx = originX + slot.footGridX();
        int fy = originY + slot.footGridY();
        for (IsoPlayer p : IsoPlayer.players) {
            if (p == null || p == self || p.isDead()) {
                continue;
            }
            if (p.isOnBed() || p.isAsleep()) {
                IsoObject pBed = p.getBed() != null ? p.getBed() : p.getSitOnFurnitureObject();
                if (pBed == headObj || (footObj != null && pBed == footObj)) {
                    return true;
                }
                if (EmberTargetProjector.levelOf(p.getZ()) == bedZ) {
                    int px = PZMath.fastfloor(p.getX());
                    int py = PZMath.fastfloor(p.getY());
                    if ((px == hx && py == hy) || (px == fx && py == fy)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static IsoObject[][] buildBedGridMatrix(
            ArrayList<IsoObject> bedTiles, IsoSpriteGrid grid,
            int gridW, int gridH, int originX, int originY, IsoObject fallbackObj) {
        IsoObject[][] matrix = new IsoObject[gridW][gridH];
        if (grid == null) {
            matrix[0][0] = fallbackObj;
            return matrix;
        }
        for (int i = 0; i < bedTiles.size(); i++) {
            IsoObject obj = bedTiles.get(i);
            if (obj == null || obj.getSprite() == null || obj.getSquare() == null) {
                continue;
            }
            int gx = grid.getSpriteGridPosX(obj.getSprite());
            int gy = grid.getSpriteGridPosY(obj.getSprite());
            if (gx < 0 || gx >= gridW || gy < 0 || gy >= gridH) {
                gx = obj.getSquare().getX() - originX;
                gy = obj.getSquare().getY() - originY;
            }
            if (gx >= 0 && gx < gridW && gy >= 0 && gy < gridH) {
                matrix[gx][gy] = obj;
            }
        }
        return matrix;
    }

    private static BedFacing resolveBedFacing(
            IsoObject clickedBed, ArrayList<IsoObject> bedTiles, int gridW, int gridH) {
        BedFacing f = BedFacing.normalize(clickedBed.getProperties() != null ? clickedBed.getProperties().get("Facing") : null);
        if (f != null) {
            return f;
        }
        for (int i = 0; i < bedTiles.size(); i++) {
            IsoObject obj = bedTiles.get(i);
            if (obj != null && obj.getProperties() != null) {
                f = BedFacing.normalize(obj.getProperties().get("Facing"));
                if (f != null) {
                    return f;
                }
            }
        }
        return BedFacing.inferFromGrid(gridW, gridH);
    }

    private void beginDirectSitToBed(
            IsoNPC npc,
            IsoObject seatTile,
            IsoDirections currentSitDir,
            IsoObject effectiveHeadObj,
            IsoObject otherSlotObj,
            ArrayList<IsoObject> bedTiles,
            BedFacing lieFacing,
            String sideOnBedDir,
            int bedZ) {
        long now = System.currentTimeMillis();
        if (seatTile.getObjectIndex() != -1) {
            seatTile.setSatChair(true);
        }

        this.sleepInSitState = true;
        this.bedSeatTile = seatTile;
        this.bedSeatDir = currentSitDir;
        this.bedSlideStartX = npc.getX();
        this.bedSlideStartY = npc.getY();
        this.bedSeatX = this.bedSlideStartX;
        this.bedSeatY = this.bedSlideStartY;

        this.targetObj = effectiveHeadObj;
        this.bedSlotOtherTile = (otherSlotObj != null && otherSlotObj != effectiveHeadObj) ? otherSlotObj : null;
        this.onBedDirection = sideOnBedDir;
        this.lieDir = toIsoDir(lieFacing);
        this.beforeDir = toIsoDir(toBedFacing(currentSitDir).opposite());
        this.bedType = resolveBedType(effectiveHeadObj);
        this.bedCenterX = effectiveHeadObj.getSquare().getX() + 0.5F;
        this.bedCenterY = effectiveHeadObj.getSquare().getY() + 0.5F;
        this.approachZ = bedZ;
        this.bedMaxLiftZ = resolveBedMaxLiftZ(effectiveHeadObj, bedTiles);
        this.cachedBedRenderLiftZ = 0.0F;

        this.enterBedFromSit = true;
        this.bedSitProgress01 = 0.0F;
        this.eventFired = true;
        this.eventFiredMs = now;
        this.leavingBed = false;
        this.standUpAfterSitBridge = false;
        this.urgentAfterSitBridge = false;

        boolean leftSide = EmberPostureMath.isLeftSideOnBed(onBedDirection);
        String animKey = leftSide ? "FromSitLeft" : "FromSitRight";

        clearBedVars(npc);
        npc.clearVariable("OnBedDirection");
        npc.setVariable("ER_BedSitFrac", EmberPostureMath.BED_SIT_HANDOFF_FRAC);
        npc.setOnBed(false);

        npc.setSitOnFurnitureObject(seatTile);
        npc.setSitOnFurnitureDirection(currentSitDir);
        npc.setSittingOnFurniture(true);
        npc.blockTurning = true;
        npc.setCollidable(false);
        npc.setHideWeaponModel(true);
        npc.setBed(effectiveHeadObj);
        npc.setBedType(bedType);
        npc.setVariable("SitOnFurnitureStarted", true);
        npc.setVariable("SitOnFurnitureAnim", "Idle");
        npc.setVariable("ER_SitSleepAnim", animKey);
        npc.setVariable("ExerciseStarted", false);
        npc.setVariable("ExerciseEnded", true);
        npc.faceDirection(lieDir);

        this.state = PostureState.ENTERING_BED;
        this.stateEnteredMs = now;

        DebugLog.log(String.format(Locale.US,
                "[EmberRoutes][POSTURE_DIAG] BED_FROM_SIT npc=%s anim=%s seatSprite=%s sleepHead=%s onBedDir=%s lieDir=%s start=(%.2f,%.2f) center=(%.2f,%.2f) mode=IN_SIT",
                npc.getNpcId(), animKey, safeSpriteName(seatTile), safeSpriteName(effectiveHeadObj), onBedDirection, lieDir.name(),
                bedSlideStartX, bedSlideStartY, bedCenterX, bedCenterY));
    }

    private BedViaSitCandidate findBestBedViaSitCandidate(
            IsoNPC npc, IsoCell cell, BedLayout layout, IsoObject excludeTile) {
        SeatingManager sm = SeatingManager.getInstance();
        if (sm == null) {
            return null;
        }
        BedFacing[] sideOutDirs = {layout.facing.rotLeft90(), layout.facing.rotRight90()};
        Vector3f exactPos = new Vector3f();
        Vector3f pathPos = new Vector3f();
        BedViaSitCandidate best = null;

        for (int si = 0; si < layout.slots.size(); si++) {
            BedSlot slot = layout.slots.get(si);
            IsoObject headObj = layout.gridObjects[slot.headGridX()][slot.headGridY()];
            IsoObject footObj = layout.gridObjects[slot.footGridX()][slot.footGridY()];
            if (headObj == null || headObj.getObjectIndex() == -1) {
                continue;
            }
            if (isBedSlotOccupied(npc, headObj, footObj, layout.originX, layout.originY, layout.bedZ, slot)) {
                continue;
            }

            IsoObject[] slotTiles = (footObj != null && footObj != headObj && footObj.getObjectIndex() != -1)
                    ? new IsoObject[]{headObj, footObj}
                    : new IsoObject[]{headObj};

            for (BedFacing outDir : sideOutDirs) {
                if (!slotHasApproachBeforeDir(slot, outDir.opposite())) {
                    continue;
                }
                for (int ti = 0; ti < slotTiles.length; ti++) {
                    IsoObject seatTile = slotTiles[ti];
                    if (excludeTile != null && seatTile == excludeTile) {
                        continue;
                    }
                    boolean isHeadTile = (ti == 0);
                    if (!isSittableFurniture(seatTile) || seatTile.getSquare() == null) {
                        continue;
                    }
                    if (seatTile.isFurnitureOccupied(npc) || NpcManager.isFurnitureTileClaimedByOther(npc, seatTile)) {
                        continue;
                    }
                    IsoGridSquare sq = seatTile.getSquare();
                    IsoObject otherSlotObj = isHeadTile
                            ? ((footObj != null && footObj != headObj && footObj.getObjectIndex() != -1) ? footObj : null)
                            : headObj;
                    BedFacing lieFacing = isHeadTile ? layout.facing : layout.facing.opposite();
                    List<SeatCandidate> group = new ArrayList<>(SEAT_SIDES.length);
                    for (String sideStr : SEAT_SIDES) {
                        if (!sm.getAdjacentPosition(npc, seatTile, outDir.name(), sideStr,
                                "sitonfurniture", "SitOnFurniture" + sideStr, exactPos)) {
                            continue;
                        }
                        if (!computeSeatApproachPair(npc, cell, sq, outDir.name(), sideStr, exactPos, pathPos)) {
                            continue;
                        }
                        float dist = dist3D(npc, pathPos.x, pathPos.y, sq.getZ());
                        float score = dist
                                + (isHeadTile ? HEAD_TILE_PRIORITY_BONUS : 0.0F)
                                + (slot.slotIndex() == layout.clickedSlot ? -1.5F : 0.0F)
                                + ("Front".equals(sideStr) ? -0.3F : 0.0F);
                        IsoDirections iSitDir = toIsoDir(outDir);
                        IsoDirections iBeforeDir = toIsoDir(EmberPostureMath.resolveSeatBeforeDir(outDir, sideStr));
                        group.add(new SeatCandidate(seatTile, iSitDir, sideStr, iBeforeDir,
                                pathPos.x, pathPos.y, exactPos.x, exactPos.y, sq.getZ(), score));
                    }
                    if (!group.isEmpty()) {
                        group.sort(Comparator.comparingDouble(c -> c.score));
                        SeatCandidate groupBest = group.get(0);
                        if (best == null || groupBest.score < best.primary.score) {
                            best = new BedViaSitCandidate(
                                    seatTile, otherSlotObj, isHeadTile, lieFacing, groupBest, group);
                        }
                    }
                }
            }
        }
        return best;
    }

    private BedCandidate findBestDirectBedCandidate(
            IsoNPC npc, IsoCell cell, BedLayout layout, IsoObject excludeTile) {
        BedCandidate best = null;
        for (int si = 0; si < layout.slots.size(); si++) {
            BedSlot slot = layout.slots.get(si);
            IsoObject headObj = layout.gridObjects[slot.headGridX()][slot.headGridY()];
            IsoObject footObj = layout.gridObjects[slot.footGridX()][slot.footGridY()];
            if (headObj == null || headObj.getObjectIndex() == -1) {
                continue;
            }
            if (isBedSlotOccupied(npc, headObj, footObj, layout.originX, layout.originY, layout.bedZ, slot)) {
                continue;
            }
            boolean hasSeparateFoot = footObj != null && footObj != headObj && footObj.getObjectIndex() != -1;
            for (BedApproach cand : slot.approaches()) {
                IsoObject effectiveHeadObj = cand.headEntry() ? headObj : (hasSeparateFoot ? footObj : headObj);
                if (excludeTile != null && effectiveHeadObj == excludeTile) {
                    continue;
                }
                IsoObject otherSlotObj = hasSeparateFoot
                        ? (effectiveHeadObj == headObj ? footObj : headObj)
                        : null;
                IsoGridSquare adjSq = cell.getGridSquare(cand.adjTileX(), cand.adjTileY(), layout.bedZ);
                if (adjSq == null || !adjSq.TreatAsSolidFloor() || adjSq.isSolid() || adjSq.isSolidTrans()) {
                    continue;
                }
                if (!EmberTargetProjector.canStandClean(npc, cand.approachX(), cand.approachY(), layout.bedZ)) {
                    continue;
                }
                if (isLosBlocked(cell, cand.adjTileX(), cand.adjTileY(), layout.bedZ,
                        cand.adjacentTargetTileX(), cand.adjacentTargetTileY())) {
                    continue;
                }
                float dist = dist3D(npc, cand.approachX(), cand.approachY(), layout.bedZ);
                float score = dist
                        + (cand.headEntry() ? HEAD_TILE_PRIORITY_BONUS : 0.0F)
                        + (slot.slotIndex() == layout.clickedSlot ? -1.5F : 0.0F);
                if (best == null || score < best.score) {
                    best = new BedCandidate(effectiveHeadObj, otherSlotObj, slot.slotIndex(), cand, score);
                }
            }
        }
        return best;
    }

    private static BedLayout resolveBedLayout(IsoObject bedTile, ArrayList<IsoObject> bedTiles) {
        IsoSpriteGrid grid = bedTile.getSprite() != null ? bedTile.getSprite().getSpriteGrid() : null;
        int gridW = grid != null ? Math.max(1, grid.getWidth()) : 1;
        int gridH = grid != null ? Math.max(1, grid.getHeight()) : 1;
        int clickedGx = grid != null ? grid.getSpriteGridPosX(bedTile.getSprite()) : 0;
        int clickedGy = grid != null ? grid.getSpriteGridPosY(bedTile.getSprite()) : 0;
        if (clickedGx < 0 || clickedGx >= gridW || clickedGy < 0 || clickedGy >= gridH) {
            clickedGx = 0;
            clickedGy = 0;
        }
        int originX = bedTile.getSquare().getX() - clickedGx;
        int originY = bedTile.getSquare().getY() - clickedGy;
        int bedZ = bedTile.getSquare().getZ();
        IsoObject[][] gridObjects = buildBedGridMatrix(bedTiles, grid, gridW, gridH, originX, originY, bedTile);
        BedFacing facing = resolveBedFacing(bedTile, bedTiles, gridW, gridH);
        List<BedSlot> slots = EmberPostureMath.enumerateBedSlots(facing, gridW, gridH, originX, originY);
        int clickedSlot = EmberPostureMath.resolveClickedSlotIndex(facing, gridW, gridH, clickedGx, clickedGy);
        return new BedLayout(gridW, gridH, originX, originY, bedZ, gridObjects, facing, slots, clickedSlot);
    }

    private static BedSlot findSlotContainingTile(BedLayout layout, IsoObject tile) {
        if (tile == null) {
            return null;
        }
        for (int i = 0; i < layout.slots.size(); i++) {
            BedSlot slot = layout.slots.get(i);
            if (isTileInSlot(layout, slot, tile)) {
                return slot;
            }
        }
        return null;
    }

    private static boolean isTileInSlot(BedLayout layout, BedSlot slot, IsoObject tile) {
        if (slot == null || tile == null) {
            return false;
        }
        IsoObject headObj = layout.gridObjects[slot.headGridX()][slot.headGridY()];
        IsoObject footObj = layout.gridObjects[slot.footGridX()][slot.footGridY()];
        return tile == headObj || tile == footObj;
    }

    private static boolean slotHasApproachBeforeDir(BedSlot slot, BedFacing beforeDir) {
        if (slot == null || beforeDir == null) {
            return false;
        }
        for (BedApproach a : slot.approaches()) {
            if (a.beforeDir() == beforeDir) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsObject(ArrayList<IsoObject> list, IsoObject target) {
        if (list == null || target == null) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) {
                return true;
            }
        }
        return false;
    }

    private static BedFacing toBedFacing(IsoDirections d) {
        if (d == null) {
            return BedFacing.S;
        }
        return switch (d) {
            case N -> BedFacing.N;
            case S -> BedFacing.S;
            case W -> BedFacing.W;
            case E -> BedFacing.E;
            default -> BedFacing.S;
        };
    }

    private static ArrayList<IsoObject> collectGridObjects(IsoObject root) {
        ArrayList<IsoObject> tiles = new ArrayList<>();
        root.getSpriteGridObjectsIncludingSelf(tiles);
        if (tiles.isEmpty()) {
            tiles.add(root);
        }
        return tiles;
    }

    private static void clearSitVars(IsoNPC npc) {
        npc.clearVariable("forceGetUp");
        npc.clearVariable("getUpQuick");
        npc.clearVariable("pressedRunButton");
        npc.clearVariable("SitOnFurnitureAnim");
        npc.clearVariable("SitOnFurnitureStarted");
    }

    private static void clearBedVars(IsoNPC npc) {
        npc.clearVariable("forceGetUp");
        npc.clearVariable("getUpQuick");
        npc.clearVariable("pressedRunButton");
        npc.clearVariable("OnBedAnim");
        npc.clearVariable("OnBedStarted");
    }

    private static void clearAllPostureVars(IsoNPC npc) {
        clearSitVars(npc);
        npc.clearVariable("SitOnFurnitureDirection");
        clearBedVars(npc);
        npc.clearVariable("OnBedDirection");
        npc.clearVariable("ER_BedSitFrac");
        npc.clearVariable("ER_SitSleepAnim");
    }

    private static IsoDirections toIsoDir(BedFacing f) {
        if (f == null) {
            return IsoDirections.S;
        }
        return switch (f) {
            case N -> IsoDirections.N;
            case S -> IsoDirections.S;
            case W -> IsoDirections.W;
            case E -> IsoDirections.E;
        };
    }

    private static String resolveBedType(IsoObject bed) {
        if (bed != null && bed.getProperties() != null && bed.getProperties().has("BedType")) {
            String t = bed.getProperties().get("BedType");
            if (t != null && !t.isBlank()) {
                return t;
            }
        }
        return "averageBed";
    }

    private static String safeSpriteName(IsoObject obj) {
        return obj != null && obj.getSprite() != null ? String.valueOf(obj.getSprite().getName()) : "null";
    }

    private static float dist3D(IsoNPC npc, float tx, float ty, int tz) {
        float floorDiff = Math.abs(EmberTargetProjector.levelOf(npc.getZ()) - tz) * 12.0F;
        return (float) Math.hypot(tx - npc.getX(), ty - npc.getY()) + floorDiff;
    }

    private static IsoCell currentCell() {
        return IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
    }

    private record SeatCandidate(
            IsoObject tile, IsoDirections sitDir, String sideStr, IsoDirections beforeDir,
            float pathX, float pathY, float exactX, float exactY, int z, float score) {}

    private record BedCandidate(
            IsoObject effectiveHeadObj, IsoObject otherSlotObj, int slotIndex, BedApproach approach, float score) {}

    private record BedLayout(
            int gridW, int gridH, int originX, int originY, int bedZ,
            IsoObject[][] gridObjects, BedFacing facing, List<BedSlot> slots, int clickedSlot) {}

    private record BedViaSitCandidate(
            IsoObject sleepHeadObj, IsoObject otherSlotObj, boolean isHeadTile,
            BedFacing lieFacing, SeatCandidate primary, List<SeatCandidate> sideCandidates) {}
}
