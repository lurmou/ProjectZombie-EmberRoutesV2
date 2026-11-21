package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import emberroutes.warehouse.ContainerDirtyQueue;
import emberroutes.warehouse.WarehouseFastIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

/**
 * 机械动力 / 原版对齐的批量箱物交互动作（挂载原版 {@code Loot} 动作 + 真实原件转移）：
 * <ul>
 *   <li>严格通过 {@code DoRemoveItem(item) -> AddItem(item)} 迁移真实 {@link InventoryItem} 实例，100% 保留 {@code id} 与 {@code ModData}；</li>
 *   <li>支持中途目标箱满仓检出（返回 {@link #getLeftoverItems()} 供上层平滑切换次优箱或退回源箱）；</li>
 *   <li>每次变动立即置 {@code setDirty(true)} 并唤醒 {@link ContainerDirtyQueue#markSquare(IsoGridSquare)}。</li>
 * </ul>
 */
public final class BatchContainerTransferAction extends BaseAction {
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

    public BatchContainerTransferAction(
            TransferMode mode,
            String worldContainerId,
            int targetPlayerIndex,
            List<InventoryItem> items,
            double durationSimSec) {
        this(mode, worldContainerId, targetPlayerIndex, items, null, durationSimSec);
    }

    public BatchContainerTransferAction(
            TransferMode mode,
            String worldContainerId,
            int targetPlayerIndex,
            List<InventoryItem> items,
            Map<InventoryItem, ItemContainer> loadTargetsByItem,
            double durationSimSec) {
        super(durationSimSec, "Loot");
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
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead()) {
            return;
        }
        if (mode == TransferMode.HANDOVER_TO_PLAYER) {
            IsoPlayer targetPlayer = resolveTargetPlayer(targetPlayerIndex);
            if (targetPlayer != null) {
                npc.faceThisObject(targetPlayer);
            }
            playActionSound(npc, "PutItemInBag");
            return;
        }
        WarehouseFastIndex.LiveContainerRef ref = WarehouseFastIndex.resolveLiveContainer(worldContainerId);
        if (ref == null || ref.container() == null) {
            containerMissing = true;
            return;
        }
        ItemContainer ic = ref.container();
        if ("freezer".equalsIgnoreCase(ic.getType()) && ic.getFreezerPosition() != null) {
            setExtraAnimVar("LootPosition", ic.getFreezerPosition());
        } else if (ic.getContainerPosition() != null) {
            setExtraAnimVar("LootPosition", ic.getContainerPosition());
        }
        if (ref.object() != null) {
            npc.faceThisObject(ref.object());
        }
        playActionSound(npc, "PutItemInBag");
    }

    @Override
    protected boolean onComplete(BrainContext ctx) {
        transferredItems.clear();
        leftoverItems.clear();
        IsoNPC npc = ctx != null ? ctx.npc : null;
        if (npc == null || npc.isDead() || npc.getInventory() == null) {
            leftoverItems.addAll(plannedItems);
            return false;
        }

        if (mode == TransferMode.HANDOVER_TO_PLAYER) {
            IsoPlayer player = resolveTargetPlayer(targetPlayerIndex);
            if (player == null || player.isDead() || player.getInventory() == null) {
                leftoverItems.addAll(plannedItems);
                return false;
            }
            return executeTransfer(npc, npc.getInventory(), player.getInventory(), null, player);
        }

        WarehouseFastIndex.LiveContainerRef ref = WarehouseFastIndex.resolveLiveContainer(worldContainerId);
        if (ref == null || ref.container() == null) {
            containerMissing = true;
            leftoverItems.addAll(plannedItems);
            return false;
        }

        ItemContainer worldCont = ref.container();
        IsoGridSquare sq = ref.square();
        if (mode == TransferMode.LOAD_FROM_CONTAINER) {
            return executeTransfer(npc, worldCont, npc.getInventory(), sq, npc);
        } else {
            return executeTransfer(npc, npc.getInventory(), worldCont, sq, npc);
        }
    }

    private boolean executeTransfer(
            IsoNPC actor,
            ItemContainer defaultSrc,
            ItemContainer defaultDest,
            IsoGridSquare worldSq,
            zombie.characters.IsoGameCharacter destOwner) {
        ItemContainer actorMainInv = actor.getInventory();
        boolean npcIsSource = mode != TransferMode.LOAD_FROM_CONTAINER;
        boolean anyMoved = false;

        for (int i = 0, n = plannedItems.size(); i < n; i++) {
            InventoryItem item = plannedItems.get(i);
            if (item == null) {
                continue;
            }
            ItemContainer actualSrc = npcIsSource ? resolveNpcOwnedContainer(actor, item) : defaultSrc;
            if (actualSrc == null || !actualSrc.contains(item)) {
                continue;
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

            if (!targetDest.hasRoomFor(destOwner, item) || !targetDest.isItemAllowed(item)) {
                leftoverItems.add(item);
                continue;
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
            anyMoved = true;
        }
        if (worldSq != null && anyMoved) {
            ContainerDirtyQueue.markSquare(worldSq);
        }
        return anyMoved || leftoverItems.isEmpty();
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

    private static IsoPlayer resolveTargetPlayer(int playerIndex) {
        if (IsoPlayer.players == null || playerIndex < 0 || playerIndex >= IsoPlayer.players.length) {
            return null;
        }
        return IsoPlayer.players[playerIndex];
    }
}
