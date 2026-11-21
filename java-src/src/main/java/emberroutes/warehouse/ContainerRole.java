package emberroutes.warehouse;

import java.util.Locale;

/**
 * 据点容器三大物流角色（M3-② 智能分拣与物流底座）：
 * <ul>
 *   <li>{@link #STORAGE} — 常规分类储物仓（按 {@code SmartZoning} 规则接纳理货入库与供货，含高优先级工坊备料架）；</li>
 *   <li>{@link #BUFFER_IN} — 卸货缓冲箱（内有未加锁可用物品时 $O(1)$ 自动加入 {@code advertisedHaulOutSlots} 触发理货拍卖）；</li>
 *   <li>{@link #PICKUP_OUT} — 提货交接箱（免自动理货吸回，兼作提货单降级投放点）。</li>
 * </ul>
 */
public enum ContainerRole {
    STORAGE,
    BUFFER_IN,
    PICKUP_OUT;

    public static ContainerRole fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return STORAGE;
        }
        String norm = raw.trim().toUpperCase(Locale.ROOT);
        for (ContainerRole role : values()) {
            if (role.name().equals(norm)) {
                return role;
            }
        }
        return STORAGE;
    }
}
