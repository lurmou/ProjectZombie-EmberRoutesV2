package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.brain.NeedsPolicy.UrgentNeed;
import emberroutes.npc.combat.NpcRepackMagAction;
import emberroutes.npc.combat.NpcWeaponSelector;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import zombie.characters.CharacterStat;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.HandWeapon;

/**
 * 统一随身生理自理与战间压弹状态节点（据点生存支线与随从战间自理共用一套状态机与背囊取放契约）：
 * <ol>
 *   <li>严格遵守引擎「起身屏障」：等待 {@code !posture.isBlockingLocomotion()} 后再发起背包取物或执行动作；</li>
 *   <li>严格遵守原版容器规则：若物品/弹匣/散弹在背包内，先经 {@link SelfInventoryTransferAction} 掏入主物品栏，
 *       执行完毕后再将保留的容器/工具/弹匣归还原背包；</li>
 *   <li>全部在途动作与临时锁定均绑定至 {@link StateScope}，遇敌打断或死亡时自动对称撤销。</li>
 * </ol>
 */
public final class SelfAidActionNode extends StateNode {
    private enum Mode {
        SINGLE,
        FIELD_AID
    }

    private enum Phase {
        IDLE,
        UNPACKING,
        EXECUTING,
        REPACKING
    }

    private enum FieldKind {
        NONE,
        BANDAGE,
        EAT,
        DRINK,
        REPACK
    }

    private final Mode mode;
    private final Function<IsoNPC, BaseAction> singleFactory;
    private final Function<IsoNPC, BaseAction.ItemLocation> singleFinder;
    private final Predicate<BrainContext> enterGuard;
    private final Predicate<BrainContext> continueGuard;
    private final Predicate<BrainContext> threatNearbyGuard;

    private Phase phase = Phase.IDLE;
    private FieldKind activeFieldKind = FieldKind.NONE;
    private SelfInventoryTransferAction transferAction;
    private BaseAction aidAction;
    private InventoryItem returnItem;
    private ItemContainer returnBag;
    private boolean failed;

    private SelfAidActionNode(
            String id,
            Mode mode,
            Function<IsoNPC, BaseAction> singleFactory,
            Function<IsoNPC, BaseAction.ItemLocation> singleFinder,
            Predicate<BrainContext> enterGuard,
            Predicate<BrainContext> continueGuard,
            Predicate<BrainContext> threatNearbyGuard) {
        super(id, null, null, 0.0, true);
        this.mode = mode;
        this.singleFactory = singleFactory;
        this.singleFinder = singleFinder;
        this.enterGuard = enterGuard;
        this.continueGuard = continueGuard;
        this.threatNearbyGuard = threatNearbyGuard;
    }

    /** 据点单类自理叶（{@code BANDAGE / EAT / DRINK}），取代旧 {@code SurvivalActionNode}。 */
    public static SelfAidActionNode forSingleAction(
            String id,
            Function<IsoNPC, BaseAction> factory,
            Function<IsoNPC, BaseAction.ItemLocation> itemFinder,
            Predicate<BrainContext> enterGuard,
            Predicate<BrainContext> continueGuard) {
        return new SelfAidActionNode(id, Mode.SINGLE, factory, itemFinder, enterGuard, continueGuard, null);
    }

    /** 随从战间随身自理叶（包扎/干粮/饮水/背囊取弹压弹），受 {@code threatNearbyGuard} 实时门控。 */
    public static SelfAidActionNode forSquadFieldAid(Predicate<BrainContext> threatNearbyGuard) {
        return new SelfAidActionNode(
                "SQUAD_FIELD_FIRST_AID",
                Mode.FIELD_AID,
                null,
                null,
                null,
                null,
                threatNearbyGuard);
    }

