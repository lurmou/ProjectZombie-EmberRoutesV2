package emberroutes.npc.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战斗链路事件级轻量化诊断日志与只读实时状态快照（纯 Java，0 {@code zombie.*} 依赖）。
 *
 * <p>默认 {@code enabled = false}（零生产热路径日志污染），仅在玩家于 {@code DebugPanel} 手动开启时输出。</p>
 */
public final class CombatDiagLog {
    private static final int MAX_STATUS_ENTRIES = 32;

    public static final class NpcCombatDiagStatus {
        public final String npcId;
        public volatile String state = "IDLE";
        public volatile String activity = "";
        public volatile int targetId = -1;
        public volatile String targetPosture = "NONE";
        public volatile float dist;
        public volatile float minRange;
        public volatile float maxRange;
        public volatile boolean aimFloor;
        public volatile boolean stomp;
        public volatile String radialMode = "HOLD";
        public volatile long updatedAtMs;

        NpcCombatDiagStatus(String npcId) {
            this.npcId = npcId;
        }
    }

    private static volatile boolean diagEnabled = false;
    private static final Map<String, NpcCombatDiagStatus> LIVE_STATUS = new ConcurrentHashMap<>();

    private CombatDiagLog() {}

    public static boolean isEnabled() {
        return diagEnabled;
    }

    public static boolean setEnabled(boolean enabled) {
        diagEnabled = enabled;
        return true;
    }

    public static void reset() {
        LIVE_STATUS.clear();
    }

    public static void removeNpc(String npcId) {
        if (npcId == null) return;
        LIVE_STATUS.remove(npcId);
    }

    public static void logCommit(
            String npcId,
            String attackKind,
            int targetId,
            int liveMask,
            float dist,
            float minRange,
            float maxRange,
            boolean aimFloor,
            boolean doShove,
            boolean stompAnim) {
        if (!diagEnabled) return;
        System.out.println(String.format(Locale.ROOT,
                "[EmberRoutes][COMBAT_DIAG] COMMIT npc=%s kind=%s tgt=%d posture=%s dist=%.2f range=[%.2f..%.2f] aimFloor=%s shove=%s stomp=%s",
                npcId != null ? npcId : "unknown",
                attackKind,
                targetId,
                describePosture(liveMask),
                dist,
                minRange,
                maxRange,
                aimFloor,
                doShove,
                stompAnim));
    }

    public static void logEvent(String npcId, String tag, String detail) {
        if (!diagEnabled) return;
        System.out.println(String.format(Locale.ROOT,
                "[EmberRoutes][COMBAT_DIAG] %s npc=%s %s",
                tag != null ? tag : "EVENT",
                npcId != null ? npcId : "unknown",
                detail != null ? detail : ""));
    }

    public static void updateNpcStatus(
            String npcId,
            String state,
            String activity,
            int targetId,
            int targetMask,
            float dist,
            float minRange,
            float maxRange,
            boolean aimFloor,
            boolean stomp,
            String radialMode,
            long nowMs) {
        if (npcId == null || npcId.isEmpty()) return;
        NpcCombatDiagStatus status = LIVE_STATUS.get(npcId);
        if (status == null) {
            if (LIVE_STATUS.size() >= MAX_STATUS_ENTRIES) {
                return;
            }
            status = new NpcCombatDiagStatus(npcId);
            NpcCombatDiagStatus prev = LIVE_STATUS.putIfAbsent(npcId, status);
            if (prev != null) {
                status = prev;
            }
        }
        status.state = state != null ? state : "IDLE";
        status.activity = activity != null ? activity : "";
        status.targetId = targetId;
        status.targetPosture = describePosture(targetMask);
        status.dist = dist;
        status.minRange = minRange;
        status.maxRange = maxRange;
        status.aimFloor = aimFloor;
        status.stomp = stomp;
        status.radialMode = radialMode != null ? radialMode : "HOLD";
        status.updatedAtMs = nowMs;
    }

    public static List<NpcCombatDiagStatus> snapshotNpcStatuses() {
        List<NpcCombatDiagStatus> out = new ArrayList<>(LIVE_STATUS.values());
        out.sort(Comparator.comparing(s -> s.npcId));
        return out;
    }

    public static String describePosture(int liveMask) {
        if (liveMask == 0) return "NONE";
        if (CombatTargetState.isPostureTransition(liveMask)) return "TRANSITION";
        if (CombatTargetState.isCrawler(liveMask)) return "CRAWLER";
        if (CombatTargetState.isProneOrCrawl(liveMask)) return "PRONE";
        if (CombatTargetState.isStaggered(liveMask)) return "STAGGER";
        if (CombatTargetState.isLunging(liveMask)) return "LUNGE";
        if (CombatTargetState.isSprinter(liveMask)) return "SPRINTER";
        return CombatTargetState.isStanding(liveMask) ? "STANDING" : "UNKNOWN";
    }
}
