local EmberUI = require "EmberUI"
local UI = EmberUI.UI
local Theme = EmberUI.Theme
local Clock = EmberUI.Core and EmberUI.Core.Clock
local SlotRegistry = EmberUI.SlotRegistry

local Kernel = require "EmberRoutes/Kernel"
local SettlementStore = require "EmberRoutes/Settlement/SettlementStore"
local Geometry = require "EmberRoutes/Settlement/SettlementGeometry"
local CategoryCatalog = require "EmberRoutes/UI/CategoryCatalog"
local ZoneEditorPanel = require "EmberRoutes/UI/ZoneEditorPanel"
local ZoneOverlay = require "EmberRoutes/UI/ZoneOverlay"
local WarehouseTerminalTab = require "EmberRoutes/UI/WarehouseTerminalTab"
local ContainersTab = require "EmberRoutes/UI/ContainersTab"
local RosterTab = require "EmberRoutes/UI/RosterTab"

local function nowMs()
    if Clock and type(Clock.nowMs) == "function" then
        return Clock.nowMs()
    end
    if EmberRoutesJava and EmberRoutesJava.diagnosticTimeNanos then
        return EmberRoutesJava.diagnosticTimeNanos() / 1000000.0
    end
    return 0
end

local SettlementControlPanel = {}
local instance = nil

local SLOT_NAME = "er.settlement.tabs"
local LIVE_POLL_INTERVAL_MS = 600

if Theme and Theme.registerModule then
    Theme.registerModule({
        id = "er.warehouse",
        labelKey = "IGUI_ER_ThemeMod_Warehouse",
        label = "Settlement Control",
        order = 10,
        colors = {
            { key = "countText",    labelKey = "IGUI_ER_ThemeCol_CountText",    label = "Item Count Text",      default = "accent" },
            { key = "degradedText", labelKey = "IGUI_ER_ThemeCol_DegradedText", label = "Degraded Alert Text",  default = "warning" },
            { key = "storageText",  labelKey = "IGUI_ER_ThemeCol_StorageText",  label = "Storage Summary Text", default = "success" },
            { key = "patchText",    labelKey = "IGUI_ER_ThemeCol_PatchText",    label = "Engine Stats Text",    default = "accent" },
        },
    })
end

local function registerDefaultTabs()
    if not SlotRegistry or not SlotRegistry.register then return end
    SlotRegistry.register({
        id = "warehouse",
        slot = SLOT_NAME,
        order = 10,
        title = getText("IGUI_ER_Tab_Warehouse"),
        mount = function(ctrl)
            return WarehouseTerminalTab.createTabPage(ctrl)
        end,
    })
    SlotRegistry.register({
        id = "containers",
        slot = SLOT_NAME,
        order = 20,
        title = getText("IGUI_ER_Tab_Containers"),
        mount = function(ctrl)
            return ContainersTab.createTabPage(ctrl)
        end,
    })
    SlotRegistry.register({
        id = "roster",
        slot = SLOT_NAME,
        order = 30,
        title = getText("IGUI_ER_Tab_Roster"),
        mount = function(ctrl)
            return RosterTab.createTabPage(ctrl)
        end,
    })
end

local PanelCtrl = {}
PanelCtrl.__index = PanelCtrl

function PanelCtrl:getSettlementListCached()
    if self.storeDirty or not self.cachedList then
        self.cachedList = SettlementStore.list()
    end
    return self.cachedList
end

function PanelCtrl:selectBySettlementId(settlementId)
    self.storeDirty = true
    local list = self:getSettlementListCached()
    for i = 1, #list do
        if list[i].id == settlementId then
            self.selectedIdx = i
            break
        end
    end
    self.viewDirty = true
end

function PanelCtrl:forceRefresh()
    self.storeDirty = true
    self.viewDirty = true
    self:projectIfDirty(true)
end

function PanelCtrl:selectTab(tabId)
    if self.tabBar and self.tabBar.setSelected then
        self.tabBar:setSelected(tabId, false)
    end
end

