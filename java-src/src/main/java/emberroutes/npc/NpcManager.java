package emberroutes.npc;

import emberroutes.npc.brain.BrainContext;
import emberroutes.npc.brain.BrainMode;
import emberroutes.npc.brain.SettlementDangerField;
import emberroutes.npc.brain.TempPropHelper;
import emberroutes.warehouse.WarehouseFastIndex;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import zombie.GameTime;
import zombie.ZomboidFileSystem;
import zombie.characters.AttachedItems.AttachedItem;
import zombie.characters.AttachedItems.AttachedItems;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.characters.SurvivorFactory;
import zombie.characters.SurvivorFactory.SurvivorType;
import zombie.characters.WornItems.WornItem;
import zombie.characters.WornItems.WornItems;
import zombie.core.Core;
import zombie.debug.DebugLog;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemContainer;
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

    private static final Map<String, NpcRecord> records = new LinkedHashMap<>();
    private static final List<MoveEvent> pendingMoveEvents = new ArrayList<>();
    private static IsoCell owningCell;
    private static int nextIdSeq = 1;
    private static int tickCounter = 0;
    private static int offscreenCount = 0;
    private static boolean loadedForCurrentSave = false;
    private static long lastRankSolveMs = 0L;
    private static int lastRankFollowerCount = 0;
    private static int lastRankPlayerIndex = -1;
    private static double brainSimTimeSec = 0.0;

    private NpcManager() {}

    public static synchronized boolean isLoadedForCurrentSave() {
        return loadedForCurrentSave;
    }

    public static synchronized void reset() {
        for (NpcRecord rec : records.values()) {
            if (rec.liveNpc != null) {
                try {
                    rec.liveNpc.getBrain().shutdown(rec.liveNpc.getBrainContext());
                    TempPropHelper.cleanupAllTempProps(rec.liveNpc);
                    rec.liveNpc.getPosture().forceImmediateReset(rec.liveNpc, "RESET", true);
                    rec.liveNpc.getFollow().stop(rec.liveNpc, false, "RESET");
                    rec.liveNpc.getLocomotion().interrupt(rec.liveNpc, "RESET");
                } catch (Throwable ignored) {}
                detachFromWorld(rec.liveNpc, owningCell);
                rec.liveNpc = null;
            }
        }
        records.clear();
        pendingMoveEvents.clear();
        EmberLeaderTrail.resetAll();
        SettlementDangerField.reset();
        owningCell = null;
        nextIdSeq = 1;
        tickCounter = 0;
        offscreenCount = 0;
        loadedForCurrentSave = false;
        lastRankSolveMs = 0L;
        lastRankFollowerCount = 0;
        lastRankPlayerIndex = -1;
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
            attachToCell(npc, cell, sq, spawnX, spawnY, iz, npc.getDirectionAngle());

            NpcRecord rec = new NpcRecord(npcId);
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
        if (rec.liveNpc != null) {
            rec.liveNpc.getBrain().shutdown(rec.liveNpc.getBrainContext());
            TempPropHelper.cleanupAllTempProps(rec.liveNpc);
            rec.liveNpc.getPosture().forceImmediateReset(rec.liveNpc, "DESPAWN", true);
            rec.liveNpc.getFollow().stop(rec.liveNpc, false, "DESPAWN");
            rec.liveNpc.getLocomotion().interrupt(rec.liveNpc, "DESPAWN");
            collectSettledMoveEventLocked(rec);
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
        npc.getBrain().shutdown(npc.getBrainContext());
        TempPropHelper.cleanupAllTempProps(npc);
        npc.getPosture().forceImmediateReset(npc, "PHASE_OUT", true);
        npc.getFollow().stop(npc, true, "OFFSCREEN");
        npc.getLocomotion().interrupt(npc, "OFFSCREEN");
        collectSettledMoveEventLocked(rec);
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

                int gx = (int) Math.floor(npc.getX());
                int gy = (int) Math.floor(npc.getY());
                int gz = EmberTargetProjector.levelOf(npc.getZ());
                IsoGridSquare sq = cell.getGridSquare(gx, gy, gz);
                if (sq == null) {
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
        NpcRecord rec = records.get(npc.getNpcId());
        if (rec != null) {
            npc.getBrain().shutdown(npc.getBrainContext());
            TempPropHelper.cleanupAllTempProps(npc);
            npc.getPosture().forceImmediateReset(npc, "DEATH", true);
            npc.getFollow().stop(npc, false, "DEAD");
            npc.getLocomotion().interrupt(npc, "DEAD");
            collectSettledMoveEventLocked(rec);
            rec.deadRetired = true;
            rec.offscreenSnapshot = null;
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
        for (int i = 0, n = snapshot.size(); i < n; i++) {
            NpcRecord rec = snapshot.get(i);
            if (!rec.phasedIn || rec.deadRetired || rec.liveNpc == null || rec.liveNpc.isDead()) {
                continue;
            }
            IsoNPC npc = rec.liveNpc;
            if (npc.getCurrentSquare() == null) {
                continue;
            }
            BrainContext ctx = npc.getBrainContext();
            ctx.frameTick = frameTick;
            ctx.dtSim = stepDt;
            ctx.simTime = brainSimTimeSec;
            ctx.settlementId = WarehouseFastIndex.findSettlementIdAt(
                    (int) Math.floor(npc.getX()), (int) Math.floor(npc.getY()));
            try {
                npc.getBrain().tick(ctx);
            } catch (Throwable t) {
                DebugLog.log("[EmberRoutes] Brain tick failed for " + rec.npcId + ": " + t.getMessage());
            }
        }
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
        if (rec.phasedIn && rec.liveNpc != null && !rec.liveNpc.isDead()) {
            rec.liveNpc.getBrain().setMode(parsed, rec.liveNpc.getBrainContext());
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
        rec.liveNpc.getLocomotion().interrupt(rec.liveNpc, "FOLLOW_START");
        collectSettledMoveEventLocked(rec);
        if (rec.liveNpc.getPosture().isBlockingLocomotion()) {
            rec.liveNpc.getPosture().queueFollowAfterStandUp(rec.liveNpc, playerIndex, slot);
            return true;
        }
        if (rec.liveNpc.getPosture().isActive()) {
            rec.liveNpc.getPosture().commandStandUp(rec.liveNpc, true);
        }
        rec.liveNpc.getFollow().start(playerIndex, slot);
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
        rec.liveNpc.getFollow().stop(rec.liveNpc, true, "USER_STOP");
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

    private static void refreshFollowRanksLocked(int playerIndex, long now) {
        List<IsoNPC> dynamicFollowers = new ArrayList<>();
        for (NpcRecord rec : records.values()) {
            if (!rec.phasedIn || rec.deadRetired || rec.liveNpc == null || rec.liveNpc.isDead()) {
                continue;
            }
            IsoNPC npc = rec.liveNpc;
            EmberFollowController fc = npc.getFollow();
            if (!fc.isEnabled() || fc.getLeaderPlayerIndex() != playerIndex || fc.getRequestedSlot() >= 0) {
                continue;
            }
            dynamicFollowers.add(npc);
        }

        int count = dynamicFollowers.size();
        if (count == 0) {
            lastRankFollowerCount = 0;
            lastRankPlayerIndex = playerIndex;
            lastRankSolveMs = now;
            return;
        }

        boolean allAssigned = true;
        for (int i = 0; i < count; i++) {
            if (dynamicFollowers.get(i).getFollow().getAssignedRank() < 0) {
                allAssigned = false;
                break;
            }
        }
        if (allAssigned
                && count == lastRankFollowerCount
                && playerIndex == lastRankPlayerIndex
                && now - lastRankSolveMs < 100L) {
            return;
        }

        IsoPlayer leader = (IsoPlayer.players != null && playerIndex >= 0 && playerIndex < IsoPlayer.players.length)
                ? IsoPlayer.players[playerIndex]
                : null;
        if (leader == null || leader.isDead()) {
            return;
        }

        float[] fx = new float[count];
        float[] fy = new float[count];
        float[] fz = new float[count];
        boolean[] onStairs = new boolean[count];
        int[] prevRank = new int[count];
        for (int i = 0; i < count; i++) {
            IsoNPC npc = dynamicFollowers.get(i);
            fx[i] = npc.getX();
            fy[i] = npc.getY();
            fz[i] = npc.getZ();
            IsoGridSquare sq = npc.getCurrentSquare();
            onStairs[i] = sq != null && sq.HasStairs();
            prevRank[i] = npc.getFollow().getAssignedRank();
        }

        EmberFollowController firstFc = dynamicFollowers.get(0).getFollow();
        float headingX = firstFc.getLastDirX();
        float headingY = firstFc.getLastDirY();
        if (headingX * headingX + headingY * headingY < 1.0E-4F) {
            headingX = leader.getForwardDirectionX();
            headingY = leader.getForwardDirectionY();
        }

        int[] solved = EmberFormationMatcher.assignRanks(
                count, fx, fy, fz, onStairs, prevRank,
                leader.getX(), leader.getY(), leader.getZ(),
                headingX, headingY);
        for (int i = 0; i < count; i++) {
            dynamicFollowers.get(i).getFollow().setAssignedRank(solved[i]);
        }
        lastRankFollowerCount = count;
        lastRankPlayerIndex = playerIndex;
        lastRankSolveMs = now;
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
                    rec.liveNpc.getBrain().shutdown(rec.liveNpc.getBrainContext());
                    TempPropHelper.cleanupAllTempProps(rec.liveNpc);
                    rec.liveNpc.getLocomotion().interrupt(rec.liveNpc, "CELL_CHANGED");
                    rec.liveNpc.setAttached(false);
                    rec.liveNpc = null;
                    rec.phasedIn = false;
                }
            }
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
