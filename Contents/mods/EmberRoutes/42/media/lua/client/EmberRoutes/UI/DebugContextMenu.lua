local EmberUI = require "EmberUI"
local Kernel = require "EmberRoutes/Kernel"
local SettlementStore = require "EmberRoutes/Settlement/SettlementStore"
local NpcRuntime = require "EmberRoutes/Npc/NpcRuntime"
local NpcLocomotionPort = require "EmberRoutes/Npc/NpcLocomotionPort"
local ZoneOverlay = require "EmberRoutes/UI/ZoneOverlay"
local ZoneEditorPanel = require "EmberRoutes/UI/ZoneEditorPanel"
local DebugWarehousePanel = require "EmberRoutes/UI/DebugWarehousePanel"

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
            local ok = NpcRuntime.sitOnFurniture(pickedId, seatObj)
            print("[EmberRoutes][M3] SitOnFurniture " .. tostring(pickedId) .. " -> " .. tostring(ok))
        end)
    end
    if bedObj and pickedId then
        menu:addOption(getText("ContextMenu_ER_M3_SleepBedFmt", pickedId), actor, function()
            local ok = NpcRuntime.sleepOnBed(pickedId, bedObj)
            print("[EmberRoutes][M3] SleepOnBed " .. tostring(pickedId) .. " -> " .. tostring(ok))
        end)
    end
    if includeStandUp then
        menu:addOption(getText("ContextMenu_ER_M3_StandUpAll"), actor, function()
            local n = NpcRuntime.standUpAll(false)
            print("[EmberRoutes][M3] StandUpAll count=" .. tostring(n))
        end)
    end
end