function PanelCtrl:onPrevSettlement()
    local list = self:getSettlementListCached()
    if #list == 0 then return end
    self.selectedIdx = ((self.selectedIdx - 2) % #list) + 1
    self.viewDirty = true
    self:projectIfDirty(true)
end

function PanelCtrl:onNextSettlement()
    local list = self:getSettlementListCached()
    if #list == 0 then return end
    self.selectedIdx = (self.selectedIdx % #list) + 1
    self.viewDirty = true
    self:projectIfDirty(true)
end

function PanelCtrl:onCreateSettlement()
    ZoneEditorPanel.open(nil, function(saved)
        if instance then
            instance:selectBySettlementId(saved.id)
        end
    end)
end

function PanelCtrl:onEditSettlement()
    local list = self:getSettlementListCached()
    local cur = list[self.selectedIdx]
    if not cur then return end
    ZoneEditorPanel.open(cur, function(saved)
        if instance then
            instance:selectBySettlementId(saved.id)
        end
    end)
end

function PanelCtrl:onToggleOverlay()
    ZoneOverlay.toggleVisible()
    self:syncOverlayButtonTitle()
end

function PanelCtrl:onOpenTheme()
    if EmberUI.openThemeSettings then
        EmberUI.openThemeSettings("er.warehouse")
    end
end

function PanelCtrl:onForceRemount()
    SettlementStore.reconcileAndMountAll()
    self:forceRefresh()
end

function PanelCtrl:onDeleteSettlement()
    local list = self:getSettlementListCached()
    local cur = list[self.selectedIdx]
    if not cur then return end
    SettlementStore.deleteSettlement(cur.id)
    self:forceRefresh()
end

function PanelCtrl:syncOverlayButtonTitle()
    if not self.btnOverlay then return end
    local visible = ZoneOverlay.isVisible()
    self.btnOverlay:setTitle(visible and getText("IGUI_ER_BtnOverlayOn") or getText("IGUI_ER_BtnOverlayOff"))
    Theme.button(self.btnOverlay, visible, visible)
end

function PanelCtrl:syncActiveTabLive()
    local activePanel = self.tabPanels and self.tabPanels[self.activeTabId]
    if activePanel and activePanel.ctrl then
        if self.activeTabId == "warehouse" and activePanel.ctrl.syncLiveOrders then
            activePanel.ctrl:syncLiveOrders()
        elseif self.activeTabId == "roster" and activePanel.ctrl.refreshData then
            activePanel.ctrl:refreshData(self.cachedSettlementId)
        end
    end
end

function PanelCtrl:refreshAllTabPages(settlementId, snap, containers)
    if not self.tabPanels then return end
    for _id, panel in pairs(self.tabPanels) do
        if panel and panel.ctrl and panel.ctrl.refreshData then
            panel.ctrl:refreshData(settlementId, snap, containers)
        end
    end
end

function PanelCtrl:projectIfDirty(force)
    if not EmberRoutesJava then
        self.lblHeader:setText(getText("IGUI_ER_NoJavaBridge"))
        self.lblTerritory:setText("")
        return
    end

    local actor = getSpecificPlayer and getSpecificPlayer(0) or nil
    local pz = actor and math.floor(actor:getZ()) or 0
    local list = self:getSettlementListCached()
    local dirtyEv = EmberRoutesJava.getDirtyEventCount()
    local awakeTickers = EmberRoutesJava.getAwakeTickerCount and EmberRoutesJava.getAwakeTickerCount() or 0

    if #list == 0 then
        local changed = force or self.storeDirty or self.viewDirty or self.cachedSettlementId ~= nil
        if not changed then
            local now = nowMs()
            if now - (self.lastLivePollMs or 0) >= LIVE_POLL_INTERVAL_MS then
                self.lastLivePollMs = now
                self:syncActiveTabLive()
            end
            return
        end
        self.storeDirty = false
        self.viewDirty = false
        self.cachedSettlementId = nil
        self.cachedRevision = -1
        self.cachedDirtyEvents = dirtyEv
        self.cachedAwakeTickers = awakeTickers
        self.cachedFloorZ = pz
        self.lastLivePollMs = nowMs()

        self.lblHeader:setText(getText("IGUI_ER_EmptySettlements"))
        self.lblTerritory:setText(getText("IGUI_ER_EmptyHint"))
        if self.loadMeter and self.loadMeter.setValue then
            self.loadMeter:setValue(0, "0 / 0 kg")
        end
        if self.btnEdit then self.btnEdit:setEnable(false) end
        if self.btnDelete then self.btnDelete:setEnable(false) end
        if self.prevBtn then self.prevBtn:setEnable(false) end
        if self.nextBtn then self.nextBtn:setEnable(false) end
        self:refreshAllTabPages(nil, nil, nil)
        return
    end

    if self.selectedIdx < 1 or self.selectedIdx > #list then
        self.selectedIdx = 1
    end
    local s = list[self.selectedIdx]
    local rev = EmberRoutesJava.getWarehouseRevision(s.id)

    local structuralDirty = force
        or self.storeDirty
        or self.viewDirty
        or self.cachedSettlementId ~= s.id
        or self.cachedRevision ~= rev
        or self.cachedDirtyEvents ~= dirtyEv
        or self.cachedAwakeTickers ~= awakeTickers
        or self.cachedFloorZ ~= pz

    if not structuralDirty then
        local now = nowMs()
        if now - (self.lastLivePollMs or 0) >= LIVE_POLL_INTERVAL_MS then
            self.lastLivePollMs = now
            self:syncActiveTabLive()
        end
        return
    end

    self.storeDirty = false
    self.viewDirty = false
    self.cachedSettlementId = s.id
    self.cachedRevision = rev
    self.cachedDirtyEvents = dirtyEv
    self.cachedAwakeTickers = awakeTickers
    self.cachedFloorZ = pz
    self.lastLivePollMs = nowMs()

    if self.btnEdit then self.btnEdit:setEnable(true) end
    if self.btnDelete then self.btnDelete:setEnable(true) end
    if self.prevBtn then self.prevBtn:setEnable(#list > 1) end
    if self.nextBtn then self.nextBtn:setEnable(#list > 1) end

    local tiles = Geometry.totalTiles(s.areas)
    local enc = EmberRoutesJava.inspectAreasEnclosure(SettlementStore.toFlatRects(s.areas), pz)
    local enclosedTiles = enc and enc.enclosed or 0
    local roofedTiles = enc and enc.roofed or 0

    local snap = EmberRoutesJava.getWarehouseSnapshot(s.id)
    local counts = snap and snap.counts
    local loaded = counts and counts.loaded or 0
    local unloaded = counts and counts.unloaded or 0
    local privCount = counts and counts.privateCount or 0
    local items = counts and counts.items or 0
    local usedW = counts and counts.usedWeight or 0
    local totalCap = counts and counts.totalCapacity or 0

    self.lblHeader:setText(string.format("[%d/%d] %s (%s)", self.selectedIdx, #list, s.name, s.id))
    self.lblTerritory:setText(getText(
        "IGUI_ER_Shell_StatsLineFmt",
        tostring(tiles),
        tostring(pz),
        tostring(enclosedTiles),
        tostring(roofedTiles),
        string.format("%.0f", loaded),
        string.format("%.0f", unloaded),
        string.format("%.0f", privCount),
        string.format("%.0f", items)
    ))

    if self.loadMeter and self.loadMeter.setValue then
        local frac = totalCap > 0 and math.max(0, math.min(1, usedW / totalCap)) or 0
        self.loadMeter:setValue(frac, string.format("%.1f / %.0f kg", usedW, totalCap))
    end

    local containers = EmberRoutesJava.listSettlementContainers
        and EmberRoutesJava.listSettlementContainers(s.id)
        or {}
    self:refreshAllTabPages(s.id, snap, containers)
end

function SettlementControlPanel.open(selectSettlementId, initialTabId)
    if instance and instance.win and not instance.win.disposed then
        instance.win:addToUIManager()
        instance.win:setVisible(true)
        instance.win:bringToTop()
        if selectSettlementId then
            instance:selectBySettlementId(selectSettlementId)
        else
            instance.viewDirty = true
        end
        if initialTabId then
            instance:selectTab(initialTabId)
        end
        instance:projectIfDirty(true)
        return instance
    end

    registerDefaultTabs()
    local scope = Kernel.scope("SettlementControlPanel")

    local ctrl = setmetatable({
        selectedIdx = 1,
        scope = scope,
        storeDirty = true,
        viewDirty = true,
        cachedList = nil,
        cachedSettlementId = nil,
        cachedRevision = -1,
        cachedDirtyEvents = -1,
        cachedAwakeTickers = -1,
        cachedFloorZ = 0,
        lastLivePollMs = 0,
        activeTabId = initialTabId or "warehouse",
        tabPanels = {},
    }, PanelCtrl)

    scope:subscribe(SettlementStore, function()
        if not ctrl.win or ctrl.win.disposed then return end
        ctrl.storeDirty = true
    end)
    scope:defer(function()
        if EmberRoutesJava and EmberRoutesJava.clearContainerHighlight then
            EmberRoutesJava.clearContainerHighlight()
        end
        if ctrl.tabPanels then
            for _id, panel in pairs(ctrl.tabPanels) do
                if panel and type(panel.dispose) == "function" and not panel.closed then
                    pcall(panel.dispose, panel)
                end
            end
        end
        if instance == ctrl then
            instance = nil
        end
    end)

    local win, w, _h = CategoryCatalog.createScopedWindow(ctrl, scope, getText("IGUI_ER_PanelTitle"), {
        width = 840,
        height = 540,
        minWidth = 720,
        minHeight = 460,
    })

    -- Header Bar
    ctrl.prevBtn = UI.Button("<", ctrl, PanelCtrl.onPrevSettlement, { w = 28, h = 24 })
    ctrl.lblHeader = UI.Label("", { h = 24, font = UIFont.Medium, color = Theme.warning, truncate = true })
    ctrl.nextBtn = UI.Button(">", ctrl, PanelCtrl.onNextSettlement, { w = 28, h = 24 })

    ctrl.btnCreate = UI.Button(getText("IGUI_ER_BtnNewSettlement"), ctrl, PanelCtrl.onCreateSettlement, { h = 24, primary = true })
    ctrl.btnEdit = UI.Button(getText("IGUI_ER_BtnEditZone"), ctrl, PanelCtrl.onEditSettlement, { h = 24 })
    ctrl.btnOverlay = UI.Button(getText("IGUI_ER_BtnOverlayOn"), ctrl, PanelCtrl.onToggleOverlay, { h = 24 })
    ctrl.btnRescan = UI.Button(getText("IGUI_ER_BtnRescan"), ctrl, PanelCtrl.onForceRemount, { h = 24 })
    ctrl.btnDelete = UI.Button(getText("IGUI_ER_BtnDeleteSettlement"), ctrl, PanelCtrl.onDeleteSettlement, { h = 24 })
    ctrl.btnTheme = UI.Button(getText("IGUI_ER_BtnTheme"), ctrl, PanelCtrl.onOpenTheme, { h = 24 })

    ctrl.lblTerritory = UI.Label("", { h = 18, color = Theme.getModuleColor("er.warehouse", "storageText"), truncate = true })
    ctrl.loadMeter = UI.StatMeter(0, { h = 18, mode = "load", label = "0 / 0 kg" })

    -- SlotRegistry-backed tabs container
    local tabBody = ISPanel:new(0, 0, w - 20, 320)
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

    local resolvedEntries = SlotRegistry.resolve(SLOT_NAME, { ctrl = ctrl })
    local tabDefs = {}
    for i = 1, #resolvedEntries do
        local entry = resolvedEntries[i]
        local panel = entry.mount and entry.mount(ctrl, tabBody) or nil
        if panel then
            panel:setX(0)
            panel:setY(0)
            panel:setWidth(tabBody.width)
            panel:setHeight(tabBody.height)
            tabBody:addChild(panel)
            panel:setVisible(false)
            ctrl.tabPanels[entry.id] = panel
            tabDefs[#tabDefs + 1] = {
                id = entry.id,
                label = entry.title or (entry.labelKey and getText(entry.labelKey)) or entry.id,
                panel = panel,
            }
        end
    end

    if not ctrl.tabPanels[ctrl.activeTabId] and tabDefs[1] then
        ctrl.activeTabId = tabDefs[1].id
    end

    tabBody.onResize = function(self)
        ISPanel.onResize(self)
        for _id, panel in pairs(ctrl.tabPanels) do
            if panel and not panel.closed then
                panel:setX(0)
                panel:setY(0)
                panel:setWidth(self.width)
                panel:setHeight(self.height)
                if panel.onResize then panel:onResize() end
            end
        end
    end

    ctrl.tabBar = UI.TabBar(tabDefs, ctrl.activeTabId, function(target, nextId)
        local prevPanel = target.tabPanels and target.tabPanels[target.activeTabId]
        if prevPanel and prevPanel.onDeactivate then
            prevPanel:onDeactivate()
        end
        target.activeTabId = nextId
        local nextPanel = target.tabPanels and target.tabPanels[nextId]
        if nextPanel and nextPanel.onActivate then
            nextPanel:onActivate()
        end
        target:syncActiveTabLive()
    end, { h = 28, variant = "underline", target = ctrl, panels = ctrl.tabPanels })

    ctrl.tabBar:bindPanels(ctrl.tabPanels)

    local fh = (getTextManager and getTextManager().getFontHeight) and getTextManager():getFontHeight(UIFont.Small) or 14
    local topRowH = math.max(24, fh + 6)
    local statRowH = math.max(18, fh + 4)
    local tabBarH = math.max(28, fh + 8)

    local spec = UI.Column({ padding = 10, gap = 6 }, {
        UI.Item(UI.Row({ gap = 6, align = "stretch" }, {
            UI.Item(ctrl.prevBtn, { basis = 28, grow = 0 }),
            UI.Item(ctrl.lblHeader, { basis = 0, grow = 1 }),
            UI.Item(ctrl.nextBtn, { basis = 28, grow = 0 }),
            UI.Item(ctrl.btnCreate, { basis = 96, grow = 0 }),
            UI.Item(ctrl.btnEdit, { basis = 84, grow = 0 }),
            UI.Item(ctrl.btnOverlay, { basis = 76, grow = 0 }),
            UI.Item(ctrl.btnRescan, { basis = 74, grow = 0 }),
            UI.Item(ctrl.btnDelete, { basis = 64, grow = 0 }),
            UI.Item(ctrl.btnTheme, { basis = 64, grow = 0 }),
        }), { basis = topRowH, grow = 0 }),
        UI.Item(UI.Row({ gap = 8, align = "stretch" }, {
            UI.Item(ctrl.lblTerritory, { basis = 0, grow = 1 }),
            UI.Item(ctrl.loadMeter, { basis = 180, grow = 0 }),
        }), { basis = statRowH, grow = 0 }),
        UI.Item(ctrl.tabBar, { basis = tabBarH, grow = 0 }),
        UI.Item(UI.Native(tabBody), { basis = 0, grow = 1 }),
    })
    win:setContentSpec(spec)
    win:onResize()

    local baseUpdate = win.update or (EmberUI.WindowHost and EmberUI.WindowHost.update)
    win.update = function(self)
        if type(baseUpdate) == "function" then
            baseUpdate(self)
        end
        ctrl:projectIfDirty(false)
    end

    local basePrerender = EmberUI.WindowHost.prerender
    win.prerender = function(self)
        ctrl:projectIfDirty(false)
        basePrerender(self)
    end

    instance = ctrl
    ctrl:syncOverlayButtonTitle()
    if selectSettlementId then
        ctrl:selectBySettlementId(selectSettlementId)
    end
    ctrl:projectIfDirty(true)
    return ctrl
end

function SettlementControlPanel.getActive()
    return instance
end

return SettlementControlPanel
