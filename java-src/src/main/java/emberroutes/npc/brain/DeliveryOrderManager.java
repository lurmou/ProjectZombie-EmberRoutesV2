package emberroutes.npc.brain;

import emberroutes.warehouse.ContainerRole;
import emberroutes.warehouse.SmartObjectRegistry;
import emberroutes.warehouse.SmartSlot;
import emberroutes.warehouse.WarehouseFastIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机械动力式提货配送申请单管理器（纯内存瞬态订单队列，支撑 M3-③ AE2 提货终端与测试入口）：
 * <ul>
 *   <li>跟踪 {@code PENDING -> ASSIGNED -> COMPLETED / FALLBACK_PICKUP_OUT / CANCELLED} 状态机；</li>
 *   <li>若玩家离开据点外扩 15 格、遇丧尸威胁或背包负重满载，动态降级转存据点 {@code PICKUP_OUT} 提货箱并加 60s 保护窗。</li>
 * </ul>
 */
public final class DeliveryOrderManager {
    public static final double PLAYER_OUTSIDE_BUFFER_TILES = 15.0;
    public static final double HANDOVER_RANGE_TILES = 2.5;
    public static final double PICKUP_OUT_PROTECTION_SEC = 60.0;

    public enum OrderStatus {
        PENDING,
        ASSIGNED,
        COMPLETED,
        FALLBACK_PICKUP_OUT,
        CANCELLED
    }

    public static final class DeliveryOrder {
        public final String orderId;
        public final String settlementId;
        public final int playerIndex;
        public final String fullType;
        public final int requestedCount;
        public final double createdSimTime;
        public int deliveredCount = 0;
        public OrderStatus status = OrderStatus.PENDING;
        public String assignedNpcId;

        DeliveryOrder(
                String orderId,
                String settlementId,
                int playerIndex,
                String fullType,
                int requestedCount,
                double createdSimTime) {
            this.orderId = orderId;
            this.settlementId = settlementId;
            this.playerIndex = playerIndex;
            this.fullType = fullType;
            this.requestedCount = Math.max(1, requestedCount);
            this.createdSimTime = createdSimTime;
        }

        public int getRemainingCount() {
            return Math.max(0, requestedCount - deliveredCount);
        }
    }

    private static final Map<String, DeliveryOrder> ORDERS_BY_ID = new LinkedHashMap<>();
    private static long nextOrderSeq = 1L;

    private DeliveryOrderManager() {}

    public static synchronized void reset() {
        ORDERS_BY_ID.clear();
        nextOrderSeq = 1L;
    }

    public static synchronized void clearSettlement(String settlementId) {
        if (settlementId == null) {
            return;
        }
        ORDERS_BY_ID.values().removeIf(o -> settlementId.equals(o.settlementId));
    }

    public static synchronized String submitOrder(
            String settlementId, int playerIndex, String fullType, int count, double simTime) {
        if (settlementId == null || settlementId.isBlank() || fullType == null || fullType.isBlank() || count <= 0) {
            return null;
        }
        String orderId = "ord_" + (nextOrderSeq++);
        DeliveryOrder order = new DeliveryOrder(orderId, settlementId, playerIndex, fullType, count, simTime);
        ORDERS_BY_ID.put(orderId, order);
        SmartObjectRegistry.markAuctionDirty(settlementId);
        return orderId;
    }