    @Override
    public boolean canEnter(BrainContext ctx) {
        if (ctx == null || ctx.npc == null || ctx.npc.isDead()) {
            return false;
        }
        if (mode == Mode.SINGLE) {
            return enterGuard.test(ctx);
        }
        if (ctx.npc.getVehicle() != null || (threatNearbyGuard != null && threatNearbyGuard.test(ctx))) {
            return false;
        }
        return pickFieldKind(ctx.npc) != FieldKind.NONE;
    }

    @Override
    public boolean canContinue(BrainContext ctx) {
        if (ctx == null || ctx.npc == null || ctx.npc.isDead() || failed) {
            return false;
        }
        if (mode == Mode.SINGLE) {
            if (phase == Phase.REPACKING && transferAction != null && transferAction.isRunning()) {
                return true;
            }
            return continueGuard.test(ctx);
        }
        if (ctx.npc.getVehicle() != null || (threatNearbyGuard != null && threatNearbyGuard.test(ctx))) {
            return false;
        }
        if (phase == Phase.UNPACKING || phase == Phase.REPACKING) {
            return transferAction != null && transferAction.isRunning();
        }
        if (phase == Phase.EXECUTING) {
            return aidAction != null && aidAction.isRunning();
        }
        return pickFieldKind(ctx.npc) != FieldKind.NONE;
    }

    @Override
    public void onEnter(BrainContext ctx, StateScope scope) {
        ctx.working = false;
        resetTransientState();
        if (mode == Mode.FIELD_AID) {
            ctx.combatActivityState = "FIRST_AID";
        }
        IsoNPC npc = ctx.npc;
        if (npc != null && !npc.isDead()) {
            npc.getLocomotion().stop(npc);
            if (npc.getPosture().isBlockingLocomotion()) {
                ctx.requestStandUp(true);
            }
            if (mode == Mode.FIELD_AID) {
                scope.defer(npc.getGaze().suppress("FIRST_AID"));
            }
        }
        scope.defer(() -> {
            if (mode == Mode.FIELD_AID) {
                ctx.combatActivityState = "";
            }
            if (this.transferAction != null && this.transferAction.isRunning()) {
                this.transferAction.interrupt(ctx, "SCOPE_EXIT");
            }
            this.transferAction = null;
            if (this.aidAction != null && this.aidAction.isRunning()) {
                this.aidAction.interrupt(ctx, "SCOPE_EXIT");
            }
            this.aidAction = null;
            resetTransientState();
        });
    }

    @Override
    public void onTick(BrainContext ctx, StateScope scope, double dtSim) {
        if (ctx.npc == null || failed) {
            return;
        }
        IsoNPC npc = ctx.npc;
        if (phase == Phase.IDLE && npc.getPosture().isActive()) {
            ctx.requestStandUp(false);
            if (npc.getPosture().isBlockingLocomotion()) {
                return;
            }
        }

        switch (phase) {
            case IDLE -> startNextCycle(ctx);
            case UNPACKING -> tickUnpacking(ctx);
            case EXECUTING -> tickExecuting(ctx);
            case REPACKING -> tickRepacking(ctx);
        }
    }

    private void tickUnpacking(BrainContext ctx) {
        if (transferAction == null) {
            failed = true;
            return;
        }
        if (transferAction.isRunning()) {
            return;
        }
        boolean ok = transferAction.getStatus() == BaseAction.ActionStatus.COMPLETED;
        transferAction = null;
        if (!ok) {
            returnItem = null;
            returnBag = null;
            failed = true;
            return;
        }
        startExecutingPhase(ctx);
    }

    private void tickExecuting(BrainContext ctx) {
        if (aidAction == null) {
            failed = true;
            return;
        }
        if (aidAction.isRunning()) {
            return;
        }
        BaseAction.ActionStatus st = aidAction.getStatus();
        aidAction = null;
        if (st == BaseAction.ActionStatus.FAILED) {
            failed = true;
            return;
        }
        if (tryStartRepackIfNeeded(ctx)) {
            return;
        }
        if (shouldLoopAgain(ctx)) {
            startNextCycle(ctx);
        } else {
            phase = Phase.IDLE;
            activeFieldKind = FieldKind.NONE;
        }
    }

