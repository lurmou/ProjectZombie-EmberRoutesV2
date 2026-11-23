package emberroutes.warehouse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.core.properties.PropertyContainer;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.Food;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.InventoryContainer;
import zombie.inventory.types.Literature;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoBarbecue;
import zombie.iso.objects.IsoCarBatteryCharger;
import zombie.iso.objects.IsoClothingDryer;
import zombie.iso.objects.IsoClothingWasher;
import zombie.iso.objects.IsoCombinationWasherDryer;
import zombie.iso.objects.IsoCompost;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoFeedingTrough;
import zombie.iso.objects.IsoFireplace;
import zombie.iso.objects.IsoHutch;
import zombie.iso.objects.IsoMannequin;
import zombie.iso.objects.IsoStackedWasherDryer;
import zombie.iso.objects.IsoStove;
import zombie.iso.objects.IsoTrap;
import zombie.iso.objects.IsoWheelieBin;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.scripting.objects.Item;
import zombie.scripting.objects.ItemTag;
import zombie.util.list.PZArrayList;

/**
 * 据点全楼层世界容器增量聚合索引（对标 AE2 {@code StorageService + StorageBusPart + TickManagerService + KeyCounter}）：
 *
 * <ol>
 *   <li>双通道感知：{@code @Patch} 无锁脏队列即时唤醒（{@code alertDevice}）+ {@code PriorityQueue} 最小堆自适应退避巡检（{@code min=5, max=60}）；</li>
 *   <li>懒惰快照物化（{@code StorageService.cachedStacksNeedUpdate}）：变动仅更新纯 Java {@link Aggregate} 并令 {@code front = null}，不开面板 0 {@code KahluaTable} 分配；</li>
 *   <li>准入隔离与单箱权限（{@code MEInventoryHandler}）：内置非储物设施黑名单，支持单方块 {@code ModData.ER_Private} 私密排除（先判 {@code hasModData()} 零空表污染，随对象走防漂移）；</li>
 *   <li>两级健康度统计（{@code KeyCounter}）：在 {@code fullType} 下同步区分可用件数与腐烂/烧焦/损坏/耗尽件数；</li>
 *   <li>僵毁离屏适配（{@code OFFLINE_CACHED}）：区块卸载保留最后已知快照供外出查阅与宏观经济读取，同时区分 {@code loaded} 在场可物理提取容器。</li>
 * </ol>
 */
public final class WarehouseFastIndex {
    public static final int TICK_MIN_RATE = 5;
    public static final int TICK_MAX_RATE = 60;
    public static final int TICK_SLOW_DOWN_FACTOR = 1;
    private static final int MAX_PROBES_PER_TICK = 4;

    public static final String MODDATA_PRIVATE_KEY = "ER_Private";
    public static final String STATUS_LOADED = "LOADED";
    public static final String STATUS_OFFLINE_CACHED = "OFFLINE_CACHED";
    public static final String STATUS_PRIVATE = "PRIVATE";

    private static final Map<String, String> CUSTOM_CATEGORY_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<String, String> CATEGORY_CACHE = new ConcurrentHashMap<>();

    private static final Set<String> MEDICAL_TAG_KEYS = Set.of(
            "pills", "bandage", "reusablebandage", "disinfectant", "tweezers", "sutureneedle",
            "sutureneedleholder", "scalpel", "medical", "firstaid", "removebullet", "removeglass",
            "stitch", "splint", "comfrey", "plantain", "wildgarlic"
    );
    private static final Set<String> TOOL_TAG_KEYS = Set.of(
            "hammer", "hammerstone", "clubhammer", "smithinghammer", "mallet",
            "saw", "crudesaw", "metalsaw", "smallsaw",
            "screwdriver", "wrench", "pipewrench",
            "choptree", "cutplant", "diggrave", "sledgehammer",
            "weldingmask", "blowtorch", "crowbar", "prybar", "scissors",
            "pliers", "metalworkingpliers", "visegrips", "file", "smallfiles",
            "tongs", "crudetongs", "carpentrychisel", "masonschisel", "metalworkingchisel",
            "awl", "boltcutters", "sheetmetalsnips", "lightmetalsnips", "pickaxe",
            "flashlight", "flashlightpillar", "lighter", "refillablelighter",
            "canopener", "knappingtool", "fleshingtool"
    );
    private static final Set<String> UTENSIL_OR_STATIONERY_TAG_KEYS = Set.of(
            "write", "pen", "pencil", "redpen", "bluepen", "greenpen",
            "eraser", "fork", "spoon", "butterknife", "rollingpin", "pizzacutter"
    );
    private static final Set<String> KIT_FISHING_TAGS = Set.of(
            "fishinghook", "fishingline", "fishingnet", "fishingrod", "fishingspear"
    );
    private static final Set<String> KIT_FARMING_TAGS = Set.of(
            "isseed", "fertilizer", "farmingloot", "digplow", "sprayer"
    );
    private static final Set<String> KIT_TAILORING_TAGS = Set.of(
            "sewingneedle", "thread", "thimble", "knittingneedles", "pickaramidthread"
    );
    private static final Set<String> KIT_MECHANIC_TAGS = Set.of(
            "lugwrench", "wholetire", "brake", "carbattery", "siphongas"
    );
    private static final Set<String> LITERATURE_TAGS = Set.of(
            "magazine", "newspaper", "newspaper_new", "newspaperread",
            "hardcover", "softcover", "fancybook", "picturebook", "consumeonread"
    );
    private static final Set<String> AMMO_TAGS = Set.of(
            "ammo", "ammocase", "shotgunshell", "pistolmagazine", "riflemagazine",
            "reloadfastbullets", "reloadfastmagazines", "reloadfastshells"
    );
    private static final Set<String> ARMOR_TAGS = Set.of(
            "gasmask", "respirator", "hazmatsuit", "scba"
    );
    private static final Set<String> APPAREL_TAGS = Set.of(
            "wearable", "gasmask", "respirator", "hazmatsuit", "fitsclothingrack", "long_johns"
    );
    private static final Set<String> BAG_TAGS = Set.of(
            "duffelbag", "largesack", "burlapbag"
    );
    private static final Set<String> ELECTRONICS_TAGS = Set.of(
            "miscelectronic", "usesbattery", "tvremote"
    );

    private WarehouseFastIndex() {}

    private static KahluaTable table() {
        return J2SEPlatform.getInstance().newTable();
    }

    // ==================== 1. 可扩展物品分类规则链 ====================

    public static void registerCategoryOverride(String key, String categoryNode) {
        if (key == null || key.isEmpty() || categoryNode == null || categoryNode.isEmpty()) {
            return;
        }
        CUSTOM_CATEGORY_OVERRIDES.put(key.trim().toLowerCase(Locale.ROOT), categoryNode.trim());
        CATEGORY_CACHE.clear();
    }

    public static void clearCategoryOverrides() {
        CUSTOM_CATEGORY_OVERRIDES.clear();
        CATEGORY_CACHE.clear();
    }

    private static String normalizeTagKey(ItemTag tag) {
        if (tag == null) return "";
        String raw = tag.toString();
        if (raw == null || raw.isEmpty()) return "";
        int colon = raw.lastIndexOf(':');
        int dot = raw.lastIndexOf('.');
        int cut = Math.max(colon, dot);
        String clean = cut >= 0 && cut + 1 < raw.length() ? raw.substring(cut + 1) : raw;
        return clean.toLowerCase(Locale.ROOT);
    }

    public static String classifyItem(InventoryItem item) {
        if (item == null) return "other";
        boolean isDynamicFood = item instanceof Food || item.isFood();
        String ft = item.getFullType();
        if (!isDynamicFood && ft != null) {
            String cached = CATEGORY_CACHE.get(ft);
            if (cached != null) return cached;
        }
        String node = doClassify(item);
        if (ft != null) {
            if (!isDynamicFood) {
                CATEGORY_CACHE.put(ft, node);
            } else {
                CATEGORY_CACHE.putIfAbsent(ft, "scrap.compost".equals(node) ? "food.perishable" : node);
            }
        }
        return node;
    }

    private static String doClassify(InventoryItem item) {
        try {
            String ft = item.getFullType();
            Set<ItemTag> rawTags = null;
            try {
                rawTags = item.getTags();
            } catch (RuntimeException ignored) {
            }

            Set<String> normalizedTags = null;
            if (rawTags != null && !rawTags.isEmpty()) {
                normalizedTags = new HashSet<>(rawTags.size() * 2);
                for (ItemTag tag : rawTags) {
                    String tk = normalizeTagKey(tag);
                    if (!tk.isEmpty()) {
                        normalizedTags.add(tk);
                    }
                }
            }

            boolean isFood = item instanceof Food || item.isFood();
            boolean rotten = false;
            if (item instanceof Food food) {
                rotten = food.isRotten();
            } else if (isFood) {
                rotten = item.IsRotten();
            }
            boolean cooked = item.isCooked();
            boolean perishable = item.getOffAgeMax() < 1_000_000_000;
            boolean isLiterature = item instanceof Literature || item.IsLiterature();
            boolean isContainer = item instanceof InventoryContainer || item.IsInventoryContainer();
            boolean isClothing = item.IsClothing();
            boolean isRanged = item instanceof HandWeapon hw && hw.isRanged();
            boolean hasFluidContainer = item.getFluidContainer() != null;
            Item scriptItem = item.getScriptItem();
            boolean extraMedical = item.getBandagePower() > 0.0F
                    || (scriptItem != null && scriptItem.isMedicalLoot());

            return classifyItemFactsInternal(
                    ft,
                    item.getCategory(),
                    item.getDisplayCategory(),
                    normalizedTags,
                    isFood,
                    rotten,
                    cooked,
                    perishable,
                    isLiterature,
                    isContainer,
                    isClothing,
                    isRanged,
                    hasFluidContainer,
                    extraMedical);
        } catch (RuntimeException ignored) {
        }
        return "other";
    }

