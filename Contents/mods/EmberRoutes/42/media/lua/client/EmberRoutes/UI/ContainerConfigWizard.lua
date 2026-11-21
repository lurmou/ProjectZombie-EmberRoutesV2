local EmberUI = require "EmberUI"
local UI = EmberUI.UI
local Theme = EmberUI.Theme
local CachedList = require "EmberUI/Widgets/CachedList"
local Text = require "EmberUI/Core/Text"

local Kernel = require "EmberRoutes/Kernel"
local CategoryCatalog = require "EmberRoutes/UI/CategoryCatalog"

local ContainerConfigWizard = {}
local activeWizard = nil
local copiedRuleBuffer = nil

local COLOR_ALLOW = { r = 0.36, g = 0.90, b = 0.58, a = 1.0 }
local COLOR_DENY = { r = 0.96, g = 0.42, b = 0.38, a = 1.0 }
local ROLE_IDS = { "STORAGE", "BUFFER_IN", "PICKUP_OUT" }

local rowKey = CategoryCatalog.rowKey
local rowCaption = CategoryCatalog.rowCaption

local function cloneSet(src)
    local out = {}
    if type(src) == "table" then
        for k, v in pairs(src) do
            if v == true then
                out[k] = true
            end
        end
    end
    return out
end

local function cloneRuleState(src)
    return {
        role = src.role or "STORAGE",
        priority = src.priority or 3,
        enabled = src.enabled ~= false,
        isPrivate = src.isPrivate == true,
        allowGroups = cloneSet(src.allowGroups),
        denyGroups = cloneSet(src.denyGroups),
        allowTypes = cloneSet(src.allowTypes),
        denyTypes = cloneSet(src.denyTypes),
    }
end

local function decodeRuleTable(raw)
    raw = raw or {}
    return {
        role = raw.role or "STORAGE",
        priority = math.max(1, math.min(5, tonumber(raw.priority) or 3)),
        enabled = raw.enabled ~= false,
        isPrivate = raw.isPrivate == true,
        allowGroups = CategoryCatalog.parseCsvSet(raw.allowGroups),
        denyGroups = CategoryCatalog.parseCsvSet(raw.denyGroups),
        allowTypes = CategoryCatalog.parseCsvSet(raw.allowTypes),
        denyTypes = CategoryCatalog.parseCsvSet(raw.denyTypes),
    }
end

local function encodeRuleTable(rule)
    return {
        role = rule.role or "STORAGE",
        priority = rule.priority or 3,
        enabled = rule.enabled ~= false,
        isPrivate = rule.isPrivate == true,
        allowGroups = CategoryCatalog.joinCsvSet(rule.allowGroups),
        denyGroups = CategoryCatalog.joinCsvSet(rule.denyGroups),
        allowTypes = CategoryCatalog.joinCsvSet(rule.allowTypes),
        denyTypes = CategoryCatalog.joinCsvSet(rule.denyTypes),
    }
end

local function applyButtonActiveState(btn, isActive)
    if not btn then return end
    btn.isActive = isActive == true
    btn._emberActive = isActive == true
    Theme.button(btn, isActive == true, isActive == true)
end

local TREE_LIST_STYLE = {
    prepare = function(_list, row, _label)
        return {
            height = 24,
            indent = row.isChild and 20 or 4,
            hasChildren = row.hasChildren == true,
            expanded = row.expanded == true,
            state = row.state or "neutral",
            stateMark = row.stateMark or "[ ]",
            title = row.title or "",
        }
    end,
    draw = function(list, y, entry)
        local v, nextY = CategoryCatalog.beginListRowDraw(list, y, entry, 24, 0.25)
        if not v then return nextY end
        local x = 6 + (v.indent or 0)
        if not entry.item.value.isChild then
            local arrow = v.hasChildren and (v.expanded and "v " or "> ") or "  "
            Theme.text(list, arrow, x, y + 4, Theme.muted, UIFont.Small)
        end
        local markX = 24 + (v.indent or 0)
        local markCol = Theme.muted
        if v.state == "allow" then
            markCol = COLOR_ALLOW
        elseif v.state == "deny" then
            markCol = COLOR_DENY
        end
        Theme.text(list, v.stateMark, markX, y + 4, markCol, UIFont.Small)
        local textX = markX + 26
        local availW = math.max(24, list.width - textX - 16)
        local clipped = Text.truncate(v.title, availW, UIFont.Small)
        Theme.text(list, clipped, textX, y + 4, Theme.textColor, UIFont.Small)
        return nextY
    end,
}

