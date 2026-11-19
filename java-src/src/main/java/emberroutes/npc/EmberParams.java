package emberroutes.npc;

/**
 * EMBER 机器人级局部运动控制器（ROS 2 Nav2 Regulated Pure Pursuit + 原生双混合树无级变速 CVT）可调参数表。
 * 所有参数均具备明确物理量纲（格、度、角速度倍率）。
 */
public final class EmberParams {
    /** 跑步导航时的原版骨骼转向速率倍率（默认 0.80 与真玩家 WASD 奔跑 100% 一致；步行态固定复用原版 1.00）。 */
    public static volatile float TURN_GAIN = 0.80F;

    /** 终点恒定减速度平方根刹车曲线启动距离（单位：格；设为 0 则关闭提前减速）。 */
    public static volatile float BRAKE_DIST = 1.30F;

    /** RPP 曲率限速最小转弯半径（单位：格；当前曲率半径小于此值时按比例平滑下调无级油门 WalkSpeed）。 */
    public static volatile float MIN_TURN_RADIUS = 0.55F;

    /** 窄门框/近墙前探降速距离（单位：格；仅在贴近障碍拐弯前微收油门）。 */
    public static volatile float PROX_DIST = 0.95F;

    /** 低速/窄门穿针时的最小自适应前瞻距离 L_min（单位：格）。 */
    public static volatile float LOOKAHEAD_MIN = 0.28F;

    /** 高速开阔地巡航时的最大自适应前瞻距离 L_max（单位：格；匹配原版 0.80f 奔跑转弯半径 ~1.46 格）。 */
    public static volatile float LOOKAHEAD_MAX = 1.45F;

    /** 终点到达内死区半径 R_in（单位：格；进入即触发 Arrived 滞回锁死）。 */
    public static volatile float ARRIVE_RADIUS = 0.32F;

    /** 大航向误差低速先转身门禁角度（单位：度；起步或拐死弯偏角超过此阈值时先压低线速度快速转体，防撞墙）。 */
    public static volatile float ROTATE_HEADING_DEG = 80.0F;

    private EmberParams() {}

    public static void resetDefaults() {
        TURN_GAIN = 0.80F;
        BRAKE_DIST = 1.30F;
        MIN_TURN_RADIUS = 0.55F;
        PROX_DIST = 0.95F;
        LOOKAHEAD_MIN = 0.28F;
        LOOKAHEAD_MAX = 1.45F;
        ARRIVE_RADIUS = 0.32F;
        ROTATE_HEADING_DEG = 80.0F;
    }

    public static Float get(String key) {
        if (key == null) return null;
        return switch (key) {
            case "EMBER_TURN_GAIN" -> TURN_GAIN;
            case "EMBER_BRAKE_DIST" -> BRAKE_DIST;
            case "EMBER_MIN_TURN_R" -> MIN_TURN_RADIUS;
            case "EMBER_PROX_DIST" -> PROX_DIST;
            case "EMBER_LOOK_MIN" -> LOOKAHEAD_MIN;
            case "EMBER_LOOK_MAX" -> LOOKAHEAD_MAX;
            case "EMBER_ARRIVE_R" -> ARRIVE_RADIUS;
            case "EMBER_ROTATE_DEG" -> ROTATE_HEADING_DEG;
            default -> null;
        };
    }

    public static boolean set(String key, double value) {
        if (key == null || !Double.isFinite(value)) return false;
        float v = (float) value;
        switch (key) {
            case "EMBER_TURN_GAIN" -> TURN_GAIN = clamp(v, 0.80F, 4.50F);
            case "EMBER_BRAKE_DIST" -> BRAKE_DIST = clamp(v, 0.00F, 3.50F);
            case "EMBER_MIN_TURN_R" -> MIN_TURN_RADIUS = clamp(v, 0.05F, 2.50F);
            case "EMBER_PROX_DIST" -> PROX_DIST = clamp(v, 0.00F, 3.00F);
            case "EMBER_LOOK_MIN" -> {
                LOOKAHEAD_MIN = clamp(v, 0.12F, 1.50F);
                if (LOOKAHEAD_MAX < LOOKAHEAD_MIN) LOOKAHEAD_MAX = LOOKAHEAD_MIN;
            }
            case "EMBER_LOOK_MAX" -> LOOKAHEAD_MAX = clamp(v, LOOKAHEAD_MIN, 2.50F);
            case "EMBER_ARRIVE_R" -> ARRIVE_RADIUS = clamp(v, 0.05F, 0.60F);
            case "EMBER_ROTATE_DEG" -> ROTATE_HEADING_DEG = clamp(v, 25.0F, 180.0F);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
