package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import emberroutes.warehouse.ContainerDirtyQueue;
import emberroutes.warehouse.WarehouseFastIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
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
    private final List<InventoryItem> transferredItems = new ArrayList<>();
    private final List<InventoryItem> leftoverItems = new ArrayList<>();
    private boolean containerMissing;

    public BatchContainerTransferAction(
            TransferMode mode,
            String worldContainerId,
            int targetPlayerIndex,
            List<InventoryItem> items,
            double durationSimSec) {
        super(durationSimSec, "Loot");
        this.mode = mode;
        this.worldContainerId = worldContainerId;
        this.targetPlayerIndex = targetPlayerIndex;
        this.plannedItems = items != null ? new ArrayList<>(items) : Collections.emptyList();
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
            ItemContainer src,
            ItemContainer dest,
            IsoGridSquare worldSq,
            zombie.characters.IsoGameCharacter destOwner) {
        boolean anyMoved = false;
        for (int i = 0, n = plannedItems.size(); i < n; i++) {
            InventoryItem item = plannedItems.get(i);
            if (item == null) {
                continue;
            }
            if (!src.contains(item)) {
                continue;
            }
            if (!dest.hasRoomFor(destOwner, item) || !dest.isItemAllowed(item)) {
                leftoverItems.add(item);
                continue;
            }
            if (actor.getPrimaryHandItem() == item) {
                actor.setPrimaryHandItem(null);
            }
            if (actor.getSecondaryHandItem() == item) {
                actor.setSecondaryHandItem(null);
            }
            src.DoRemoveItem(item);
            src.setDirty(true);
            src.setDrawDirty(true);
            dest.AddItem(item);
            dest.setDirty(true);
            dest.setDrawDirty(true);
            transferredItems.add(item);
            anyMoved = true;
        }
        if (worldSq != null && anyMoved) {
            ContainerDirtyQueue.markSquare(worldSq);
        }
        return anyMoved || leftoverItems.isEmpty();
    }

    private static IsoPlayer resolveTargetPlayer(int playerIndex) {
        if (IsoPlayer.players == null || playerIndex < 0 || playerIndex >= IsoPlayer.players.length) {
            return null;
        }
        return IsoPlayer.players[playerIndex];
    }
}