local SAMPLE_LIST_STYLE = {
    prepare = function(_list, row, _label)
        return {
            height = 22,
            leftText = row.leftText or "",
            rightText = row.rightText or "",
            state = row.state or "neutral",
            tooltip = row.tooltip,
        }
    end,
    draw = function(list, y, entry)
        local v, nextY = CategoryCatalog.beginListRowDraw(list, y, entry, 22, 0)
        if not v then return nextY end
        local col = Theme.textColor
        if v.state == "allow" then col = COLOR_ALLOW
        elseif v.state == "deny" then col = COLOR_DENY end
        local rightCol = v.state == "neutral" and Theme.muted or col
        local rw = 156
        local clipped = Text.truncate(v.leftText, math.max(30, list.width - rw - 24), UIFont.Small)
        Theme.text(list, clipped, 6, y + 3, col, UIFont.Small)
        list:drawTextRight(v.rightText, list.width - 16, y + 3, rightCol.r, rightCol.g, rightCol.b, 1.0, UIFont.Small)
        return nextY
    end,
}

local WizCtrl = {}
WizCtrl.__index = WizCtrl

function WizCtrl:syncHighlight()
    if not EmberRoutesJava then return end
    if self.highlightEnabled and self.currentMeta and EmberRoutesJava.setContainerHighlight then
        EmberRoutesJava.setContainerHighlight(
            self.currentMeta.x or 0,
            self.currentMeta.y or 0,
            self.currentMeta.z or 0
        )
    elseif EmberRoutesJava.clearContainerHighlight then
        EmberRoutesJava.clearContainerHighlight()
    end
end

function WizCtrl:loadContainerList(selectContainerId)
    self.containers = {}
    if self.settlementId and EmberRoutesJava and EmberRoutesJava.listSettlementContainers then
        self.containers = EmberRoutesJava.listSettlementContainers(self.settlementId) or {}
    end
    self.selectedIdx = 1
    if selectContainerId then
        for i = 1, #self.containers do
            if self.containers[i].containerId == selectContainerId then
                self.selectedIdx = i
                break
            end
        end
    end
end

function WizCtrl:loadCurrentRuleAndSamples()
    local meta = nil
    local rawRule = nil
    local samples = {}

    if self.targetObject and EmberRoutesJava then
        local cid = self.targetContainerId
        if not cid and EmberRoutesJava.resolveObjectContainerId then
            cid = EmberRoutesJava.resolveObjectContainerId(self.targetObject)
            self.targetContainerId = cid
        end
        if cid and #self.containers > 0 then
            for i = 1, #self.containers do
                if self.containers[i].containerId == cid then
                    self.selectedIdx = i
                    meta = self.containers[i]
                    break
                end
            end
        end
        if EmberRoutesJava.getContainerRule then
            rawRule = EmberRoutesJava.getContainerRule(self.targetObject)
        end
        if EmberRoutesJava.getContainerSampleItems then
            samples = EmberRoutesJava.getContainerSampleItems(self.targetObject) or {}
        end
        if not meta then
            local sq = self.targetObject.getSquare and self.targetObject:getSquare()
            meta = {
                containerId = cid,
                displayName = EmberRoutesJava.getStorageObjectDisplayName
                    and EmberRoutesJava.getStorageObjectDisplayName(self.targetObject) or "container",
                x = sq and sq:getX() or 0,
                y = sq and sq:getY() or 0,
                z = sq and sq:getZ() or 0,
                loaded = true,
            }
        end
    elseif #self.containers > 0 then
        if self.selectedIdx < 1 or self.selectedIdx > #self.containers then
            self.selectedIdx = 1
        end
        meta = self.containers[self.selectedIdx]
        self.targetContainerId = meta.containerId
        if EmberRoutesJava and EmberRoutesJava.getContainerRuleById then
            rawRule = EmberRoutesJava.getContainerRuleById(self.settlementId, meta.containerId)
        end
        if not rawRule then
            rawRule = meta
        end
        if EmberRoutesJava and EmberRoutesJava.getContainerSampleItemsById then
            samples = EmberRoutesJava.getContainerSampleItemsById(self.settlementId, meta.containerId) or {}
        end
    end

    self.currentMeta = meta
    self.rule = decodeRuleTable(rawRule or meta or {})
    self.sampleItems = samples or {}
    self.dirty = false
    self:syncControlsFromRule()
    self:refreshTreeRows()
    self:refreshSampleRows()
    self:syncHighlight()
