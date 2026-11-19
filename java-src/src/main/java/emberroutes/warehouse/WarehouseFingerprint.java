package emberroutes.warehouse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import zombie.entity.components.fluids.FluidContainer;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Food;

/**
 * 槽位与原位状态容器指纹（对标 AE2 {@code AEItemKey} 组件感知哈希 + 零分配快速比对）：
 *
 * <ol>
 *   <li>槽位真实 {@code InventoryItem.id} 顺序数组；</li>
 *   <li>{@code 0.01kg} 量化内容总重；</li>
 *   <li>零分配滚动 64 位原位状态摘要（覆盖充能/剩余次数、武器工具耐久、损坏标记、食物新鲜/腐烂/熟焦状态、流体毫升量）；</li>
 *   <li>{@link #matchesContainer(ItemContainer)} 支持未变箱子 0 数组/0 对象分配直接比对。</li>
 * </ol>
 */
public final class WarehouseFingerprint {
    private static final long FNV_OFFSET = -3750763034362895579L;
    private static final long FNV_PRIME = 1099511628211L;

    private final int[] slotIds;
    private final long weightBits;
    private final long stateHash;

    public WarehouseFingerprint(int[] slotIds, double weight) {
        this(slotIds, weight, 0L);
    }

    public WarehouseFingerprint(int[] slotIds, double weight, long stateHash) {
        this.slotIds = slotIds;
        this.weightBits = quantizeWeight(weight);
        this.stateHash = stateHash;
    }

    private static long quantizeWeight(double weight) {
        return Double.doubleToLongBits(Math.round(weight * 100.0) / 100.0);
    }

    public static long mixItemState(
            long acc,
            int itemId,
            int currentUses,
            int condition,
            int stateFlags,
            int fluidMilliliters) {
        long h = acc;
        h = (h ^ itemId) * FNV_PRIME;
        h = (h ^ currentUses) * FNV_PRIME;
        h = (h ^ condition) * FNV_PRIME;
        h = (h ^ stateFlags) * FNV_PRIME;
        h = (h ^ fluidMilliliters) * FNV_PRIME;
        return h;
    }

    private static long foldItemState(long acc, InventoryItem it, int id) {
        if (it == null) {
            return (acc ^ 0L) * FNV_PRIME;
        }
        int uses = it.IsDrainable() ? it.getCurrentUses() : 0;
        int cond = it.getCondition();
        int flags = (it.isBroken() ? 1 : 0)
                | (it.isCooked() ? 2 : 0)
                | (it.isBurnt() ? 4 : 0);
        if (it instanceof Food food) {
            flags |= (food.isRotten() ? 8 : 0) | (food.isFresh() ? 16 : 0);
        }
        int fluidMilli = 0;
        FluidContainer fc = it.getFluidContainer();
        if (fc != null) {
            fluidMilli = Math.round(fc.getAmount() * 1000.0f);
        }
        return mixItemState(acc, id, uses, cond, flags, fluidMilli);
    }

    /**
     * 零分配比对容器当前真实内容是否与本指纹完全一致（尺寸 -> 量化重量 -> 槽位 ID -> 原位状态哈希，逐级早退）。
     */
    public boolean matchesContainer(ItemContainer container) {
        if (container == null) return false;
        ArrayList<InventoryItem> items = container.getItems();
        if (items == null) return false;
        int sz = items.size();
        if (sz != this.slotIds.length) return false;
        if (quantizeWeight(container.getContentsWeight()) != this.weightBits) return false;

        long hash = FNV_OFFSET;
        for (int i = 0; i < sz; i++) {
            InventoryItem it = items.get(i);
            int id = it == null ? 0 : it.getID();
            if (id != this.slotIds[i]) {
                return false;
            }
            hash = foldItemState(hash, it, id);
        }
        return hash == this.stateHash;
    }

    public static WarehouseFingerprint of(ItemContainer container) {
        if (container == null) return null;
        ArrayList<InventoryItem> items = container.getItems();
        if (items == null) return null;
        int sz = items.size();
        int[] ids = new int[sz];
        long hash = FNV_OFFSET;
        for (int i = 0; i < sz; i++) {
            InventoryItem it = items.get(i);
            int id = it == null ? 0 : it.getID();
            ids[i] = id;
            hash = foldItemState(hash, it, id);
        }
        return new WarehouseFingerprint(ids, container.getContentsWeight(), hash);
    }

    public boolean matches(WarehouseFingerprint other) {
        if (other == null) return false;
        return this.weightBits == other.weightBits
                && this.stateHash == other.stateHash
                && Arrays.equals(this.slotIds, other.slotIds);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WarehouseFingerprint that)) return false;
        return matches(that);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(slotIds), weightBits, stateHash);
    }
}
