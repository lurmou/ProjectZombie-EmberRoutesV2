local EmberUI = require "EmberUI"
local UI = EmberUI.UI
local Theme = EmberUI.Theme
local Chrome = EmberUI.Chrome
local CachedList = require "EmberUI/Widgets/CachedList"

local SettlementStore = require "EmberRoutes/SettlementStore"
local Geometry = require "EmberRoutes/SettlementGeometry"
local ZoneEditorPanel = require "EmberRoutes/ZoneEditorPanel"
local Kernel = require "EmberRoutes/Kernel"

local DebugWarehousePanel = {}
local instance = nil

local PanelCtrl = {}
PanelCtrl.__index = PanelCtrl

local WAREHOUSE_LIST_STYLE = {
    prepare = function(list, row, label)
        return {
            height = 24,
            leftText = row.leftText,
            rightText = row.rightText,
            hasDegraded = row.hasDegraded == true,
        }
    end,
    draw = function(list, y, entry)
        local h = entry.height or 24
        local visible = y + list:getYScroll()
        if visible + h < 0 or visible >= list.height then
            return y + h
        end
        if list.selected == entry.index then
            Chrome.roundedFill(list, 2, y + 1, list.width - 12, h - 2, Theme.selected)
        elseif entry.index % 2 == 0 then
            list:drawRect(2, y, list.width - 12, h - 1, 0.14, 0.16, 0.20, 0.35)
        end
        local vis = entry.item and entry.item.visual
        if vis then
            Theme.text(list, vis.leftText, 8, y + 4, Theme.textColor, UIFont.Small)
            local rightCol = vis.hasDegraded and Theme.warning or Theme.accent
            list:drawTextRight(vis.rightText, list:getWidth() - 12, y + 4, rightCol.r, rightCol.g, rightCol.b, rightCol.a or 1.0, UIFont.Small)
        end
        return y + h
    end,
}

local function rowKey(row)
    return row.key
end

local function rowCaption(row)
    return row.caption
end

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

