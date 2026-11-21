local EmberUI = require "EmberUI"
local Theme = EmberUI.Theme
local Chrome = EmberUI.Chrome
local SemanticBadge = require "EmberUI/Widgets/SemanticBadge"
local Text = require "EmberUI/Core/Text"

local CategoryCatalog = {}
local BADGE_OPT_BUF = { label = "" }

---@class CategoryNodeDef
---@field id string
---@field parent string?
---@field getLabel fun(): string

local LABELS = {
    all = function() return getText("IGUI_ER_Cat_All") end,
    food = function() return getText("IGUI_ER_Cat_food") end,
    ["food.meals"] = function() return getText("IGUI_ER_Cat_food_meals") end,
    ["food.perishable"] = function() return getText("IGUI_ER_Cat_food_perishable") end,
    ["food.stable"] = function() return getText("IGUI_ER_Cat_food_stable") end,
    medical = function() return getText("IGUI_ER_Cat_medical") end,
    ["medical.bandages"] = function() return getText("IGUI_ER_Cat_medical_bandages") end,
    ["medical.pills"] = function() return getText("IGUI_ER_Cat_medical_pills") end,
    weapons = function() return getText("IGUI_ER_Cat_weapons") end,
    ["weapons.guns"] = function() return getText("IGUI_ER_Cat_weapons_guns") end,
    ["weapons.ammo"] = function() return getText("IGUI_ER_Cat_weapons_ammo") end,
    ["weapons.parts"] = function() return getText("IGUI_ER_Cat_weapons_parts") end,
    ["weapons.other"] = function() return getText("IGUI_ER_Cat_weapons_other") end,
    apparel = function() return getText("IGUI_ER_Cat_apparel") end,
    ["apparel.clothing"] = function() return getText("IGUI_ER_Cat_apparel_clothing") end,
    ["apparel.armor"] = function() return getText("IGUI_ER_Cat_apparel_armor") end,
    ["apparel.bags"] = function() return getText("IGUI_ER_Cat_apparel_bags") end,
    literature = function() return getText("IGUI_ER_Cat_literature") end,
    ["literature.skills"] = function() return getText("IGUI_ER_Cat_literature_skills") end,
    ["literature.entertainment"] = function() return getText("IGUI_ER_Cat_literature_entertainment") end,
    tools = function() return getText("IGUI_ER_Cat_tools") end,
    materials = function() return getText("IGUI_ER_Cat_materials") end,
    ["materials.electronics"] = function() return getText("IGUI_ER_Cat_materials_electronics") end,
    fluids = function() return getText("IGUI_ER_Cat_fluids") end,
    kits = function() return getText("IGUI_ER_Cat_kits") end,
    ["kits.fishing"] = function() return getText("IGUI_ER_Cat_kits_fishing") end,
    ["kits.farming"] = function() return getText("IGUI_ER_Cat_kits_farming") end,
    ["kits.tailoring"] = function() return getText("IGUI_ER_Cat_kits_tailoring") end,
    ["kits.mechanic"] = function() return getText("IGUI_ER_Cat_kits_mechanic") end,
    scrap = function() return getText("IGUI_ER_Cat_scrap") end,
    ["scrap.waste"] = function() return getText("IGUI_ER_Cat_scrap_waste") end,
    ["scrap.compost"] = function() return getText("IGUI_ER_Cat_scrap_compost") end,
    other = function() return getText("IGUI_ER_Cat_other") end,
}

local ROLE_LABELS = {
    STORAGE = function() return getText("IGUI_ER_Role_STORAGE") end,
    BUFFER_IN = function() return getText("IGUI_ER_Role_BUFFER_IN") end,
    PICKUP_OUT = function() return getText("IGUI_ER_Role_PICKUP_OUT") end,
}

local ROLE_SHORT = {
    STORAGE = function() return getText("IGUI_ER_RoleShort_STORAGE") end,
    BUFFER_IN = function() return getText("IGUI_ER_RoleShort_BUFFER_IN") end,
    PICKUP_OUT = function() return getText("IGUI_ER_RoleShort_PICKUP_OUT") end,
}

local ROLE_DESCS = {
    STORAGE = function() return getText("IGUI_ER_RoleDesc_STORAGE") end,
    BUFFER_IN = function() return getText("IGUI_ER_RoleDesc_BUFFER_IN") end,
    PICKUP_OUT = function() return getText("IGUI_ER_RoleDesc_PICKUP_OUT") end,
}

