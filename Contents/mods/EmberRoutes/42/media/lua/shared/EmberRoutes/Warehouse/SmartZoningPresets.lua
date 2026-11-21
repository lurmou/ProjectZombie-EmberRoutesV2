local Kernel = require "EmberRoutes/Kernel"

local SmartZoningPresets = {
    presets = {
        food = { groups = { "food", "food.perishable", "food.stable" }, priority = 3 },
        ["food.perishable"] = { groups = { "food.perishable" }, priority = 4 },
        ["food.stable"] = { groups = { "food.stable" }, priority = 3 },
        medical = { groups = { "medical" }, priority = 4 },
        weapons = { groups = { "weapons", "weapons.guns", "weapons.ammo", "weapons.other" }, priority = 4 },
        ["weapons.guns"] = { groups = { "weapons.guns" }, priority = 5 },
        ["weapons.ammo"] = { groups = { "weapons.ammo" }, priority = 4 },
        tools = { groups = { "tools" }, priority = 3 },
        materials = { groups = { "materials" }, priority = 3 },
        literature = { groups = { "literature" }, priority = 3 },
        apparel = { groups = { "apparel" }, priority = 3 },
        fluids = { groups = { "fluids" }, priority = 3 },
        containers = { groups = { "containers" }, priority = 2 },
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
