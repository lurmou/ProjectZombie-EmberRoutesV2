package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import emberroutes.warehouse.ContainerDirtyQueue;
import emberroutes.warehouse.SmartObjectRegistry;
import emberroutes.warehouse.WarehouseFastIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import zombie.characters.BodyDamage.Metabolics;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;
import zombie.inventory.types.Radio;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoStove;
import zombie.scripting.objects.CharacterTrait;

/**
 * 原版 B42 对齐的容器物品转移 TimedAction（1:1 复刻 {@code ISInventoryTransferAction}）：
 * <ul>
 *   <li>挂载原版 {@code Loot} 动作、按容器高度设 {@code LootPosition}、调用 {@code configureHandModels(null, null)} 隐藏武器并上报 {@code EventLootItem}；</li>
 *   <li>开始前自动关闭运行中的微波炉、播放容器 {@code TakeSound} 与循环翻找音 {@code RummageInInventory}，期间施加 {@code Metabolics.LightWork}；</li>
 *   <li>按 {@link VanillaActionTiming#computeInventoryTransferSeconds} 对每件物品独立计时，每完成 1 件立即执行 {@code DoRemoveItem -> AddItem} 真实原件转移并播放 {@code PutSound / StoreItemPlayerInventory}，中途打断保留已完成件。</li>
 * </ul>
 */
public final class BatchContainerTransferAction extends BaseAction {
    private static final double MAX_CONTAINER_TRANSFER_DIST = 1.65;
    private static final double MAX_HANDOVER_TRANSFER_DIST = 1.75;

    public enum TransferMode {
        LOAD_FROM_CONTAINER,
        UNLOAD_TO_CONTAINER,
        HANDOVER_TO_PLAYER
    }

    private final TransferMode mode;
    private final String worldContainerId;
    private final int targetPlayerIndex;
    private final List<InventoryItem> plannedItems;
    private final Map<InventoryItem, ItemContainer> loadTargetsByItem;
    private final List<InventoryItem> transferredItems = new ArrayList<>();
    private final List<InventoryItem> leftoverItems = new ArrayList<>();
    private boolean containerMissing;
    private double[] itemDurations;
    private int currentIdx;
    private double itemElapsedSimSec;
    private IsoObject facingTarget;

    public BatchContainerTransferAction(
            IsoNPC npc,
            TransferMode mode,
            String worldContainerId,
            int targetPlayerIndex,
            List<InventoryItem> items,
            double durationSimSec) {
        this(npc, mode, worldContainerId, targetPlayerIndex, items, null, durationSimSec);
    }

    public BatchContainerTransferAction(
            IsoNPC npc,
            TransferMode mode,
            String worldContainerId,
            int targetPlayerIndex,
            List<InventoryItem> items,
            Map<InventoryItem, ItemContainer> loadTargetsByItem,
            double durationSimSec) {
        super(npc, durationSimSec, "Loot");
        this.stopOnWalk = true;
        this.stopOnRun = true;
        this.stopOnAim = false;
        this.mode = mode;
        this.worldContainerId = worldContainerId;
        this.targetPlayerIndex = targetPlayerIndex;
        this.plannedItems = items != null ? new ArrayList<>(items) : Collections.emptyList();
        if (loadTargetsByItem != null && !loadTargetsByItem.isEmpty()) {
            this.loadTargetsByItem = new IdentityHashMap<>(loadTargetsByItem);
        } else {
            this.loadTargetsByItem = null;
        }
    }

    public List<InventoryItem> getTransferredItems() {
        return Collections.unmodifiableList(transferredItems);
    }

    public List<InventoryItem> getLeftoverItems() {
        return Collections.unmodifiableList(leftoverItems);
    }

    public boolean isContainerMissing() {
        return containerMissing;
    }

