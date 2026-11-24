package emberroutes.npc;

import java.util.Locale;

/**
 * 小队交火规则（Rules of Engagement）：EMB-19 持久化并暴露 UI 切换，EMB-30 战斗导演直接消费。
 */
public enum RulesOfEngagement {
    FREE_FIRE,
    RETURN_FIRE,
    HOLD_FIRE;

    public static RulesOfEngagement fromString(String raw, RulesOfEngagement fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback != null ? fallback : FREE_FIRE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback != null ? fallback : FREE_FIRE;
        }
    }

    public RulesOfEngagement next() {
        RulesOfEngagement[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }
}
