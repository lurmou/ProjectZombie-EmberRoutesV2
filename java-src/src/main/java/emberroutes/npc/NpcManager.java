package emberroutes.npc;

import emberroutes.npc.SquadTacticalConfig.OrderMode;
import emberroutes.npc.SquadTacticalConfig.VehicleCommandMode;
import emberroutes.npc.brain.BrainContext;
import emberroutes.npc.brain.BrainMode;
import emberroutes.npc.brain.LogisticsWorkDutyProvider;
import emberroutes.npc.brain.SettlementDangerField;
import emberroutes.npc.brain.TempPropHelper;
import emberroutes.npc.brain.VehicleCoRideHelper;
import emberroutes.npc.brain.VehicleSeatRegistry;
import emberroutes.npc.combat.B42CombatRuntime;
import emberroutes.npc.combat.CombatDiagLog;
import emberroutes.npc.combat.CombatHookContext;
import emberroutes.npc.combat.EmberSquadDirector;
import emberroutes.warehouse.WarehouseFastIndex;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.joml.Vector3f;
import zombie.GameTime;
import zombie.ZomboidFileSystem;
import zombie.characters.AttachedItems.AttachedItem;
import zombie.characters.AttachedItems.AttachedItems;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.BodyDamage.BodyPart;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.characters.CharacterStat;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.characters.SurvivorFactory;
import zombie.characters.SurvivorFactory.SurvivorType;
import zombie.characters.WornItems.WornItem;
import zombie.characters.WornItems.WornItems;
import zombie.core.Core;
import zombie.core.skinnedmodel.animation.debug.AnimationPlayerRecorder;
import zombie.debug.DebugLog;
import zombie.entity.ComponentType;
import zombie.entity.components.fluids.Fluid;
import zombie.entity.components.fluids.FluidContainer;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteGrid;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.vehicles.BaseVehicle;

/**
 * M2 非玩家槽 {@link IsoNPC} 全生命周期管理中心：
 * <ul>
 *   <li><b>创建 / 挂载 / PhaseIn</b>：严禁占 {@code IsoPlayer.players[]}，在保护真玩家实例与相机焦点前提下挂载世界；</li>
 *   <li><b>运行态 / PhaseOut</b>：区块卸载自动将 NPC 序列化为 {@link NpcSnapshot} 离屏休眠；区块重新加载无缝恢复原件；</li>
 *   <li><b>正常存盘 / 死亡 / 卸载</b>：仅在 {@code phaseOut} 与 {@code GameWindow.save} 时写入 {@code emberroutes_npcs.bin}，
 *       死亡后由原版 {@link zombie.iso.objects.IsoDeadBody} 接管原件 {@code ItemContainer} 并彻底退役记录。</li>
 * </ul>
 */
public final class NpcManager {
    public static final String MODDATA_HOME_SETTLEMENT_KEY = "ER_HomeSettlementId";
    public static final String MODDATA_BRAIN_MODE_KEY = "ER_BrainMode";
    public static final String MODDATA_GUARD_DUTY_KEY = "ER_GuardDuty";
    private static final String SAVE_FILE_NAME = "emberroutes_npcs.bin";
    private static final int RETIRE_FORCE_DIE_TICKS = 180;
    private static final int RETIRE_HARD_CLEANUP_TICKS = 360;
    private static final int OFFSCREEN_POLL_INTERVAL = 15;

    private static final String[] TEST_BAGS = {
        "Base.Bag_NormalHikingBag", "Base.Bag_BigHikingBag", "Base.Bag_ALICEpack", "Base.Bag_ALICEpack_Army",
        "Base.Bag_DuffelBag", "Base.Bag_DuffelBagTINT", "Base.Bag_Schoolbag", "Base.Bag_Satchel",
        "Base.Bag_SurvivorBag", "Base.Bag_MedicalBag"
    };
    private static final String[] TEST_JACKETS = {
        "Base.Jacket_Varsity", "Base.Jacket_Fireman", "Base.Jacket_Police", "Base.Jacket_Black",
        "Base.Jacket_Padded", "Base.Jacket_Ranger", "Base.Jacket_ArmyCamoGreen", "Base.HoodieDOWN_WhiteTINT",
        "Base.Vest_BulletCivilian", "Base.Jacket_CoatArmy"
    };
    private static final String[] TEST_WEAPONS = {
        "Base.Crowbar", "Base.Axe", "Base.WoodAxe", "Base.BaseballBat", "Base.PipeWrench",
        "Base.HandAxe", "Base.Shovel", "Base.Machete", "Base.HuntingKnife", "Base.Nightstick"
    };
    private static final String[] TEST_SUPPLIES = {
        "Base.Apple", "Base.Bandage", "Base.TinnedBeans", "Base.WaterBottle", "Base.Pills"
    };
    private static final String[] TEST_TOOLS = {
        "Base.Hammer", "Base.Screwdriver", "Base.Saw", "Base.Wrench", "Base.HandTorch"
    };

    private static final class NpcRecord {
        final String npcId;
        IsoNPC liveNpc;
        NpcSnapshot offscreenSnapshot;
        BrainMode brainMode = BrainMode.SETTLEMENT;
        String assignedSettlementId;
        boolean guardDuty;
        boolean phasedIn;
        boolean deadRetired;
        int deathWatchdogTicks;
        long lastEmittedMoveSeq;

        NpcRecord(String npcId) {
            this.npcId = npcId;
        }
    }

    public record MoveEvent(String npcId, long seq, String status, String reason) {}

    public record NpcInfoView(
            String npcId,
            String displayName,
            boolean phasedIn,
            boolean dead,
            boolean isLocalPlayer,
            int playerNumPlayers,
            float x,
            float y,
            float z,
            boolean running,
            String moveStatus,
            String moveReason,
            long moveSeq,
            long settledSeq,
            int itemCount,
            String postureState,
            String debugLine) {}

    public record NpcRosterEntry(
            String npcId,
            String displayName,
            boolean phasedIn,
            boolean dead,
            String assignedSettlementId,
            String brainMode,
            String brainStatePath,
            String postureState,
            double health,
            double hunger,
            double thirst,
            double fatigue,
            boolean bleeding,
            double x,
            double y,
            double z) {}

    public record SquadMemberView(
            String npcId,
            String displayName,
            boolean phasedIn,
            double health,
            boolean bleeding,
            boolean injured,
            String tacticalRole,
            String weaponType,
            String weaponName,
            String effectiveRoe,
            boolean roeOverridden,
            boolean effectiveCqb,
            boolean cqbOverridden,
            String activityState,
            boolean inVehicle,
            int vehicleSeat,
            boolean pendingDismiss,
            double x,
            double y,
            double z) {}

    private static final Map<String, NpcRecord> records = new LinkedHashMap<>();
    private static final List<MoveEvent> pendingMoveEvents = new ArrayList<>();
    private static final EmberFormationMatcher.RectangularHungarianWorkspace rankWorkspace =
            new EmberFormationMatcher.RectangularHungarianWorkspace();
    private static final SquadSlotSolver.Workspace slotWorkspace = new SquadSlotSolver.Workspace();
    private static IsoCell owningCell;
    private static int nextIdSeq = 1;
    private static int tickCounter = 0;
    private static int offscreenCount = 0;
    private static boolean loadedForCurrentSave = false;
    private static long lastRankSolveMs = 0L;
    private static int lastRankFollowerCount = 0;
    private static int lastRankPlayerIndex = -1;
    private static long lastRankConfigVersion = -1L;
    private static final float[] cachedHeadingScratch = new float[2];
    private static float lastSlotAnchorX = 0.0F;
    private static float lastSlotAnchorY = 0.0F;
    private static int lastSlotAnchorZ = Integer.MIN_VALUE;
    private static boolean lastSlotOutsideVeh = false;
    private static boolean lastSlotLeaderMoving = false;
    private static int lastSlotCount = 0;
    private static String[] lastSlotMemberIds = new String[0];
    private static float[] cachedWorldSlotX = new float[16];
    private static float[] cachedWorldSlotY = new float[16];
    private static int[] cachedRanksScratch = new int[16];
    private static WeakReference<BaseVehicle> lastLeaderVehicleRef;
    private static double brainSimTimeSec = 0.0;
    private static volatile List<IsoNPC> liveNpcsSnapshot = java.util.Collections.emptyList();
    private static volatile boolean allNpcAnimRecorderEnabled = false;

    private NpcManager() {}

    public static List<IsoNPC> getLiveNpcsSnapshot() {
        return liveNpcsSnapshot;
    }

    public static synchronized boolean isLoadedForCurrentSave() {
        return loadedForCurrentSave;
    }

    private static void invalidateFollowSlotLockLocked() {
        lastRankSolveMs = 0L;
        lastRankFollowerCount = 0;
        lastSlotCount = 0;
        lastSlotAnchorZ = Integer.MIN_VALUE;
        lastSlotMemberIds = new String[0];
    }

    public static synchronized void reset() {
        try {
            AnimationPlayerRecorder.setAnimationRecorderActiveForType(IsoNPC.class, false);
        } catch (Throwable ignored) {}
        for (NpcRecord rec : records.values()) {
            if (rec.liveNpc != null) {
                try {
                    rec.liveNpc.getBrain().shutdown(rec.liveNpc.getBrainContext());
                    TempPropHelper.cleanupAllTempProps(rec.liveNpc);
                    VehicleCoRideHelper.forceDetachFromVehicle(rec.liveNpc, "RESET");
                    rec.liveNpc.getGaze().forceReset(rec.liveNpc);
                    rec.liveNpc.getPerception().reset();
                    rec.liveNpc.getPosture().forceImmediateReset(rec.liveNpc, "RESET", true);
                    rec.liveNpc.getFollow().stop(rec.liveNpc, false, "RESET");
                    rec.liveNpc.getLocomotion().interrupt(rec.liveNpc, "RESET");
                    rec.liveNpc.cleanupCombatResources();
                    rec.liveNpc.detachAnimDebugTools();
                } catch (Throwable ignored) {}
                detachFromWorld(rec.liveNpc, owningCell);
                rec.liveNpc = null;
            }
        }
        records.clear();
        pendingMoveEvents.clear();
        EmberLeaderTrail.resetAll();
        SettlementDangerField.reset();
        B42CombatRuntime.reset();
        CombatDiagLog.reset();
        CombatHookContext.resetAll();
        liveNpcsSnapshot = java.util.Collections.emptyList();
        LogisticsWorkDutyProvider.reset();
        SquadTacticalConfig.reset();
        VehicleSeatRegistry.reset();
        NpcPortraitBakery.reset();
        owningCell = null;
        nextIdSeq = 1;
        tickCounter = 0;
        offscreenCount = 0;
        loadedForCurrentSave = false;
        lastRankPlayerIndex = -1;
        lastRankConfigVersion = -1L;
        lastSlotOutsideVeh = false;
        lastSlotLeaderMoving = false;
        invalidateFollowSlotLockLocked();
        lastLeaderVehicleRef = null;
        brainSimTimeSec = 0.0;
    }

    private static void recountOffscreenLocked() {
        int count = 0;
        for (NpcRecord rec : records.values()) {
            if (!rec.phasedIn && !rec.deadRetired && rec.offscreenSnapshot != null) {
                count++;
            }
        }
        offscreenCount = count;
    }

    private static void collectSettledMoveEventLocked(NpcRecord rec) {
        if (rec == null || rec.liveNpc == null) return;
        EmberLocomotionController loco = rec.liveNpc.getLocomotion();
        long settled = loco.getSettledSeq();
        if (settled > rec.lastEmittedMoveSeq && settled > 0L) {
            rec.lastEmittedMoveSeq = settled;
            EmberLocomotionController.Status st = loco.getStatus();
            if (st != EmberLocomotionController.Status.IDLE && st != EmberLocomotionController.Status.MOVING) {
                pendingMoveEvents.add(new MoveEvent(rec.npcId, settled, st.name(), loco.getLastReason()));
            }
        }
    }