local function appendM2NpcSubmenu(parentSub, context, actor, gx, gy, z, bedObj, seatObj, pickedId)
    local m2Sub = ISContextMenu:getNew(context)
    local m2Opt = parentSub:addOption(getText("ContextMenu_ER_M2_Root"), nil, nil)
    context:addSubMenu(m2Opt, m2Sub)

    local tx, ty, tz = gx + 0.5, gy + 0.5, z

    m2Sub:addOption(getText("ContextMenu_ER_M2_SpawnTest"), actor, function()
        local id = NpcRuntime.spawn(tx, ty, tz, false)
        if id then
            local summary = NpcRuntime.giveTestKit(id)
            print("[EmberRoutes][M2] Spawned " .. tostring(id) .. " -> " .. tostring(summary))
        else
            print("[EmberRoutes][M2] Spawn failed at " .. tostring(tx) .. "," .. tostring(ty) .. "," .. tostring(tz))
        end
    end)

    m2Sub:addOption(getText("ContextMenu_ER_M2_SpawnStress10"), actor, function()
        for i = 1, 10 do
            local dx = ((i - 1) % 5 - 2) * 1.4
            local dy = (math.floor((i - 1) / 5) - 0.5) * 1.4
            local female = (i % 2 == 0)
            local sx = tx + dx
            local sy = ty + dy
            local id = NpcRuntime.spawn(sx, sy, tz, female)
            if id then
                local summary = NpcRuntime.giveTestKit(id)
                print("[EmberRoutes][M2][SPAWN10] " .. tostring(i) .. "/10 " .. tostring(summary))
            else
                print("[EmberRoutes][M2][SPAWN10] FAILED " .. tostring(i) .. "/10 at "
                    .. tostring(sx) .. "," .. tostring(sy) .. "," .. tostring(tz))
            end
        end
    end)

    local ids = NpcRuntime.listIds()
    local targetId = ids and #ids > 0 and ids[#ids] or nil
    if targetId then
        appendPostureOptions(m2Sub, actor, pickedId or targetId, bedObj, seatObj, true)
        local info = NpcRuntime.getInfo(targetId)
        m2Sub:addOption(getText("ContextMenu_ER_M2_WalkHereFmt", targetId), actor, function()
            NpcLocomotionPort.moveTo(targetId, tx, ty, tz, {
                run = false,
                onArrival = function(id, status, reason)
                    print("[EmberRoutes][M2] Walk callback " .. tostring(id) .. ": " .. tostring(status) .. " (" .. tostring(reason) .. ")")
                end,
            })
        end)

        m2Sub:addOption(getText("ContextMenu_ER_M2_RunHereFmt", targetId), actor, function()
            NpcLocomotionPort.moveTo(targetId, tx, ty, tz, {
                run = true,
                onArrival = function(id, status, reason)
                    print("[EmberRoutes][M2] Run callback " .. tostring(id) .. ": " .. tostring(status) .. " (" .. tostring(reason) .. ")")
                end,
            })
        end)

        m2Sub:addOption(getText("ContextMenu_ER_M2_StopFmt", targetId), actor, function()
            NpcLocomotionPort.stop(targetId)
        end)

        m2Sub:addOption(getText("ContextMenu_ER_M2_FollowAll"), actor, function()
            local pIdx = actor and actor.getPlayerNum and actor:getPlayerNum() or 0
            for i = 1, #ids do
                NpcLocomotionPort.follow(ids[i], { playerIndex = pIdx })
            end
        end)

        m2Sub:addOption(getText("ContextMenu_ER_M2_StopFollowAll"), actor, function()
            for i = 1, #ids do
                NpcLocomotionPort.stopFollow(ids[i])
            end
        end)

        if info and info.phasedIn then
            m2Sub:addOption(getText("ContextMenu_ER_M2_PhaseOutFmt", targetId), actor, function()
                local ok = NpcRuntime.phaseOut(targetId)
                print("[EmberRoutes][M2] PhaseOut " .. tostring(targetId) .. " -> " .. tostring(ok))
            end)
        else
            m2Sub:addOption(getText("ContextMenu_ER_M2_PhaseInFmt", targetId), actor, function()
                local ok = NpcRuntime.phaseIn(targetId)
                print("[EmberRoutes][M2] PhaseIn " .. tostring(targetId) .. " -> " .. tostring(ok))
            end)
        end

        m2Sub:addOption(getText("ContextMenu_ER_M2_InspectFmt", targetId), actor, function()
            local latest = NpcRuntime.getInfo(targetId)
            local invLog = NpcRuntime.inspectInventoryIds(targetId)
            print("[EmberRoutes][M2] Inspect " .. tostring(invLog))
            if latest then
                print("[EmberRoutes][M2] Status=" .. tostring(latest.moveStatus)
                    .. " numPlayers=" .. tostring(latest.playerNumPlayers)
                    .. " isLocalPlayer=" .. tostring(latest.isLocalPlayer)
                    .. " debug=" .. tostring(latest.debugLine))
            end
        end)

        m2Sub:addOption(getText("ContextMenu_ER_M2_InspectAll"), actor, function()
            local allIds = NpcRuntime.listIds()
            print("[EmberRoutes][M2][INSPECT_ALL] count=" .. tostring(#allIds))
            for i = 1, #allIds do
                local id = allIds[i]
                local info = NpcRuntime.getInfo(id)
                local invLog = NpcRuntime.inspectInventoryIds(id)
                print("[EmberRoutes][M2][INSPECT_ALL] " .. tostring(i) .. "/" .. tostring(#allIds)
                    .. " " .. tostring(invLog)
                    .. " | phased=" .. tostring(info and info.phasedIn)
                    .. " | status=" .. tostring(info and info.moveStatus)
                    .. " | follow=" .. tostring(NpcLocomotionPort.getFollowState(id)))
            end
        end)

        m2Sub:addOption(getText("ContextMenu_ER_M2_SaveNow"), actor, function()
            local ok = NpcRuntime.saveNow()
            print("[EmberRoutes][M2] SaveNow -> " .. tostring(ok))
        end)

        m2Sub:addOption(getText("ContextMenu_ER_M2_KillFmt", targetId), actor, function()
            local invBefore = NpcRuntime.inspectInventoryIds(targetId)
            local ok = NpcRuntime.kill(targetId)
            print("[EmberRoutes][M2] Kill " .. tostring(targetId) .. " ok=" .. tostring(ok) .. " invBefore=" .. tostring(invBefore))
        end)

        m2Sub:addOption(getText("ContextMenu_ER_M2_DespawnAll"), actor, function()
            local n = NpcRuntime.despawnAll()
            print("[EmberRoutes][M2] Despawned count=" .. tostring(n))
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

    sub:addOption(getText("ContextMenu_ER_ThemeSettings"), actor, function()
        EmberUI.openThemeSettings("er.warehouse")
    end)

    local ids = NpcRuntime.listIds()
    local bedObj, seatObj = findClickedFurnitureAndBed(gx, gy, z, worldobjects)
    local pickedId = pickBestNpcForPosture(ids, gx + 0.5, gy + 0.5, z)
    appendPostureOptions(sub, actor, pickedId, bedObj, seatObj, #ids > 0 and (bedObj ~= nil or seatObj ~= nil))

    appendM2NpcSubmenu(sub, context, actor, gx, gy, z, bedObj, seatObj, pickedId)
end

local DebugContextMenu = {}

Kernel.registerModule({
    id = "debug-context-menu",
    onWorldReady = function(scope)
        scope:on(Events.OnFillWorldObjectContextMenu, onFillWorldMenu)
    end,
})

return DebugContextMenu
