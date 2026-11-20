local EmberUI = require "EmberUI"
local UI = EmberUI.UI
local Theme = EmberUI.Theme

local Geometry = require "EmberRoutes/Settlement/SettlementGeometry"
local SettlementStore = require "EmberRoutes/Settlement/SettlementStore"
local Kernel = require "EmberRoutes/Kernel"

local ZoneEditorPanel = {}
local activeEditor = nil

if Theme.registerModule then
    Theme.registerModule({
        id = "er.zone_editor",
        labelKey = "IGUI_ER_ThemeMod_ZoneEditor",
        label = "Zone Editor",
        order = 20,
        colors = {
            { key = "eraseActiveBg", labelKey = "IGUI_ER_ThemeCol_EraseActive", label = "Erase Mode Active Fill", default = "#852E2E" },
            { key = "summaryText",   labelKey = "IGUI_ER_ThemeCol_SummaryText", label = "Area Summary Text",      default = "success" },
        },
    })
end

local function isModalBlocking()
    if not UIManager then return false end
    if UIManager.isModalVisible and UIManager.isModalVisible() then
        return true
    end
    if UIManager.getModal then
        local modal = UIManager.getModal()
        if modal and modal:isVisible() then
            return true
        end
    end
    return false
end

local function isPointOverAnyVisibleWindow(mx, my)
    if type(mx) ~= "number" or type(my) ~= "number" or not UIManager or not UIManager.getUI then
        return false
    end
    local uiList = UIManager.getUI()
    if not uiList then return false end
    local tooltip = UIManager.getToolTip and UIManager.getToolTip() or nil
    for i = 0, uiList:size() - 1 do
        local el = uiList:get(i)
        if el and el ~= tooltip and el:isVisible() then
            if el.isPointOver then
                if el:isPointOver(mx, my) then
                    return true
                end
            else
                local ax, ay = el:getX(), el:getY()
                local aw, ah = el:getWidth(), el:getHeight()
                if mx >= ax and my >= ay and mx < ax + aw and my < ay + ah then
                    return true
                end
            end
        end
    end
    return false
end

function ZoneEditorPanel.getActive()
    return activeEditor
end

local EditorCtrl = {}
EditorCtrl.__index = EditorCtrl

function EditorCtrl:isAlive()
    if self.disposed then return false end
    if self.scope and self.scope:isDisposed() then return false end
    if not self.win or self.win.disposed then return false end
    if self.win.getIsVisible and not self.win:getIsVisible() then return false end
    return true
end

function EditorCtrl:clearDragState()
    self.dragging = false
    self._mouseInitiatedDrag = false
    self.anchorX = nil
    self.anchorY = nil
    self.dragRect = nil
    self.dragConflict = nil
end

function EditorCtrl:pickMouseTile(mx, my)
    local actor = getSpecificPlayer(0)
    if not actor then return nil, nil, 0 end
    local z = math.floor(actor:getZ())
    local sx = mx or getMouseX()
    local sy = my or getMouseY()
    local gx = math.floor(screenToIsoX(0, sx, sy, z))
    local gy = math.floor(screenToIsoY(0, sx, sy, z))
    return gx, gy, z
end

function EditorCtrl:getEditingId()
    return self.editingSettlement and self.editingSettlement.id or nil
end

function EditorCtrl:findRectConflict(rect)
    if self.mode ~= "add" or not rect then return nil end
    return SettlementStore.findOverlappingSettlement({ rect }, self:getEditingId())
end

function EditorCtrl:updateHoverConflict()
    if not self.dragging and self.cursorValid == true then
        local hoverRect = Geometry.rectangle(self.cursorX, self.cursorY, self.cursorX, self.cursorY)
        self.cursorConflict = self:findRectConflict(hoverRect)
    else
        self.cursorConflict = nil
    end
end

function EditorCtrl:computePreviewAreas()
    if self.dragging and self.dragRect then
        if self.mode == "add" then
            if self.dragConflict then
                return self.areas
            end
            return Geometry.addToList(self.areas, self.dragRect)
        else
            return Geometry.subtractFromList(self.areas, self.dragRect)
        end
    end
    return self.areas
end