    /**
     * 零分配批拉取本帧移动终结事件（ARRIVED / NO_PATH / STUCK / INTERRUPTED）：
     * 无事件时直接返回 {@code null}，避免每帧无意义的跨语言对象分配。
     */
    public static synchronized List<MoveEvent> drainMoveEvents() {
        if (pendingMoveEvents.isEmpty()) {
            return null;
        }
        List<MoveEvent> out = new ArrayList<>(pendingMoveEvents);
        pendingMoveEvents.clear();
        return out;
    }

    public static synchronized String spawnNew(double rawX, double rawY, double rawZ, boolean female, String optionalName) {
        IsoCell cell = currentCell();
        if (cell == null) return null;
        syncOwningCell(cell);

        int iz = EmberTargetProjector.levelOf((float) rawZ);
        float spawnX = (float) rawX;
        float spawnY = (float) rawY;
        IsoGridSquare sq = cell.getGridSquare((int) Math.floor(spawnX), (int) Math.floor(spawnY), iz);
        if (sq == null || !sq.TreatAsSolidFloor() || sq.isSolid() || sq.isSolidTrans()) {
            EmberTargetProjector.ProjectedPoint proj = EmberTargetProjector.projectGoal(null, spawnX, spawnY, iz);
            if (proj == null) return null;
            spawnX = proj.x();
            spawnY = proj.y();
            iz = proj.z();
            sq = cell.getGridSquare((int) Math.floor(spawnX), (int) Math.floor(spawnY), iz);
            if (sq == null) return null;
        }

        String npcId = allocateNpcId();
        IsoPlayer playerBefore = IsoPlayer.getInstance();
        IsoGameCharacter cameraBefore = IsoCamera.getCameraCharacter();
        try {
            SurvivorDesc desc = SurvivorFactory.CreateSurvivor(SurvivorType.Neutral, female);
            String name = (optionalName != null && !optionalName.isBlank())
                    ? optionalName.trim()
                    : (desc.getForename() != null && !desc.getForename().isBlank() ? desc.getForename() : npcId);
            desc.setForename(name);

            IsoNPC npc = new IsoNPC(npcId, cell, desc, (int) Math.floor(spawnX), (int) Math.floor(spawnY), iz);
            npc.setUsername(name);
            npc.setFemale(desc.isFemale());
            NpcPerkInitializer.applyRandomPerks(npc);
            attachToCell(npc, cell, sq, spawnX, spawnY, iz, npc.getDirectionAngle());

            NpcRecord rec = new NpcRecord(npcId);
            String homeId = WarehouseFastIndex.findSettlementIdAt(
                    (int) Math.floor(spawnX), (int) Math.floor(spawnY));
            if (homeId != null && !homeId.isEmpty()) {
                rec.assignedSettlementId = homeId;
                npc.getModData().rawset(MODDATA_HOME_SETTLEMENT_KEY, homeId);
            }
            rec.liveNpc = npc;
            rec.phasedIn = true;
            records.put(npcId, rec);
            DebugLog.log("[EmberRoutes] Spawned IsoNPC " + npcId + " at " + spawnX + "," + spawnY + "," + iz);
            return npcId;
        } catch (Throwable t) {
            DebugLog.log("[EmberRoutes] spawnNew failed: " + t.getMessage());
            t.printStackTrace();
            return null;
        } finally {
            restoreCameraAndPlayer(playerBefore, cameraBefore);
        }
    }

    public static synchronized boolean despawn(String npcId) {
        NpcRecord rec = records.remove(npcId);
        if (rec == null) return false;
        SquadTacticalConfig.clearMemberOverrides(npcId);
        EmberSquadDirector.getInstance().releaseAllForNpc(npcId);
        CombatDiagLog.removeNpc(npcId);
        NpcPortraitBakery.invalidate(npcId);
        if (rec.liveNpc != null) {
            rec.liveNpc.getBrain().shutdown(rec.liveNpc.getBrainContext());
            TempPropHelper.cleanupAllTempProps(rec.liveNpc);
            VehicleCoRideHelper.forceDetachFromVehicle(rec.liveNpc, "DESPAWN");
            rec.liveNpc.getGaze().forceReset(rec.liveNpc);
            rec.liveNpc.getPerception().reset();
            rec.liveNpc.getPosture().forceImmediateReset(rec.liveNpc, "DESPAWN", true);
            rec.liveNpc.getFollow().stop(rec.liveNpc, false, "DESPAWN");
            rec.liveNpc.getLocomotion().interrupt(rec.liveNpc, "DESPAWN");
            rec.liveNpc.cleanupCombatResources();
            collectSettledMoveEventLocked(rec);
            rec.liveNpc.detachAnimDebugTools();
            detachFromWorld(rec.liveNpc, currentCell());
            rec.liveNpc = null;
        }
        rec.offscreenSnapshot = null;
        rec.phasedIn = false;
        recountOffscreenLocked();
        return true;
    }

    public static synchronized int despawnAll() {
        List<String> ids = new ArrayList<>(records.keySet());
        int removed = 0;
        for (String id : ids) {
            if (despawn(id)) removed++;
        }
        return removed;
    }

