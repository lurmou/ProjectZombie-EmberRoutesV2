local EmberUI = require "EmberUI"
local UI = EmberUI.UI
local Theme = EmberUI.Theme
local CachedList = require "EmberUI/Widgets/CachedList"

local CategoryCatalog = require "EmberRoutes/UI/CategoryCatalog"
local ContainerConfigWizard = require "EmberRoutes/UI/ContainerConfigWizard"

local ContainersTab = {}

local ROLE_BADGES = {
    STORAGE = "INFO",
    BUFFER_IN = "UPDATING",
    PICKUP_OUT = "READY",
    PRIVATE = "DISABLED",
}

local rowKey = CategoryCatalog.rowKey
local rowCaption = CategoryCatalog.rowCaption

local CONT_STYLE = CategoryCatalog.makeBadgedListStyle({
    height = 28,
    badgeW = 84,
    extraW = 86,
    textPadY = 6,
})

local TabCtrl = {}
TabCtrl.__index = TabCtrl

function TabCtrl:syncHighlight()
    if not EmberRoutesJava then return end
    if self.locateActive and self.selectedContainer and EmberRoutesJava.setContainerHighlight then
        EmberRoutesJava.setContainerHighlight(
            self.selectedContainer.x or 0,
            self.selectedContainer.y or 0,
            self.selectedContainer.z or 0
        )
    elseif EmberRoutesJava.clearContainerHighlight then
        EmberRoutesJava.clearContainerHighlight()
    end
end

