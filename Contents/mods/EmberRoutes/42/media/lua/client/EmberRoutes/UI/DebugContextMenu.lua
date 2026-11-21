local EmberUI = require "EmberUI"
local Kernel = require "EmberRoutes/Kernel"
local SettlementStore = require "EmberRoutes/Settlement/SettlementStore"
local NpcRuntime = require "EmberRoutes/Npc/NpcRuntime"
local NpcLocomotionPort = require "EmberRoutes/Npc/NpcLocomotionPort"
local ZoneOverlay = require "EmberRoutes/UI/ZoneOverlay"
local ZoneEditorPanel = require "EmberRoutes/UI/ZoneEditorPanel"
local SettlementControlPanel = require "EmberRoutes/UI/SettlementControlPanel"
local ContainerConfigWizard = require "EmberRoutes/UI/ContainerConfigWizard"

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
                    local applied = EmberRoutesJava.setContainerRole(targetObj, roleId)
                    EmberRoutesJava.flushDirtyContainers()
                    print("[EmberRoutes][M3] SetContainerRole " .. tostring(labelName) .. " -> " .. tostring(applied))
                end)
            end

            if EmberRoutesJava.autoConfigureContainer then
                roleSub:addOption(getText("ContextMenu_ER_M3_AutoSmartZone"), actor, function()
                    local res = EmberRoutesJava.autoConfigureContainer(targetObj)
                    EmberRoutesJava.flushDirtyContainers()
                    print("[EmberRoutes][M3] AutoSmartZone " .. tostring(labelName) .. " -> " .. tostring(res))
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

        local brainTarget = pickedId or targetId
        local curSettlement = SettlementStore.findSettlementAt(gx, gy)
        if curSettlement then
            m2Sub:addOption(
                getText("ContextMenu_ER_M3_AssignCurSettlementFmt", curSettlement.name, brainTarget),
                actor,
                function()
                    local ok = NpcRuntime.assignToSettlement(brainTarget, curSettlement.id)
                    print("[EmberRoutes][M3] AssignToSettlement " .. tostring(brainTarget)
                        .. " -> " .. tostring(curSettlement.id) .. " (" .. tostring(curSettlement.name) .. ") ok=" .. tostring(ok))
                end
            )
        end

        local allSettlements = SettlementStore.list()
        if #allSettlements > 0 then
            local assignSub = ISContextMenu:getNew(context)
            local assignOpt = m2Sub:addOption(getText("ContextMenu_ER_M3_AssignSettlementSubFmt", brainTarget), nil, nil)
            context:addSubMenu(assignOpt, assignSub)
            for i = 1, #allSettlements do
                local st = allSettlements[i]
                assignSub:addOption(getText("ContextMenu_ER_M3_AssignSettlementItemFmt", st.name, st.id), actor, function()
                    local ok = NpcRuntime.assignToSettlement(brainTarget, st.id)
                    print("[EmberRoutes][M3] AssignToSettlement " .. tostring(brainTarget)
                        .. " -> " .. tostring(st.id) .. " (" .. tostring(st.name) .. ") ok=" .. tostring(ok))
                end)
            end
            assignSub:addOption(getText("ContextMenu_ER_M3_UnassignSettlementFmt", brainTarget), actor, function()
                NpcRuntime.assignToSettlement(brainTarget, nil)
                local ok = NpcRuntime.setBrainMode(brainTarget, "SETTLEMENT")
                print("[EmberRoutes][M3] Unassigned home & switched to SETTLEMENT " .. tostring(brainTarget) .. " -> " .. tostring(ok))
            end)
        end

        m2Sub:addOption(getText("ContextMenu_ER_M3_SetBrainSettlementFmt", brainTarget), actor, function()
            local ok = NpcRuntime.setBrainMode(brainTarget, "SETTLEMENT")
            print("[EmberRoutes][M3] SetBrainMode SETTLEMENT (stopFollow) " .. tostring(brainTarget) .. " -> " .. tostring(ok))
        end)

        m2Sub:addOption(getText("ContextMenu_ER_M3_SetBrainSquadFmt", brainTarget), actor, function()
            local ok = NpcRuntime.setBrainMode(brainTarget, "SQUAD")
            print("[EmberRoutes][M3] SetBrainMode SQUAD " .. tostring(brainTarget) .. " -> " .. tostring(ok))
        end)

        m2Sub:addOption(getText("ContextMenu_ER_M3_InspectBrainsAndSlots"), actor, function()
            local sid = curSettlement and curSettlement.id or nil
            print("[EmberRoutes][M3] CursorSmartSlots=" .. tostring(NpcRuntime.getSmartSlotSummary(sid)))
            for i = 1, #ids do
                local id = ids[i]
                local homeId = NpcRuntime.getAssignedSettlement(id)
                local homeLabel = (homeId ~= "" and homeId) or "none"
                local homeSlots = (homeId ~= "" and NpcRuntime.getSmartSlotSummary(homeId)) or "none"
                print("[EmberRoutes][M3] NPC " .. tostring(id)
                    .. " home=" .. tostring(homeLabel)
                    .. " mode=" .. tostring(NpcRuntime.getBrainMode(id))
                    .. " following=" .. tostring(NpcLocomotionPort.isFollowing(id))
                    .. " path=" .. tostring(NpcRuntime.getBrainStatePath(id))
                    .. " posture=" .. tostring(NpcRuntime.getPostureState(id))
                    .. " homeSlots=" .. tostring(homeSlots))
            end
        end)

        local needSub = ISContextMenu:getNew(context)
        local needOpt = m2Sub:addOption(getText("ContextMenu_ER_M3_NeedsDebugFmt", brainTarget), nil, nil)
        context:addSubMenu(needOpt, needSub)

        local function addNeedOption(key, needKind)
            needSub:addOption(getText(key), actor, function()
                local res = NpcRuntime.debugApplyNeed(brainTarget, needKind)
                print("[EmberRoutes][M3] NeedDebug -> " .. tostring(res))
            end)
        end
        addNeedOption("ContextMenu_ER_M3_NeedKit", "SURVIVAL_KIT")
        addNeedOption("ContextMenu_ER_M3_NeedHunger", "HUNGER")
        addNeedOption("ContextMenu_ER_M3_NeedThirst", "THIRST")
        addNeedOption("ContextMenu_ER_M3_NeedFatigue", "FATIGUE")
        addNeedOption("ContextMenu_ER_M3_NeedBleeding", "BLEEDING")
        addNeedOption("ContextMenu_ER_M3_NeedRestore", "RESTORE")
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
        if EmberRoutesJava.getLogisticsSummary then
            sub:addOption(getText("ContextMenu_ER_M3_InspectLogisticsFmt", settlement.name), actor, function()
                print("[EmberRoutes][M3] Logistics " .. tostring(EmberRoutesJava.getLogisticsSummary(settlement.id)))
            end)
        end
        if EmberRoutesJava.submitDeliveryOrder then
            sub:addOption(getText("ContextMenu_ER_M3_TestDeliverAppleFmt", settlement.name), actor, function()
                local oid = EmberRoutesJava.submitDeliveryOrder(settlement.id, playerIndex, "Base.Apple", 1)
                print("[EmberRoutes][M3] SubmitDelivery Base.Apple x1 -> " .. tostring(oid))
            end)
        end
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