    /** 纯数据可单测分类入口：按最常用用途优先级输出大类与细分小类节点。 */
    public static String classifyItemFacts(
            String fullType,
            String category,
            String displayCategory,
            Set<String> normalizedTags,
            boolean isFood,
            boolean rotten,
            boolean cooked,
            boolean perishable,
            boolean isLiterature,
            boolean isContainer,
            boolean isClothing,
            boolean isRanged,
            boolean hasFluidContainer) {
        return classifyItemFactsInternal(
                fullType,
                category,
                displayCategory,
                normalizedTags,
                isFood,
                rotten,
                cooked,
                perishable,
                isLiterature,
                isContainer,
                isClothing,
                isRanged,
                hasFluidContainer,
                false);
    }

    private static String classifyItemFactsInternal(
            String fullType,
            String category,
            String displayCategory,
            Set<String> normalizedTags,
            boolean isFood,
            boolean rotten,
            boolean cooked,
            boolean perishable,
            boolean isLiterature,
            boolean isContainer,
            boolean isClothing,
            boolean isRanged,
            boolean hasFluidContainer,
            boolean extraMedicalSignal) {
        String ftLower = fullType != null ? fullType.trim().toLowerCase(Locale.ROOT) : "";
        String catLower = category != null ? category.trim().toLowerCase(Locale.ROOT) : "";
        String dispLower = displayCategory != null ? displayCategory.trim().toLowerCase(Locale.ROOT) : "";

        // 1. 自定义精确/Tag 覆盖规则最高优先
        if (!ftLower.isEmpty()) {
            String customExact = CUSTOM_CATEGORY_OVERRIDES.get(ftLower);
            if (customExact != null) return customExact;
        }
        Set<String> tags = normalizeTagSet(normalizedTags);
        if (!tags.isEmpty() && !CUSTOM_CATEGORY_OVERRIDES.isEmpty()) {
            for (String tk : tags) {
                String customByTag = CUSTOM_CATEGORY_OVERRIDES.get("tag:" + tk);
                if (customByTag != null) return customByTag;
            }
        }

        // 2. 医疗优先（在食物/武器/工具之前：含手术刀、镊子、缝合针、持针器、药片与抗生素）
        boolean isMedical = extraMedicalSignal
                || "firstaid".equals(catLower)
                || "medical".equals(catLower)
                || "firstaid".equals(dispLower)
                || "firstaidweapon".equals(dispLower)
                || "bandage".equals(dispLower)
                || "medical".equals(dispLower)
                || hasAnyTagIn(tags, MEDICAL_TAG_KEYS)
                || (!ftLower.contains("spear") && ftLower.contains("scalpel"))
                || containsAnyToken(ftLower,
                        "tweezers", "forceps", "suture", "bandage", "alcoholwipes", "cottonballs",
                        "disinfectant", "splint", "poultice", "cataplasm", "comfrey", "plantain",
                        "wildgarlic", "firstaid", "medkit", "stethoscope", "otoscope", "thermometer",
                        "pills", "vitamin", "antibiotic", "painkiller", "sedative",
                        "antidepressant", "betablocker", "sleepingtablet", "antihistamine", "coldmedicine");
        if (isMedical) {
            if (tags.contains("pills")
                    || containsAnyToken(ftLower,
                            "pills", "vitamin", "antibiotic", "painkiller", "sedative",
                            "antidepressant", "betablocker", "sleepingtablet", "antihistamine", "coldmedicine")) {
                return "medical.pills";
            }
            return "medical.bandages";
        }

        // 3. 食物与腐烂堆肥
        if (isFood || "food".equals(catLower) || "food".equals(dispLower)) {
            if (rotten) {
                return "scrap.compost";
            }
            if (cooked || containsAnyToken(ftLower,
                    "soup", "stew", "roast", "salad", "sandwich", "burger", "pizza",
                    "casserole", "stirfry", "omelette", "porridge", "oatmeal", "pasta")
                    || (ftLower.contains("pie") && !ftLower.contains("piece"))) {
                return "food.meals";
            }
            return perishable ? "food.perishable" : "food.stable";
        }

        // 4. 专业套组 kits.*（缝合针已在步骤 2 归医疗）
        if ("fishing".equals(dispLower)
                || "fishingweapon".equals(dispLower)
                || hasAnyTagIn(tags, KIT_FISHING_TAGS)
                || containsAnyToken(ftLower, "fishing", "fishhook", "lure", "tacklebox", "bobber")) {
            return "kits.fishing";
        }
        if ("gardening".equals(dispLower)
                || "farming".equals(dispLower)
                || "gardeningweapon".equals(dispLower)
                || hasAnyTagIn(tags, KIT_FARMING_TAGS)
                || containsAnyToken(ftLower, "seed", "fertilizer", "wateringcan", "plantspray", "gardening")) {
            return "kits.farming";
        }
        if ("tailoring".equals(dispLower)
                || hasAnyTagIn(tags, KIT_TAILORING_TAGS)
                || containsAnyToken(ftLower, "sewing", "thread", "knitting", "thimble", "yarn")
                || (ftLower.contains("needle") && !ftLower.contains("suture"))) {
            return "kits.tailoring";
        }
        if ("vehiclemaintenance".equals(dispLower)
                || "vehiclemaintenanceweapon".equals(dispLower)
                || "mechanics".equals(dispLower)
                || "vehicle".equals(dispLower)
                || hasAnyTagIn(tags, KIT_MECHANIC_TAGS)
                || containsAnyToken(ftLower,
                        "lugwrench", "tirepump", "carbattery", "carjack", "sparkplug",
                        "tireiron", "muffler", "windshield")
                || ftLower.endsWith(".jack")
                || ftLower.endsWith("_jack")) {
            return "kits.mechanic";
        }

        // 5. 文献（先排除弹匣 Ammo 与枪械弹夹 Tag）
        boolean isAmmoTagged = "ammo".equals(catLower)
                || "ammo".equals(dispLower)
                || hasAnyTagIn(tags, AMMO_TAGS);
        if (!isAmmoTagged && (isLiterature
                || "literature".equals(catLower)
                || "literature".equals(dispLower)
                || "skillbook".equals(dispLower)
                || "cartography".equals(dispLower)
                || "entertainment".equals(dispLower)
                || hasAnyTagIn(tags, LITERATURE_TAGS)
                || containsAnyToken(ftLower,
                        "book", "comic", "magazine", "manual", "newspaper", "vhs", "brochure", "flyer")
                || ftLower.contains("_map")
                || ftLower.endsWith(".map"))) {
            if ("skillbook".equals(dispLower)
                    || tags.contains("consumeonread")
                    || containsAnyToken(ftLower, "skill", "recipe", "manual", "vol")) {
                return "literature.skills";
            }
            return "literature.entertainment";
        }

        // 6. 穿戴与背包容器
        if (isContainer
                || "container".equals(catLower)
                || "bag".equals(dispLower)
                || hasAnyTagIn(tags, BAG_TAGS)
                || (!"junk".equals(dispLower) && containsAnyToken(
                        ftLower, "backpack", "duffel", "satchel", "alicepack", "hikingbag", "schoolbag", "fannypack"))) {
            return "apparel.bags";
        }
        if (isClothing
                || "clothing".equals(catLower)
                || "clothing".equals(dispLower)
                || "protectivegear".equals(dispLower)
                || "accessory".equals(dispLower)
                || "appearance".equals(dispLower)
                || hasAnyTagIn(tags, APPAREL_TAGS)) {
            if ("protectivegear".equals(dispLower)
                    || hasAnyTagIn(tags, ARMOR_TAGS)
                    || containsAnyToken(ftLower,
                            "bulletproof", "vest_bullet", "armor", "armour", "helmet",
                            "riot", "gasmask", "kneepad", "elbowpad", "shinpad", "gorget",
                            "vambrace", "greave", "cuirass")) {
                return "apparel.armor";
            }
            return "apparel.clothing";
        }

        // 7. 弹药 / 枪械 / 枪械配件
        if (isAmmoTagged) {
            return "weapons.ammo";
        }
        if (isRanged || tags.contains("firearm")) {
            return "weapons.guns";
        }
        if ("weaponpart".equals(catLower)
                || "weaponpart".equals(dispLower)
                || containsAnyToken(ftLower, "gunpart", "choketube", "reddot", "recoilpad", "bayonet")) {
            return "weapons.parts";
        }

        // 8. 常用工具（优先于 weapons.other）
        if ("tool".equals(catLower)
                || "tool".equals(dispLower)
                || "toolweapon".equals(catLower)
                || "toolweapon".equals(dispLower)
                || "lightsource".equals(dispLower)
                || "firesource".equals(dispLower)
                || hasAnyTagIn(tags, TOOL_TAG_KEYS)) {
            return "tools";
        }

        // 9. 防伪武器误判（文具、餐具、家居日用等最常用并非武器）
        if (hasAnyTagIn(tags, UTENSIL_OR_STATIONERY_TAG_KEYS)
                || containsAnyToken(dispLower, "household", "stationery")
                || "cooking".equals(dispLower)
                || containsAnyToken(ftLower, "rollingpin", "butterknife", "letteropener")) {
            return "other";
        }

        // 10. 废品 / 破损武器（优先于近战武器）
        if ("junk".equals(catLower)
                || "junk".equals(dispLower)
                || "trash".equals(dispLower)
                || "scrap".equals(dispLower)
                || "brokenweapon".equals(dispLower)
                || tags.contains("emptycan")) {
            return "scrap.waste";
        }

        // 11. 近战武器与爆炸物
        if ("weapon".equals(catLower) || dispLower.contains("weapon") || "explosives".equals(dispLower)) {
            return "weapons.other";
        }

        // 12. 电子元件与常规建材五金
        if ("electronics".equals(dispLower)
                || "communications".equals(dispLower)
                || hasAnyTagIn(tags, ELECTRONICS_TAGS)
                || containsAnyToken(ftLower, "electronic", "battery", "circuit", "walkietalkie", "radio")) {
            return "materials.electronics";
        }
        if ("material".equals(catLower)
                || "material".equals(dispLower)
                || "reciperesource".equals(dispLower)
                || "paint".equals(dispLower)) {
            return "materials";
        }

        // 13. 液体容器
        if (hasFluidContainer || "watercontainer".equals(dispLower) || "water".equals(dispLower)) {
            return "fluids";
        }

        return "other";
    }

