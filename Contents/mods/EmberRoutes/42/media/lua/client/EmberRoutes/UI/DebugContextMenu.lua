local EmberUI = require "EmberUI"
local Kernel = require "EmberRoutes/Kernel"
local SettlementStore = require "EmberRoutes/Settlement/SettlementStore"
local NpcRuntime = require "EmberRoutes/Npc/NpcRuntime"
local NpcLocomotionPort = require "EmberRoutes/Npc/NpcLocomotionPort"
local SquadService = require "EmberRoutes/Squad/SquadService"
local ZoneOverlay = require "EmberRoutes/UI/ZoneOverlay"
local ZoneEditorPanel = require "EmberRoutes/UI/ZoneEditorPanel"
local SettlementControlPanel = require "EmberRoutes/UI/SettlementControlPanel"
local ContainerConfigWizard = require "EmberRoutes/UI/ContainerConfigWizard"
local SquadHUD = require "EmberRoutes/UI/SquadHUD"
local DebugPanel = require "EmberRoutes/UI/DebugPanel"
local NpcAnimDebugBridge = require "EmberRoutes/UI/NpcAnimDebugBridge"

local CONTAINER_ROLES = {
    { id = "STORAGE", key = "ContextMenu_ER_M3_Role_STORAGE" },
    { id = "BUFFER_IN", key = "ContextMenu_ER_M3_Role_BUFFER_IN" },
    { id = "PICKUP_OUT", key = "ContextMenu_ER_M3_Role_PICKUP_OUT" },
}

local function appendSquareContainerPrivacyOptions(sub, context, actor, gx, gy, z, settlementId)
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

        if EmberRoutesJava.getContainerRole and EmberRoutesJava.setContainerRole then
            local curRole = EmberRoutesJava.getContainerRole(targetObj) or "STORAGE"
            local roleSub = ISContextMenu:getNew(context)
            local roleOpt = sub:addOption(
                getText("ContextMenu_ER_M3_SmartZoneMenuFmt", labelName, curRole), nil, nil)
            context:addSubMenu(roleOpt, roleSub)

            roleSub:addOption(getText("ContextMenu_ER_OpenContainerWizard"), actor, function()
                ContainerConfigWizard.openForObject(targetObj, settlementId, function()
                    local activeCtrl = SettlementControlPanel.getActive()
                    if activeCtrl then activeCtrl:forceRefresh() end
                end)
            end)

            for ri = 1, #CONTAINER_ROLES do
                local roleEntry = CONTAINER_ROLES[ri]
                local roleId = roleEntry.id
                local roleLabel = getText(roleEntry.key)
                if roleId == curRole then
                    roleLabel = "* " .. roleLabel
                end
                roleSub:addOption(roleLabel, actor, function()
                    EmberRoutesJava.setContainerRole(targetObj, roleId)
                    EmberRoutesJava.flushDirtyContainers()
                end)
            end

            if EmberRoutesJava.autoConfigureContainer then
                roleSub:addOption(getText("ContextMenu_ER_M3_AutoSmartZone"), actor, function()
                    EmberRoutesJava.autoConfigureContainer(targetObj)
                    EmberRoutesJava.flushDirtyContainers()
                end)
            end
        end
    end
end

local function findClickedFurnitureAndBed(gx, gy, z, worldobjects)
    local bedObj, seatObj = nil, nil
    local function inspectObj(obj)
        if not obj then return end
        if not bedObj and EmberRoutesJava.isBedObject(obj) then
            bedObj = obj
        end
        if not seatObj and EmberRoutesJava.isSittableFurniture(obj) then
            seatObj = obj
        end
    end

    local cell = getCell and getCell()
    local sq = cell and cell:getGridSquare(gx, gy, z)
    local objs = sq and sq:getObjects()
    if objs then
        for i = 0, objs:size() - 1 do
            inspectObj(objs:get(i))
            if bedObj and seatObj then return bedObj, seatObj end
        end
    end

    if worldobjects then
        for _, obj in ipairs(worldobjects) do
            if obj and obj.getSquare then
                local osq = obj:getSquare()
                if osq and math.floor(osq:getZ()) == z then
                    inspectObj(obj)
                    if bedObj and seatObj then break end
                end
            end
        end
    end
    return bedObj, seatObj
