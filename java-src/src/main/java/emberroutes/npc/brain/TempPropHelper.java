package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import java.util.List;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;

/**
 * 闲暇临时道具（{@code TEMP_PROP}）视觉挂载与强制回收器：
 * <ul>
 *   <li>优先走原版 {@code forceNullOverride + overrideSecondaryHandModel} 纯视觉手模覆盖，不往背包塞任何假物品；</li>
 *   <li>若任何路径附加了带 {@code ModData.ER_TempProp} 的临时物品，{@link #cleanupAllTempProps(IsoNPC)}
 *       于退闲、切态、死亡、离屏 {@code phaseOut} 与存盘前 100% 清理干净。</li>
 * </ul>
 */
public final class TempPropHelper {
    public static final String MODDATA_TEMP_PROP_KEY = "ER_TempProp";

    private TempPropHelper() {}

    /** 在左手持握临时书本视觉模型并开启阅读动作变量（不改写真实背包与手持装备）。 */
    public static void equipTempBookProp(IsoNPC npc) {
        if (npc == null || npc.isDead()) {
            return;
        }
        npc.forceNullOverride = true;
        npc.overridePrimaryHandModel = null;
        npc.overrideSecondaryHandModel = "Book";
        npc.setVariable("PerformingAction", "read");
        npc.setVariable("ReadType", "book");
        npc.setVariable("IsPerformingAnAction", true);
        try {
            npc.resetEquippedHandsModels();
        } catch (Throwable ignored) {
        }
    }

    /** 清空全部视觉临时手模与任何带 {@code ER_TempProp} 标记的残余物品，并还原武器渲染。 */
    public static void cleanupAllTempProps(IsoNPC npc) {
        if (npc == null) {
            return;
        }
        boolean modelDirty = npc.forceNullOverride
                || npc.overridePrimaryHandModel != null
                || npc.overrideSecondaryHandModel != null;

        npc.forceNullOverride = false;
        npc.overridePrimaryHandModel = null;
        npc.overrideSecondaryHandModel = null;
        npc.clearVariable("PerformingAction");
        npc.clearVariable("ReadType");
        npc.setVariable("IsPerformingAnAction", false);

        if (purgeTempPropItemsOnly(npc)) {
            modelDirty = true;
        }

        if (modelDirty) {
            try {
                npc.resetEquippedHandsModels();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 仅清扫手持/背包中残留的 {@code ER_TempProp} 物品实例（存盘热快照前调用，不影响视觉坐读）。 */
    public static boolean purgeTempPropItemsOnly(IsoNPC npc) {
        if (npc == null) {
            return false;
        }
        boolean dirty = false;
        if (isTempPropItem(npc.getPrimaryHandItem())) {
            npc.setPrimaryHandItem(null);
            dirty = true;
        }
        if (isTempPropItem(npc.getSecondaryHandItem())) {
            npc.setSecondaryHandItem(null);
            dirty = true;
        }
        if (purgeContainerTempProps(npc.getInventory())) {
            dirty = true;
        }
        return dirty;
    }

    public static boolean isTempPropItem(InventoryItem item) {
        return item != null
                && item.hasModData()
                && Boolean.TRUE.equals(item.getModData().rawget(MODDATA_TEMP_PROP_KEY));
    }

    private static boolean purgeContainerTempProps(ItemContainer container) {
        if (container == null) {
            return false;
        }
        boolean removedAny = false;
        List<InventoryItem> items = container.getItems();
        if (items == null) {
            return false;
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            InventoryItem it = items.get(i);
            if (it == null) {
                continue;
            }
            if (isTempPropItem(it)) {
                container.Remove(it);
                removedAny = true;
            } else if (it instanceof InventoryContainer bag && bag.getInventory() != null) {
                if (purgeContainerTempProps(bag.getInventory())) {
                    removedAny = true;
                }
            }
        }
        return removedAny;
    }
}
