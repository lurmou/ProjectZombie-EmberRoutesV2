local Kernel = require "EmberRoutes/Kernel"

local NpcRuntime = {}

local function tickNpcs()
    if EmberRoutesJava then
        EmberRoutesJava.tickNpcs()
    end
end

local function tickNpcBrains(frameTick, dtSim, simTime)
    if EmberRoutesJava and EmberRoutesJava.tickNpcBrains then
        EmberRoutesJava.tickNpcBrains(frameTick or 0, dtSim or 0.0, simTime or 0.0)
    end
end

local function loadSavedNpcs()
    if EmberRoutesJava then
        return EmberRoutesJava.loadNpcsFromSave()
    end
    return 0
end

local function clearVanillaActionQueue(npcObj)
    if not npcObj or not ISTimedActionQueue or not ISTimedActionQueue.queues then
        return
    end
    ISTimedActionQueue.queues[npcObj] = nil
end

function NpcRuntime.spawn(x, y, z, female, optionalName)
    if not EmberRoutesJava then return nil end
    return EmberRoutesJava.spawnNpc(x, y, z or 0, female == true, optionalName)
end

function NpcRuntime.despawn(npcId)
    if not EmberRoutesJava or not npcId then return false end
    clearVanillaActionQueue(NpcRuntime.getLiveObject(npcId))
    return EmberRoutesJava.despawnNpc(npcId) == true
end

function NpcRuntime.despawnAll()
    if not EmberRoutesJava then return 0 end
    if ISTimedActionQueue and ISTimedActionQueue.queues then
        local ids = NpcRuntime.listIds()
        for i = 1, #ids do
            clearVanillaActionQueue(NpcRuntime.getLiveObject(ids[i]))
        end
    end
    return EmberRoutesJava.despawnAllNpcs()
end

function NpcRuntime.listIds()
    if not EmberRoutesJava then return {} end
    return EmberRoutesJava.listNpcIds() or {}
end

function NpcRuntime.getNpcId(obj)
    if not obj or not EmberRoutesJava or not EmberRoutesJava.getNpcId then return nil end
    local id = EmberRoutesJava.getNpcId(obj)
    if id and id ~= "" then return id end
    return nil
end

function NpcRuntime.isTargetNpc(obj)
    local id = NpcRuntime.getNpcId(obj)
    if id then
        return true, id
    end
    return false, nil
end

function NpcRuntime.isAttached(npcId)
    if not npcId or not EmberRoutesJava or not EmberRoutesJava.isNpcAttached then return false end
    return EmberRoutesJava.isNpcAttached(npcId) == true
end

function NpcRuntime.getLiveObject(npcId)
    if not npcId or not EmberRoutesJava or not EmberRoutesJava.getNpcObject then return nil end
    return EmberRoutesJava.getNpcObject(npcId)
end

function NpcRuntime.getDisplayName(npcId, npcObj)
    if npcId and EmberRoutesJava and EmberRoutesJava.getNpcDisplayName then
        local name = EmberRoutesJava.getNpcDisplayName(npcId)
        if name and name ~= "" then return name end
    end
    if npcObj then
        if npcObj.getDescriptor then
            local desc = npcObj:getDescriptor()
            if desc then
                local fn = desc.getForename and desc:getForename() or ""
                local sn = desc.getSurname and desc:getSurname() or ""
                local full = (tostring(fn) .. " " .. tostring(sn)):gsub("^%s+", ""):gsub("%s+$", "")
                if full ~= "" then return full end
            end
        end
        if npcObj.getUsername then
            local uname = npcObj:getUsername()
            if uname and uname ~= "" then return uname end
        end
    end
    return npcId or "NPC"
end

function NpcRuntime.getInfo(npcId)
    if not EmberRoutesJava or not npcId then return nil end
    return EmberRoutesJava.getNpcInfo(npcId)
end

function NpcRuntime.phaseOut(npcId)
    if not EmberRoutesJava or not npcId then return false end
    clearVanillaActionQueue(NpcRuntime.getLiveObject(npcId))
    return EmberRoutesJava.phaseOutNpc(npcId) == true
end

function NpcRuntime.phaseIn(npcId)
    if not EmberRoutesJava or not npcId then return false end
    return EmberRoutesJava.phaseInNpc(npcId) == true
end

function NpcRuntime.kill(npcId)
    if not EmberRoutesJava or not npcId then return false end
    clearVanillaActionQueue(NpcRuntime.getLiveObject(npcId))
    return EmberRoutesJava.killNpc(npcId) == true
end

function NpcRuntime.giveTestKit(npcId)
    if not EmberRoutesJava or not npcId then return "UNAVAILABLE" end
    return EmberRoutesJava.giveNpcTestKit(npcId)
end

function NpcRuntime.inspectInventoryIds(npcId)
    if not EmberRoutesJava or not npcId then return "" end
    return EmberRoutesJava.inspectNpcInventoryIds(npcId)
end

function NpcRuntime.saveNow()
    if not EmberRoutesJava then return false end
    return EmberRoutesJava.saveNpcsToSave() == true
end

function NpcRuntime.getDebugLine(npcId)
    if not EmberRoutesJava or not npcId then return "" end
    return EmberRoutesJava.getNpcDebugLine(npcId) or ""
end