--- 仅在网格选点/模式/撤销变更时向 Java TerritoryRenderer 推送一次（渲染帧零 Lua 调用）。
function EditorCtrl:pushEditorRenderState()
    if self.disposed or (self.scope and self.scope:isDisposed()) or (self.win and self.win.disposed) then
        return
    end
    self.previewAreas = self:computePreviewAreas()
    self.previewTiles = Geometry.totalTiles(self.previewAreas)
    self.baseTiles = Geometry.totalTiles(self.areas)

    if not EmberRoutesJava or not EmberRoutesJava.syncZoneEditorState then return end

    local editId = self.editingSettlement and self.editingSettlement.id or ""
    local isConflict = (self.dragging and self.dragConflict ~= nil)
        or (not self.dragging and self.cursorValid == true and self.cursorConflict ~= nil)
    local modeInt = (self.mode == "erase" or isConflict) and 1 or 0
    local flatPreview = SettlementStore.toFlatRects(self.previewAreas)
    local hasDrag = self.dragging and self.dragRect ~= nil
    local dx, dy, dw, dh = 0, 0, 0, 0
    if hasDrag then
        dx = self.dragRect.x
        dy = self.dragRect.y
        dw = self.dragRect.w
        dh = self.dragRect.h
    end
    local hasCursor = (not hasDrag) and (self.cursorValid == true)
    EmberRoutesJava.syncZoneEditorState(
        editId,
        modeInt,
        flatPreview,
        hasDrag, dx, dy, dw, dh,
        hasCursor, self.cursorX or 0, self.cursorY or 0
    )
end

