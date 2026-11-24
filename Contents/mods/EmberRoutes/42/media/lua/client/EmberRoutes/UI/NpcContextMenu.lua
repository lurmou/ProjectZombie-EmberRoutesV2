local Kernel = require "EmberRoutes/Kernel"
local NpcRuntime = require "EmberRoutes/Npc/NpcRuntime"
local NpcLocomotionPort = require "EmberRoutes/Npc/NpcLocomotionPort"
local SquadService = require "EmberRoutes/Squad/SquadService"
local SquadHUD = require "EmberRoutes/UI/SquadHUD"
local NpcMedicalPanel = require "EmberRoutes/UI/NpcMedicalPanel"
local NpcInventoryPanel = require "EmberRoutes/UI/NpcInventoryPanel"

local NpcContextMenu = {}

local function resolveClickSquare(playerIndex, actor, worldobjects)
    local cell = getCell and getCell()
    if not cell then return nil end

    if screenToIsoX and screenToIsoY and getMouseX and getMouseY then
        local z = math.floor(actor:getZ() or 0)
        local gx = math.floor(screenToIsoX(playerIndex, getMouseX(), getMouseY(), z))
        local gy = math.floor(screenToIsoY(playerIndex, getMouseX(), getMouseY(), z))
        local sq = cell:getGridSquare(gx, gy, z)
        if sq then return sq end
    end

    if worldobjects then
        for _, obj in ipairs(worldobjects) do
            if obj and obj.getSquare then
                local sq = obj:getSquare()
                if sq then return sq end
            end
        end
    end
    return nil
end

local function collectNearbyNpcs(square, worldobjects)
    local found, seen = {}, {}
    local function tryAdd(obj)
        local isNpc, id = NpcRuntime.isTargetNpc(obj)
        if isNpc and not seen[id] and NpcMedicalPanel.isNpcLive(id, obj) then
            seen[id] = true
            found[#found + 1] = { id = id, npc = obj }
        end
    end

    if worldobjects then
        for _, obj in ipairs(worldobjects) do
            tryAdd(obj)
        end
    end

    if #found == 0 and square then
        local cell = getCell and getCell()
        if cell then
            local cx, cy, cz = square:getX(), square:getY(), square:getZ()
            for dx = -1, 1 do
                for dy = -1, 1 do
                    local sq = cell:getGridSquare(cx + dx, cy + dy, cz)
                    local moving = sq and sq.getMovingObjects and sq:getMovingObjects()
                    if moving then
                        for i = 0, moving:size() - 1 do
                            tryAdd(moving:get(i))
                        end
                    end
                end
            end
        end
    end
    return found
end

local function onFillWorldMenu(playerIndex, context, worldobjects, test)
    if test or not EmberRoutesJava or not context then return end
    local actor = getSpecificPlayer and getSpecificPlayer(playerIndex)
    if not actor or (actor.isDead and actor:isDead()) then return end

    local sq = resolveClickSquare(playerIndex, actor, worldobjects)
    local npcs = collectNearbyNpcs(sq, worldobjects)
    if #npcs == 0 then return end

    if context.removeOptionByName and getTextOrNull then
        local medCheckLabel = getTextOrNull("ContextMenu_Medical_Check")
        if medCheckLabel then context:removeOptionByName(medCheckLabel) end
    end

    local bagIcon = getTexture and getTexture("media/ui/Icon_InventoryBasic.png") or nil
    local heartIcon = getTexture and getTexture("media/ui/Heart_Red.png") or nil

    for i = 1, #npcs do
        local entry = npcs[i]
        local name = NpcRuntime.getDisplayName(entry.id, entry.npc)
        if context.removeOptionByName and getTextOrNull then
            local tradeByDisplay = getTextOrNull("ContextMenu_Trade", name)
            if tradeByDisplay then context:removeOptionByName(tradeByDisplay) end
            local uname = entry.npc and entry.npc.getUsername and entry.npc:getUsername() or nil
            if uname and uname ~= "" and uname ~= name then
                local tradeByUser = getTextOrNull("ContextMenu_Trade", uname)
                if tradeByUser then context:removeOptionByName(tradeByUser) end
            end
        end

        if NpcLocomotionPort.isFollowing(entry.id) or SquadService.isMemberInSquad(entry.id) then
            context:addOption(
                getText("ContextMenu_ER_Npc_StopFollowFmt", name),
                entry.id,
                function(id)
                    local res = SquadService.dismissMember(id)
                    if res == "PENDING_STOP" then
                        SquadHUD.showNotice(getText("IGUI_ER_Squad_NoticePendingStop"), false)
                    end
                end
            )
            if not SquadHUD.isOpen() then
                context:addOption(
                    getText("ContextMenu_ER_Squad_OpenHud"),
                    entry.id,
                    function()
                        SquadHUD.open()
                    end
                )
            end
        else
            context:addOption(
                getText("ContextMenu_ER_Npc_FollowFmt", name),
                entry.id,
                function(id)
                    SquadService.addMember(id, playerIndex)
                end
            )
        end

        local invOpt = context:addOption(
            getText("ContextMenu_ER_Npc_OpenInventoryFmt", name),
            actor,
            NpcInventoryPanel.open,
            entry.npc
        )
        if invOpt and bagIcon then
            invOpt.iconTexture = bagIcon
        end

        local medOpt = context:addOption(
            getText("ContextMenu_ER_Npc_MedicalCheckFmt", name),
            actor,
            NpcMedicalPanel.open,
            entry.npc
        )
        if medOpt and heartIcon then
            medOpt.iconTexture = heartIcon
        end
    end
end

Kernel.registerModule({
    id = "npc-context-menu",
    onWorldReady = function(scope)
        scope:on(Events.OnFillWorldObjectContextMenu, onFillWorldMenu)
    end,
})

return NpcContextMenu
