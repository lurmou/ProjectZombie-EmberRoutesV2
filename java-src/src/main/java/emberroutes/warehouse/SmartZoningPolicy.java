package emberroutes.warehouse;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.vm.KahluaTable;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.areas.IsoRoom;

/**
 * SmartZoning 容器三大物流角色与智能分类过滤策略（完整迁入 Java 单实现）：
 * <ul>
 *   <li>零空表污染读取：严格先判 {@link IsoObject#hasModData()}，未配置容器绝不分配空 {@code KahluaTable}；</li>
 *   <li>排除伪武器噪音（圆珠笔、铅笔、餐叉、餐勺等原版 {@code Category=Weapon} 杂物不会误将书桌识别成军械库）；</li>
 *   <li>支持 Lua 声明式预设同步（{@link #registerPreset(String, Collection, int)}）与纯手动触发的硬件/房间语境一键分区。</li>
 * </ul>
 */
public final class SmartZoningPolicy {
    public static final String MODDATA_SMARTZONE_KEY = "ER_SmartZone";
    public static final int DEFAULT_PRIORITY = 3;
    public static final int MIN_PRIORITY = 1;
    public static final int MAX_PRIORITY = 5;

    private static final Set<String> FAKE_WEAPONS = Set.of(
            "base.pen",
            "base.pencil",
            "base.redpen",
            "base.bluepen",
            "base.greenpen",
            "base.fork",
            "base.spoon",
            "base.butterknife",
            "base.letteropener",
            "base.scissors",
            "base.rollingpin"
    );

    public record PresetDef(String id, Set<String> allowGroups, int defaultPriority) {}

    private static final Map<String, PresetDef> PRESETS = new ConcurrentHashMap<>();

    static {
        seedDefaultPresets();
    }

    /** 容器持久化过滤规则与角色配置。 */
    public static final class ContainerFilterRule {
        public ContainerRole role = ContainerRole.STORAGE;
        public int priority = DEFAULT_PRIORITY;
        public boolean enabled = true;
        public final Set<String> allowGroups = new LinkedHashSet<>();
        public final Set<String> denyGroups = new LinkedHashSet<>();
        public final Set<String> allowTypes = new LinkedHashSet<>();
        public final Set<String> denyTypes = new LinkedHashSet<>();

        public ContainerFilterRule copy() {
            ContainerFilterRule c = new ContainerFilterRule();
            c.role = this.role;
            c.priority = this.priority;
            c.enabled = this.enabled;
            c.allowGroups.addAll(this.allowGroups);
            c.denyGroups.addAll(this.denyGroups);
            c.allowTypes.addAll(this.allowTypes);
            c.denyTypes.addAll(this.denyTypes);
            return c;
        }

        public boolean hasAnyAllowFilter() {
            return !allowGroups.isEmpty() || !allowTypes.isEmpty();
        }
    }

    private SmartZoningPolicy() {}

    public static void seedDefaultPresets() {
        PRESETS.clear();
        registerPreset("food", Set.of("food"), 3);
        registerPreset("food.meals", Set.of("food.meals"), 4);
        registerPreset("food.perishable", Set.of("food.perishable"), 4);
        registerPreset("food.stable", Set.of("food.stable"), 3);
        registerPreset("medical", Set.of("medical"), 4);
        registerPreset("medical.bandages", Set.of("medical.bandages"), 4);
        registerPreset("medical.pills", Set.of("medical.pills"), 4);
        registerPreset("weapons", Set.of("weapons"), 4);
        registerPreset("weapons.guns", Set.of("weapons.guns"), 5);
        registerPreset("weapons.ammo", Set.of("weapons.ammo"), 4);
        registerPreset("weapons.parts", Set.of("weapons.parts"), 3);
        registerPreset("weapons.other", Set.of("weapons.other"), 3);
        registerPreset("apparel", Set.of("apparel"), 3);
        registerPreset("apparel.clothing", Set.of("apparel.clothing"), 3);
        registerPreset("apparel.armor", Set.of("apparel.armor"), 4);
        registerPreset("apparel.bags", Set.of("apparel.bags"), 3);
        registerPreset("literature", Set.of("literature"), 3);
        registerPreset("literature.skills", Set.of("literature.skills"), 4);
        registerPreset("literature.entertainment", Set.of("literature.entertainment"), 2);
        registerPreset("tools", Set.of("tools"), 3);
        registerPreset("materials", Set.of("materials"), 3);
        registerPreset("materials.electronics", Set.of("materials.electronics"), 3);
        registerPreset("fluids", Set.of("fluids"), 3);
        registerPreset("kits", Set.of("kits"), 3);
        registerPreset("kits.fishing", Set.of("kits.fishing"), 3);
        registerPreset("kits.farming", Set.of("kits.farming"), 3);
        registerPreset("kits.tailoring", Set.of("kits.tailoring"), 3);
        registerPreset("kits.mechanic", Set.of("kits.mechanic"), 3);
        registerPreset("scrap", Set.of("scrap"), 1);
        registerPreset("scrap.waste", Set.of("scrap.waste"), 1);
        registerPreset("scrap.compost", Set.of("scrap.compost"), 1);
        registerPreset("general", Set.of("literature", "tools", "materials", "other"), 1);
    }

