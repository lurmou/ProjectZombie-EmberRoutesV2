local Kernel = require "EmberRoutes/Kernel"

local NpcLocomotionPort = {}

local activeMoves = {}
local persistentListeners = {}

local function snapshotList(src)
    local out = {}
    if src then
        for i = 1, #src do
            out[i] = src[i]
        end
    end
    return out
end

local function fireCallbacks(npcId, oneShot, status, reason)
    if type(oneShot) == "function" then
        local ok, err = pcall(oneShot, npcId, status, reason)
        if not ok then
            print("[EmberRoutes][NpcLocomotionPort] oneShot callback error: " .. tostring(err))
        end
    end
    local listeners = snapshotList(persistentListeners[npcId])
    for i = 1, #listeners do
        local fn = listeners[i]
        if type(fn) == "function" then
            local ok, err = pcall(fn, npcId, status, reason)
            if not ok then
                print("[EmberRoutes][NpcLocomotionPort] listener error: " .. tostring(err))
            end
        end
    end
end

local function settleMove(npcId, status, reason)
    local entry = activeMoves[npcId]
    if not entry then return end
    activeMoves[npcId] = nil
    fireCallbacks(npcId, entry.onArrival, status, reason)
end

--- PHYSICS_MOVE 阶段零分配批事件消费：无状态跃迁时 drainNpcMoveEvents() 直接返回 nil（0 表分配、0 遍历）。
local function drainMoveEvents()
    if not EmberRoutesJava or not EmberRoutesJava.drainNpcMoveEvents then return end
    local events = EmberRoutesJava.drainNpcMoveEvents()
    if not events then return end
    for i = 1, #events do
        local ev = events[i]
        if ev and ev.npcId then
            local entry = activeMoves[ev.npcId]
            if entry and ev.seq and ev.seq >= entry.seq then
                settleMove(ev.npcId, ev.status, ev.reason or ev.status)
            end
        end
    end
end

function NpcLocomotionPort.moveTo(npcId, x, y, z, opts)
    if not EmberRoutesJava or not npcId then
        return nil, "UNAVAILABLE"
    end
    local run = opts and opts.run == true or false
    local onArrival = opts and opts.onArrival or nil

    if activeMoves[npcId] then
        settleMove(npcId, "INTERRUPTED", "SUPERSEDED")
    end

    local seq = EmberRoutesJava.moveNpcTo(npcId, x, y, z or 0, run)
    if not seq or seq < 0 then
        fireCallbacks(npcId, onArrival, "NO_PATH", "INVALID_NPC")
        return nil, "INVALID_NPC"
    end

    activeMoves[npcId] = {
        seq = seq,
        onArrival = onArrival,
    }
    return seq
end

function NpcLocomotionPort.stop(npcId)
    if not EmberRoutesJava or not npcId then return false end
    local ok = EmberRoutesJava.stopNpcMove(npcId)
    if activeMoves[npcId] then
        settleMove(npcId, "INTERRUPTED", "STOPPED")
    end
    return ok
end

function NpcLocomotionPort.setRunning(npcId, running)
    if not EmberRoutesJava or not npcId then return false end
    return EmberRoutesJava.setNpcRunning(npcId, running == true)
end

function NpcLocomotionPort.isMoving(npcId)
    if not EmberRoutesJava or not npcId then return false end
    return EmberRoutesJava.isNpcMoving(npcId) == true
end

function NpcLocomotionPort.getStatus(npcId)
    if not EmberRoutesJava or not npcId then return nil end
    return EmberRoutesJava.getNpcMoveStatus(npcId)
end

function NpcLocomotionPort.follow(npcId, opts)
    if not EmberRoutesJava or not EmberRoutesJava.startNpcFollow or not npcId then
        return false
    end
    if activeMoves[npcId] then
        settleMove(npcId, "INTERRUPTED", "FOLLOW_START")
    end
    local playerIndex = opts and opts.playerIndex or 0
    local slot = opts and opts.slot or -1
    return EmberRoutesJava.startNpcFollow(npcId, playerIndex, slot) == true
end

function NpcLocomotionPort.stopFollow(npcId)
    if not EmberRoutesJava or not EmberRoutesJava.stopNpcFollow or not npcId then
        return false
    end
    return EmberRoutesJava.stopNpcFollow(npcId) == true
end

function NpcLocomotionPort.isFollowing(npcId)
    if not EmberRoutesJava or not EmberRoutesJava.isNpcFollowing or not npcId then
        return false
    end
    return EmberRoutesJava.isNpcFollowing(npcId) == true
end

function NpcLocomotionPort.getFollowState(npcId)
    if not EmberRoutesJava or not EmberRoutesJava.getNpcFollowState or not npcId then
        return "INACTIVE"
    end
    return EmberRoutesJava.getNpcFollowState(npcId) or "INACTIVE"
end

function NpcLocomotionPort.onArrival(npcId, fn)
    assert(npcId and type(fn) == "function", "NpcLocomotionPort.onArrival expects (npcId, function)")
    local list = persistentListeners[npcId]
    if not list then
        list = {}
        persistentListeners[npcId] = list
    end
    list[#list + 1] = fn
    local active = true
    return function()
        if not active then return end
        active = false
        local target = persistentListeners[npcId]
        if not target then return end
        for i = #target, 1, -1 do
            if target[i] == fn then
                table.remove(target, i)
                break
            end
        end
    end
end

Kernel.exposeApi("npcLocomotion", NpcLocomotionPort)
Kernel.registerModule({
    id = "npc-locomotion",
    onWorldReady = function(scope)
        scope:defer(function()
            activeMoves = {}
            persistentListeners = {}
        end)
    end,
    tick = { stage = "PHYSICS_MOVE", fn = drainMoveEvents },
})

return NpcLocomotionPort
