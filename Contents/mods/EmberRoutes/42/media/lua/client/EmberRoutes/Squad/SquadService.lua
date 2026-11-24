local Kernel = require "EmberRoutes/Kernel"
local NpcRuntime = require "EmberRoutes/Npc/NpcRuntime"
local NpcLocomotionPort = require "EmberRoutes/Npc/NpcLocomotionPort"

local SquadService = {}

local CONSTS = {
    MODDATA_KEY = "EmberRoutes.Squad",
    POLL_INTERVAL_SIM = 0.20,
    FORMATIONS = { "WEDGE", "COLUMN", "LINE", "RING" },
    SPACINGS = { "TIGHT", "STANDARD", "WIDE" },
    ROES = { "FREE_FIRE", "RETURN_FIRE", "HOLD_FIRE" },
}

SquadService.KEYS = {
    FMT = {
        WEDGE = "IGUI_ER_Squad_Fmt_WEDGE",
        COLUMN = "IGUI_ER_Squad_Fmt_COLUMN",
        LINE = "IGUI_ER_Squad_Fmt_LINE",
        RING = "IGUI_ER_Squad_Fmt_RING",
    },
    SP = {
        TIGHT = "IGUI_ER_Squad_Sp_TIGHT",
        STANDARD = "IGUI_ER_Squad_Sp_STANDARD",
        WIDE = "IGUI_ER_Squad_Sp_WIDE",
    },
    ROE = {
        FREE_FIRE = "IGUI_ER_Squad_Roe_FREE_FIRE",
        RETURN_FIRE = "IGUI_ER_Squad_Roe_RETURN_FIRE",
        HOLD_FIRE = "IGUI_ER_Squad_Roe_HOLD_FIRE",
    },
    ROE_SHORT = {
        FREE_FIRE = "IGUI_ER_Squad_RoeShort_FREE_FIRE",
        RETURN_FIRE = "IGUI_ER_Squad_RoeShort_RETURN_FIRE",
        HOLD_FIRE = "IGUI_ER_Squad_RoeShort_HOLD_FIRE",
    },
    ROLE_SHORT = {
        MELEE = "IGUI_ER_Squad_RoleShort_MELEE",
        RANGED = "IGUI_ER_Squad_RoleShort_RANGED",
        MEDIC = "IGUI_ER_Squad_RoleShort_MEDIC",
        SUPPORT = "IGUI_ER_Squad_RoleShort_SUPPORT",
    },
    WEP = {
        FIREARM = "IGUI_ER_Squad_Wep_FIREARM",
        MELEE = "IGUI_ER_Squad_Wep_MELEE",
        UNARMED = "IGUI_ER_Squad_Wep_UNARMED",
    },
    ACT = {
        RIDING = "IGUI_ER_Squad_Act_RIDING",
        BOARDING = "IGUI_ER_Squad_Act_BOARDING",
        EXITING = "IGUI_ER_Squad_Act_EXITING",
        HOLDING = "IGUI_ER_Squad_Act_HOLDING",
        FOLLOWING = "IGUI_ER_Squad_Act_FOLLOWING",
        WAITING = "IGUI_ER_Squad_Act_WAITING",
        WAIT_VEHICLE = "IGUI_ER_Squad_Act_WAIT_VEHICLE",
        OFFSCREEN = "IGUI_ER_Squad_Act_OFFSCREEN",
        COMBAT = "IGUI_ER_Squad_Act_COMBAT",
        RELOADING = "IGUI_ER_Squad_Act_RELOADING",
        RETREATING = "IGUI_ER_Squad_Act_RETREATING",
        FIRST_AID = "IGUI_ER_Squad_Act_FIRST_AID",
        CQB_SWEEP = "IGUI_ER_Squad_Act_CQB_SWEEP",
    },
}

