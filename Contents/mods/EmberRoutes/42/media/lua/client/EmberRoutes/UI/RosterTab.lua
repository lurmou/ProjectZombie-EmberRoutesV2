local EmberUI = require "EmberUI"
local UI = EmberUI.UI
local Theme = EmberUI.Theme
local CachedList = require "EmberUI/Widgets/CachedList"

local CategoryCatalog = require "EmberRoutes/UI/CategoryCatalog"
local NpcRuntime = require "EmberRoutes/Npc/NpcRuntime"
local NpcInventoryPanel = require "EmberRoutes/UI/NpcInventoryPanel"
local NpcMedicalPanel = require "EmberRoutes/UI/NpcMedicalPanel"

local RosterTab = {}

local rowKey = CategoryCatalog.rowKey
local rowCaption = CategoryCatalog.rowCaption

local ROSTER_STYLE = CategoryCatalog.makeBadgedListStyle({
    height = 30,
    badgeW = 92,
    extraW = 148,
    textPadY = 7,
})

local TabCtrl = {}
TabCtrl.__index = TabCtrl

function TabCtrl:refreshActionButtons()
    local npc = self.selectedNpc
    local hasNpc = npc ~= nil
    self.btnToggleBrain:setEnable(hasNpc)
    self.btnAssignHome:setEnable(hasNpc and self.settlementId ~= nil and self.settlementId ~= "")
    self.btnOpenInv:setEnable(hasNpc and npc.phasedIn == true)
    self.btnOpenMed:setEnable(hasNpc and npc.phasedIn == true)
    if hasNpc then
        if npc.assignedSettlementId == self.settlementId and self.settlementId ~= nil then
            self.btnAssignHome:setTitle(getText("IGUI_ER_Roster_BtnUnassign"))
        else
            self.btnAssignHome:setTitle(getText("IGUI_ER_Roster_BtnAssignHere"))
        end
        if npc.brainMode == "SQUAD" then
            self.btnToggleBrain:setTitle(getText("IGUI_ER_Roster_BtnToSettlement"))
        else
            self.btnToggleBrain:setTitle(getText("IGUI_ER_Roster_BtnToSquad"))
        end
    else
        self.btnAssignHome:setTitle(getText("IGUI_ER_Roster_BtnAssignHere"))
        self.btnToggleBrain:setTitle(getText("IGUI_ER_Roster_BtnToSquad"))
    end
end

