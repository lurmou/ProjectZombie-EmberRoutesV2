EmberRoutes = EmberRoutes or {}

local Kernel, Scope = {}, {}
local registry, registryIdx, flagGuards, rootCtx = {}, {}, {}, nil

local function noop() end
local function isTableEmpty(t)
    for _ in pairs(t) do return false end
    return true
end
local function safeCall(fn, ...)
    local ok, err = pcall(fn, ...)
    if not ok then print("[EmberRoutes.Kernel] cleanup error: " .. tostring(err)) end
    return ok, err
end

local function createScope(label, services)
    return setmetatable({ label = label or "scope", disposed = false, disposing = false, cleanups = {}, _services = services }, {
        __index = function(_, k)
            local m = Scope[k]
            if m ~= nil then return m end
            return services and services[k] or nil
        end,
    })
end

function Scope:isDisposed() return self.disposed == true end

function Scope:provide(name, service)
    assert(type(name) == "string" and name ~= "" and service ~= nil and Scope[name] == nil, "invalid provide: " .. tostring(name))
    local services = assert(self._services, "provide requires an active Kernel session")
    if services[name] ~= nil then error("[EmberRoutes.Kernel] Duplicate service: " .. name) end
    services[name] = service; return service
end

function Scope:defer(fn)
    assert(type(fn) == "function", "scope:defer requires a function")
    if self.disposed then safeCall(fn); return noop end
    local list, entry = self.cleanups, { fn = fn, released = false }
    list[#list + 1] = entry
    return function()
        if entry.released then return end
        entry.released = true
        local clean = entry.fn; entry.fn = nil
        if not self.disposing then
            for i = #list, 1, -1 do if list[i] == entry then table.remove(list, i); break end end
        end
        if clean then safeCall(clean) end
    end
end

function Scope:effect(fn)
    assert(type(fn) == "function", "scope:effect requires a function")
    if self.disposed then return noop end
    local cleanup = fn()
    if type(cleanup) ~= "function" then return noop end
    if self.disposed then safeCall(cleanup); return noop end
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
    if self.disposed then local dead = createScope(label, self._services); dead.disposed = true; return dead end
    local child = createScope(label, self._services)
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
            if clean then safeCall(clean) end
        end
    end
    self.cleanups, self.disposing = {}, false
    local detach = self._detachParent
    if detach then self._detachParent = nil; detach() end
end

function Kernel.plugin(def)
    assert(type(def) == "table" and type(def.name) == "string" and def.name ~= "" and type(def.apply) == "function", "invalid plugin")
    local pos = registryIdx[def.name] or (#registry + 1)
    registry[pos], registryIdx[def.name] = def, pos
end

local function resolvePlugins()
    local active, providerOf, ordered, state = {}, {}, {}, {}
    for i = 1, #registry do
        local def, ok, res = registry[i], true, true
        if type(def.when) == "function" then ok, res = pcall(def.when) end
        if not ok then print("[EmberRoutes.Kernel] when() failed for '" .. def.name .. "': " .. tostring(res)) end
        if ok and res then
            active[#active + 1] = def
            if type(def.provides) == "table" then for j = 1, #def.provides do providerOf[def.provides[j]] = def end
            elseif type(def.provides) == "string" then providerOf[def.provides] = def end
        end
    end
    for i = 1, #active do if providerOf[active[i].name] == nil then providerOf[active[i].name] = active[i] end end
    local function visit(def)
        local st = state[def.name]
        if st == 2 then return true elseif st == 1 then error("[EmberRoutes.Kernel] Cyclic dependency at '" .. def.name .. "'") end
        state[def.name] = 1
        if type(def.inject) == "table" then
            for j = 1, #def.inject do
                local dep = def.inject[j]; local p = providerOf[dep]
                if not p or (p ~= def and not visit(p)) then
                    if def.isolated then state[def.name] = 2; return false end
                    error("[EmberRoutes.Kernel] Missing service '" .. dep .. "' for '" .. def.name .. "'")
                end
            end
        end
        if type(def.optional) == "table" then
            for j = 1, #def.optional do local p = providerOf[def.optional[j]]; if p and p ~= def then visit(p) end end
        end
        state[def.name] = 2; ordered[#ordered + 1] = def; return true
    end
    for i = 1, #active do visit(active[i]) end
    return ordered
end

function Kernel.boot()
    if rootCtx and not rootCtx.disposed then return rootCtx end
    local services = {}
    local session = createScope("root", services)
    rootCtx = session
    local ok, err = pcall(function()
        local ordered = resolvePlugins()
        for i = 1, #ordered do
            local def, missing = ordered[i], nil
            if type(def.inject) == "table" then
                for j = 1, #def.inject do if services[def.inject[j]] == nil then missing = def.inject[j]; break end end
            end
            if missing then
                if not def.isolated then error("[EmberRoutes.Kernel] Unprovided '" .. missing .. "' for '" .. def.name .. "'") end
            else
                local pCtx = session:scope("plugin:" .. def.name)
                local applied, applyErr = pcall(def.apply, pCtx)
                if not applied then
                    pCtx:dispose()
                    if def.isolated then print("[EmberRoutes.Kernel] Isolated plugin '" .. def.name .. "' error: " .. tostring(applyErr))
                    else error("[EmberRoutes.Kernel] Plugin '" .. def.name .. "' failed: " .. tostring(applyErr)) end
                end
            end
        end
    end)
    if not ok then Kernel.teardown(); error(err) end
    return session
end

function Kernel.teardown()
    local ctx = rootCtx; rootCtx = nil
    if ctx and not ctx.disposed then ctx:dispose() end
    if EmberRoutesJava and EmberRoutesJava.reset then EmberRoutesJava.reset() end
end

function Kernel.scope(label)
    if rootCtx and not rootCtx.disposed then return rootCtx:scope(label) end
    return createScope(label or "standalone", nil)
end
function Kernel.isBooted() return rootCtx ~= nil and not rootCtx.disposed end
function Kernel.getService(name)
    if rootCtx and not rootCtx.disposed and rootCtx._services then return rootCtx._services[name] end
    return nil
end

EmberRoutes.Kernel, EmberRoutes.plugin = Kernel, Kernel.plugin
return Kernel