function TabCtrl:refreshFiltered()
    local q = self.searchQuery and string.lower(self.searchQuery) or ""
    local rows = {}
    self.rev = (self.rev or 0) + 1

    for i = 1, #self.allContainers do
        local c = self.allContainers[i]
        local roleKey = c.isPrivate and "PRIVATE" or tostring(c.role or "STORAGE")
        local matchFilter = (self.roleFilter == "ALL") or (roleKey == self.roleFilter)
        local matchQuery = (q == "") or (string.find(c.searchBlob, q, 1, true) ~= nil)
        if matchFilter and matchQuery then
            rows[#rows + 1] = c
        end
    end

    self.filtered = rows
    local sel = CachedList.reconcile(self.list, rows, rowCaption, rowKey, true, self.rev)
    self.selectedContainer = (type(sel) == "table") and sel or rows[1]
    local hasSel = self.selectedContainer ~= nil
    self.btnConfigure:setEnable(hasSel)
    self.btnAutoZone:setEnable(hasSel)
    self.btnLocate:setEnable(hasSel)
    if self.locateActive then
        self:syncHighlight()
    end
end

function TabCtrl:refreshData(settlementId, _snap, rawContainers)
    self.settlementId = settlementId
    local list = rawContainers
    if not list and settlementId and EmberRoutesJava and EmberRoutesJava.listSettlementContainers then
        list = EmberRoutesJava.listSettlementContainers(settlementId)
    end
    list = list or {}

    local nextAll = {}
    for i = 1, #list do
        local c = list[i]
        if c and c.containerId then
            local role = c.isPrivate and "PRIVATE" or tostring(c.role or "STORAGE")
            local badgeLabel = c.isPrivate
                and getText("IGUI_ER_Cont_BadgePrivate")
                or CategoryCatalog.getRoleShort(role)
            local rulesHint = c.allowGroups and c.allowGroups ~= "" and (" | " .. c.allowGroups) or ""
            local offTag = (c.loaded == false) and (" [" .. getText("IGUI_ER_StatusOffline") .. "]") or ""
            local disTag = (not c.isPrivate and c.enabled == false) and (" [" .. getText("IGUI_ER_Cont_DisabledTag") .. "]") or ""
            local leftText = string.format(
                "[P%d] %s (%d,%d,Z=%d)%s%s%s",
                math.floor(c.priority or 3),
                tostring(c.displayName or c.kind or "container"),
                math.floor(c.x or 0),
                math.floor(c.y or 0),
                math.floor(c.z or 0),
                offTag,
                disTag,
                rulesHint
            )
            local midText = c.isPrivate
                and getText("IGUI_ER_Cont_PrivateWeight")
                or string.format("%.1f/%.0fkg", c.usedWeight or 0, c.capacity or 0)
            nextAll[#nextAll + 1] = {
                key = c.containerId,
                containerId = c.containerId,
                x = c.x or 0,
                y = c.y or 0,
                z = c.z or 0,
                loaded = c.loaded ~= false,
                role = c.role or "STORAGE",
                isPrivate = c.isPrivate == true,
                badgeKey = ROLE_BADGES[role] or "INFO",
                badgeLabel = badgeLabel,
                leftText = leftText,
                extraText = midText,
                caption = leftText,
                searchBlob = string.lower(leftText),
                tooltip = getText(
                    "IGUI_ER_Cont_TipFmt",
                    tostring(c.displayName or "container"),
                    math.floor(c.x or 0),
                    math.floor(c.y or 0),
                    math.floor(c.z or 0),
                    badgeLabel,
                    tostring(c.allowGroups or "-"),
                    tostring(c.denyGroups or "-")
                ),
            }
        end
    end
    self.allContainers = nextAll
    self:refreshFiltered()
end

function TabCtrl:onOpenWizard()
    if not self.settlementId or not self.selectedContainer then return end
    ContainerConfigWizard.openForSettlementContainer(
        self.settlementId,
        self.selectedContainer.containerId,
        function()
            if self.parentCtrl and self.parentCtrl.forceRefresh then
                self.parentCtrl:forceRefresh()
            end
        end
    )
end

function TabCtrl:onAutoConfigureSelected()
    if not self.settlementId or not self.selectedContainer or not EmberRoutesJava then return end
    if EmberRoutesJava.autoConfigureContainerById then
        EmberRoutesJava.autoConfigureContainerById(self.settlementId, self.selectedContainer.containerId)
        if self.parentCtrl and self.parentCtrl.forceRefresh then
            self.parentCtrl:forceRefresh()
        end
    end
end

function TabCtrl:onToggleLocate()
    self.locateActive = not self.locateActive
    Theme.button(self.btnLocate, self.locateActive, self.locateActive)
    self:syncHighlight()
end

function ContainersTab.createTabPage(panelCtrl)
    local host = EmberUI.LayoutHost:new(0, 0, 800, 400, { direction = "column" })
    host:initialise()
    host:instantiate()

    local ctrl = setmetatable({
        parentCtrl = panelCtrl,
        settlementId = nil,
        searchQuery = "",
        roleFilter = "ALL",
        allContainers = {},
        filtered = {},
        selectedContainer = nil,
        locateActive = false,
        rev = 0,
    }, TabCtrl)

    ctrl.searchBox = UI.SearchInput(getText("IGUI_ER_Cont_SearchHint"), ctrl, function(target, val)
        target.searchQuery = val or ""
        target:refreshFiltered()
    end, { h = 24 })

    ctrl.roleChips = CategoryCatalog.bindFilterChipsResize(
        UI.FilterChips({
            { id = "ALL",        label = getText("IGUI_ER_Cat_All") },
            { id = "STORAGE",    label = CategoryCatalog.getRoleShort("STORAGE") },
            { id = "BUFFER_IN",  label = CategoryCatalog.getRoleShort("BUFFER_IN") },
            { id = "PICKUP_OUT", label = CategoryCatalog.getRoleShort("PICKUP_OUT") },
            { id = "PRIVATE",    label = getText("IGUI_ER_Cont_BadgePrivate") },
        }, "ALL", function(id)
            ctrl.roleFilter = id or "ALL"
            ctrl:refreshFiltered()
        end, { h = 24, gap = 4 })
    )

    ctrl.btnConfigure = UI.Button(getText("IGUI_ER_Cont_BtnConfig"), ctrl, TabCtrl.onOpenWizard, { h = 24, primary = true })
    ctrl.btnAutoZone = UI.Button(getText("IGUI_ER_Wiz_BtnAutoZone"), ctrl, TabCtrl.onAutoConfigureSelected, { h = 24 })
    ctrl.btnLocate = UI.Button(getText("IGUI_ER_Cont_BtnLocate"), ctrl, TabCtrl.onToggleLocate, { h = 24 })

    ctrl.list = UI.List({
        owner = ctrl,
        itemheight = 28,
        style = CONT_STYLE,
        well = true,
        emptyState = {
            title = getText("IGUI_ER_Cont_EmptyTitle"),
            hint = getText("IGUI_ER_Cont_EmptyHint"),
        },
        onSelect = function(target, val)
            target.selectedContainer = val
            if target.locateActive then
                target:syncHighlight()
            end
        end,
    })
    if ctrl.list.setOnMouseDoubleClick then
        ctrl.list:setOnMouseDoubleClick(ctrl, function(target, _row)
            target:onOpenWizard()
        end)
    end

    host:build(UI.Column({ padding = 6, gap = 6 }, {
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(ctrl.searchBox, { basis = 210, grow = 0 }),
            UI.Item(ctrl.roleChips, { basis = 0, grow = 1 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(ctrl.btnConfigure, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnAutoZone, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnLocate, { basis = 0, grow = 1 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(ctrl.list, { basis = 0, grow = 1 }),
    }))

    host.ctrl = ctrl
    host.onDeactivate = function()
        if ctrl.locateActive then
            ctrl.locateActive = false
            Theme.button(ctrl.btnLocate, false, false)
            if EmberRoutesJava and EmberRoutesJava.clearContainerHighlight then
                EmberRoutesJava.clearContainerHighlight()
            end
        end
    end
    return host
end

return ContainersTab
