package emberroutes.npc;

import java.util.Locale;

/**
 * 随从战术职能类别（用于阵位权重偏置与 SquadHUD 徽标展示）。
 */
public enum TacticalRole {
    MELEE,
    RANGED,
    MEDIC,
    SUPPORT;

    public static TacticalRole fromString(String raw, TacticalRole fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback != null ? fallback : SUPPORT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback != null ? fallback : SUPPORT;
        }
    }
}
