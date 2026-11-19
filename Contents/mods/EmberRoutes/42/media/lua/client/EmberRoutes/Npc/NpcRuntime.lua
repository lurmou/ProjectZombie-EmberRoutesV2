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

Kernel.exposeApi("npc", NpcRuntime)
Kernel.registerModule({
    id = "npc-runtime",
    onWorldReady = function()
        loadSavedNpcs()
    end,
    tick = { stage = "PHYSICS_MOVE", fn = tickNpcs },
})

return NpcRuntime
