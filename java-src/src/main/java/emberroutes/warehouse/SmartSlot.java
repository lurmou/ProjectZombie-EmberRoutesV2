package emberroutes.warehouse;

/**
 * UE5 SmartObject 逆向广播插槽模型与租约句柄：
 * <ul>
 *   <li>多格家具（{@code IsoSpriteGrid != null}）严格以网格原点主格 {@code (gridPosX==0, gridPosY==0)} 为唯一键；</li>
 *   <li>仅当全子格位掩码 {@code loadedMask == fullMask} 时激活，任一子格卸载即整体下架并置租约 {@code INVALIDATED}。</li>
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

    /** 绑定进 {@code StateScope} 的 SmartSlot 逻辑预约句柄。 */
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
}
