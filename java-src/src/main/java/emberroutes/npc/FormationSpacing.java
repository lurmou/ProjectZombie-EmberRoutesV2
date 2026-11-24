package emberroutes.npc;

import java.util.Locale;

/**
 * 队形三档间距倍率（紧 / 标 / 宽，纯 Java）。
 */
public enum FormationSpacing {
    TIGHT(0.75F),
    STANDARD(1.00F),
    WIDE(1.45F);

    private final float multiplier;

    FormationSpacing(float multiplier) {
        this.multiplier = multiplier;
    }

    public float multiplier() {
        return multiplier;
    }

    public static FormationSpacing fromString(String raw, FormationSpacing fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback != null ? fallback : STANDARD;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback != null ? fallback : STANDARD;
        }
    }

    public FormationSpacing next() {
        FormationSpacing[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }
}