    @Override
    protected void onStart(BrainContext ctx) {
        transferredItems.clear();
        leftoverItems.clear();
        containerMissing = false;
        currentIdx = 0;
        itemElapsedSimSec = 0.0;
        facingTarget = null;

        IsoNPC targetNpc = this.npc != null ? this.npc : (ctx != null ? ctx.npc : null);
        if (targetNpc == null || targetNpc.isDead()) {
            abortRemainingAsLeftover();
            itemDurations = new double[0];
            failOnStart();
            return;
        }

        ItemContainer worldCont = null;
        if (mode == TransferMode.HANDOVER_TO_PLAYER) {
            IsoPlayer targetPlayer = resolveTargetPlayer(targetPlayerIndex);
            if (targetPlayer == null
                    || targetPlayer.isDead()
                    || targetPlayer.getInventory() == null
                    || !canReachPlayer(targetNpc, targetPlayer)) {
                abortRemainingAsLeftover();
                itemDurations = new double[0];
                failOnStart();
                return;
            }
            worldCont = targetPlayer.getInventory();
            facingTarget = targetPlayer;
            targetNpc.faceThisObject(targetPlayer);
            setPerformingActionAnim("TransferItemOnSelf");
            setReportEventName(null);
        } else {
            WarehouseFastIndex.LiveContainerRef ref = WarehouseFastIndex.resolveLiveContainer(worldContainerId);
            if (ref == null || ref.container() == null) {
                containerMissing = true;
                abortRemainingAsLeftover();
                itemDurations = new double[0];
                failOnStart();
                return;
            }
            if (!canReachContainer(targetNpc, ref.square())) {
                abortRemainingAsLeftover();
                itemDurations = new double[0];
                failOnStart();
                return;
            }
            worldCont = ref.container();
            facingTarget = ref.object();

            if ("microwave".equalsIgnoreCase(worldCont.getType())
                    && worldCont.getParent() instanceof IsoStove stove
                    && stove.Activated()) {
                stove.setActivated(false);
            }

            String lootPos = worldCont.getContainerPosition();
            if ("freezer".equalsIgnoreCase(worldCont.getType()) && worldCont.getFreezerPosition() != null) {
                lootPos = worldCont.getFreezerPosition();
            }
            if ("floor".equalsIgnoreCase(worldCont.getType())
                    || worldCont.getParent() instanceof IsoDeadBody
                    || (worldCont.getContainingItem() != null && worldCont.getContainingItem().getWorldItem() != null)) {
                lootPos = "Low";
            }
            if (lootPos != null && !lootPos.isEmpty()) {
                setExtraAnimVar("LootPosition", lootPos);
            }
            if (facingTarget != null) {
                targetNpc.faceThisObject(facingTarget);
            }
            setPerformingActionAnim("Loot");
            setReportEventName("EventLootItem");
        }

        configureHandModels(null, null);

        if (mode == TransferMode.LOAD_FROM_CONTAINER && worldCont != null) {
            String takeSound = worldCont.getTakeSound();
            if (takeSound != null && !takeSound.isEmpty()) {
                playOneShotSound(targetNpc, takeSound);
            }
        } else if (!plannedItems.isEmpty()) {
            ItemContainer srcBag = resolveNpcOwnedContainer(targetNpc, plannedItems.get(0));
            String takeSound = srcBag != null ? srcBag.getTakeSound() : null;
            if (takeSound != null && !takeSound.isEmpty()) {
                playOneShotSound(targetNpc, takeSound);
            }
        }
        playLoopingActionSound(targetNpc, "RummageInInventory");

        int n = plannedItems.size();
        this.itemDurations = new double[n];
        if (n == 0) {
            forceComplete();
            return;
        }
        boolean dextrous = targetNpc.hasTrait(CharacterTrait.DEXTROUS);
        boolean clumsy = targetNpc.hasTrait(CharacterTrait.ALL_THUMBS) || targetNpc.isWearingAwkwardGloves();
        boolean toFloor = mode == TransferMode.UNLOAD_TO_CONTAINER
                && worldCont != null
                && "floor".equalsIgnoreCase(worldCont.getType());

        double totalSec = 0.0;
        for (int i = 0; i < n; i++) {
            InventoryItem item = plannedItems.get(i);
            double w = item != null ? item.getActualWeight() : 0.1;
            boolean fromMainInv = item != null
                    && mode != TransferMode.LOAD_FROM_CONTAINER
                    && resolveNpcOwnedContainer(targetNpc, item) == targetNpc.getInventory();
            double sec = VanillaActionTiming.computeInventoryTransferSeconds(
                    w, false, 1.0, dextrous, clumsy, toFloor, fromMainInv);
            this.itemDurations[i] = sec;
            totalSec += sec;
        }
        setDurationSimSec(totalSec);
    }

    @Override
    protected void onTick(BrainContext ctx, double dtSim) {
        IsoNPC targetNpc = this.npc != null ? this.npc : (ctx != null ? ctx.npc : null);
        if (targetNpc == null || targetNpc.isDead()) {
            abortRemainingAsLeftover();
            forceStop();
            return;
        }
        targetNpc.setMetabolicTarget(Metabolics.LightWork);
        if (facingTarget != null && !targetNpc.isSittingOnFurniture()) {
            targetNpc.faceThisObject(facingTarget);
        }
        if (itemDurations == null || currentIdx >= plannedItems.size()) {
            return;
        }
        this.itemElapsedSimSec += Math.max(0.0, dtSim);
        while (currentIdx < plannedItems.size() && this.itemElapsedSimSec >= itemDurations[currentIdx]) {
            this.itemElapsedSimSec -= itemDurations[currentIdx];
            if (!processCurrentItem(targetNpc)) {
                abortRemainingAsLeftover();
                forceComplete();
                return;
            }
        }
    }