local ORDER_LABELS = {
    PENDING = function() return getText("IGUI_ER_OrderStatus_PENDING") end,
    ASSIGNED = function() return getText("IGUI_ER_OrderStatus_ASSIGNED") end,
    FALLBACK_PICKUP_OUT = function() return getText("IGUI_ER_OrderStatus_FALLBACK_PICKUP_OUT") end,
    COMPLETED = function() return getText("IGUI_ER_OrderStatus_COMPLETED") end,
    CANCELLED = function() return getText("IGUI_ER_OrderStatus_CANCELLED") end,
}

local ORDER_BADGES = {
    PENDING = "UPDATING",
    ASSIGNED = "ACTIVE",
    FALLBACK_PICKUP_OUT = "WARNING",
    COMPLETED = "READY",
    CANCELLED = "DISABLED",
}

CategoryCatalog.MAJORS = {
    { id = "food",       children = { "food.meals", "food.perishable", "food.stable" } },
    { id = "medical",    children = { "medical.bandages", "medical.pills" } },
    { id = "weapons",    children = { "weapons.guns", "weapons.ammo", "weapons.parts", "weapons.other" } },
    { id = "apparel",    children = { "apparel.clothing", "apparel.armor", "apparel.bags" } },
    { id = "literature", children = { "literature.skills", "literature.entertainment" } },
    { id = "tools",      children = {} },
    { id = "materials",  children = { "materials.electronics" } },
    { id = "fluids",     children = {} },
    { id = "kits",       children = { "kits.fishing", "kits.farming", "kits.tailoring", "kits.mechanic" } },
    { id = "scrap",      children = { "scrap.waste", "scrap.compost" } },
    { id = "other",      children = {} },
}

local PARENT_BY_ID = {}
for i = 1, #CategoryCatalog.MAJORS do
    local maj = CategoryCatalog.MAJORS[i]
    for j = 1, #maj.children do
        PARENT_BY_ID[maj.children[j]] = maj.id
    end
end

function CategoryCatalog.getLabel(id)
    local fn = LABELS[id]
    if fn then
        return fn()
    end
    return tostring(id or "")
end

function CategoryCatalog.majorOf(node)
    if type(node) ~= "string" or node == "" then
        return "other"
    end
    if PARENT_BY_ID[node] then
        return PARENT_BY_ID[node]
    end
    local dot = string.find(node, ".", 1, true)
    local root = dot and string.sub(node, 1, dot - 1) or node
    if LABELS[root] then
        return root
    end
    return "other"
end

function CategoryCatalog.parentOf(node)
    return PARENT_BY_ID[node]
end

function CategoryCatalog.getRoleLabel(role)
    local fn = ROLE_LABELS[role]
    return fn and fn() or tostring(role or "STORAGE")
end

function CategoryCatalog.getRoleShort(role)
    local fn = ROLE_SHORT[role]
    return fn and fn() or tostring(role or "STORAGE")
end

function CategoryCatalog.getRoleDesc(role)
    local fn = ROLE_DESCS[role]
    return fn and fn() or ""
end

function CategoryCatalog.getOrderStatusLabel(status)
    local fn = ORDER_LABELS[status]
    return fn and fn() or tostring(status or "PENDING")
end

function CategoryCatalog.getOrderBadgeKey(status)
    return ORDER_BADGES[status] or "INFO"
end

function CategoryCatalog.resolveItemName(fullType)
    if type(fullType) ~= "string" or fullType == "" then
        return ""
    end
    local sm = getScriptManager and getScriptManager()
    local scriptItem = sm and sm:FindItem(fullType)
    if scriptItem and scriptItem.getDisplayName then
        local dn = scriptItem:getDisplayName()
        if dn and dn ~= "" then
            return dn
        end
    end
    return fullType
end

function CategoryCatalog.parseCsvSet(csvStr)
    local out = {}
    if type(csvStr) ~= "string" or csvStr == "" then
        return out
    end
    for token in string.gmatch(csvStr, "[^,]+") do
        local clean = string.lower(string.gsub(token, "^%s*(.-)%s*$", "%1"))
        if clean ~= "" then
            out[clean] = true
        end
    end
    return out
end