end

function WizCtrl:syncControlsFromRule()
    local meta = self.currentMeta
    if meta then
        local stateStr = meta.loaded == false
            and getText("IGUI_ER_StatusOffline")
            or getText("IGUI_ER_StatusOnline")
        local countLabel = #self.containers > 1
            and string.format("[%d/%d] ", self.selectedIdx, #self.containers)
            or ""
        self.lblTitle:setText(string.format(
            "%s%s (%d, %d, Z=%d) | %s",
            countLabel,
            tostring(meta.displayName or "container"),
            math.floor(meta.x or 0),
            math.floor(meta.y or 0),
            math.floor(meta.z or 0),
            stateStr
        ))
    else
        self.lblTitle:setText(getText("IGUI_ER_Wiz_NoContainer"))
    end

    if self.prevBtn then self.prevBtn:setEnable(#self.containers > 1) end
    if self.nextBtn then self.nextBtn:setEnable(#self.containers > 1) end

    local r = self.rule
    for i = 1, #ROLE_IDS do
        local rid = ROLE_IDS[i]
        applyButtonActiveState(self.roleBtns[rid], r.role == rid)
    end
    self.lblRoleDesc:setText(CategoryCatalog.getRoleDesc(r.role))

    for p = 1, 5 do
        applyButtonActiveState(self.prioBtns[p], r.priority == p)
    end

    if self.swEnabled then self.swEnabled:setChecked(r.enabled == true, true) end
    if self.swPrivate then self.swPrivate:setChecked(r.isPrivate == true, true) end
    applyButtonActiveState(self.btnHighlight, self.highlightEnabled)
    if self.btnPaste then self.btnPaste:setEnable(copiedRuleBuffer ~= nil) end

    self.lblSummary:setText(getText(
        "IGUI_ER_Wiz_SummaryFmt",
        tostring(CategoryCatalog.countSet(r.allowGroups)),
        tostring(CategoryCatalog.countSet(r.denyGroups)),
        tostring(CategoryCatalog.countSet(r.allowTypes)),
        tostring(CategoryCatalog.countSet(r.denyTypes))
    ))
end

function WizCtrl:getNodeFilterState(nodeId)
    local r = self.rule
    if r.denyGroups[nodeId] then return "deny" end
    if r.allowGroups[nodeId] then return "allow" end
    return "neutral"
end

function WizCtrl:cycleNodeFilterState(nodeId)
    local r = self.rule
    local cur = self:getNodeFilterState(nodeId)
    r.allowGroups[nodeId] = nil
    r.denyGroups[nodeId] = nil
    if cur == "neutral" then
        r.allowGroups[nodeId] = true
    elseif cur == "allow" then
        r.denyGroups[nodeId] = true
    end
    self.dirty = true
    self:syncControlsFromRule()
    self:refreshTreeRows()
end

function WizCtrl:refreshTreeRows()
    local q = self.searchQuery and string.lower(self.searchQuery) or ""
    local rows = {}
    self.treeRev = (self.treeRev or 0) + 1

    local function stateMarkFor(st)
        if st == "allow" then return "[+]"
        elseif st == "deny" then return "[X]"
        else return "[ ]" end
    end

    for i = 1, #CategoryCatalog.MAJORS do
        local maj = CategoryCatalog.MAJORS[i]
        local majLabel = CategoryCatalog.getLabel(maj.id)
        local majMatches = (q == "")
            or (string.find(string.lower(majLabel), q, 1, true) ~= nil)
            or (string.find(maj.id, q, 1, true) ~= nil)

        local matchingChildren = {}
        for j = 1, #maj.children do
            local cid = maj.children[j]
            local cLabel = CategoryCatalog.getLabel(cid)
            local childMatches = (q == "")
                or (string.find(string.lower(cLabel), q, 1, true) ~= nil)
                or (string.find(cid, q, 1, true) ~= nil)
            if childMatches then
                matchingChildren[#matchingChildren + 1] = { id = cid, label = cLabel }
            end
        end

        if majMatches or #matchingChildren > 0 then
            local expanded = (q ~= "" and #matchingChildren > 0) or (self.expandedMajors[maj.id] == true)
            local mst = self:getNodeFilterState(maj.id)
            rows[#rows + 1] = {
                key = maj.id,
                id = maj.id,
                isChild = false,
                hasChildren = #maj.children > 0,
                expanded = expanded,
                state = mst,
                stateMark = stateMarkFor(mst),
                title = majLabel,
                caption = majLabel,
            }
            if expanded then
                local childList = (q ~= "" and not majMatches) and matchingChildren or nil
                if childList then
                    for j = 1, #childList do
                        local ch = childList[j]
                        local cst = self:getNodeFilterState(ch.id)
                        rows[#rows + 1] = {
                            key = ch.id,
                            id = ch.id,
                            isChild = true,
                            hasChildren = false,
                            expanded = false,
                            state = cst,
                            stateMark = stateMarkFor(cst),
                            title = ch.label,
                            caption = ch.label,
                        }
                    end
                else
                    for j = 1, #maj.children do
                        local cid = maj.children[j]
                        local cLabel = CategoryCatalog.getLabel(cid)
                        local cst = self:getNodeFilterState(cid)
                        rows[#rows + 1] = {
                            key = cid,
                            id = cid,
                            isChild = true,
                            hasChildren = false,
                            expanded = false,
                            state = cst,
                            stateMark = stateMarkFor(cst),
                            title = cLabel,
                            caption = cLabel,
                        }
                    end
                end
            end
        end
    end

    self.treeRows = rows
    CachedList.reconcile(self.treeList, rows, rowCaption, rowKey, true, self.treeRev)
end

function WizCtrl:refreshSampleRows()
    local rows = {}
    local r = self.rule
    self.sampleRev = (self.sampleRev or 0) + 1
    for i = 1, #self.sampleItems do
        local s = self.sampleItems[i]
        local ftLower = string.lower(tostring(s.fullType or ""))
        local st = "neutral"
        local stLabel = CategoryCatalog.getLabel(s.node or "other")
        if r.denyTypes[ftLower] then
            st = "deny"
            stLabel = getText("IGUI_ER_Wiz_TagDenied") .. " | " .. stLabel
        elseif r.allowTypes[ftLower] then
            st = "allow"
            stLabel = getText("IGUI_ER_Wiz_TagAllowed") .. " | " .. stLabel
        end
        rows[#rows + 1] = {
            key = ftLower,
            fullType = s.fullType,
            ftLower = ftLower,
            leftText = string.format("%s x%d", tostring(s.displayName or s.fullType), math.floor(s.count or 1)),
            rightText = stLabel,
            state = st,
            caption = tostring(s.displayName or s.fullType),
            tooltip = tostring(s.fullType) .. "\n" .. stLabel,
        }
    end
    self.sampleRows = rows
    CachedList.reconcile(self.sampleList, rows, rowCaption, rowKey, true, self.sampleRev)
    if #rows == 0 then
        self.selectedSample = nil
    elseif not self.selectedSample then
        self.selectedSample = rows[1]
    else
        local found = nil
        for i = 1, #rows do
            if rows[i].ftLower == self.selectedSample.ftLower then
                found = rows[i]
                break
            end
        end
        self.selectedSample = found or rows[1]
    end
    local hasSample = self.selectedSample ~= nil
    if self.btnAllowType then self.btnAllowType:setEnable(hasSample) end
    if self.btnDenyType then self.btnDenyType:setEnable(hasSample) end
    if self.btnClearType then self.btnClearType:setEnable(hasSample) end
end

function WizCtrl:onPrevContainer()
    if #self.containers <= 1 then return end
    self.targetObject = nil
    self.selectedIdx = ((self.selectedIdx - 2) % #self.containers) + 1
    self:loadCurrentRuleAndSamples()
end

function WizCtrl:onNextContainer()
    if #self.containers <= 1 then return end
    self.targetObject = nil
    self.selectedIdx = (self.selectedIdx % #self.containers) + 1
    self:loadCurrentRuleAndSamples()
end

function WizCtrl:onToggleHighlight()
    self.highlightEnabled = not self.highlightEnabled
    self:syncControlsFromRule()
    self:syncHighlight()
end

function WizCtrl:onCopyRule()
    copiedRuleBuffer = cloneRuleState(self.rule)
    self:syncControlsFromRule()
end

function WizCtrl:onPasteRule()
    if not copiedRuleBuffer then return end
    self.rule = cloneRuleState(copiedRuleBuffer)
    self.dirty = true
    self:syncControlsFromRule()
    self:refreshTreeRows()
    self:refreshSampleRows()
end

function WizCtrl:onAutoZone()
    if not EmberRoutesJava then return end
    local res = nil
    if self.targetObject and EmberRoutesJava.autoConfigureContainer then
        res = EmberRoutesJava.autoConfigureContainer(self.targetObject)
    elseif self.targetContainerId and EmberRoutesJava.autoConfigureContainerById then
        res = EmberRoutesJava.autoConfigureContainerById(self.settlementId, self.targetContainerId)
    end
    if res then
        if EmberRoutesJava.flushDirtyContainers then EmberRoutesJava.flushDirtyContainers() end
        self:loadContainerList(self.targetContainerId)
        self:loadCurrentRuleAndSamples()
        if self.onSaved then self.onSaved() end
    end
end

function WizCtrl:onSaveRule()
    if not EmberRoutesJava then return end
    local encoded = encodeRuleTable(self.rule)
    local ok = false
    if self.targetObject and EmberRoutesJava.setContainerRule then
        ok = EmberRoutesJava.setContainerRule(self.targetObject, encoded)
    elseif self.targetContainerId and EmberRoutesJava.setContainerRuleById then
        ok = EmberRoutesJava.setContainerRuleById(self.settlementId, self.targetContainerId, encoded)
    end
    if ok then
        self.dirty = false
        self:loadContainerList(self.targetContainerId)
        self:loadCurrentRuleAndSamples()
        if self.onSaved then self.onSaved() end
    end
end

function WizCtrl:onClearRule()
    self.rule = decodeRuleTable({
        role = "STORAGE",
        priority = 3,
        enabled = true,
        isPrivate = false,
    })
    self.dirty = true
    self:syncControlsFromRule()
    self:refreshTreeRows()
    self:refreshSampleRows()
end

function WizCtrl:applySampleTypeMode(mode)
    if not self.selectedSample then return end
    local ft = self.selectedSample.ftLower
    if mode == "allow" then
        self.rule.denyTypes[ft] = nil
        self.rule.allowTypes[ft] = true
    elseif mode == "deny" then
        self.rule.allowTypes[ft] = nil
        self.rule.denyTypes[ft] = true
    else
        self.rule.allowTypes[ft] = nil
        self.rule.denyTypes[ft] = nil
    end
    self.dirty = true
    self:syncControlsFromRule()
    self:refreshSampleRows()
end

local function createWizardInternal(opts)
    if activeWizard and activeWizard.win and not activeWizard.win.disposed then
        activeWizard.settlementId = opts.settlementId
        activeWizard.targetContainerId = opts.containerId
        activeWizard.targetObject = opts.targetObject
        activeWizard.onSaved = opts.onSaved
        activeWizard.highlightEnabled = true
        activeWizard.win:addToUIManager()
        activeWizard.win:setVisible(true)
        activeWizard.win:bringToTop()
        activeWizard:loadContainerList(opts.containerId)
        activeWizard:loadCurrentRuleAndSamples()
        return activeWizard
    end

    local scope = Kernel.scope("ContainerConfigWizard")
    local ctrl = setmetatable({
        scope = scope,
        settlementId = opts.settlementId,
        targetContainerId = opts.containerId,
        targetObject = opts.targetObject,
        onSaved = opts.onSaved,
        containers = {},
        selectedIdx = 1,
        highlightEnabled = true,
        expandedMajors = { food = true, medical = true, weapons = true },
        searchQuery = "",
        rule = decodeRuleTable({}),
        sampleItems = {},
        treeRows = {},
        sampleRows = {},
        selectedSample = nil,
        roleBtns = {},
        prioBtns = {},
        treeRev = 0,
        sampleRev = 0,
        dirty = false,
    }, WizCtrl)

    scope:defer(function()
        if EmberRoutesJava and EmberRoutesJava.clearContainerHighlight then
            EmberRoutesJava.clearContainerHighlight()
        end
        if activeWizard == ctrl then
            activeWizard = nil
        end
    end)

    local win = CategoryCatalog.createScopedWindow(ctrl, scope, getText("IGUI_ER_Wiz_Title"), {
        width = 760,
        height = 500,
        minWidth = 680,
        minHeight = 450,
    })

    -- Top navigation + action bar
    ctrl.prevBtn = UI.Button("<", ctrl, WizCtrl.onPrevContainer, { w = 28, h = 24 })
    ctrl.lblTitle = UI.Label("", { h = 24, font = UIFont.Medium, color = Theme.accent, truncate = true })
    ctrl.nextBtn = UI.Button(">", ctrl, WizCtrl.onNextContainer, { w = 28, h = 24 })

    ctrl.btnHighlight = UI.Button(getText("IGUI_ER_Wiz_BtnLocate3D"), ctrl, WizCtrl.onToggleHighlight, { h = 24 })
    ctrl.btnCopy = UI.Button(getText("IGUI_ER_Wiz_BtnCopy"), ctrl, WizCtrl.onCopyRule, { h = 24 })
    ctrl.btnPaste = UI.Button(getText("IGUI_ER_Wiz_BtnPaste"), ctrl, WizCtrl.onPasteRule, { h = 24 })
    ctrl.btnAutoZone = UI.Button(getText("IGUI_ER_Wiz_BtnAutoZone"), ctrl, WizCtrl.onAutoZone, { h = 24, primary = true })

    -- Left pane controls
    for i = 1, #ROLE_IDS do
        local rid = ROLE_IDS[i]
        ctrl.roleBtns[rid] = UI.Button(CategoryCatalog.getRoleLabel(rid), ctrl, function(target)
            target.rule.role = rid
            target.dirty = true
            target:syncControlsFromRule()
        end, { h = 24 })
    end
    ctrl.lblRoleDesc = UI.Label("", { h = 34, wrap = true, maxLines = 2, muted = true })
    ctrl.swEnabled = UI.Switch(true, ctrl, function(target, checked)
        target.rule.enabled = checked == true
        target.dirty = true
        target:syncControlsFromRule()
    end, { h = 24, label = getText("IGUI_ER_Wiz_SwitchEnabled") })
    ctrl.swPrivate = UI.Switch(false, ctrl, function(target, checked)
        target.rule.isPrivate = checked == true
        target.dirty = true
        target:syncControlsFromRule()
    end, { h = 24, label = getText("IGUI_ER_Wiz_SwitchPrivate") })

    local prioSpecChildren = {}
    for p = 1, 5 do
        local pVal = p
        local pBtn = UI.Button("P" .. tostring(pVal), ctrl, function(target)
            target.rule.priority = pVal
            target.dirty = true
            target:syncControlsFromRule()
        end, { h = 24 })
        ctrl.prioBtns[pVal] = pBtn
        prioSpecChildren[#prioSpecChildren + 1] = UI.Item(pBtn, { basis = 0, grow = 1 })
    end

    ctrl.lblSummary = UI.Label("", { h = 18, muted = true, truncate = true })
    ctrl.btnSave = UI.Button(getText("IGUI_ER_Wiz_BtnSave"), ctrl, WizCtrl.onSaveRule, { h = 26, primary = true, active = true })
    ctrl.btnClear = UI.Button(getText("IGUI_ER_Wiz_BtnReset"), ctrl, WizCtrl.onClearRule, { h = 26 })

    local leftSpec = UI.Column({ padding = 6, gap = 6 }, {
        UI.Item(UI.Label(getText("IGUI_ER_Wiz_RoleSection"), { h = 18, color = Theme.accent }), { basis = 18, grow = 0 }),
        UI.Item(ctrl.roleBtns.STORAGE, { basis = 24, grow = 0 }),
        UI.Item(ctrl.roleBtns.BUFFER_IN, { basis = 24, grow = 0 }),
        UI.Item(ctrl.roleBtns.PICKUP_OUT, { basis = 24, grow = 0 }),
        UI.Item(ctrl.lblRoleDesc, { basis = 34, grow = 0 }),
        UI.Item(ctrl.swEnabled, { basis = 24, grow = 0 }),
        UI.Item(ctrl.swPrivate, { basis = 24, grow = 0 }),
        UI.Item(UI.Label(getText("IGUI_ER_Wiz_PriorityLabel"), { h = 18, color = Theme.accent }), { basis = 18, grow = 0 }),
        UI.Item(UI.Row({ gap = 4 }, prioSpecChildren), { basis = 24, grow = 0 }),
        UI.Item(ctrl.lblSummary, { basis = 18, grow = 0 }),
        UI.Spacer(1),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(ctrl.btnSave, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnClear, { basis = 0, grow = 1 }),
        }), { basis = 26, grow = 0 }),
    })

    -- Right pane controls
    ctrl.searchBox = UI.SearchInput(getText("IGUI_ER_Wiz_SearchPlaceholder"), ctrl, function(target, val)
        target.searchQuery = val or ""
        target:refreshTreeRows()
    end, { h = 24 })

    ctrl.treeList = UI.List({
        owner = ctrl,
        itemheight = 24,
        style = TREE_LIST_STYLE,
        well = true,
        emptyState = { title = getText("IGUI_ER_EmptySearch"), hint = "" },
        onSelect = function(target, row)
            if not row then return end
            local mx = target.treeList.getMouseX and target.treeList:getMouseX() or 999
            if not row.isChild and row.hasChildren and mx >= 0 and mx <= 24 then
                target.expandedMajors[row.id] = not (target.expandedMajors[row.id] == true)
                target:refreshTreeRows()
            else
                target:cycleNodeFilterState(row.id)
            end
        end,
    })

    ctrl.sampleList = UI.List({
        owner = ctrl,
        itemheight = 22,
        style = SAMPLE_LIST_STYLE,
        well = true,
        emptyState = { title = getText("IGUI_ER_Wiz_NoSampleItems"), hint = "" },
        onSelect = function(target, row)
            target.selectedSample = row
        end,
    })

    ctrl.btnAllowType = UI.Button(getText("IGUI_ER_Wiz_BtnAllowType"), ctrl, function(t)
        t:applySampleTypeMode("allow")
    end, { h = 24 })
    ctrl.btnDenyType = UI.Button(getText("IGUI_ER_Wiz_BtnDenyType"), ctrl, function(t)
        t:applySampleTypeMode("deny")
    end, { h = 24 })
    ctrl.btnClearType = UI.Button(getText("IGUI_ER_Wiz_BtnClearType"), ctrl, function(t)
        t:applySampleTypeMode("neutral")
    end, { h = 24 })

    local rightSpec = UI.Column({ padding = 6, gap = 6 }, {
        UI.Item(ctrl.searchBox, { basis = 24, grow = 0 }),
        UI.Item(UI.Label(getText("IGUI_ER_Wiz_TreeHint"), { h = 16, muted = true, truncate = true }), { basis = 16, grow = 0 }),
        UI.Item(ctrl.treeList, { grow = 1 }),
        UI.Item(UI.Label(getText("IGUI_ER_Wiz_SampleItemsHdr"), { h = 18, color = Theme.accent }), { basis = 18, grow = 0 }),
        UI.Item(ctrl.sampleList, { basis = 88, grow = 0 }),
        UI.Item(UI.Row({ gap = 4 }, {
            UI.Item(ctrl.btnAllowType, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnDenyType, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnClearType, { basis = 0, grow = 1 }),
        }), { basis = 24, grow = 0 }),
    })

    ctrl.splitView = UI.SplitView(leftSpec, rightSpec, {
        ratio = 0.37,
        minFirst = 230,
        minSecond = 320,
        handleSize = 6,
    })

    local rootSpec = UI.Column({ padding = 10, gap = 6 }, {
        UI.Item(UI.Row({ gap = 6, align = "center" }, {
            UI.Item(ctrl.prevBtn, { basis = 28, grow = 0 }),
            UI.Item(ctrl.lblTitle, { grow = 1 }),
            UI.Item(ctrl.nextBtn, { basis = 28, grow = 0 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(UI.Row({ gap = 6 }, {
            UI.Item(ctrl.btnHighlight, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnCopy, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnPaste, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnAutoZone, { basis = 0, grow = 1 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(ctrl.splitView, { grow = 1 }),
    })
    win:setContentSpec(rootSpec)

    activeWizard = ctrl
    ctrl:loadContainerList(opts.containerId)
    ctrl:loadCurrentRuleAndSamples()
    return ctrl
end

function ContainerConfigWizard.openForSettlementContainer(settlementId, containerId, onSaved)
    return createWizardInternal({
        settlementId = settlementId,
        containerId = containerId,
        onSaved = onSaved,
    })
end

function ContainerConfigWizard.openForObject(isoObj, settlementId, onSaved)
    if not isoObj then return nil end
    local cid = EmberRoutesJava and EmberRoutesJava.resolveObjectContainerId
        and EmberRoutesJava.resolveObjectContainerId(isoObj)
        or nil
    return createWizardInternal({
        settlementId = settlementId,
        containerId = cid,
        targetObject = isoObj,
        onSaved = onSaved,
    })
end

function ContainerConfigWizard.getActive()
    return activeWizard
end

return ContainerConfigWizard
