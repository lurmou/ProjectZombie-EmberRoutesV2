local Geometry = require "EmberRoutes/Settlement/SettlementGeometry"
local Kernel = require "EmberRoutes/Kernel"

local SettlementStore = {}

local MODDATA_KEY = "EmberRoutes.Settlements"
local listeners = {}

local function getRoot()
    local root = ModData.getOrCreate(MODDATA_KEY)
    if type(root.nextSeq) ~= "number" then root.nextSeq = 1 end
    if type(root.settlements) ~= "table" then root.settlements = {} end
    if type(root.order) ~= "table" then root.order = {} end
    return root
end

local function notifyChanged()
    if #listeners == 0 then return end
    local snap = {}
    for i = 1, #listeners do
        snap[i] = listeners[i]
    end
    for i = 1, #snap do
        local entry = snap[i]
        if entry.active and entry.fn then
            entry.fn()
        end
    end
end

function SettlementStore.onChanged(fn)
    if type(fn) ~= "function" then
        return function() end
    end
    local entry = { fn = fn, active = true }
    listeners[#listeners + 1] = entry
    return function()
        if not entry.active then return end
        entry.active = false
        for i = #listeners, 1, -1 do
            if listeners[i] == entry then
                table.remove(listeners, i)
                break
            end
        end
    end
end

function SettlementStore.toFlatRects(areas)
    local flat = {}
    if type(areas) == "table" then
        local idx = 1
        for i = 1, #areas do
            local r = areas[i]
            if r then
                flat[idx] = r.x
                flat[idx + 1] = r.y
                flat[idx + 2] = r.w
                flat[idx + 3] = r.h
                idx = idx + 4
            end
        end
    end
    return flat
end

local function syncToJava(record)
    if EmberRoutesJava and record then
        EmberRoutesJava.syncSettlementAreas(record.id, SettlementStore.toFlatRects(record.areas))
    end
end

function SettlementStore.findOverlappingSettlement(areas, ignoreId)
    if type(areas) ~= "table" or #areas == 0 then return nil end
    local root = getRoot()
    for i = 1, #root.order do
        local sid = root.order[i]
        if sid ~= ignoreId then
            local s = root.settlements[sid]
            if s and type(s.areas) == "table" then
                for aIdx = 1, #areas do
                    local ra = areas[aIdx]
                    if Geometry.valid(ra) then
                        for bIdx = 1, #s.areas do
                            local rb = s.areas[bIdx]
                            if Geometry.valid(rb) and Geometry.overlap(ra, rb, 0) then
                                return s
                            end
                        end
                    end
                end
            end
        end
    end
    return nil
end

function SettlementStore.list()
    local root = getRoot()
    local out = {}
    for i = 1, #root.order do
        local s = root.settlements[root.order[i]]
        if s then out[#out + 1] = s end
    end
    return out
end

function SettlementStore.get(settlementId)
    if not settlementId then return nil end
    return getRoot().settlements[settlementId]
end

function SettlementStore.findSettlementAt(x, y)
    local root = getRoot()
    for i = 1, #root.order do
        local s = root.settlements[root.order[i]]
        if s and Geometry.insideAreas(s.areas, x, y) then
            return s
        end
    end
    return nil
end

--- 提交新建或更新据点领地（严格规约为互不重叠子矩形，并立即扫描领地内全楼层世界容器）。
function SettlementStore.commitSettlement(settlementId, name, areas)
    if type(areas) ~= "table" or #areas == 0 then
        return nil, "EMPTY_AREAS"
    end
    local compacted = Geometry.normalizeDisjoint(areas)
    if #compacted == 0 then
        return nil, "INVALID_BOUNDS"
    end
    if #compacted > Geometry.MAX_AREAS then
        return nil, "MAX_AREAS_EXCEEDED"
    end

    local root = getRoot()
    if SettlementStore.findOverlappingSettlement(compacted, settlementId) ~= nil then
        return nil, "TERRITORY_OVERLAP"
    end

    local record = settlementId and root.settlements[settlementId] or nil
    if record then
        if type(name) == "string" and name ~= "" then
            record.name = name
        end
        record.areas = compacted
    else
        local seq = root.nextSeq
        root.nextSeq = seq + 1
        local sid = "S" .. tostring(seq)
        local displayName = (type(name) == "string" and name ~= "") and name or ("Settlement #" .. tostring(seq))
        record = {
            id = sid,
            name = displayName,
            areas = compacted,
        }
        root.settlements[sid] = record
        root.order[#root.order + 1] = sid
    end

    syncToJava(record)
    notifyChanged()
    return record
end

function SettlementStore.createSettlement(name, x1, y1, x2, y2)
    local rect, err = Geometry.rectangle(x1, y1, x2, y2)
    if not rect then return nil, err end
    return SettlementStore.commitSettlement(nil, name, { rect })
end

function SettlementStore.expandTerritory(settlementId, x1, y1, x2, y2)
    local s = SettlementStore.get(settlementId)
    if not s then return nil, "NOT_FOUND" end
    local rect, err = Geometry.rectangle(x1, y1, x2, y2)
    if not rect then return nil, err end
    local nextAreas = Geometry.addToList(s.areas, rect)
    return SettlementStore.commitSettlement(settlementId, s.name, nextAreas)
end

function SettlementStore.trimTerritory(settlementId, x1, y1, x2, y2)
    local s = SettlementStore.get(settlementId)
    if not s then return nil, "NOT_FOUND" end
    local cutRect, err = Geometry.rectangle(x1, y1, x2, y2)
    if not cutRect then return nil, err end
    local nextAreas = Geometry.subtractFromList(s.areas, cutRect)
    if #nextAreas == 0 then
        return nil, "EMPTY_TERRITORY_USE_DELETE"
    end
    return SettlementStore.commitSettlement(settlementId, s.name, nextAreas)
end

function SettlementStore.deleteSettlement(settlementId)
    local root = getRoot()
    local s = root.settlements[settlementId]
    if not s then return false end

    if EmberRoutesJava then
        EmberRoutesJava.invalidateSettlementWarehouse(s.id)
    end

    root.settlements[settlementId] = nil
    for i = #root.order, 1, -1 do
        if root.order[i] == settlementId then
            table.remove(root.order, i)
        end
    end
    notifyChanged()
    return true
end

--- 读档/进游戏同步：清空上一局静态缓存，重新挂载所有已存据点的全楼层世界容器。
function SettlementStore.reconcileAndMountAll()
    if not EmberRoutesJava then return end
    EmberRoutesJava.reset()

    local root = getRoot()
    for i = 1, #root.order do
        local s = root.settlements[root.order[i]]
        if s and type(s.areas) == "table" and #s.areas > 0 then
            syncToJava(s)
        end
    end
    notifyChanged()
end

Kernel.plugin({
    name = "settlements",
    provides = { "settlements" },
    apply = function(ctx)
        ctx:provide("settlements", SettlementStore)
    end,
})

return SettlementStore
