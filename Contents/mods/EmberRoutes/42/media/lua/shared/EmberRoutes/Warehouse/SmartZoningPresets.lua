local Kernel = require "EmberRoutes/Kernel"

local SmartZoningPresets = {
    presets = {
        food = { groups = { "food" }, priority = 3 },
        ["food.meals"] = { groups = { "food.meals" }, priority = 4 },
        ["food.perishable"] = { groups = { "food.perishable" }, priority = 4 },
        ["food.stable"] = { groups = { "food.stable" }, priority = 3 },
        medical = { groups = { "medical" }, priority = 4 },
        ["medical.bandages"] = { groups = { "medical.bandages" }, priority = 4 },
        ["medical.pills"] = { groups = { "medical.pills" }, priority = 4 },
        weapons = { groups = { "weapons" }, priority = 4 },
        ["weapons.guns"] = { groups = { "weapons.guns" }, priority = 5 },
        ["weapons.ammo"] = { groups = { "weapons.ammo" }, priority = 4 },
        ["weapons.parts"] = { groups = { "weapons.parts" }, priority = 3 },
        ["weapons.other"] = { groups = { "weapons.other" }, priority = 3 },
        apparel = { groups = { "apparel" }, priority = 3 },
        ["apparel.clothing"] = { groups = { "apparel.clothing" }, priority = 3 },
        ["apparel.armor"] = { groups = { "apparel.armor" }, priority = 4 },
        ["apparel.bags"] = { groups = { "apparel.bags" }, priority = 3 },
        literature = { groups = { "literature" }, priority = 3 },
        ["literature.skills"] = { groups = { "literature.skills" }, priority = 4 },
        ["literature.entertainment"] = { groups = { "literature.entertainment" }, priority = 2 },
        tools = { groups = { "tools" }, priority = 3 },
        materials = { groups = { "materials" }, priority = 3 },
        ["materials.electronics"] = { groups = { "materials.electronics" }, priority = 3 },
        fluids = { groups = { "fluids" }, priority = 3 },
        kits = { groups = { "kits" }, priority = 3 },
        ["kits.fishing"] = { groups = { "kits.fishing" }, priority = 3 },
        ["kits.farming"] = { groups = { "kits.farming" }, priority = 3 },
        ["kits.tailoring"] = { groups = { "kits.tailoring" }, priority = 3 },
        ["kits.mechanic"] = { groups = { "kits.mechanic" }, priority = 3 },
        scrap = { groups = { "scrap" }, priority = 1 },
        ["scrap.waste"] = { groups = { "scrap.waste" }, priority = 1 },
        ["scrap.compost"] = { groups = { "scrap.compost" }, priority = 1 },
        general = { groups = { "literature", "tools", "materials", "other" }, priority = 1 },
    },
}

local function copyStringArray(list)
    local out = {}
    if type(list) == "table" then
        for i = 1, #list do
            if type(list[i]) == "string" and list[i] ~= "" then
                out[#out + 1] = list[i]
            end
        end
    end
    return out
end

--- 强契约扩展点：允许附属子模组或 Compat 声明式注册自定义智能分拣预设并同步至 Java 过滤引擎。
function SmartZoningPresets.registerPreset(id, def)
    if type(id) ~= "string" or id == "" then
        return false, "INVALID_PRESET_ID"
    end
    if type(def) ~= "table" or type(def.groups) ~= "table" or #def.groups == 0 then
        return false, "INVALID_PRESET_GROUPS"
    end
    local groups = copyStringArray(def.groups)
    if #groups == 0 then
        return false, "EMPTY_PRESET_GROUPS"
    end
    local prio = tonumber(def.priority) or 3
    if prio < 1 then prio = 1 elseif prio > 5 then prio = 5 end

    SmartZoningPresets.presets[id] = {
        groups = groups,
        priority = math.floor(prio),
    }

    if EmberRoutesJava and EmberRoutesJava.registerSmartZoningPreset then
        EmberRoutesJava.registerSmartZoningPreset(id, groups, math.floor(prio))
    end
    return true
end

function SmartZoningPresets.syncAllToJava()
    if not EmberRoutesJava or not EmberRoutesJava.registerSmartZoningPreset then
        return 0
    end
    local count = 0
    for id, def in pairs(SmartZoningPresets.presets) do
        EmberRoutesJava.registerSmartZoningPreset(id, def.groups, def.priority or 3)
        count = count + 1
    end
    return count
end

Kernel.exposeApi("smartZoning", SmartZoningPresets)

return SmartZoningPresets
