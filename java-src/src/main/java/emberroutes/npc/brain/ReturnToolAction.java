package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import emberroutes.warehouse.ContainerDirtyQueue;
import emberroutes.warehouse.WarehouseFastIndex;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.util.list.PZArrayList;

/**
 * 借用工具自愈归还策略：扫描背包带有 {@code ModData.ER_BorrowedFrom} 来源戳的真工具原件，
 * 空闲时步行前往原仓储容器坐标归还并清空来源戳，杜绝 6 层持久互锁账本。
 */
public final class ReturnToolAction {
    public static final String MODDATA_BORROWED_FROM_KEY = "ER_BorrowedFrom";

    private ReturnToolAction() {}

    public static BaseAction.ItemLocation findBorrowedTool(IsoNPC npc) {
        if (npc == null || npc.getInventory() == null) {
            return null;
        }
        return BaseAction.findItemRecursive(npc.getInventory(), item ->
                item != null
                        && item.hasModData()
                        && item.getModData().rawget(MODDATA_BORROWED_FROM_KEY) instanceof String s
                        && !s.isEmpty());
    }

    /** 解析 {@code x:y:z#...} 格式坐标，返回 {@code {x, y, z}}，无法解析则返回 {@code null}。 */
    public static int[] parseSourceCoords(String sourceKey) {
        if (sourceKey == null || sourceKey.isEmpty()) {
            return null;
        }
        int hash = sourceKey.indexOf('#');
        String coordPart = hash > 0 ? sourceKey.substring(0, hash) : sourceKey;
        String[] parts = coordPart.split(":");
        if (parts.length < 3) {
            return null;
        }
        try {
            return new int[]{
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim())
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 尝试将指定借用工具原件交还给来源格上的仓储容器并清除 {@code ER_BorrowedFrom}。 */
    public static boolean tryDepositToSourceSquare(IsoNPC npc, BaseAction.ItemLocation loc, int x, int y, int z) {
        if (npc == null || loc == null || loc.item() == null || IsoWorld.instance == null) {
            return false;
        }
        IsoCell cell = IsoWorld.instance.currentCell;
        if (cell == null) {
            return false;
        }
        IsoGridSquare sq = cell.getGridSquare(x, y, z);
        if (sq == null) {
            return false;
        }
        ItemContainer target = findTargetStorageContainer(sq, loc.item());
        if (target == null) {
            // 原容器已被拆毁：清除过期来源戳，避免死循环卡在归还节点
            loc.item().getModData().rawset(MODDATA_BORROWED_FROM_KEY, null);
            return true;
        }
        InventoryItem tool = loc.item();
        if (npc.getPrimaryHandItem() == tool) {
            npc.setPrimaryHandItem(null);
        }
        if (npc.getSecondaryHandItem() == tool) {
            npc.setSecondaryHandItem(null);
        }
        if (loc.container() != null) {
            loc.container().Remove(tool);
        } else if (npc.getInventory() != null) {
            npc.getInventory().Remove(tool);
        }
        tool.getModData().rawset(MODDATA_BORROWED_FROM_KEY, null);
        target.AddItem(tool);
        target.setDirty(true);
        ContainerDirtyQueue.markSquare(sq);
        return true;
    }

    private static ItemContainer findTargetStorageContainer(IsoGridSquare sq, InventoryItem item) {
        PZArrayList<IsoObject> objs = sq.getObjects();
        if (objs == null) {
            return null;
        }
        for (int i = 0, n = objs.size(); i < n; i++) {
            IsoObject obj = objs.get(i);
            if (!WarehouseFastIndex.isEligibleStorageObject(obj) || WarehouseFastIndex.isObjectPrivate(obj)) {
                continue;
            }
            int cnt = Math.max(1, obj.getContainerCount());
            for (int ci = 0; ci < cnt; ci++) {
                ItemContainer ic = obj.getContainerByIndex(ci);
                if (ic == null && ci == 0) {
                    ic = obj.getContainer();
                }
                if (WarehouseFastIndex.isEligibleContainerType(ic) && ic.hasRoomFor(null, item)) {
                    return ic;
                }
            }
        }
        return null;
    }
}
