package emberroutes.warehouse;

import java.util.Locale;

/**
 * 纯 Java 家具静态分类器（零 {@code zombie.*} 依赖，支持脱机 JUnit 回归）：
 * 用于严格区分真实床铺与带 {@code IsoFlagType.bed} 的打盹椅子/沙发，保持 {@code warehouse <- npc} 单向无环依赖。
 */
public final class FurnitureClassifier {
    private static final String[] SEATING_ONLY_TILESET_PREFIXES = {
        "furniture_seating_",
        "location_restaurant_",
        "location_shop_mall_",
        "location_community_church_",
        "location_entertainment_theatre_",
        "location_services_beauty_",
        "location_community_park_",
        "location_community_police_",
        "fixtures_bathroom_"
    };

    private static final String[] SEATING_ONLY_CUSTOM_NAME_TOKENS = {
        "chair", "stool", "bench", "couch", "sofa", "seat",
        "pew", "booth", "toilet", "armchair", "loveseat", "ottoman", "recliner"
    };

    private static final String[] BED_FRIENDLY_TILESET_PREFIXES = {
        "furniture_bedding_", "camping_"
    };

    private static final String[] BED_FRIENDLY_CUSTOM_NAME_TOKENS = {
        "bed", "cot", "bunk", "mattress", "tent", "sleeping"
    };

    private FurnitureClassifier() {}

    /** 归一化四向朝向字符串（仅返回 {@code N/S/W/E} 或 {@code null}）。 */
    public static String normalizeCardinalFacing(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        return switch (trimmed) {
            case "N", "S", "W", "E" -> trimmed;
            default -> null;
        };
    }

    /**
     * 严格区分真实床铺与带打盹标志（{@code IsoFlagType.bed}）的椅子/沙发：
     * <ul>
     *   <li>排除座椅专有图集（{@code furniture_seating_*} 等）与座椅类名；</li>
     *   <li>多格物体必须满足长边与 {@code Facing} 一致（{@code N/S -> gridH >= 2}，{@code W/E -> gridW >= 2}），排除横向朝前的双/三人沙发。</li>
     * </ul>
     */
    public static boolean isRealBedObject(
            String spriteOrTileset, String customName, String facingCardinal, int gridW, int gridH) {
        String tile = spriteOrTileset != null ? spriteOrTileset.trim().toLowerCase(Locale.ROOT) : "";
        String cname = customName != null ? customName.trim().toLowerCase(Locale.ROOT) : "";

        for (String prefix : SEATING_ONLY_TILESET_PREFIXES) {
            if (tile.startsWith(prefix)) {
                return false;
            }
        }
        for (String token : SEATING_ONLY_CUSTOM_NAME_TOKENS) {
            if (cname.contains(token)) {
                return false;
            }
        }

        String facing = normalizeCardinalFacing(facingCardinal);
        int safeW = Math.max(1, gridW);
        int safeH = Math.max(1, gridH);
        boolean multiTile = safeW > 1 || safeH > 1;
        boolean alignedWithFacing = !multiTile || facing == null
                || (("N".equals(facing) || "S".equals(facing)) ? safeH >= 2 : safeW >= 2);

        for (String prefix : BED_FRIENDLY_TILESET_PREFIXES) {
            if (tile.startsWith(prefix)) {
                return alignedWithFacing;
            }
        }
        for (String token : BED_FRIENDLY_CUSTOM_NAME_TOKENS) {
            if (cname.contains(token)) {
                return alignedWithFacing;
            }
        }

        return multiTile && alignedWithFacing;
    }
}
