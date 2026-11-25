package emberroutes.npc.combat;

import emberroutes.npc.EmberTargetProjector;
import emberroutes.npc.IsoNPC;
import emberroutes.npc.NpcManager;
import emberroutes.npc.NpcNullInputComponent;
import emberroutes.npc.RulesOfEngagement;
import emberroutes.npc.SquadTacticalConfig;
import emberroutes.npc.brain.BandageSelfAction;
import emberroutes.npc.brain.BaseAction;
import emberroutes.npc.brain.BrainContext;
import emberroutes.npc.brain.DrinkWaterAction;
import emberroutes.npc.brain.EatFoodAction;
import emberroutes.npc.brain.NeedsPolicy.UrgentNeed;
import emberroutes.npc.brain.SelfInventoryTransferAction;
import emberroutes.npc.brain.StateNode;
import emberroutes.npc.brain.StateScope;
import emberroutes.npc.combat.NpcWeaponSelector.ReloadKind;
import emberroutes.npc.combat.NpcWeaponSelector.WeaponMode;
import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.iso.Vector2;

/**
 * {@code BrainMode.SQUAD} 战斗与战间随身自理状态节点集，以及与据点守卫共用的单兵战斗执行核。
 */
public final class SquadCombatNodes {
    private static final float SELF_DEFENSE_ENTER_DIST = 1.35F;
    private static final float SELF_DEFENSE_EXIT_DIST = 1.85F;
    private static final float FIELD_AID_SAFE_RADIUS = 4.5F;

    private SquadCombatNodes() {}

    public static StateNode createEmergencySelfDefenseNode() {
        return new EmergencySelfDefenseNode();
    }

    public static StateNode createCombatEngageNode() {
        return new CombatEngageNode();
    }

    public static StateNode createSquadFieldFirstAidNode() {
        return new SquadFieldFirstAidNode();
    }

    /** 共有状态上下文（由单兵战斗节点通过 {@link StateScope} 管理并释放）。 */
    public static final class CombatSessionState {
        public final HordeRetreatPolicy retreatPolicy = new HordeRetreatPolicy();
        public final CqbController cqb = new CqbController();
        public final MeleeSlotAssigner.SelectedEngagement selectedMelee =
                new MeleeSlotAssigner.SelectedEngagement();
        public final SpacingController.MoveCommand moveCmd = new SpacingController.MoveCommand();
        public final Vector2 tmpAim = new Vector2();
        public final float[] wallScratch = new float[2];
        public int currentMeleeTargetId = -1;
        public float emergencyUntilSim;
        public long lastCqbScanMs;

        public void reset() {
            retreatPolicy.reset();
            cqb.reset();
            currentMeleeTargetId = -1;
            emergencyUntilSim = 0.0F;
            lastCqbScanMs = 0L;
        }
    }

    /** ① 最高优贴身自卫（{@code < 1.35} 格无视 {@code HOLD_FIRE} 停火令强行推开/挥击）。 */
    private static final class EmergencySelfDefenseNode extends StateNode {
        private final Vector2 tmpAim = new Vector2();
        private final float[] wallScratch = new float[2];
        private double enteredAtSim;

        EmergencySelfDefenseNode() {
            super("EMERGENCY_SELF_DEFENSE", null, null, 0.0, true);
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            if (ctx.npc == null || ctx.npc.isDead() || ctx.npc.getVehicle() != null) {
                return false;
            }
            return findClosestThreatDist(ctx.npc, SELF_DEFENSE_ENTER_DIST) != null;
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
            ctx.combatActivityState = "COMBAT";
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead()) return;

            ctx.requestStandUp(true);
            npc.getFollow().stop(npc, false, "EMERGENCY_SELF_DEF");
            if (!npc.getCharacterActions().isEmpty()) {
                npc.StopAllActionQueue();
            }
            scope.defer(npc.getGaze().suppress("COMBAT"));
            scope.defer(() -> {
                ctx.combatActivityState = "";
                if (ctx.npc != null) {
                    ctx.npc.clearVirtualAim();
                    ctx.npc.setIsAiming(false);
                    NpcNullInputComponent in = ctx.npc.getNullInputComponent();
                    if (in != null) {
                        in.clearCombatIntents();
                    }
                    ctx.npc.getLocomotion().clearCombatMove(ctx.npc);
                }
            });
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            IsoNPC npc = ctx.npc;
            if (npc == null || npc.isDead()) return;
            EmberSquadDirector.ThreatEntry threat = findClosestThreatDist(npc, SELF_DEFENSE_EXIT_DIST + 0.5F);
            if (threat == null) return;

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
                    2.5F,
                    (float) Math.max(0.016, dtSim));
            npc.setIsAiming(true);
            npc.setForwardDirection(tmpAim.set(npc.getVirtualAim().getAimDirX(), npc.getVirtualAim().getAimDirY()));
            npc.setDirectionAngle(npc.getVirtualAim().getAimYawDeg());

