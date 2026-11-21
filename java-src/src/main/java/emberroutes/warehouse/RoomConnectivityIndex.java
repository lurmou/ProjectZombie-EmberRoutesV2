package emberroutes.warehouse;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;

/**
 * 同层相邻 {@code roomId} 门连通邻接表：
 * <ul>
 *   <li>在 {@link WarehouseFastIndex} / {@link SmartObjectRegistry} 扫格时零轮询记录门/门框/开口两侧 {@code roomId}；</li>
 *   <li>供 {@code SettlementTaskAuctioneer} 计算 {@link RoomTransition} 隔墙修正惩罚（+0s / +2.5s / +9.0s / +12.0s/层）。</li>
 * </ul>
 */
public final class RoomConnectivityIndex {
    public enum RoomTransition {
        SAME_ROOM,
        DIRECT_DOOR,
        INDIRECT_DETOUR,
        CROSS_FLOOR
    }

    /** 缺失或尚未采样房间号时的哨兵值（不做无门绕行误罚）；室外为 {@code -1L}（对齐原版 {@code IsoGridSquare.getRoomID()}）。 */
    public static final long UNKNOWN_ROOM_ID = Long.MIN_VALUE;

    private static final int[] CARDINAL_DX = {1, -1, 0, 0};
    private static final int[] CARDINAL_DY = {0, 0, 1, -1};

    private static final Map<String, Map<Integer, Map<Long, Set<Long>>>> ADJACENCY_BY_SETTLEMENT = new HashMap<>();

    private RoomConnectivityIndex() {}

    public static synchronized void reset() {
        ADJACENCY_BY_SETTLEMENT.clear();
    }

    public static synchronized void invalidateSettlement(String settlementId) {
        if (settlementId != null) {
            ADJACENCY_BY_SETTLEMENT.remove(settlementId);
        }
    }

    public static synchronized void recordDoorEdge(String settlementId, int z, long roomA, long roomB) {
        if (settlementId == null || roomA == UNKNOWN_ROOM_ID || roomB == UNKNOWN_ROOM_ID || roomA == roomB) {
            return;
        }
        Map<Integer, Map<Long, Set<Long>>> byFloor =
                ADJACENCY_BY_SETTLEMENT.computeIfAbsent(settlementId, k -> new HashMap<>());
        Map<Long, Set<Long>> adj = byFloor.computeIfAbsent(Integer.valueOf(z), k -> new HashMap<>());
        adj.computeIfAbsent(Long.valueOf(roomA), k -> new HashSet<>(4)).add(Long.valueOf(roomB));
        adj.computeIfAbsent(Long.valueOf(roomB), k -> new HashSet<>(4)).add(Long.valueOf(roomA));
    }

    /** 扫描单格四向邻接门/门框/连通开口，自动登记跨房间无向直通边。 */
    public static void scanSquareDoors(String settlementId, IsoGridSquare sq) {
        if (settlementId == null || sq == null) {
            return;
        }
        IsoCell cell = sq.getCell();
        if (cell == null) {
            return;
        }
        long roomA = sq.getRoomID();
        int x = sq.getX();
        int y = sq.getY();
        int z = sq.getZ();
        for (int i = 0; i < 4; i++) {
            IsoGridSquare nb = cell.getGridSquare(x + CARDINAL_DX[i], y + CARDINAL_DY[i], z);
            if (nb == null) {
                continue;
            }
            long roomB = nb.getRoomID();
            if (roomA == roomB) {
                continue;
            }
            boolean hasPortal = sq.getDoorTo(nb) != null
                    || sq.isDoorTo(nb)
                    || (!sq.isWallTo(nb) && !sq.isWindowTo(nb) && !sq.isWindowBlockedTo(nb) && !sq.isStairBlockedTo(nb));
            if (hasPortal) {
                recordDoorEdge(settlementId, z, roomA, roomB);
            }
        }
    }

    public static synchronized boolean hasDirectDoor(String settlementId, int z, long roomA, long roomB) {
        if (settlementId == null || roomA == UNKNOWN_ROOM_ID || roomB == UNKNOWN_ROOM_ID) {
            return false;
        }
        Map<Integer, Map<Long, Set<Long>>> byFloor = ADJACENCY_BY_SETTLEMENT.get(settlementId);
        if (byFloor == null) {
            return false;
        }
        Map<Long, Set<Long>> adj = byFloor.get(Integer.valueOf(z));
        if (adj == null) {
            return false;
        }
        Set<Long> neighbors = adj.get(Long.valueOf(roomA));
        return neighbors != null && neighbors.contains(Long.valueOf(roomB));
    }

    public static RoomTransition classify(String settlementId, int zA, long roomA, int zB, long roomB) {
        if (zA != zB) {
            return RoomTransition.CROSS_FLOOR;
        }
        if (roomA == UNKNOWN_ROOM_ID || roomB == UNKNOWN_ROOM_ID || roomA == roomB) {
            return RoomTransition.SAME_ROOM;
        }
        return hasDirectDoor(settlementId, zA, roomA, roomB)
                ? RoomTransition.DIRECT_DOOR
                : RoomTransition.INDIRECT_DETOUR;
    }
}
