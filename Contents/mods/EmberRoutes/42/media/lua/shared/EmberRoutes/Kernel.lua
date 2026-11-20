EmberRoutes = EmberRoutes or {}
EmberRoutes.api = EmberRoutes.api or {}

local Kernel, Scope = {}, {}
local STAGES = { "WORLD_SYNC", "PHYSICS_MOVE", "SOFT_LOCKS", "AGENT_BRAIN", "MACRO_WORLD" }
local STAGE_INDEX = { WORLD_SYNC = 1, PHYSICS_MOVE = 2, SOFT_LOCKS = 3, AGENT_BRAIN = 4, MACRO_WORLD = 5 }
local SLOW_STAGE_MS, SLOW_LOG_COOLDOWN_MS, ERR_LOG_COOLDOWN_MS = 6.0, 5000.0, 5000.0

local modules, moduleIdx, bootOrder = {}, {}, nil
local compats, compatIdx = {}, {}
local addons, addonIdx = {}, {}
local flagGuards, subscribedOnce = {}, {}
local modDataScope, worldScope = nil, nil
local modDataBooted, worldBooted = false, false
local activeModules, stageBuckets, pausedHandlers = {}, { {}, {}, {}, {}, {} }, {}
local frameCounter, simTime = 0, 0.0
local lastSlowLogMs, lastErrLogMs = {}, {}

local function noop() end
local function isTableEmpty(t)
    for _ in pairs(t) do return false end
    return true
end
local function safeCall(tag, fn, ...)
    local ok, err = pcall(fn, ...)
    if not ok then print("[EmberRoutes.Kernel] " .. tag .. ": " .. tostring(err)) end
    return ok, err
end
local function nowDiagMs()
    local bridge = EmberRoutesJava
    if bridge and type(bridge.diagnosticTimeNanos) == "function" then
        return bridge.diagnosticTimeNanos() / 1000000.0
    end
    return nil
end
local function logThrottled(bucket, key, cooldownMs, nowMs, msg)
    if nowMs then
        local prev = bucket[key]
        if prev and (nowMs - prev) < cooldownMs then return end
        bucket[key] = nowMs
    end
    print(msg)
end

-- ==================== 1. Structured Disposal Scope ====================

local function createScope(label)
    return setmetatable({ label = label or "scope", disposed = false, disposing = false, cleanups = {} }, { __index = Scope })
end

function Scope:isDisposed() return self.disposed == true end