            float dx = threat.x - npc.getX();
            float dy = threat.y - npc.getY();
            float dist = (float) Math.hypot(dx, dy);
            float awayX = dist > 1.0E-4F ? -(dx / dist) : -npc.getForwardDirectionX();
            float awayY = dist > 1.0E-4F ? -(dy / dist) : -npc.getForwardDirectionY();
            SpacingController.applyWallAvoidTangentialSlide(
                    npc.getX(), npc.getY(), zLvl, awayX, awayY, B42CombatRuntime.WORLD_PROBE, wallScratch);
            if (dist < 1.15F) {
                npc.getLocomotion().setCombatMove(npc, wallScratch[0], wallScratch[1], false, true, 0.78F);
            } else {
                npc.getLocomotion().setCombatMove(npc, 0.0F, 0.0F, false, true, 0.0F);
            }

            if (npc.getMeleeDelay() <= 0.5F && npc.CanAttack()) {
                assignAttackTargetSquare(npc, threat);
                NpcNullInputComponent in = npc.getNullInputComponent();
                boolean transition = CombatTargetState.isPostureTransition(threat.stateMask);
                if (!transition && CombatTargetState.isGroundAttackable(threat.stateMask) && dist < 1.15F) {
                    if (in != null) {
                        in.setCombatStompIntent(true);
                        in.setCombatMeleeButtonIntent(true);
                    }
                    npc.setAimAtFloor(true);
                    npc.setDoShove(true);
                    npc.AttemptAttack(0.0F);
                } else if (!transition) {
                    if (in != null) {
                        in.clearCombatIntents();
                    }
                    npc.setAimAtFloor(false);
                    npc.setDoShove(true);
                    npc.AttemptAttack(0.0F);
                } else {
                    if (in != null) {
                        in.clearCombatIntents();
                    }
                    npc.setAimAtFloor(false);
                    npc.setDoShove(false);
                    npc.AttemptAttack(0.35F);
                }
            }
        }
    }

    /** ② 小队核心交战节点（受 ROE / CQB / 7 大微操内核 / 集中式导演联合控制）。 */
    private static final class CombatEngageNode extends StateNode {
        private final CombatSessionState session = new CombatSessionState();

        CombatEngageNode() {
            super("COMBAT_ENGAGE", null, null, 0.25, true);
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
            npc.getFollow().stop(npc, false, "COMBAT_ENGAGE");
            scope.defer(npc.getGaze().suppress("COMBAT"));
            scope.defer(() -> cleanupCombatScope(ctx, session));
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            boolean cqbEnabled = SquadTacticalConfig.isEffectiveCqb(ctx.npcId);
            executeTacticalCombatTick(ctx, session, cqbEnabled, true, dtSim);
        }
    }

    /** ③ 战间随身包扎/干粮/压弹（严格经原版背囊取放、绝不离开小队或找床找箱）。 */
    private static final class SquadFieldFirstAidNode extends StateNode {
        private enum Phase {
            IDLE,
            BANDAGE,
            EAT,
            DRINK,
            REPACK
        }

        private Phase phase = Phase.IDLE;
        private boolean transferringOut;
        private boolean repackingBack;
        private SelfInventoryTransferAction transferAction;
        private BaseAction brainAction;
        private InventoryItem unpackedItem;
        private ItemContainer unpackedFromBag;

        SquadFieldFirstAidNode() {
            super("SQUAD_FIELD_FIRST_AID", null, null, 0.0, true);
        }

        @Override
        public boolean canEnter(BrainContext ctx) {
            if (ctx.npc == null || ctx.npc.isDead() || ctx.npc.getVehicle() != null) {
                return false;
            }
            if (findClosestThreatDist(ctx.npc, FIELD_AID_SAFE_RADIUS) != null) {
                return false;
            }
            return pickAidKind(ctx.npc) != Phase.IDLE;
        }

        @Override
        public boolean canContinue(BrainContext ctx) {
            if (ctx.npc == null || ctx.npc.isDead() || ctx.npc.getVehicle() != null) {
                return false;
            }
            if (findClosestThreatDist(ctx.npc, FIELD_AID_SAFE_RADIUS) != null) {
                return false;
            }
            if (transferringOut || repackingBack) {
                return transferAction != null && transferAction.isRunning();
            }
            if (phase == Phase.REPACK) {
                return !ctx.npc.getCharacterActions().isEmpty();
            }
            return brainAction != null && brainAction.isRunning();
        }

        @Override
        public void onEnter(BrainContext ctx, StateScope scope) {
            phase = pickAidKind(ctx.npc);
            transferringOut = false;
            repackingBack = false;
            transferAction = null;
            brainAction = null;
            unpackedItem = null;
            unpackedFromBag = null;
            ctx.combatActivityState = "FIRST_AID";

            IsoNPC npc = ctx.npc;
            if (phase == Phase.REPACK) {
                NpcRepackMagAction.tryStart(npc);
            } else if (npc != null) {
                BaseAction.ItemLocation loc = findItemForPhase(npc, phase);
                ItemContainer mainInv = npc.getInventory();
                if (loc != null && loc.item() != null && mainInv != null) {
                    ItemContainer src = loc.item().getContainer() != null ? loc.item().getContainer() : loc.container();
                    if (src != null && src != mainInv) {
                        unpackedItem = loc.item();
                        unpackedFromBag = src;
                        transferringOut = true;
                        transferAction = new SelfInventoryTransferAction(List.of(
                                new SelfInventoryTransferAction.TransferEntry(loc.item(), src, mainInv)));
                        transferAction.start(ctx);
                    } else {
                        startPrimaryAidAction(ctx);
                    }
                }
            }

            scope.defer(() -> {
                ctx.combatActivityState = "";
                if (transferAction != null && transferAction.isRunning()) {
                    transferAction.interrupt(ctx, "FIELD_AID_EXIT");
                }
                transferAction = null;
                if (brainAction != null && brainAction.isRunning()) {
                    brainAction.interrupt(ctx, "FIELD_AID_EXIT");
                }
                brainAction = null;
                if (ctx.npc != null && !ctx.npc.getCharacterActions().isEmpty()) {
                    ctx.npc.StopAllActionQueue();
                }
                unpackedItem = null;
                unpackedFromBag = null;
                transferringOut = false;
                repackingBack = false;
                phase = Phase.IDLE;
            });
        }

        @Override
        public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
            if (transferringOut) {
                if (transferAction == null) {
                    transferringOut = false;
                    return;
                }
                transferAction.tick(ctx, dtSim);
                if (!transferAction.isRunning()) {
                    boolean ok = transferAction.getStatus() == BaseAction.ActionStatus.COMPLETED;
                    transferAction = null;
                    transferringOut = false;
                    if (ok) {
                        startPrimaryAidAction(ctx);
                    } else {
                        unpackedItem = null;
                        unpackedFromBag = null;
                    }
                }
                return;
            }

            if (repackingBack) {
                if (transferAction != null) {
                    transferAction.tick(ctx, dtSim);
                    if (!transferAction.isRunning()) {
                        transferAction = null;
                        repackingBack = false;
                    }
                } else {
                    repackingBack = false;
                }
                return;
            }

            if (brainAction != null && brainAction.isRunning()) {
                brainAction.tick(ctx, dtSim);
                if (!brainAction.isRunning() && ctx.npc != null) {
                    brainAction = null;
                    ItemContainer mainInv = ctx.npc.getInventory();
                    if (unpackedItem != null
                            && unpackedFromBag != null
                            && mainInv != null
                            && mainInv.contains(unpackedItem)
                            && unpackedFromBag.hasRoomFor(ctx.npc, unpackedItem)
                            && unpackedFromBag.isItemAllowed(unpackedItem)) {
                        repackingBack = true;
                        transferAction = new SelfInventoryTransferAction(List.of(
                                new SelfInventoryTransferAction.TransferEntry(unpackedItem, mainInv, unpackedFromBag)));
                        unpackedItem = null;
                        unpackedFromBag = null;
                        transferAction.start(ctx);
                    } else {
                        unpackedItem = null;
                        unpackedFromBag = null;
                    }
                }
            }
        }

        private void startPrimaryAidAction(BrainContext ctx) {
            if (phase == Phase.BANDAGE) {
                brainAction = new BandageSelfAction();
            } else if (phase == Phase.EAT) {
                brainAction = new EatFoodAction();
            } else if (phase == Phase.DRINK) {
                brainAction = new DrinkWaterAction();
            }
            if (brainAction != null) {
                brainAction.start(ctx);
            }
        }

        private static BaseAction.ItemLocation findItemForPhase(IsoNPC npc, Phase p) {
            return switch (p) {
                case BANDAGE -> BandageSelfAction.findBestBandage(npc);
                case EAT -> EatFoodAction.findBestEdibleFood(npc);
                case DRINK -> DrinkWaterAction.findCleanWaterContainer(npc);
                default -> null;
            };
        }

        private static Phase pickAidKind(IsoNPC npc) {
            if (npc == null) return Phase.IDLE;
            if (BandageSelfAction.hasBleedingUnbandagedPart(npc)
                    && BandageSelfAction.findBestBandage(npc) != null) {
                return Phase.BANDAGE;
            }
            if (npc.getStats() != null) {
                float h = npc.getStats().get(zombie.characters.CharacterStat.HUNGER);
                float t = npc.getStats().get(zombie.characters.CharacterStat.THIRST);
                UrgentNeed need = emberroutes.npc.brain.NeedsPolicy.evaluateUrgentNeed(
                        h, t, 0.0F, false, false, UrgentNeed.NONE);
                if (need == UrgentNeed.EAT && EatFoodAction.findBestEdibleFood(npc) != null) {
                    return Phase.EAT;
                }
                if (need == UrgentNeed.DRINK && DrinkWaterAction.findCleanWaterContainer(npc) != null) {
                    return Phase.DRINK;
                }
            }
            if (npc.getCharacterActions().isEmpty()
                    && NpcWeaponSelector.findReloadableEmptyMagazine(npc) != null) {
                return Phase.REPACK;
            }
            return Phase.IDLE;
        }
    }

    // =========================================================================
    //  小队与据点守卫共用的单兵战斗核心管线（禁止在 Guard 中复制粘贴逻辑）
    // =========================================================================

    static void cleanupCombatScope(BrainContext ctx, CombatSessionState session) {
        if (session != null) {
            session.reset();
        }
        ctx.combatActivityState = "";
        IsoNPC npc = ctx.npc;
        if (npc != null) {
            EmberSquadDirector.getInstance().releaseAllForNpc(npc.getNpcId());
            npc.clearVirtualAim();
            npc.setIsAiming(false);
            NpcNullInputComponent in = npc.getNullInputComponent();
            if (in != null) {
                in.clearCombatIntents();
            }
            npc.getLocomotion().clearCombatMove(npc);
        }
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

    /** 供 {@link CombatEngageNode} 与 {@link GuardCombatNode} 共用的单兵战斗每帧决策步。 */
    static void executeTacticalCombatTick(
            BrainContext ctx,
            CombatSessionState session,
            boolean cqbEnabled,
            boolean allowSquadSweepWhenIdle,
            double dtSim) {
        IsoNPC npc = ctx.npc;
        if (npc == null || npc.isDead()) return;

        EmberSquadDirector director = EmberSquadDirector.getInstance();
        long nowMs = System.currentTimeMillis();
        int npcZ = EmberTargetProjector.levelOf(npc.getZ());
        IsoPlayer leader = resolveLeader(ctx.followPlayerIndex);
        List<IsoNPC> liveAllies = NpcManager.getLiveNpcsSnapshot();

        // 0. 检查是否有正在运行的原版 Lua 动作（例如泵动霰弹开火后自动排队的 ISRackFirearm）
        if (!npc.getCharacterActions().isEmpty()) {
            zombie.characters.CharacterTimedActions.BaseAction curAct = npc.getCharacterActions().get(0);
            ctx.combatActivityState = "RELOADING";
            npc.setIsAiming(false);
            npc.clearVirtualAim();
            if (curAct instanceof NpcReloadAction) {
                director.getReloadPool().acquire(npc.getNpcId(), nowMs);
            }
            if (!(curAct instanceof NpcReloadAction) && curAct.stopOnWalk) {
                npc.getLocomotion().clearCombatMove(npc);
                return;
            }
            stepRetreatSpacingDuringAction(npc, npcZ, session.wallScratch);
            return;
        }

        // 1. 武器自适应切换（CQB/有备弹优先枪械，断弹自动切换随身近战）
        WeaponMode mode = NpcWeaponSelector.ensureBestCombatWeapon(npc, cqbEnabled, true);
        HandWeapon weapon = NpcWeaponSelector.getEquippedWeapon(npc);

        // 2. 二段尸潮质心撤退（<3.0 格触发背离撤退 3.0 格，1.35s 驻留 + 4.0s 冷却）
        EmberSquadDirector.HordeCluster cluster = director.findNearestCluster(npc.getX(), npc.getY(), npcZ, 6.0F);
        boolean retreating = session.retreatPolicy.update(
                npc.getX(),
                npc.getY(),
                npcZ,
                cluster != null,
                cluster != null ? cluster.centroidX : 0.0F,
                cluster != null ? cluster.centroidY : 0.0F,
                ctx.simTime,
                B42CombatRuntime.WORLD_PROBE);
        if (retreating) {
            ctx.combatActivityState = "RETREATING";
            npc.setIsAiming(false);
            npc.clearVirtualAim();
            float dx = session.retreatPolicy.getTargetX() - npc.getX();
            float dy = session.retreatPolicy.getTargetY() - npc.getY();
            float len = (float) Math.hypot(dx, dy);
            if (len > 0.1F) {
                npc.getLocomotion().setCombatMove(npc, dx / len, dy / len, true, false, 1.0F);
            }
            return;
        }

        // 3. 枪械装弹 / 排障与掩护换弹令牌池协同
        if (mode == WeaponMode.FIREARM && weapon != null) {
            ReloadKind reloadKind = NpcWeaponSelector.resolveNeededReload(npc, weapon);
            boolean dryOrJammed = !NpcWeaponSelector.canShootNow(npc, weapon);
            if (reloadKind != ReloadKind.NONE && dryOrJammed) {
                ctx.combatActivityState = "RELOADING";
                director.getReloadPool().acquire(npc.getNpcId(), nowMs);
                npc.setIsAiming(false);
                npc.clearVirtualAim();
                NpcReloadAction.startIfIdle(npc, weapon, reloadKind);
                stepRetreatSpacingDuringAction(npc, npcZ, session.wallScratch);
                return;
            }
            // 未空仓但弹匣见底：向掩护令牌池申请换弹权，未拿到则继续架枪掩护队友
            if (reloadKind != ReloadKind.NONE && weapon.getCurrentAmmoCount() <= Math.max(1, weapon.getMaxAmmo() / 4)) {
                EmberSquadDirector.ThreatEntry veryClose = findClosestThreatDist(npc, 3.2F);
                if (veryClose == null && director.getReloadPool().acquire(npc.getNpcId(), nowMs)) {
                    ctx.combatActivityState = "RELOADING";
                    npc.setIsAiming(false);
                    npc.clearVirtualAim();
                    NpcReloadAction.startIfIdle(npc, weapon, reloadKind);
                    stepRetreatSpacingDuringAction(npc, npcZ, session.wallScratch);
                    return;
                }
            }
        }
        director.getReloadPool().release(npc.getNpcId());

        // 4. 远程射击流
        if (mode == WeaponMode.FIREARM && weapon != null && NpcWeaponSelector.canShootNow(npc, weapon)) {
            EmberSquadDirector.ThreatEntry rangedTarget = pickBestRangedLosTarget(
                    npc, npcZ, weapon, director);
            if (rangedTarget != null) {
                ctx.combatActivityState = "COMBAT";
                runRangedCombatStep(npc, npcZ, weapon, rangedTarget, leader, liveAllies, session, director, nowMs, dtSim);
                return;
            }
            // 手持满弹枪但暂时无射线目标：若已开 CQB 且处于室内，进入 3m 门窗 4 相位切角警戒
            if (allowSquadSweepWhenIdle && cqbEnabled) {
                ctx.combatActivityState = "CQB_SWEEP";
                if (nowMs - session.lastCqbScanMs >= 750L) {
                    session.lastCqbScanMs = nowMs;
                    B42CombatRuntime.scanDoorWindowSectors(
                            session.cqb, npc.getX(), npc.getY(), npcZ, npc.getDirectionAngleRadians());
                }
                session.cqb.stepSectorSweep(
                        npc.getVirtualAim(),
                        npc.getDirectionAngleRadians(),
                        npc.getX(),
                        npc.getY(),
                        npc.getZ(),
                        weapon.getMaxRange(npc),
                        dtSim);
                npc.setIsAiming(true);
                npc.setAngleFromAim();
                npc.getLocomotion().setCombatMove(npc, 0.0F, 0.0F, false, true, 0.0F);
                return;
            }
            director.unregisterCorridor(npc.getNpcId());
        } else {
            director.unregisterCorridor(npc.getNpcId());
        }

        // 5. 近战围攻 / 硬直窗口压进 / 连续风筝拉扯
        float desiredRange = resolvePreferredMeleeRange(weapon);
        if (cqbEnabled
                && CqbController.isMeleeAdvanceSuppressedByCqb(
                        true, npc.getX(), npc.getY(), director, npc.getNpcId(), npcZ, B42CombatRuntime.WORLD_PROBE)
                && findClosestThreatDist(npc, 2.1F) == null) {
            ctx.combatActivityState = "COMBAT";
            EmberSquadDirector.CorridorAvoidanceResult avoid = session.moveCmd.corridor;
            director.evaluateCorridorAvoidance(
                    npc.getX(), npc.getY(), npcZ, npc.getNpcId(), 3.0F, B42CombatRuntime.WORLD_PROBE, avoid);
            if (avoid.inCorridor && (avoid.pushX != 0.0F || avoid.pushY != 0.0F)) {
                npc.getLocomotion().setCombatMove(npc, avoid.pushX, avoid.pushY, false, true, 0.78F);
                return;
            }
        }

        EmberSquadDirector.ThreatEntry meleeTarget = MeleeSlotAssigner.selectThreatAndSlot(
                director,
                npc.getNpcId(),
                npc.getX(),
                npc.getY(),
                npcZ,
                desiredRange,
                14.0F,
                session.currentMeleeTargetId,
                nowMs,
                B42CombatRuntime.WORLD_PROBE,
                session.selectedMelee);
        if (meleeTarget == null) {
            ctx.combatActivityState = "";
            npc.clearVirtualAim();
            npc.setIsAiming(false);
            npc.getLocomotion().clearCombatMove(npc);
            return;
        }

        ctx.combatActivityState = "COMBAT";
        session.currentMeleeTargetId = meleeTarget.zombieId;
        runMeleeCombatStep(npc, npcZ, weapon, desiredRange, meleeTarget, session, director, nowMs, dtSim);
    }

    private static void stepRetreatSpacingDuringAction(IsoNPC npc, int npcZ, float[] wallScratch) {
        EmberSquadDirector.ThreatEntry nearest = findClosestThreatDist(npc, 4.2F);
        if (nearest == null) {
            npc.getLocomotion().setCombatMove(npc, 0.0F, 0.0F, false, false, 0.0F);
            return;
        }
        float dx = npc.getX() - nearest.x;
        float dy = npc.getY() - nearest.y;
        float dist = (float) Math.hypot(dx, dy);
        if (dist < 1.0E-4F) return;
        SpacingController.applyWallAvoidTangentialSlide(
                npc.getX(), npc.getY(), npcZ, dx / dist, dy / dist, B42CombatRuntime.WORLD_PROBE, wallScratch);
        // 步行远离，runGear=false（尊重 stopOnRun=true）
        npc.getLocomotion().setCombatMove(npc, wallScratch[0], wallScratch[1], false, false, 0.72F);
    }

    private static void runRangedCombatStep(
            IsoNPC npc,
            int npcZ,
            HandWeapon gun,
            EmberSquadDirector.ThreatEntry target,
            IsoPlayer leader,
            List<IsoNPC> liveAllies,
            CombatSessionState session,
            EmberSquadDirector director,
            long nowMs,
            double dtSim) {
        float maxRange = Math.max(4.0F, gun.getMaxRange(npc));
        float dx = target.x - npc.getX();
        float dy = target.y - npc.getY();
        float dist = (float) Math.hypot(dx, dy);

        aimAtThreat(npc, target, maxRange, dtSim);
        npc.setIsAiming(true);
        npc.setAngleFromAim();

        float ax = npc.getX();
        float ay = npc.getY();
        float bx = ax + npc.getVirtualAim().getAimDirX() * Math.min(maxRange, dist + 1.5F);
        float by = ay + npc.getVirtualAim().getAimDirY() * Math.min(maxRange, dist + 1.5F);
        director.registerCorridor(npc.getNpcId(), ax, ay, bx, by, npcZ, nowMs);

        // 若近身有敌贴近或爬行者，先拉开安全步枪/手枪距离
        EmberSquadDirector.ThreatEntry tooClose = findClosestThreatDist(npc, 2.75F);
        if (tooClose != null) {
            float awayX = npc.getX() - tooClose.x;
            float awayY = npc.getY() - tooClose.y;
            float al = (float) Math.hypot(awayX, awayY);
            if (al > 1.0E-4F) {
                SpacingController.applyWallAvoidTangentialSlide(
                        npc.getX(), npc.getY(), npcZ, awayX / al, awayY / al, B42CombatRuntime.WORLD_PROBE, session.wallScratch);
                npc.getLocomotion().setCombatMove(npc, session.wallScratch[0], session.wallScratch[1], false, true, 0.72F);
            }
        } else {
            npc.getLocomotion().setCombatMove(npc, 0.0F, 0.0F, false, true, 0.0F);
        }

        boolean shotgun = gun.isRangeFalloff() || gun.getProjectileCount() > 1;
        boolean ready = AiAimingReadiness.isAimReady(
                npc.getAimingDelay(),
                npc.getRecoilDelay(),
                npc.getVirtualAim().getErrorDegrees(),
                dist,
                maxRange,
                shotgun);
        boolean coneBlocked = B42CombatRuntime.isLineBlockedByAllyInWorld(
                npc,
                npc.getVirtualAim().getAimDirX(),
                npc.getVirtualAim().getAimDirY(),
                dist,
                leader,
                liveAllies);
        boolean narrowHold = B42CombatRuntime.shouldShooterHoldInNarrowCorridor(
                npc.getX(),
                npc.getY(),
                npc.getVirtualAim().getAimDirX(),
                npc.getVirtualAim().getAimDirY(),
                dist,
                liveAllies);

        if (ready && !coneBlocked && !narrowHold && npc.CanAttack()) {
            NpcNullInputComponent in = npc.getNullInputComponent();
            if (in != null) in.clearCombatIntents();
            npc.setDoShove(false);
            assignAttackTargetSquare(npc, target);
            npc.AttemptAttack(0.0F);
        }
    }

    private static void assignAttackTargetSquare(IsoNPC npc, EmberSquadDirector.ThreatEntry target) {
        IsoZombie liveZ = B42CombatRuntime.getLiveZombie(target);
        if (liveZ != null && liveZ.getCurrentSquare() != null) {
            npc.setAttackTargetSquare(liveZ.getCurrentSquare());
        }
    }

    private static void runMeleeCombatStep(
            IsoNPC npc,
            int npcZ,
            HandWeapon weapon,
            float desiredRange,
            EmberSquadDirector.ThreatEntry target,
            CombatSessionState session,
            EmberSquadDirector director,
            long nowMs,
            double dtSim) {
        float dx = target.x - npc.getX();
        float dy = target.y - npc.getY();
        float dist = (float) Math.hypot(dx, dy);

        aimAtThreat(npc, target, Math.max(2.5F, desiredRange + 1.0F), dtSim);
        npc.setIsAiming(true);
        npc.setForwardDirection(session.tmpAim.set(npc.getVirtualAim().getAimDirX(), npc.getVirtualAim().getAimDirY()));
        npc.setDirectionAngle(npc.getVirtualAim().getAimYawDeg());

        boolean attackOnCooldown = npc.getMeleeDelay() > 0.5F || npc.isAttackStarted();
        SpacingController.computeMeleeStep(
                npc.getX(),
                npc.getY(),
                npcZ,
                npc.getNpcId(),
                session.selectedMelee.standX,
                session.selectedMelee.standY,
                target.x,
                target.y,
                target.velX,
                target.velY,
                target.stateMask,
                desiredRange,
                attackOnCooldown,
                director,
                B42CombatRuntime.WORLD_PROBE,
                session.moveCmd);

        if (session.moveCmd.shouldMove) {
            float cap = session.moveCmd.runGear ? -1.0F : 0.85F;
            npc.getLocomotion().setCombatMove(
                    npc,
                    session.moveCmd.dirX,
                    session.moveCmd.dirY,
                    session.moveCmd.runGear,
                    session.moveCmd.aimStrafe,
                    cap);
        } else {
            npc.getLocomotion().setCombatMove(npc, 0.0F, 0.0F, false, true, 0.0F);
        }

        // 外圈预备位成员如无贴脸风险先不跨槽空挥，等待主攻/侧翼位轮换
        boolean hasMeleeSlot = session.selectedMelee.ringSlot >= 0 || dist < 1.35F;
        if (!hasMeleeSlot || attackOnCooldown || !npc.CanAttack()) {
            return;
        }

        float moveNeedDist = Math.max(0.0F, dist - desiredRange);
        float closeMoveMs = (moveNeedDist / 2.2F) * 1000.0F;
        float secondaryEtaMs = director.estimateNearestSecondaryArrivalMs(
                npc.getX(), npc.getY(), npcZ, target.zombieId);
        StaggerPressure.Decision decision = StaggerPressure.evaluate(
                target.staggerStartMs, nowMs, closeMoveMs, secondaryEtaMs, target.stateMask);
        if (decision != StaggerPressure.Decision.COMMIT_PRESS && dist > 1.25F) {
            return;
        }

        float hitReach = Math.max(1.15F, (weapon != null ? weapon.getMaxRange(npc) : 1.05F) + 0.18F);
        if (dist > hitReach) {
            return;
        }

        assignAttackTargetSquare(npc, target);
        NpcNullInputComponent in = npc.getNullInputComponent();
        boolean allowStompOrShove = CombatTargetState.allowsStompOrShove(target.stateMask);

        if (allowStompOrShove && CombatTargetState.isGroundAttackable(target.stateMask) && dist < 1.15F) {
            if (in != null) {
                in.setCombatStompIntent(true);
                in.setCombatMeleeButtonIntent(weapon == null);
            }
            npc.setAimAtFloor(true);
            npc.setDoShove(weapon == null);
            npc.AttemptAttack(0.0F);
        } else if (allowStompOrShove && (weapon == null || dist < 0.72F)) {
            if (in != null) in.clearCombatIntents();
            npc.setAimAtFloor(false);
            npc.setDoShove(true);
            npc.AttemptAttack(0.0F);
        } else {
            // 常规挥击（包括倒起过渡期强行普通重击白嫖）
            if (in != null) in.clearCombatIntents();
            npc.setAimAtFloor(false);
            npc.setDoShove(false);
            npc.AttemptAttack(0.55F);
        }
    }

    static EmberSquadDirector.ThreatEntry findClosestThreatDist(IsoNPC npc, float maxRadius) {
        EmberSquadDirector director = EmberSquadDirector.getInstance();
        int npcZ = EmberTargetProjector.levelOf(npc.getZ());
        float nx = npc.getX();
        float ny = npc.getY();
        EmberSquadDirector.ThreatEntry best = null;
        float bestDistSq = maxRadius * maxRadius;
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || Math.abs(t.z - npcZ) > 0.6F) continue;
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

    private static EmberSquadDirector.ThreatEntry pickBestRangedLosTarget(
            IsoNPC npc, int npcZ, HandWeapon gun, EmberSquadDirector director) {
        float maxRange = Math.max(4.0F, Math.min(20.0F, gun.getMaxRange(npc) + 1.5F));
        float bestScore = Float.MAX_VALUE;
        EmberSquadDirector.ThreatEntry best = null;
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || Math.abs(t.z - npcZ) > 0.6F) continue;
            float dx = t.x - npc.getX();
            float dy = t.y - npc.getY();
            float d = (float) Math.hypot(dx, dy);
            if (d > maxRange) continue;
            if (IsoWorld.instance != null && IsoWorld.instance.currentCell != null) {
                LosUtil.TestResults los = LosUtil.lineClear(
                        IsoWorld.instance.currentCell,
                        (int) Math.floor(npc.getX()),
                        (int) Math.floor(npc.getY()),
                        npcZ,
                        (int) Math.floor(t.x),
                        (int) Math.floor(t.y),
                        npcZ,
                        false);
                if (los == LosUtil.TestResults.Blocked || los == LosUtil.TestResults.ClearThroughClosedDoor) {
                    continue;
                }
            }
            float score = d - (CombatTargetState.isSprinter(t.stateMask) ? 2.5F : 0.0F);
            if (score < bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    private static void aimAtThreat(
            IsoNPC npc, EmberSquadDirector.ThreatEntry target, float maxRange, double dtSim) {
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
                maxRange,
                (float) Math.max(0.016, dtSim));
    }

    private static float resolvePreferredMeleeRange(HandWeapon weapon) {
        if (weapon == null || weapon.isRanged()) {
            return 0.88F;
        }
        return Math.max(0.80F, Math.min(1.45F, weapon.getMaxRange() * 0.80F));
    }

    private static IsoPlayer resolveLeader(int idx) {
        if (IsoPlayer.players == null || idx < 0 || idx >= IsoPlayer.players.length) {
            return null;
        }
        return IsoPlayer.players[idx];
    }
}