end

local function pickBestNpcForPosture(ids, tx, ty, tz)
    if not ids or #ids == 0 then return nil end
    local bestStandingId, bestStandingDist = nil, math.huge
    local bestAnyId, bestAnyDist = nil, math.huge
    for i = 1, #ids do
        local id = ids[i]
        local info = NpcRuntime.getInfo(id)
        if info and info.phasedIn and not info.dead then
            local dx = (info.x or 0) - tx
            local dy = (info.y or 0) - ty
            local dz = math.abs(math.floor(info.z or 0) - tz) * 12.0
            local dist = math.sqrt(dx * dx + dy * dy) + dz
            if info.postureState == "STANDING" then
                if dist < bestStandingDist then
                    bestStandingDist = dist
                    bestStandingId = id
                end
            end
            if dist < bestAnyDist then
                bestAnyDist = dist
                bestAnyId = id
            end
        end
    end
    return bestStandingId or bestAnyId
end

local function appendPostureOptions(menu, actor, pickedId, bedObj, seatObj, includeStandUp)
    if seatObj and pickedId then
        menu:addOption(getText("ContextMenu_ER_M3_SitHereFmt", pickedId), actor, function()
            NpcRuntime.sitOnFurniture(pickedId, seatObj)
        end)
    end
    if bedObj and pickedId then
        menu:addOption(getText("ContextMenu_ER_M3_SleepBedFmt", pickedId), actor, function()
            NpcRuntime.sleepOnBed(pickedId, bedObj)
        end)
    end
    if includeStandUp then
        menu:addOption(getText("ContextMenu_ER_M3_StandUpAll"), actor, function()
            NpcRuntime.standUpAll(false)
        end)
    end
end

