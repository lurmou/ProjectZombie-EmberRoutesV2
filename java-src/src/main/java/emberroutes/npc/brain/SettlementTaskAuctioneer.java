package emberroutes.npc.brain;

import emberroutes.npc.EmberFormationMatcher;
import emberroutes.npc.EmberFormationMatcher.RectangularHungarianWorkspace;
import emberroutes.warehouse.RoomConnectivityIndex.RoomTransition;
import emberroutes.warehouse.SmartObjectRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Factorio 2.0 / ONI 边际成本组合拍卖器与 Lookahead 零空驶接力调度器（纯 Java 0-GC 稳态）：
 * <ul>
 *   <li>由 {@code auctionDirty} + {@code 250ms} 最短冷却窗（或空闲/进 2.5s 完工窗边缘触发）联合门控；</li>
 *   <li>竞标池同时纳入空闲 NPC（{@code T_wait = 0}）与 {@code remainingEtaSec <= 2.5s} 的在途 Lookahead NPC（锚点取终点站位）；</li>
 *   <li>统一按“等效秒”构造 {@code M x K} 成本矩阵并调用 {@link EmberFormationMatcher#solveRectangularHungarian}。</li>
 * </ul>
 */
public final class SettlementTaskAuctioneer {
    public static final double MIN_AUCTION_COOLDOWN_SEC = 0.25;
    public static final double LOOKAHEAD_WINDOW_SEC = 2.5;
    public static final double CHAINED_PRELOCK_TTL_SEC = 6.0;

    public static final double WALK_SPEED_TILES_PER_SEC = 1.8;
    public static final double ROOM_SAME_PENALTY_SEC = 0.0;
    public static final double ROOM_DIRECT_DOOR_PENALTY_SEC = 2.5;
    public static final double ROOM_INDIRECT_DETOUR_PENALTY_SEC = 9.0;
    public static final double CROSS_FLOOR_PENALTY_SEC = 12.0;

    public static final double SPECIALIST_HAUL_OPPORTUNITY_COST_SEC = 8.0;
    public static final double CHAIN_BONUS_RADIUS_TILES = 4.0;
    public static final double CHAIN_BONUS_SEC = 6.0;

    public static final double AGING_RATE_PER_SEC = 0.30;
    public static final double AGING_MAX_SEC = 35.0;
    public static final double DELIVERY_URGENCY_BONUS_SEC = 15.0;
    public static final double COST_OFFSET_SEC = 50.0;

    public enum TaskKind {
        HAUL_BATCH,
        DELIVERY_ORDER
    }

    public static final class TaskSlice {
        public final String taskId;
        public final TaskKind kind;
        public final String settlementId;
        public final String sourceSlotId;
        public final int x;
        public final int y;
        public final int z;
        public final long roomId;
        public final double createdSimTime;
        public final double estimatedExecSec;
        public final String deliveryOrderId;

        public TaskSlice(
                String taskId,
                TaskKind kind,
                String settlementId,
                String sourceSlotId,
                int x,
                int y,
                int z,
                long roomId,
                double createdSimTime,
                double estimatedExecSec,
                String deliveryOrderId) {
            this.taskId = taskId;
            this.kind = kind;
            this.settlementId = settlementId;
            this.sourceSlotId = sourceSlotId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.roomId = roomId;
            this.createdSimTime = createdSimTime;
            this.estimatedExecSec = Math.max(0.5, estimatedExecSec);
            this.deliveryOrderId = deliveryOrderId;
        }
    }

    public static final class Bidder {
        public final String npcId;
        public final double anchorX;
        public final double anchorY;
        public final int anchorZ;
        public final long anchorRoomId;
        public final double waitSec;
        public final double skillSpeedMult;
        public final boolean specialist;
        public final boolean lookahead;

        public Bidder(
                String npcId,
                double anchorX,
                double anchorY,
                int anchorZ,
                long anchorRoomId,
                double waitSec,
                double skillSpeedMult,
                boolean specialist,
                boolean lookahead) {
            this.npcId = npcId;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.anchorZ = anchorZ;
            this.anchorRoomId = anchorRoomId;
            this.waitSec = Math.max(0.0, waitSec);
            this.skillSpeedMult = Math.max(0.25, skillSpeedMult);
            this.specialist = specialist;
            this.lookahead = lookahead;
        }
    }

    public record Assignment(Bidder bidder, TaskSlice task, double marginalCostSec) {}

    /** 是否属于可参与派单竞拍的稳定姿势（允许站立及闲暇坐椅接单，仅屏蔽睡眠/入床/起立过渡态）。 */
    public static boolean isBidderPostureEligible(String postureStateName) {
        return "STANDING".equals(postureStateName) || "SEATED".equals(postureStateName);
    }

    private final RectangularHungarianWorkspace workspace = new RectangularHungarianWorkspace(32);
    private final Map<String, Double> lastAuctionSimTimeBySettlement = new HashMap<>();
    private double[][] costBuffer = new double[16][16];
    private int[] rowToColBuffer = new int[16];

    public void reset() {
        lastAuctionSimTimeBySettlement.clear();
    }

    public void invalidateSettlement(String settlementId) {
        if (settlementId != null) {
            lastAuctionSimTimeBySettlement.remove(settlementId);
        }
    }

    /** 检查是否满足 {@code auctionDirty} / 边缘触发 + {@code 250ms} 最短冷却门控。 */
    public boolean canTriggerAuction(
            String settlementId, double simTime, boolean auctionDirty, boolean hasEdgeTrigger) {
        if (settlementId == null || (!auctionDirty && !hasEdgeTrigger)) {
            return false;
        }
        Double prev = lastAuctionSimTimeBySettlement.get(settlementId);
        return prev == null || (simTime - prev.doubleValue()) >= MIN_AUCTION_COOLDOWN_SEC;
    }

    public void markAuctionExecuted(String settlementId, double simTime) {
        if (settlementId != null) {
            lastAuctionSimTimeBySettlement.put(settlementId, Double.valueOf(simTime));
        }
    }

    /**
     * 计算单名竞标者对单切片任务的统一“等效秒”边际成本：
     * {@code C(i, j) = T_wait + T_travel + T_exec - B_chain - B_urgency + C_offset}。
     */
    public static double computeMarginalCost(
            Bidder bidder, TaskSlice task, RoomTransition transition, double simTime) {
        if (bidder == null || task == null) {
            return EmberFormationMatcher.DUMMY_COST;
        }
        if (bidder.lookahead && task.kind == TaskKind.DELIVERY_ORDER) {
            return EmberFormationMatcher.DUMMY_COST;
        }
        double dist2D = Math.hypot(task.x + 0.5 - bidder.anchorX, task.y + 0.5 - bidder.anchorY);
        double floorPenalty = 0.0;
        RoomTransition effectiveTrans = transition;
        if (bidder.anchorZ != task.z) {
            effectiveTrans = RoomTransition.CROSS_FLOOR;
        }
        if (effectiveTrans == RoomTransition.CROSS_FLOOR) {
            int floorDiff = Math.max(1, Math.abs(task.z - bidder.anchorZ));
            floorPenalty = CROSS_FLOOR_PENALTY_SEC * floorDiff;
        } else if (effectiveTrans == RoomTransition.DIRECT_DOOR) {
            floorPenalty = ROOM_DIRECT_DOOR_PENALTY_SEC;
        } else if (effectiveTrans == RoomTransition.INDIRECT_DETOUR) {
            floorPenalty = ROOM_INDIRECT_DETOUR_PENALTY_SEC;
        } else {
            floorPenalty = ROOM_SAME_PENALTY_SEC;
        }

        double tWait = bidder.waitSec;
        double tTravel = (dist2D / WALK_SPEED_TILES_PER_SEC) + floorPenalty;
        double tExec = task.estimatedExecSec / Math.max(0.25, bidder.skillSpeedMult);
        if (bidder.specialist && task.kind == TaskKind.HAUL_BATCH) {
            tExec += SPECIALIST_HAUL_OPPORTUNITY_COST_SEC;
        }

        double bChain = (bidder.anchorZ == task.z && dist2D <= CHAIN_BONUS_RADIUS_TILES)
                ? CHAIN_BONUS_SEC
                : 0.0;

        double waitAgeSec = Math.max(0.0, simTime - task.createdSimTime);
        double agingBonus = Math.min(AGING_MAX_SEC, waitAgeSec * AGING_RATE_PER_SEC);
        double roleUrgency = (task.kind == TaskKind.DELIVERY_ORDER) ? DELIVERY_URGENCY_BONUS_SEC : 0.0;
        double bUrgency = agingBonus + roleUrgency;

        double cost = tWait + tTravel + tExec - bChain - bUrgency + COST_OFFSET_SEC;
        return Math.max(0.01, cost);
    }

    /** 执行一次矩形匈牙利全局组合拍卖，把结果写入调用方提供的预分配 {@code outAssignments} 列表。 */
    public int runAuction(
            String settlementId,
            List<Bidder> bidders,
            List<TaskSlice> tasks,
            double simTime,
            List<Assignment> outAssignments) {
        if (outAssignments == null) {
            return 0;
        }
        outAssignments.clear();
        if (settlementId == null || bidders == null || tasks == null || bidders.isEmpty() || tasks.isEmpty()) {
            return 0;
        }

        int m = bidders.size();
        int k = tasks.size();
        ensureMatrixCapacity(m, k);

        for (int i = 0; i < m; i++) {
            Bidder b = bidders.get(i);
            for (int j = 0; j < k; j++) {
                TaskSlice t = tasks.get(j);
                RoomTransition trans = SmartObjectRegistry.classifyRoomTransition(
                        settlementId, b.anchorZ, b.anchorRoomId, t.z, t.roomId);
                costBuffer[i][j] = computeMarginalCost(b, t, trans, simTime);
            }
        }

        EmberFormationMatcher.solveRectangularHungarian(m, k, costBuffer, rowToColBuffer, workspace);

        int count = 0;
        for (int i = 0; i < m; i++) {
            int j = rowToColBuffer[i];
            if (j >= 0 && j < k) {
                outAssignments.add(new Assignment(bidders.get(i), tasks.get(j), costBuffer[i][j]));
                count++;
            }
        }
        markAuctionExecuted(settlementId, simTime);
        return count;
    }

    private void ensureMatrixCapacity(int rows, int cols) {
        if (rows <= costBuffer.length && cols <= costBuffer[0].length && rows <= rowToColBuffer.length) {
            return;
        }
        int newR = Math.max(rows, costBuffer.length * 2);
        int newC = Math.max(cols, costBuffer[0].length * 2);
        this.costBuffer = new double[newR][newC];
        this.rowToColBuffer = new int[newR];
    }
}
