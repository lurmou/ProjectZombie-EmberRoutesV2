package emberroutes.npc.combat;

import emberroutes.npc.EmberLocomotionController;
import emberroutes.npc.EmberTargetProjector;
import emberroutes.npc.IsoNPC;
import emberroutes.npc.NpcManager;
import emberroutes.npc.NpcNullInputComponent;
import emberroutes.npc.RulesOfEngagement;
import emberroutes.npc.SquadTacticalConfig;
import emberroutes.npc.brain.BaseAction;
import emberroutes.npc.brain.BrainContext;
import emberroutes.npc.brain.SelfInventoryTransferAction;
import emberroutes.npc.brain.StateNode;
import emberroutes.npc.brain.StateScope;
import emberroutes.npc.combat.NpcWeaponSelector.DesiredWeaponPlan;
import emberroutes.npc.combat.NpcWeaponSelector.ReloadKind;
import emberroutes.npc.combat.NpcWeaponSelector.WeaponMode;
import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.physics.BallisticsController;
import zombie.inventory.types.HandWeapon;

/**
 * 随从与据点守卫共用的 {@code EmberStateTree} 战斗子树与单兵交战执行核：
 * <ol>
 *   <li>真子叶拆分：{@code COMBAT_ROOT -> (COMBAT_RETREAT, COMBAT_EQUIP_OR_RELOAD, COMBAT_RANGED, COMBAT_MELEE)}；</li>
 *   <li>全链路遵守异步「起身屏障」与 {@code StateScope} RAII 对称回收（交由 {@link B42CombatRuntime#cleanupNpcCombat} 单点收口）；</li>
 *   <li>唯一身体控制端口：所有移动矢量、架势与瞄准朝向均经 {@link EmberLocomotionController#setCombatAimStrafe} 同帧下发。</li>
 * </ol>
 */
public final class SquadCombatNodes {
    private static final float SELF_DEFENSE_ENTER_DIST = 1.35F;
    private static final float SELF_DEFENSE_EXIT_DIST = 1.85F;
    private static final float SQUAD_HOLD_COMBAT_RADIUS = 6.5F;
    private static final double TARGET_COMMIT_SIM = 0.45;
    private static final int MAX_ALLY_ARRAY = 16;

    private SquadCombatNodes() {}

    public static StateNode createEmergencySelfDefenseNode() {
        return new EmergencySelfDefenseNode();
    }

    public static StateNode createCombatRootNode() {
        return new CombatRootNode();
    }

    /** 供 {@link CombatRootNode} 与 {@link GuardCombatNode} 共享的 4 枚正式战斗子叶。 */
    public static StateNode[] createCombatChildLeaves(
            CombatSessionState session, boolean allowSquadCqbSweep) {
        return new StateNode[]{
            new CombatRetreatLeaf(session),
            new CombatEquipOrReloadLeaf(session),
            new CombatRangedLeaf(session, allowSquadCqbSweep),
            new CombatMeleeLeaf(session)
        };
    }

    /** 共有状态上下文（纯内存瞬态，由战斗根分支或守卫分支持有并经 {@link StateScope} 自动清理）。 */
    public static final class CombatSessionState {
        public final HordeRetreatPolicy retreatPolicy = new HordeRetreatPolicy();
        public final CqbController cqb = new CqbController();
        public final CombatLocomotionSolver.CombatMoveState moveState =
                new CombatLocomotionSolver.CombatMoveState();
        public final CombatLocomotionSolver.MoveCommand moveCmd =
                new CombatLocomotionSolver.MoveCommand();
        public final CombatLocomotionSolver.SafeSideGuard safeGuard =
                new CombatLocomotionSolver.SafeSideGuard();
        public final MeleeTargetSelector.SelectedEngagement selectedMelee =
                new MeleeTargetSelector.SelectedEngagement();
        public final RangedTacticsController.RepositionCommand rangedCmd =
                new RangedTacticsController.RepositionCommand();
        public final float[] allyXs = new float[MAX_ALLY_ARRAY];
        public final float[] allyYs = new float[MAX_ALLY_ARRAY];
        public final String[] allyIds = new String[MAX_ALLY_ARRAY];
        public final float[] retreatCentroidScratch = new float[2];

        public int currentMeleeTargetId = -1;
        public int currentRangedTargetId = -1;
        public double targetCommitUntilSim;
        public double lastShoveSim = -10.0;
        public long lastCqbScanMs;
        public String activeSubState = "IDLE";

        public void reset() {
            retreatPolicy.reset();
            cqb.reset();
            moveState.reset();
            moveCmd.reset();
            safeGuard.reset();
            rangedCmd.reset();
            selectedMelee.threat = null;
            selectedMelee.ringSlot = -1;
            currentMeleeTargetId = -1;
            currentRangedTargetId = -1;
            targetCommitUntilSim = 0.0;
            lastShoveSim = -10.0;
            lastCqbScanMs = 0L;
            activeSubState = "IDLE";
        }
    }

    public static boolean hasThreatWithin(IsoNPC npc, float radius) {
        return npc != null && findClosestThreatDist(npc, radius) != null;
    }

    public static void performQuickDefensiveShove(
            IsoNPC npc, EmberSquadDirector.ThreatEntry blocker, double nowSim) {
        MeleeAttackExecutor.commitDefensiveShove(npc, blocker, nowSim);
    }

