package emberroutes.npc.brain;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 纯内存车辆座位与登车入口原子预约表（纯 Java，无 {@code zombie.*} 依赖，支持 {@code StateScope.defer} 自动释放）。
 * <ul>
 *   <li>硬性保留 {@code seat == 0} 给人类驾驶员，绝不将驾驶位分配给任何 NPC；</li>
 *   <li>对无外门的后排深座启用「{@code finalSeat + transitEntrySeat} 双锁」，先让早到的 NPC 占住深座并在门口中转位换座进去后立即释放门口锁；</li>
 *   <li>当仅剩深座空缺且门口中转座已被其他在车随从占住时，向在车随从下发 1 跳让道请求（{@link #consumeDoorwayShiftTarget(String)}），消除门口堵死。</li>
 * </ul>
 */
public final class VehicleSeatRegistry {
    public record SeatAllocation(
            int finalSeat,
            int transitEntrySeat,
            boolean shiftRequested,
            String shiftBlockerNpcId,
            int shiftTargetSeat) {}

    private static final class Reservation {
        final String npcId;
        final WeakReference<Object> vehicleRef;
        int finalSeat;
        int transitEntrySeat;

        Reservation(String npcId, Object vehicle, int finalSeat, int transitEntrySeat) {
            this.npcId = npcId;
            this.vehicleRef = new WeakReference<>(vehicle);
            this.finalSeat = finalSeat;
            this.transitEntrySeat = transitEntrySeat;
        }
    }

    private static final class ShiftOrder {
        final WeakReference<Object> vehicleRef;
        final int targetSeat;

        ShiftOrder(Object vehicle, int targetSeat) {
            this.vehicleRef = new WeakReference<>(vehicle);
            this.targetSeat = targetSeat;
        }
    }

    private static final Map<String, Reservation> BY_NPC = new HashMap<>();
    private static final Map<String, ShiftOrder> PENDING_SHIFTS = new HashMap<>();

    private VehicleSeatRegistry() {}

    public static synchronized void reset() {
        BY_NPC.clear();
        PENDING_SHIFTS.clear();
    }

    private static void pruneDeadLocked() {
        Iterator<Map.Entry<String, Reservation>> it = BY_NPC.entrySet().iterator();
        while (it.hasNext()) {
            Reservation r = it.next().getValue();
            if (r.vehicleRef.get() == null) {
                it.remove();
            }
        }
        Iterator<Map.Entry<String, ShiftOrder>> sit = PENDING_SHIFTS.entrySet().iterator();
        while (sit.hasNext()) {
            ShiftOrder o = sit.next().getValue();
            if (o.vehicleRef.get() == null) {
                sit.remove();
            }
        }
    }

    public static synchronized void releaseForNpc(String npcId) {
        if (npcId == null || npcId.isEmpty()) {
            return;
        }
        BY_NPC.remove(npcId);
        PENDING_SHIFTS.remove(npcId);
    }

    public static synchronized void releaseTransitSeat(String npcId) {
        if (npcId == null || npcId.isEmpty()) {
            return;
        }
        Reservation r = BY_NPC.get(npcId);
        if (r != null) {
            r.transitEntrySeat = r.finalSeat;
        }
    }

    public static synchronized boolean isSeatReservedByOther(Object vehicle, int seat, String selfNpcId) {
        if (vehicle == null || seat < 0) {
            return false;
        }
        pruneDeadLocked();
        for (Reservation r : BY_NPC.values()) {
            if (r.vehicleRef.get() != vehicle) {
                continue;
            }
            if (selfNpcId != null && selfNpcId.equals(r.npcId)) {
                continue;
            }
            if (r.finalSeat == seat || r.transitEntrySeat == seat) {
                return true;
            }
        }
        return false;
    }

    public static synchronized int getReservedFinalSeat(Object vehicle, String npcId) {
        if (vehicle == null || npcId == null) {
            return -1;
        }
        Reservation r = BY_NPC.get(npcId);
        if (r != null && r.vehicleRef.get() == vehicle) {
            return r.finalSeat;
        }
        return -1;
    }

    public static synchronized int consumeDoorwayShiftTarget(Object vehicle, String seatedNpcId) {
        if (vehicle == null || seatedNpcId == null) {
            return -1;
        }
        ShiftOrder order = PENDING_SHIFTS.get(seatedNpcId);
        if (order == null) {
            return -1;
        }
        PENDING_SHIFTS.remove(seatedNpcId);
        if (order.vehicleRef.get() != vehicle) {
            return -1;
        }
        return order.targetSeat;
    }

    /**
     * 快速查询指定车辆是否存在可分配给 {@code npcId} 的非驾驶位空座（含中转深座或门口让道情形）。
     */
    public static synchronized boolean hasCandidateSeat(
            Object vehicle,
            String npcId,
            int maxPassengers,
            boolean[] seatInstalled,
            boolean[] seatOccupied,
            String[] seatedNpcIds,
            boolean[] directEntryClear,
            boolean[][] canSwitch) {
        if (vehicle == null || npcId == null || maxPassengers <= 1) {
            return false;
        }
        pruneDeadLocked();
        Reservation existing = BY_NPC.get(npcId);
        if (existing != null && existing.vehicleRef.get() == vehicle) {
            int fs = existing.finalSeat;
            if (fs >= 1 && fs < maxPassengers && seatInstalled[fs] && !seatOccupied[fs]) {
                return true;
            }
        }

        for (int s = 1; s < maxPassengers; s++) {
            if (!seatInstalled[s] || seatOccupied[s] || isSeatReservedByOther(vehicle, s, npcId)) {
                continue;
            }
            if (directEntryClear[s]) {
                return true;
            }
            for (int e = 1; e < maxPassengers; e++) {
                if (e == s || !seatInstalled[e] || !directEntryClear[e] || !canSwitch[e][s]) {
                    continue;
                }
                if (!isSeatReservedByOther(vehicle, e, npcId) && !seatOccupied[e]) {
                    return true;
                }
                if (seatedNpcIds != null && seatedNpcIds[e] != null && !seatedNpcIds[e].isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 为 {@code npcId} 原子锁定最佳乘车目标座 {@code finalSeat} 与登车中转座 {@code transitEntrySeat}。
     */
    public static synchronized SeatAllocation chooseAndReserve(
            Object vehicle,
            String npcId,
            int maxPassengers,
            boolean[] seatInstalled,
            boolean[] seatOccupied,
            String[] seatedNpcIds,
            boolean[] directEntryClear,
            boolean[][] canSwitch,
            float[] doorDistSq) {
        if (vehicle == null || npcId == null || npcId.isEmpty() || maxPassengers <= 1) {
            return null;
        }
        pruneDeadLocked();

        Reservation existing = BY_NPC.get(npcId);
        if (existing != null && existing.vehicleRef.get() == vehicle) {
            int fs = existing.finalSeat;
            int ts = existing.transitEntrySeat;
            if (fs >= 1 && fs < maxPassengers && seatInstalled[fs]
                    && (!seatOccupied[fs] || npcId.equals(seatedNpcIds != null ? seatedNpcIds[fs] : null))) {
                if (ts == fs || (ts >= 1 && ts < maxPassengers && !seatOccupied[ts]
                        && !isSeatReservedByOther(vehicle, ts, npcId))) {
                    return new SeatAllocation(fs, ts, false, null, -1);
                }
                // 中转门临时被占时重新寻门但保留深座锁
                int altEntry = findOpenDirectEntryFor(vehicle, npcId, maxPassengers, fs, seatInstalled, seatOccupied, directEntryClear, canSwitch, doorDistSq);
                if (altEntry >= 1) {
                    existing.transitEntrySeat = altEntry;
                    return new SeatAllocation(fs, altEntry, false, null, -1);
                }
            }
            BY_NPC.remove(npcId);
        } else if (existing != null) {
            BY_NPC.remove(npcId);
        }

        // Pass 1：深座优先（无直通外门但可经由空置直接门切换进去），防止早来随从坐死门口堵住后排
        int bestDeepFinal = -1;
        int bestDeepEntry = -1;
        float bestDeepScore = Float.POSITIVE_INFINITY;
        for (int s = 1; s < maxPassengers; s++) {
            if (!seatInstalled[s] || seatOccupied[s] || directEntryClear[s] || isSeatReservedByOther(vehicle, s, npcId)) {
                continue;
            }
            int entry = findOpenDirectEntryFor(vehicle, npcId, maxPassengers, s, seatInstalled, seatOccupied, directEntryClear, canSwitch, doorDistSq);
            if (entry >= 1) {
                float d = (doorDistSq != null && entry < doorDistSq.length) ? doorDistSq[entry] : 0.0F;
                if (d < bestDeepScore) {
                    bestDeepScore = d;
                    bestDeepFinal = s;
                    bestDeepEntry = entry;
                }
            }
        }
        if (bestDeepFinal >= 1) {
            BY_NPC.put(npcId, new Reservation(npcId, vehicle, bestDeepFinal, bestDeepEntry));
            return new SeatAllocation(bestDeepFinal, bestDeepEntry, false, null, -1);
        }

        // Pass 2：直通外门座位（无外门堵门则一步入座）
        int bestDirect = -1;
        float bestDirectScore = Float.POSITIVE_INFINITY;
        for (int s = 1; s < maxPassengers; s++) {
            if (!seatInstalled[s] || seatOccupied[s] || !directEntryClear[s] || isSeatReservedByOther(vehicle, s, npcId)) {
                continue;
            }
            float d = (doorDistSq != null && s < doorDistSq.length) ? doorDistSq[s] : 0.0F;
            if (d < bestDirectScore) {
                bestDirectScore = d;
                bestDirect = s;
            }
        }
        if (bestDirect >= 1) {
            BY_NPC.put(npcId, new Reservation(npcId, vehicle, bestDirect, bestDirect));
            return new SeatAllocation(bestDirect, bestDirect, false, null, -1);
        }

        // Pass 3：门口已被其他随从坐住而深座仍空时，通知门口随从向深座挪移 1 跳让出通道
        for (int s = 1; s < maxPassengers; s++) {
            if (!seatInstalled[s] || seatOccupied[s]
                    || isSeatReservedByOther(vehicle, s, npcId)
                    || isDeepSeatTargetedByShift(vehicle, s)) {
                continue;
            }
            for (int e = 1; e < maxPassengers; e++) {
                if (e == s || !seatInstalled[e] || !directEntryClear[e] || !canSwitch[e][s]) {
                    continue;
                }
                String occupant = (seatedNpcIds != null && e < seatedNpcIds.length) ? seatedNpcIds[e] : null;
                if (occupant != null && !occupant.isEmpty() && !occupant.equals(npcId) && !PENDING_SHIFTS.containsKey(occupant)) {
                    PENDING_SHIFTS.put(occupant, new ShiftOrder(vehicle, s));
                    return new SeatAllocation(-1, -1, true, occupant, s);
                }
            }
        }
        return null;
    }

    private static boolean isDeepSeatTargetedByShift(Object vehicle, int seat) {
        for (ShiftOrder order : PENDING_SHIFTS.values()) {
            if (order.vehicleRef.get() == vehicle && order.targetSeat == seat) {
                return true;
            }
        }
        return false;
    }

    private static int findOpenDirectEntryFor(
            Object vehicle,
            String npcId,
            int maxPassengers,
            int targetSeat,
            boolean[] seatInstalled,
            boolean[] seatOccupied,
            boolean[] directEntryClear,
            boolean[][] canSwitch,
            float[] doorDistSq) {
        int bestEntry = -1;
        float bestDist = Float.POSITIVE_INFINITY;
        for (int e = 1; e < maxPassengers; e++) {
            if (e == targetSeat || !seatInstalled[e] || seatOccupied[e]
                    || !directEntryClear[e] || !canSwitch[e][targetSeat]
                    || isSeatReservedByOther(vehicle, e, npcId)) {
                continue;
            }
            float d = (doorDistSq != null && e < doorDistSq.length) ? doorDistSq[e] : 0.0F;
            if (d < bestDist) {
                bestDist = d;
                bestEntry = e;
            }
        }
        return bestEntry;
    }

    /** 清理已失活或指定载具上的全部预约（仅供卸载看门狗与单测核验）。 */
    public static synchronized List<String> listReservedNpcsOnVehicle(Object vehicle) {
        List<String> out = new ArrayList<>();
        if (vehicle == null) {
            return out;
        }
        pruneDeadLocked();
        for (Reservation r : BY_NPC.values()) {
            if (r.vehicleRef.get() == vehicle) {
                out.add(r.npcId);
            }
        }
        return out;
    }
}
