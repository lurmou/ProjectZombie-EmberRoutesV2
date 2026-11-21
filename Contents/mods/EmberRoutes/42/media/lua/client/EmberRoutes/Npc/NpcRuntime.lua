local Kernel = require "EmberRoutes/Kernel"

local NpcRuntime = {}

local function tickNpcs()
    if EmberRoutesJava then
        EmberRoutesJava.tickNpcs()
    end
end

local function loadSavedNpcs()
    if EmberRoutesJava then
        return EmberRoutesJava.loadNpcsFromSave()
    end
    return 0
end

function NpcRuntime.spawn(x, y, z, female, optionalName)
    if not EmberRoutesJava then return nil end
    return EmberRoutesJava.spawnNpc(x, y, z or 0, female == true, optionalName)
end

function NpcRuntime.despawn(npcId)
    if not EmberRoutesJava or not npcId then return false end
    return EmberRoutesJava.despawnNpc(npcId) == true
end

function NpcRuntime.despawnAll()
    if not EmberRoutesJava then return 0 end
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
    return EmberRoutesJava.phaseOutNpc(npcId) == true
end

function NpcRuntime.phaseIn(npcId)
    if not EmberRoutesJava or not npcId then return false end
    return EmberRoutesJava.phaseInNpc(npcId) == true
end

function NpcRuntime.kill(npcId)
    if not EmberRoutesJava or not npcId then return false end
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

Kernel.exposeApi("npc", NpcRuntime)
Kernel.registerModule({
    id = "npc-runtime",
    onWorldReady = function()
        loadSavedNpcs()
    end,
    tick = { stage = "PHYSICS_MOVE", fn = tickNpcs },
})

return NpcRuntime