function EditorCtrl:refreshButtonStyles()
    if self.btnAddMode then
        Theme.button(self.btnAddMode, self.mode == "add", self.mode == "add")
    end
    if self.btnEraseMode then
        Theme.button(self.btnEraseMode, false, self.mode == "erase")
        if self.mode == "erase" then
            self.btnEraseMode._emberCustomBg = true
            self.btnEraseMode.backgroundColor = Theme.getModuleColor("er.zone_editor", "eraseActiveBg")
        else
            self.btnEraseMode._emberCustomBg = false
        end
    end
    if self.btnUndo then self.btnUndo:setEnable(#self.history > 0) end
    if self.btnClear then self.btnClear:setEnable(#self.areas > 0) end
end

function EditorCtrl:onModeAdd()
    self.mode = "add"
    self.errorMsg = nil
    self.dragConflict = self:findRectConflict(self.dragRect)
    self:updateHoverConflict()
    self:refreshButtonStyles()
    self:pushEditorRenderState()
    self.statusDirty = true
end

function EditorCtrl:onModeErase()
    self.mode = "erase"
    self.errorMsg = nil
    self.dragConflict = nil
    self.cursorConflict = nil
    self:refreshButtonStyles()
    self:pushEditorRenderState()
    self.statusDirty = true
end

function EditorCtrl:onUndo()
    if #self.history == 0 then return end
    self.areas = table.remove(self.history)
    self.errorMsg = nil
    self:refreshButtonStyles()
    self:pushEditorRenderState()
    self.statusDirty = true
end

function EditorCtrl:onClear()
    if #self.areas == 0 then return end
    self.history[#self.history + 1] = Geometry.cloneAreas(self.areas)
    self.areas = {}
    self.errorMsg = nil
    self:refreshButtonStyles()
    self:pushEditorRenderState()
    self.statusDirty = true
end

function EditorCtrl:finishDrag()
    if self.dragging and self.dragRect then
        if self.dragConflict then
            self.errorMsg = getText("IGUI_ER_EditorOverlapFmt", tostring(self.dragConflict.name))
        else
            if #self.history >= 16 then
                table.remove(self.history, 1)
            end
            self.history[#self.history + 1] = Geometry.cloneAreas(self.areas)
            self.areas = self:computePreviewAreas()
            self.errorMsg = nil
            self:refreshButtonStyles()
        end
    end
    self:clearDragState()
    self:updateHoverConflict()
    self:pushEditorRenderState()
    self.statusDirty = true
end

function EditorCtrl:onWorldMouseDown(mx, my)
    if not self:isAlive() or (self.win and self.win.moving) or isModalBlocking() then
        return
    end
    local sx = mx or getMouseX()
    local sy = my or getMouseY()
    if isPointOverAnyVisibleWindow(sx, sy) then
        return
    end
    local gx, gy, gz = self:pickMouseTile(sx, sy)
    if not gx then return end

    local startRect = Geometry.rectangle(gx, gy, gx, gy)
    local conflict = self:findRectConflict(startRect)
    if conflict then
        self.cursorX = gx
        self.cursorY = gy
        self.cursorValid = true
        self.previewZ = gz
        self.cursorConflict = conflict
        self.errorMsg = getText("IGUI_ER_EditorOverlapFmt", tostring(conflict.name))
        self:pushEditorRenderState()
        self.statusDirty = true
        return
    end

    self.anchorX = gx
    self.anchorY = gy
    self.cursorX = gx
    self.cursorY = gy
    self.cursorValid = true
    self.cursorConflict = nil
    self.previewZ = gz
    self.dragging = true
    self._mouseInitiatedDrag = true
    self.dragRect = startRect
    self.dragConflict = nil
    self.errorMsg = nil
    self:pushEditorRenderState()
    self.statusDirty = true
end

function EditorCtrl:onWorldMouseMove(mx, my)
    if not self:isAlive() or (self.win and self.win.moving) then
        return
    end
    local sx = mx or getMouseX()
    local sy = my or getMouseY()
    if self.dragging and self._mouseInitiatedDrag and type(isMouseButtonDown) == "function" and not isMouseButtonDown(0) then
        self:finishDrag()
        return
    end
    local overWindow = (not self.dragging) and (isModalBlocking() or isPointOverAnyVisibleWindow(sx, sy))
    self._lastUiCheckX = sx
    self._lastUiCheckY = sy
    local gx, gy, gz = self:pickMouseTile(sx, sy)
    if not gx then return end
    local validNow = not overWindow
    if gx ~= self.cursorX or gy ~= self.cursorY or gz ~= self.previewZ or validNow ~= self.cursorValid then
        self.cursorX = gx
        self.cursorY = gy
        self.cursorValid = validNow
        self.previewZ = gz
        if self.dragging and self.anchorX then
            local rect = Geometry.rectangle(self.anchorX, self.anchorY, gx, gy)
            if rect then
                self.dragRect = rect
                self.dragConflict = self:findRectConflict(rect)
            end
            self.errorMsg = nil
        else
            self:updateHoverConflict()
            if validNow and not self.cursorConflict then
                self.errorMsg = nil
            end
        end
        self:pushEditorRenderState()
        self.statusDirty = true
    end
end

function EditorCtrl:onWorldMouseUp()
    if not self:isAlive() then return end
    if self.dragging then
        self:finishDrag()
    end
end

function EditorCtrl:syncUiGuardTick()
    if not self:isAlive() then return end
    if self.dragging and self._mouseInitiatedDrag and type(isMouseButtonDown) == "function" and not isMouseButtonDown(0) then
        self:finishDrag()
        return
    end
    if not self.dragging and self.cursorValid and type(getMouseX) == "function" and type(getMouseY) == "function" then
        local mx, my = getMouseX(), getMouseY()
        if mx ~= self._lastUiCheckX or my ~= self._lastUiCheckY then
            self._lastUiCheckX = mx
            self._lastUiCheckY = my
            if isModalBlocking() or isPointOverAnyVisibleWindow(mx, my) then
                self.cursorValid = false
                self.cursorConflict = nil
                self:pushEditorRenderState()
                self.statusDirty = true
            end
        end
    end
end

function EditorCtrl:onSave()
    if self.dragging then
        self:clearDragState()
        self:updateHoverConflict()
        self:pushEditorRenderState()
    end
    local name = self.nameEntry and self.nameEntry:getText() or ""
    local targetId = self:getEditingId()
    local saved, err = SettlementStore.commitSettlement(targetId, name, self.areas)
    if not saved then
        if err == "TERRITORY_OVERLAP" then
            local conflict = SettlementStore.findOverlappingSettlement(self.areas, targetId)
            if conflict then
                self.errorMsg = getText("IGUI_ER_EditorOverlapFmt", tostring(conflict.name))
            else
                self.errorMsg = getText("IGUI_ER_MsgRejectedPrefix") .. tostring(err)
            end
        else
            self.errorMsg = getText("IGUI_ER_MsgRejectedPrefix") .. tostring(err)
        end
        self.statusDirty = true
        return
    end
    local onSaved = self.onSavedCallback
    if self.win then
        self.win:close()
    end
    if onSaved then
        onSaved(saved)
    end
end

function EditorCtrl:onCancel()
    if self.win then
        self.win:close()
    end
end

function EditorCtrl:projectStatusIfDirty()
    if not self.statusDirty then return end
    self.statusDirty = false

    if self.errorMsg then
        self.lineHint = self.errorMsg
        self.hintIsError = true
    elseif self.dragging and self.dragRect then
        if self.dragConflict then
            self.lineHint = getText("IGUI_ER_EditorOverlapFmt", tostring(self.dragConflict.name))
            self.hintIsError = true
        else
            local r = self.dragRect
            local deltaTiles = math.abs(self.previewTiles - self.baseTiles)
            if self.mode == "add" then
                self.lineHint = getText(
                    "IGUI_ER_EditorAddingFmt",
                    tostring(r.w),
                    tostring(r.h),
                    tostring(deltaTiles),
                    tostring(self.previewTiles)
                )
            else
                self.lineHint = getText(
                    "IGUI_ER_EditorErasingFmt",
                    tostring(r.w),
                    tostring(r.h),
                    tostring(deltaTiles),
                    tostring(self.previewTiles)
                )
            end
            self.hintIsError = false
        end
    elseif self.cursorValid and self.cursorConflict then
        self.lineHint = getText("IGUI_ER_EditorOverlapFmt", tostring(self.cursorConflict.name))
        self.hintIsError = true
    else
        self.lineHint = getText(
            "IGUI_ER_EditorHoverFmt",
            tostring(self.cursorX or 0),
            tostring(self.cursorY or 0),
            tostring(self.previewZ or 0)
        )
        self.hintIsError = false
    end

    self.lineSummary = getText("IGUI_ER_EditorSummaryFmt", tostring(self.previewTiles or 0))
    if self.lblHint then
        self.lblHint:setText(self.lineHint)
        self.lblHint:setColor(self.hintIsError and Theme.danger or Theme.textColor)
    end
    if self.lblSummary then
        self.lblSummary:setText(self.lineSummary)
        self.lblSummary:setColor(Theme.getModuleColor("er.zone_editor", "summaryText"))
    end
end

function ZoneEditorPanel.open(editingSettlement, onSavedCallback)
    if activeEditor and activeEditor.win then
        activeEditor.win:close()
    end

    local scope = Kernel.scope("ZoneEditorPanel")
    scope:acquireGlobalFlag(ISWorldObjectContextMenu, "disableWorldMenu", true)

    local ctrl = setmetatable({
        editingSettlement = editingSettlement,
        onSavedCallback = onSavedCallback,
        scope = scope,
        disposed = false,
        mode = "add",
        areas = editingSettlement and Geometry.cloneAreas(editingSettlement.areas) or {},
        history = {},
        dragging = false,
        _mouseInitiatedDrag = false,
        dragRect = nil,
        dragConflict = nil,
        cursorX = 0,
        cursorY = 0,
        cursorValid = false,
        cursorConflict = nil,
        _lastUiCheckX = nil,
        _lastUiCheckY = nil,
        previewZ = 0,
        statusDirty = true,
        lineHint = "",
        lineSummary = "",
        hintIsError = false,
    }, EditorCtrl)

    scope:defer(function()
        ctrl.disposed = true
        ctrl:clearDragState()
        ctrl.cursorValid = false
        ctrl.cursorConflict = nil
        if EmberRoutesJava and EmberRoutesJava.clearZoneEditorState then
            EmberRoutesJava.clearZoneEditorState()
        end
        if activeEditor == ctrl then
            activeEditor = nil
        end
    end)

    local winTitle = editingSettlement
        and getText("IGUI_ER_EditorTitleEdit", editingSettlement.name)
        or getText("IGUI_ER_EditorTitleCreate")

    local w, h = 420, 214
    local win = EmberUI.WindowHost:new(24, 60, w, h, {
        title = winTitle,
        moduleId = "er.zone_editor",
        resizable = false,
        closeOnEscape = true,
        hostProps = { direction = "column", padding = 10, gap = 6 },
    })
    win:initialise()
    win:instantiate()
    win:addToUIManager()
    EmberUI.PageLifecycle.attachExternalScope(win, scope)

    ctrl.win = win
    function ctrl:setX(nx) self.win:setX(nx) end
    function ctrl:setY(ny) self.win:setY(ny) end
    ctrl.onModeAdd = function(_, ...) EditorCtrl.onModeAdd(ctrl) end
    ctrl.onModeErase = function(_, ...) EditorCtrl.onModeErase(ctrl) end
    ctrl.onUndo = function(_, ...) EditorCtrl.onUndo(ctrl) end
    ctrl.onClear = function(_, ...) EditorCtrl.onClear(ctrl) end
    ctrl.onSave = function(_, ...) EditorCtrl.onSave(ctrl) end
    ctrl.close = function() win:close() end

    local defaultName = editingSettlement and editingSettlement.name
        or getText("IGUI_ER_DefaultSettlementName", tostring(#SettlementStore.list() + 1))

    ctrl.nameEntry = UI.TextInput(defaultName, ctrl, nil, { h = 24 })
    ctrl.btnAddMode = UI.Button(getText("IGUI_ER_ToolAdd"), ctrl, EditorCtrl.onModeAdd, { h = 24 })
    ctrl.btnEraseMode = UI.Button(getText("IGUI_ER_ToolErase"), ctrl, EditorCtrl.onModeErase, { h = 24 })
    ctrl.btnUndo = UI.Button(getText("IGUI_ER_ToolUndo"), ctrl, EditorCtrl.onUndo, { h = 24 })
    ctrl.btnClear = UI.Button(getText("IGUI_ER_ToolClear"), ctrl, EditorCtrl.onClear, { h = 24 })
    ctrl.lblHint = UI.Label("", { h = 18, truncate = true })
    ctrl.lblSummary = UI.Label("", { h = 18, color = Theme.getModuleColor("er.zone_editor", "summaryText"), truncate = true })
    ctrl.btnSave = UI.Button(getText("IGUI_ER_BtnSaveZone"), ctrl, EditorCtrl.onSave, { h = 26, primary = true, active = true })
    ctrl.btnCancel = UI.Button(getText("IGUI_ER_BtnCancelZone"), ctrl, EditorCtrl.onCancel, { h = 26 })

    local spec = UI.Column({ padding = 10, gap = 6 }, {
        UI.Item(ctrl.nameEntry, { basis = 24, grow = 0 }),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(ctrl.btnAddMode, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnEraseMode, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnUndo, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnClear, { basis = 0, grow = 1 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(ctrl.lblHint, { basis = 18, grow = 0 }),
        UI.Item(ctrl.lblSummary, { basis = 18, grow = 0 }),
        UI.Spacer(1),
        UI.Item(UI.Row({ gap = 8 }, {
            UI.Item(ctrl.btnSave, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnCancel, { basis = 0, grow = 1 }),
        }), { basis = 26, grow = 0 }),
    })
    win:setContentSpec(spec)

    if Events and Events.OnMouseDown then
        scope:on(Events.OnMouseDown, function(mx, my)
            ctrl:onWorldMouseDown(mx, my)
        end)
    end
    if Events and Events.OnMouseMove then
        scope:on(Events.OnMouseMove, function(mx, my)
            ctrl:onWorldMouseMove(mx, my)
        end)
    end
    if Events and Events.OnMouseUp then
        scope:on(Events.OnMouseUp, function()
            ctrl:onWorldMouseUp()
        end)
    end

    local baseUpdate = win.update or (EmberUI.WindowHost and EmberUI.WindowHost.update)
    win.update = function(self)
        if type(baseUpdate) == "function" then
            baseUpdate(self)
        end
        ctrl:syncUiGuardTick()
    end

    local basePrerender = EmberUI.WindowHost and EmberUI.WindowHost.prerender
    win.prerender = function(self)
        ctrl:projectStatusIfDirty()
        if type(basePrerender) == "function" then
            basePrerender(self)
        end
    end

    ctrl:refreshButtonStyles()
    ctrl:pushEditorRenderState()
    ctrl:projectStatusIfDirty()

    activeEditor = ctrl
    return ctrl
end

return ZoneEditorPanel