    @Override
    protected boolean onComplete(BrainContext ctx) {
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead() || npc.getInventory() == null) {
            abortRemainingAsLeftover();
            return false;
        }
        while (currentIdx < plannedItems.size()) {
            if (!processCurrentItem(npc)) {
                abortRemainingAsLeftover();
                break;
            }
        }
        return !transferredItems.isEmpty() || leftoverItems.isEmpty();
    }

    @Override
    protected void onInterrupt(BrainContext ctx, String reason) {
        abortRemainingAsLeftover();
    }

    private void abortRemainingAsLeftover() {
        while (currentIdx < plannedItems.size()) {
            InventoryItem rem = plannedItems.get(currentIdx++);
            if (rem != null) {
                leftoverItems.add(rem);
            }
        }
    }

    private boolean processCurrentItem(IsoNPC npc) {
        InventoryItem item = plannedItems.get(currentIdx);
        currentIdx++;
        if (item == null) {
            return true;
        }
        if (mode == TransferMode.HANDOVER_TO_PLAYER) {
            IsoPlayer player = resolveTargetPlayer(targetPlayerIndex);
            if (player == null
                    || player.isDead()
                    || player.getInventory() == null
                    || !canReachPlayer(npc, player)) {
                leftoverItems.add(item);
                return false;
            }
            transferSingleItem(npc, npc.getInventory(), player.getInventory(), null, player, item);
            return true;
        }

        WarehouseFastIndex.LiveContainerRef ref = WarehouseFastIndex.resolveLiveContainer(worldContainerId);
        if (ref == null || ref.container() == null) {
            containerMissing = true;
            leftoverItems.add(item);
            return false;
        }
        if (!canReachContainer(npc, ref.square())) {
            leftoverItems.add(item);
            return false;
        }

        ItemContainer worldCont = ref.container();
        IsoGridSquare sq = ref.square();
        if (mode == TransferMode.LOAD_FROM_CONTAINER) {
            transferSingleItem(npc, worldCont, npc.getInventory(), sq, npc, item);
        } else {
            transferSingleItem(npc, npc.getInventory(), worldCont, sq, npc, item);
        }
        return true;
    }

    private void transferSingleItem(
            IsoNPC actor,
            ItemContainer defaultSrc,
            ItemContainer defaultDest,
            IsoGridSquare worldSq,
            IsoGameCharacter destOwner,
            InventoryItem item) {
        ItemContainer actorMainInv = actor.getInventory();
        boolean npcIsSource = mode != TransferMode.LOAD_FROM_CONTAINER;
        ItemContainer actualSrc = npcIsSource ? resolveNpcOwnedContainer(actor, item) : defaultSrc;
        if (actualSrc == null || !actualSrc.contains(item)) {
            return;
        }

        ItemContainer targetDest = defaultDest;
        if (!npcIsSource && loadTargetsByItem != null) {
            ItemContainer preferred = loadTargetsByItem.get(item);
            if (preferred != null
                    && preferred != defaultDest
                    && isNpcOwnedContainer(actor, preferred)
                    && preferred.hasRoomFor(destOwner, item)
                    && preferred.isItemAllowed(item)) {
                targetDest = preferred;
            }
        }

        if (!npcIsSource && (!targetDest.hasRoomFor(destOwner, item) || !targetDest.isItemAllowed(item))) {
            ItemContainer fallbackBag = findFallbackEquippedBag(actor, destOwner, item, targetDest);
            if (fallbackBag != null) {
                targetDest = fallbackBag;
            }
        }

        if (!targetDest.hasRoomFor(destOwner, item) || !targetDest.isItemAllowed(item)) {
            leftoverItems.add(item);
            return;
        }
        if (actor.getPrimaryHandItem() == item) {
            actor.setPrimaryHandItem(null);
        }
        if (actor.getSecondaryHandItem() == item) {
            actor.setSecondaryHandItem(null);
        }
        actualSrc.DoRemoveItem(item);
        actualSrc.setDirty(true);
        actualSrc.setDrawDirty(true);
        targetDest.AddItem(item);
        targetDest.setDirty(true);
        targetDest.setDrawDirty(true);
        if (actorMainInv != null && (actualSrc != actorMainInv || targetDest != actorMainInv)) {
            actorMainInv.setDirty(true);
            actorMainInv.setDrawDirty(true);
        }
        transferredItems.add(item);
        if (item instanceof Radio) {
            actor.updateEquippedRadioFreq();
            if (destOwner != null && destOwner != actor) {
                destOwner.updateEquippedRadioFreq();
            }
        }
        if (worldSq != null) {
            ContainerDirtyQueue.markSquare(worldSq);
        }

        String completeSound = targetDest.getPutSound();
        if ((completeSound == null || completeSound.isEmpty())
                && destOwner != null
                && targetDest == destOwner.getInventory()) {
            completeSound = "StoreItemPlayerInventory";
        }
        if (completeSound != null && !completeSound.isEmpty()) {
            playOneShotSound(actor, completeSound);
        }
    }

    /** 检索 NPC 主物品栏或已装备/随身子背包容器，定位该物品真实所在的容器。 */
    static ItemContainer resolveNpcOwnedContainer(IsoNPC actor, InventoryItem item) {
        if (actor == null || item == null || actor.getInventory() == null) {
            return null;
        }
        ItemContainer main = actor.getInventory();
        if (main.contains(item)) {
            return main;
        }
        List<InventoryItem> mainItems = main.getItems();
        if (mainItems != null) {
            for (int i = 0, n = mainItems.size(); i < n; i++) {
                InventoryItem entry = mainItems.get(i);
                if (entry instanceof InventoryContainer bag
                        && bag.getInventory() != null
                        && bag.getInventory().contains(item)) {
                    return bag.getInventory();
                }
            }
        }
        return null;
    }

    private static boolean isNpcOwnedContainer(IsoNPC actor, ItemContainer candidate) {
        if (actor == null || candidate == null || actor.getInventory() == null) {
            return false;
        }
        if (candidate == actor.getInventory()) {
            return true;
        }
        List<InventoryItem> mainItems = actor.getInventory().getItems();
        if (mainItems != null) {
            for (int i = 0, n = mainItems.size(); i < n; i++) {
                InventoryItem entry = mainItems.get(i);
                if (entry instanceof InventoryContainer bag
                        && actor.isEquipped(bag)
                        && bag.getInventory() == candidate) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ItemContainer findFallbackEquippedBag(
            IsoNPC actor,
            IsoGameCharacter destOwner,
            InventoryItem item,
            ItemContainer excluded) {
        if (actor == null || item == null || actor.getInventory() == null) {
            return null;
        }
        List<InventoryItem> mainItems = actor.getInventory().getItems();
        if (mainItems == null || mainItems.isEmpty()) {
            return null;
        }
        ItemContainer best = null;
        int bestReduction = -1;
        for (int i = 0, n = mainItems.size(); i < n; i++) {
            InventoryItem entry = mainItems.get(i);
            if (!(entry instanceof InventoryContainer bag) || !actor.isEquipped(bag)) {
                continue;
            }
            ItemContainer bagInv = bag.getInventory();
            if (bagInv == null || bagInv == excluded) {
                continue;
            }
            if (bagInv.hasRoomFor(destOwner, item) && bagInv.isItemAllowed(item)) {
                int reduction = bag.getWeightReduction();
                if (best == null || reduction > bestReduction) {
                    best = bagInv;
                    bestReduction = reduction;
                }
            }
        }
        return best;
    }

    private static boolean canReachContainer(IsoNPC npc, IsoGridSquare contSq) {
        if (npc == null || contSq == null) {
            return false;
        }
        if (!SmartObjectRegistry.canReachSquare(npc.getCurrentSquare(), contSq)) {
            return false;
        }
        return Math.hypot((contSq.getX() + 0.5) - npc.getX(), (contSq.getY() + 0.5) - npc.getY())
                <= MAX_CONTAINER_TRANSFER_DIST;
    }

    private static boolean canReachPlayer(IsoNPC npc, IsoPlayer player) {
        if (npc == null || player == null) {
            return false;
        }
        if (!SmartObjectRegistry.canReachSquare(npc.getCurrentSquare(), player.getCurrentSquare())) {
            return false;
        }
        return Math.hypot(player.getX() - npc.getX(), player.getY() - npc.getY())
                <= MAX_HANDOVER_TRANSFER_DIST;
    }

    private static IsoPlayer resolveTargetPlayer(int playerIndex) {
        if (IsoPlayer.players == null || playerIndex < 0 || playerIndex >= IsoPlayer.players.length) {
            return null;
        }
        return IsoPlayer.players[playerIndex];
    }
}