function Scope:defer(fn)
    assert(type(fn) == "function", "scope:defer requires a function")
    if self.disposed then safeCall("cleanup error", fn); return noop end
    local list, entry = self.cleanups, { fn = fn, released = false }
    list[#list + 1] = entry
    return function()
        if entry.released then return end
        entry.released = true
        local clean = entry.fn; entry.fn = nil
        if not self.disposing then
            for i = #list, 1, -1 do if list[i] == entry then table.remove(list, i); break end end
        end
        if clean then safeCall("cleanup error", clean) end
    end
end

function Scope:effect(fn)
    assert(type(fn) == "function", "scope:effect requires a function")
    if self.disposed then return noop end
    local cleanup = fn()
    if type(cleanup) ~= "function" then return noop end
    if self.disposed then safeCall("cleanup error", cleanup); return noop end
    return self:defer(cleanup)
end

function Scope:on(eventObj, handler)
    assert(type(eventObj) == "table" and type(eventObj.Add) == "function" and type(eventObj.Remove) == "function", "invalid Event")
    assert(type(handler) == "function", "scope:on requires handler function")
    if self.disposed then return noop end
    eventObj.Add(handler)
    return self:defer(function() eventObj.Remove(handler) end)
end

function Scope:subscribe(store, fn)
    assert(type(store) == "table" and type(fn) == "function", "scope:subscribe requires (store, fn)")
    if self.disposed then return noop end
    local unsub
    if type(store.onChanged) == "function" then unsub = store.onChanged(fn)
    elseif type(store.subscribe) == "function" then unsub = store:subscribe(fn)
    else error("[EmberRoutes.Kernel] Store lacks onChanged/subscribe") end
    if type(unsub) == "function" then return self:defer(unsub) end
    if type(unsub) == "table" and type(unsub.release) == "function" then return self:defer(function() unsub:release() end) end
    return noop
end

function Scope:acquireGlobalFlag(tbl, key, val)
    assert(type(tbl) == "table" and key ~= nil, "acquireGlobalFlag requires (tbl, key, val)")
    if self.disposed then return noop end
    local byKey = flagGuards[tbl] or {}; flagGuards[tbl] = byKey
    local rec = byKey[key] or { count = 0, orig = tbl[key] }; byKey[key] = rec
    rec.count = rec.count + 1; tbl[key] = val
    return self:defer(function()
        rec.count = rec.count - 1
        if rec.count <= 0 then
            tbl[key] = rec.orig; byKey[key] = nil
            if isTableEmpty(byKey) then flagGuards[tbl] = nil end
        end
    end)
end

function Scope:wrap(tbl, method, factory)
    assert(type(tbl) == "table" and method ~= nil and type(factory) == "function", "scope:wrap requires (tbl, method, factory)")
    if self.disposed then return noop end
    local orig, active = tbl[method], true
    local impl = factory(orig); assert(type(impl) == "function", "wrap factory must return a function")
    local function guardFn(...)
        if active then return impl(...) end
        if type(orig) == "function" then return orig(...) end
    end
    tbl[method] = guardFn
    return self:defer(function() active = false; if tbl[method] == guardFn then tbl[method] = orig end end)
end

function Scope:scope(label)
    if self.disposed then local dead = createScope(label); dead.disposed = true; return dead end
    local child = createScope(label)
    child._detachParent = self:defer(function() child:dispose() end)
    return child
end

function Scope:dispose()
    if self.disposed then return end
    self.disposed, self.disposing = true, true
    local list = self.cleanups
    for i = #list, 1, -1 do
        local entry = list[i]
        if entry and not entry.released then
            entry.released = true; local clean = entry.fn; entry.fn = nil
            if clean then safeCall("cleanup error", clean) end
        end
    end
    self.cleanups, self.disposing = {}, false
    local detach = self._detachParent
    if detach then self._detachParent = nil; detach() end
end

-- ==================== 2. Registration & Public Extension Surface ====================

local function upsertById(list, idxMap, def)
    local pos = idxMap[def.id] or (#list + 1)
    list[pos], idxMap[def.id] = def, pos
end

function Kernel.subscribeOnce(eventObj, key, fn)
    if type(key) ~= "string" or type(fn) ~= "function" then return false end
    if type(eventObj) ~= "table" or type(eventObj.Add) ~= "function" then return false end
    local keys = subscribedOnce[eventObj]
    if not keys then keys = {}; subscribedOnce[eventObj] = keys end
    if keys[key] then return false end
    keys[key] = true
    eventObj.Add(fn)
    return true
end

function Kernel.registerModule(def)
    assert(type(def) == "table" and type(def.id) == "string" and def.id ~= "", "invalid module def")
    upsertById(modules, moduleIdx, def)
end

function Kernel.setBootOrder(idList)
    assert(type(idList) == "table", "setBootOrder requires table")
    bootOrder = idList
end

function Kernel.exposeApi(name, value)
    assert(type(name) == "string" and name ~= "" and value ~= nil, "invalid api export: " .. tostring(name))
    EmberRoutes.api[name] = value
    return value
end

function Kernel.registerCompat(def)
    assert(type(def) == "table" and type(def.id) == "string" and def.id ~= "" and type(def.apply) == "function", "invalid compat def")
    upsertById(compats, compatIdx, def)
end

function Kernel.registerAddon(def)
    assert(type(def) == "table" and type(def.id) == "string" and def.id ~= "" and type(def.apply) == "function", "invalid addon def")
    upsertById(addons, addonIdx, def)
end

local function orderedModules()
    local ordered, seen = {}, {}
    if bootOrder then
        for i = 1, #bootOrder do
            local idx = moduleIdx[bootOrder[i]]
            if idx then ordered[#ordered + 1] = modules[idx]; seen[bootOrder[i]] = true end
        end
    end
    for i = 1, #modules do
        local m = modules[i]
        if not seen[m.id] then ordered[#ordered + 1] = m; seen[m.id] = true end
    end
    return ordered
end

-- ==================== 3. Staged Lifecycle & 5-Stage Dual-Clock Tick Pipeline ====================

local function resetPipeline()
    activeModules = {}
    stageBuckets = { {}, {}, {}, {}, {} }
    pausedHandlers = {}
    frameCounter, simTime = 0, 0.0
    lastSlowLogMs, lastErrLogMs = {}, {}
end

local function registerTickSpec(modId, spec)
    if type(spec) ~= "table" or type(spec.fn) ~= "function" then return end
    local sIdx = STAGE_INDEX[spec.stage]
    assert(sIdx ~= nil, "[EmberRoutes.Kernel] Unknown tick stage '" .. tostring(spec.stage) .. "' in module '" .. modId .. "'")
    local bucket = stageBuckets[sIdx]
    bucket[#bucket + 1] = { id = modId, stage = spec.stage, fn = spec.fn }
end

local function buildPipeline(ordered)
    resetPipeline()
    for i = 1, #ordered do
        local m = ordered[i]
        activeModules[#activeModules + 1] = m
        if type(m.tick) == "table" then registerTickSpec(m.id, m.tick) end
        if type(m.ticks) == "table" then
            for j = 1, #m.ticks do registerTickSpec(m.id, m.ticks[j]) end
        end
        if type(m.onTickPaused) == "function" then
            pausedHandlers[#pausedHandlers + 1] = { id = m.id, fn = m.onTickPaused }
        end
    end
end

local function activateIsolatedList(list, kindLabel, parentScope)
    for i = 1, #list do
        local def = list[i]
        local condOk, enabled = true, true
        if type(def.when) == "function" then condOk, enabled = pcall(def.when) end
        if not condOk then
            print("[EmberRoutes.Kernel] " .. kindLabel .. " '" .. def.id .. "' when() failed: " .. tostring(enabled))
        elseif enabled then
            local isoScope = parentScope:scope(kindLabel .. ":" .. def.id)
            local ok, err = pcall(def.apply, isoScope, EmberRoutes.api)
            if not ok then
                isoScope:dispose()
                print("[EmberRoutes.Kernel] Isolated " .. kindLabel .. " '" .. def.id .. "' failed: " .. tostring(err))
            end
        end
    end
end

function Kernel.onInitModData(isNewGame)
    if modDataBooted then return modDataScope end
    local scope = createScope("moddata")
    modDataScope, modDataBooted = scope, true
    local ordered = orderedModules()
    local ok, err = pcall(function()
        for i = 1, #ordered do
            local m = ordered[i]
            if type(m.onModDataReady) == "function" then
                m.onModDataReady(scope:scope("moddata:" .. m.id), isNewGame == true)
            end
        end
    end)
    if not ok then Kernel.teardown(); error(err) end
    return scope
end

function Kernel.onWorldReady()
    if worldBooted and worldScope and not worldScope.disposed then return worldScope end
    if not modDataBooted then Kernel.onInitModData(false) end
    local scope = createScope("world")
    worldScope, worldBooted = scope, true
    local ordered = orderedModules()
    local ok, err = pcall(function()
        for i = 1, #ordered do
            local m = ordered[i]
            if type(m.onWorldReady) == "function" then
                m.onWorldReady(scope:scope("module:" .. m.id))
            end
        end
        buildPipeline(ordered)
        activateIsolatedList(compats, "compat", scope)
        activateIsolatedList(addons, "addon", scope)
    end)
    if not ok then Kernel.teardown(); error(err) end
    return scope
end

function Kernel.onPlayerCreated(playerIndex, player)
    if not worldBooted then return end
    for i = 1, #activeModules do
        local m = activeModules[i]
        if type(m.onPlayerCreated) == "function" then
            safeCall("onPlayerCreated:" .. m.id, m.onPlayerCreated, playerIndex, player)
        end
    end
end

local function computeSimDelta()
    local gt = (getGameTime and getGameTime())
        or (GameTime and GameTime.getInstance and GameTime.getInstance())
        or nil
    if gt and gt.getGameWorldSecondsSinceLastUpdate then
        local dt = gt:getGameWorldSecondsSinceLastUpdate()
        if type(dt) == "number" and dt > 0 then return dt end
    end
    return 1.0 / 60.0
end

function Kernel.dispatchTick(numberTicks)
    if not worldBooted then return end
    frameCounter = (type(numberTicks) == "number") and numberTicks or (frameCounter + 1)
    local dtSim = computeSimDelta()
    simTime = simTime + dtSim

    for sIdx = 1, 5 do
        local bucket = stageBuckets[sIdx]
        local n = #bucket
        if n > 0 then
            local t0 = nowDiagMs()
            for i = 1, n do
                local entry = bucket[i]
                local ok, err = pcall(entry.fn, frameCounter, dtSim, simTime)
                if not ok then
                    logThrottled(lastErrLogMs, entry.stage .. ":" .. entry.id, ERR_LOG_COOLDOWN_MS, nowDiagMs(),
                        "[EmberRoutes.Kernel] Tick error [" .. entry.stage .. "/" .. entry.id .. "]: " .. tostring(err))
                end
            end
            if t0 then
                local t1 = nowDiagMs()
                local elapsed = t1 - t0
                if elapsed >= SLOW_STAGE_MS then
                    logThrottled(lastSlowLogMs, STAGES[sIdx], SLOW_LOG_COOLDOWN_MS, t1,
                        string.format("[EmberRoutes.Perf] SLOW_STAGE stage=%s ms=%.3f tick=%.0f", STAGES[sIdx], elapsed, frameCounter))
                end
            end
        end
    end
end

function Kernel.dispatchTickPaused()
    if not worldBooted then return end
    for i = 1, #pausedHandlers do
        local entry = pausedHandlers[i]
        local ok, err = pcall(entry.fn)
        if not ok then
            logThrottled(lastErrLogMs, "PAUSED:" .. entry.id, ERR_LOG_COOLDOWN_MS, nowDiagMs(),
                "[EmberRoutes.Kernel] Paused tick error [" .. entry.id .. "]: " .. tostring(err))
        end
    end
end

function Kernel.teardown()
    local prevActive = activeModules
    local ws, ms = worldScope, modDataScope
    worldScope, modDataScope = nil, nil
    worldBooted, modDataBooted = false, false
    resetPipeline()

    if ws and not ws.disposed then ws:dispose() end
    if ms and not ms.disposed then ms:dispose() end
    for i = #prevActive, 1, -1 do
        local m = prevActive[i]
        if type(m.onTeardown) == "function" then safeCall("onTeardown:" .. m.id, m.onTeardown) end
    end
    if EmberRoutesJava and EmberRoutesJava.reset then EmberRoutesJava.reset() end
end

function Kernel.scope(label)
    if worldScope and not worldScope.disposed then return worldScope:scope(label) end
    return createScope(label or "standalone")
end

function Kernel.isWorldReady() return worldBooted and worldScope ~= nil and not worldScope.disposed end
function Kernel.isModDataReady() return modDataBooted end
function Kernel.getSimTime() return simTime end
function Kernel.getFrameTick() return frameCounter end

Kernel.STAGES = STAGES
EmberRoutes.Kernel = Kernel
EmberRoutes.registerCompat = Kernel.registerCompat
EmberRoutes.registerAddon = Kernel.registerAddon
return Kernel