local function appendNpcCommandSubmenu(parentSub, context, actor, gx, gy, z, ids, curSettlement, pickedId)
    if not ids or #ids == 0 then return end
    local cmdSub = ISContextMenu:getNew(context)
    local cmdOpt = parentSub:addOption(getText("ContextMenu_ER_NpcCmd_Root"), nil, nil)
    context:addSubMenu(cmdOpt, cmdSub)

    local tx, ty, tz = gx + 0.5, gy + 0.5, z
    local targetId = ids[#ids]
    local brainTarget = pickedId or targetId

    cmdSub:addOption(getText("ContextMenu_ER_M2_WalkHereFmt", targetId), actor, function()
        NpcLocomotionPort.moveTo(targetId, tx, ty, tz, { run = false })
    end)
    cmdSub:addOption(getText("ContextMenu_ER_M2_RunHereFmt", targetId), actor, function()
        NpcLocomotionPort.moveTo(targetId, tx, ty, tz, { run = true })
    end)
    cmdSub:addOption(getText("ContextMenu_ER_M2_StopFmt", targetId), actor, function()
        NpcLocomotionPort.stop(targetId)
    end)

    cmdSub:addOption(getText("ContextMenu_ER_M2_FollowAll"), actor, function()
        local pIdx = actor and actor.getPlayerNum and actor:getPlayerNum() or 0
        for i = 1, #ids do
            SquadService.addMember(ids[i], pIdx)
        end
    end)
    cmdSub:addOption(getText("ContextMenu_ER_M2_StopFollowAll"), actor, function()
        for i = 1, #ids do
            SquadService.dismissMember(ids[i])
        end
    end)
    cmdSub:addOption(getText("ContextMenu_ER_M3_StandUpAll"), actor, function()
        NpcRuntime.standUpAll(false)
    end)

    if curSettlement then
        cmdSub:addOption(
            getText("ContextMenu_ER_M3_AssignCurSettlementFmt", curSettlement.name, brainTarget),
            actor,
            function()
                NpcRuntime.assignToSettlement(brainTarget, curSettlement.id)
            end
        )
    end

    local allSettlements = SettlementStore.list()
    if allSettlements and #allSettlements > 0 then
        local assignSub = ISContextMenu:getNew(context)
        local assignOpt = cmdSub:addOption(getText("ContextMenu_ER_M3_AssignSettlementSubFmt", brainTarget), nil, nil)
        context:addSubMenu(assignOpt, assignSub)
        for i = 1, #allSettlements do
            local st = allSettlements[i]
            assignSub:addOption(getText("ContextMenu_ER_M3_AssignSettlementItemFmt", st.name, st.id), actor, function()
                NpcRuntime.assignToSettlement(brainTarget, st.id)
            end)
        end
        assignSub:addOption(getText("ContextMenu_ER_M3_UnassignSettlementFmt", brainTarget), actor, function()
            NpcRuntime.assignToSettlement(brainTarget, nil)
            NpcRuntime.setBrainMode(brainTarget, "SETTLEMENT")
        end)
    end

    cmdSub:addOption(getText("ContextMenu_ER_M3_SetBrainSettlementFmt", brainTarget), actor, function()
        NpcRuntime.setBrainMode(brainTarget, "SETTLEMENT")
    end)
    cmdSub:addOption(getText("ContextMenu_ER_M3_SetBrainSquadFmt", brainTarget), actor, function()
        NpcRuntime.setBrainMode(brainTarget, "SQUAD")
    end)
end

local function appendDebugSubmenu(parentSub, context, actor, gx, gy, z)
    local debugSub = ISContextMenu:getNew(context)
    local debugOpt = parentSub:addOption(getText("ContextMenu_ER_Debug_Root"), nil, nil)
    context:addSubMenu(debugOpt, debugSub)

    local tx, ty, tz = gx + 0.5, gy + 0.5, z

    debugSub:addOption(getText("ContextMenu_ER_Debug_OpenPanel"), actor, function()
        DebugPanel.open("combat")
    end)

    debugSub:addOption(getText("ContextMenu_ER_M2_SpawnTest"), actor, function()
        local id = NpcRuntime.spawn(tx, ty, tz, false)
        if id then
            local summary = NpcRuntime.giveTestKit(id)
            print("[EmberRoutes][M2] Spawned " .. tostring(id) .. " -> " .. tostring(summary))
        end
    end)
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

    local settlement = SettlementStore.findSettlementAt(gx, gy)
    sub:addOption(getText("ContextMenu_ER_OpenMonitor"), actor, function()
        SettlementControlPanel.open(settlement and settlement.id or nil)
    end)

    sub:addOption(getText("ContextMenu_ER_NewSettlementEditor"), actor, function()
        ZoneEditorPanel.open(nil, function(saved)
            SettlementControlPanel.open(saved.id)
        end)
    end)

    if settlement then
        sub:addOption(
            getText("ContextMenu_ER_EditSettlementFmt", settlement.name),
            actor,
            function()
                ZoneEditorPanel.open(settlement, function(saved)
                    SettlementControlPanel.open(saved.id)
                end)
            end
        )
        appendSquareContainerPrivacyOptions(sub, context, actor, gx, gy, z, settlement.id)
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

    local squadMembers = SquadService.getMembers and SquadService.getMembers() or nil
    if squadMembers and #squadMembers > 0 and not SquadHUD.isOpen() then
        sub:addOption(getText("ContextMenu_ER_Squad_OpenHud"), actor, function()
            SquadHUD.open()
        end)
    end

    sub:addOption(getText("ContextMenu_ER_ThemeSettings"), actor, function()
        EmberUI.openThemeSettings("er.warehouse")
    end)

    local ids = NpcRuntime.listIds()
    local bedObj, seatObj = findClickedFurnitureAndBed(gx, gy, z, worldobjects)
    local pickedId = pickBestNpcForPosture(ids, gx + 0.5, gy + 0.5, z)
    appendPostureOptions(sub, actor, pickedId, bedObj, seatObj, #ids > 0 and (bedObj ~= nil or seatObj ~= nil))
    appendNpcCommandSubmenu(sub, context, actor, gx, gy, z, ids, settlement, pickedId)
    appendDebugSubmenu(sub, context, actor, gx, gy, z)
end

local DebugContextMenu = {}

Kernel.registerModule({
    id = "debug-context-menu",
    onWorldReady = function(scope)
        scope:on(Events.OnFillWorldObjectContextMenu, onFillWorldMenu)
    end,
    onTeardown = function()
        NpcAnimDebugBridge.detach()
    end,
})

return DebugContextMenu
