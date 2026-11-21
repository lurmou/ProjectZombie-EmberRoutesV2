package emberroutes.npc.brain;

/**
 * 纯 Java 生理需求与工作/空闲双阈值滞回策略（零引擎依赖，支持脱机 JUnit 严密单测）：
 * <ul>
 *   <li>空闲态阈值：{@code hunger >= 0.25}、{@code thirst >= 0.20}、{@code fatigue >= 0.65}；</li>
 *   <li>工作态耐受抬升：{@code hunger >= 0.50}、{@code thirst >= 0.50}、{@code fatigue >= 0.80}，防刚干活 10 秒就回城震荡；</li>
 *   <li>退出滞回下限：一旦开始处理对应需求，须回落至 {@code 0.10 / 0.15} 以下才结束；流血伤势永远无视工作抑制即刻包扎。</li>
 * </ul>
 */
public final class NeedsPolicy {
    public static final float IDLE_HUNGER_ENTER = 0.25F;
    public static final float IDLE_THIRST_ENTER = 0.20F;
    public static final float IDLE_FATIGUE_ENTER = 0.65F;

    public static final float WORK_HUNGER_ENTER = 0.50F;
    public static final float WORK_THIRST_ENTER = 0.50F;
    public static final float WORK_FATIGUE_ENTER = 0.80F;

    public static final float HUNGER_EXIT = 0.10F;
    public static final float THIRST_EXIT = 0.10F;
    public static final float FATIGUE_EXIT = 0.15F;

    public enum UrgentNeed {
        NONE,
        BANDAGE,
        EAT,
        DRINK,
        SLEEP
    }

    private NeedsPolicy() {}

    /** 按当前生理指标、是否在工/忙碌与现行需求，返回经滞回过滤后的最高优生存需求。 */
    public static UrgentNeed evaluateUrgentNeed(
            float hunger,
            float thirst,
            float fatigue,
            boolean bleeding,
            boolean isWorking,
            UrgentNeed currentNeed) {
        if (bleeding) {
            return UrgentNeed.BANDAGE;
        }
        if (currentNeed == UrgentNeed.EAT && hunger > HUNGER_EXIT) {
            return UrgentNeed.EAT;
        }
        if (currentNeed == UrgentNeed.DRINK && thirst > THIRST_EXIT) {
            return UrgentNeed.DRINK;
        }
        if (currentNeed == UrgentNeed.SLEEP && fatigue > FATIGUE_EXIT) {
            return UrgentNeed.SLEEP;
        }

        float enterHunger = isWorking ? WORK_HUNGER_ENTER : IDLE_HUNGER_ENTER;
        float enterThirst = isWorking ? WORK_THIRST_ENTER : IDLE_THIRST_ENTER;
        float enterFatigue = isWorking ? WORK_FATIGUE_ENTER : IDLE_FATIGUE_ENTER;

        if (hunger >= enterHunger) {
            return UrgentNeed.EAT;
        }
        if (thirst >= enterThirst) {
            return UrgentNeed.DRINK;
        }
        if (fatigue >= enterFatigue) {
            return UrgentNeed.SLEEP;
        }
        return UrgentNeed.NONE;
    }
}
