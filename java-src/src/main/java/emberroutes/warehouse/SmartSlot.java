package emberroutes.warehouse;

import java.util.ArrayList;
import java.util.List;

/**
 * UE5 SmartObject 逆向广播插槽模型与租约句柄：
 * <ul>
 *   <li>多格家具（{@code IsoSpriteGrid != null}）严格以网格原点主格 {@code (gridPosX==0, gridPosY==0)} 为唯一键；</li>
 *   <li>仅当全子格位掩码 {@code loadedMask == fullMask} 时激活，任一子格卸载即整体下架并置租约 {@code INVALIDATED}；</li>
 *   <li>容器插槽追加物流角色（{@link ContainerRole}）、8 方位可达掩码、房间 ID、软预约账本与 {@code BUFFER_IN} 侵入式广播下标。</li>
 * </ul>
 */
public final class SmartSlot {
    public enum SlotKind {
        BED,
        SEAT,
        CONTAINER,
        WORKSTATION
    }

    public enum LeaseStatus {
        ACTIVE,
        RELEASED,
        INVALIDATED
    }

    public enum LeaseDirection {
        OUTBOUND,
        INBOUND
    }

    /** 绑定进 {@code StateScope} 的 SmartSlot 逻辑预约句柄（家具单占锁）。 */
    public static final class SmartSlotLease {
        private final SmartSlot slot;
        private final long leaseToken;
        private LeaseStatus status = LeaseStatus.ACTIVE;

        SmartSlotLease(SmartSlot slot, long leaseToken) {
            this.slot = slot;
            this.leaseToken = leaseToken;
        }

        public SmartSlot getSlot() {
            return slot;
        }

        public String getSlotId() {
            return slot.slotId;
        }

        public long getLeaseToken() {
            return leaseToken;
        }

        public LeaseStatus getStatus() {
            return status;
        }

        void setStatus(LeaseStatus status) {
            this.status = status;
        }

        public boolean isValid() {
            return status == LeaseStatus.ACTIVE
                    && slot.isActive()
                    && slot.getLeaseToken() == leaseToken;
        }
    }

    /** 绑定进 {@code StateScope} 的容器物流软预约句柄（出库件数锁 / 入库预留负重锁）。 */
    public static final class ContainerLease {
        private final SmartSlot slot;
        private final long leaseToken;
        private final String npcId;
        private final LeaseDirection direction;
        private final int reservedItems;
        private final double reservedWeight;
        private final double expiresSimTime;
        private LeaseStatus status = LeaseStatus.ACTIVE;

        ContainerLease(
                SmartSlot slot,
                long leaseToken,
                String npcId,
                LeaseDirection direction,
                int reservedItems,
                double reservedWeight,
                double expiresSimTime) {
            this.slot = slot;
            this.leaseToken = leaseToken;
            this.npcId = npcId;
            this.direction = direction;
            this.reservedItems = Math.max(0, reservedItems);
            this.reservedWeight = Math.max(0.0, reservedWeight);
            this.expiresSimTime = expiresSimTime;
        }

        public SmartSlot getSlot() {
            return slot;
        }

        public String getSlotId() {
            return slot.slotId;
        }

        public long getLeaseToken() {
            return leaseToken;
        }

        public String getNpcId() {
            return npcId;
        }

        public LeaseDirection getDirection() {
            return direction;
        }

        public int getReservedItems() {
            return reservedItems;
        }

        public double getReservedWeight() {
            return reservedWeight;
        }

        public double getExpiresSimTime() {
            return expiresSimTime;
        }

        public LeaseStatus getStatus() {
            return status;
        }

        void setStatus(LeaseStatus status) {
            this.status = status;
        }

        public boolean isExpiredAt(double simTime) {
            return expiresSimTime > 0.0 && simTime >= expiresSimTime;
        }

        public boolean isValid() {
            return status == LeaseStatus.ACTIVE && slot.isActive();
        }
    }

    public final String slotId;
    public final String settlementId;
    public final SlotKind kind;
    public final int originX;
    public final int originY;
    public final int originZ;
    public final int gridW;
    public final int gridH;
    public final long fullMask;

    long loadedMask;
    boolean active;
    String occupiedByNpcId;
    long leaseToken;
    double leaseExpiresSimTime;
    SmartSlotLease activeLease;