    private void tickRepacking(BrainContext ctx) {
        if (transferAction != null && transferAction.isRunning()) {
            return;
        }
        transferAction = null;
        if (shouldLoopAgain(ctx)) {
            startNextCycle(ctx);
        } else {
            phase = Phase.IDLE;
            activeFieldKind = FieldKind.NONE;
        }
    }

    private boolean tryStartRepackIfNeeded(BrainContext ctx) {
        IsoNPC npc = ctx.npc;
        ItemContainer mainInv = npc != null ? npc.getInventory() : null;
        if (returnItem == null || returnBag == null || mainInv == null || !mainInv.contains(returnItem)) {
            returnItem = null;
            returnBag = null;
            return false;
        }
        if (mode == Mode.SINGLE && continueGuard.test(ctx)) {
            BaseAction.ItemLocation nextLoc = singleFinder.apply(npc);
            if (nextLoc != null && nextLoc.item() == returnItem) {
                return false;
            }
        }
        if (returnBag.hasRoomFor(npc, returnItem) && returnBag.isItemAllowed(returnItem)) {
            phase = Phase.REPACKING;
            transferAction = new SelfInventoryTransferAction(npc, List.of(
                    new SelfInventoryTransferAction.TransferEntry(returnItem, mainInv, returnBag)));
            returnItem = null;
            returnBag = null;
            transferAction.start(ctx);
            return true;
        }
        returnItem = null;
        returnBag = null;
        return false;
    }

    private void startNextCycle(BrainContext ctx) {
        IsoNPC npc = ctx.npc;
        ItemContainer mainInv = npc != null ? npc.getInventory() : null;
        if (npc == null || mainInv == null) {
            failed = true;
            return;
        }
        if (mode == Mode.SINGLE) {
            BaseAction.ItemLocation loc = singleFinder.apply(npc);
            if (loc == null || loc.item() == null) {
                failed = true;
                return;
            }
            ItemContainer src = loc.item().getContainer() != null ? loc.item().getContainer() : loc.container();
            if (src != null && src != mainInv) {
                this.returnItem = loc.item();
                this.returnBag = src;
                this.phase = Phase.UNPACKING;
                this.transferAction = new SelfInventoryTransferAction(npc, List.of(
                        new SelfInventoryTransferAction.TransferEntry(loc.item(), src, mainInv)));
                this.transferAction.start(ctx);
            } else {
                startExecutingPhase(ctx);
            }
            return;
        }

        FieldKind kind = pickFieldKind(npc);
        if (kind == FieldKind.NONE) {
            phase = Phase.IDLE;
            activeFieldKind = FieldKind.NONE;
            return;
        }
        activeFieldKind = kind;
        if (kind == FieldKind.REPACK) {
            HandWeapon eqGun = NpcWeaponSelector.getEquippedWeapon(npc);
            NpcWeaponSelector.RepackPlan plan = NpcWeaponSelector.planMagRepack(npc, eqGun);
            if (plan == null || plan.magazine() == null) {
                failed = true;
                return;
            }
            this.returnItem = plan.magazine();
            this.returnBag = plan.magOriginBag();
            List<SelfInventoryTransferAction.TransferEntry> prep = plan.prepTransfers();
            if (prep != null && !prep.isEmpty()) {
                this.phase = Phase.UNPACKING;
                this.transferAction = new SelfInventoryTransferAction(npc, prep);
                this.transferAction.start(ctx);
            } else {
                this.phase = Phase.EXECUTING;
                this.aidAction = new NpcRepackMagAction(npc, plan.magazine(), eqGun);
                this.aidAction.start(ctx);
            }
            return;
        }

        BaseAction.ItemLocation loc = findItemForFieldKind(npc, kind);
        if (loc == null || loc.item() == null) {
            failed = true;
            return;
        }
        ItemContainer src = loc.item().getContainer() != null ? loc.item().getContainer() : loc.container();
        if (src != null && src != mainInv) {
            this.returnItem = loc.item();
            this.returnBag = src;
            this.phase = Phase.UNPACKING;
            this.transferAction = new SelfInventoryTransferAction(npc, List.of(
                    new SelfInventoryTransferAction.TransferEntry(loc.item(), src, mainInv)));
            this.transferAction.start(ctx);
        } else {
            startExecutingPhase(ctx);
        }
    }