function PanelCtrl:onPrevSettlement()
    local list = self:getSettlementListCached()
    if #list == 0 then return end
    self.selectedIdx = ((self.selectedIdx - 2) % #list) + 1
    self.viewDirty = true
    self:projectIfDirty()
end

function PanelCtrl:onNextSettlement()
    local list = self:getSettlementListCached()
    if #list == 0 then return end
    self.selectedIdx = (self.selectedIdx % #list) + 1
    self.viewDirty = true
    self:projectIfDirty()
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

function PanelCtrl:onDeleteSettlement()
    local list = self:getSettlementListCached()
    local cur = list[self.selectedIdx]
    if not cur then return end
    SettlementStore.deleteSettlement(cur.id)
    self.storeDirty = true
    self:projectIfDirty()
end

function PanelCtrl:onForceRemount()
    SettlementStore.reconcileAndMountAll()
    self.storeDirty = true
    self:projectIfDirty()
end

--- 纯投影层：常规帧 0 表分配，仅在据点变更、仓储 revision 递增、切楼层或调试计数变化时更新缓存文本与列表。
function PanelCtrl:projectIfDirty()
    if not EmberRoutesJava then
        self.lblHeader:setText(getText("IGUI_ER_NoJavaBridge"))
        self.lblTerritory:setText("")
        self.lblStorage:setText("")
        self.lblPatch:setText("")
        return
    end

    local actor = getSpecificPlayer(0)
    local pz = actor and math.floor(actor:getZ()) or 0
    local list = self:getSettlementListCached()
    local dirtyEv = EmberRoutesJava.getDirtyEventCount()
    local awakeTickers = EmberRoutesJava.getAwakeTickerCount and EmberRoutesJava.getAwakeTickerCount() or 0

    if #list == 0 then
        if not self.storeDirty
            and not self.viewDirty
            and self.cachedSettlementId == nil
            and self.cachedDirtyEvents == dirtyEv
            and self.cachedAwakeTickers == awakeTickers
        then
            return
        end
        self.storeDirty = false
        self.viewDirty = false
        self.cachedSettlementId = nil
        self.cachedRevision = -1
        self.cachedDirtyEvents = dirtyEv
        self.cachedAwakeTickers = awakeTickers
        self.cachedFloorZ = pz

        self.lblHeader:setText(getText("IGUI_ER_EmptySettlements"))
        self.lblTerritory:setText(getText("IGUI_ER_EmptyHint"))
        self.lblStorage:setText("")
        self.lblPatch:setText(getText(
            "IGUI_ER_PatchIdleFmt",
            string.format("%.0f", dirtyEv),
            string.format("%.0f", awakeTickers)
        ))
        if self.loadMeter and self.loadMeter.setFraction then
            self.loadMeter:setFraction(0)
        end
        if self.btnEdit then self.btnEdit:setEnable(false) end
        if self.btnDelete then self.btnDelete:setEnable(false) end
        if self.prevBtn then self.prevBtn:setEnable(false) end
        if self.nextBtn then self.nextBtn:setEnable(false) end
        self.cachedRows = {}
        CachedList.reconcile(self.itemList, self.cachedRows, rowCaption, rowKey, true, -1)
        return
    end

    if self.selectedIdx < 1 or self.selectedIdx > #list then
        self.selectedIdx = 1
    end
    local s = list[self.selectedIdx]
    local rev = EmberRoutesJava.getWarehouseRevision(s.id)

    if not self.storeDirty
        and not self.viewDirty
        and self.cachedSettlementId == s.id
        and self.cachedRevision == rev
        and self.cachedDirtyEvents == dirtyEv
        and self.cachedAwakeTickers == awakeTickers
        and self.cachedFloorZ == pz
    then
        return
    end

    self.storeDirty = false
    self.viewDirty = false
    self.cachedSettlementId = s.id
    self.cachedRevision = rev
    self.cachedDirtyEvents = dirtyEv
    self.cachedAwakeTickers = awakeTickers
    self.cachedFloorZ = pz

    if self.btnEdit then self.btnEdit:setEnable(true) end
    if self.btnDelete then self.btnDelete:setEnable(true) end
    if self.prevBtn then self.prevBtn:setEnable(#list > 1) end
    if self.nextBtn then self.nextBtn:setEnable(#list > 1) end

    local tiles = Geometry.totalTiles(s.areas)
    local enc = EmberRoutesJava.inspectAreasEnclosure(SettlementStore.toFlatRects(s.areas), pz)
    local enclosedTiles = enc and enc.enclosed or 0
    local roofedTiles = enc and enc.roofed or 0

    self.lblHeader:setText(string.format("[%d/%d] %s (%s)", self.selectedIdx, #list, s.name, s.id))
    self.lblTerritory:setText(getText(
        "IGUI_ER_TerritoryFmt",
        tostring(tiles),
        tostring(pz),
        tostring(enclosedTiles),
        tostring(roofedTiles)
    ))

    local snap = EmberRoutesJava.getWarehouseSnapshot(s.id)
    local counts = snap and snap.counts
    local loaded = counts and counts.loaded or 0
    local unloaded = counts and counts.unloaded or 0
    local privCount = counts and counts.privateCount or 0
    local items = counts and counts.items or 0
    local usedW = counts and counts.usedWeight or 0
    local totalCap = counts and counts.totalCapacity or 0

    self.lblStorage:setText(getText(
        "IGUI_ER_StorageSummaryFmt",
        string.format("%.0f", loaded),
        string.format("%.0f", unloaded),
        string.format("%.0f", privCount),
        string.format("%.0f", items),
        string.format("%.1f", usedW),
        string.format("%.1f", totalCap)
    ))
    self.lblPatch:setText(getText(
        "IGUI_ER_PatchStatsFmt",
        string.format("%.0f", rev),
        string.format("%.0f", dirtyEv),
        string.format("%.0f", awakeTickers)
    ))
    if self.loadMeter and self.loadMeter.setFraction then
        local frac = totalCap > 0 and math.max(0, math.min(1, usedW / totalCap)) or 0
        self.loadMeter:setFraction(frac)
    end

    local nextRows = {}
    local rows = snap and snap.rows
    if rows then
        local sm = getScriptManager and getScriptManager()
        local rowLen = #rows
        for i = 1, rowLen do
            local r = rows[i]
            if r then
                local scriptItem = sm and sm:FindItem(r.fullType)
                local dispName = scriptItem and scriptItem:getDisplayName() or r.fullType
                local srcCount = r.sources and #r.sources or 0
                local totalCnt = r.count or 0
                local degradedCnt = r.degradedCount or 0
                local usableCnt = r.usableCount or (totalCnt - degradedCnt)
                local leftText = string.format("[%s] %s", tostring(r.node or "other"), dispName)
                local rightText
                if degradedCnt > 0 then
                    rightText = getText(
                        "IGUI_ER_RowCountDegradedFmt",
                        string.format("%.0f", totalCnt),
                        string.format("%.0f", usableCnt),
                        string.format("%.0f", degradedCnt),
                        tostring(srcCount)
                    )
                else
                    rightText = getText(
                        "IGUI_ER_RowCountFmt",
                        string.format("%.0f", totalCnt),
                        tostring(srcCount)
                    )
                end
                nextRows[#nextRows + 1] = {
                    key = tostring(r.fullType),
                    leftText = leftText,
                    rightText = rightText,
                    caption = leftText .. " " .. rightText,
                    hasDegraded = degradedCnt > 0,
                }
            end
        end
    end
    self.cachedRows = nextRows
    CachedList.reconcile(self.itemList, self.cachedRows, rowCaption, rowKey, true, rev)
end

function DebugWarehousePanel.open(selectSettlementId)
    if instance and instance.win and not instance.win.disposed then
        instance.win:addToUIManager()
        instance.win:setVisible(true)
        instance.win:bringToTop()
        if selectSettlementId then
            instance:selectBySettlementId(selectSettlementId)
        else
            instance.viewDirty = true
        end
        return instance
    end

    local scope = Kernel.scope("DebugWarehousePanel")

    local ctrl = setmetatable({
        selectedIdx = 1,
        scope = scope,
        storeDirty = true,
        viewDirty = true,
        cachedList = nil,
        cachedRows = {},
        cachedSettlementId = nil,
        cachedRevision = -1,
        cachedDirtyEvents = -1,
        cachedAwakeTickers = -1,
        cachedFloorZ = 0,
        fontHeight = 14,
        pad = 6,
    }, PanelCtrl)

    scope:subscribe(SettlementStore, function()
        if not ctrl.win or ctrl.win.disposed then return end
        ctrl.storeDirty = true
    end)
    scope:defer(function()
        if instance == ctrl then
            instance = nil
        end
    end)

    local w, h = 516, 460
    local x = math.max(20, getCore():getScreenWidth() - w - 28)
    local y = 52
    local win = EmberUI.WindowHost:new(x, y, w, h, {
        title = getText("IGUI_ER_PanelTitle"),
        resizable = false,
        closeOnEscape = true,
        hostProps = { direction = "column", padding = 12, gap = 8 },
    })
    win:initialise()
    win:instantiate()
    win:addToUIManager()
    EmberUI.PageLifecycle.attachExternalScope(win, scope)

    ctrl.win = win
    function ctrl:setX(nx) self.win:setX(nx) end
    function ctrl:setY(ny) self.win:setY(ny) end
    ctrl.close = function() win:close() end

    ctrl.prevBtn = UI.Button("<", ctrl, PanelCtrl.onPrevSettlement, { w = 28, h = 24 })
    ctrl.lblHeader = UI.Label("", { h = 24, font = UIFont.Medium, color = Theme.warning, truncate = true })
    ctrl.nextBtn = UI.Button(">", ctrl, PanelCtrl.onNextSettlement, { w = 28, h = 24 })

    ctrl.btnCreate = UI.Button(getText("IGUI_ER_BtnNewSettlement"), ctrl, PanelCtrl.onCreateSettlement, { h = 24, primary = true })
    ctrl.btnEdit = UI.Button(getText("IGUI_ER_BtnEditZone"), ctrl, PanelCtrl.onEditSettlement, { h = 24 })
    ctrl.btnRescan = UI.Button(getText("IGUI_ER_BtnRescan"), ctrl, PanelCtrl.onForceRemount, { h = 24 })
    ctrl.btnDelete = UI.Button(getText("IGUI_ER_BtnDeleteSettlement"), ctrl, PanelCtrl.onDeleteSettlement, { h = 24 })

    ctrl.lblTerritory = UI.Label("", { h = 18, truncate = true })
    ctrl.lblStorage = UI.Label("", { h = 18, color = Theme.success, truncate = true })
    ctrl.lblPatch = UI.Label("", { h = 18, color = Theme.accent, truncate = true })
    ctrl.loadMeter = UI.StatMeter(0, { h = 10 })

    ctrl.itemList = UI.List({
        owner = ctrl,
        itemheight = 24,
        style = WAREHOUSE_LIST_STYLE,
        well = true,
    })

    local spec = UI.Column({ padding = 12, gap = 6 }, {
        UI.Item(UI.Row({ gap = 8, align = "center" }, {
            UI.Item(ctrl.prevBtn, { basis = 28, grow = 0 }),
            UI.Item(ctrl.lblHeader, { grow = 1 }),
            UI.Item(ctrl.nextBtn, { basis = 28, grow = 0 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(ctrl.btnCreate, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnEdit, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnRescan, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnDelete, { basis = 0, grow = 1 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(ctrl.lblTerritory, { basis = 18, grow = 0 }),
        UI.Item(ctrl.lblStorage, { basis = 18, grow = 0 }),
        UI.Item(ctrl.lblPatch, { basis = 18, grow = 0 }),
        UI.Item(ctrl.loadMeter, { basis = 10, grow = 0 }),
        UI.Item(ctrl.itemList, { grow = 1 }),
    })
    win:setContentSpec(spec)

    local basePrerender = EmberUI.WindowHost.prerender
    win.prerender = function(self)
        ctrl:projectIfDirty()
        basePrerender(self)
    end

    instance = ctrl
    if selectSettlementId then
        ctrl:selectBySettlementId(selectSettlementId)
    end
    ctrl:projectIfDirty()
    return ctrl
end

return DebugWarehousePanel