function TabCtrl:refreshRoster()
    local raw = (EmberRoutesJava and EmberRoutesJava.listNpcRoster)
        and (EmberRoutesJava.listNpcRoster() or {})
        or {}

    local sid = self.settlementId
    local sorted = {}
    for i = 1, #raw do
        sorted[#sorted + 1] = raw[i]
    end
    table.sort(sorted, function(a, b)
        local aHome = (sid ~= nil and a.assignedSettlementId == sid) and 0 or 1
        local bHome = (sid ~= nil and b.assignedSettlementId == sid) and 0 or 1
        if aHome ~= bHome then return aHome < bHome end
        return tostring(a.displayName or a.npcId) < tostring(b.displayName or b.npcId)
    end)

    local total, hereCount, settleCount, squadCount = 0, 0, 0, 0
    local rows = {}
    self.rev = (self.rev or 0) + 1

    for i = 1, #sorted do
        local n = sorted[i]
        total = total + 1
        local homeId = n.assignedSettlementId or ""
        local isHere = (sid ~= nil and homeId == sid)
        if isHere then hereCount = hereCount + 1 end
        if n.brainMode == "SQUAD" then
            squadCount = squadCount + 1
        else
            settleCount = settleCount + 1
        end

        local badgeKey = "INFO"
        local badgeLabel = getText("IGUI_ER_Roster_ModeSettlement")
        if not n.phasedIn then
            badgeKey = "DISABLED"
            badgeLabel = getText("IGUI_ER_StatusOffline")
        elseif n.brainMode == "SQUAD" then
            badgeKey = "ACTIVE"
            badgeLabel = getText("IGUI_ER_Roster_ModeSquad")
        else
            badgeKey = "READY"
            badgeLabel = getText("IGUI_ER_Roster_ModeSettlement")
        end

        local homeTag = isHere and getText("IGUI_ER_Roster_TagHere")
            or (homeId ~= "" and ("[" .. homeId .. "]") or getText("IGUI_ER_Roster_TagUnassigned"))
        local pathShort = tostring(n.brainStatePath or "IDLE")
        local lastSlash = nil
        local pos = 1
        while true do
            local found = string.find(pathShort, "/", pos, true)
            if not found then break end
            lastSlash = found
            pos = found + 1
        end
        if lastSlash then
            pathShort = string.sub(pathShort, lastSlash + 1)
        end

        local leftText = string.format("%s %s | %s", tostring(n.displayName or n.npcId), homeTag, pathShort)
        local hpPct = math.floor((n.health or 100) + 0.5)
        local bleedTag = n.bleeding and (" " .. getText("IGUI_ER_Roster_BleedWarn")) or ""
        local vitalsText = n.phasedIn
            and getText(
                "IGUI_ER_Roster_VitalsFmt",
                tostring(hpPct),
                tostring(math.floor((n.hunger or 0) * 100)),
                tostring(math.floor((n.fatigue or 0) * 100)),
                bleedTag
            )
            or getText("IGUI_ER_StatusOffline")

        local hasAlert = (n.bleeding == true) or (hpPct < 75) or ((n.hunger or 0) >= 0.6) or ((n.thirst or 0) >= 0.6)
        rows[#rows + 1] = {
            key = n.npcId,
            npcId = n.npcId,
            displayName = n.displayName or n.npcId,
            phasedIn = n.phasedIn == true,
            assignedSettlementId = homeId,
            brainMode = n.brainMode or "SETTLEMENT",
            leftText = leftText,
            extraText = vitalsText,
            hasAlert = hasAlert,
            badgeKey = badgeKey,
            badgeLabel = badgeLabel,
            caption = leftText,
            tooltip = string.format(
                "%s (%s)\n%s\n%s",
                tostring(n.displayName or n.npcId),
                tostring(n.npcId),
                tostring(n.brainStatePath or ""),
                vitalsText
            ),
        }
    end

    self.lblSummary:setText(getText(
        "IGUI_ER_Roster_SummaryFmt",
        tostring(total),
        tostring(hereCount),
        tostring(settleCount),
        tostring(squadCount)
    ))

    local sel = CachedList.reconcile(self.list, rows, rowCaption, rowKey, true, self.rev)
    self.selectedNpc = (type(sel) == "table") and sel or rows[1]
    self:refreshActionButtons()
end

function TabCtrl:refreshData(settlementId)
    self.settlementId = settlementId
    self:refreshRoster()
end

function TabCtrl:onToggleBrainMode()
    if not self.selectedNpc then return end
    local nextMode = (self.selectedNpc.brainMode == "SQUAD") and "SETTLEMENT" or "SQUAD"
    NpcRuntime.setBrainMode(self.selectedNpc.npcId, nextMode)
    self:refreshRoster()
end

function TabCtrl:onToggleAssignHome()
    if not self.selectedNpc or not self.settlementId or self.settlementId == "" then return end
    local alreadyHere = (self.selectedNpc.assignedSettlementId == self.settlementId)
    NpcRuntime.assignToSettlement(self.selectedNpc.npcId, alreadyHere and nil or self.settlementId)
    self:refreshRoster()
end

function TabCtrl:onOpenInventory()
    if not self.selectedNpc then return end
    local player = getSpecificPlayer and getSpecificPlayer(0)
    local npcObj = NpcRuntime.getObject(self.selectedNpc.npcId)
    if player and npcObj then
        NpcInventoryPanel.open(player, npcObj)
    end
end

function TabCtrl:onOpenMedical()
    if not self.selectedNpc then return end
    local player = getSpecificPlayer and getSpecificPlayer(0)
    local npcObj = NpcRuntime.getObject(self.selectedNpc.npcId)
    if player and npcObj then
        NpcMedicalPanel.open(player, npcObj)
    end
end

function RosterTab.createTabPage(panelCtrl)
    local host = EmberUI.LayoutHost:new(0, 0, 800, 400, { direction = "column" })
    host:initialise()
    host:instantiate()

    local ctrl = setmetatable({
        parentCtrl = panelCtrl,
        settlementId = nil,
        selectedNpc = nil,
        rev = 0,
    }, TabCtrl)

    ctrl.lblSummary = UI.Label(getText("IGUI_ER_Roster_SummaryFmt", "0", "0", "0", "0"), {
        h = 20,
        color = Theme.accent,
        truncate = true,
    })

    ctrl.list = UI.List({
        owner = ctrl,
        itemheight = 30,
        style = ROSTER_STYLE,
        well = true,
        emptyState = {
            title = getText("IGUI_ER_Roster_EmptyTitle"),
            hint = getText("IGUI_ER_Roster_EmptyHint"),
        },
        onSelect = function(target, val)
            target.selectedNpc = val
            target:refreshActionButtons()
        end,
    })

    ctrl.btnToggleBrain = UI.Button(getText("IGUI_ER_Roster_BtnToSquad"), ctrl, TabCtrl.onToggleBrainMode, { h = 24, primary = true })
    ctrl.btnAssignHome = UI.Button(getText("IGUI_ER_Roster_BtnAssignHere"), ctrl, TabCtrl.onToggleAssignHome, { h = 24 })
    ctrl.btnOpenInv = UI.Button(getText("IGUI_ER_Roster_BtnInventory"), ctrl, TabCtrl.onOpenInventory, { h = 24 })
    ctrl.btnOpenMed = UI.Button(getText("IGUI_ER_Roster_BtnMedical"), ctrl, TabCtrl.onOpenMedical, { h = 24 })

    host:build(UI.Column({ padding = 6, gap = 6 }, {
        UI.Item(ctrl.lblSummary, { basis = 20, grow = 0 }),
        UI.Item(ctrl.list, { grow = 1 }),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(ctrl.btnToggleBrain, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnAssignHome, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnOpenInv, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnOpenMed, { basis = 0, grow = 1 }),
        }), { basis = 24, grow = 0 }),
    }))

    host.ctrl = ctrl
    ctrl:refreshActionButtons()
    return host
end

return RosterTab