    public static synchronized boolean phaseOut(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || !rec.phasedIn || rec.liveNpc == null || rec.deadRetired || rec.liveNpc.isDead()) {
            return false;
        }
        IsoNPC npc = rec.liveNpc;
        EmberSquadDirector.getInstance().releaseAllForNpc(npcId);
        npc.getBrain().shutdown(npc.getBrainContext());
        TempPropHelper.cleanupAllTempProps(npc);
        VehicleCoRideHelper.forceDetachFromVehicle(npc, "PHASE_OUT");
        npc.getGaze().forceReset(npc);
        npc.getPerception().reset();
        npc.getPosture().forceImmediateReset(npc, "PHASE_OUT", true);
        npc.getFollow().stop(npc, true, "OFFSCREEN");
        npc.getLocomotion().interrupt(npc, "OFFSCREEN");
        npc.cleanupCombatResources();
        collectSettledMoveEventLocked(rec);
        npc.detachAnimDebugTools();
        try {
            rec.offscreenSnapshot = NpcSnapshot.captureFrom(npc);
        } catch (Throwable t) {
            DebugLog.log("[EmberRoutes] phaseOut snapshot failed for " + npcId + ": " + t.getMessage());
            t.printStackTrace();
            return false;
        }
        detachFromWorld(npc, currentCell());
        rec.liveNpc = null;
        rec.phasedIn = false;
        recountOffscreenLocked();
        DebugLog.log("[EmberRoutes] Phased out IsoNPC " + npcId);
        return true;
    }

    public static synchronized boolean phaseIn(String npcId) {
        NpcRecord rec = records.get(npcId);
        IsoCell cell = currentCell();
        if (rec == null || cell == null || rec.phasedIn || rec.deadRetired || rec.offscreenSnapshot == null) {
            return false;
        }
        syncOwningCell(cell);
        boolean ok = phaseInRecord(rec, cell);
        recountOffscreenLocked();
        return ok;
    }

    public static synchronized void tick() {
        EmberLocomotionController.beginFramePathBudget();
        IsoCell cell = currentCell();
        if (cell == null) return;
        syncOwningCell(cell);
        tickCounter++;

        IsoPlayer p0 = (IsoPlayer.players != null && IsoPlayer.players.length > 0) ? IsoPlayer.players[0] : null;
        BaseVehicle curLeaderVeh = (p0 != null && !p0.isDead()) ? p0.getVehicle() : null;
        reconcileLeaderVehicleOverridesLocked(p0, curLeaderVeh);

        List<NpcRecord> list = new ArrayList<>(records.values());
        boolean offscreenChanged = false;
        for (NpcRecord rec : list) {
            if (rec.phasedIn && rec.liveNpc != null) {
                IsoNPC npc = rec.liveNpc;
                if (rec.deadRetired || npc.isDead()) {
                    npc.getLocomotion().interrupt(npc, "DEAD");
                    collectSettledMoveEventLocked(rec);
                    rec.deadRetired = true;
                    rec.deathWatchdogTicks++;
                    boolean done = npc.isOnDeathDone();
                    if (!done && rec.deathWatchdogTicks >= RETIRE_FORCE_DIE_TICKS) {
                        try {
                            npc.die();
                            done = npc.isOnDeathDone();
                        } catch (Throwable t) {
                            DebugLog.log("[EmberRoutes] Forced die() failed for " + rec.npcId + ": " + t.getMessage());
                        }
                    }
                    if (done || rec.deathWatchdogTicks >= RETIRE_HARD_CLEANUP_TICKS) {
                        detachFromWorld(npc, cell);
                        rec.liveNpc = null;
                        rec.phasedIn = false;
                        records.remove(rec.npcId);
                        offscreenChanged = true;
                        DebugLog.log("[EmberRoutes] Retired dead IsoNPC after corpse handoff: " + rec.npcId);
                    }
                    continue;
                }

                collectSettledMoveEventLocked(rec);

                boolean mountedWithLeader = false;
                if (npc.getVehicle() != null) {
                    BaseVehicle veh = npc.getVehicle();
                    if (veh.isRemovedFromWorld() || veh.getSquare() == null || veh.getSeat(npc) == -1) {
                        VehicleCoRideHelper.forceDetachFromVehicle(npc, "VEHICLE_UNLOADED");
                    } else {
                        try {
                            npc.setCurrentSquareFromPosition();
                            if (npc.getCurrentSquare() != null) {
                                npc.setMovingSquareNow();
                            }
                        } catch (Throwable ignored) {}
                        mountedWithLeader = (curLeaderVeh != null && curLeaderVeh == veh);
                    }
                }

                int gx = (int) Math.floor(npc.getX());
                int gy = (int) Math.floor(npc.getY());
                int gz = EmberTargetProjector.levelOf(npc.getZ());
                IsoGridSquare sq = cell.getGridSquare(gx, gy, gz);
                if (sq == null && !mountedWithLeader) {
                    phaseOut(rec.npcId);
                }
            } else if (!rec.phasedIn && !rec.deadRetired && rec.offscreenSnapshot != null
                    && (tickCounter % OFFSCREEN_POLL_INTERVAL == 0)) {
                NpcSnapshot snap = rec.offscreenSnapshot;
                IsoGridSquare sq = cell.getGridSquare(
                        (int) Math.floor(snap.x), (int) Math.floor(snap.y), EmberTargetProjector.levelOf(snap.z));
                if (sq != null && phaseInRecord(rec, cell)) {
                    offscreenChanged = true;
                }
            }
        }
        if (offscreenChanged) {
            recountOffscreenLocked();
        }
    }

    private static void reconcileLeaderVehicleOverridesLocked(IsoPlayer leader, BaseVehicle curLeaderVeh) {
        BaseVehicle prevLeaderVeh = lastLeaderVehicleRef != null ? lastLeaderVehicleRef.get() : null;
        if (prevLeaderVeh != curLeaderVeh) {
            if (prevLeaderVeh != null) {
                // 领袖离开旧车或换乘新车时自动清除旧手动乘降覆写（切回 AUTO 自适应协同）
                SquadTacticalConfig.clearManualVehicleOverride();
            } else if (curLeaderVeh != null) {
                VehicleCommandMode ov = SquadTacticalConfig.getVehicleOverride();
                if (ov == VehicleCommandMode.FORCE_EXIT
                        || (ov == VehicleCommandMode.FORCE_BOARD && !SquadTacticalConfig.matchesOverrideVehicle(curLeaderVeh))) {
                    SquadTacticalConfig.clearManualVehicleOverride();
                }
            }
            lastLeaderVehicleRef = curLeaderVeh != null ? new WeakReference<>(curLeaderVeh) : null;
        }
        if (SquadTacticalConfig.getVehicleOverride() == VehicleCommandMode.FORCE_BOARD) {
            Object ovObj = SquadTacticalConfig.getOverrideVehicle();
            if (!(ovObj instanceof BaseVehicle bv) || bv.isRemovedFromWorld() || bv.getSquare() == null) {
                SquadTacticalConfig.clearManualVehicleOverride();
            } else if (curLeaderVeh == null && leader != null && !leader.isDead()) {
                if (VehicleCoRideHelper.distSq(leader.getX(), leader.getY(), bv.getX(), bv.getY()) > 36.0F * 36.0F) {
                    SquadTacticalConfig.clearManualVehicleOverride();
                }
            }
        }
    }

    /**
     * Chunk 级离屏 NPC 唤醒（由底层 Chunk 传感器直接触发，零 Kahlua 跨语言调用）：
     * 无离屏休眠 NPC 时仅做 O(1) 整数判断即返回。
     */
    public static synchronized void onChunkLoaded(IsoChunk chunk) {
        if (chunk == null || offscreenCount <= 0) return;
        IsoCell cell = currentCell();
        if (cell == null) return;
        syncOwningCell(cell);

        int x0 = chunk.wx * 8;
        int y0 = chunk.wy * 8;
        int x1 = x0 + 8;
        int y1 = y0 + 8;
        boolean changed = false;
        for (NpcRecord rec : new ArrayList<>(records.values())) {
            if (!rec.phasedIn && !rec.deadRetired && rec.offscreenSnapshot != null) {
                NpcSnapshot snap = rec.offscreenSnapshot;
                int sx = (int) Math.floor(snap.x);
                int sy = (int) Math.floor(snap.y);
                if (sx >= x0 && sx < x1 && sy >= y0 && sy < y1) {
                    if (phaseInRecord(rec, cell)) {
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            recountOffscreenLocked();
        }
    }

    public static synchronized void onSquareLoaded(IsoGridSquare sq) {
        if (sq == null || offscreenCount <= 0) return;
        IsoCell cell = currentCell();
        if (cell == null) return;
        syncOwningCell(cell);

        int sx = sq.getX();
        int sy = sq.getY();
        int sz = sq.getZ();
        boolean changed = false;
        for (NpcRecord rec : new ArrayList<>(records.values())) {
            if (!rec.phasedIn && !rec.deadRetired && rec.offscreenSnapshot != null) {
                NpcSnapshot snap = rec.offscreenSnapshot;
                if ((int) Math.floor(snap.x) == sx
                        && (int) Math.floor(snap.y) == sy
                        && EmberTargetProjector.levelOf(snap.z) == sz) {
                    if (phaseInRecord(rec, cell)) {
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            recountOffscreenLocked();
        }
    }

    public static synchronized void onNpcDeath(IsoNPC npc) {
        if (npc == null) return;
        SquadTacticalConfig.clearMemberOverrides(npc.getNpcId());
        EmberSquadDirector.getInstance().releaseAllForNpc(npc.getNpcId());
        CombatDiagLog.removeNpc(npc.getNpcId());
        NpcPortraitBakery.invalidate(npc.getNpcId());
        NpcRecord rec = records.get(npc.getNpcId());
        if (rec != null) {
            npc.getBrain().shutdown(npc.getBrainContext());
            TempPropHelper.cleanupAllTempProps(npc);
            VehicleCoRideHelper.forceDetachFromVehicle(npc, "DEATH");
            npc.getGaze().forceReset(npc);
            npc.getPerception().reset();
            npc.getPosture().forceImmediateReset(npc, "DEATH", true);
            npc.getFollow().stop(npc, false, "DEAD");
            npc.getLocomotion().interrupt(npc, "DEAD");
            npc.cleanupCombatResources();
            collectSettledMoveEventLocked(rec);
            rec.deadRetired = true;
            rec.offscreenSnapshot = null;
            invalidateFollowSlotLockLocked();
            recountOffscreenLocked();
        }
    }

    /** AGENT_BRAIN 阶段单入口批调度：按真实游戏倍速缩放时间驱动在场 NPC 双大脑状态树。 */
    public static synchronized void tickBrains(long frameTick, double dtSim, double luaSimTime) {
        IsoCell cell = currentCell();
        if (cell == null || records.isEmpty()) {
            return;
        }
        GameTime gt = GameTime.getInstance();
        double stepDt = (gt != null && gt.getTimeDelta() > 0.0F) ? gt.getTimeDelta() : Math.max(0.0, dtSim);
        brainSimTimeSec += stepDt;

        List<NpcRecord> snapshot = new ArrayList<>(records.values());
        List<IsoNPC> activeNpcs = new ArrayList<>(snapshot.size());
        for (int i = 0, n = snapshot.size(); i < n; i++) {
            NpcRecord rec = snapshot.get(i);
            if (!rec.phasedIn || rec.deadRetired || rec.liveNpc == null || rec.liveNpc.isDead()) {
                continue;
            }
            IsoNPC npc = rec.liveNpc;
            if (npc.getCurrentSquare() == null && npc.getVehicle() != null) {
                try {
                    npc.setCurrentSquareFromPosition();
                } catch (Throwable ignored) {}
            }
            if (npc.getCurrentSquare() == null && npc.getVehicle() == null) {
                continue;
            }
            BrainContext ctx = npc.getBrainContext();
            ctx.frameTick = frameTick;
            ctx.dtSim = stepDt;
            ctx.simTime = brainSimTimeSec;
            String homeId = rec.assignedSettlementId;
            ctx.settlementId = (homeId != null && !homeId.isEmpty())
                    ? homeId
                    : WarehouseFastIndex.findSettlementIdAt(
                            (int) Math.floor(npc.getX()), (int) Math.floor(npc.getY()));
            ctx.guardDuty = rec.guardDuty;
            activeNpcs.add(npc);
        }

        liveNpcsSnapshot = activeNpcs;
        IsoPlayer leader = (IsoPlayer.players != null && IsoPlayer.players.length > 0) ? IsoPlayer.players[0] : null;
        B42CombatRuntime.tickDirector(
                activeNpcs, leader, brainSimTimeSec, Math.round(brainSimTimeSec * 1000.0));
        LogisticsWorkDutyProvider.tickSettlementAuctions(activeNpcs, brainSimTimeSec);

        for (int i = 0, n = activeNpcs.size(); i < n; i++) {
            IsoNPC npc = activeNpcs.get(i);
            BrainContext ctx = npc.getBrainContext();
            try {
                npc.getPerception().tick(npc, brainSimTimeSec, stepDt);
                npc.getBrain().tick(ctx);
            } catch (Throwable t) {
                DebugLog.log("[EmberRoutes] Brain tick failed for " + npc.getNpcId() + ": " + t.getMessage());
            }
        }
    }

    public static synchronized boolean assignToSettlement(String npcId, String settlementId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null) {
            return false;
        }
        String normalized = (settlementId != null && !settlementId.isBlank()) ? settlementId.trim() : null;
        rec.assignedSettlementId = normalized;
        if (rec.liveNpc != null) {
            if (normalized != null) {
                rec.liveNpc.getModData().rawset(MODDATA_HOME_SETTLEMENT_KEY, normalized);
            } else if (rec.liveNpc.hasModData()) {
                rec.liveNpc.getModData().rawset(MODDATA_HOME_SETTLEMENT_KEY, null);
            }
        }
        if (normalized != null) {
            setBrainMode(npcId, BrainMode.SETTLEMENT.name());
        }
        return true;
    }

    public static synchronized boolean setGuardDuty(String npcId, boolean guard) {
        NpcRecord rec = records.get(npcId);
        if (rec == null) {
            return false;
        }
        rec.guardDuty = guard;
        if (rec.liveNpc != null) {
            rec.liveNpc.getBrainContext().guardDuty = guard;
            if (guard) {
                rec.liveNpc.getModData().rawset(MODDATA_GUARD_DUTY_KEY, Boolean.TRUE);
            } else if (rec.liveNpc.hasModData()) {
                rec.liveNpc.getModData().rawset(MODDATA_GUARD_DUTY_KEY, null);
            }
        }
        return true;
    }

    public static synchronized boolean isGuardDuty(String npcId) {
        NpcRecord rec = records.get(npcId);
        return rec != null && rec.guardDuty;
    }

    public static synchronized String getAssignedSettlementId(String npcId) {
        NpcRecord rec = records.get(npcId);
        return rec != null && rec.assignedSettlementId != null ? rec.assignedSettlementId : "";
    }

    public static synchronized int clearSettlementAssignments(String settlementId) {
        if (settlementId == null || settlementId.isEmpty()) {
            return 0;
        }
        int cleared = 0;
        for (NpcRecord rec : records.values()) {
            if (settlementId.equals(rec.assignedSettlementId)) {
                rec.assignedSettlementId = null;
                if (rec.liveNpc != null && rec.liveNpc.hasModData()) {
                    rec.liveNpc.getModData().rawset(MODDATA_HOME_SETTLEMENT_KEY, null);
                }
                cleared++;
            }
        }
        return cleared;
    }

    public static synchronized boolean setBrainMode(String npcId, String modeName) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || modeName == null) {
            return false;
        }
        BrainMode parsed;
        try {
            parsed = BrainMode.valueOf(modeName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        rec.brainMode = parsed;
        if (rec.liveNpc != null) {
            rec.liveNpc.getModData().rawset(MODDATA_BRAIN_MODE_KEY, parsed.name());
        }
        if (rec.phasedIn && rec.liveNpc != null && !rec.liveNpc.isDead()) {
            IsoNPC npc = rec.liveNpc;
            if (parsed == BrainMode.SETTLEMENT) {
                // 切回据点自治时无条件解除小队跟随（防旧跟随句柄阻挡空闲/返回据点分支）
                npc.getBrainContext().pendingDismissAfterExit = false;
                npc.getPosture().clearPendingContinuation();
                if (npc.getFollow().isEnabled()) {
                    npc.getFollow().stop(npc, false, "BRAIN_SETTLEMENT");
                }
            }
            npc.getBrain().setMode(parsed, npc.getBrainContext());
        }
        return true;
    }

    public static synchronized String getBrainMode(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null) {
            return "MISSING";
        }
        if (rec.phasedIn && rec.liveNpc != null) {
            return rec.liveNpc.getBrain().getMode().name();
        }
        return rec.brainMode.name();
    }

    public static synchronized String getBrainStatePath(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null) {
            return "MISSING";
        }
        if (!rec.phasedIn || rec.liveNpc == null) {
            return "OFFSCREEN";
        }
        return rec.liveNpc.getBrain().getActivePathString();
    }

    /** M3 实机调试专用：给指定 NPC 施加饥饿/口渴/疲劳/安全无感染流血、发放真实补给或恢复全满健康。 */
    public static synchronized String debugApplyNeed(String npcId, String needKind) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || !rec.phasedIn || rec.liveNpc == null || rec.liveNpc.isDead()) {
            return "NO_LIVE_NPC";
        }
        IsoNPC npc = rec.liveNpc;
        String kind = needKind != null ? needKind.trim().toUpperCase(Locale.ROOT) : "";
        switch (kind) {
            case "HUNGER" -> {
                if (npc.getStats() != null) {
                    npc.getStats().set(CharacterStat.HUNGER, 0.70F);
                }
            }
            case "THIRST" -> {
                if (npc.getStats() != null) {
                    npc.getStats().set(CharacterStat.THIRST, 0.70F);
                }
            }
            case "FATIGUE" -> {
                if (npc.getStats() != null) {
                    npc.getStats().set(CharacterStat.FATIGUE, 0.85F);
                }
            }
            case "BLEEDING" -> {
                BodyDamage bd = npc.getBodyDamage();
                if (bd != null) {
                    BodyPart part = bd.getBodyPart(BodyPartType.ForeArm_L);
                    if (part != null) {
                        part.setBandaged(false, 0.0F);
                        part.setScratched(true, true);
                        part.setBleeding(true);
                        if (part.getBleedingTime() < 5.0F) {
                            part.setBleedingTime(10.0F);
                        }
                    }
                }
            }
            case "SURVIVAL_KIT" -> {
                ItemContainer inv = npc.getInventory();
                if (inv != null) {
                    for (int i = 0; i < 2; i++) {
                        InventoryItem apple = createFirstAvailable("Base.Apple", "Base.TinnedBeans");
                        if (apple != null) inv.AddItem(apple);
                        InventoryItem bottle = createFirstAvailable("Base.WaterBottle", "Base.PopBottle");
                        if (bottle != null) {
                            if (bottle.hasComponent(ComponentType.FluidContainer)) {
                                FluidContainer fc = bottle.getFluidContainer();
                                if (fc != null) {
                                    fc.Empty();
                                    fc.addFluid(Fluid.Water, fc.getCapacity());
                                }
                            }
                            inv.AddItem(bottle);
                        }
                        InventoryItem bandage = createFirstAvailable("Base.Bandage", "Base.RippedSheets");
                        if (bandage != null) inv.AddItem(bandage);
                    }
                    try { inv.setDrawDirty(true); } catch (Throwable ignored) {}
                }
            }
            case "RESTORE" -> {
                if (npc.getStats() != null) {
                    npc.getStats().set(CharacterStat.HUNGER, 0.0F);
                    npc.getStats().set(CharacterStat.THIRST, 0.0F);
                    npc.getStats().set(CharacterStat.FATIGUE, 0.0F);
                    npc.getStats().set(CharacterStat.ENDURANCE, 1.0F);
                }
                if (npc.getBodyDamage() != null) {
                    npc.getBodyDamage().RestoreToFullHealth();
                }
            }
            default -> {
                return "UNKNOWN_NEED:" + kind;
            }
        }
        float h = npc.getStats() != null ? npc.getStats().get(CharacterStat.HUNGER) : 0.0F;
        float t = npc.getStats() != null ? npc.getStats().get(CharacterStat.THIRST) : 0.0F;
        float f = npc.getStats() != null ? npc.getStats().get(CharacterStat.FATIGUE) : 0.0F;
        int bleeds = npc.getBodyDamage() != null ? npc.getBodyDamage().getNumPartsBleeding() : 0;
        return String.format(Locale.US, "%s [%s] H=%.2f T=%.2f F=%.2f bleed=%d path=%s",
                npcId, kind, h, t, f, bleeds, npc.getBrain().getActivePathString());
    }

    public static synchronized void onGameWindowSave() {
        saveAllToDisk();
    }

    public static synchronized boolean saveAllToDisk() {
        if (!loadedForCurrentSave) return false;
        if (Core.getInstance().isNoSave()) return false;
        IsoCell cell = currentCell();
        if (cell == null) return false;
        String gameMode = Core.getInstance().getGameMode();
        if ("LastStand".equals(gameMode) || "Tutorial".equals(gameMode)) return false;

        File saveFile = resolveSaveFile();
        if (saveFile == null) return false;

        List<NpcSnapshot> toWrite = new ArrayList<>();
        for (NpcRecord rec : records.values()) {
            if (rec.deadRetired) continue;
            if (rec.phasedIn && rec.liveNpc != null) {
                if (rec.liveNpc.isDead()) {
                    rec.deadRetired = true;
                    if (!rec.liveNpc.isOnDeathDone()) {
                        try { rec.liveNpc.die(); } catch (Throwable ignored) {}
                    }
                    continue;
                }
                try {
                    TempPropHelper.purgeTempPropItemsOnly(rec.liveNpc);
                    toWrite.add(NpcSnapshot.captureFrom(rec.liveNpc));
                } catch (Throwable t) {
                    DebugLog.log("[EmberRoutes] Capture snapshot failed on save for " + rec.npcId + ": " + t.getMessage());
                    if (rec.offscreenSnapshot != null) {
                        toWrite.add(rec.offscreenSnapshot);
                    }
                }
            } else if (rec.offscreenSnapshot != null) {
                toWrite.add(rec.offscreenSnapshot);
            }
        }

        try {
            File parent = saveFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Path tmpPath = saveFile.toPath().resolveSibling(SAVE_FILE_NAME + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmpPath.toFile())))) {
                out.writeInt(NpcSnapshot.FILE_MAGIC);
                out.writeInt(NpcSnapshot.FILE_VERSION);
                out.writeInt(nextIdSeq);
                out.writeInt(toWrite.size());
                for (NpcSnapshot snap : toWrite) {
                    snap.writeTo(out);
                }
            }
            Files.move(tmpPath, saveFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            DebugLog.log("[EmberRoutes] Saved " + toWrite.size() + " NPC snapshot(s) to " + saveFile.getName());
            return true;
        } catch (Throwable t) {
            DebugLog.log("[EmberRoutes] saveAllToDisk failed: " + t.getMessage());
            t.printStackTrace();
            return false;
        }
    }

    public static synchronized int loadFromCurrentSave() {
        IsoCell cell = currentCell();
        if (cell == null) return 0;
        syncOwningCell(cell);
        if (loadedForCurrentSave) return 0;
        loadedForCurrentSave = true;

        File saveFile = resolveSaveFile();
        if (saveFile == null || !saveFile.isFile()) return 0;

        int loadedCount = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(saveFile)))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != NpcSnapshot.FILE_MAGIC || version != NpcSnapshot.FILE_VERSION) {
                DebugLog.log("[EmberRoutes] Ignoring incompatible NPC binary save header");
                return 0;
            }
            int savedSeq = in.readInt();
            nextIdSeq = Math.max(nextIdSeq, savedSeq);
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                NpcSnapshot snap = NpcSnapshot.readFrom(in);
                if (snap.npcId == null || snap.npcId.isEmpty() || records.containsKey(snap.npcId)) {
                    continue;
                }
                bumpSeqFromId(snap.npcId);
                NpcRecord rec = new NpcRecord(snap.npcId);
                rec.offscreenSnapshot = snap;
                rec.phasedIn = false;
                records.put(snap.npcId, rec);
                loadedCount++;
                IsoGridSquare sq = cell.getGridSquare(
                        (int) Math.floor(snap.x), (int) Math.floor(snap.y), EmberTargetProjector.levelOf(snap.z));
                if (sq != null) {
                    phaseInRecord(rec, cell);
                }
            }
            recountOffscreenLocked();
            DebugLog.log("[EmberRoutes] Loaded " + loadedCount + " NPC snapshot(s) from save");
        } catch (Throwable t) {
            recountOffscreenLocked();
            DebugLog.log("[EmberRoutes] loadFromCurrentSave failed: " + t.getMessage());
            t.printStackTrace();
        }
        return loadedCount;
    }

    public static synchronized long moveTo(String npcId, double x, double y, double z, boolean running) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || !rec.phasedIn || rec.liveNpc == null || rec.liveNpc.isDead()) {
            return -1L;
        }
        rec.liveNpc.getFollow().stop(rec.liveNpc, false, "MOVE_TO");
        if (rec.liveNpc.getPosture().isBlockingLocomotion()) {
            collectSettledMoveEventLocked(rec);
            long reservedSeq = Math.max(1L, rec.liveNpc.getLocomotion().getMoveSeq() + 1L);
            rec.liveNpc.getPosture().queueMoveAfterStandUp(rec.liveNpc, x, y, z, running);
            return reservedSeq;
        }
        if (rec.liveNpc.getPosture().isActive()) {
            rec.liveNpc.getPosture().commandStandUp(rec.liveNpc, true);
        }
        long seq = rec.liveNpc.getLocomotion().moveTo(rec.liveNpc, x, y, z, running);
        collectSettledMoveEventLocked(rec);
        return seq;
    }

    public static synchronized boolean stopMove(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || rec.liveNpc == null) return false;
        rec.liveNpc.getFollow().stop(rec.liveNpc, false, "STOP");
        rec.liveNpc.getPosture().clearPendingContinuation();
        if (rec.liveNpc.getPosture().isActive()) {
            rec.liveNpc.getPosture().commandStandUp(rec.liveNpc, true);
        }
        rec.liveNpc.getLocomotion().stop(rec.liveNpc);
        collectSettledMoveEventLocked(rec);
        return true;
    }

    public static synchronized boolean startFollow(String npcId, int playerIndex, int slot) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || !rec.phasedIn || rec.liveNpc == null || rec.liveNpc.isDead()) {
            return false;
        }
        IsoNPC npc = rec.liveNpc;
        rec.brainMode = BrainMode.SQUAD;
        npc.getModData().rawset(MODDATA_BRAIN_MODE_KEY, BrainMode.SQUAD.name());
        npc.getBrainContext().pendingDismissAfterExit = false;
        npc.getBrainContext().manualExitVehicleRequested = false;
        npc.getBrainContext().followPlayerIndex = playerIndex;
        npc.getBrainContext().followSlot = slot;
        npc.getBrain().setMode(BrainMode.SQUAD, npc.getBrainContext());
        npc.getLocomotion().interrupt(npc, "FOLLOW_START");
        collectSettledMoveEventLocked(rec);
        invalidateFollowSlotLockLocked();
        if (npc.getPosture().isBlockingLocomotion()) {
            npc.getPosture().queueFollowAfterStandUp(npc, playerIndex, slot);
            return true;
        }
        if (npc.getPosture().isActive()) {
            npc.getPosture().commandStandUp(npc, true);
        }
        npc.getFollow().start(playerIndex, slot);
        return true;
    }

    public static synchronized boolean sitNpcOnFurniture(String npcId, IsoObject furniture) {
        IsoNPC npc = getLiveNpc(npcId);
        return npc != null && npc.getPosture().commandSitOnFurniture(npc, furniture);
    }

    public static synchronized boolean sleepNpcOnBed(String npcId, IsoObject bed) {
        IsoNPC npc = getLiveNpc(npcId);
        return npc != null && npc.getPosture().commandSleepOnBed(npc, bed);
    }

    public static synchronized boolean standUpNpc(String npcId, boolean urgent) {
        IsoNPC npc = getLiveNpc(npcId);
        if (npc == null) {
            return false;
        }
        npc.getPosture().clearPendingContinuation();
        return npc.getPosture().commandStandUp(npc, urgent);
    }

    public static synchronized boolean isNpcAnimRecorderActive(String npcId) {
        IsoNPC npc = getLiveNpc(npcId);
        return npc != null && npc.isAnimationRecorderActive();
    }

    public static synchronized String toggleNpcAnimRecorder(String npcId) {
        IsoNPC npc = getLiveNpc(npcId);
        if (npc == null || npc.isDead()) {
            return "MISSING";
        }
        boolean next = !npc.isAnimationRecorderActive();
        npc.setAnimRecorderActive(next, next);
        if (!next) {
            npc.closeAnimationRecorder();
        }
        return (next ? "ON -> " : "OFF -> ") + AnimationPlayerRecorder.getRecordingDir();
    }

    public static synchronized boolean isAllNpcAnimRecorderEnabled() {
        return allNpcAnimRecorderEnabled;
    }

    public static synchronized String setAllNpcAnimRecorderEnabled(boolean enabled) {
        allNpcAnimRecorderEnabled = enabled;
        try {
            AnimationPlayerRecorder.setAnimationRecorderActiveForType(IsoNPC.class, enabled);
        } catch (Throwable ignored) {}
        String dir = "";
        try {
            dir = AnimationPlayerRecorder.getRecordingDir();
        } catch (Throwable ignored) {}
        if (!enabled) {
            for (NpcRecord rec : records.values()) {
                if (rec.liveNpc != null) {
                    try {
                        rec.liveNpc.setAnimRecorderActive(false, false);
                        rec.liveNpc.closeAnimationRecorder();
                    } catch (Throwable ignored) {}
                }
            }
        } else {
            for (NpcRecord rec : records.values()) {
                if (rec.phasedIn && !rec.deadRetired && rec.liveNpc != null && !rec.liveNpc.isDead()) {
                    try {
                        DebugLog.log("[EmberRoutes][ANIM_REC] ACTIVE npc=" + rec.npcId
                                + " uid=" + rec.liveNpc.getUID() + " dir=" + dir);
                    } catch (Throwable ignored) {}
                }
            }
        }
        return (enabled ? "ON -> " : "OFF -> ") + dir;
    }

    public static synchronized int standUpAllNpcs(boolean urgent) {
        int count = 0;
        for (NpcRecord rec : records.values()) {
            if (rec.phasedIn && !rec.deadRetired && rec.liveNpc != null && !rec.liveNpc.isDead()) {
                rec.liveNpc.getPosture().clearPendingContinuation();
                if (rec.liveNpc.getPosture().commandStandUp(rec.liveNpc, urgent)) {
                    count++;
                }
            }
        }
        return count;
    }

    public static synchronized String getPostureState(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null) {
            return "MISSING";
        }
        if (!rec.phasedIn || rec.liveNpc == null) {
            return "OFFSCREEN";
        }
        return rec.liveNpc.getPosture().getState().name();
    }

    static synchronized boolean isFurnitureTileClaimedByOther(IsoNPC self, IsoObject tile) {
        return isObjectClaimedByOther(self, tile, false, false);
    }

    static synchronized boolean isBedHeadClaimedByOther(IsoNPC self, IsoObject headObj) {
        return isObjectClaimedByOther(self, headObj, true, false);
    }

    /** 检查给定 {@code SmartSlot} 原点家具（含其同网格其它瓦片，以及正从该家具起身者）是否仍被另一 NPC 物理占用。 */
    public static synchronized boolean isSmartSlotPhysicallyClaimedByOther(
            IsoNPC self, IsoObject originObj, boolean isBed) {
        return isObjectClaimedByOther(self, originObj, isBed, true);
    }

    private static boolean isObjectClaimedByOther(
            IsoNPC self, IsoObject target, boolean includeBed, boolean matchSpriteGrid) {
        if (target == null) {
            return false;
        }
        for (NpcRecord rec : records.values()) {
            if (!rec.phasedIn || rec.deadRetired || rec.liveNpc == null || rec.liveNpc == self || rec.liveNpc.isDead()) {
                continue;
            }
            IsoNPC other = rec.liveNpc;
            if (matchesTargetFurniture(other.getPosture().getTargetObject(), target, matchSpriteGrid)
                    || matchesTargetFurniture(other.getPosture().getSecondaryBedTile(), target, matchSpriteGrid)
                    || matchesTargetFurniture(other.getSitOnFurnitureObject(), target, matchSpriteGrid)
                    || (includeBed && matchesTargetFurniture(other.getBed(), target, matchSpriteGrid))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesTargetFurniture(IsoObject held, IsoObject target, boolean matchSpriteGrid) {
        if (held == null || target == null) {
            return false;
        }
        if (held == target) {
            return true;
        }
        if (!matchSpriteGrid || held.getSquare() == null || target.getSquare() == null) {
            return false;
        }
        IsoGridSquare hSq = held.getSquare();
        IsoGridSquare tSq = target.getSquare();
        if (hSq.getZ() != tSq.getZ()) {
            return false;
        }
        IsoSprite hs = held.getSprite();
        IsoSprite ts = target.getSprite();
        IsoSpriteGrid hg = hs != null ? hs.getSpriteGrid() : null;
        IsoSpriteGrid tg = ts != null ? ts.getSpriteGrid() : null;
        if (hg == null || tg == null || hg.getWidth() != tg.getWidth() || hg.getHeight() != tg.getHeight()) {
            return false;
        }
        int hgx = hg.getSpriteGridPosX(hs);
        int hgy = hg.getSpriteGridPosY(hs);
        int tgx = tg.getSpriteGridPosX(ts);
        int tgy = tg.getSpriteGridPosY(ts);
        if (hgx < 0 || hgy < 0 || tgx < 0 || tgy < 0) {
            return false;
        }
        return (hSq.getX() - hgx) == (tSq.getX() - tgx)
                && (hSq.getY() - hgy) == (tSq.getY() - tgy);
    }

    public static synchronized boolean stopFollow(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || rec.liveNpc == null) return false;
        rec.liveNpc.getPosture().clearPendingContinuation();
        rec.liveNpc.getFollow().stop(rec.liveNpc, true, "USER_STOP");
        invalidateFollowSlotLockLocked();
        if (rec.brainMode == BrainMode.SQUAD) {
            rec.brainMode = BrainMode.SETTLEMENT;
            rec.liveNpc.getModData().rawset(MODDATA_BRAIN_MODE_KEY, BrainMode.SETTLEMENT.name());
            rec.liveNpc.getBrain().setMode(BrainMode.SETTLEMENT, rec.liveNpc.getBrainContext());
        }
        return true;
    }

    public static synchronized boolean isFollowing(String npcId) {
        NpcRecord rec = records.get(npcId);
        return rec != null && rec.phasedIn && rec.liveNpc != null && !rec.liveNpc.isDead()
                && rec.liveNpc.getFollow().isEnabled();
    }

    public static synchronized String getFollowState(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || !rec.phasedIn || rec.liveNpc == null || rec.liveNpc.isDead()) {
            return "INACTIVE";
        }
        return rec.liveNpc.getFollow().getState().name();
    }

    private static boolean isActiveOnFootFollower(NpcRecord rec, int playerIndex) {
        if (!rec.phasedIn || rec.deadRetired || rec.liveNpc == null || rec.liveNpc.isDead()) {
            return false;
        }
        IsoNPC npc = rec.liveNpc;
        if (npc.getVehicle() != null) {
            return false;
        }
        EmberFollowController fc = npc.getFollow();
        return fc.isEnabled() && fc.getLeaderPlayerIndex() == playerIndex;
    }

    static synchronized int getActiveFollowCount(int playerIndex) {
        int count = 0;
        for (NpcRecord rec : records.values()) {
            if (isActiveOnFootFollower(rec, playerIndex)) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    static synchronized int resolveEffectiveFollowRank(IsoNPC self, int playerIndex) {
        return resolveEffectiveFollowRankLocked(self, playerIndex);
    }

    private static int resolveEffectiveFollowRankLocked(IsoNPC self, int playerIndex) {
        if (self == null) {
            return 0;
        }
        refreshFollowRanksLocked(playerIndex, System.currentTimeMillis());
        int assigned = self.getFollow().getAssignedRank();
        return assigned >= 0 ? assigned : 0;
    }

    private static void ensureSlotScratchCapacity(int count) {
        if (cachedWorldSlotX.length < count) {
            int cap = Math.max(count, cachedWorldSlotX.length * 2);
            cachedWorldSlotX = new float[cap];
            cachedWorldSlotY = new float[cap];
            cachedRanksScratch = new int[cap];
        }
    }

    private static boolean areLockedSlotsStillValid(
            List<IsoNPC> dynamicFollowers, int count, int playerIndex,
            boolean configChanged, boolean anchorMovingNow, boolean outsideVeh,
            float anchorX, float anchorY, int anchorFloor) {
        if (configChanged || anchorMovingNow || lastSlotLeaderMoving
                || count != lastSlotCount || lastSlotMemberIds.length != count
                || playerIndex != lastRankPlayerIndex || outsideVeh != lastSlotOutsideVeh
                || anchorFloor != lastSlotAnchorZ || lastSlotAnchorZ == Integer.MIN_VALUE) {
            return false;
        }
        if (Math.hypot(anchorX - lastSlotAnchorX, anchorY - lastSlotAnchorY) > 0.35F) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            IsoNPC npc = dynamicFollowers.get(i);
            if (!npc.getNpcId().equals(lastSlotMemberIds[i])) {
                return false;
            }
            EmberFollowController fc = npc.getFollow();
            if (fc.getAssignedRank() < 0 || !fc.hasAssignedWorldSlot()
                    || fc.getAssignedSlotZ() != anchorFloor
                    || !EmberTargetProjector.canStandFormationSlot(
                            npc, fc.getAssignedSlotX(), fc.getAssignedSlotY(), anchorFloor)) {
                return false;
            }
        }
        return true;
    }

    private static void refreshFollowRanksLocked(int playerIndex, long now) {
        List<IsoNPC> dynamicFollowers = new ArrayList<>();
        for (NpcRecord rec : records.values()) {
            if (isActiveOnFootFollower(rec, playerIndex) && rec.liveNpc.getFollow().getRequestedSlot() < 0) {
                dynamicFollowers.add(rec.liveNpc);
            }
        }

        int count = dynamicFollowers.size();
        if (count == 0) {
            invalidateFollowSlotLockLocked();
            lastRankPlayerIndex = playerIndex;
            lastRankSolveMs = now;
            return;
        }

        long cfgVer = SquadTacticalConfig.getVersion();
        boolean configChanged = (cfgVer != lastRankConfigVersion);
        boolean useHoldAnchor = SquadTacticalConfig.hasHoldAnchor();
        IsoPlayer leader = (IsoPlayer.players != null && playerIndex >= 0 && playerIndex < IsoPlayer.players.length)
                ? IsoPlayer.players[playerIndex]
                : null;
        if (!useHoldAnchor && (leader == null || leader.isDead())) {
            return;
        }

        float anchorX;
        float anchorY;
        float anchorZ;
        float headingX;
        float headingY;
        float seedX;
        float seedY;
        float minAnchorDist = SquadSlotSolver.DEFAULT_ANCHOR_CLEARANCE;
        boolean outsideVeh = false;
        boolean anchorMovingNow;

        if (useHoldAnchor) {
            anchorX = SquadTacticalConfig.getHoldX();
            anchorY = SquadTacticalConfig.getHoldY();
            anchorZ = SquadTacticalConfig.getHoldZ();
            headingX = SquadTacticalConfig.getHoldDirX();
            headingY = SquadTacticalConfig.getHoldDirY();
            seedX = anchorX;
            seedY = anchorY;
            anchorMovingNow = false;
        } else {
            BaseVehicle lVeh = leader.getVehicle();
            if (lVeh != null) {
                outsideVeh = true;
                anchorX = lVeh.getX();
                anchorY = lVeh.getY();
                anchorZ = lVeh.getZ();
                minAnchorDist = VehicleCoRideHelper.computeVehicleSafeRadius(lVeh) + 1.0F;
                Vector3f safePt = VehicleCoRideHelper.computeSafeOutsidePoint(
                        lVeh, Math.max(0, lVeh.getSeat(leader)), dynamicFollowers.get(0));
                seedX = safePt != null ? safePt.x : dynamicFollowers.get(0).getX();
                seedY = safePt != null ? safePt.y : dynamicFollowers.get(0).getY();
            } else {
                anchorX = leader.getX();
                anchorY = leader.getY();
                anchorZ = leader.getZ();
                seedX = anchorX;
                seedY = anchorY;
            }
            EmberLeaderTrail trail = EmberLeaderTrail.forLeader(playerIndex, leader);
            int obsFloor = EmberTargetProjector.levelOf(anchorZ);
            trail.record(anchorX, anchorY, obsFloor, now);
            trail.seedHeadingIfUnset(leader.getForwardDirectionX(), leader.getForwardDirectionY());
            if (trail.getStableHeading(cachedHeadingScratch)) {
                headingX = cachedHeadingScratch[0];
                headingY = cachedHeadingScratch[1];
            } else {
                headingX = leader.getForwardDirectionX();
                headingY = leader.getForwardDirectionY();
            }
            if (outsideVeh) {
                anchorMovingNow = lVeh != null && Math.abs(lVeh.getCurrentSpeedKmHour()) > 0.80F;
                if (!anchorMovingNow) {
                    trail.forceStopped();
                }
            } else {
                anchorMovingNow = trail.isLeaderMoving(now);
            }
        }

        int anchorFloor = EmberTargetProjector.levelOf(anchorZ);
        if (areLockedSlotsStillValid(
                dynamicFollowers, count, playerIndex, configChanged,
                anchorMovingNow, outsideVeh, anchorX, anchorY, anchorFloor)) {
            return;
        }

        boolean allAssigned = true;
        for (int i = 0; i < count; i++) {
            EmberFollowController fc = dynamicFollowers.get(i).getFollow();
            if (fc.getAssignedRank() < 0 || !fc.hasAssignedWorldSlot()) {
                allAssigned = false;
                break;
            }
        }
        if (!configChanged
                && allAssigned
                && count == lastRankFollowerCount
                && playerIndex == lastRankPlayerIndex
                && anchorMovingNow == lastSlotLeaderMoving
                && outsideVeh == lastSlotOutsideVeh
                && now - lastRankSolveMs < 100L) {
            return;
        }

        float[] fx = new float[count];
        float[] fy = new float[count];
        float[] fz = new float[count];
        boolean[] onStairs = new boolean[count];
        int[] prevRank = new int[count];
        TacticalRole[] roles = new TacticalRole[count];
        String[] memberIds = new String[count];
        for (int i = 0; i < count; i++) {
            IsoNPC npc = dynamicFollowers.get(i);
            fx[i] = npc.getX();
            fy[i] = npc.getY();
            fz[i] = npc.getZ();
            IsoGridSquare sq = npc.getCurrentSquare();
            onStairs[i] = sq != null && sq.HasStairs();
            prevRank[i] = npc.getFollow().getAssignedRank();
            roles[i] = VehicleCoRideHelper.resolveTacticalRole(npc);
            memberIds[i] = npc.getNpcId();
        }

        IsoNPC probeActor = dynamicFollowers.get(0);
        SquadSlotSolver.TerrainProbe probe = new SquadSlotSolver.TerrainProbe() {
            @Override
            public boolean canStandSafe(float x, float y) {
                return EmberTargetProjector.canStandFormationSlot(probeActor, x, y, anchorFloor);
            }

            @Override
            public boolean isOpenGround(float x, float y) {
                return EmberTargetProjector.isOpenGround(x, y, anchorFloor);
            }

            @Override
            public boolean isSegmentClear(float x1, float y1, float x2, float y2) {
                return EmberTargetProjector.isFormationSegmentClear(probeActor, x1, y1, x2, y2, anchorFloor);
            }

            @Override
            public boolean canStepTile(int fromTx, int fromTy, int toTx, int toTy) {
                return EmberTargetProjector.canStepFormationTile(probeActor, fromTx, fromTy, toTx, toTy, anchorFloor);
            }
        };

        ensureSlotScratchCapacity(count);
        FormationType formation = resolveEffectiveFormation(anchorX, anchorY, anchorFloor);
        FormationSpacing spacing = SquadTacticalConfig.getSpacing();
        SquadSlotSolver.resolveWorldSlots(
                count, formation, spacing,
                anchorX, anchorY, seedX, seedY, headingX, headingY,
                minAnchorDist, probe, cachedWorldSlotX, cachedWorldSlotY, slotWorkspace);

        if (count == 1) {
            cachedRanksScratch[0] = 0;
        } else {
            EmberFormationMatcher.assignRanksToWorldSlots(
                    count, fx, fy, fz, onStairs, prevRank, roles,
                    formation, configChanged, anchorZ,
                    cachedWorldSlotX, cachedWorldSlotY, probe,
                    cachedRanksScratch, rankWorkspace);
        }

        for (int i = 0; i < count; i++) {
            int r = (cachedRanksScratch[i] >= 0 && cachedRanksScratch[i] < count) ? cachedRanksScratch[i] : i;
            dynamicFollowers.get(i).getFollow().setAssignedSlot(
                    r, cachedWorldSlotX[r], cachedWorldSlotY[r], anchorFloor, true);
        }

        lastSlotAnchorX = anchorX;
        lastSlotAnchorY = anchorY;
        lastSlotAnchorZ = anchorFloor;
        lastSlotOutsideVeh = outsideVeh;
        lastSlotLeaderMoving = anchorMovingNow;
        lastSlotCount = count;
        lastSlotMemberIds = memberIds;
        lastRankFollowerCount = count;
        lastRankPlayerIndex = playerIndex;
        lastRankSolveMs = now;
        lastRankConfigVersion = cfgVer;
    }

    static FormationType resolveEffectiveFormation(float anchorX, float anchorY, int anchorFloor) {
        FormationType formation = SquadTacticalConfig.getFormation();
        if (SquadTacticalConfig.isDefaultCqb()) {
            boolean indoor = B42CombatRuntime.isIndoorViaIsoRegions(anchorX, anchorY, anchorFloor);
            int clearance = indoor ? B42CombatRuntime.countWalkableNeighbors4(anchorX, anchorY, anchorFloor) : 4;
            formation = emberroutes.npc.combat.CqbController.resolveIndoorFormation(true, indoor, clearance, formation);
        }
        return formation;
    }

    static synchronized boolean isClearOfOtherFollowers(
            IsoNPC self, int playerIndex, float x, float y, int z, float minSep) {
        float minSepSq = minSep * minSep;
        for (NpcRecord rec : records.values()) {
            if (!isActiveOnFootFollower(rec, playerIndex) || rec.liveNpc == self) {
                continue;
            }
            IsoNPC other = rec.liveNpc;
            if (EmberTargetProjector.levelOf(other.getZ()) != z) {
                continue;
            }
            float dx = other.getX() - x;
            float dy = other.getY() - y;
            if (dx * dx + dy * dy < minSepSq) {
                return false;
            }
        }
        return true;
    }

    private static String resolveDisplayName(String candidate, String fallbackId) {
        return (candidate != null && !candidate.isEmpty()) ? candidate : fallbackId;
    }

    private static SquadMemberView createOffscreenSquadMemberView(
            NpcRecord rec,
            RulesOfEngagement effRoe,
            boolean roeOver,
            boolean effCqb,
            boolean cqbOver) {
        NpcSnapshot s = rec.offscreenSnapshot;
        return new SquadMemberView(
                rec.npcId,
                resolveDisplayName(s.displayName, rec.npcId),
                false,
                100.0,
                false,
                false,
                TacticalRole.SUPPORT.name(),
                "UNARMED",
                "",
                effRoe.name(),
                roeOver,
                effCqb,
                cqbOver,
                "OFFSCREEN",
                false,
                -1,
                false,
                s.x,
                s.y,
                s.z);
    }

    public static synchronized List<SquadMemberView> listSquadMembers() {
        List<SquadMemberView> out = new ArrayList<>();
        for (NpcRecord rec : records.values()) {
            if (rec.deadRetired || rec.brainMode != BrainMode.SQUAD) {
                continue;
            }
            boolean isLive = rec.phasedIn && rec.liveNpc != null && !rec.liveNpc.isDead();
            RulesOfEngagement effRoe = SquadTacticalConfig.getEffectiveRoe(rec.npcId);
            boolean roeOver = SquadTacticalConfig.hasMemberRoeOverride(rec.npcId);
            boolean effCqb = SquadTacticalConfig.isEffectiveCqb(rec.npcId);
            boolean cqbOver = SquadTacticalConfig.hasMemberCqbOverride(rec.npcId);

            if (isLive) {
                IsoNPC npc = rec.liveNpc;
                String name = resolveDisplayName(npc.getUsername(), rec.npcId);
                BodyDamage bd = npc.getBodyDamage();
                double health = bd != null ? bd.getOverallBodyHealth() : 100.0;
                boolean bleeding = bd != null && bd.getNumPartsBleeding() > 0;
                boolean injured = bleeding || (bd != null && (bd.HasInjury() || health < 99.0));
                TacticalRole role = VehicleCoRideHelper.resolveTacticalRole(npc);
                String weaponType = VehicleCoRideHelper.resolveWeaponType(npc);
                String weaponName = "";
                if (npc.getPrimaryHandItem() instanceof HandWeapon hw) {
                    String dn = hw.getDisplayName();
                    if (dn != null) {
                        weaponName = dn;
                    }
                }
                BaseVehicle veh = npc.getVehicle();
                boolean inVeh = veh != null;
                int seat = veh != null ? veh.getSeat(npc) : -1;
                out.add(new SquadMemberView(
                        rec.npcId,
                        name,
                        true,
                        health,
                        bleeding,
                        injured,
                        role.name(),
                        weaponType,
                        weaponName,
                        effRoe.name(),
                        roeOver,
                        effCqb,
                        cqbOver,
                        resolveMemberActivityState(npc),
                        inVeh,
                        seat,
                        npc.getBrainContext().pendingDismissAfterExit,
                        npc.getX(),
                        npc.getY(),
                        npc.getZ()));
            } else if (rec.offscreenSnapshot != null) {
                out.add(createOffscreenSquadMemberView(rec, effRoe, roeOver, effCqb, cqbOver));
            }
        }
        return out;
    }

    private static String resolveMemberActivityState(IsoNPC npc) {
        if (npc == null) {
            return "WAITING";
        }
        if (npc.getVehicle() != null) {
            if ("true".equalsIgnoreCase(npc.getVariableString("bExitingVehicle"))) {
                return "EXITING";
            }
            return "RIDING";
        }
        String path = npc.getBrain().getActivePathString();
        if (path != null && path.contains("VEHICLE_CO_RIDE")) {
            return "BOARDING";
        }
        String combatState = npc.getBrainContext().combatActivityState;
        if (combatState != null && !combatState.isEmpty()) {
            return combatState;
        }
        EmberFollowController.FollowState fs = npc.getFollow().getState();
        if (fs == EmberFollowController.FollowState.WAIT_VEHICLE) {
            return "WAIT_VEHICLE";
        }
        if (SquadTacticalConfig.getOrderMode() == OrderMode.HOLD_POSITION) {
            return fs == EmberFollowController.FollowState.WAITING ? "HOLDING" : "FOLLOWING";
        }
        return switch (fs) {
            case FOLLOWING, STAIR_QUEUE, YIELDING -> "FOLLOWING";
            default -> "WAITING";
        };
    }

    public static synchronized void computeAndSetHoldAnchor(int playerIndex) {
        IsoPlayer leader = (IsoPlayer.players != null && playerIndex >= 0 && playerIndex < IsoPlayer.players.length)
                ? IsoPlayer.players[playerIndex] : null;
        int targetFloor = leader != null ? EmberTargetProjector.levelOf(leader.getZ()) : 0;
        float sumX = 0.0F;
        float sumY = 0.0F;
        int count = 0;
        for (NpcRecord rec : records.values()) {
            if (!rec.phasedIn || rec.deadRetired || rec.brainMode != BrainMode.SQUAD
                    || rec.liveNpc == null || rec.liveNpc.isDead() || rec.liveNpc.getVehicle() != null) {
                continue;
            }
            IsoNPC npc = rec.liveNpc;
            if (leader == null || EmberTargetProjector.levelOf(npc.getZ()) == targetFloor) {
                sumX += npc.getX();
                sumY += npc.getY();
                count++;
                if (leader == null && count == 1) {
                    targetFloor = EmberTargetProjector.levelOf(npc.getZ());
                }
            }
        }
        float ax;
        float ay;
        if (count > 0) {
            ax = sumX / count;
            ay = sumY / count;
        } else if (leader != null) {
            ax = leader.getX();
            ay = leader.getY();
        } else {
            return;
        }
        float dx = leader != null ? leader.getForwardDirectionX() : 0.0F;
        float dy = leader != null ? leader.getForwardDirectionY() : 1.0F;
        SquadTacticalConfig.setHoldAnchor(ax, ay, targetFloor, dx, dy);
    }

    public static synchronized String requestDismissFromSquad(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || rec.deadRetired) {
            return "MISSING";
        }
        if (rec.phasedIn && rec.liveNpc != null && !rec.liveNpc.isDead()) {
            IsoNPC npc = rec.liveNpc;
            BaseVehicle veh = npc.getVehicle();
            if (veh != null) {
                if (Math.abs(veh.getCurrentSpeedKmHour()) > 0.80F) {
                    npc.getBrainContext().pendingDismissAfterExit = true;
                    return "PENDING_STOP";
                }
                VehicleCoRideHelper.forceDetachFromVehicle(npc, "DISMISS");
            }
            npc.getBrainContext().pendingDismissAfterExit = false;
            npc.getBrainContext().manualExitVehicleRequested = false;
        }
        SquadTacticalConfig.clearMemberOverrides(npcId);
        setBrainMode(npcId, BrainMode.SETTLEMENT.name());
        return "DISMISSED";
    }

    public static synchronized void completePendingDismissToSettlement(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || rec.deadRetired) {
            return;
        }
        SquadTacticalConfig.clearMemberOverrides(npcId);
        if (rec.liveNpc != null) {
            rec.liveNpc.getBrainContext().pendingDismissAfterExit = false;
            rec.liveNpc.getBrainContext().manualExitVehicleRequested = false;
        }
        setBrainMode(npcId, BrainMode.SETTLEMENT.name());
    }

    public static synchronized String commandSquadVehicleToggle(int playerIndex) {
        BaseVehicle seatedVeh = null;
        for (NpcRecord rec : records.values()) {
            if (rec.phasedIn && !rec.deadRetired && rec.brainMode == BrainMode.SQUAD
                    && rec.liveNpc != null && !rec.liveNpc.isDead() && rec.liveNpc.getVehicle() != null) {
                seatedVeh = rec.liveNpc.getVehicle();
                break;
            }
        }
        if (seatedVeh != null) {
            SquadTacticalConfig.setManualVehicleOverride(VehicleCommandMode.FORCE_EXIT, seatedVeh);
            return "EXITING";
        }

        IsoPlayer leader = (IsoPlayer.players != null && playerIndex >= 0 && playerIndex < IsoPlayer.players.length)
                ? IsoPlayer.players[playerIndex] : null;
        BaseVehicle targetVeh = (leader != null && !leader.isDead()) ? leader.getVehicle() : null;
        if (targetVeh == null) {
            targetVeh = VehicleCoRideHelper.findNearestBoardableVehicle(leader, 12.0F);
        }
        if (targetVeh == null || targetVeh.isRemovedFromWorld() || targetVeh.getSquare() == null) {
            return "NO_VEHICLE";
        }
        int freeSeats = 0;
        int max = targetVeh.getMaxPassengers();
        for (int s = 1; s < max; s++) {
            if (targetVeh.isSeatInstalled(s) && !targetVeh.isSeatOccupied(s)) {
                freeSeats++;
            }
        }
        if (freeSeats <= 0) {
            return "FULL";
        }
        for (NpcRecord rec : records.values()) {
            if (rec.phasedIn && !rec.deadRetired && rec.brainMode == BrainMode.SQUAD
                    && rec.liveNpc != null && !rec.liveNpc.isDead()) {
                rec.liveNpc.getBrainContext().manualExitVehicleRequested = false;
            }
        }
        SquadTacticalConfig.setManualVehicleOverride(VehicleCommandMode.FORCE_BOARD, targetVeh);
        return "BOARDING";
    }

    public static synchronized boolean commandMemberExitVehicle(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || !rec.phasedIn || rec.deadRetired || rec.liveNpc == null || rec.liveNpc.isDead()) {
            return false;
        }
        if (rec.liveNpc.getVehicle() == null) {
            return false;
        }
        rec.liveNpc.getBrainContext().manualExitVehicleRequested = true;
        return true;
    }

    public static synchronized boolean isAnySquadMemberInVehicle() {
        for (NpcRecord rec : records.values()) {
            if (rec.phasedIn && !rec.deadRetired && rec.brainMode == BrainMode.SQUAD
                    && rec.liveNpc != null && !rec.liveNpc.isDead() && rec.liveNpc.getVehicle() != null) {
                return true;
            }
        }
        return false;
    }

    static synchronized boolean isHigherRankFollowerOnStairsAhead(IsoNPC self, int playerIndex, int myRank) {
        return describeHigherRankFollowerOnStairsAhead(self, playerIndex, myRank) != null;
    }

    static synchronized String describeHigherRankFollowerOnStairsAhead(IsoNPC self, int playerIndex, int myRank) {
        if (self == null) {
            return null;
        }
        IsoGridSquare selfSq = self.getCurrentSquare();
        if (selfSq != null && selfSq.HasStairs()) {
            return null;
        }
        int selfZ = EmberTargetProjector.levelOf(self.getZ());
        for (NpcRecord rec : records.values()) {
            if (!rec.phasedIn || rec.deadRetired || rec.liveNpc == null || rec.liveNpc.isDead()) {
                continue;
            }
            IsoNPC other = rec.liveNpc;
            if (other == self) {
                continue;
            }
            EmberFollowController fc = other.getFollow();
            if (!fc.isEnabled() || fc.getLeaderPlayerIndex() != playerIndex) {
                continue;
            }
            IsoGridSquare sq = other.getCurrentSquare();
            if (sq == null || !sq.HasStairs()) {
                continue;
            }
            int otherZ = EmberTargetProjector.levelOf(other.getZ());
            if (otherZ != selfZ) {
                continue;
            }
            float dx = other.getX() - self.getX();
            float dy = other.getY() - self.getY();
            if (dx * dx + dy * dy <= 1.3225F) {
                int otherRank = fc.getRequestedSlot() >= 0
                        ? fc.getRequestedSlot()
                        : Math.max(0, fc.getAssignedRank());
                return String.format(Locale.US, "%s(r%d)@(%.2f,%.2f,%.2f)",
                        other.getNpcId(), otherRank, other.getX(), other.getY(), other.getZ());
            }
        }
        return null;
    }

    public static synchronized boolean setRunning(String npcId, boolean running) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || rec.liveNpc == null) return false;
        rec.liveNpc.getLocomotion().onRunningChanged(rec.liveNpc, running);
        return true;
    }

    public static synchronized boolean isMoving(String npcId) {
        NpcRecord rec = records.get(npcId);
        return rec != null && rec.phasedIn && rec.liveNpc != null && rec.liveNpc.getLocomotion().isMoving();
    }

    public static synchronized boolean killNpc(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || !rec.phasedIn || rec.liveNpc == null || rec.liveNpc.isDead()) {
            return false;
        }
        rec.liveNpc.Kill(null);
        return true;
    }

    public static synchronized List<String> listIds() {
        return new ArrayList<>(records.keySet());
    }

    public static synchronized String getNpcId(Object obj) {
        if (!(obj instanceof IsoNPC npc)) {
            return null;
        }
        String id = npc.getNpcId();
        if (id == null || id.isEmpty()) {
            return null;
        }
        NpcRecord rec = records.get(id);
        return (rec != null && rec.liveNpc == npc && rec.phasedIn && !rec.deadRetired) ? id : null;
    }

    public static synchronized boolean isNpcAttached(String npcId) {
        if (npcId == null || npcId.isEmpty()) {
            return false;
        }
        NpcRecord rec = records.get(npcId);
        return rec != null
                && rec.phasedIn
                && !rec.deadRetired
                && rec.liveNpc != null
                && rec.liveNpc.isAttached()
                && !rec.liveNpc.isDead()
                && rec.liveNpc.getCurrentSquare() != null;
    }

    public static synchronized String getNpcDisplayName(String npcId) {
        if (npcId == null || npcId.isEmpty()) {
            return null;
        }
        NpcRecord rec = records.get(npcId);
        if (rec == null) {
            return null;
        }
        if (rec.liveNpc != null && rec.liveNpc.getUsername() != null && !rec.liveNpc.getUsername().isEmpty()) {
            return rec.liveNpc.getUsername();
        }
        if (rec.offscreenSnapshot != null
                && rec.offscreenSnapshot.displayName != null
                && !rec.offscreenSnapshot.displayName.isEmpty()) {
            return rec.offscreenSnapshot.displayName;
        }
        return rec.npcId;
    }

    public static synchronized IsoNPC getLiveNpc(String npcId) {
        if (npcId == null || npcId.isEmpty()) {
            return null;
        }
        NpcRecord rec = records.get(npcId);
        if (rec != null
                && rec.phasedIn
                && !rec.deadRetired
                && rec.liveNpc != null
                && rec.liveNpc.isAttached()
                && !rec.liveNpc.isDead()) {
            return rec.liveNpc;
        }
        return null;
    }

    public static synchronized NpcInfoView getInfo(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null) return null;
        if (rec.phasedIn && rec.liveNpc != null) {
            IsoNPC npc = rec.liveNpc;
            EmberLocomotionController loco = npc.getLocomotion();
            int count = countAllItems(npc.getInventory());
            return new NpcInfoView(
                    rec.npcId,
                    npc.getUsername(),
                    true,
                    npc.isDead(),
                    npc.isLocalPlayer(),
                    IsoPlayer.numPlayers,
                    npc.getX(),
                    npc.getY(),
                    npc.getZ(),
                    npc.isRunningMode(),
                    loco.getStatus().name(),
                    loco.getLastReason(),
                    loco.getMoveSeq(),
                    loco.getSettledSeq(),
                    count,
                    npc.getPosture().getState().name(),
                    loco.getLiveDebugLine(npc)
            );
        }
        if (rec.offscreenSnapshot != null) {
            NpcSnapshot s = rec.offscreenSnapshot;
            return new NpcInfoView(
                    rec.npcId,
                    s.displayName != null ? s.displayName : rec.npcId,
                    false,
                    rec.deadRetired,
                    false,
                    IsoPlayer.numPlayers,
                    s.x,
                    s.y,
                    s.z,
                    false,
                    "OFFSCREEN",
                    "PHASED_OUT",
                    0L,
                    0L,
                    0,
                    "OFFSCREEN",
                    "[OFFSCREEN]"
            );
        }
        return null;
    }

    public static synchronized List<NpcRosterEntry> listRoster() {
        List<NpcRosterEntry> out = new ArrayList<>(records.size());
        for (NpcRecord rec : records.values()) {
            if (rec.deadRetired) {
                continue;
            }
            String assignedId = rec.assignedSettlementId != null ? rec.assignedSettlementId : "";
            if (rec.phasedIn && rec.liveNpc != null) {
                IsoNPC npc = rec.liveNpc;
                if (npc.isDead()) {
                    continue;
                }
                double health = npc.getBodyDamage() != null ? npc.getBodyDamage().getOverallBodyHealth() : 100.0;
                boolean bleeding = npc.getBodyDamage() != null && npc.getBodyDamage().getNumPartsBleeding() > 0;
                double hunger = npc.getStats() != null ? npc.getStats().get(CharacterStat.HUNGER) : 0.0;
                double thirst = npc.getStats() != null ? npc.getStats().get(CharacterStat.THIRST) : 0.0;
                double fatigue = npc.getStats() != null ? npc.getStats().get(CharacterStat.FATIGUE) : 0.0;
                String name = resolveDisplayName(npc.getUsername(), rec.npcId);
                out.add(new NpcRosterEntry(
                        rec.npcId,
                        name,
                        true,
                        false,
                        assignedId,
                        npc.getBrain().getMode().name(),
                        npc.getBrain().getActivePathString(),
                        npc.getPosture().getState().name(),
                        health,
                        hunger,
                        thirst,
                        fatigue,
                        bleeding,
                        npc.getX(),
                        npc.getY(),
                        npc.getZ()));
            } else if (rec.offscreenSnapshot != null) {
                out.add(createOffscreenRosterEntry(rec, assignedId));
            }
        }
        return out;
    }

    private static NpcRosterEntry createOffscreenRosterEntry(NpcRecord rec, String assignedId) {
        NpcSnapshot s = rec.offscreenSnapshot;
        return new NpcRosterEntry(
                rec.npcId,
                resolveDisplayName(s.displayName, rec.npcId),
                false,
                false,
                assignedId,
                rec.brainMode.name(),
                "OFFSCREEN",
                "OFFSCREEN",
                100.0,
                0.0,
                0.0,
                0.0,
                false,
                s.x,
                s.y,
                s.z);
    }

    private static int resolveTestPresetIndex(String npcId) {
        if (npcId != null && npcId.startsWith("npc_")) {
            try {
                int num = Integer.parseInt(npcId.substring(4));
                return Math.floorMod(num - 1, TEST_BAGS.length);
            } catch (NumberFormatException ignored) {}
        }
        return Math.floorMod(npcId != null ? npcId.hashCode() : 0, TEST_BAGS.length);
    }

    public static synchronized String giveTestKit(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null || !rec.phasedIn || rec.liveNpc == null || rec.liveNpc.isDead()) {
            return "NO_LIVE_NPC";
        }
        IsoNPC npc = rec.liveNpc;
        ItemContainer inv = npc.getInventory();
        if (inv == null) return "NO_INVENTORY";

        int idx = resolveTestPresetIndex(npcId);
        InventoryItem bagItem = createFirstAvailable(
                TEST_BAGS[idx], "Base.Bag_NormalHikingBag", "Base.Bag_DuffelBag", "Base.Bag_Schoolbag");
        if (bagItem instanceof InventoryContainer bag) {
            inv.AddItem(bag);
            ItemContainer bagInv = bag.getInventory();
            if (bagInv != null) {
                InventoryItem supply = createFirstAvailable(TEST_SUPPLIES[idx % TEST_SUPPLIES.length], "Base.Apple");
                if (supply != null) bagInv.AddItem(supply);
                InventoryItem nestedTool = createFirstAvailable(TEST_TOOLS[idx % TEST_TOOLS.length], "Base.Hammer");
                if (nestedTool != null) {
                    bagInv.AddItem(nestedTool);
                    nestedTool.getModData().rawset("ER_TestStamp", npcId + ":nested");
                }
            }
            ItemBodyLocation bagLoc = bag.canBeEquipped();
            if (bagLoc != null && (npc.getBodyLocationGroup() == null || npc.getBodyLocationGroup().getLocation(bagLoc) != null)) {
                npc.getWornItems().setItem(bagLoc, bag);
            }
        }

        InventoryItem jacket = createFirstAvailable(TEST_JACKETS[idx], "Base.Jacket_Varsity", "Base.Tshirt_DefaultDECAL");
        if (jacket != null) {
            inv.AddItem(jacket);
            ItemBodyLocation jLoc = jacket.getBodyLocation();
            if (jLoc != null && (npc.getBodyLocationGroup() == null || npc.getBodyLocationGroup().getLocation(jLoc) != null)) {
                npc.getWornItems().setItem(jLoc, jacket);
            }
        }

        InventoryItem weapon = createFirstAvailable(TEST_WEAPONS[idx], "Base.Crowbar", "Base.Hammer");
        if (weapon != null) {
            inv.AddItem(weapon);
            weapon.getModData().rawset("ER_TestStamp", npcId + ":primary");
            npc.setPrimaryHandItem(weapon);
        }

        npc.getModData().rawset("ER_NpcId", npcId);
        try { npc.onWornItemsChanged(); } catch (Throwable ignored) {}
        try { npc.getInventory().setDrawDirty(true); } catch (Throwable ignored) {}
        npc.resetModelNextFrame();
        return inspectInventorySummaryLocked(npc);
    }

    public static synchronized String inspectInventoryIds(String npcId) {
        NpcRecord rec = records.get(npcId);
        if (rec == null) return "MISSING:" + npcId;
        if (!rec.phasedIn || rec.liveNpc == null) {
            return rec.npcId + " [OFFSCREEN_SNAPSHOT] bytes="
                    + (rec.offscreenSnapshot != null && rec.offscreenSnapshot.inventoryData != null
                    ? rec.offscreenSnapshot.inventoryData.length : 0);
        }
        return inspectInventorySummaryLocked(rec.liveNpc);
    }

    private static void appendItemWithStamp(StringBuilder sb, InventoryItem it) {
        sb.append(it.getFullType()).append('#').append(it.id);
        if (it.hasModData()) {
            Object stamp = it.getModData().rawget("ER_TestStamp");
            if (stamp != null) {
                sb.append("[stamp=").append(stamp).append(']');
            }
        }
    }

    private static String inspectInventorySummaryLocked(IsoNPC npc) {
        StringBuilder sb = new StringBuilder();
        sb.append(npc.getNpcId())
                .append(" numPlayers=").append(IsoPlayer.numPlayers)
                .append(" local=").append(npc.isLocalPlayer())
                .append(String.format(Locale.US, " pos=(%.1f,%.1f,%d)",
                        npc.getX(), npc.getY(), EmberTargetProjector.levelOf(npc.getZ())));
        if (npc.hasModData()) {
            Object npcStamp = npc.getModData().rawget("ER_NpcId");
            if (npcStamp != null) {
                sb.append(" npcStamp=").append(npcStamp);
            }
        }
        InventoryItem prim = npc.getPrimaryHandItem();
        if (prim != null) {
            sb.append(" primary=");
            appendItemWithStamp(sb, prim);
        }
        ItemContainer inv = npc.getInventory();
        if (inv != null) {
            sb.append(" items=[");
            List<InventoryItem> items = inv.getItems();
            for (int i = 0; i < items.size(); i++) {
                InventoryItem it = items.get(i);
                if (i > 0) sb.append(", ");
                appendItemWithStamp(sb, it);
                if (it instanceof InventoryContainer bag && bag.getInventory() != null) {
                    sb.append("{");
                    List<InventoryItem> nested = bag.getInventory().getItems();
                    for (int j = 0; j < nested.size(); j++) {
                        InventoryItem sub = nested.get(j);
                        if (j > 0) sb.append(",");
                        appendItemWithStamp(sb, sub);
                    }
                    sb.append("}");
                }
            }
            sb.append("]");
        }
        WornItems worn = npc.getWornItems();
        if (worn != null && worn.size() > 0) {
            sb.append(" worn=").append(worn.size());
        }
        AttachedItems att = npc.getAttachedItems();
        if (att != null && att.size() > 0) {
            sb.append(" attached=").append(att.size());
        }
        return sb.toString();
    }

    private static boolean phaseInRecord(NpcRecord rec, IsoCell cell) {
        NpcSnapshot snap = rec.offscreenSnapshot;
        if (snap == null) return false;
        IsoGridSquare sq = cell.getGridSquare(
                (int) Math.floor(snap.x), (int) Math.floor(snap.y), EmberTargetProjector.levelOf(snap.z));
        if (sq == null) return false;

        IsoPlayer playerBefore = IsoPlayer.getInstance();
        IsoGameCharacter cameraBefore = IsoCamera.getCameraCharacter();
        try {
            SurvivorDesc placeholder = SurvivorFactory.CreateSurvivor(SurvivorType.Neutral, false);
            IsoNPC npc = new IsoNPC(
                    rec.npcId, cell, placeholder,
                    (int) Math.floor(snap.x), (int) Math.floor(snap.y), EmberTargetProjector.levelOf(snap.z));
            snap.restoreTo(npc);
            if (npc.hasModData()
                    && npc.getModData().rawget(MODDATA_HOME_SETTLEMENT_KEY) instanceof String savedHome
                    && !savedHome.isBlank()) {
                rec.assignedSettlementId = savedHome.trim();
            } else if (rec.assignedSettlementId != null && !rec.assignedSettlementId.isBlank()) {
                npc.getModData().rawset(MODDATA_HOME_SETTLEMENT_KEY, rec.assignedSettlementId);
            }
            if (npc.hasModData()
                    && npc.getModData().rawget(MODDATA_BRAIN_MODE_KEY) instanceof String savedBrain
                    && !savedBrain.isBlank()) {
                try {
                    rec.brainMode = BrainMode.valueOf(savedBrain.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {}
            }
            if (npc.hasModData()
                    && Boolean.TRUE.equals(npc.getModData().rawget(MODDATA_GUARD_DUTY_KEY))) {
                rec.guardDuty = true;
            }
            npc.getBrainContext().guardDuty = rec.guardDuty;
            if (rec.brainMode == BrainMode.SQUAD) {
                npc.getBrainContext().followPlayerIndex = 0;
                npc.getBrainContext().followSlot = -1;
            }
            attachToCell(npc, cell, sq, snap.x, snap.y, snap.z, snap.dirAngleDeg);
            npc.getBrain().setMode(rec.brainMode, npc.getBrainContext());
            rec.liveNpc = npc;
            rec.phasedIn = true;
            rec.lastEmittedMoveSeq = 0L;
            DebugLog.log("[EmberRoutes] Phased in IsoNPC " + rec.npcId + " at " + snap.x + "," + snap.y + "," + snap.z);
            return true;
        } catch (Throwable t) {
            DebugLog.log("[EmberRoutes] phaseInRecord failed for " + rec.npcId + ": " + t.getMessage());
            t.printStackTrace();
            return false;
        } finally {
            restoreCameraAndPlayer(playerBefore, cameraBefore);
        }
    }

    private static void attachToCell(
            IsoNPC npc, IsoCell cell, IsoGridSquare sq, float x, float y, float z, float dirAngleDeg) {
        npc.setX(x);
        npc.setY(y);
        npc.setZ(z);
        npc.setNextX(x);
        npc.setNextY(y);
        npc.setLastX(x);
        npc.setLastY(y);
        npc.setLastZ(z);
        npc.setDirectionAngle(dirAngleDeg);
        npc.setCurrent(sq);
        npc.square = sq;
        npc.setMovingSquareNow();
        npc.setDoRender(true);
        npc.setSceneCulled(false);
        npc.setAlphaAndTarget(1.0F);
        npc.setAttached(true);
        npc.addToWorld();
        cell.addMovingObject(npc);
        if (!sq.getMovingObjects().contains(npc)) {
            sq.getMovingObjects().add(npc);
        }
        npc.resetModelNextFrame();
        if (allNpcAnimRecorderEnabled) {
            try {
                AnimationPlayerRecorder.setAnimationRecorderActiveForType(IsoNPC.class, true);
                DebugLog.log("[EmberRoutes][ANIM_REC] TRACK npc=" + npc.getNpcId()
                        + " uid=" + npc.getUID()
                        + " dir=" + AnimationPlayerRecorder.getRecordingDir());
            } catch (Throwable ignored) {}
        }
    }

    private static void detachFromWorld(IsoNPC npc, IsoCell cell) {
        if (npc == null) return;
        npc.setAttached(false);
        npc.discardRootMotion();
        try { npc.removeFromWorld(); } catch (Throwable ignored) {}
        try { npc.removeFromSquare(); } catch (Throwable ignored) {}
        if (cell != null) {
            try { cell.getObjectList().remove(npc); } catch (Throwable ignored) {}
            try { cell.getAddList().remove(npc); } catch (Throwable ignored) {}
            try { cell.getRemoveList().remove(npc); } catch (Throwable ignored) {}
        }
    }

    private static void restoreCameraAndPlayer(IsoPlayer playerBefore, IsoGameCharacter cameraBefore) {
        IsoPlayer.setInstance(playerBefore);
        if (cameraBefore == null) {
            IsoCamera.clearCameraCharacter();
        } else {
            IsoCamera.setCameraCharacter(cameraBefore);
        }
    }

    private static void syncOwningCell(IsoCell cell) {
        if (owningCell != cell) {
            for (NpcRecord rec : records.values()) {
                if (rec.liveNpc != null) {
                    EmberSquadDirector.getInstance().releaseAllForNpc(rec.npcId);
                    rec.liveNpc.getBrain().shutdown(rec.liveNpc.getBrainContext());
                    TempPropHelper.cleanupAllTempProps(rec.liveNpc);
                    VehicleCoRideHelper.forceDetachFromVehicle(rec.liveNpc, "CELL_CHANGED");
                    rec.liveNpc.getLocomotion().interrupt(rec.liveNpc, "CELL_CHANGED");
                    rec.liveNpc.cleanupCombatResources();
                    rec.liveNpc.detachAnimDebugTools();
                    rec.liveNpc.setAttached(false);
                    rec.liveNpc = null;
                    rec.phasedIn = false;
                }
            }
            liveNpcsSnapshot = java.util.Collections.emptyList();
            B42CombatRuntime.reset();
            CombatHookContext.resetAll();
            CombatDiagLog.reset();
            owningCell = cell;
            recountOffscreenLocked();
        }
    }

    private static IsoCell currentCell() {
        return (IsoWorld.instance != null) ? IsoWorld.instance.currentCell : null;
    }

    private static File resolveSaveFile() {
        try {
            if (ZomboidFileSystem.instance == null || Core.gameSaveWorld == null || Core.gameSaveWorld.isEmpty()) {
                return null;
            }
            return ZomboidFileSystem.instance.getFileInCurrentSave(SAVE_FILE_NAME);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String allocateNpcId() {
        String id;
        do {
            id = "npc_" + (nextIdSeq++);
        } while (records.containsKey(id));
        return id;
    }

    private static void bumpSeqFromId(String npcId) {
        if (npcId != null && npcId.startsWith("npc_")) {
            try {
                int num = Integer.parseInt(npcId.substring(4));
                if (num >= nextIdSeq) {
                    nextIdSeq = num + 1;
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    private static InventoryItem createFirstAvailable(String... fullTypes) {
        for (String ft : fullTypes) {
            try {
                InventoryItem item = InventoryItemFactory.CreateItem(ft);
                if (item != null) return item;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static int countAllItems(ItemContainer container) {
        if (container == null) return 0;
        int total = 0;
        List<InventoryItem> items = container.getItems();
        for (int i = 0; i < items.size(); i++) {
            total++;
            InventoryItem it = items.get(i);
            if (it instanceof InventoryContainer bag && bag.getInventory() != null) {
                total += countAllItems(bag.getInventory());
            }
        }
        return total;
    }
}