    private void startExecutingPhase(BrainContext ctx) {
        IsoNPC npc = ctx.npc;
        if (npc == null) {
            failed = true;
            return;
        }
        this.phase = Phase.EXECUTING;
        if (mode == Mode.SINGLE) {
            this.aidAction = singleFactory.apply(npc);
            if (this.aidAction != null) {
                this.aidAction.start(ctx);
            } else {
                failed = true;
            }
            return;
        }
        switch (activeFieldKind) {
            case BANDAGE -> this.aidAction = new BandageSelfAction(npc);
            case EAT -> this.aidAction = new EatFoodAction(npc);
            case DRINK -> this.aidAction = new DrinkWaterAction(npc);
            case REPACK -> {
                HandWeapon eqGun = NpcWeaponSelector.getEquippedWeapon(npc);
                InventoryItem mag = returnItem != null
                        ? returnItem
                        : NpcWeaponSelector.findReloadableEmptyMagazine(npc);
                this.aidAction = (mag != null) ? new NpcRepackMagAction(npc, mag, eqGun) : null;
            }
            default -> this.aidAction = null;
        }
        if (this.aidAction != null) {
            this.aidAction.start(ctx);
        } else {
            failed = true;
        }
    }

    private boolean shouldLoopAgain(BrainContext ctx) {
        if (mode == Mode.SINGLE) {
            return continueGuard.test(ctx);
        }
        return canContinue(ctx) && pickFieldKind(ctx.npc) != FieldKind.NONE;
    }

    private void resetTransientState() {
        phase = Phase.IDLE;
        activeFieldKind = FieldKind.NONE;
        returnItem = null;
        returnBag = null;
        failed = false;
    }

    private static BaseAction.ItemLocation findItemForFieldKind(IsoNPC npc, FieldKind kind) {
        return switch (kind) {
            case BANDAGE -> BandageSelfAction.findBestBandage(npc);
            case EAT -> EatFoodAction.findBestEdibleFood(npc);
            case DRINK -> DrinkWaterAction.findCleanWaterContainer(npc);
            default -> null;
        };
    }

    private static FieldKind pickFieldKind(IsoNPC npc) {
        if (npc == null) return FieldKind.NONE;
        if (BandageSelfAction.hasBleedingUnbandagedPart(npc)
                && BandageSelfAction.findBestBandage(npc) != null) {
            return FieldKind.BANDAGE;
        }
        if (npc.getStats() != null) {
            float h = npc.getStats().get(CharacterStat.HUNGER);
            float t = npc.getStats().get(CharacterStat.THIRST);
            UrgentNeed need = NeedsPolicy.evaluateUrgentNeed(
                    h, t, 0.0F, false, false, UrgentNeed.NONE);
            if (need == UrgentNeed.EAT && EatFoodAction.findBestEdibleFood(npc) != null) {
                return FieldKind.EAT;
            }
            if (need == UrgentNeed.DRINK && DrinkWaterAction.findCleanWaterContainer(npc) != null) {
                return FieldKind.DRINK;
            }
        }
        HandWeapon gun = NpcWeaponSelector.getEquippedWeapon(npc);
        if (npc.getCharacterActions().isEmpty()
                && NpcWeaponSelector.findReloadableEmptyMagazineLocation(npc, gun) != null) {
            return FieldKind.REPACK;
        }
        return FieldKind.NONE;
    }
}