local state = {
    store = nil,
    cachedConfig = {
        formation = "WEDGE",
        spacing = "STANDARD",
        roe = "FREE_FIRE",
        cqb = false,
        orderMode = "FOLLOW",
        autoVehicle = true,
        vehicleOverride = "AUTO",
        version = 1,
        anyInVehicle = false,
        hasHoldAnchor = false,
    },
    cachedMembers = {},
    portraitCache = {},
    subscribers = {},
    lastDigest = nil,
    pollAccumSim = 0.0,
    worldReady = false,
}

local function nextInCycle(list, cur)
    for i = 1, #list do
        if list[i] == cur then
            return list[(i % #list) + 1]
        end
    end
    return list[1]
end

local function ensureStore()
    if state.store then
        return state.store
    end
    local raw = nil
    if ModData and type(ModData.getOrCreate) == "function" then
        raw = ModData.getOrCreate(CONSTS.MODDATA_KEY)
    end
    raw = raw or {}
    raw.memberOrder = raw.memberOrder or {}
    raw.callsigns = raw.callsigns or {}
    raw.config = raw.config or {
        formation = "WEDGE",
        spacing = "STANDARD",
        roe = "FREE_FIRE",
        cqb = false,
        orderMode = "FOLLOW",
        autoVehicle = true,
    }
    raw.memberRoe = raw.memberRoe or {}
    raw.memberCqb = raw.memberCqb or {}
    raw.hud = raw.hud or {
        visible = true,
        collapsed = false,
        x = nil,
        y = nil,
    }
    if raw.hud.visible == nil then
        raw.hud.visible = true
    end
    if raw.hud.collapsed == nil then
        raw.hud.collapsed = false
    end
    state.store = raw
    return raw
end

local function notifySubscribers(reason)
    local snapshot = {}
    for i = 1, #state.subscribers do
        snapshot[i] = state.subscribers[i]
    end
    for i = 1, #snapshot do
        local fn = snapshot[i]
        if type(fn) == "function" then
            local ok, err = pcall(fn, reason or "update")
            if not ok then
                print("[EmberRoutes][SquadService] subscriber error: " .. tostring(err))
            end
        end
    end
end

local function pushStoredConfigToJava()
    if not EmberRoutesJava then
        return
    end
    local s = ensureStore()
    local cfg = s.config
    if EmberRoutesJava.setSquadFormation then
        EmberRoutesJava.setSquadFormation(cfg.formation or "WEDGE")
    end
    if EmberRoutesJava.setSquadSpacing then
        EmberRoutesJava.setSquadSpacing(cfg.spacing or "STANDARD")
    end
    if EmberRoutesJava.setSquadRoe then
        EmberRoutesJava.setSquadRoe(cfg.roe or "FREE_FIRE")
    end
    if EmberRoutesJava.setSquadCqb then
        EmberRoutesJava.setSquadCqb(cfg.cqb == true)
    end
    if EmberRoutesJava.setSquadAutoVehicle then
        EmberRoutesJava.setSquadAutoVehicle(cfg.autoVehicle ~= false)
    end
    if EmberRoutesJava.setSquadOrderMode then
        EmberRoutesJava.setSquadOrderMode(cfg.orderMode == "HOLD_POSITION" and "HOLD_POSITION" or "FOLLOW", 0)
    end
    if EmberRoutesJava.setSquadMemberRoe then
        for npcId, roeVal in pairs(s.memberRoe) do
            if type(npcId) == "string" and type(roeVal) == "string" then
                EmberRoutesJava.setSquadMemberRoe(npcId, roeVal)
            end
        end
    end
    if EmberRoutesJava.setSquadMemberCqb then
        for npcId, cqbVal in pairs(s.memberCqb) do
            if type(npcId) == "string" and cqbVal ~= nil then
                EmberRoutesJava.setSquadMemberCqb(npcId, cqbVal == true)
            end
        end
    end
end

local function pullJavaConfig()
    local s = ensureStore()
    local stored = s.config
    if EmberRoutesJava and EmberRoutesJava.getSquadTacticalConfig then
        local jCfg = EmberRoutesJava.getSquadTacticalConfig()
        if jCfg then
            state.cachedConfig = {
                formation = jCfg.formation or stored.formation or "WEDGE",
                spacing = jCfg.spacing or stored.spacing or "STANDARD",
                roe = jCfg.roe or stored.roe or "FREE_FIRE",
                cqb = jCfg.cqb == true,
                orderMode = jCfg.orderMode or stored.orderMode or "FOLLOW",
                autoVehicle = jCfg.autoVehicle ~= false,
                vehicleOverride = jCfg.vehicleOverride or "AUTO",
                version = jCfg.version or 1,
                anyInVehicle = jCfg.anyInVehicle == true,
                hasHoldAnchor = jCfg.hasHoldAnchor == true,
            }
            return state.cachedConfig
        end
    end
    state.cachedConfig = {
        formation = stored.formation or "WEDGE",
        spacing = stored.spacing or "STANDARD",
        roe = stored.roe or "FREE_FIRE",
        cqb = stored.cqb == true,
        orderMode = stored.orderMode or "FOLLOW",
        autoVehicle = stored.autoVehicle ~= false,
        vehicleOverride = "AUTO",
        version = 1,
        anyInVehicle = false,
        hasHoldAnchor = stored.orderMode == "HOLD_POSITION",
    }
    return state.cachedConfig
end

local function reconcileMembers()
    local s = ensureStore()
    local raw = (EmberRoutesJava and EmberRoutesJava.listSquadMembers) and EmberRoutesJava.listSquadMembers() or nil
    local byId = {}
    local rawOrder = {}
    if raw then
        for i = 1, #raw do
            local m = raw[i]
            if m and m.npcId then
                byId[m.npcId] = m
                rawOrder[#rawOrder + 1] = m.npcId
            end
        end
    end

    local nextOrder = {}
    local seen = {}
    for i = 1, #s.memberOrder do
        local id = s.memberOrder[i]
        if id and byId[id] and not seen[id] then
            nextOrder[#nextOrder + 1] = id
            seen[id] = true
        end
    end
    for i = 1, #rawOrder do
        local id = rawOrder[i]
        if id and not seen[id] then
            nextOrder[#nextOrder + 1] = id
            seen[id] = true
        end
    end
    s.memberOrder = nextOrder

    local out = {}
    for i = 1, #nextOrder do
        local id = nextOrder[i]
        local src = byId[id]
        local callsign = s.callsigns[id]
        local hasCallsign = type(callsign) == "string" and callsign ~= ""
        local baseName = src.displayName or NpcRuntime.getDisplayName(id) or id
        local tex = SquadService.getPortraitTexture(id)
        out[i] = {
            npcId = id,
            displayName = baseName,
            callsign = hasCallsign and callsign or "",
            shownName = hasCallsign and callsign or baseName,
            phasedIn = src.phasedIn == true,
            health = src.health or 100.0,
            bleeding = src.bleeding == true,
            injured = src.injured == true,
            tacticalRole = src.tacticalRole or "SUPPORT",
            weaponType = src.weaponType or "UNARMED",
            weaponName = src.weaponName or "",
            effectiveRoe = src.effectiveRoe or "FREE_FIRE",
            roeOverridden = src.roeOverridden == true,
            effectiveCqb = src.effectiveCqb == true,
            cqbOverridden = src.cqbOverridden == true,
            activityState = src.activityState or "WAITING",
            inVehicle = src.inVehicle == true,
            vehicleSeat = src.vehicleSeat or -1,
            pendingDismiss = src.pendingDismiss == true,
            x = src.x or 0,
            y = src.y or 0,
            z = src.z or 0,
            portraitTex = tex,
        }
    end
    state.cachedMembers = out
    return out
end

local function computeDigest(cfg, members, hud)
    local parts = {
        tostring(cfg.version),
        tostring(cfg.anyInVehicle),
        tostring(cfg.vehicleOverride),
        tostring(cfg.orderMode),
        tostring(hud.visible),
        tostring(hud.collapsed),
        tostring(#members),
    }
    for i = 1, #members do
        local m = members[i]
        local hpBucket = math.floor((m.health or 100) + 0.5)
        parts[#parts + 1] = table.concat({
            m.npcId,
            m.shownName,
            tostring(m.phasedIn),
            tostring(hpBucket),
            tostring(m.bleeding),
            tostring(m.injured),
            m.tacticalRole,
            m.weaponType,
            m.weaponName,
            m.effectiveRoe,
            tostring(m.roeOverridden),
            tostring(m.effectiveCqb),
            tostring(m.cqbOverridden),
            m.activityState,
            tostring(m.vehicleSeat),
            tostring(m.pendingDismiss),
            m.portraitTex ~= nil and "1" or "0",
        }, ":")
    end
    return table.concat(parts, "|")
end

function SquadService.refreshNow(forceNotify, reason)
    local cfg = pullJavaConfig()
    local members = reconcileMembers()
    local hud = ensureStore().hud
    local digest = computeDigest(cfg, members, hud)
    if forceNotify or digest ~= state.lastDigest then
        state.lastDigest = digest
        notifySubscribers(reason or "sync")
    end
end

local function pollTick(_frameTick, dtSim, _simTime)
    if not state.worldReady then
        return
    end
    state.pollAccumSim = state.pollAccumSim + (dtSim or 0.016)
    if state.pollAccumSim < CONSTS.POLL_INTERVAL_SIM then
        return
    end
    state.pollAccumSim = 0.0
    SquadService.refreshNow(false, "poll")
end

function SquadService.getPortraitTexture(npcId)
    if not npcId or npcId == "" then
        return nil
    end
    if EmberRoutesJava and EmberRoutesJava.getNpcPortraitTexture then
        local tex = EmberRoutesJava.getNpcPortraitTexture(npcId)
        if tex then
            state.portraitCache[npcId] = tex
            return tex
        end
    end
    return state.portraitCache[npcId]
end

function SquadService.getConfig()
    return state.cachedConfig
end

function SquadService.getMembers()
    return state.cachedMembers
end

function SquadService.getHudState()
    return ensureStore().hud
end

function SquadService.getCallsign(npcId)
    local s = ensureStore()
    return s.callsigns[npcId]
end

function SquadService.getDisplayName(memberOrId)
    if type(memberOrId) == "table" then
        return memberOrId.shownName or memberOrId.displayName or memberOrId.npcId
    end
    local cs = SquadService.getCallsign(memberOrId)
    if type(cs) == "string" and cs ~= "" then
        return cs
    end
    return NpcRuntime.getDisplayName(memberOrId)
end

function SquadService.isMemberInSquad(npcId)
    if not npcId then
        return false
    end
    for i = 1, #state.cachedMembers do
        if state.cachedMembers[i].npcId == npcId then
            return true
        end
    end
    return EmberRoutesJava and EmberRoutesJava.getNpcBrainMode and EmberRoutesJava.getNpcBrainMode(npcId) == "SQUAD" or false
end

function SquadService.setHudState(patch, notifyReason)
    if type(patch) ~= "table" then
        return
    end
    local hud = ensureStore().hud
    if patch.visible ~= nil then hud.visible = patch.visible == true end
    if patch.collapsed ~= nil then hud.collapsed = patch.collapsed == true end
    if patch.x ~= nil then hud.x = patch.x end
    if patch.y ~= nil then hud.y = patch.y end
    if notifyReason ~= false then
        SquadService.refreshNow(true, notifyReason or "hud")
    end
end

function SquadService.setFormation(formation)
    local s = ensureStore()
    s.config.formation = formation
    if EmberRoutesJava and EmberRoutesJava.setSquadFormation then
        EmberRoutesJava.setSquadFormation(formation)
    end
    SquadService.refreshNow(true, "formation")
end

function SquadService.cycleFormation()
    local cur = SquadService.getConfig().formation or "WEDGE"
    SquadService.setFormation(nextInCycle(CONSTS.FORMATIONS, cur))
end

function SquadService.setSpacing(spacing)
    local s = ensureStore()
    s.config.spacing = spacing
    if EmberRoutesJava and EmberRoutesJava.setSquadSpacing then
        EmberRoutesJava.setSquadSpacing(spacing)
    end
    SquadService.refreshNow(true, "spacing")
end

function SquadService.cycleSpacing()
    local cur = SquadService.getConfig().spacing or "STANDARD"
    SquadService.setSpacing(nextInCycle(CONSTS.SPACINGS, cur))
end

function SquadService.setRoe(roe)
    local s = ensureStore()
    s.config.roe = roe
    if EmberRoutesJava and EmberRoutesJava.setSquadRoe then
        EmberRoutesJava.setSquadRoe(roe)
    end
    SquadService.refreshNow(true, "roe")
end

function SquadService.cycleRoe()
    local cur = SquadService.getConfig().roe or "FREE_FIRE"
    SquadService.setRoe(nextInCycle(CONSTS.ROES, cur))
end

function SquadService.setCqb(enabled)
    local s = ensureStore()
    s.config.cqb = enabled == true
    if EmberRoutesJava and EmberRoutesJava.setSquadCqb then
        EmberRoutesJava.setSquadCqb(enabled == true)
    end
    SquadService.refreshNow(true, "cqb")
end

function SquadService.toggleCqb()
    SquadService.setCqb(not (SquadService.getConfig().cqb == true))
end

function SquadService.setOrderMode(mode, playerIndex)
    local s = ensureStore()
    s.config.orderMode = mode
    if EmberRoutesJava and EmberRoutesJava.setSquadOrderMode then
        EmberRoutesJava.setSquadOrderMode(mode, playerIndex or 0)
    end
    SquadService.refreshNow(true, "orderMode")
end

function SquadService.toggleOrderMode(playerIndex)
    local cur = SquadService.getConfig().orderMode or "FOLLOW"
    SquadService.setOrderMode(cur == "FOLLOW" and "HOLD_POSITION" or "FOLLOW", playerIndex or 0)
end

function SquadService.setAutoVehicle(enabled)
    local s = ensureStore()
    s.config.autoVehicle = enabled == true
    if EmberRoutesJava and EmberRoutesJava.setSquadAutoVehicle then
        EmberRoutesJava.setSquadAutoVehicle(enabled == true)
    end
    SquadService.refreshNow(true, "autoVehicle")
end

function SquadService.toggleAutoVehicle()
    SquadService.setAutoVehicle(not (SquadService.getConfig().autoVehicle ~= false))
end

function SquadService.setMemberRoe(npcId, roeOrNil)
    if not npcId or npcId == "" then
        return false
    end
    local s = ensureStore()
    local normalized = (type(roeOrNil) == "string" and roeOrNil ~= "" and roeOrNil ~= "INHERIT") and roeOrNil or nil
    s.memberRoe[npcId] = normalized
    if EmberRoutesJava and EmberRoutesJava.setSquadMemberRoe then
        EmberRoutesJava.setSquadMemberRoe(npcId, normalized)
    end
    SquadService.refreshNow(true, "memberRoe")
    return true
end

function SquadService.setMemberCqb(npcId, cqbOrNil)
    if not npcId or npcId == "" then
        return false
    end
    local s = ensureStore()
    if cqbOrNil == nil then
        s.memberCqb[npcId] = nil
    else
        s.memberCqb[npcId] = cqbOrNil == true
    end
    if EmberRoutesJava and EmberRoutesJava.setSquadMemberCqb then
        EmberRoutesJava.setSquadMemberCqb(npcId, cqbOrNil)
    end
    SquadService.refreshNow(true, "memberCqb")
    return true
end

function SquadService.setCallsign(npcId, text)
    if not npcId or npcId == "" then
        return
    end
    local s = ensureStore()
    local trimmed = type(text) == "string" and text:gsub("^%s+", ""):gsub("%s+$", "") or ""
    if trimmed == "" then
        s.callsigns[npcId] = nil
    else
        s.callsigns[npcId] = trimmed
    end
    SquadService.refreshNow(true, "callsign")
end

function SquadService.reorderMember(fromIdx, toIdx)
    local s = ensureStore()
    local order = s.memberOrder
    if fromIdx < 1 or fromIdx > #order or toIdx < 1 or toIdx > #order or fromIdx == toIdx then
        return false
    end
    local moved = table.remove(order, fromIdx)
    table.insert(order, toIdx, moved)
    SquadService.refreshNow(true, "reorder")
    return true
end

function SquadService.commandVehicleToggle(playerIndex)
    if not EmberRoutesJava or not EmberRoutesJava.commandSquadVehicleToggle then
        return "NO_VEHICLE"
    end
    local res = EmberRoutesJava.commandSquadVehicleToggle(playerIndex or 0)
    SquadService.refreshNow(true, "vehicleToggle")
    return res
end

function SquadService.commandMemberExitVehicle(npcId)
    if not EmberRoutesJava or not EmberRoutesJava.commandMemberExitVehicle or not npcId then
        return false
    end
    local ok = EmberRoutesJava.commandMemberExitVehicle(npcId) == true
    SquadService.refreshNow(true, "memberExit")
    return ok
end

function SquadService.addMember(npcId, playerIndex)
    if not npcId or npcId == "" then
        return false
    end
    local ok = NpcLocomotionPort.follow(npcId, { playerIndex = playerIndex or 0, slot = -1 })
    if ok then
        local hud = ensureStore().hud
        hud.visible = true
        SquadService.refreshNow(true, "addMember")
    end
    return ok
end

function SquadService.dismissMember(npcId)
    if not npcId or npcId == "" then
        return "MISSING"
    end
    local res = "MISSING"
    if EmberRoutesJava and EmberRoutesJava.dismissSquadMember then
        res = EmberRoutesJava.dismissSquadMember(npcId) or "MISSING"
    else
        if NpcLocomotionPort.stopFollow(npcId) then
            res = "DISMISSED"
        end
    end
    if res == "DISMISSED" then
        local s = ensureStore()
        s.callsigns[npcId] = nil
        s.memberRoe[npcId] = nil
        s.memberCqb[npcId] = nil
        if EmberRoutesJava and EmberRoutesJava.setSquadMemberRoe then
            EmberRoutesJava.setSquadMemberRoe(npcId, nil)
        end
        if EmberRoutesJava and EmberRoutesJava.setSquadMemberCqb then
            EmberRoutesJava.setSquadMemberCqb(npcId, nil)
        end
    end
    SquadService.refreshNow(true, "dismiss")
    return res
end

function SquadService.subscribe(fn)
    assert(type(fn) == "function", "SquadService.subscribe requires a function")
    state.subscribers[#state.subscribers + 1] = fn
    local active = true
    return function()
        if not active then
            return
        end
        active = false
        for i = #state.subscribers, 1, -1 do
            if state.subscribers[i] == fn then
                table.remove(state.subscribers, i)
                break
            end
        end
    end
end

Kernel.exposeApi("squadService", SquadService)

Kernel.registerModule({
    id = "squad-service",
    onInitModData = function()
        state.store = nil
        ensureStore()
    end,
    onWorldReady = function(scope)
        state.worldReady = true
        state.pollAccumSim = 0.0
        state.lastDigest = nil
        state.portraitCache = {}
        pushStoredConfigToJava()
        SquadService.refreshNow(false, "worldReady")
        scope:defer(function()
            state.worldReady = false
            state.cachedMembers = {}
            state.portraitCache = {}
            state.lastDigest = nil
        end)
    end,
    onTeardown = function()
        state.worldReady = false
        state.store = nil
        state.cachedMembers = {}
        state.portraitCache = {}
        state.subscribers = {}
        state.lastDigest = nil
    end,
    tick = { stage = "MACRO_WORLD", fn = pollTick },
})

return SquadService