    public static synchronized boolean cancelOrder(String orderId) {
        DeliveryOrder order = orderId != null ? ORDERS_BY_ID.get(orderId) : null;
        if (order == null) {
            return false;
        }
        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.FALLBACK_PICKUP_OUT) {
            return false;
        }
        order.status = OrderStatus.CANCELLED;
        order.assignedNpcId = null;
        return true;
    }

    public static synchronized DeliveryOrder getOrder(String orderId) {
        return orderId != null ? ORDERS_BY_ID.get(orderId) : null;
    }

    public static synchronized List<DeliveryOrder> getPendingOrders(String settlementId) {
        if (settlementId == null || ORDERS_BY_ID.isEmpty()) {
            return Collections.emptyList();
        }
        List<DeliveryOrder> out = new ArrayList<>();
        for (DeliveryOrder o : ORDERS_BY_ID.values()) {
            if (settlementId.equals(o.settlementId) && o.status == OrderStatus.PENDING) {
                out.add(o);
            }
        }
        return out;
    }

    public static synchronized boolean markAssigned(String orderId, String npcId) {
        DeliveryOrder o = getOrder(orderId);
        if (o == null || o.status != OrderStatus.PENDING) {
            return false;
        }
        o.status = OrderStatus.ASSIGNED;
        o.assignedNpcId = npcId;
        return true;
    }

    public static synchronized void requeueIfInterrupted(String orderId, String npcId) {
        DeliveryOrder o = getOrder(orderId);
        if (o == null || o.status != OrderStatus.ASSIGNED) {
            return;
        }
        if (npcId == null || npcId.equals(o.assignedNpcId)) {
            o.status = OrderStatus.PENDING;
            o.assignedNpcId = null;
            SmartObjectRegistry.markAuctionDirty(o.settlementId);
        }
    }

    public static synchronized void markCompleted(String orderId) {
        DeliveryOrder o = getOrder(orderId);
        if (o != null && o.status != OrderStatus.CANCELLED) {
            o.status = OrderStatus.COMPLETED;
        }
    }

    public static synchronized void markFallbackPickupOut(String orderId) {
        DeliveryOrder o = getOrder(orderId);
        if (o != null && o.status != OrderStatus.CANCELLED) {
            o.status = OrderStatus.FALLBACK_PICKUP_OUT;
        }
    }

    /** 累加单趟部分交接件数（防后续降级转存重复计件）。 */
    public static synchronized void recordPartialProgress(String orderId, int deliveredDelta) {
        applyDeliveredDelta(getOrder(orderId), deliveredDelta);
    }

    /**
     * 结算一趟配送（交接给玩家或降级存 {@code PICKUP_OUT}）：
     * 若尚未送齐整单需求，自动置回 {@code PENDING} 并标记 {@code auctionDirty} 驱动后续趟次。
     */
    public static synchronized void recordDeliveredBatch(
            String orderId, String npcId, int deliveredBatchCount, boolean fallbackPickupOut) {
        DeliveryOrder o = getOrder(orderId);
        if (o == null || o.status == OrderStatus.CANCELLED) {
            return;
        }
        applyDeliveredDelta(o, deliveredBatchCount);
        if (o.getRemainingCount() > 0) {
            if (npcId == null || npcId.equals(o.assignedNpcId) || o.assignedNpcId == null) {
                o.status = OrderStatus.PENDING;
                o.assignedNpcId = null;
                SmartObjectRegistry.markAuctionDirty(o.settlementId);
            }
            return;
        }
        o.assignedNpcId = null;
        o.status = fallbackPickupOut ? OrderStatus.FALLBACK_PICKUP_OUT : OrderStatus.COMPLETED;
    }

    private static void applyDeliveredDelta(DeliveryOrder order, int delta) {
        if (order == null || order.status == OrderStatus.CANCELLED || delta <= 0) {
            return;
        }
        order.deliveredCount = Math.min(order.requestedCount, order.deliveredCount + delta);
    }

    /** 检查提货配送是否必须动态降级转存 {@code PICKUP_OUT} 提货箱（纯数据无引擎角色依赖，支持离线 JUnit）。 */
    public static boolean shouldFallbackToPickupOut(
            String settlementId,
            double playerX,
            double playerY,
            boolean playerValid,
            boolean playerHasRoom,
            boolean threatDetected) {
        if (threatDetected || !playerValid || !playerHasRoom) {
            return true;
        }
        double distFromTerritory = WarehouseFastIndex.distanceToSettlement2D(
                settlementId, playerX, playerY);
        return distFromTerritory > PLAYER_OUTSIDE_BUFFER_TILES;
    }

    /** 寻找据点内最近且容量充足的 {@code PICKUP_OUT} 提货交接箱。 */
    public static SmartSlot findBestPickupOutSlot(
            String settlementId, double requiredWeight, float fromX, float fromY, int fromZ, double simTime) {
        if (settlementId == null) {
            return null;
        }
        List<SmartSlot> containers = SmartObjectRegistry.getActiveContainerSlots(settlementId);
        SmartSlot best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0, n = containers.size(); i < n; i++) {
            SmartSlot slot = containers.get(i);
            if (!slot.isActive()
                    || slot.getContainerRole() != ContainerRole.PICKUP_OUT
                    || slot.isFullCooldownAt(simTime)
                    || slot.getFreeInWeight() + 1.0E-4 < requiredWeight) {
                continue;
            }
            double dx = slot.originX - fromX;
            double dy = slot.originY - fromY;
            double dz = Math.abs(slot.originZ - fromZ) * 10.0;
            double dSq = dx * dx + dy * dy + dz * dz;
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = slot;
            }
        }
        return best;
    }

    public static synchronized String getSummary(String settlementId) {
        int pending = 0;
        int assigned = 0;
        int done = 0;
        int fallback = 0;
        for (DeliveryOrder o : ORDERS_BY_ID.values()) {
            if (settlementId != null && !settlementId.equals(o.settlementId)) {
                continue;
            }
            switch (o.status) {
                case PENDING -> pending++;
                case ASSIGNED -> assigned++;
                case COMPLETED -> done++;
                case FALLBACK_PICKUP_OUT -> fallback++;
                case CANCELLED -> {}
            }
        }
        return String.format("orders(pending=%d,assigned=%d,done=%d,pickupOut=%d)", pending, assigned, done, fallback);
    }
}