function CategoryCatalog.joinCsvSet(setTbl)
    local arr = {}
    if type(setTbl) == "table" then
        for k, v in pairs(setTbl) do
            if v == true and type(k) == "string" and k ~= "" then
                arr[#arr + 1] = k
            end
        end
    end
    table.sort(arr)
    return table.concat(arr, ",")
end

function CategoryCatalog.countSet(setTbl)
    local n = 0
    if type(setTbl) == "table" then
        for _, v in pairs(setTbl) do
            if v == true then
                n = n + 1
            end
        end
    end
    return n
end

function CategoryCatalog.bindFilterChipsResize(chips)
    if not chips then return chips end
    local origResize = chips.onResize
    chips.onResize = function(self)
        if origResize then origResize(self) end
        if self.options and #self.options > 0 then
            self:setOptions(self.options, self.selectedId)
            self:setSelected(self.selectedId, true)
        end
    end
    return chips
end

function CategoryCatalog.buildMajorFilterOptions(countsByMajor, totalCount)
    local opts = {
        { id = "all", label = CategoryCatalog.getLabel("all"), count = totalCount },
    }
    for i = 1, #CategoryCatalog.MAJORS do
        local maj = CategoryCatalog.MAJORS[i]
        opts[#opts + 1] = {
            id = maj.id,
            label = CategoryCatalog.getLabel(maj.id),
            count = countsByMajor and countsByMajor[maj.id] or 0,
        }
    end
    return opts
end

function CategoryCatalog.rowKey(row)
    return row.key
end

function CategoryCatalog.rowCaption(row)
    return row.caption or ""
end

function CategoryCatalog.beginListRowDraw(list, y, entry, defaultH, alternatingAlpha)
    local h = entry.height or defaultH or 24
    local vis = y + list:getYScroll()
    if vis + h < 0 or vis >= list.height then
        return nil, y + h
    end
    if list.selected == entry.index then
        Chrome.roundedFill(list, 2, y + 1, list.width - 12, h - 2, Theme.selected)
    elseif alternatingAlpha and alternatingAlpha > 0 and entry.index % 2 == 0 then
        local pt = Theme.panel
        list:drawRect(2, y, list.width - 12, h - 1, alternatingAlpha, pt.r, pt.g, pt.b)
    end
    return entry.item and entry.item.visual, y + h
end

function CategoryCatalog.makeBadgedListStyle(opts)
    opts = opts or {}
    local rowH = opts.height or 28
    local badgeW = opts.badgeW or 86
    local extraW = opts.extraW or 0
    local textPadY = opts.textPadY or math.floor((rowH - 14) / 2)
    return {
        prepare = function(_list, row, _label)
            return {
                height = rowH,
                leftText = row.leftText or "",
                extraText = row.extraText or "",
                hasAlert = row.hasAlert == true,
                badgeKey = row.badgeKey or "INFO",
                badgeLabel = row.badgeLabel or "",
                tooltip = row.tooltip,
            }
        end,
        draw = function(list, y, entry)
            local v, nextY = CategoryCatalog.beginListRowDraw(list, y, entry, rowH, 0.25)
            if not v then return nextY end
            local rightMargin = badgeW + extraW + 22
            local leftClip = Text.truncate(v.leftText, math.max(36, list.width - rightMargin), UIFont.Small)
            Theme.text(list, leftClip, 8, y + textPadY, Theme.textColor, UIFont.Small)
            if extraW > 0 and v.extraText ~= "" then
                local col = v.hasAlert and (Theme.danger or Theme.warning) or Theme.muted
                list:drawTextRight(v.extraText, list.width - badgeW - 18, y + textPadY, col.r, col.g, col.b, 1.0, UIFont.Small)
            end
            BADGE_OPT_BUF.label = v.badgeLabel
            SemanticBadge.draw(list, list.width - badgeW - 12, y + 3, badgeW, rowH - 6, v.badgeKey, BADGE_OPT_BUF)
            return nextY
        end,
    }
end

function CategoryCatalog.createScopedWindow(ctrl, scope, title, opts)
    opts = opts or {}
    local screenW = getCore and getCore():getScreenWidth() or 1280
    local screenH = getCore and getCore():getScreenHeight() or 720
    local w = math.min(opts.width or 760, screenW - 32)
    local h = math.min(opts.height or 500, screenH - 36)
    local x = opts.x or math.max(16, math.floor((screenW - w) / 2))
    local y = opts.y or math.max(18, math.floor((screenH - h) / 2))
    local win = EmberUI.WindowHost:new(x, y, w, h, {
        title = title,
        moduleId = opts.moduleId or "er.warehouse",
        resizable = opts.resizable ~= false,
        minWidth = opts.minWidth or 680,
        minHeight = opts.minHeight or 440,
        closeOnEscape = opts.closeOnEscape ~= false,
        hostProps = opts.hostProps or { direction = "column", padding = 10, gap = 6 },
    })
    win:initialise()
    win:instantiate()
    win:addToUIManager()
    EmberUI.PageLifecycle.attachExternalScope(win, scope)
    ctrl.win = win
    function ctrl:setX(nx) self.win:setX(nx) end
    function ctrl:setY(ny) self.win:setY(ny) end
    ctrl.close = function() win:close() end
    return win, w, h
end

return CategoryCatalog