    public static void registerPreset(String id, Collection<String> allowGroups, int defaultPriority) {
        if (id == null || id.isBlank()) {
            return;
        }
        String normId = id.trim().toLowerCase(Locale.ROOT);
        Set<String> groups = new LinkedHashSet<>();
        if (allowGroups != null) {
            for (String g : allowGroups) {
                if (g != null && !g.isBlank()) {
                    groups.add(g.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (groups.isEmpty()) {
            groups.add(normId);
        }
        PRESETS.put(normId, new PresetDef(normId, Collections.unmodifiableSet(groups), clampPriority(defaultPriority)));
    }

    public static PresetDef getPreset(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return PRESETS.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public static Map<String, PresetDef> getAllPresets() {
        return Collections.unmodifiableMap(PRESETS);
    }

    public static int clampPriority(int p) {
        return Math.max(MIN_PRIORITY, Math.min(MAX_PRIORITY, p));
    }

    public static boolean isFakeWeapon(String fullType) {
        if (fullType == null || fullType.isEmpty()) {
            return false;
        }
        return FAKE_WEAPONS.contains(fullType.trim().toLowerCase(Locale.ROOT));
    }

    /** 读取容器对象 {@code ModData.ER_SmartZone}；未配置对象绝不分配 {@code ModData}。 */
    public static ContainerFilterRule readRule(IsoObject obj) {
        ContainerFilterRule rule = new ContainerFilterRule();
        if (obj == null || !obj.hasModData()) {
            return rule;
        }
        KahluaTable md = obj.getModData();
        if (md == null) {
            return rule;
        }
        Object raw = md.rawget(MODDATA_SMARTZONE_KEY);
        if (!(raw instanceof KahluaTable tbl)) {
            return rule;
        }
        return kahluaTableToRule(tbl);
    }

    public static ContainerFilterRule kahluaTableToRule(KahluaTable tbl) {
        ContainerFilterRule rule = new ContainerFilterRule();
        if (tbl == null) {
            return rule;
        }
        Object rObj = tbl.rawget("role");
        if (rObj instanceof String s) {
            rule.role = ContainerRole.fromString(s);
        }
        Object pObj = tbl.rawget("priority");
        if (pObj instanceof Number n) {
            rule.priority = clampPriority(n.intValue());
        }
        Object eObj = tbl.rawget("enabled");
        if (eObj instanceof Boolean b) {
            rule.enabled = b.booleanValue();
        }
        parseFilterListOrCsv(tbl.rawget("allowGroups"), rule.allowGroups);
        parseFilterListOrCsv(tbl.rawget("denyGroups"), rule.denyGroups);
        parseFilterListOrCsv(tbl.rawget("allowTypes"), rule.allowTypes);
        parseFilterListOrCsv(tbl.rawget("denyTypes"), rule.denyTypes);
        return rule;
    }

    public static KahluaTable ruleToKahluaTable(ContainerFilterRule rule, boolean isPrivate) {
        ContainerFilterRule src = rule != null ? rule : new ContainerFilterRule();
        KahluaTable tbl = J2SEPlatform.getInstance().newTable();
        tbl.rawset("role", src.role.name());
        tbl.rawset("priority", Double.valueOf(clampPriority(src.priority)));
        tbl.rawset("enabled", Boolean.valueOf(src.enabled));
        tbl.rawset("isPrivate", Boolean.valueOf(isPrivate));
        tbl.rawset("allowGroups", joinCsv(src.allowGroups));
        tbl.rawset("denyGroups", joinCsv(src.denyGroups));
        tbl.rawset("allowTypes", joinCsv(src.allowTypes));
        tbl.rawset("denyTypes", joinCsv(src.denyTypes));
        return tbl;
    }

    private static void parseFilterListOrCsv(Object raw, Set<String> out) {
        if (raw instanceof String) {
            parseCsvInto(raw, out, true);
        } else if (raw instanceof KahluaTable listTbl) {
            se.krka.kahlua.vm.KahluaTableIterator it = listTbl.iterator();
            while (it.advance()) {
                Object val = it.getValue();
                if (val instanceof String s && !s.isBlank()) {
                    out.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    /** 将过滤规则与角色写入世界容器对象自身 {@code ModData.ER_SmartZone}，并置热存盘与脏队列。 */
    public static boolean writeRule(IsoObject obj, ContainerFilterRule rule) {
        if (obj == null || rule == null) {
            return false;
        }
        KahluaTable tbl = J2SEPlatform.getInstance().newTable();
        tbl.rawset("role", rule.role.name());
        tbl.rawset("priority", Double.valueOf(clampPriority(rule.priority)));
        tbl.rawset("enabled", Boolean.valueOf(rule.enabled));
        tbl.rawset("allowGroups", joinCsv(rule.allowGroups));
        tbl.rawset("denyGroups", joinCsv(rule.denyGroups));
        tbl.rawset("allowTypes", joinCsv(rule.allowTypes));
        tbl.rawset("denyTypes", joinCsv(rule.denyTypes));
        obj.getModData().rawset(MODDATA_SMARTZONE_KEY, tbl);
        obj.flagForHotSave();
        IsoGridSquare sq = obj.getSquare();
        if (sq != null) {
            ContainerDirtyQueue.markSquare(sq);
        }
        return true;
    }

    public static ContainerFilterRule buildRuleFromPreset(String presetId, ContainerRole role) {
        ContainerFilterRule rule = new ContainerFilterRule();
        rule.role = role != null ? role : ContainerRole.STORAGE;
        PresetDef def = getPreset(presetId);
        if (def != null) {
            rule.allowGroups.addAll(def.allowGroups());
            rule.priority = def.defaultPriority();
        }
        return rule;
    }

    /** 评估容器规则（完整迁移 V1 {@code SmartZoning.evaluate}：硬件特征 -> 降噪物品计分 -> 房间语境 -> 通用兜底）。 */
    public static ContainerFilterRule evaluateAutoRule(IsoObject obj, ItemContainer ic) {
        if (obj == null || ic == null) {
            return new ContainerFilterRule();
        }
        String cType = ic.getType() != null ? ic.getType().toLowerCase(Locale.ROOT) : "";
        String spriteName = (obj.getSprite() != null && obj.getSprite().getName() != null)
                ? obj.getSprite().getName().toLowerCase(Locale.ROOT)
                : "";
        IsoGridSquare sq = obj.getSquare();
        IsoRoom room = sq != null ? sq.getRoom() : null;
        String roomName = (room != null && room.getName() != null) ? room.getName().toLowerCase(Locale.ROOT) : "";

        Map<String, Double> scores = new HashMap<>();
        int itemCount = 0;
        var items = ic.getItems();
        if (items != null) {
            itemCount = items.size();
            for (int i = 0; i < itemCount; i++) {
                InventoryItem item = items.get(i);
                if (item == null) {
                    continue;
                }
                String ft = item.getFullType();
                if (isFakeWeapon(ft)) {
                    scores.merge("literature", 0.5, Double::sum);
                    continue;
                }
                String node = WarehouseFastIndex.classifyItem(item);
                String root = rootCategoryOf(node);
                if (!"other".equals(root)) {
                    scores.merge(root, 1.0, Double::sum);
                }
            }
        }
        return evaluateHardwareOrContext(cType, spriteName, roomName, scores, itemCount);
    }

    /** 纯数据可单测入口：结合容器类型、贴图名、房间名与物品得分推导最佳分区规则。 */
    public static ContainerFilterRule evaluateHardwareOrContext(
            String containerTypeLower,
            String spriteNameLower,
            String roomNameLower,
            Map<String, Double> scores,
            int totalItemCount) {
        String cType = containerTypeLower != null ? containerTypeLower.toLowerCase(Locale.ROOT) : "";
        String spr = spriteNameLower != null ? spriteNameLower.toLowerCase(Locale.ROOT) : "";
        String room = roomNameLower != null ? roomNameLower.toLowerCase(Locale.ROOT) : "";

        ContainerFilterRule rule = new ContainerFilterRule();
        rule.role = ContainerRole.STORAGE;
        rule.enabled = true;

        // 1. 硬件固定设施优先
        if ("fridge".equals(cType) || "freezer".equals(cType) || spr.contains("fridge") || spr.contains("freezer")) {
            rule.allowGroups.add("food.perishable");
            rule.allowGroups.add("food.meals");
            rule.denyGroups.add("scrap.compost");
            rule.priority = 4;
            return rule;
        }
        if ("medicine".equals(cType) || "firstaid".equals(cType) || spr.contains("medicine") || spr.contains("firstaid")) {
            rule.allowGroups.add("medical");
            rule.priority = 4;
            return rule;
        }
        if ("wardrobe".equals(cType) || "dresser".equals(cType) || spr.contains("wardrobe") || spr.contains("dresser")) {
            rule.allowGroups.add("apparel.clothing");
            rule.allowGroups.add("apparel.armor");
            rule.priority = 3;
            return rule;
        }
        if ("bookshelf".equals(cType) || "bookcase".equals(cType)
                || spr.contains("bookshelf") || spr.contains("bookcase") || spr.contains("book")) {
            rule.allowGroups.add("literature");
            rule.priority = 3;
            return rule;
        }
        if ("gunrack".equals(cType) || spr.contains("gun") || spr.contains("armory")
                || (spr.contains("weapon") && !spr.contains("tool"))) {
            rule.allowGroups.add("weapons.guns");
            rule.allowGroups.add("weapons.ammo");
            rule.allowGroups.add("weapons.parts");
            rule.priority = 5;
            return rule;
        }

        double foodScore = scoreOf(scores, "food");
        double medScore = scoreOf(scores, "medical");
        double weaponScore = scoreOf(scores, "weapons");
        double toolScore = scoreOf(scores, "tools");
        double matScore = scoreOf(scores, "materials");
        double kitScore = scoreOf(scores, "kits");
        double litScore = scoreOf(scores, "literature");
        double apparelScore = scoreOf(scores, "apparel");
        double totalCore = foodScore + medScore + weaponScore + toolScore + matScore + kitScore + litScore + apparelScore;

        boolean isKitchen = room.contains("kitchen") || spr.contains("kitchen")
                || (cType.contains("counter") && room.contains("kitchen"));
        boolean isWorkshop = room.contains("garage") || room.contains("shed") || room.contains("workshop")
                || room.contains("storage") || spr.contains("tool") || spr.contains("metal")
                || spr.contains("industrial") || spr.contains("shelving");
        boolean isBathroom = room.contains("bathroom") || room.contains("clinic");
        boolean isBedroom = room.contains("bedroom");

        boolean armoryDominant = weaponScore >= 2.0
                || (weaponScore >= 1.0 && weaponScore >= (totalCore - weaponScore) && !isKitchen && !isWorkshop);
        if (armoryDominant) {
            rule.allowGroups.add("weapons.guns");
            rule.allowGroups.add("weapons.ammo");
            rule.allowGroups.add("weapons.parts");
            rule.allowGroups.add("weapons.other");
            rule.priority = 4;
            return rule;
        }

        boolean medicalDominant = medScore >= 2.0
                || (medScore >= 1.0 && (isBathroom || medScore >= (totalCore - medScore)));
        if (medicalDominant) {
            rule.allowGroups.add("medical");
            rule.priority = 4;
            return rule;
        }

        if (isKitchen || foodScore >= 2.0 || (foodScore >= 1.0 && toolScore >= 1.0)) {
            rule.allowGroups.add("food.stable");
            rule.allowGroups.add("food.meals");
            rule.allowGroups.add("tools");
            rule.priority = 3;
            return rule;
        }

        if (isWorkshop || (toolScore + matScore + kitScore) >= 2.0 || (toolScore >= 1.0 && isWorkshop)) {
            rule.allowGroups.add("tools");
            rule.allowGroups.add("materials");
            if (room.contains("garage") || kitScore >= 1.0) {
                rule.allowGroups.add("kits.mechanic");
            }
            rule.priority = 3;
            return rule;
        }

        if (litScore >= 2.0 || (litScore >= 1.0 && totalItemCount <= 4)) {
            rule.allowGroups.add("literature");
            rule.priority = 3;
            return rule;
        }

        if (apparelScore >= 2.0 || (apparelScore >= 1.0 && isBedroom)) {
            rule.allowGroups.add("apparel.clothing");
            rule.allowGroups.add("apparel.armor");
            rule.priority = 3;
            return rule;
        }

        if (isBedroom) {
            rule.allowGroups.add("apparel.clothing");
            rule.allowGroups.add("literature");
            rule.priority = 2;
            return rule;
        }

        if (isBathroom) {
            rule.allowGroups.add("medical");
            rule.priority = 3;
            return rule;
        }

        rule.allowGroups.add("literature");
        rule.allowGroups.add("tools");
        rule.allowGroups.add("materials");
        rule.allowGroups.add("other");
        rule.priority = 1;
        return rule;
    }

    /** 检查物品是否允许自动分拣进入目标容器规则（自动跳过 {@code BUFFER_IN / PICKUP_OUT / disabled}）。 */
    public static boolean matchesItem(ContainerFilterRule rule, InventoryItem item) {
        if (rule == null || item == null) {
            return false;
        }
        String ft = item.getFullType();
        String node = WarehouseFastIndex.classifyItem(item);
        return matchesItemNode(rule, ft, node);
    }

    public static boolean matchesItemNode(ContainerFilterRule rule, String fullType, String categoryNode) {
        if (rule == null || !rule.enabled) {
            return false;
        }
        if (rule.role == ContainerRole.BUFFER_IN || rule.role == ContainerRole.PICKUP_OUT) {
            return false;
        }
        if (!rule.hasAnyAllowFilter()) {
            return false;
        }
        String ft = fullType != null ? fullType.trim().toLowerCase(Locale.ROOT) : "";
        String node = categoryNode != null ? categoryNode.trim().toLowerCase(Locale.ROOT) : "other";

        if (!ft.isEmpty() && rule.denyTypes.contains(ft)) {
            return false;
        }
        if (!ft.isEmpty() && rule.allowTypes.contains(ft)) {
            return true;
        }
        for (String dg : rule.denyGroups) {
            if (groupMatches(dg, node)) {
                return false;
            }
        }
        for (String ag : rule.allowGroups) {
            if (groupMatches(ag, node)) {
                return true;
            }
        }
        return false;
    }

    private static boolean groupMatches(String pattern, String node) {
        if (pattern == null || pattern.isEmpty() || node == null || node.isEmpty()) {
            return false;
        }
        return node.equals(pattern) || node.startsWith(pattern + ".");
    }

    private static String rootCategoryOf(String node) {
        if (node == null || node.isEmpty()) {
            return "other";
        }
        int dot = node.indexOf('.');
        return dot > 0 ? node.substring(0, dot) : node;
    }

    private static double scoreOf(Map<String, Double> scores, String key) {
        if (scores == null) {
            return 0.0;
        }
        Double v = scores.get(key);
        return v != null ? v.doubleValue() : 0.0;
    }

    private static void parseCsvInto(Object raw, Set<String> out, boolean lowercase) {
        if (!(raw instanceof String s) || s.isBlank()) {
            return;
        }
        int start = 0;
        int len = s.length();
        while (start < len) {
            int comma = s.indexOf(',', start);
            int end = comma >= 0 ? comma : len;
            String token = s.substring(start, end).trim();
            if (!token.isEmpty()) {
                out.add(lowercase ? token.toLowerCase(Locale.ROOT) : token);
            }
            start = end + 1;
        }
    }

    private static String joinCsv(Set<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String it : items) {
            if (it == null || it.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(it);
        }
        return sb.toString();
    }
}