    private static Set<String> normalizeTagSet(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new HashSet<>(raw.size() * 2);
        for (String tag : raw) {
            if (tag == null) continue;
            String trimmed = tag.trim().toLowerCase(Locale.ROOT);
            int colon = trimmed.lastIndexOf(':');
            int dot = trimmed.lastIndexOf('.');
            int cut = Math.max(colon, dot);
            String clean = cut >= 0 && cut + 1 < trimmed.length() ? trimmed.substring(cut + 1) : trimmed;
            if (!clean.isEmpty()) {
                out.add(clean);
            }
        }
        return out;
    }

    private static boolean hasAnyTagIn(Set<String> tags, Set<String> candidates) {
        if (tags.isEmpty()) {
            return false;
        }
        for (String tk : tags) {
            if (candidates.contains(tk)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyToken(String value, String... tokens) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEligibleItem(InventoryItem item) {
        return item != null && !item.isEquipped();
    }

    private static boolean isItemDegraded(InventoryItem item) {
        if (item == null) return false;
        if (item.isBroken() || item.isBurnt()) return true;
        if (item instanceof Food food && food.isRotten()) return true;
        if (item instanceof DrainableComboItem && item.getCurrentUses() <= 0) return true;
        return false;
    }

    // ==================== 2. 容器准入过滤与单方块私密开关 ====================

    /**
     * 判定世界方块对象是否属于合法的据点储物家具（自动排除活体、尸体、地面散落物、假人、食槽、禽舍、垃圾桶、炉灶、洗衣烘干机等设施）。
     */
    public static boolean isEligibleStorageObject(IsoObject obj) {
        if (obj == null
                || obj instanceof IsoGameCharacter
                || obj instanceof IsoDeadBody
                || obj instanceof IsoWorldInventoryObject
                || obj instanceof IsoMannequin
                || obj instanceof IsoFeedingTrough
                || obj instanceof IsoHutch
                || obj instanceof IsoWheelieBin
                || obj instanceof IsoCompost
                || obj instanceof IsoTrap
                || obj instanceof IsoCarBatteryCharger
                || obj instanceof IsoStove
                || obj instanceof IsoBarbecue
                || obj instanceof IsoFireplace
                || obj instanceof IsoClothingWasher
                || obj instanceof IsoClothingDryer
                || obj instanceof IsoCombinationWasherDryer
                || obj instanceof IsoStackedWasherDryer) {
            return false;
        }
        int contCount = obj.getContainerCount();
        if (contCount <= 0 && obj.getContainer() == null) {
            return false;
        }
        boolean anyStorageContainer = false;
        if (contCount > 0) {
            for (int ci = 0; ci < contCount; ci++) {
                if (isEligibleContainerType(obj.getContainerByIndex(ci))) {
                    anyStorageContainer = true;
                    break;
                }
            }
        } else {
            anyStorageContainer = isEligibleContainerType(obj.getContainer());
        }
        return anyStorageContainer;
    }

    public static boolean isEligibleContainerType(ItemContainer ic) {
        if (ic == null || ic.getCharacter() != null || ic.isVehiclePart()) {
            return false;
        }
        String kind = ic.getType();
        if (kind == null || kind.isEmpty()) {
            return true;
        }
        return !"floor".equalsIgnoreCase(kind)
                && !"bin".equalsIgnoreCase(kind)
                && !"garbage".equalsIgnoreCase(kind)
                && !"inventorymale".equalsIgnoreCase(kind)
                && !"inventoryfemale".equalsIgnoreCase(kind);
    }

    /**
     * 零空表分配读取单方块私密状态：严格先调 {@link IsoObject#hasModData()}，绝不为普通箱子分配空 {@code KahluaTable}。
     */
    public static boolean isObjectPrivate(IsoObject obj) {
        if (obj == null || !obj.hasModData()) {
            return false;
        }
        KahluaTable md = obj.getModData();
        return md != null && Boolean.TRUE.equals(md.rawget(MODDATA_PRIVATE_KEY));
    }

    /**
     * 设置或清除世界储物方块的私密排除标记（直接附着于 {@link IsoObject} 自身，刷漆或拆叠箱绝不漂移）。
     */
    public static boolean setObjectPrivate(IsoObject obj, boolean makePrivate) {
        if (obj == null) return false;
        if (makePrivate) {
            obj.getModData().rawset(MODDATA_PRIVATE_KEY, Boolean.TRUE);
        } else if (obj.hasModData()) {
            obj.getModData().rawset(MODDATA_PRIVATE_KEY, null);
        }
        obj.flagForHotSave();
        IsoGridSquare sq = obj.getSquare();
        if (sq != null) {
            ContainerDirtyQueue.markSquare(sq);
        }
        return isObjectPrivate(obj);
    }

    public static String resolveObjectDisplayName(IsoObject obj) {
        String custom = resolveCustomName(obj);
        if (custom != null && !custom.isEmpty()) {
            return custom;
        }
        if (obj != null) {
            ItemContainer ic = obj.getContainer();
            if (ic != null && ic.getType() != null && !ic.getType().isEmpty()) {
                return ic.getType();
            }
            String objName = obj.getObjectName();
            if (objName != null && !objName.isEmpty()) {
                return objName;
            }
        }
        return "container";
    }

    // ==================== 3. 单箱缓存、两级健康度与总账差量结构 ====================

    static final class ContainerContribution {
        final String containerId;
        final int x, y, z;
        WarehouseFingerprint fingerprint;
        int count;
        int usableCount;
        int degradedCount;
        Map<String, Integer> typeCounts = Collections.emptyMap();
        Map<String, Integer> usableTypeCounts = Collections.emptyMap();
        Map<String, Integer> degradedTypeCounts = Collections.emptyMap();
        Map<String, Integer> tagCounts = Collections.emptyMap();
        double capacity, usedWeight;
        String kind = "container";
        String customName;
        String status = "UNINITIALIZED";
        SmartZoningPolicy.ContainerFilterRule lastRule = new SmartZoningPolicy.ContainerFilterRule();

        ContainerContribution(String containerId, int x, int y, int z) {
            this.containerId = containerId;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static final class Source {
        final String id, kind, customName;
        final int x, y, z, count;
        final boolean loaded;

        Source(ContainerContribution c, int count, boolean loaded) {
            this.id = c.containerId;
            this.kind = c.kind;
            this.customName = c.customName;
            this.x = c.x;
            this.y = c.y;
            this.z = c.z;
            this.count = count;
            this.loaded = loaded;
        }
    }

    static final class Aggregate {
        final TreeMap<String, Integer> typeCounts = new TreeMap<>();
        final TreeMap<String, Integer> usableByType = new TreeMap<>();
        final TreeMap<String, Integer> degradedByType = new TreeMap<>();
        final TreeMap<String, List<Source>> sourcesByType = new TreeMap<>();
        final TreeMap<String, Integer> tagCounts = new TreeMap<>();
        int loaded, unloaded, privateCount;
        int totalItems, loadedItems, usableItems, degradedItems, fullCount;
        double totalCapacity, totalUsedWeight;

        private static void bump(TreeMap<String, Integer> map, String key, int delta) {
            Integer old = map.get(key);
            int next = (old == null ? 0 : old) + delta;
            if (next <= 0) map.remove(key);
            else map.put(key, next);
        }

        void subtract(ContainerContribution c) {
            if (STATUS_PRIVATE.equals(c.status)) {
                privateCount--;
                return;
            }
            boolean isLoaded = STATUS_LOADED.equals(c.status);
            boolean isOfflineCached = STATUS_OFFLINE_CACHED.equals(c.status);
            if (!isLoaded && !isOfflineCached) {
                return;
            }
            if (isLoaded) {
                loaded--;
                loadedItems -= c.count;
            } else {
                unloaded--;
            }
            totalItems -= c.count;
            usableItems -= c.usableCount;
            degradedItems -= c.degradedCount;
            totalCapacity -= c.capacity;
            totalUsedWeight -= c.usedWeight;
            if (c.capacity > 0 && c.usedWeight >= c.capacity * 0.9) {
                fullCount--;
            }
            for (Map.Entry<String, Integer> e : c.typeCounts.entrySet()) {
                bump(typeCounts, e.getKey(), -e.getValue());
                List<Source> list = sourcesByType.get(e.getKey());
                if (list != null) {
                    list.removeIf(s -> c.containerId.equals(s.id));
                    if (list.isEmpty()) sourcesByType.remove(e.getKey());
                }
            }
            for (Map.Entry<String, Integer> e : c.usableTypeCounts.entrySet()) {
                bump(usableByType, e.getKey(), -e.getValue());
            }
            for (Map.Entry<String, Integer> e : c.degradedTypeCounts.entrySet()) {
                bump(degradedByType, e.getKey(), -e.getValue());
            }
            for (Map.Entry<String, Integer> e : c.tagCounts.entrySet()) {
                bump(tagCounts, e.getKey(), -e.getValue());
            }
        }

        void add(ContainerContribution c) {
            if (STATUS_PRIVATE.equals(c.status)) {
                privateCount++;
                return;
            }
            boolean isLoaded = STATUS_LOADED.equals(c.status);
            boolean isOfflineCached = STATUS_OFFLINE_CACHED.equals(c.status);
            if (!isLoaded && !isOfflineCached) {
                return;
            }
            if (isLoaded) {
                loaded++;
                loadedItems += c.count;
            } else {
                unloaded++;
            }
            totalItems += c.count;
            usableItems += c.usableCount;
            degradedItems += c.degradedCount;
            totalCapacity += c.capacity;
            totalUsedWeight += c.usedWeight;
            if (c.capacity > 0 && c.usedWeight >= c.capacity * 0.9) {
                fullCount++;
            }
            for (Map.Entry<String, Integer> e : c.typeCounts.entrySet()) {
                bump(typeCounts, e.getKey(), e.getValue());
                sourcesByType.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(new Source(c, e.getValue(), isLoaded));
            }
            for (Map.Entry<String, Integer> e : c.usableTypeCounts.entrySet()) {
                bump(usableByType, e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Integer> e : c.degradedTypeCounts.entrySet()) {
                bump(degradedByType, e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Integer> e : c.tagCounts.entrySet()) {
                bump(tagCounts, e.getKey(), e.getValue());
            }
        }
    }

    // ==================== 4. AE2 TickManagerService 最小堆动态退避调度器 ====================

    static final class SquareTickTracker implements Comparable<SquareTickTracker> {
        final long sqKey;
        String settlementId;
        int currentRate;
        long nextTick;
        boolean awake;

        SquareTickTracker(long sqKey, String settlementId, long nowTick, int initialRate) {
            this.sqKey = sqKey;
            this.settlementId = settlementId;
            this.currentRate = clampRate(initialRate);
            this.nextTick = nowTick + this.currentRate;
        }

        void modulate(boolean changed, long nowTick) {
            if (changed) {
                this.currentRate = TICK_MIN_RATE; // AE2 TickRateModulation.URGENT
            } else {
                this.currentRate = clampRate(this.currentRate + TICK_SLOW_DOWN_FACTOR); // AE2 TickRateModulation.SLOWER
            }
            this.nextTick = nowTick + this.currentRate;
        }

        private static int clampRate(int r) {
            if (r < TICK_MIN_RATE) return TICK_MIN_RATE;
            if (r > TICK_MAX_RATE) return TICK_MAX_RATE;
            return r;
        }

        @Override
        public int compareTo(SquareTickTracker o) {
            int cmp = Long.compare(this.nextTick, o.nextTick);
            return cmp != 0 ? cmp : Long.compare(this.sqKey, o.sqKey);
        }
    }

    static final class SettlementState {
        final String settlementId;
        final List<int[]> rects = new ArrayList<>(); // 2D rects: [x, y, w, h]
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE; // exclusive
        int maxY = Integer.MIN_VALUE; // exclusive
        final Map<String, ContainerContribution> containers = new HashMap<>();
        final Map<Long, Set<String>> squareIndex = new HashMap<>();
        final Aggregate aggregate = new Aggregate();
        volatile KahluaTable front = null; // null 表示需要按需物化（对标 StorageService.cachedStacksNeedUpdate）
        long revision = 0;

        SettlementState(String settlementId) {
            this.settlementId = settlementId;
        }

        void recomputeBounds() {
            int bx0 = Integer.MAX_VALUE;
            int by0 = Integer.MAX_VALUE;
            int bx1 = Integer.MIN_VALUE;
            int by1 = Integer.MIN_VALUE;
            for (int i = 0, n = rects.size(); i < n; i++) {
                int[] r = rects.get(i);
                if (r[0] < bx0) bx0 = r[0];
                if (r[1] < by0) by0 = r[1];
                int rx1 = r[0] + r[2];
                int ry1 = r[1] + r[3];
                if (rx1 > bx1) bx1 = rx1;
                if (ry1 > by1) by1 = ry1;
            }
            this.minX = bx0;
            this.minY = by0;
            this.maxX = bx1;
            this.maxY = by1;
        }

        boolean intersectsBox(int x0, int y0, int x1, int y1) {
            if (rects.isEmpty() || x1 <= minX || x0 >= maxX || y1 <= minY || y0 >= maxY) {
                return false;
            }
            for (int i = 0, n = rects.size(); i < n; i++) {
                int[] r = rects.get(i);
                if (x1 > r[0] && x0 < r[0] + r[2] && y1 > r[1] && y0 < r[1] + r[3]) {
                    return true;
                }
            }
            return false;
        }

        boolean contains2D(int x, int y) {
            if (x < minX || x >= maxX || y < minY || y >= maxY) {
                return false;
            }
            for (int i = 0, n = rects.size(); i < n; i++) {
                int[] r = rects.get(i);
                if (x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3]) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Map<String, SettlementState> SETTLEMENTS = new ConcurrentHashMap<>();
    private static final Map<Long, SquareTickTracker> TRACKERS = new HashMap<>();
    private static final PriorityQueue<SquareTickTracker> AWAKE_QUEUE = new PriorityQueue<>();
    private static long currentTick = 0L;

    public static void reset() {
        SETTLEMENTS.clear();
        CATEGORY_CACHE.clear();
        TRACKERS.clear();
        AWAKE_QUEUE.clear();
        currentTick = 0L;
        TerritoryRenderer.reset();
        SmartObjectRegistry.reset();
        SmartItemPicker.reset();
    }

    public static int getAwakeTickerCount() {
        return AWAKE_QUEUE.size();
    }

    static SquareTickTracker registerOrWakeTracker(long sqKey, String settlementId, boolean urgent) {
        SquareTickTracker tt = TRACKERS.get(sqKey);
        if (tt == null) {
            int initRate = urgent ? TICK_MIN_RATE : (TICK_MIN_RATE + (int) (Math.abs(sqKey) % 15L));
            tt = new SquareTickTracker(sqKey, settlementId, currentTick, initRate);
            tt.awake = true;
            TRACKERS.put(sqKey, tt);
            AWAKE_QUEUE.add(tt);
        } else {
            tt.settlementId = settlementId;
            if (urgent) {
                if (tt.awake) {
                    AWAKE_QUEUE.remove(tt);
                }
                tt.currentRate = TICK_MIN_RATE;
                tt.nextTick = currentTick + TICK_MIN_RATE;
                tt.awake = true;
                AWAKE_QUEUE.add(tt);
            } else if (!tt.awake) {
                tt.nextTick = currentTick + tt.currentRate;
                tt.awake = true;
                AWAKE_QUEUE.add(tt);
            }
        }
        return tt;
    }

    static void sleepAndRemoveTracker(long sqKey) {
        SquareTickTracker tt = TRACKERS.remove(sqKey);
        if (tt != null && tt.awake) {
            tt.awake = false;
            AWAKE_QUEUE.remove(tt);
        }
    }

    public static void invalidateSettlement(String settlementId) {
        if (settlementId != null) {
            SettlementState removed = SETTLEMENTS.remove(settlementId);
            if (removed != null) {
                for (Long sqKey : removed.squareIndex.keySet()) {
                    sleepAndRemoveTracker(sqKey.longValue());
                }
            }
            TerritoryRenderer.removeSettlement(settlementId);
            SmartObjectRegistry.invalidateSettlement(settlementId);
            SmartItemPicker.clearSettlement(settlementId);
        }
    }

    private static SettlementState findOwner(int x, int y) {
        for (SettlementState s : SETTLEMENTS.values()) {
            if (s.contains2D(x, y)) {
                return s;
            }
        }
        return null;
    }

    public static String findSettlementIdAt(int x, int y) {
        SettlementState s = findOwner(x, y);
        return s != null ? s.settlementId : null;
    }

    public static boolean containsPoint(String settlementId, int x, int y) {
        if (settlementId == null) {
            return false;
        }
        SettlementState s = SETTLEMENTS.get(settlementId);
        return s != null && s.contains2D(x, y);
    }

    /** 返回指定据点领地的代表锚点格坐标 {@code {x, y}}（取最大连通子矩形中心），若据点不存在或无领地返回 {@code null}。 */
    public static int[] getSettlementAnchorTile(String settlementId) {
        if (settlementId == null) {
            return null;
        }
        SettlementState s = SETTLEMENTS.get(settlementId);
        if (s == null || s.rects.isEmpty()) {
            return null;
        }
        int[] best = s.rects.get(0);
        long bestArea = (long) best[2] * best[3];
        for (int i = 1, n = s.rects.size(); i < n; i++) {
            int[] r = s.rects.get(i);
            long area = (long) r[2] * r[3];
            if (area > bestArea) {
                bestArea = area;
                best = r;
            }
        }
        return new int[]{best[0] + best[2] / 2, best[1] + best[3] / 2};
    }

    /** 计算世界 2D 坐标点到据点领地最近矩形边界的距离（在领地内返回 {@code 0.0}，无据点返回 {@code Double.MAX_VALUE}）。 */
    public static double distanceToSettlement2D(String settlementId, double x, double y) {
        if (settlementId == null) {
            return Double.MAX_VALUE;
        }
        SettlementState s = SETTLEMENTS.get(settlementId);
        if (s == null || s.rects.isEmpty()) {
            return Double.MAX_VALUE;
        }
        double minDistSq = Double.MAX_VALUE;
        for (int i = 0, n = s.rects.size(); i < n; i++) {
            int[] r = s.rects.get(i);
            double x0 = r[0];
            double y0 = r[1];
            double x1 = r[0] + r[2];
            double y1 = r[1] + r[3];
            double dx = x < x0 ? (x0 - x) : (x > x1 ? (x - x1) : 0.0);
            double dy = y < y0 ? (y0 - y) : (y > y1 ? (y - y1) : 0.0);
            double dSq = dx * dx + dy * dy;
            if (dSq == 0.0) {
                return 0.0;
            }
            if (dSq < minDistSq) {
                minDistSq = dSq;
            }
        }
        return Math.sqrt(minDistSq);
    }

    public record LoadedSource(String containerId, int x, int y, int z, int count, String kind, String customName) {}

    public record LiveContainerRef(IsoObject object, ItemContainer container, IsoGridSquare square, String containerId) {}

    /** 查询指定据点当前已加载在场的所有 {@code fullType} 真实容器来源列表。 */
    public static List<LoadedSource> getLoadedSourcesByType(String settlementId, String fullType) {
        if (settlementId == null || fullType == null) {
            return Collections.emptyList();
        }
        SettlementState s = SETTLEMENTS.get(settlementId);
        if (s == null) {
            return Collections.emptyList();
        }
        List<Source> raw = s.aggregate.sourcesByType.get(fullType);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<LoadedSource> out = new ArrayList<>(raw.size());
        for (int i = 0, n = raw.size(); i < n; i++) {
            Source src = raw.get(i);
            if (src.loaded && src.count > 0) {
                out.add(new LoadedSource(src.id, src.x, src.y, src.z, src.count, src.kind, src.customName));
            }
        }
        return out;
    }

    /** 根据稳定 {@code containerId}（{@code x:y:z#sprite:kind:ci#ord}）解析实时世界对象与容器。 */
    public static LiveContainerRef resolveLiveContainer(String containerId) {
        return resolveLiveContainer(containerId, false);
    }

    /** 根据稳定 {@code containerId} 解析实时世界对象与容器（可选包含 {@code ER_Private} 私密容器）。 */
    public static LiveContainerRef resolveLiveContainer(String containerId, boolean includePrivate) {
        if (containerId == null || containerId.isEmpty() || IsoWorld.instance == null) {
            return null;
        }
        IsoCell cell = IsoWorld.instance.currentCell;
        if (cell == null) {
            return null;
        }
        int firstHash = containerId.indexOf('#');
        int secondHash = firstHash > 0 ? containerId.indexOf('#', firstHash + 1) : -1;
        if (firstHash <= 0 || secondHash <= firstHash + 1) {
            return null;
        }
        String[] xyz = containerId.substring(0, firstHash).split(":");
        if (xyz.length < 3) {
            return null;
        }
        int x, y, z, targetOrd;
        try {
            x = Integer.parseInt(xyz[0]);
            y = Integer.parseInt(xyz[1]);
            z = Integer.parseInt(xyz[2]);
            targetOrd = Integer.parseInt(containerId.substring(secondHash + 1));
        } catch (NumberFormatException ignored) {
            return null;
        }
        String targetSig = containerId.substring(firstHash + 1, secondHash);

        IsoGridSquare sq = cell.getGridSquare(x, y, z);
        if (sq == null) {
            return null;
        }
        PZArrayList<IsoObject> objects = sq.getObjects();
        if (objects == null) {
            return null;
        }
        int matchedOrd = 0;
        for (int oi = 0, objSize = objects.size(); oi < objSize; oi++) {
            IsoObject obj = objects.get(oi);
            if (!isEligibleStorageObject(obj) || (!includePrivate && isObjectPrivate(obj))) {
                continue;
            }
            String spriteName = resolveSpriteName(obj);
            int contCount = resolveContainerCount(obj);
            for (int ci = 0; ci < contCount; ci++) {
                ItemContainer ic = resolveEligibleContainerAt(obj, ci);
                if (ic == null) {
                    continue;
                }
                String kind = resolveContainerKind(ic);
                String sig = spriteName + ":" + kind + ":" + ci;
                if (targetSig.equals(sig)) {
                    if (matchedOrd == targetOrd) {
                        return new LiveContainerRef(obj, ic, sq, containerId);
                    }
                    matchedOrd++;
                }
            }
        }
        return null;
    }

    public static IsoObject resolveContainerObject(String containerId) {
        LiveContainerRef ref = resolveLiveContainer(containerId, true);
        return ref != null ? ref.object() : null;
    }

    public static String resolveObjectContainerId(IsoObject targetObj) {
        if (targetObj == null || !isEligibleStorageObject(targetObj)) {
            return null;
        }
        IsoGridSquare sq = targetObj.getSquare();
        if (sq == null) {
            return null;
        }
        PZArrayList<IsoObject> objects = sq.getObjects();
        if (objects == null) {
            return null;
        }
        Map<String, Integer> stackOrdinals = new HashMap<>();
        int x = sq.getX(), y = sq.getY(), z = sq.getZ();
        for (int oi = 0, objSize = objects.size(); oi < objSize; oi++) {
            IsoObject obj = objects.get(oi);
            if (!isEligibleStorageObject(obj)) {
                continue;
            }
            String spriteName = resolveSpriteName(obj);
            int contCount = resolveContainerCount(obj);
            for (int ci = 0; ci < contCount; ci++) {
                ItemContainer ic = resolveEligibleContainerAt(obj, ci);
                if (ic == null) continue;
                String sig = spriteName + ":" + resolveContainerKind(ic) + ":" + ci;
                int ord = stackOrdinals.getOrDefault(sig, 0);
                stackOrdinals.put(sig, ord + 1);
                if (obj == targetObj) {
                    return x + ":" + y + ":" + z + "#" + sig + "#" + ord;
                }
            }
        }
        return null;
    }

    private static int roleSortRank(ContainerRole role, boolean isPrivate) {
        if (isPrivate) return 3;
        return switch (role) {
            case PICKUP_OUT -> 0;
            case BUFFER_IN -> 1;
            case STORAGE -> 2;
        };
    }

    /** 列出据点全量已纳管容器（含在场、离屏与私密），按角色与优先级排序供 UI 清单与配置向导使用。 */
    public static KahluaTable listSettlementContainers(String settlementId) {
        KahluaTable out = table();
        if (settlementId == null || settlementId.isEmpty()) {
            return out;
        }
        SettlementState s = SETTLEMENTS.get(settlementId);
        if (s == null || s.containers.isEmpty()) {
            return out;
        }

        record ContainerView(
                ContainerContribution contrib,
                SmartZoningPolicy.ContainerFilterRule rule,
                boolean isPrivate,
                boolean loaded,
                String displayName) {}

        List<ContainerView> list = new ArrayList<>(s.containers.size());
        for (ContainerContribution c : s.containers.values()) {
            boolean isPrivate = STATUS_PRIVATE.equals(c.status);
            boolean loaded = STATUS_LOADED.equals(c.status);
            if (!isPrivate && !loaded && !STATUS_OFFLINE_CACHED.equals(c.status)) {
                continue;
            }
            SmartSlot slot = SmartObjectRegistry.getContainerSlot(settlementId, c.containerId);
            SmartZoningPolicy.ContainerFilterRule rule = slot != null && slot.getFilterRule() != null
                    ? slot.getFilterRule()
                    : (c.lastRule != null ? c.lastRule : new SmartZoningPolicy.ContainerFilterRule());
            String disp = (c.customName != null && !c.customName.isEmpty())
                    ? c.customName
                    : (c.kind != null && !c.kind.isEmpty() ? c.kind : "container");
            list.add(new ContainerView(c, rule, isPrivate, loaded, disp));
        }

        list.sort((a, b) -> {
            int rCmp = Integer.compare(
                    roleSortRank(a.rule.role, a.isPrivate),
                    roleSortRank(b.rule.role, b.isPrivate));
            if (rCmp != 0) return rCmp;
            int pCmp = Integer.compare(b.rule.priority, a.rule.priority);
            if (pCmp != 0) return pCmp;
            int nCmp = a.displayName.compareToIgnoreCase(b.displayName);
            if (nCmp != 0) return nCmp;
            int xCmp = Integer.compare(a.contrib.x, b.contrib.x);
            if (xCmp != 0) return xCmp;
            int yCmp = Integer.compare(a.contrib.y, b.contrib.y);
            if (yCmp != 0) return yCmp;
            return Integer.compare(a.contrib.z, b.contrib.z);
        });

        for (int i = 0; i < list.size(); i++) {
            ContainerView v = list.get(i);
            KahluaTable row = SmartZoningPolicy.ruleToKahluaTable(v.rule, v.isPrivate);
            row.rawset("containerId", v.contrib.containerId);
            row.rawset("displayName", v.displayName);
            row.rawset("kind", v.contrib.kind);
            row.rawset("x", Double.valueOf(v.contrib.x));
            row.rawset("y", Double.valueOf(v.contrib.y));
            row.rawset("z", Double.valueOf(v.contrib.z));
            row.rawset("loaded", Boolean.valueOf(v.loaded));
            row.rawset("status", v.contrib.status);
            row.rawset("itemCount", Double.valueOf(v.contrib.count));
            row.rawset("usedWeight", Double.valueOf(v.contrib.usedWeight));
            row.rawset("capacity", Double.valueOf(v.contrib.capacity));
            out.rawset(Double.valueOf(i + 1), row);
        }
        return out;
    }

    /** 枚举目标容器当前在场样本物品，供向导右键加白/排除快速操作。 */
    public static KahluaTable getContainerSampleItems(IsoObject obj) {
        KahluaTable out = table();
        if (obj == null || !isEligibleStorageObject(obj)) {
            return out;
        }
        final class SampleAgg {
            final String fullType;
            String displayName;
            int count;
            final String node;

            SampleAgg(String fullType, String displayName, String node) {
                this.fullType = fullType;
                this.displayName = displayName;
                this.node = node;
            }
        }
        Map<String, SampleAgg> byType = new HashMap<>();
        int contCount = resolveContainerCount(obj);
        for (int ci = 0; ci < contCount; ci++) {
            ItemContainer ic = resolveEligibleContainerAt(obj, ci);
            if (ic == null) continue;
            ArrayList<InventoryItem> items = ic.getItems();
            if (items == null) continue;
            for (int i = 0, n = items.size(); i < n; i++) {
                InventoryItem itm = items.get(i);
                if (!isEligibleItem(itm)) continue;
                String ft = itm.getFullType();
                if (ft == null || ft.isEmpty()) continue;
                SampleAgg agg = byType.get(ft);
                if (agg == null) {
                    String dn = itm.getDisplayName();
                    if (dn == null || dn.isEmpty()) dn = ft;
                    agg = new SampleAgg(ft, dn, classifyItem(itm));
                    byType.put(ft, agg);
                }
                agg.count++;
            }
        }
        List<SampleAgg> sorted = new ArrayList<>(byType.values());
        sorted.sort(Comparator.<SampleAgg>comparingInt(a -> a.count).reversed()
                .thenComparing(a -> a.displayName, String.CASE_INSENSITIVE_ORDER));
        for (int i = 0; i < sorted.size(); i++) {
            SampleAgg a = sorted.get(i);
            KahluaTable row = table();
            row.rawset("fullType", a.fullType);
            row.rawset("displayName", a.displayName);
            row.rawset("count", Double.valueOf(a.count));
            row.rawset("node", a.node);
            out.rawset(Double.valueOf(i + 1), row);
        }
        return out;
    }

    private static String resolveSpriteName(IsoObject obj) {
        String spriteName = obj.getSpriteName();
        return (spriteName == null || spriteName.isEmpty()) ? "nosprite" : spriteName;
    }

    private static int resolveContainerCount(IsoObject obj) {
        int cnt = obj.getContainerCount();
        return (cnt <= 0 && obj.getContainer() != null) ? 1 : cnt;
    }

    private static ItemContainer resolveEligibleContainerAt(IsoObject obj, int ci) {
        ItemContainer ic = obj.getContainerByIndex(ci);
        if (ic == null && ci == 0) {
            ic = obj.getContainer();
        }
        return isEligibleContainerType(ic) ? ic : null;
    }

    private static String resolveContainerKind(ItemContainer ic) {
        String kind = ic.getType();
        return (kind == null || kind.isEmpty()) ? "container" : kind;
    }

    public static double typeCount(String settlementId, String fullType) {
        if (settlementId == null || fullType == null) return 0;
        SettlementState s = SETTLEMENTS.get(settlementId);
        if (s == null) return 0;
        Integer n = s.aggregate.typeCounts.get(fullType);
        return n == null ? 0 : n.doubleValue();
    }

    public static double usableTypeCount(String settlementId, String fullType) {
        if (settlementId == null || fullType == null) return 0;
        SettlementState s = SETTLEMENTS.get(settlementId);
        if (s == null) return 0;
        Integer n = s.aggregate.usableByType.get(fullType);
        return n == null ? 0 : n.doubleValue();
    }

    public static long getRevision(String settlementId) {
        if (settlementId == null) return 0;
        SettlementState s = SETTLEMENTS.get(settlementId);
        return s == null ? 0 : s.revision;
    }

    /**
     * 懒惰标记快照失效（对标 AE2 {@code StorageService.invalidateCache}）：
     * 后台变动期间 0 {@code KahluaTable} 分配，直到 UI 真正调用 {@link #getSnapshot(String)} 时才按需组装一次。
     */
    private static void markSnapshotDirty(SettlementState s) {
        s.revision++;
        s.front = null;
    }

    public static KahluaTable getSnapshot(String settlementId) {
        if (settlementId == null) return null;
        SettlementState s = SETTLEMENTS.get(settlementId);
        if (s == null) return null;
        KahluaTable cached = s.front;
        if (cached == null) {
            cached = compileSnapshot(s);
            s.front = cached;
        }
        return cached;
    }

    private static int toInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /**
     * 同步据点 2D 领地矩形列表（扁平数组 {x1, y1, w1, h1, x2, y2, w2, h2, ...}）：
     * 1. 自动摘除已被裁剪移出领地的旧容器贡献与节拍器；
     * 2. 自动扫描领地内全部已加载区块的所有有效楼层（含 B42 地下室 minLevel 至顶层 maxLevel）。
     */
    public static double syncSettlementAreas(String settlementId, KahluaTable flatRects) {
        if (settlementId == null) return 0;
        SettlementState s = SETTLEMENTS.computeIfAbsent(settlementId, SettlementState::new);
        List<int[]> rawRects = new ArrayList<>();
        if (flatRects != null) {
            int len = flatRects.len();
            for (int i = 1; i + 3 <= len; i += 4) {
                int rx = toInt(flatRects.rawget(Double.valueOf(i)));
                int ry = toInt(flatRects.rawget(Double.valueOf(i + 1)));
                int rw = toInt(flatRects.rawget(Double.valueOf(i + 2)));
                int rh = toInt(flatRects.rawget(Double.valueOf(i + 3)));
                if (rw > 0 && rh > 0) {
                    rawRects.add(new int[]{rx, ry, rw, rh});
                }
            }
        }
        s.rects.clear();
        s.rects.addAll(TerritoryContour.normalizeDisjoint(rawRects));
        s.recomputeBounds();
        TerritoryRenderer.syncSettlement(settlementId, s.rects);
        SmartObjectRegistry.trimOutsideRects(s.settlementId, s.rects);

        boolean changed = s.revision == 0;

        // 1. 剔除不再落于新领地内的已索引格（含在场与离屏缓存）
        Iterator<Map.Entry<Long, Set<String>>> it = s.squareIndex.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Set<String>> entry = it.next();
            long packed = entry.getKey().longValue();
            int sx = ContainerDirtyQueue.unpackX(packed);
            int sy = ContainerDirtyQueue.unpackY(packed);
            if (!s.contains2D(sx, sy)) {
                for (String cid : entry.getValue()) {
                    ContainerContribution removed = s.containers.remove(cid);
                    if (removed != null) {
                        s.aggregate.subtract(removed);
                        SmartObjectRegistry.onContainerRemoved(s.settlementId, cid);
                        changed = true;
                    }
                }
                sleepAndRemoveTracker(packed);
                it.remove();
            }
        }

        // 2. 扫描领地内所有已加载区块的全楼层世界容器
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell != null) {
            for (int ri = 0, rn = s.rects.size(); ri < rn; ri++) {
                int[] r = s.rects.get(ri);
                int rx = r[0], ry = r[1], rw = r[2], rh = r[3];
                int lastCx = Integer.MIN_VALUE, lastCy = Integer.MIN_VALUE;
                IsoChunk cachedChunk = null;
                int minZ = 0, maxZ = 7;

                for (int x = rx; x < rx + rw; x++) {
                    int cx = Math.floorDiv(x, 8);
                    for (int y = ry; y < ry + rh; y++) {
                        int cy = Math.floorDiv(y, 8);
                        if (cx != lastCx || cy != lastCy) {
                            lastCx = cx;
                            lastCy = cy;
                            cachedChunk = cell.getChunkForGridSquare(x, y, 0);
                            if (cachedChunk != null) {
                                minZ = cachedChunk.getMinLevel();
                                maxZ = cachedChunk.getMaxLevel();
                            } else {
                                minZ = 0;
                                maxZ = 7;
                            }
                        }
                        for (int z = minZ; z <= maxZ; z++) {
                            IsoGridSquare sq = cell.getGridSquare(x, y, z);
                            if (sq != null && scanSquareInternal(s, sq, false)) {
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        if (changed) {
            markSnapshotDirty(s);
        }
        return s.revision;
    }

    /**
     * 主线程零分配排空 {@code @Patch} 脏格队列（对应 AE2 {@code alertDevice} 即时通道，暂停期亦可安全调用）。
     */
    public static boolean flushDirtyQueue() {
        if (!ContainerDirtyQueue.hasDirty()) {
            return false;
        }
        Set<SettlementState> touched = new HashSet<>();
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;

        ContainerDirtyQueue.drain(packed -> {
            int x = ContainerDirtyQueue.unpackX(packed);
            int y = ContainerDirtyQueue.unpackY(packed);
            int z = ContainerDirtyQueue.unpackZ(packed);
            SettlementState owner = findOwner(x, y);
            if (owner == null) {
                sleepAndRemoveTracker(packed);
                return;
            }
            IsoGridSquare sq = cell != null ? cell.getGridSquare(x, y, z) : null;
            boolean changed = sq != null
                    ? scanSquareInternal(owner, sq, true)
                    : markSquareUnloadedInternal(owner, x, y, z);
            if (changed || owner.revision == 0) {
                touched.add(owner);
            }
        });

        for (SettlementState s : touched) {
            markSnapshotDirty(s);
        }
        return !touched.isEmpty();
    }

    /**
     * 运行态每 Tick 入口：先排空 {@code @Patch} 即时脏队列，再按最小堆到期时间推进最多 {@link #MAX_PROBES_PER_TICK} 个外部容器格，
     * 检出原位变化置 {@code URGENT(5)}，静止每次 {@code +1} 退避至 {@code SLOWER(60)}。
     */
    public static boolean tickWarehouse() {
        boolean anyChanged = flushDirtyQueue();
        currentTick++;
        if (AWAKE_QUEUE.isEmpty()) {
            return anyChanged;
        }

        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        int probed = 0;
        while (probed < MAX_PROBES_PER_TICK && !AWAKE_QUEUE.isEmpty()) {
            SquareTickTracker head = AWAKE_QUEUE.peek();
            if (head.nextTick > currentTick) {
                break;
            }
            AWAKE_QUEUE.poll();
            head.awake = false;
            probed++;

            SettlementState owner = SETTLEMENTS.get(head.settlementId);
            int x = ContainerDirtyQueue.unpackX(head.sqKey);
            int y = ContainerDirtyQueue.unpackY(head.sqKey);
            int z = ContainerDirtyQueue.unpackZ(head.sqKey);
            if (owner == null || !owner.contains2D(x, y)) {
                TRACKERS.remove(head.sqKey);
                continue;
            }

            IsoGridSquare sq = cell != null ? cell.getGridSquare(x, y, z) : null;
            if (sq == null) {
                if (markSquareUnloadedInternal(owner, x, y, z)) {
                    markSnapshotDirty(owner);
                    anyChanged = true;
                }
                continue;
            }

            boolean sqChanged = scanSquareInternal(owner, sq, false);
            if (sqChanged) {
                markSnapshotDirty(owner);
                anyChanged = true;
            }

            Set<String> activeOnSq = owner.squareIndex.get(head.sqKey);
            if (activeOnSq != null && !activeOnSq.isEmpty() && TRACKERS.get(head.sqKey) == head) {
                head.modulate(sqChanged, currentTick);
                head.awake = true;
                AWAKE_QUEUE.add(head);
            } else {
                TRACKERS.remove(head.sqKey);
            }
        }
        return anyChanged;
    }

    public static void onSquareLoaded(IsoGridSquare sq) {
        if (sq == null) return;
        SettlementState owner = findOwner(sq.getX(), sq.getY());
        if (owner != null && scanSquareInternal(owner, sq, true)) {
            markSnapshotDirty(owner);
        }
    }

    public static void onSquareUnloaded(IsoGridSquare sq) {
        if (sq == null) return;
        SettlementState owner = findOwner(sq.getX(), sq.getY());
        if (owner != null && markSquareUnloadedInternal(owner, sq.getX(), sq.getY(), sq.getZ())) {
            markSnapshotDirty(owner);
        }
    }

    /**
     * Chunk 级主线程区块加载感知（由底层 Chunk 传感器直接触发，零 Kahlua 跨语言调用）：
     * 野外无据点相交的 Chunk 仅做 O(1) 整数包围盒判断即直接返回。
     */
    public static void onChunkLoaded(IsoChunk chunk) {
        processChunkSquares(chunk, true);
    }

    /**
     * Chunk 级主线程区块回收感知（在 {@code IsoChunk.doReuseGridsquares @OnEnter} 触发，零 Kahlua 跨语言调用）：
     * 将被回收的据点容器切换为 {@code OFFLINE_CACHED} 保留最后已知快照并停掉对应节拍器。
     */
    public static void onChunkUnloaded(IsoChunk chunk) {
        SmartObjectRegistry.onChunkUnloaded(chunk);
        processChunkSquares(chunk, false);
    }

    private static void processChunkSquares(IsoChunk chunk, boolean loaded) {
        if (chunk == null || SETTLEMENTS.isEmpty()) return;
        int x0 = chunk.wx * 8;
        int y0 = chunk.wy * 8;
        List<SettlementState> overlapping = collectOverlappingSettlements(x0, y0, x0 + 8, y0 + 8);
        if (overlapping == null) return;

        int minZ = chunk.getMinLevel();
        int maxZ = chunk.getMaxLevel();
        Set<SettlementState> dirty = null;
        for (int z = minZ; z <= maxZ; z++) {
            for (int ly = 0; ly < 8; ly++) {
                int wy = y0 + ly;
                for (int lx = 0; lx < 8; lx++) {
                    int wx = x0 + lx;
                    SettlementState owner = findOwnerInList(overlapping, wx, wy);
                    if (owner == null) continue;
                    IsoGridSquare sq = chunk.getGridSquare(lx, ly, z);
                    if (sq == null) continue;
                    boolean changed = loaded
                            ? scanSquareInternal(owner, sq, true)
                            : markSquareUnloadedInternal(owner, wx, wy, z);
                    if (changed) {
                        if (dirty == null) dirty = new HashSet<>();
                        dirty.add(owner);
                    }
                }
            }
        }
        if (dirty != null) {
            for (SettlementState s : dirty) {
                markSnapshotDirty(s);
            }
        }
    }

    private static List<SettlementState> collectOverlappingSettlements(int x0, int y0, int x1, int y1) {
        List<SettlementState> matched = null;
        for (SettlementState s : SETTLEMENTS.values()) {
            if (s.intersectsBox(x0, y0, x1, y1)) {
                if (matched == null) matched = new ArrayList<>(2);
                matched.add(s);
            }
        }
        return matched;
    }

    private static SettlementState findOwnerInList(List<SettlementState> list, int x, int y) {
        for (int i = 0, n = list.size(); i < n; i++) {
            SettlementState s = list.get(i);
            if (s.contains2D(x, y)) return s;
        }
        return null;
    }

    /**
     * 区块卸载处理（僵毁专属修正：保留 {@code OFFLINE_CACHED} 最后已知快照而非清零，同时停掉该格轮询节拍器）。
     */
    static boolean markSquareUnloadedInternal(SettlementState s, int x, int y, int z) {
        SmartObjectRegistry.unregisterSquare(s.settlementId, x, y, z);
        long sqKey = ContainerDirtyQueue.packCoord(x, y, z);
        sleepAndRemoveTracker(sqKey);
        Set<String> ids = s.squareIndex.get(sqKey);
        if (ids == null || ids.isEmpty()) return false;
        boolean changed = false;
        for (String id : ids) {
            ContainerContribution c = s.containers.get(id);
            if (c != null && STATUS_LOADED.equals(c.status)) {
                s.aggregate.subtract(c);
                c.fingerprint = null; // 下次重进区块强制与真实容器复核
                c.status = STATUS_OFFLINE_CACHED;
                s.aggregate.add(c);
                changed = true;
            }
        }
        return changed;
    }

    private static String resolveCustomName(IsoObject obj) {
        if (obj == null) return null;
        String name = obj.getName();
        if (name != null && !name.isEmpty()) return name;
        PropertyContainer props = obj.getProperties();
        if (props != null && props.has("CustomName")) {
            String group = props.get("GroupName");
            String custom = props.get("CustomName");
            if (group != null && !group.isEmpty() && custom != null && !custom.isEmpty()) {
                return group + " " + custom;
            }
            if (custom != null && !custom.isEmpty()) return custom;
        }
        return null;
    }

    private static boolean scanSquareInternal(SettlementState s, IsoGridSquare sq, boolean urgentWake) {
        int x = sq.getX(), y = sq.getY(), z = sq.getZ();
        long sqKey = ContainerDirtyQueue.packCoord(x, y, z);
        PZArrayList<IsoObject> objects = sq.getObjects();
        Set<String> seenIds = new HashSet<>();
        Map<String, Integer> stackOrdinals = new HashMap<>();
        boolean changed = false;

        if (objects != null) {
            int objSize = objects.size();
            for (int oi = 0; oi < objSize; oi++) {
                IsoObject obj = objects.get(oi);
                if (!isEligibleStorageObject(obj)) {
                    continue;
                }

                String spriteName = resolveSpriteName(obj);
                String customName = resolveCustomName(obj);
                boolean markedPrivate = isObjectPrivate(obj);

                int contCount = resolveContainerCount(obj);
                for (int ci = 0; ci < contCount; ci++) {
                    ItemContainer ic = resolveEligibleContainerAt(obj, ci);
                    if (ic == null) continue;

                    String kind = resolveContainerKind(ic);
                    String sig = spriteName + ":" + kind + ":" + ci;
                    int ord = stackOrdinals.getOrDefault(sig, 0);
                    stackOrdinals.put(sig, ord + 1);

                    String cid = x + ":" + y + ":" + z + "#" + sig + "#" + ord;
                    seenIds.add(cid);

                    SmartZoningPolicy.ContainerFilterRule zoneRule = SmartZoningPolicy.readRule(obj);
                    ContainerContribution existing = s.containers.get(cid);
                    if (markedPrivate) {
                        SmartObjectRegistry.onContainerRemoved(s.settlementId, cid);
                        if (existing != null && STATUS_PRIVATE.equals(existing.status)) {
                            existing.lastRule = zoneRule != null
                                    ? zoneRule.copy()
                                    : new SmartZoningPolicy.ContainerFilterRule();
                            continue;
                        }
                        if (existing != null) {
                            s.aggregate.subtract(existing);
                        } else {
                            existing = new ContainerContribution(cid, x, y, z);
                            s.containers.put(cid, existing);
                        }
                        existing.fingerprint = null;
                        existing.count = 0;
                        existing.usableCount = 0;
                        existing.degradedCount = 0;
                        existing.typeCounts = Collections.emptyMap();
                        existing.usableTypeCounts = Collections.emptyMap();
                        existing.degradedTypeCounts = Collections.emptyMap();
                        existing.tagCounts = Collections.emptyMap();
                        existing.capacity = 0;
                        existing.usedWeight = 0;
                        existing.kind = kind;
                        existing.customName = customName;
                        existing.status = STATUS_PRIVATE;
                        existing.lastRule = zoneRule != null
                                ? zoneRule.copy()
                                : new SmartZoningPolicy.ContainerFilterRule();
                        s.aggregate.add(existing);
                        changed = true;
                        continue;
                    }

                    double cap = ic.getCapacity();
                    if (existing != null
                            && STATUS_LOADED.equals(existing.status)
                            && existing.fingerprint != null
                            && Double.compare(existing.capacity, cap) == 0
                            && Objects.equals(existing.kind, kind)
                            && Objects.equals(existing.customName, customName)
                            && existing.fingerprint.matchesContainer(ic)) {
                        existing.lastRule = zoneRule != null
                                ? zoneRule.copy()
                                : new SmartZoningPolicy.ContainerFilterRule();
                        if (hasSmartZoneRuleChanged(s.settlementId, cid, zoneRule)) {
                            SmartObjectRegistry.onContainerScanned(
                                    s.settlementId, cid, sq, zoneRule, existing.count, cap, existing.usedWeight);
                            changed = true;
                        }
                        continue;
                    }

                    WarehouseFingerprint fp = WarehouseFingerprint.of(ic);
                    double used = ic.getContentsWeight();

                    if (existing != null) {
                        s.aggregate.subtract(existing);
                    } else {
                        existing = new ContainerContribution(cid, x, y, z);
                        s.containers.put(cid, existing);
                    }

                    existing.lastRule = zoneRule != null
                            ? zoneRule.copy()
                            : new SmartZoningPolicy.ContainerFilterRule();
                    populateLoadedContribution(existing, ic, fp, cap, used, kind, customName);
                    s.aggregate.add(existing);
                    SmartObjectRegistry.onContainerScanned(
                            s.settlementId, cid, sq, zoneRule, existing.count, cap, used);
                    changed = true;
                }
            }
        }

        Set<String> prevIds = s.squareIndex.get(sqKey);
        if (prevIds != null) {
            for (String oldId : prevIds) {
                if (!seenIds.contains(oldId)) {
                    ContainerContribution old = s.containers.remove(oldId);
                    if (old != null) {
                        s.aggregate.subtract(old);
                        SmartObjectRegistry.onContainerRemoved(s.settlementId, oldId);
                        changed = true;
                    }
                }
            }
        }

        if (seenIds.isEmpty()) {
            s.squareIndex.remove(sqKey);
            sleepAndRemoveTracker(sqKey);
        } else {
            s.squareIndex.put(sqKey, seenIds);
            registerOrWakeTracker(sqKey, s.settlementId, urgentWake);
        }
        SmartObjectRegistry.onSquareScanned(s.settlementId, sq);
        return changed;
    }

    private static boolean hasSmartZoneRuleChanged(
            String settlementId, String containerId, SmartZoningPolicy.ContainerFilterRule rule) {
        SmartSlot slot = SmartObjectRegistry.getContainerSlot(settlementId, containerId);
        if (slot == null) {
            return true;
        }
        SmartZoningPolicy.ContainerFilterRule cur = slot.getFilterRule();
        if (cur == null) {
            return true;
        }
        return cur.role != rule.role
                || cur.priority != rule.priority
                || cur.enabled != rule.enabled
                || !cur.allowGroups.equals(rule.allowGroups)
                || !cur.denyGroups.equals(rule.denyGroups)
                || !cur.allowTypes.equals(rule.allowTypes)
                || !cur.denyTypes.equals(rule.denyTypes);
    }

    private static void populateLoadedContribution(
            ContainerContribution c,
            ItemContainer ic,
            WarehouseFingerprint fp,
            double cap,
            double used,
            String kind,
            String customName) {
        ArrayList<InventoryItem> items = ic.getItems();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> usableCounts = new HashMap<>();
        Map<String, Integer> degradedCounts = new HashMap<>();
        Map<String, Integer> tags = new HashMap<>();
        int eligibleCount = 0;
        int usableTotal = 0;
        int degradedTotal = 0;

        if (items != null) {
            int sz = items.size();
            for (int i = 0; i < sz; i++) {
                InventoryItem itm = items.get(i);
                if (!isEligibleItem(itm)) continue;
                String ft = itm.getFullType();
                if (ft != null) {
                    counts.put(ft, counts.getOrDefault(ft, 0) + 1);
                    eligibleCount++;
                    if (isItemDegraded(itm)) {
                        degradedCounts.put(ft, degradedCounts.getOrDefault(ft, 0) + 1);
                        degradedTotal++;
                    } else {
                        usableCounts.put(ft, usableCounts.getOrDefault(ft, 0) + 1);
                        usableTotal++;
                    }
                    classifyItem(itm);
                }
                try {
                    Set<ItemTag> itemTags = itm.getTags();
                    if (itemTags != null && tags.size() < 256) {
                        for (ItemTag tag : itemTags) {
                            if (tag == null) continue;
                            String name = tag.toString();
                            if (name != null && !name.isEmpty()) {
                                tags.put(name, tags.getOrDefault(name, 0) + 1);
                            }
                        }
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }

        c.fingerprint = fp;
        c.count = eligibleCount;
        c.usableCount = usableTotal;
        c.degradedCount = degradedTotal;
        c.typeCounts = counts;
        c.usableTypeCounts = usableCounts;
        c.degradedTypeCounts = degradedCounts;
        c.tagCounts = tags;
        c.capacity = cap;
        c.usedWeight = used;
        c.kind = kind;
        c.customName = customName;
        c.status = STATUS_LOADED;
    }

    private static KahluaTable compileSnapshot(SettlementState s) {
        KahluaTable result = table();
        KahluaTable rowsTable = table();
        KahluaTable countsTable = table();
        KahluaTable containersTable = table();
        KahluaTable fullContainersList = table();
        Aggregate a = s.aggregate;

        int contIndex = 1;
        for (ContainerContribution c : s.containers.values()) {
            if (STATUS_PRIVATE.equals(c.status)) continue;
            boolean isLoaded = STATUS_LOADED.equals(c.status);
            if (!isLoaded && !STATUS_OFFLINE_CACHED.equals(c.status)) continue;

            if (c.capacity > 0 && c.usedWeight >= c.capacity * 0.9 && fullContainersList.len() < 8) {
                KahluaTable fc = table();
                fc.rawset("name", c.customName != null ? c.customName : c.kind);
                fc.rawset("x", Double.valueOf(c.x));
                fc.rawset("y", Double.valueOf(c.y));
                fc.rawset("z", Double.valueOf(c.z));
                fc.rawset("capacity", Double.valueOf(c.capacity));
                fc.rawset("used", Double.valueOf(c.usedWeight));
                fc.rawset("loaded", Boolean.valueOf(isLoaded));
                fullContainersList.rawset(Double.valueOf(fullContainersList.len() + 1), fc);
            }
            KahluaTable contEntry = table();
            contEntry.rawset("id", c.containerId);
            contEntry.rawset("x", Double.valueOf(c.x));
            contEntry.rawset("y", Double.valueOf(c.y));
            contEntry.rawset("z", Double.valueOf(c.z));
            contEntry.rawset("kind", c.kind);
            if (c.customName != null) contEntry.rawset("customName", c.customName);
            contEntry.rawset("capacity", Double.valueOf(c.capacity));
            contEntry.rawset("usedWeight", Double.valueOf(c.usedWeight));
            contEntry.rawset("count", Double.valueOf(c.count));
            contEntry.rawset("loaded", Boolean.valueOf(isLoaded));
            contEntry.rawset("status", c.status);
            SmartSlot slot = SmartObjectRegistry.getContainerSlot(s.settlementId, c.containerId);
            if (slot != null) {
                contEntry.rawset("role", slot.getContainerRole().name());
                contEntry.rawset("priority", Double.valueOf(slot.getPriority()));
            } else {
                contEntry.rawset("role", ContainerRole.STORAGE.name());
                contEntry.rawset("priority", Double.valueOf(SmartZoningPolicy.DEFAULT_PRIORITY));
            }
            containersTable.rawset(Double.valueOf(contIndex++), contEntry);
        }

        int rowIndex = 1;
        for (Map.Entry<String, Integer> e : a.typeCounts.entrySet()) {
            String ft = e.getKey();
            int total = e.getValue().intValue();
            int usable = a.usableByType.getOrDefault(ft, Integer.valueOf(0)).intValue();
            int degraded = a.degradedByType.getOrDefault(ft, Integer.valueOf(0)).intValue();

            KahluaTable row = table();
            row.rawset("fullType", ft);
            row.rawset("count", Double.valueOf(total));
            row.rawset("usableCount", Double.valueOf(usable));
            row.rawset("degradedCount", Double.valueOf(degraded));
            row.rawset("node", CATEGORY_CACHE.getOrDefault(ft, "other"));

            KahluaTable sources = table();
            List<Source> srcList = a.sourcesByType.get(ft);
            if (srcList != null) {
                for (int i = 0; i < srcList.size(); i++) {
                    Source src = srcList.get(i);
                    KahluaTable entry = table();
                    entry.rawset("id", src.id);
                    entry.rawset("count", Double.valueOf(src.count));
                    entry.rawset("x", Double.valueOf(src.x));
                    entry.rawset("y", Double.valueOf(src.y));
                    entry.rawset("z", Double.valueOf(src.z));
                    entry.rawset("kind", src.kind);
                    entry.rawset("loaded", Boolean.valueOf(src.loaded));
                    if (src.customName != null) entry.rawset("customName", src.customName);
                    sources.rawset(Double.valueOf(i + 1), entry);
                }
            }
            row.rawset("sources", sources);
            rowsTable.rawset(Double.valueOf(rowIndex++), row);
        }

        countsTable.rawset("loaded", Double.valueOf(a.loaded));
        countsTable.rawset("unloaded", Double.valueOf(a.unloaded));
        countsTable.rawset("privateCount", Double.valueOf(a.privateCount));
        countsTable.rawset("items", Double.valueOf(a.totalItems));
        countsTable.rawset("loadedItems", Double.valueOf(a.loadedItems));
        countsTable.rawset("usableItems", Double.valueOf(a.usableItems));
        countsTable.rawset("degradedItems", Double.valueOf(a.degradedItems));
        countsTable.rawset("containers", Double.valueOf(a.loaded + a.unloaded));
        countsTable.rawset("totalCapacity", Double.valueOf(a.totalCapacity));
        countsTable.rawset("usedWeight", Double.valueOf(a.totalUsedWeight));
        countsTable.rawset("fullCount", Double.valueOf(a.fullCount));
        countsTable.rawset("fullContainers", fullContainersList);

        KahluaTable tagsTable = table();
        int tagIndex = 1;
        for (Map.Entry<String, Integer> e : a.tagCounts.entrySet()) {
            KahluaTable tagRow = table();
            tagRow.rawset("tag", e.getKey());
            tagRow.rawset("count", Double.valueOf(e.getValue()));
            tagsTable.rawset(Double.valueOf(tagIndex++), tagRow);
        }

        result.rawset("state", "READY");
        result.rawset("rows", rowsTable);
        result.rawset("counts", countsTable);
        result.rawset("containers", containersTable);
        result.rawset("tags", tagsTable);
        result.rawset("revision", Double.valueOf(s.revision));
        return result;
    }
}
