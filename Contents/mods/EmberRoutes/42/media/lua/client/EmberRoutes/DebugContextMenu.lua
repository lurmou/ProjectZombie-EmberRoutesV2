require "EmberRoutes/WarehouseWatcher"
local SettlementStore = require "EmberRoutes/SettlementStore"
local ZoneOverlay = require "EmberRoutes/ZoneOverlay"
local ZoneEditorPanel = require "EmberRoutes/ZoneEditorPanel"
local DebugWarehousePanel = require "EmberRoutes/DebugWarehousePanel"

local function appendSquareContainerPrivacyOptions(sub, actor, gx, gy, z)
    local cell = getCell and getCell()
    local sq = cell and cell:getGridSquare(gx, gy, z)
    if not sq then return end
    local objs = sq:getObjects()
    if not objs then return end

    local eligible = {}
    for i = 0, objs:size() - 1 do
        local obj = objs:get(i)
        if obj and EmberRoutesJava.isEligibleStorageObject(obj) then
            eligible[#eligible + 1] = obj
        end
    end

    local total = #eligible
    for idx = 1, total do
        local targetObj = eligible[idx]
        local baseName = EmberRoutesJava.getStorageObjectDisplayName(targetObj)
        local labelName = total > 1 and (baseName .. " #" .. tostring(idx)) or baseName
        local isPriv = EmberRoutesJava.isContainerObjectPrivate(targetObj)
        local optText = isPriv
            and getText("ContextMenu_ER_UnmarkPrivateFmt", labelName)
            or getText("ContextMenu_ER_MarkPrivateFmt", labelName)

        sub:addOption(optText, actor, function()
            EmberRoutesJava.setContainerObjectPrivate(targetObj, not isPriv)
            EmberRoutesJava.flushDirtyContainers()
        end)
    end
end

local function onFillWorldMenu(playerIndex, context, worldobjects, test)
    if test or not EmberRoutesJava then return end
    local actor = getSpecificPlayer(playerIndex)
    if not actor or actor:isDead() then return end

    -- 统一使用屏幕鼠标反投影到玩家所在楼层格坐标，彻底杜绝前景半透明墙体/屋顶射线偏移
    local z = math.floor(actor:getZ())
    local gx = math.floor(screenToIsoX(playerIndex, getMouseX(), getMouseY(), z))
    local gy = math.floor(screenToIsoY(playerIndex, getMouseX(), getMouseY(), z))

    local sub = ISContextMenu:getNew(context)
    local rootOpt = context:addOption(getText("ContextMenu_ER_Root"), nil, nil)
    context:addSubMenu(rootOpt, sub)

    sub:addOption(getText("ContextMenu_ER_OpenMonitor"), actor, function()
        DebugWarehousePanel.open()
    end)

    sub:addOption(getText("ContextMenu_ER_NewSettlementEditor"), actor, function()
        ZoneEditorPanel.open(nil, function(saved)
            DebugWarehousePanel.open(saved.id)
        end)
    end)

    local settlement = SettlementStore.findSettlementAt(gx, gy)
    if settlement then
        sub:addOption(
            getText("ContextMenu_ER_EditSettlementFmt", settlement.name),
            actor,
            function()
                ZoneEditorPanel.open(settlement, function(saved)
                    DebugWarehousePanel.open(saved.id)
                end)
            end
        )
        appendSquareContainerPrivacyOptions(sub, actor, gx, gy, z)
        sub:addOption(
            getText("ContextMenu_ER_DeleteSettlementFmt", settlement.name),
            actor,
            function()
                SettlementStore.deleteSettlement(settlement.id)
            end
        )
    end

    local borderLabel = ZoneOverlay.isVisible()
        and getText("ContextMenu_ER_HideBorders")
        or getText("ContextMenu_ER_ShowBorders")
    sub:addOption(borderLabel, actor, function()
        ZoneOverlay.toggleVisible()
    end)
end

Events.OnFillWorldObjectContextMenu.Add(onFillWorldMenu)