function NpcRuntime.sitOnFurniture(npcId, obj)
    if not EmberRoutesJava or not npcId or not obj then return false end
    return EmberRoutesJava.sitNpcOnFurniture(npcId, obj) == true
end

function NpcRuntime.sleepOnBed(npcId, obj)
    if not EmberRoutesJava or not npcId or not obj then return false end
    return EmberRoutesJava.sleepNpcOnBed(npcId, obj) == true
end

function NpcRuntime.standUp(npcId, urgent)
    if not EmberRoutesJava or not npcId then return false end
    return EmberRoutesJava.standUpNpc(npcId, urgent == true) == true
end

function NpcRuntime.standUpAll(urgent)
    if not EmberRoutesJava then return 0 end
    return EmberRoutesJava.standUpAllNpcs(urgent == true)
end

function NpcRuntime.getPostureState(npcId)
    if not EmberRoutesJava or not npcId then return "MISSING" end
    return EmberRoutesJava.getNpcPostureState(npcId) or "MISSING"
end

function NpcRuntime.isAnimRecorderActive(npcId)
    if not EmberRoutesJava or not EmberRoutesJava.isNpcAnimRecorderActive or not npcId then return false end
    return EmberRoutesJava.isNpcAnimRecorderActive(npcId) == true
end

function NpcRuntime.toggleAnimRecorder(npcId)
    if not EmberRoutesJava or not EmberRoutesJava.toggleNpcAnimRecorder or not npcId then return "UNAVAILABLE" end
    return EmberRoutesJava.toggleNpcAnimRecorder(npcId) or "UNAVAILABLE"
end

function NpcRuntime.isAllAnimRecorderEnabled()
    if not EmberRoutesJava or not EmberRoutesJava.isAllNpcAnimRecorderEnabled then return false end
    return EmberRoutesJava.isAllNpcAnimRecorderEnabled() == true
end

function NpcRuntime.setAllAnimRecorderEnabled(enabled)
    if not EmberRoutesJava or not EmberRoutesJava.setAllNpcAnimRecorderEnabled then return "UNAVAILABLE" end
    return EmberRoutesJava.setAllNpcAnimRecorderEnabled(enabled == true) or "UNAVAILABLE"
end

function NpcRuntime.setBrainMode(npcId, modeName)
    if not EmberRoutesJava or not EmberRoutesJava.setNpcBrainMode or not npcId or not modeName then return false end
    return EmberRoutesJava.setNpcBrainMode(npcId, modeName) == true
end

function NpcRuntime.getBrainMode(npcId)
    if not EmberRoutesJava or not EmberRoutesJava.getNpcBrainMode or not npcId then return "MISSING" end
    return EmberRoutesJava.getNpcBrainMode(npcId) or "MISSING"
end

function NpcRuntime.getBrainStatePath(npcId)
    if not EmberRoutesJava or not EmberRoutesJava.getNpcBrainStatePath or not npcId then return "MISSING" end
    return EmberRoutesJava.getNpcBrainStatePath(npcId) or "MISSING"
end

function NpcRuntime.getSmartSlotSummary(settlementId)
    if not EmberRoutesJava or not EmberRoutesJava.getSmartSlotSummary then return "none" end
    return EmberRoutesJava.getSmartSlotSummary(settlementId) or "none"
end

function NpcRuntime.debugApplyNeed(npcId, needKind)
    if not EmberRoutesJava or not EmberRoutesJava.debugApplyNpcNeed or not npcId or not needKind then
        return "UNAVAILABLE"
    end
    return EmberRoutesJava.debugApplyNpcNeed(npcId, needKind) or "UNAVAILABLE"
end

function NpcRuntime.assignToSettlement(npcId, settlementId)
    if not EmberRoutesJava or not EmberRoutesJava.assignNpcToSettlement or not npcId then return false end
    return EmberRoutesJava.assignNpcToSettlement(npcId, settlementId) == true
end

function NpcRuntime.getAssignedSettlement(npcId)
    if not EmberRoutesJava or not EmberRoutesJava.getNpcAssignedSettlement or not npcId then return "" end
    return EmberRoutesJava.getNpcAssignedSettlement(npcId) or ""
end

function NpcRuntime.setGuardDuty(npcId, enabled)
    if not EmberRoutesJava or not EmberRoutesJava.setNpcGuardDuty or not npcId then return false end
    return EmberRoutesJava.setNpcGuardDuty(npcId, enabled == true) == true
end

function NpcRuntime.isGuardDuty(npcId)
    if not EmberRoutesJava or not EmberRoutesJava.isNpcGuardDuty or not npcId then return false end
    return EmberRoutesJava.isNpcGuardDuty(npcId) == true
end

Kernel.exposeApi("npc", NpcRuntime)
Kernel.registerModule({
    id = "npc-runtime",
    onWorldReady = function(scope)
        loadSavedNpcs()
        if scope and Events and Events.OnCharacterDeath then
            scope:on(Events.OnCharacterDeath, function(chr)
                if chr and NpcRuntime.getNpcId(chr) then
                    clearVanillaActionQueue(chr)
                end
            end)
        end
    end,
    ticks = {
        { stage = "PHYSICS_MOVE", fn = tickNpcs },
        { stage = "AGENT_BRAIN", fn = tickNpcBrains },
    },
})

return NpcRuntime