    // 物流/工位扩展状态（纯内存瞬态；过滤规则与角色持久化于 IsoObject ModData）
    String containerId;
    ContainerRole containerRole = ContainerRole.STORAGE;
    int priority = SmartZoningPolicy.DEFAULT_PRIORITY;
    boolean filterEnabled = true;
    int standDirMask;
    final double[] standDirBackoffUntil = new double[8];
    long roomId = RoomConnectivityIndex.UNKNOWN_ROOM_ID;
    int availableItemCount;
    double capacity;
    double usedWeight;
    int reservedOutItemCount;
    double reservedInWeight;
    double withdrawnUntilSimTime;
    double fullCooldownUntilSimTime;
    int advertisedHaulOutListIdx = -1;
    SmartZoningPolicy.ContainerFilterRule filterRule = new SmartZoningPolicy.ContainerFilterRule();
    final List<ContainerLease> containerLeases = new ArrayList<>(2);

    SmartSlot(
            String slotId,
            String settlementId,
            SlotKind kind,
            int originX,
            int originY,
            int originZ,
            int gridW,
            int gridH) {
        this.slotId = slotId;
        this.settlementId = settlementId;
        this.kind = kind;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.gridW = Math.max(1, gridW);
        this.gridH = Math.max(1, gridH);
        int cellCount = Math.min(63, this.gridW * this.gridH);
        this.fullMask = cellCount >= 63 ? Long.MAX_VALUE : ((1L << cellCount) - 1L);
    }

    public String getSlotId() {
        return slotId;
    }

    public String getSettlementId() {
        return settlementId;
    }

    public SlotKind getKind() {
        return kind;
    }

    public int getOriginX() {
        return originX;
    }

    public int getOriginY() {
        return originY;
    }

    public int getOriginZ() {
        return originZ;
    }

    public int getGridW() {
        return gridW;
    }

    public int getGridH() {
        return gridH;
    }

    public long getLoadedMask() {
        return loadedMask;
    }

    public long getFullMask() {
        return fullMask;
    }

    public boolean isActive() {
        return active;
    }

    public String getOccupiedByNpcId() {
        return occupiedByNpcId;
    }

    public long getLeaseToken() {
        return leaseToken;
    }

    public boolean isOccupiedAt(double simTime) {
        return occupiedByNpcId != null && (leaseExpiresSimTime <= 0.0 || simTime < leaseExpiresSimTime);
    }

    public String getContainerId() {
        return containerId;
    }

    public ContainerRole getContainerRole() {
        return containerRole;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isFilterEnabled() {
        return filterEnabled;
    }

    public int getStandDirMask() {
        return standDirMask;
    }

    public long getRoomId() {
        return roomId;
    }

    public int getAvailableItemCount() {
        return availableItemCount;
    }

    public double getCapacity() {
        return capacity;
    }

    public double getUsedWeight() {
        return usedWeight;
    }

    public int getReservedOutItemCount() {
        return reservedOutItemCount;
    }

    public double getReservedInWeight() {
        return reservedInWeight;
    }

    public int getUnreservedOutItemCount() {
        return Math.max(0, availableItemCount - reservedOutItemCount);
    }

    public double getFreeInWeight() {
        return Math.max(0.0, capacity - usedWeight - reservedInWeight);
    }

    public double getWithdrawnUntilSimTime() {
        return withdrawnUntilSimTime;
    }

    public boolean isWithdrawnAt(double simTime) {
        return withdrawnUntilSimTime > 0.0 && simTime < withdrawnUntilSimTime;
    }

    public double getFullCooldownUntilSimTime() {
        return fullCooldownUntilSimTime;
    }

    public boolean isFullCooldownAt(double simTime) {
        return fullCooldownUntilSimTime > 0.0 && simTime < fullCooldownUntilSimTime;
    }

    public int getAdvertisedHaulOutListIdx() {
        return advertisedHaulOutListIdx;
    }

    public SmartZoningPolicy.ContainerFilterRule getFilterRule() {
        return filterRule;
    }

    public boolean isStandDirBackedOff(int dirIdx, double simTime) {
        if (dirIdx < 0 || dirIdx >= 8) {
            return false;
        }
        return standDirBackoffUntil[dirIdx] > 0.0 && simTime < standDirBackoffUntil[dirIdx];
    }
}