    public static EmberSquadDirector.ThreatEntry findClosestThreatDist(IsoNPC npc, float maxRadius) {
        if (npc == null) return null;
        EmberSquadDirector director = EmberSquadDirector.getInstance();
        int npcZ = EmberTargetProjector.levelOf(npc.getZ());
        float nx = npc.getX();
        float ny = npc.getY();
        EmberSquadDirector.ThreatEntry best = null;
        float bestDistSq = maxRadius * maxRadius;
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || !t.perceivedBySquad || Math.abs(t.z - npcZ) > 0.6F) continue;
            float dx = t.x - nx;
            float dy = t.y - ny;
            float dSq = dx * dx + dy * dy;
            if (dSq <= bestDistSq) {
                bestDistSq = dSq;
                best = t;
            }
        }
        return best;
    }

    static boolean shouldEngageSquadCombat(BrainContext ctx) {
        if (ctx.npc == null || ctx.npc.isDead() || ctx.npc.getVehicle() != null) {
            return false;
        }
        RulesOfEngagement roe = SquadTacticalConfig.getEffectiveRoe(ctx.npcId);
        if (roe == RulesOfEngagement.HOLD_FIRE) {
            return false;
        }
        boolean cqb = SquadTacticalConfig.isEffectiveCqb(ctx.npcId);
        IsoNPC npc = ctx.npc;
        HandWeapon eq = NpcWeaponSelector.getEquippedWeapon(npc);
        boolean hasGun = NpcWeaponSelector.isUsableFirearm(eq)
                && NpcWeaponSelector.hasAnyReserveAmmoForGun(npc, eq);
        float maxEngage = hasGun ? Math.max(12.0F, eq.getMaxRange(npc) + 3.5F) : 10.5F;
        if (roe == RulesOfEngagement.RETURN_FIRE) {
            IsoPlayer leader = resolveLeader(ctx.followPlayerIndex);
            boolean leaderInFight = leader != null && (leader.isAttackStarted() || leader.isAiming());
            maxEngage = leaderInFight ? maxEngage : 6.5F;
        }
        if (findClosestThreatDist(npc, maxEngage) != null) {
            return true;
        }
        return cqb && hasGun && !npc.isOutside();
    }

    /** ① 最高优贴身自卫（停火令 / 长动作打断 / 枪手被极近身贴脸时的紧急推开自保）。 */
    private static final class EmergencySelfDefenseNode extends StateNode {
        private final CombatLocomotionSolver.CombatMoveState moveState =
                new CombatLocomotionSolver.CombatMoveState();
        private final CombatLocomotionSolver.MoveCommand moveCmd =
                new CombatLocomotionSolver.MoveCommand();
        private final float[] allyXs = new float[MAX_ALLY_ARRAY];
        private final float[] allyYs = new float[MAX_ALLY_ARRAY];
        private double enteredAtSim;

        EmergencySelfDefenseNode() {
            super("EMERGENCY_SELF_DEFENSE", null, null, 0.0, true);
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            if (ctx.npc == null || ctx.npc.isDead() || ctx.npc.getVehicle() != null) {
                return false;
            }
            RulesOfEngagement roe = SquadTacticalConfig.getEffectiveRoe(ctx.npcId);
            boolean hasAction = !ctx.npc.getCharacterActions().isEmpty();
            HandWeapon eq = NpcWeaponSelector.getEquippedWeapon(ctx.npc);
            boolean holdingFirearm = NpcWeaponSelector.isUsableFirearm(eq);
            boolean closeThreat = findClosestThreatDist(ctx.npc, SELF_DEFENSE_ENTER_DIST) != null;
            return CombatTargetState.isEmergencySelfDefenseNeeded(roe, hasAction, holdingFirearm, closeThreat);
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            if (ctx.npc == null || ctx.npc.isDead() || ctx.npc.getVehicle() != null) {
                return false;
            }
            if (ctx.simTime - enteredAtSim < 0.40) {
                return true;
            }
            return findClosestThreatDist(ctx.npc, SELF_DEFENSE_EXIT_DIST) != null;
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            ctx.working = false;
            enteredAtSim = ctx.simTime;
            moveState.reset();
            moveCmd.reset();
            ctx.combatActivityState = "COMBAT";
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead()) return;

            ctx.requestStandUp(true);
            npc.getFollow().stop(npc, false, "EMERGENCY_SELF_DEF");
            if (!npc.getCharacterActions().isEmpty()) {
                npc.StopAllActionQueue();
            }
            scope.defer(npc.getGaze().suppress("COMBAT"));
            scope.defer(() -> B42CombatRuntime.cleanupNpcCombat(ctx.npc, null));
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead()) return;
            if (npc.getPosture().isBlockingLocomotion()) {
                ctx.requestStandUp(true);
                return;
            }

            EmberSquadDirector.ThreatEntry threat = findClosestThreatDist(npc, SELF_DEFENSE_EXIT_DIST + 0.5F);
            if (threat == null) {
                npc.getLocomotion().clearCombatMove(npc);
                return;
            }

            int zLvl = EmberTargetProjector.levelOf(npc.getZ());
            float bodyYaw = npc.getDirectionAngleRadians();
            npc.getVirtualAim().aimAtTarget(
                    bodyYaw,
                    npc.getX(),
                    npc.getY(),
                    npc.getZ(),
                    threat.x,
                    threat.y,
                    threat.z,
                    threat.velX,
                    threat.velY,
                    CombatTargetState.isProneOrCrawl(threat.stateMask),
                    2.5F,
                    (float) Math.max(0.016, dtSim));

            float dx = threat.x - npc.getX();
            float dy = threat.y - npc.getY();
            float dist = (float) Math.hypot(dx, dy);
            EmberSquadDirector director = EmberSquadDirector.getInstance();
            int allyCount = collectAllies(npc, zLvl, allyXs, allyYs, null);

            CombatLocomotionSolver.computeRangedRetreatStep(
                    moveState,
                    director,
                    npc.getNpcId(),
                    npc.getX(),
                    npc.getY(),
                    zLvl,
                    threat.x,
                    threat.y,
                    dist,
                    allyXs,
                    allyYs,
                    allyCount,
                    false,
                    0.0F,
                    0.0F,
                    0.0F,
                    B42CombatRuntime.WORLD_PROBE,
                    ctx.simTime,
                    (float) dtSim,
                    moveCmd);

            boolean wantStep = moveCmd.wantMove && dist < 1.15F;
            npc.getLocomotion().setCombatAimStrafe(
                    npc,
                    wantStep ? moveCmd.dirX : 0.0F,
                    wantStep ? moveCmd.dirY : 0.0F,
                    wantStep,
                    true,
                    true,
                    npc.getVirtualAim().getAimDirX(),
                    npc.getVirtualAim().getAimDirY(),
                    false,
                    0.78F);

            if (npc.getMeleeDelay() <= 0.5F
                    && npc.CanAttack()
                    && !npc.isCombatAttackBlocked(ctx.simTime)
                    && dist <= MeleeWeaponMetrics.VANILLA_BARE_HANDS_MAX_RANGE + 0.15F) {
                MeleeAttackExecutor.commitDefensiveShove(npc, threat, ctx.simTime);
            }
        }
    }

    /** ② {@code SQUAD} 主战斗分支节点（持有统一姿态、凝视屏蔽与收口生命周期）。 */
    private static final class CombatRootNode extends StateNode {
        private final CombatSessionState session;

        CombatRootNode() {
            this(new CombatSessionState());
        }

        private CombatRootNode(CombatSessionState session) {
            super("COMBAT_ROOT", createCombatChildLeaves(session, true), null, 0.25, true);
            this.session = session;
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            return shouldEngageSquadCombat(ctx);
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            return shouldEngageSquadCombat(ctx) || session.retreatPolicy.isActive();
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            ctx.working = false;
            session.reset();
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead()) return;
            ctx.requestStandUp(true);
            npc.getFollow().stop(npc, false, "COMBAT_ROOT");
            scope.defer(npc.getGaze().suppress("COMBAT"));
            scope.defer(() -> B42CombatRuntime.cleanupNpcCombat(ctx.npc, session));
        }
    }

    /** ③ 战斗子叶 1：尸潮质心撤退（直行目标点，不做二次偏转打架）。 */
    private static final class CombatRetreatLeaf extends StateNode {
        private final CombatSessionState session;

        CombatRetreatLeaf(CombatSessionState session) {
            super("COMBAT_RETREAT", null, null, 0.0, true);
            this.session = session;
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead() || npc.getPosture().isBlockingLocomotion()
                    || npc.isPerformingHostileSwing()) {
                return false;
            }
            int npcZ = EmberTargetProjector.levelOf(npc.getZ());
            boolean hasPersonalHorde = EmberSquadDirector.getInstance().findPersonalHordeThreat(
                    npc.getNpcId(),
                    npc.getX(),
                    npc.getY(),
                    npcZ,
                    HordeRetreatPolicy.TRIGGER_RADIUS_TILES,
                    session.retreatCentroidScratch);
            return session.retreatPolicy.wouldTrigger(
                    npc.getX(),
                    npc.getY(),
                    hasPersonalHorde,
                    session.retreatCentroidScratch[0],
                    session.retreatCentroidScratch[1],
                    ctx.simTime);
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead()) {
                return false;
            }
            return session.retreatPolicy.isActive();
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            IsoNPC npc = ctx.npc;
            if (npc == null) return;
            session.activeSubState = "RETREAT";
            ctx.combatActivityState = "RETREATING";
            npc.setIsAiming(false);
            npc.clearVirtualAim();
            int npcZ = EmberTargetProjector.levelOf(npc.getZ());
            EmberSquadDirector dir = EmberSquadDirector.getInstance();
            boolean hasPersonalHorde = dir.findPersonalHordeThreat(
                    npc.getNpcId(),
                    npc.getX(),
                    npc.getY(),
                    npcZ,
                    HordeRetreatPolicy.TRIGGER_RADIUS_TILES,
                    session.retreatCentroidScratch);
            session.retreatPolicy.update(
                    npc.getX(),
                    npc.getY(),
                    npcZ,
                    hasPersonalHorde,
                    session.retreatCentroidScratch[0],
                    session.retreatCentroidScratch[1],
                    ctx.simTime,
                    B42CombatRuntime.WORLD_PROBE,
                    dir);
            CombatDiagLog.logEvent(
                    npc.getNpcId(),
                    "RETREAT_ENTER",
                    String.format(java.util.Locale.ROOT,
                            "centroid=(%.2f,%.2f) goal=(%.2f,%.2f)",
                            session.retreatCentroidScratch[0],
                            session.retreatCentroidScratch[1],
                            session.retreatPolicy.getTargetX(),
                            session.retreatPolicy.getTargetY()));
            scope.defer(() -> {
                if (ctx.npc != null) {
                    ctx.npc.getLocomotion().clearCombatMove(ctx.npc);
                }
            });
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead()) return;
            if (npc.getPosture().isBlockingLocomotion()) {
                ctx.requestStandUp(true);
                return;
            }
            int npcZ = EmberTargetProjector.levelOf(npc.getZ());
            EmberSquadDirector dir = EmberSquadDirector.getInstance();
            boolean hasPersonalHorde = dir.findPersonalHordeThreat(
                    npc.getNpcId(),
                    npc.getX(),
                    npc.getY(),
                    npcZ,
                    HordeRetreatPolicy.TRIGGER_RADIUS_TILES,
                    session.retreatCentroidScratch);
            boolean active = session.retreatPolicy.update(
                    npc.getX(),
                    npc.getY(),
                    npcZ,
                    hasPersonalHorde,
                    session.retreatCentroidScratch[0],
                    session.retreatCentroidScratch[1],
                    ctx.simTime,
                    B42CombatRuntime.WORLD_PROBE,
                    dir);
            if (!active) {
                npc.getLocomotion().clearCombatMove(npc);
                return;
            }
            ctx.combatActivityState = "RETREATING";
            npc.setIsAiming(false);
            npc.clearVirtualAim();
            float dx = session.retreatPolicy.getTargetX() - npc.getX();
            float dy = session.retreatPolicy.getTargetY() - npc.getY();
            float len = (float) Math.hypot(dx, dy);
            if (len > 0.1F) {
                npc.getLocomotion().setCombatAimStrafe(
                        npc, dx / len, dy / len, true, false, false, 0.0F, 0.0F, true, -1.0F);
            } else {
                npc.getLocomotion().clearCombatMove(npc);
            }
        }
    }

    /** ③ 战斗子叶 2：背包掏取 -> 换装 / 装弹（严格经 {@link SelfInventoryTransferAction} 与统一 {@link BaseAction} 执行）。 */
    private static final class CombatEquipOrReloadLeaf extends StateNode {
        private final CombatSessionState session;
        private BaseAction ownedAction;
        private HandWeapon pendingEquipWeapon;
        private HandWeapon pendingReloadGun;
        private ReloadKind pendingReloadKind = ReloadKind.NONE;
        private boolean equipMode;

        CombatEquipOrReloadLeaf(CombatSessionState session) {
            super("COMBAT_EQUIP_OR_RELOAD", null, null, 0.0, true);
            this.session = session;
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            return needsEquipOrReloadNow(ctx, false);
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            return needsEquipOrReloadNow(ctx, true);
        }

        private boolean needsEquipOrReloadNow(BrainContext ctx, boolean alreadyInNode) {
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead()) {
                return false;
            }
            if (!npc.getCharacterActions().isEmpty()) {
                return true;
            }
            if (alreadyInNode && ownedAction != null && ownedAction.isRunning()) {
                return true;
            }
            boolean preferFirearm = SquadTacticalConfig.isEffectiveCqb(ctx.npcId)
                    || NpcWeaponSelector.isUsableFirearm(NpcWeaponSelector.getEquippedWeapon(npc));
            DesiredWeaponPlan plan = NpcWeaponSelector.planDesiredCombatWeapon(npc, preferFirearm, true);
            if (plan.needsEquip()) {
                return true;
            }
            if (plan.mode() == WeaponMode.FIREARM && plan.weapon() != null) {
                HandWeapon gun = plan.weapon();
                ReloadKind rk = NpcWeaponSelector.resolveNeededReload(npc, gun);
                if (rk == ReloadKind.NONE) {
                    return false;
                }
                if (!NpcWeaponSelector.canShootNow(npc, gun)) {
                    return true;
                }
                if (gun.getCurrentAmmoCount() <= Math.max(1, gun.getMaxAmmo() / 4)
                        && findClosestThreatDist(npc, 3.2F) == null) {
                    EmberSquadDirector dir = EmberSquadDirector.getInstance();
                    long nowMs = Math.round(ctx.simTime * 1000.0);
                    return alreadyInNode
                            ? (dir.getReloadPool().hasToken(npc.getNpcId(), nowMs)
                                    || dir.getReloadPool().acquire(npc.getNpcId(), nowMs))
                            : (dir.getReloadPool().hasToken(npc.getNpcId(), nowMs)
                                    || dir.getReloadPool().acquire(npc.getNpcId(), nowMs));
                }
            }
            return false;
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            ownedAction = null;
            pendingEquipWeapon = null;
            pendingReloadGun = null;
            pendingReloadKind = ReloadKind.NONE;
            equipMode = false;
            session.activeSubState = "EQUIP_OR_RELOAD";

            scope.defer(() -> {
                if (ownedAction != null && ownedAction.isRunning()) {
                    ownedAction.interrupt(ctx, "EQUIP_RELOAD_EXIT");
                }
                ownedAction = null;
                pendingEquipWeapon = null;
                pendingReloadGun = null;
                pendingReloadKind = ReloadKind.NONE;
                if (ctx.npc != null) {
                    EmberSquadDirector.getInstance().getReloadPool().release(ctx.npc.getNpcId());
                    ctx.npc.getLocomotion().clearCombatMove(ctx.npc);
                }
            });
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead()) return;

            if (npc.getPosture().isBlockingLocomotion()) {
                ctx.requestStandUp(true);
                return;
            }

            if (ownedAction != null && ownedAction.isRunning()) {
                applySafeWalkBackDuringAction(npc, ownedAction.stopOnWalk, dtSim, ctx.simTime);
                ctx.combatActivityState = equipMode ? "EQUIPPING" : "RELOADING";
                return;
            }
            if (!npc.getCharacterActions().isEmpty()) {
                zombie.characters.CharacterTimedActions.BaseAction vanillaAct = npc.getCharacterActions().get(0);
                applySafeWalkBackDuringAction(npc, vanillaAct.stopOnWalk, dtSim, ctx.simTime);
                ctx.combatActivityState = "RELOADING";
                return;
            }

            // 前置背包掏取完毕 -> 立即续接真正拔枪或装弹动作
            if (ownedAction != null && ownedAction.getStatus() == BaseAction.ActionStatus.COMPLETED) {
                ownedAction = null;
                if (pendingEquipWeapon != null && npc.getInventory() != null
                        && npc.getInventory().contains(pendingEquipWeapon)) {
                    HandWeapon w = pendingEquipWeapon;
                    pendingEquipWeapon = null;
                    startAction(ctx, new NpcEquipWeaponAction(npc, w), true);
                    return;
                }
                if (pendingReloadGun != null && pendingReloadKind != ReloadKind.NONE) {
                    HandWeapon g = pendingReloadGun;
                    ReloadKind rk = pendingReloadKind;
                    pendingReloadGun = null;
                    pendingReloadKind = ReloadKind.NONE;
                    long nowMs = Math.round(ctx.simTime * 1000.0);
                    EmberSquadDirector.getInstance().getReloadPool().acquire(npc.getNpcId(), nowMs);
                    startAction(ctx, new NpcReloadAction(npc, g, rk), false);
                    return;
                }
            } else {
                ownedAction = null;
                pendingEquipWeapon = null;
                pendingReloadGun = null;
                pendingReloadKind = ReloadKind.NONE;
            }

            boolean preferFirearm = SquadTacticalConfig.isEffectiveCqb(ctx.npcId)
                    || NpcWeaponSelector.isUsableFirearm(NpcWeaponSelector.getEquippedWeapon(npc));
            DesiredWeaponPlan plan = NpcWeaponSelector.planDesiredCombatWeapon(npc, preferFirearm, true);
            if (plan.needsEquip() && plan.weapon() != null) {
                if (plan.needsUnpackFromBag() && plan.location() != null && npc.getInventory() != null) {
                    pendingEquipWeapon = plan.weapon();
                    SelfInventoryTransferAction unpack = new SelfInventoryTransferAction(npc, List.of(
                            new SelfInventoryTransferAction.TransferEntry(
                                    plan.location().item(), plan.location().container(), npc.getInventory())));
                    startAction(ctx, unpack, true);
                } else {
                    startAction(ctx, new NpcEquipWeaponAction(npc, plan.weapon()), true);
                }
                return;
            }

            if (plan.mode() == WeaponMode.FIREARM && plan.weapon() != null) {
                HandWeapon gun = plan.weapon();
                ReloadKind rk = NpcWeaponSelector.resolveNeededReload(npc, gun);
                if (rk != ReloadKind.NONE) {
                    long nowMs = Math.round(ctx.simTime * 1000.0);
                    if (NpcWeaponSelector.canShootNow(npc, gun)
                            && !EmberSquadDirector.getInstance().getReloadPool().acquire(npc.getNpcId(), nowMs)) {
                        return;
                    }
                    EmberSquadDirector.getInstance().getReloadPool().acquire(npc.getNpcId(), nowMs);
                    List<SelfInventoryTransferAction.TransferEntry> prep =
                            NpcWeaponSelector.findReloadPrepTransfers(npc, gun, rk);
                    if (!prep.isEmpty()) {
                        pendingReloadGun = gun;
                        pendingReloadKind = rk;
                        startAction(ctx, new SelfInventoryTransferAction(npc, prep), false);
                    } else {
                        startAction(ctx, new NpcReloadAction(npc, gun, rk), false);
                    }
                }
            }
        }

        private void startAction(BrainContext ctx, BaseAction action, boolean equipping) {
            IsoNPC npc = ctx.npc;
            if (npc == null || action == null) return;
            npc.setIsAiming(false);
            npc.clearVirtualAim();
            npc.getLocomotion().clearCombatMove(npc);
            this.equipMode = equipping;
            this.ownedAction = action;
            ctx.combatActivityState = equipping ? "EQUIPPING" : "RELOADING";
            action.start(ctx);
        }

        private void applySafeWalkBackDuringAction(
                IsoNPC npc, boolean actionStopsOnWalk, double dtSim, double nowSim) {
            npc.setIsAiming(false);
            npc.clearVirtualAim();
            if (actionStopsOnWalk) {
                npc.getLocomotion().clearCombatMove(npc);
                return;
            }
            EmberSquadDirector.ThreatEntry nearest = findClosestThreatDist(npc, 3.5F);
            if (nearest == null) {
                npc.getLocomotion().clearCombatMove(npc);
                return;
            }
            int zLvl = EmberTargetProjector.levelOf(npc.getZ());
            int allyCount = collectAllies(npc, zLvl, session.allyXs, session.allyYs, session.allyIds);
            float dist = (float) Math.hypot(nearest.x - npc.getX(), nearest.y - npc.getY());
            CombatLocomotionSolver.computeRangedRetreatStep(
                    session.moveState,
                    EmberSquadDirector.getInstance(),
                    npc.getNpcId(),
                    npc.getX(),
                    npc.getY(),
                    zLvl,
                    nearest.x,
                    nearest.y,
                    dist,
                    session.allyXs,
                    session.allyYs,
                    allyCount,
                    false,
                    0.0F,
                    0.0F,
                    0.0F,
                    B42CombatRuntime.WORLD_PROBE,
                    nowSim,
                    (float) dtSim,
                    session.moveCmd);
            if (session.moveCmd.wantMove) {
                npc.getLocomotion().setCombatAimStrafe(
                        npc,
                        session.moveCmd.dirX,
                        session.moveCmd.dirY,
                        true,
                        false,
                        false,
                        0.0F,
                        0.0F,
                        false,
                        0.72F);
            } else {
                npc.getLocomotion().clearCombatMove(npc);
            }
        }
    }

    /** ③ 战斗子叶 3：远程射击、2.55/3.45m 滞回走位、失视线侧切与 CQB 室内切角戒备。 */
    private static final class CombatRangedLeaf extends StateNode {
        private final CombatSessionState session;
        private final boolean allowSquadCqbSweep;

        CombatRangedLeaf(CombatSessionState session, boolean allowSquadCqbSweep) {
            super("COMBAT_RANGED", null, null, 0.0, false);
            this.session = session;
            this.allowSquadCqbSweep = allowSquadCqbSweep;
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            return isRangedEligible(ctx);
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            return isRangedEligible(ctx);
        }

        private boolean isRangedEligible(BrainContext ctx) {
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead()) return false;
            HandWeapon weapon = NpcWeaponSelector.getEquippedWeapon(npc);
            if (!NpcWeaponSelector.isUsableFirearm(weapon) || !NpcWeaponSelector.canShootNow(npc, weapon)) {
                return false;
            }
            EmberSquadDirector dir = EmberSquadDirector.getInstance();
            long nowMs = Math.round(ctx.simTime * 1000.0);
            int npcZ = EmberTargetProjector.levelOf(npc.getZ());
            if (pickBestRangedTarget(npc, npcZ, weapon, dir, nowMs, session.currentRangedTargetId) != null) {
                return true;
            }
            return allowSquadCqbSweep
                    && SquadTacticalConfig.isEffectiveCqb(ctx.npcId)
                    && !npc.isOutside();
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            session.activeSubState = "RANGED";
            session.moveState.rangedRetreatActive = false;
            scope.defer(() -> {
                session.currentRangedTargetId = -1;
                session.moveState.rangedRetreatActive = false;
                if (ctx.npc != null) {
                    EmberSquadDirector.getInstance().unregisterCorridor(ctx.npc.getNpcId());
                    EmberSquadDirector.getInstance().releaseRangedClaim(ctx.npc.getNpcId());
                    ctx.npc.clearVirtualAim();
                    NpcNullInputComponent in = ctx.npc.getNullInputComponent();
                    if (in != null) {
                        in.clearAutoFireHold();
                    }
                    ctx.npc.getLocomotion().clearCombatMove(ctx.npc);
                }
            });
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead()) return;
            if (npc.getPosture().isBlockingLocomotion()) {
                ctx.requestStandUp(true);
                return;
            }

            HandWeapon gun = NpcWeaponSelector.getEquippedWeapon(npc);
            if (!NpcWeaponSelector.isUsableFirearm(gun) || !NpcWeaponSelector.canShootNow(npc, gun)) {
                return;
            }

            int npcZ = EmberTargetProjector.levelOf(npc.getZ());
            EmberSquadDirector director = EmberSquadDirector.getInstance();
            long nowMs = Math.round(ctx.simTime * 1000.0);
            IsoPlayer leader = resolveLeader(ctx.followPlayerIndex);
            List<IsoNPC> liveAllies = NpcManager.getLiveNpcsSnapshot();

            EmberSquadDirector.ThreatEntry target =
                    pickBestRangedTarget(npc, npcZ, gun, director, nowMs, session.currentRangedTargetId);

            if (target == null) {
                director.unregisterCorridor(npc.getNpcId());
                director.releaseRangedClaim(npc.getNpcId());
                session.currentRangedTargetId = -1;
                if (allowSquadCqbSweep && SquadTacticalConfig.isEffectiveCqb(ctx.npcId) && !npc.isOutside()) {
                    ctx.combatActivityState = "CQB_SWEEP";
                    if (nowMs - session.lastCqbScanMs >= 750L) {
                        session.lastCqbScanMs = nowMs;
                        B42CombatRuntime.scanDoorWindowSectors(
                                session.cqb, npc.getX(), npc.getY(), npcZ, npc.getDirectionAngleRadians());
                        int squadCount = liveAllies != null ? Math.max(1, liveAllies.size()) : 1;
                        session.cqb.applyMemberPhaseOffset(
                                ctx.followSlot >= 0 ? ctx.followSlot : 0, squadCount);
                    }
                    session.cqb.stepSectorSweep(
                            npc.getVirtualAim(),
                            npc.getDirectionAngleRadians(),
                            npc.getX(),
                            npc.getY(),
                            npc.getZ(),
                            gun.getMaxRange(npc),
                            dtSim);
                    npc.getLocomotion().setCombatAimStrafe(
                            npc,
                            0.0F,
                            0.0F,
                            false,
                            true,
                            true,
                            npc.getVirtualAim().getAimDirX(),
                            npc.getVirtualAim().getAimDirY(),
                            false,
                            -1.0F);
                }
                return;
            }

            IsoZombie liveZ = B42CombatRuntime.getLiveZombie(target);
            if (liveZ == null || !B42CombatRuntime.isShootableTarget(liveZ)) {
                B42CombatRuntime.markThreatDead(target);
                session.currentRangedTargetId = -1;
                return;
            }

            ctx.combatActivityState = "COMBAT";
            session.currentRangedTargetId = target.zombieId;
            director.claimRangedTarget(npc.getNpcId(), target.zombieId, nowMs);

            float maxRange = Math.max(4.0F, gun.getMaxRange(npc));
            float dx = target.x - npc.getX();
            float dy = target.y - npc.getY();
            float dist = (float) Math.hypot(dx, dy);

            npc.getVirtualAim().aimAtTarget(
                    npc.getDirectionAngleRadians(),
                    npc.getX(),
                    npc.getY(),
                    npc.getZ(),
                    target.x,
                    target.y,
                    target.z,
                    target.velX,
                    target.velY,
                    CombatTargetState.isProneOrCrawl(target.stateMask),
                    maxRange,
                    (float) Math.max(0.016, dtSim));
            npc.setTargetVerticalAimAngle(npc.getVirtualAim().getPitchDegrees());

            float aimDirX = npc.getVirtualAim().getAimDirX();
            float aimDirY = npc.getVirtualAim().getAimDirY();
            director.registerCorridor(
                    npc.getNpcId(),
                    npc.getX(),
                    npc.getY(),
                    npc.getX() + aimDirX * Math.min(maxRange, dist + 1.5F),
                    npc.getY() + aimDirY * Math.min(maxRange, dist + 1.5F),
                    npcZ,
                    nowMs);

            boolean holdActive = !ctx.guardDuty && SquadTacticalConfig.hasHoldAnchor();
            float holdX = ctx.guardDuty ? ctx.guardAnchorX : SquadTacticalConfig.getHoldX();
            float holdY = ctx.guardDuty ? ctx.guardAnchorY : SquadTacticalConfig.getHoldY();
            float holdRadius = ctx.guardDuty ? GuardCombatNode.GUARD_HARD_LEASH : SQUAD_HOLD_COMBAT_RADIUS;

            boolean hasLos = B42CombatRuntime.RANGED_LOS_PROBE.isLosClear(npc.getX(), npc.getY(), target.x, target.y, npcZ);
            boolean coneBlocked = B42CombatRuntime.isLineBlockedByAllyInWorld(
                    npc, aimDirX, aimDirY, dist, leader, liveAllies);
            boolean narrowHold = B42CombatRuntime.shouldShooterHoldInNarrowCorridor(
                    npc.getX(), npc.getY(), aimDirX, aimDirY, dist, liveAllies);

            EmberSquadDirector.ThreatEntry closestThreat = findClosestThreatDist(npc, 3.6F);
            float closestDist = closestThreat != null
                    ? (float) Math.hypot(closestThreat.x - npc.getX(), closestThreat.y - npc.getY())
                    : Float.MAX_VALUE;

            boolean needRetreatHysteresis = session.moveState.rangedRetreatActive
                    || closestDist < CombatLocomotionSolver.RANGED_RETREAT_ENTER_DIST;

            boolean retreatBoxedIn = false;
            if (closestThreat != null && needRetreatHysteresis) {
                int allyCount = collectAllies(npc, npcZ, session.allyXs, session.allyYs, session.allyIds);
                CombatLocomotionSolver.computeRangedRetreatStep(
                        session.moveState,
                        director,
                        npc.getNpcId(),
                        npc.getX(),
                        npc.getY(),
                        npcZ,
                        closestThreat.x,
                        closestThreat.y,
                        closestDist,
                        session.allyXs,
                        session.allyYs,
                        allyCount,
                        holdActive,
                        holdX,
                        holdY,
                        holdRadius,
                        B42CombatRuntime.WORLD_PROBE,
                        ctx.simTime,
                        (float) dtSim,
                        session.moveCmd);
                npc.getLocomotion().setCombatAimStrafe(
                        npc,
                        session.moveCmd.dirX,
                        session.moveCmd.dirY,
                        session.moveCmd.wantMove,
                        true,
                        true,
                        aimDirX,
                        aimDirY,
                        false,
                        session.moveCmd.walkSpeedCap);
                retreatBoxedIn = !session.moveCmd.wantMove && closestDist < 1.35F;
            } else {
                session.moveState.rangedRetreatActive = false;
                RangedTacticsController.computeRangedReposition(
                        npc.getX(),
                        npc.getY(),
                        npcZ,
                        target.x,
                        target.y,
                        maxRange,
                        CombatLocomotionSolver.RANGED_RETREAT_ENTER_DIST,
                        hasLos,
                        coneBlocked,
                        holdActive,
                        holdX,
                        holdY,
                        holdRadius,
                        director,
                        B42CombatRuntime.WORLD_PROBE,
                        B42CombatRuntime.RANGED_LOS_PROBE,
                        session.rangedCmd);
                retreatBoxedIn = session.rangedCmd.retreatBlocked && closestDist < 1.35F;
                if (session.rangedCmd.shouldMove && !session.rangedCmd.directVector) {
                    npc.getLocomotion().clearCombatMove(npc);
                    ctx.requestMoveTo(
                            session.rangedCmd.goalX, session.rangedCmd.goalY, npcZ, session.rangedCmd.run);
                } else {
                    npc.getLocomotion().setCombatAimStrafe(
                            npc,
                            session.rangedCmd.dirX,
                            session.rangedCmd.dirY,
                            session.rangedCmd.shouldMove,
                            true,
                            true,
                            aimDirX,
                            aimDirY,
                            false,
                            -1.0F);
                }
            }

            if (retreatBoxedIn
                    && closestThreat != null
                    && npc.getMeleeDelay() <= 0.5F
                    && npc.CanAttack()
                    && !npc.isCombatAttackBlocked(ctx.simTime)) {
                MeleeAttackExecutor.commitDefensiveShove(npc, closestThreat, ctx.simTime);
                return;
            }

            if (!hasLos || coneBlocked || narrowHold) {
                return;
            }

            BallisticsController bc = npc.getBallisticsController();
            boolean ballisticsOk = bc == null
                    || dist <= 1.8F
                    || bc.isValidTarget(liveZ.getID())
                    || bc.isValidCachedTarget(liveZ.getID());
            if (!ballisticsOk) {
                return;
            }

            boolean shotgun = gun.isRangeFalloff() || gun.getProjectileCount() > 1;
            boolean ready = AiAimingReadiness.isAimReady(
                    npc.getAimingDelay(),
                    npc.getRecoilDelay(),
                    npc.getVirtualAim().getErrorDegrees(),
                    dist,
                    maxRange,
                    shotgun,
                    npc.getBeenMovingFor());

            if (ready && npc.CanAttack() && !npc.isCombatAttackBlocked(ctx.simTime)) {
                NpcNullInputComponent in = npc.getNullInputComponent();
                if (in != null) {
                    if ("Auto".equals(gun.getFireMode())) {
                        in.armAutoFireHold(ctx.simTime, 0.25);
                    } else {
                        in.clearAutoFireHold();
                    }
                }
                npc.setDoShove(false);
                B42CombatRuntime.assignAttackTargetSquare(npc, target);
                npc.AttemptAttack(0.0F);
            }
        }
    }

    /** ③ 战斗子叶 4：近战单轨极坐标走位 + {@code SafeSideGuard} 倒地安全侧 + 原版命中框裁决。 */
    private static final class CombatMeleeLeaf extends StateNode {
        private final CombatSessionState session;

        CombatMeleeLeaf(CombatSessionState session) {
            super("COMBAT_MELEE", null, null, 0.0, false);
            this.session = session;
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            return ctx.npc != null && !ctx.npc.isDead() && findClosestThreatDist(ctx.npc, 14.5F) != null;
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            return ctx.npc != null && !ctx.npc.isDead() && findClosestThreatDist(ctx.npc, 15.0F) != null;
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            session.activeSubState = "MELEE";
            scope.defer(() -> {
                session.currentMeleeTargetId = -1;
                session.targetCommitUntilSim = 0.0;
                if (ctx.npc != null) {
                    EmberSquadDirector director = EmberSquadDirector.getInstance();
                    for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
                        EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
                        if (t.active) {
                            t.ringTable.release(ctx.npc.getNpcId());
                        }
                    }
                    ctx.npc.getLocomotion().clearCombatMove(ctx.npc);
                    ctx.npc.clearVirtualAim();
                }
            });
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead()) return;
            if (npc.getPosture().isBlockingLocomotion()) {
                ctx.requestStandUp(true);
                return;
            }

            int npcZ = EmberTargetProjector.levelOf(npc.getZ());
            EmberSquadDirector director = EmberSquadDirector.getInstance();
            long nowMs = Math.round(ctx.simTime * 1000.0);
            HandWeapon weapon = NpcWeaponSelector.getEquippedWeapon(npc);
            MeleeWeaponMetrics metrics = B42CombatRuntime.extractWeaponMetrics(npc, weapon);

            CombatLocomotionSolver.evaluateProneSafeSide(
                    director, npc.getX(), npc.getY(), npcZ, session.safeGuard);

            int allyCount = collectAllies(npc, npcZ, session.allyXs, session.allyYs, session.allyIds);
            boolean holdActive = !ctx.guardDuty && SquadTacticalConfig.hasHoldAnchor();
            float holdX = ctx.guardDuty ? ctx.guardAnchorX : SquadTacticalConfig.getHoldX();
            float holdY = ctx.guardDuty ? ctx.guardAnchorY : SquadTacticalConfig.getHoldY();
            float holdRadius = ctx.guardDuty ? GuardCombatNode.GUARD_HARD_LEASH : SQUAD_HOLD_COMBAT_RADIUS;

            boolean targetCommitActive = ctx.simTime < session.targetCommitUntilSim;
            EmberSquadDirector.ThreatEntry target = MeleeTargetSelector.select(
                    director,
                    npc.getNpcId(),
                    npc.getX(),
                    npc.getY(),
                    npcZ,
                    metrics.reliableRange(),
                    metrics.groundHeadStandRadius(),
                    14.5F,
                    session.currentMeleeTargetId,
                    nowMs,
                    session.allyXs,
                    session.allyYs,
                    session.allyIds,
                    allyCount,
                    0,
                    holdActive,
                    holdX,
                    holdY,
                    holdRadius,
                    session.safeGuard,
                    B42CombatRuntime.WORLD_PROBE,
                    B42CombatRuntime.MELEE_BARRIER_PROBE,
                    targetCommitActive,
                    session.selectedMelee);

            if (target == null) {
                npc.clearVirtualAim();
                npc.getLocomotion().clearCombatMove(npc);
                return;
            }

            IsoZombie liveZ = B42CombatRuntime.getLiveZombie(target);
            if (liveZ == null) {
                B42CombatRuntime.markThreatDead(target);
                session.currentMeleeTargetId = -1;
                return;
            }
            int liveMask = B42CombatRuntime.classifyTarget(liveZ);
            if (liveMask == 0) {
                B42CombatRuntime.markThreatDead(target);
                session.currentMeleeTargetId = -1;
                return;
            }

            if (target.zombieId != session.currentMeleeTargetId) {
                session.currentMeleeTargetId = target.zombieId;
                session.targetCommitUntilSim = ctx.simTime + TARGET_COMMIT_SIM;
            }
            ctx.combatActivityState = "COMBAT";

            boolean groundAttackable = CombatTargetState.isGroundAttackable(liveMask);
            boolean targetProne = CombatTargetState.isProneOrCrawl(liveMask);
            boolean targetLunging = CombatTargetState.isLunging(liveMask);
            float aimX = (groundAttackable && target.hasHeadPos) ? target.headX : liveZ.getX();
            float aimY = (groundAttackable && target.hasHeadPos) ? target.headY : liveZ.getY();

            npc.getVirtualAim().aimAtTarget(
                    npc.getDirectionAngleRadians(),
                    npc.getX(),
                    npc.getY(),
                    npc.getZ(),
                    aimX,
                    aimY,
                    liveZ.getZ(),
                    target.velX,
                    target.velY,
                    targetProne,
                    Math.max(2.5F, metrics.maxRange() + 1.0F),
                    (float) Math.max(0.016, dtSim));

            float aimDirX = npc.getVirtualAim().getAimDirX();
            float aimDirY = npc.getVirtualAim().getAimDirY();
            boolean performingSwing = npc.isPerformingHostileSwing();
            boolean attackOnCooldown = npc.getMeleeDelay() > 0.5F || npc.isAttackStarted();
            int nearbyEnemies = countNearbyStandingEnemies(director, npc.getX(), npc.getY(), npcZ, 3.2F);
            int frontStandingCount = countFrontStandingEnemies(
                    director, npc.getX(), npc.getY(), npcZ, aimDirX, aimDirY,
                    MeleeWeaponMetrics.ARMED_MULTI_SHOVE_MAX_DIST + 0.35F);

            float idealMin = metrics.idealRingMin(targetLunging);
            float idealMax = metrics.idealRingMax(targetLunging);
            float coolMin = metrics.cooldownRingMin(nearbyEnemies);
            float coolMax = metrics.cooldownRingMax(nearbyEnemies);
            float boneDist = B42CombatRuntime.getNearestBoneDistance(npc, liveZ);

            float toSelfX = npc.getX() - aimX;
            float toSelfY = npc.getY() - aimY;
            float toSelfLen = (float) Math.hypot(toSelfX, toSelfY);
            float closingSpeed = toSelfLen > 0.01F
                    ? Math.max(0.0F, (target.velX * (toSelfX / toSelfLen) + target.velY * (toSelfY / toSelfLen)))
                    : 0.0F;
            boolean attackingMe = liveZ.getTarget() == npc || target.attackedAllyPriority == 1;
            float stanceDist = CombatLocomotionSolver.computeDynamicStanceDist(
                    metrics.reliableRange(),
                    nearbyEnemies,
                    attackingMe,
                    CombatTargetState.isStaggered(liveMask) || CombatTargetState.isPostureTransition(liveMask),
                    groundAttackable,
                    CombatTargetState.isSprinter(liveMask),
                    closingSpeed,
                    false);

            float moveNeedDist = Math.max(0.0F, boneDist - idealMax);
            float closeMoveMs = (moveNeedDist / 2.2F) * 1000.0F;
            float secondaryEtaMs = director.estimateNearestSecondaryArrivalMs(
                    npc.getX(), npc.getY(), npcZ, target.zombieId);
            float engineStaggerMs = B42CombatRuntime.readZombieStaggerRemainingMs(liveZ);
            StaggerPressure.Decision staggerDecision = StaggerPressure.evaluateWithEngineTimer(
                    engineStaggerMs, target.staggerStartMs, nowMs, closeMoveMs, secondaryEtaMs, liveMask);
            boolean staggerPress = (staggerDecision == StaggerPressure.Decision.COMMIT_PRESS)
                    && CombatTargetState.isStaggered(liveMask);

            CombatLocomotionSolver.computeMeleeStep(
                    session.moveState,
                    director,
                    npc.getNpcId(),
                    npc.getX(),
                    npc.getY(),
                    npcZ,
                    target.zombieId,
                    aimX,
                    aimY,
                    liveMask,
                    session.selectedMelee.standX,
                    session.selectedMelee.standY,
                    idealMin,
                    idealMax,
                    coolMin,
                    coolMax,
                    attackOnCooldown && !staggerPress,
                    performingSwing,
                    stanceDist,
                    session.allyXs,
                    session.allyYs,
                    allyCount,
                    holdActive,
                    holdX,
                    holdY,
                    holdRadius,
                    session.safeGuard,
                    B42CombatRuntime.WORLD_PROBE,
                    ctx.simTime,
                    (float) dtSim,
                    session.moveCmd);

            npc.getLocomotion().setCombatAimStrafe(
                    npc,
                    session.moveCmd.dirX,
                    session.moveCmd.dirY,
                    session.moveCmd.wantMove,
                    session.moveCmd.aimStrafe,
                    true,
                    aimDirX,
                    aimDirY,
                    session.moveCmd.runGear,
                    session.moveCmd.walkSpeedCap);

            CombatDiagLog.updateNpcStatus(
                    npc.getNpcId(),
                    "COMBAT_MELEE",
                    ctx.combatActivityState,
                    target.zombieId,
                    liveMask,
                    boneDist,
                    idealMin,
                    idealMax,
                    npc.isAimAtFloor(),
                    npc.isDoStomp(),
                    session.moveCmd.radialMode.name(),
                    nowMs);

            int closeStandingCount = countNearbyStandingEnemies(director, npc.getX(), npc.getY(), npcZ, 1.60F);
            boolean immediateBiteThreat = !groundAttackable
                    && CombatTargetState.allowsStompOrShove(liveMask)
                    && boneDist <= 1.05F
                    && (liveZ.getTarget() == npc || targetLunging);
            if (!performingSwing && !attackOnCooldown && !npc.isCombatAttackBlocked(ctx.simTime)
                    && npc.CanAttack() && (ctx.simTime - session.lastShoveSim) >= 0.50
                    && !groundAttackable && CombatTargetState.allowsStompOrShove(liveMask)
                    && (immediateBiteThreat || (closeStandingCount >= 2 && boneDist <= 1.55F))) {
                session.lastShoveSim = ctx.simTime;
                MeleeAttackExecutor.commitDefensiveShove(npc, target, ctx.simTime);
                return;
            }

            boolean hasMeleeSlot = session.selectedMelee.ringSlot >= 0 || boneDist < 1.35F;
            if (!hasMeleeSlot || performingSwing || attackOnCooldown
                    || !npc.CanAttack() || npc.isCombatAttackBlocked(ctx.simTime)) {
                return;
            }

            float maxSwingDist = groundAttackable
                    ? metrics.groundHeadSwingReach()
                    : metrics.effectiveMaxRange(targetLunging);
            if (boneDist > maxSwingDist) {
                return;
            }
            if (!B42CombatRuntime.hasVanillaMeleeHitTarget(npc, weapon, liveZ, metrics.unarmed())) {
                return;
            }

            MeleeAttackExecutor.commit(
                    npc, weapon, metrics, target, liveZ, liveMask,
                    boneDist, frontStandingCount, ctx.simTime, nowMs);
        }
    }

    private static EmberSquadDirector.ThreatEntry pickBestRangedTarget(
            IsoNPC npc,
            int npcZ,
            HandWeapon gun,
            EmberSquadDirector director,
            long nowMs,
            int currentTargetId) {
        float maxRange = Math.max(4.0F, Math.min(20.0F, gun.getMaxRange(npc) + 1.5F));
        float bestScore = Float.MAX_VALUE;
        EmberSquadDirector.ThreatEntry best = null;
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || !t.perceivedBySquad || Math.abs(t.z - npcZ) > 0.6F) continue;
            if (director.isUnreachable(npc.getNpcId(), t.zombieId, true, nowMs)) continue;
            IsoZombie liveZ = B42CombatRuntime.getLiveZombie(t);
            if (liveZ == null || !B42CombatRuntime.isShootableTarget(liveZ)) continue;

            float dx = t.x - npc.getX();
            float dy = t.y - npc.getY();
            float d = (float) Math.hypot(dx, dy);
            if (d > maxRange) continue;
            if (!B42CombatRuntime.RANGED_LOS_PROBE.isLosClear(npc.getX(), npc.getY(), t.x, t.y, npcZ)) {
                continue;
            }

            float score = d;
            if (t.attackedAllyPriority >= 2) {
                score *= 0.60F;
            } else if (t.attackedAllyPriority == 1 && nowMs < t.distressPriorityUntilMs) {
                score *= 0.78F;
            }
            if (CombatTargetState.isSprinter(t.stateMask)) {
                score -= 2.5F;
            }
            if (t.zombieId == currentTargetId) {
                score -= 1.2F;
            }
            int otherClaims = director.countOtherRangedClaims(npc.getNpcId(), t.zombieId, nowMs);
            if (otherClaims > 0 && d > 3.5F) {
                score += otherClaims * 3.5F;
            }
            if (score < bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    private static int collectAllies(
            IsoNPC self, int zLvl, float[] outXs, float[] outYs, String[] outIds) {
        List<IsoNPC> live = NpcManager.getLiveNpcsSnapshot();
        int count = 0;
        if (live != null) {
            for (int i = 0, n = live.size(); i < n && count < outXs.length; i++) {
                IsoNPC other = live.get(i);
                if (other == null || other == self || other.isDead()) continue;
                if (EmberTargetProjector.levelOf(other.getZ()) != zLvl) continue;
                outXs[count] = other.getX();
                outYs[count] = other.getY();
                if (outIds != null) {
                    outIds[count] = other.getNpcId();
                }
                count++;
            }
        }
        return count;
    }

    private static int countNearbyStandingEnemies(
            EmberSquadDirector director, float x, float y, int zLvl, float radius) {
        int c = 0;
        float rSq = radius * radius;
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || !CombatTargetState.isStanding(t.stateMask) || Math.abs(t.z - zLvl) > 0.6F) {
                continue;
            }
            float dx = t.x - x;
            float dy = t.y - y;
            if (dx * dx + dy * dy <= rSq) {
                c++;
            }
        }
        return c;
    }

    private static int countFrontStandingEnemies(
            EmberSquadDirector director,
            float selfX,
            float selfY,
            int zLvl,
            float fwdX,
            float fwdY,
            float maxDist) {
        int c = 0;
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || !CombatTargetState.isStanding(t.stateMask) || Math.abs(t.z - zLvl) > 0.6F) {
                continue;
            }
            float dx = t.x - selfX;
            float dy = t.y - selfY;
            float d = (float) Math.hypot(dx, dy);
            if (d > maxDist || d < 1.0E-4F) continue;
            if ((dx / d) * fwdX + (dy / d) * fwdY >= 0.35F) {
                c++;
            }
        }
        return c;
    }

    private static IsoPlayer resolveLeader(int idx) {
        if (IsoPlayer.players == null || idx < 0 || idx >= IsoPlayer.players.length) {
            return null;
        }
        return IsoPlayer.players[idx];
    }
}
