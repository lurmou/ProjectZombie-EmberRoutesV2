local EmberUI = require "EmberUI"
local UI = EmberUI.UI
local Theme = EmberUI.Theme
local Clock = EmberUI.Core and EmberUI.Core.Clock

local Kernel = require "EmberRoutes/Kernel"
local SettlementStore = require "EmberRoutes/Settlement/SettlementStore"
local NpcRuntime = require "EmberRoutes/Npc/NpcRuntime"
local NpcLocomotionPort = require "EmberRoutes/Npc/NpcLocomotionPort"
local CategoryCatalog = require "EmberRoutes/UI/CategoryCatalog"
local SettlementControlPanel = require "EmberRoutes/UI/SettlementControlPanel"
local ZoneOverlay = require "EmberRoutes/UI/ZoneOverlay"
local NpcAnimDebugBridge = require "EmberRoutes/UI/NpcAnimDebugBridge"

local DebugPanel = {}
local instance = nil

local LIVE_POLL_INTERVAL_MS = 500
local COMBAT_ROW_COUNT = 6

local function nowMs()
    if Clock and type(Clock.nowMs) == "function" then
        return Clock.nowMs()
    end
    if EmberRoutesJava and EmberRoutesJava.diagnosticTimeNanos then
        return EmberRoutesJava.diagnosticTimeNanos() / 1000000.0
    end
    return 0
end

local function makeLayoutTabHost(w, h)
    local host = EmberUI.LayoutHost:new(0, 0, w, h, { direction = "column" })
    host:initialise()
    host:instantiate()
    return host
end

local PanelCtrl = {}
PanelCtrl.__index = PanelCtrl

function PanelCtrl:setFeedback(msg)
    if self.lblFeedback then
        self.lblFeedback:setText(tostring(msg or ""))
    end
end

function PanelCtrl:selectTab(tabId)
    if self.tabBar and self.tabBar.setSelected then
        self.tabBar:setSelected(tabId, false)
    end
end

