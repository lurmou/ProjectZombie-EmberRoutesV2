package emberroutes.npc;

import java.util.Locale;

/**
 * 小队队形枚举（纯 Java，零引擎依赖）。
 */
public enum FormationType {
    WEDGE,
    COLUMN,
    LINE,
    RING;

    public static FormationType fromString(String raw, FormationType fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback != null ? fallback : WEDGE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback != null ? fallback : WEDGE;
        }
    }

    public FormationType next() {
        FormationType[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }
}
