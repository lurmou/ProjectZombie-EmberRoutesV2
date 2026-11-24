package emberroutes.npc;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * 小队共享战术配置单一权威状态（纯 Java，无 {@code zombie.*} 依赖）：
 * <ul>
 *   <li>维护全队阵型、间距、ROE、CQB、集结/原地驻守（含锚点）与自动上下车开关；</li>
 *   <li>维护按 {@code npcId} 的单兵 ROE/CQB 独立覆盖，以及绑定弱车引用的手动乘降指令；</li>
 *   <li>每次变更均递增单调 {@code version}，供跟随控制器当帧穿透停靠滞回并秒级重排阵型。</li>
 * </ul>
 */
public final class SquadTacticalConfig {
    public enum OrderMode {
        FOLLOW,
        HOLD_POSITION;

        public static OrderMode fromString(String raw, OrderMode fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback != null ? fallback : FOLLOW;
            }
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback != null ? fallback : FOLLOW;
            }
        }
    }

    public enum VehicleCommandMode {
        AUTO,
        FORCE_BOARD,
        FORCE_EXIT
    }

    private static FormationType formation = FormationType.WEDGE;
    private static FormationSpacing spacing = FormationSpacing.STANDARD;
    private static RulesOfEngagement defaultRoe = RulesOfEngagement.FREE_FIRE;
    private static boolean defaultCqb = false;
    private static OrderMode orderMode = OrderMode.FOLLOW;
    private static boolean autoVehicle = true;

    private static boolean hasHoldAnchor = false;
    private static float holdX = 0.0F;
    private static float holdY = 0.0F;
    private static int holdZ = 0;
    private static float holdDirX = 0.0F;
    private static float holdDirY = 1.0F;

    private static VehicleCommandMode vehicleOverride = VehicleCommandMode.AUTO;
    private static WeakReference<Object> overrideVehicleRef;

    private static final Map<String, RulesOfEngagement> memberRoeOverrides = new HashMap<>();
    private static final Map<String, Boolean> memberCqbOverrides = new HashMap<>();

    private static long version = 1L;

    private SquadTacticalConfig() {}

    public static synchronized void reset() {
        formation = FormationType.WEDGE;
        spacing = FormationSpacing.STANDARD;
        defaultRoe = RulesOfEngagement.FREE_FIRE;
        defaultCqb = false;
        orderMode = OrderMode.FOLLOW;
        autoVehicle = true;
        hasHoldAnchor = false;
        holdX = 0.0F;
        holdY = 0.0F;
        holdZ = 0;
        holdDirX = 0.0F;
        holdDirY = 1.0F;
        vehicleOverride = VehicleCommandMode.AUTO;
        overrideVehicleRef = null;
        memberRoeOverrides.clear();
        memberCqbOverrides.clear();
        version++;
    }

    public static synchronized long getVersion() {
        return version;
    }

    public static synchronized FormationType getFormation() {
        return formation;
    }

    public static synchronized boolean setFormation(FormationType next) {
        if (next == null || next == formation) {
            return false;
        }
        formation = next;
        version++;
        return true;
    }

    public static synchronized FormationSpacing getSpacing() {
        return spacing;
    }

    public static synchronized boolean setSpacing(FormationSpacing next) {
        if (next == null || next == spacing) {
            return false;
        }
        spacing = next;
        version++;
        return true;
    }

    public static synchronized RulesOfEngagement getDefaultRoe() {
        return defaultRoe;
    }

    public static synchronized boolean setDefaultRoe(RulesOfEngagement next) {
        if (next == null || next == defaultRoe) {
            return false;
        }
        defaultRoe = next;
        version++;
        return true;
    }

    public static synchronized boolean isDefaultCqb() {
        return defaultCqb;
    }

    public static synchronized boolean setDefaultCqb(boolean next) {
        if (next == defaultCqb) {
            return false;
        }
        defaultCqb = next;
        version++;
        return true;
    }

    public static synchronized OrderMode getOrderMode() {
        return orderMode;
    }

    public static synchronized boolean setOrderMode(OrderMode next) {
        if (next == null || next == orderMode) {
            return false;
        }
        orderMode = next;
        if (next == OrderMode.FOLLOW) {
            hasHoldAnchor = false;
        }
        version++;
        return true;
    }

    public static synchronized void setHoldAnchor(float x, float y, int z, float dirX, float dirY) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return;
        }
        double len = Math.hypot(dirX, dirY);
        float ux = len > 1.0E-4 ? (float) (dirX / len) : 0.0F;
        float uy = len > 1.0E-4 ? (float) (dirY / len) : 1.0F;
        holdX = x;
        holdY = y;
        holdZ = z;
        holdDirX = ux;
        holdDirY = uy;
        hasHoldAnchor = true;
        orderMode = OrderMode.HOLD_POSITION;
        version++;
    }

    public static synchronized boolean hasHoldAnchor() {
        return hasHoldAnchor && orderMode == OrderMode.HOLD_POSITION;
    }

    public static synchronized float getHoldX() {
        return holdX;
    }

    public static synchronized float getHoldY() {
        return holdY;
    }

    public static synchronized int getHoldZ() {
        return holdZ;
    }

    public static synchronized float getHoldDirX() {
        return holdDirX;
    }

    public static synchronized float getHoldDirY() {
        return holdDirY;
    }

    public static synchronized boolean isAutoVehicle() {
        return autoVehicle;
    }

    public static synchronized boolean setAutoVehicle(boolean enabled) {
        if (enabled == autoVehicle) {
            return false;
        }
        autoVehicle = enabled;
        version++;
        return true;
    }

    public static synchronized VehicleCommandMode getVehicleOverride() {
        return vehicleOverride;
    }

    public static synchronized Object getOverrideVehicle() {
        return overrideVehicleRef != null ? overrideVehicleRef.get() : null;
    }

    public static synchronized boolean matchesOverrideVehicle(Object vehicleObj) {
        if (vehicleObj == null || overrideVehicleRef == null) {
            return false;
        }
        return overrideVehicleRef.get() == vehicleObj;
    }

    public static synchronized void setManualVehicleOverride(VehicleCommandMode mode, Object vehicleObj) {
        vehicleOverride = mode != null ? mode : VehicleCommandMode.AUTO;
        overrideVehicleRef = (vehicleObj != null && vehicleOverride != VehicleCommandMode.AUTO)
                ? new WeakReference<>(vehicleObj)
                : null;
        if (vehicleOverride == VehicleCommandMode.FORCE_BOARD && orderMode == OrderMode.HOLD_POSITION) {
            orderMode = OrderMode.FOLLOW;
            hasHoldAnchor = false;
        }
        version++;
    }

    public static synchronized void clearManualVehicleOverride() {
        if (vehicleOverride != VehicleCommandMode.AUTO || overrideVehicleRef != null) {
            vehicleOverride = VehicleCommandMode.AUTO;
            overrideVehicleRef = null;
            version++;
        }
    }

    public static synchronized RulesOfEngagement getEffectiveRoe(String npcId) {
        if (npcId != null) {
            RulesOfEngagement ov = memberRoeOverrides.get(npcId);
            if (ov != null) {
                return ov;
            }
        }
        return defaultRoe;
    }

    public static synchronized boolean hasMemberRoeOverride(String npcId) {
        return npcId != null && memberRoeOverrides.containsKey(npcId);
    }

    public static synchronized void setMemberRoeOverride(String npcId, RulesOfEngagement roeOrNull) {
        if (npcId == null || npcId.isEmpty()) {
            return;
        }
        if (roeOrNull == null) {
            if (memberRoeOverrides.remove(npcId) != null) {
                version++;
            }
        } else {
            memberRoeOverrides.put(npcId, roeOrNull);
            version++;
        }
    }

    public static synchronized boolean isEffectiveCqb(String npcId) {
        if (npcId != null) {
            Boolean ov = memberCqbOverrides.get(npcId);
            if (ov != null) {
                return ov.booleanValue();
            }
        }
        return defaultCqb;
    }

    public static synchronized boolean hasMemberCqbOverride(String npcId) {
        return npcId != null && memberCqbOverrides.containsKey(npcId);
    }

    public static synchronized void setMemberCqbOverride(String npcId, Boolean cqbOrNull) {
        if (npcId == null || npcId.isEmpty()) {
            return;
        }
        if (cqbOrNull == null) {
            if (memberCqbOverrides.remove(npcId) != null) {
                version++;
            }
        } else {
            memberCqbOverrides.put(npcId, cqbOrNull);
            version++;
        }
    }

    public static synchronized void clearMemberOverrides(String npcId) {
        if (npcId == null) {
            return;
        }
        boolean changed = memberRoeOverrides.remove(npcId) != null;
        changed |= memberCqbOverrides.remove(npcId) != null;
        if (changed) {
            version++;
        }
    }
}