function PanelCtrl:buildCombatTab(w, h)
    local host = makeLayoutTabHost(w, h)

    self.lblCombatDiagTitle = UI.Label(getText("IGUI_ER_Debug_SecCombatDiag"), { h = 16, font = UIFont.Small, color = Theme.accent, truncate = true })
    self.btnDiagToggle = UI.Button(getText("IGUI_ER_Debug_BtnDiagOff"), self, function(target)
        if EmberRoutesJava and EmberRoutesJava.setCombatDiagEnabled then
            local cur = EmberRoutesJava.isCombatDiagEnabled and (EmberRoutesJava.isCombatDiagEnabled() == true) or false
            local nextVal = not cur
            EmberRoutesJava.setCombatDiagEnabled(nextVal)
            target:setFeedback(getText("IGUI_ER_Debug_FeedbackDiagFmt", tostring(nextVal)))
            target:refreshCombatTab(true)
        end
    end, { h = 22 })
    self.btnAllAnimRecToggle = UI.Button(getText("IGUI_ER_Debug_BtnAllAnimRecOff"), self, function(target)
        if NpcRuntime.setAllAnimRecorderEnabled then
            local cur = NpcRuntime.isAllAnimRecorderEnabled and (NpcRuntime.isAllAnimRecorderEnabled() == true) or false
            local res = NpcRuntime.setAllAnimRecorderEnabled(not cur)
            target:setFeedback(getText("IGUI_ER_Debug_FeedbackAllAnimRecFmt", tostring(res)))
            target:refreshCombatTab(true)
        end
    end, { h = 22 })
    self.btnDumpSnap = UI.Button(getText("IGUI_ER_Debug_BtnDumpSnapshot"), self, function(target)
        if EmberRoutesJava and EmberRoutesJava.dumpCombatDebugSnapshot then
            local summary = EmberRoutesJava.dumpCombatDebugSnapshot()
            target:setFeedback(getText("IGUI_ER_Debug_FeedbackDumpFmt", tostring(summary)))
            target:refreshCombatTab(true)
        end
    end, { h = 22 })

    self.lblCombatSummary = UI.Label("", { h = 18, color = Theme.warning, truncate = true })
    self.combatRows = {}
    local rowItems = {}
    for i = 1, COMBAT_ROW_COUNT do
        local lbl = UI.Label("", { h = 18, font = UIFont.Small, color = Theme.textColor, truncate = true })
        self.combatRows[i] = lbl
        rowItems[#rowItems + 1] = UI.Item(lbl, { basis = 18, grow = 0 })
    end

    local children = {
        UI.Item(self.lblCombatDiagTitle, { basis = 18, grow = 0 }),
        UI.Item(UI.Row({ gap = 8 }, {
            UI.Item(self.btnDiagToggle, { basis = 0, grow = 1 }),
            UI.Item(self.btnAllAnimRecToggle, { basis = 0, grow = 1 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(UI.Row({ gap = 8 }, {
            UI.Item(self.btnDumpSnap, { basis = 0, grow = 1 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(self.lblCombatSummary, { basis = 18, grow = 0 }),
    }
    for i = 1, #rowItems do
        children[#children + 1] = rowItems[i]
    end
    children[#children + 1] = UI.Spacer(1)

    host:build(UI.Column({ padding = 4, gap = 6 }, children))
    return host
end

function PanelCtrl:refreshCombatTab(force)
    local snap = self.previewCombatSnap
    if not snap and EmberRoutesJava and EmberRoutesJava.getCombatDebugSnapshot then
        snap = EmberRoutesJava.getCombatDebugSnapshot()
    end
    if not snap then return end

    local diagOn = snap.combatDiagEnabled == true
    local animRecOn
    if snap.allAnimRecEnabled ~= nil then
        animRecOn = snap.allAnimRecEnabled == true
    elseif NpcRuntime.isAllAnimRecorderEnabled then
        animRecOn = NpcRuntime.isAllAnimRecorderEnabled() == true
    else
        animRecOn = false
    end
    local sig = tostring(diagOn) .. "|" .. tostring(animRecOn)

    if force or self.lastCombatCfgSig ~= sig then
        self.lastCombatCfgSig = sig
        self.btnDiagToggle:setTitle(diagOn and getText("IGUI_ER_Debug_BtnDiagOn") or getText("IGUI_ER_Debug_BtnDiagOff"))
        Theme.button(self.btnDiagToggle, diagOn, diagOn)

        self.btnAllAnimRecToggle:setTitle(animRecOn and getText("IGUI_ER_Debug_BtnAllAnimRecOn") or getText("IGUI_ER_Debug_BtnAllAnimRecOff"))
        Theme.button(self.btnAllAnimRecToggle, animRecOn, animRecOn)
    end

    self.lblCombatSummary:setText(getText(
        "IGUI_ER_Debug_CombatSummaryFmt",
        tostring(math.floor(snap.threatCount or 0)),
        tostring(math.floor(snap.clusterCount or 0)),
        tostring(math.floor(snap.corridorCount or 0))
    ))

    local npcs = snap.npcs or {}
    for i = 1, COMBAT_ROW_COUNT do
        local lbl = self.combatRows[i]
        local r = npcs[i]
        if r then
            lbl:setText(string.format(
                "%s | %s | tgt=%d(%s) d=%.2f [%.2f..%.2f] rad=%s flr=%s stp=%s",
                tostring(r.npcId),
                tostring(r.state or "IDLE"),
                math.floor(r.targetId or -1),
                tostring(r.targetPosture or "NONE"),
                tonumber(r.dist or 0),
                tonumber(r.minRange or 0),
                tonumber(r.maxRange or 0),
                tostring(r.radialMode or "HOLD"),
                tostring(r.aimFloor == true),
                tostring(r.stomp == true)
            ))
        elseif i == 1 then
            lbl:setText(getText("IGUI_ER_Debug_NoCombatRows"))
        else
            lbl:setText("")
        end
    end
end

function PanelCtrl:selectedNpcId()
    local ids = NpcRuntime.listIds()
    if not ids or #ids == 0 then
        self.selectedNpcIdx = 1
        return nil, 0
    end
    if self.selectedNpcIdx < 1 or self.selectedNpcIdx > #ids then
        self.selectedNpcIdx = 1
    end
    return ids[self.selectedNpcIdx], #ids
end

function PanelCtrl:buildNpcTab(w, h)
    local host = makeLayoutTabHost(w, h)

    self.btnNpcPrev = UI.Button("<", self, function(target)
        local ids = NpcRuntime.listIds()
        if ids and #ids > 0 then
            target.selectedNpcIdx = ((target.selectedNpcIdx - 2) % #ids) + 1
            target:refreshNpcTab()
        end
    end, { w = 28, h = 24 })
    self.lblNpcSelected = UI.Label("", { h = 24, font = UIFont.Small, color = Theme.warning, truncate = true })
    self.btnNpcNext = UI.Button(">", self, function(target)
        local ids = NpcRuntime.listIds()
        if ids and #ids > 0 then
            target.selectedNpcIdx = (target.selectedNpcIdx % #ids) + 1
            target:refreshNpcTab()
        end
    end, { w = 28, h = 24 })
    self.btnNpcRefresh = UI.Button(getText("IGUI_ER_Debug_BtnRefresh"), self, function(target)
        target:refreshNpcTab()
    end, { h = 24 })

    local function spawnNearPlayer(count)
        local actor = getSpecificPlayer and getSpecificPlayer(0) or nil
        if not actor then return end
        local bx = actor:getX() + 1.5
        local by = actor:getY() + 1.5
        local bz = math.floor(actor:getZ())
        local lastId = nil
        for i = 1, count do
            local dx = count == 1 and 0 or (((i - 1) % 5 - 2) * 1.4)
            local dy = count == 1 and 0 or ((math.floor((i - 1) / 5) - 0.5) * 1.4)
            local id = NpcRuntime.spawn(bx + dx, by + dy, bz, (i % 2 == 0))
            if id then
                NpcRuntime.giveTestKit(id)
                lastId = id
            end
        end
        self:setFeedback(getText("IGUI_ER_Debug_FeedbackSpawnFmt", tostring(count), tostring(lastId or "ERR")))
        self:refreshNpcTab()
    end

    self.lblNpcSecLife = UI.Label(getText("IGUI_ER_Debug_SecNpcLife"), { h = 18, color = Theme.accent, truncate = true })
    self.btnSpawn1 = UI.Button(getText("IGUI_ER_Debug_BtnSpawn1"), self, function() spawnNearPlayer(1) end, { h = 24, primary = true })
    self.btnSpawn10 = UI.Button(getText("IGUI_ER_Debug_BtnSpawn10"), self, function() spawnNearPlayer(10) end, { h = 24 })
    self.btnStopSel = UI.Button(getText("IGUI_ER_Debug_BtnStopSel"), self, function(target)
        local id = target:selectedNpcId()
        if id then
            NpcLocomotionPort.stop(id)
            target:setFeedback("STOP " .. tostring(id))
        end
    end, { h = 24 })
    self.btnPhaseToggle = UI.Button(getText("IGUI_ER_Debug_BtnPhaseOut"), self, function(target)
        local id = target:selectedNpcId()
        if not id then return end
        local info = NpcRuntime.getInfo(id)
        local ok = (info and info.phasedIn) and NpcRuntime.phaseOut(id) or NpcRuntime.phaseIn(id)
        target:setFeedback("Phase toggle " .. tostring(id) .. " -> " .. tostring(ok))
        target:refreshNpcTab()
    end, { h = 24 })
    self.btnKillSel = UI.Button(getText("IGUI_ER_Debug_BtnKillSel"), self, function(target)
        local id = target:selectedNpcId()
        if id then
            local ok = NpcRuntime.kill(id)
            target:setFeedback("Kill " .. tostring(id) .. " -> " .. tostring(ok))
            target:refreshNpcTab()
        end
    end, { h = 24 })
    self.btnDespawnAll = UI.Button(getText("IGUI_ER_Debug_BtnDespawnAll"), self, function(target)
        local n = NpcRuntime.despawnAll()
        target:setFeedback("Despawned " .. tostring(n))
        target:refreshNpcTab()
    end, { h = 24 })

    self.lblNpcSecSquad = UI.Label(getText("IGUI_ER_Debug_SecNpcBrain"), { h = 18, color = Theme.accent, truncate = true })
    self.btnFollowAll = UI.Button(getText("IGUI_ER_Debug_BtnFollowAll"), self, function(target)
        local ids = NpcRuntime.listIds()
        for i = 1, #ids do NpcLocomotionPort.follow(ids[i], { playerIndex = 0 }) end
        target:setFeedback("FollowAll n=" .. tostring(#ids))
        target:refreshNpcTab()
    end, { h = 24 })
    self.btnStopFollowAll = UI.Button(getText("IGUI_ER_Debug_BtnStopFollowAll"), self, function(target)
        local ids = NpcRuntime.listIds()
        for i = 1, #ids do NpcLocomotionPort.stopFollow(ids[i]) end
        target:setFeedback("StopFollowAll n=" .. tostring(#ids))
        target:refreshNpcTab()
    end, { h = 24 })
    self.btnStandUpAll = UI.Button(getText("IGUI_ER_Debug_BtnStandUpAll"), self, function(target)
        local n = NpcRuntime.standUpAll(false)
        target:setFeedback("StandUpAll n=" .. tostring(n))
    end, { h = 24 })
    self.btnBrainSquad = UI.Button(getText("IGUI_ER_Debug_BtnBrainSquad"), self, function(target)
        local id = target:selectedNpcId()
        if id then
            local ok = NpcRuntime.setBrainMode(id, "SQUAD")
            target:setFeedback(tostring(id) .. " -> SQUAD: " .. tostring(ok))
            target:refreshNpcTab()
        end
    end, { h = 24 })
    self.btnBrainSettle = UI.Button(getText("IGUI_ER_Debug_BtnBrainSettle"), self, function(target)
        local id = target:selectedNpcId()
        if id then
            local ok = NpcRuntime.setBrainMode(id, "SETTLEMENT")
            target:setFeedback(tostring(id) .. " -> SETTLEMENT: " .. tostring(ok))
            target:refreshNpcTab()
        end
    end, { h = 24 })
    self.btnAssignSettle = UI.Button(getText("IGUI_ER_Debug_BtnAssignHome"), self, function(target)
        local id = target:selectedNpcId()
        local sList = SettlementStore.list()
        if not id or not sList or #sList == 0 then
            target:setFeedback(getText("IGUI_ER_Debug_NoSettlement"))
            return
        end
        local actor = getSpecificPlayer and getSpecificPlayer(0) or nil
        local st = actor and SettlementStore.findSettlementAt(math.floor(actor:getX()), math.floor(actor:getY())) or sList[1]
        local ok = NpcRuntime.assignToSettlement(id, st.id)
        target:setFeedback(tostring(id) .. " -> " .. tostring(st.name) .. ": " .. tostring(ok))
        target:refreshNpcTab()
    end, { h = 24 })
    self.btnUnassignSettle = UI.Button(getText("IGUI_ER_Debug_BtnUnassignHome"), self, function(target)
        local id = target:selectedNpcId()
        if id then
            NpcRuntime.assignToSettlement(id, nil)
            target:setFeedback("Unassigned " .. tostring(id))
            target:refreshNpcTab()
        end
    end, { h = 24 })

    local function applyNeed(needKind)
        local id = self:selectedNpcId()
        if not id then return end
        local res = NpcRuntime.debugApplyNeed(id, needKind)
        self:setFeedback(tostring(id) .. " " .. needKind .. " -> " .. tostring(res))
    end

    self.lblNpcSecNeeds = UI.Label(getText("IGUI_ER_Debug_SecNpcNeeds"), { h = 18, color = Theme.accent, truncate = true })
    self.btnNeedKit = UI.Button(getText("IGUI_ER_Debug_NeedKit"), self, function() applyNeed("SURVIVAL_KIT") end, { h = 24 })
    self.btnNeedHunger = UI.Button(getText("IGUI_ER_Debug_NeedHunger"), self, function() applyNeed("HUNGER") end, { h = 24 })
    self.btnNeedThirst = UI.Button(getText("IGUI_ER_Debug_NeedThirst"), self, function() applyNeed("THIRST") end, { h = 24 })
    self.btnNeedFatigue = UI.Button(getText("IGUI_ER_Debug_NeedFatigue"), self, function() applyNeed("FATIGUE") end, { h = 24 })
    self.btnNeedBleed = UI.Button(getText("IGUI_ER_Debug_NeedBleed"), self, function() applyNeed("BLEEDING") end, { h = 24 })
    self.btnNeedRestore = UI.Button(getText("IGUI_ER_Debug_NeedRestore"), self, function() applyNeed("RESTORE") end, { h = 24 })

    self.lblNpcSecTools = UI.Label(getText("IGUI_ER_Debug_SecNpcTools"), { h = 18, color = Theme.accent, truncate = true })
    self.btnInspectOne = UI.Button(getText("IGUI_ER_Debug_BtnInspectOne"), self, function(target)
        local id = target:selectedNpcId()
        if id then
            local info = NpcRuntime.getInfo(id)
            local inv = NpcRuntime.inspectInventoryIds(id)
            print("[EmberRoutes][Debug] Inspect " .. tostring(inv))
            if info then
                print("[EmberRoutes][Debug] status=" .. tostring(info.moveStatus)
                    .. " debug=" .. tostring(info.debugLine))
            end
            target:setFeedback("Inspected " .. tostring(id) .. " (see console.txt)")
        end
    end, { h = 24 })
    self.btnInspectAll = UI.Button(getText("IGUI_ER_Debug_BtnInspectAll"), self, function(target)
        local ids = NpcRuntime.listIds()
        print("[EmberRoutes][Debug][INSPECT_ALL] count=" .. tostring(#ids))
        for i = 1, #ids do
            local id = ids[i]
            print("[EmberRoutes][Debug] " .. tostring(id) .. " " .. tostring(NpcRuntime.inspectInventoryIds(id)))
        end
        target:setFeedback("Inspected all n=" .. tostring(#ids) .. " (console.txt)")
    end, { h = 24 })
    self.btnSaveNow = UI.Button(getText("IGUI_ER_Debug_BtnSaveNow"), self, function(target)
        local ok = NpcRuntime.saveNow()
        target:setFeedback("SaveNow -> " .. tostring(ok))
    end, { h = 24 })
    self.btnAnimMon = UI.Button(getText("IGUI_ER_Debug_BtnAnimMonitor"), self, function(target)
        local id = target:selectedNpcId()
        if id then
            NpcAnimDebugBridge.openForNpc(id)
            target:setFeedback("AnimMonitor -> " .. tostring(id))
        end
    end, { h = 24 })
    self.btnAnimRec = UI.Button(getText("IGUI_ER_Debug_BtnAnimRecord"), self, function(target)
        local id = target:selectedNpcId()
        if id then
            local res = NpcRuntime.toggleAnimRecorder(id)
            target:setFeedback("AnimRec " .. tostring(id) .. " -> " .. tostring(res))
        end
    end, { h = 24 })

    host:build(UI.Column({ padding = 4, gap = 3 }, {
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(self.btnNpcPrev, { basis = 28, grow = 0 }),
            UI.Item(self.lblNpcSelected, { basis = 0, grow = 1 }),
            UI.Item(self.btnNpcNext, { basis = 28, grow = 0 }),
            UI.Item(self.btnNpcRefresh, { basis = 84, grow = 0 }),
        }), { basis = 22, grow = 0 }),

        UI.Item(self.lblNpcSecLife, { basis = 16, grow = 0 }),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(self.btnSpawn1, { basis = 0, grow = 1 }),
            UI.Item(self.btnSpawn10, { basis = 0, grow = 1 }),
            UI.Item(self.btnStopSel, { basis = 0, grow = 1 }),
        }), { basis = 22, grow = 0 }),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(self.btnPhaseToggle, { basis = 0, grow = 1 }),
            UI.Item(self.btnKillSel, { basis = 0, grow = 1 }),
            UI.Item(self.btnDespawnAll, { basis = 0, grow = 1 }),
        }), { basis = 22, grow = 0 }),

        UI.Item(self.lblNpcSecSquad, { basis = 16, grow = 0 }),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(self.btnFollowAll, { basis = 0, grow = 1 }),
            UI.Item(self.btnStopFollowAll, { basis = 0, grow = 1 }),
            UI.Item(self.btnStandUpAll, { basis = 0, grow = 1 }),
        }), { basis = 22, grow = 0 }),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(self.btnBrainSquad, { basis = 0, grow = 1 }),
            UI.Item(self.btnBrainSettle, { basis = 0, grow = 1 }),
            UI.Item(self.btnAssignSettle, { basis = 0, grow = 1 }),
            UI.Item(self.btnUnassignSettle, { basis = 0, grow = 1 }),
        }), { basis = 22, grow = 0 }),

        UI.Item(self.lblNpcSecNeeds, { basis = 16, grow = 0 }),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(self.btnNeedKit, { basis = 0, grow = 1 }),
            UI.Item(self.btnNeedHunger, { basis = 0, grow = 1 }),
            UI.Item(self.btnNeedThirst, { basis = 0, grow = 1 }),
        }), { basis = 22, grow = 0 }),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(self.btnNeedFatigue, { basis = 0, grow = 1 }),
            UI.Item(self.btnNeedBleed, { basis = 0, grow = 1 }),
            UI.Item(self.btnNeedRestore, { basis = 0, grow = 1 }),
        }), { basis = 22, grow = 0 }),

        UI.Item(self.lblNpcSecTools, { basis = 16, grow = 0 }),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(self.btnInspectOne, { basis = 0, grow = 1 }),
            UI.Item(self.btnInspectAll, { basis = 0, grow = 1 }),
            UI.Item(self.btnSaveNow, { basis = 0, grow = 1 }),
        }), { basis = 22, grow = 0 }),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(self.btnAnimMon, { basis = 0, grow = 1 }),
            UI.Item(self.btnAnimRec, { basis = 0, grow = 1 }),
        }), { basis = 22, grow = 0 }),
        UI.Spacer(1),
    }))
    return host
end

function PanelCtrl:refreshNpcTab()
    local id, count = self:selectedNpcId()
    local hasNpc = id ~= nil
    if hasNpc then
        local info = NpcRuntime.getInfo(id)
        local mode = NpcRuntime.getBrainMode(id)
        local posture = NpcRuntime.getPostureState(id)
        local name = NpcRuntime.getDisplayName(id, nil)
        self.lblNpcSelected:setText(string.format("[%d/%d] %s (%s) | %s | %s", self.selectedNpcIdx, count, tostring(name), tostring(id), tostring(mode), tostring(posture)))
        self.btnPhaseToggle:setTitle((info and info.phasedIn) and getText("IGUI_ER_Debug_BtnPhaseOut") or getText("IGUI_ER_Debug_BtnPhaseIn"))
    else
        self.lblNpcSelected:setText(getText("IGUI_ER_Debug_NoNpc"))
        self.btnPhaseToggle:setTitle(getText("IGUI_ER_Debug_BtnPhaseOut"))
    end
    local buttons = {
        self.btnStopSel, self.btnPhaseToggle, self.btnKillSel,
        self.btnBrainSquad, self.btnBrainSettle, self.btnAssignSettle, self.btnUnassignSettle,
        self.btnNeedKit, self.btnNeedHunger, self.btnNeedThirst, self.btnNeedFatigue,
        self.btnNeedBleed, self.btnNeedRestore, self.btnInspectOne, self.btnAnimMon, self.btnAnimRec,
    }
    for i = 1, #buttons do
        if buttons[i] then buttons[i]:setEnable(hasNpc) end
    end
end

function PanelCtrl:selectedSettlement()
    local list = SettlementStore.list()
    if not list or #list == 0 then
        self.selectedSettlementIdx = 1
        return nil, 0
    end
    if self.selectedSettlementIdx < 1 or self.selectedSettlementIdx > #list then
        self.selectedSettlementIdx = 1
    end
    return list[self.selectedSettlementIdx], #list
end

function PanelCtrl:buildLogisticsTab(w, h)
    local host = makeLayoutTabHost(w, h)

    self.btnSettlePrev = UI.Button("<", self, function(target)
        local list = SettlementStore.list()
        if list and #list > 0 then
            target.selectedSettlementIdx = ((target.selectedSettlementIdx - 2) % #list) + 1
            target:refreshLogisticsTab()
        end
    end, { w = 28, h = 24 })
    self.lblSettleSelected = UI.Label("", { h = 24, color = Theme.warning, truncate = true })
    self.btnSettleNext = UI.Button(">", self, function(target)
        local list = SettlementStore.list()
        if list and #list > 0 then
            target.selectedSettlementIdx = (target.selectedSettlementIdx % #list) + 1
            target:refreshLogisticsTab()
        end
    end, { w = 28, h = 24 })

    self.lblLogiSecActions = UI.Label(getText("IGUI_ER_Debug_SecLogistics"), { h = 18, color = Theme.accent, truncate = true })
    self.btnOpenPanel = UI.Button(getText("IGUI_ER_Debug_BtnOpenSettlePanel"), self, function(target)
        local s = target:selectedSettlement()
        SettlementControlPanel.open(s and s.id or nil)
    end, { h = 24, primary = true })
    self.btnToggleBorders = UI.Button(getText("IGUI_ER_Debug_BtnToggleBorders"), self, function(target)
        ZoneOverlay.toggleVisible()
        target:setFeedback("ZoneOverlay -> " .. tostring(ZoneOverlay.isVisible()))
    end, { h = 24 })
    self.btnPrintLogi = UI.Button(getText("IGUI_ER_Debug_BtnPrintLogistics"), self, function(target)
        local s = target:selectedSettlement()
        if s and EmberRoutesJava and EmberRoutesJava.getLogisticsSummary then
            local sum = EmberRoutesJava.getLogisticsSummary(s.id)
            print("[EmberRoutes][Debug] Logistics " .. tostring(sum))
            target:setFeedback(tostring(sum))
        end
    end, { h = 24 })
    self.btnDeliverApple = UI.Button(getText("IGUI_ER_Debug_BtnDeliverApple"), self, function(target)
        local s = target:selectedSettlement()
        if s and EmberRoutesJava and EmberRoutesJava.submitDeliveryOrder then
            local oid = EmberRoutesJava.submitDeliveryOrder(s.id, 0, "Base.Apple", 1)
            target:setFeedback("Apple x1 -> " .. tostring(oid))
        end
    end, { h = 24 })
    self.btnInspectSlots = UI.Button(getText("IGUI_ER_Debug_BtnInspectSlots"), self, function(target)
        local s = target:selectedSettlement()
        local sid = s and s.id or nil
        local slotSum = NpcRuntime.getSmartSlotSummary(sid)
        print("[EmberRoutes][Debug] SmartSlots(" .. tostring(sid) .. ")=" .. tostring(slotSum))
        local ids = NpcRuntime.listIds()
        for i = 1, #ids do
            local id = ids[i]
            print("[EmberRoutes][Debug] NPC " .. tostring(id)
                .. " mode=" .. tostring(NpcRuntime.getBrainMode(id))
                .. " path=" .. tostring(NpcRuntime.getBrainStatePath(id))
                .. " posture=" .. tostring(NpcRuntime.getPostureState(id)))
        end
        target:setFeedback("SmartSlots=" .. tostring(slotSum))
    end, { h = 24 })
    self.btnOpenTheme = UI.Button(getText("IGUI_ER_Debug_BtnTheme"), self, function()
        if EmberUI.openThemeSettings then
            EmberUI.openThemeSettings("er.warehouse")
        end
    end, { h = 24 })

    host:build(UI.Column({ padding = 4, gap = 6 }, {
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(self.btnSettlePrev, { basis = 28, grow = 0 }),
            UI.Item(self.lblSettleSelected, { basis = 0, grow = 1 }),
            UI.Item(self.btnSettleNext, { basis = 28, grow = 0 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(self.lblLogiSecActions, { basis = 18, grow = 0 }),
        UI.Item(UI.Row({ gap = 8 }, {
            UI.Item(self.btnOpenPanel, { basis = 0, grow = 1 }),
            UI.Item(self.btnToggleBorders, { basis = 0, grow = 1 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(UI.Row({ gap = 8 }, {
            UI.Item(self.btnPrintLogi, { basis = 0, grow = 1 }),
            UI.Item(self.btnDeliverApple, { basis = 0, grow = 1 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(UI.Row({ gap = 8 }, {
            UI.Item(self.btnInspectSlots, { basis = 0, grow = 1 }),
            UI.Item(self.btnOpenTheme, { basis = 0, grow = 1 }),
        }), { basis = 24, grow = 0 }),
        UI.Spacer(1),
    }))
    return host
end

function PanelCtrl:refreshLogisticsTab()
    local s, count = self:selectedSettlement()
    local hasSettle = s ~= nil
    if hasSettle then
        self.lblSettleSelected:setText(string.format("[%d/%d] %s (%s)", self.selectedSettlementIdx, count, tostring(s.name), tostring(s.id)))
    else
        self.lblSettleSelected:setText(getText("IGUI_ER_Debug_NoSettlement"))
    end
    self.btnPrintLogi:setEnable(hasSettle)
    self.btnDeliverApple:setEnable(hasSettle)
end

function PanelCtrl:syncActiveTab(force)
    if self.activeTabId == "combat" then
        self:refreshCombatTab(force)
    elseif self.activeTabId == "npc" then
        self:refreshNpcTab()
    elseif self.activeTabId == "logistics" then
        self:refreshLogisticsTab()
    end
end

local function createDebugWindow(initialTabId, opts)
    opts = opts or {}
    local scope = Kernel.scope("DebugPanel")
    local ctrl = setmetatable({
        scope = scope,
        activeTabId = initialTabId or "combat",
        selectedNpcIdx = 1,
        selectedSettlementIdx = 1,
        lastLivePollMs = 0,
        lastCombatCfgSig = "",
        tabPanels = {},
        previewCombatSnap = opts.previewCombatSnap,
    }, PanelCtrl)

    scope:defer(function()
        if ctrl.tabPanels then
            for _id, p in pairs(ctrl.tabPanels) do
                if p and type(p.dispose) == "function" and not p.closed then
                    pcall(p.dispose, p)
                end
            end
        end
        if instance == ctrl then
            instance = nil
        end
    end)

    local win, w, h = CategoryCatalog.createScopedWindow(ctrl, scope, getText("IGUI_ER_Debug_PanelTitle"), {
        width = opts.width or 760,
        height = opts.height or 540,
        minWidth = 640,
        minHeight = 460,
        x = opts.x,
        y = opts.y,
    })

    local tabBody = ISPanel:new(0, 0, w - 20, math.max(360, h - 100))
    tabBody:initialise()
    tabBody:instantiate()
    tabBody.background = false
    ctrl.tabBody = tabBody

    tabBody.setActivation = function(_self, actScope)
        for _id, panel in pairs(ctrl.tabPanels) do
            if panel and type(panel.setActivation) == "function" then
                pcall(panel.setActivation, panel, actScope)
            end
        end
    end

    tabBody.deactivate = function(_self)
        for _id, panel in pairs(ctrl.tabPanels) do
            if panel and type(panel.deactivate) == "function" then
                pcall(panel.deactivate, panel)
            end
        end
    end

    local pCombat = ctrl:buildCombatTab(tabBody.width, tabBody.height)
    local pNpc = ctrl:buildNpcTab(tabBody.width, tabBody.height)
    local pLogi = ctrl:buildLogisticsTab(tabBody.width, tabBody.height)
    local panels = {
        { id = "combat", label = getText("IGUI_ER_Debug_TabCombat"), panel = pCombat },
        { id = "npc", label = getText("IGUI_ER_Debug_TabNpc"), panel = pNpc },
        { id = "logistics", label = getText("IGUI_ER_Debug_TabLogistics"), panel = pLogi },
    }
    for i = 1, #panels do
        local p = panels[i].panel
        p:setX(0)
        p:setY(0)
        p:setWidth(tabBody.width)
        p:setHeight(tabBody.height)
        tabBody:addChild(p)
        p:setVisible(false)
        ctrl.tabPanels[panels[i].id] = p
    end

    tabBody.onResize = function(self)
        ISPanel.onResize(self)
        for _id, p in pairs(ctrl.tabPanels) do
            if p and not p.closed then
                p:setX(0)
                p:setY(0)
                p:setWidth(self.width)
                p:setHeight(self.height)
                if p.onResize then p:onResize() end
            end
        end
    end

    ctrl.tabBar = UI.TabBar(panels, ctrl.activeTabId, function(target, nextId)
        target.activeTabId = nextId
        target:syncActiveTab(true)
    end, { h = 28, variant = "underline", target = ctrl, panels = ctrl.tabPanels })
    ctrl.tabBar:bindPanels(ctrl.tabPanels)

    ctrl.lblFeedback = UI.Label(getText("IGUI_ER_Debug_ReadyHint"), { h = 18, color = Theme.muted, truncate = true })

    local spec = UI.Column({ padding = 10, gap = 6 }, {
        UI.Item(ctrl.tabBar, { basis = 28, grow = 0 }),
        UI.Item(UI.Native(tabBody), { basis = 0, grow = 1 }),
        UI.Item(ctrl.lblFeedback, { basis = 18, grow = 0 }),
    })
    win:setContentSpec(spec)
    win:onResize()

    local baseUpdate = win.update or (EmberUI.WindowHost and EmberUI.WindowHost.update)
    win.update = function(self)
        if type(baseUpdate) == "function" then
            baseUpdate(self)
        end
        local now = nowMs()
        if now - (ctrl.lastLivePollMs or 0) >= LIVE_POLL_INTERVAL_MS then
            ctrl.lastLivePollMs = now
            if ctrl.activeTabId == "combat" then
                ctrl:refreshCombatTab(false)
            end
        end
    end

    instance = ctrl
    ctrl:syncActiveTab(true)
    return ctrl
end

function DebugPanel.open(initialTabId)
    if instance and instance.win and not instance.win.disposed then
        instance.win:addToUIManager()
        instance.win:setVisible(true)
        instance.win:bringToTop()
        if initialTabId then
            instance:selectTab(initialTabId)
        end
        instance:syncActiveTab(true)
        return instance
    end
    return createDebugWindow(initialTabId, nil)
end

function DebugPanel.openPreview(opts)
    DebugPanel.close()
    return createDebugWindow(opts and opts.tabId or "combat", opts)
end

function DebugPanel.close()
    if instance and instance.win and not instance.win.disposed then
        instance.win:close()
    end
    instance = nil
end

function DebugPanel.toggle()
    if instance and instance.win and not instance.win.disposed and instance.win:isVisible() then
        DebugPanel.close()
        return
    end
    DebugPanel.open("combat")
end

function DebugPanel.getActive()
    return instance
end

Kernel.registerModule({
    id = "debug-panel",
    onTeardown = function()
        DebugPanel.close()
    end,
})

return DebugPanel
