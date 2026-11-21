local EmberUI = require "EmberUI"
local UI = EmberUI.UI
local Theme = EmberUI.Theme
local CachedList = require "EmberUI/Widgets/CachedList"
local Text = require "EmberUI/Core/Text"

local CategoryCatalog = require "EmberRoutes/UI/CategoryCatalog"

local WarehouseTerminalTab = {}

local ROW1_CATS = { "all", "food", "medical", "weapons", "apparel", "literature" }
local ROW2_CATS = { "tools", "materials", "fluids", "kits", "scrap", "other" }

local rowKey = CategoryCatalog.rowKey
local rowCaption = CategoryCatalog.rowCaption

local ITEM_STYLE = {
    prepare = function(_list, row, _label)
        return {
            height = 24,
            catTag = row.catTag or "",
            leftText = row.leftText or "",
            rightText = row.rightText or "",
            hasDegraded = row.hasDegraded == true,
            tooltip = row.tooltip,
        }
    end,
    draw = function(list, y, entry)
        local v, nextY = CategoryCatalog.beginListRowDraw(list, y, entry, 24, 0.25)
        if not v then return nextY end
        local countCol = Theme.getModuleColor("er.warehouse", v.hasDegraded and "degradedText" or "countText")
        local rightW = 74
        local leftClip = Text.truncate(v.leftText, math.max(40, list.width - rightW - 22), UIFont.Small)
        Theme.text(list, leftClip, 8, y + 4, Theme.textColor, UIFont.Small)
        list:drawTextRight(v.rightText, list.width - 12, y + 4, countCol.r, countCol.g, countCol.b, countCol.a or 1.0, UIFont.Small)
        return nextY
    end,
}

local BASKET_STYLE = {
    prepare = function(_list, row, _label)
        return {
            height = 22,
            leftText = row.leftText or "",
            rightText = row.rightText or "",
            tooltip = row.tooltip,
        }
    end,
    draw = function(list, y, entry)
        local v, nextY = CategoryCatalog.beginListRowDraw(list, y, entry, 22, 0)
        if not v then return nextY end
        local clip = Text.truncate(v.leftText, math.max(30, list.width - 62), UIFont.Small)
        Theme.text(list, clip, 6, y + 3, Theme.textColor, UIFont.Small)
        local ac = Theme.accent
        list:drawTextRight(v.rightText, list.width - 12, y + 3, ac.r, ac.g, ac.b, 1.0, UIFont.Small)
        return nextY
    end,
}

local ORDER_STYLE = CategoryCatalog.makeBadgedListStyle({
    height = 26,
    badgeW = 96,
    extraW = 0,
    textPadY = 5,
})

local TabCtrl = {}
TabCtrl.__index = TabCtrl

function TabCtrl:buildChipOptions()
    local c = self.countsByMajor
    local total = self.totalRowCount or 0
    local o1 = {}
    for i = 1, #ROW1_CATS do
        local id = ROW1_CATS[i]
        o1[#o1 + 1] = {
            id = id,
            label = CategoryCatalog.getLabel(id),
            count = (id == "all") and total or (c[id] or 0),
        }
    end
    local o2 = {}
    for i = 1, #ROW2_CATS do
        local id = ROW2_CATS[i]
        o2[#o2 + 1] = {
            id = id,
            label = CategoryCatalog.getLabel(id),
            count = c[id] or 0,
        }
    end
    if self.chips1 then
        local in1 = false
        for i = 1, #ROW1_CATS do if ROW1_CATS[i] == self.catFilter then in1 = true break end end
        self.chips1:setOptions(o1, in1 and self.catFilter or nil)
        self.chips1:setSelected(in1 and self.catFilter or nil, true)
    end
    if self.chips2 then
        local in2 = false
        for i = 1, #ROW2_CATS do if ROW2_CATS[i] == self.catFilter then in2 = true break end end
        self.chips2:setOptions(o2, in2 and self.catFilter or nil)
        self.chips2:setSelected(in2 and self.catFilter or nil, true)
    end
end

function TabCtrl:refreshFilteredItems()
    local q = self.searchQuery and string.lower(self.searchQuery) or ""
    local rows = {}
    self.itemsRev = (self.itemsRev or 0) + 1

    for i = 1, #self.allItems do
        local it = self.allItems[i]
        local matchCat = (self.catFilter == "all") or (it.major == self.catFilter)
        local matchStatus = true
        if self.statusFilter == "usable" then
            matchStatus = (it.usableCount > 0)
        elseif self.statusFilter == "degraded" then
            matchStatus = (it.degradedCount > 0)
        end
        local matchQuery = (q == "")
            or (string.find(it.searchBlob, q, 1, true) ~= nil)
        if matchCat and matchStatus and matchQuery then
            rows[#rows + 1] = it
        end
    end

    self.filteredItems = rows
    local selectedVal = CachedList.reconcile(self.itemList, rows, rowCaption, rowKey, true, self.itemsRev)
    if selectedVal and type(selectedVal) == "table" then
        self:selectStorageItem(selectedVal)
    elseif #rows > 0 then
        self:selectStorageItem(rows[1])
    else
        self:selectStorageItem(nil)
    end
end

function TabCtrl:selectStorageItem(it)
    self.selectedItem = it
    if not it then
        self.lblSelectedItem:setText(getText("IGUI_ER_Term_SelectPrompt"))
        self.qtyStepper.min = 1
        self.qtyStepper.max = 1
        self.qtyStepper:setValue(1)
        self.btnAddBasket:setEnable(false)
        return
    end
    local maxAvail = math.max(1, math.floor(it.count or 1))
    self.lblSelectedItem:setText(string.format("%s (%d)", it.displayName, maxAvail))
    self.qtyStepper.min = 1
    self.qtyStepper.max = maxAvail
    local cur = tonumber(self.qtyStepper.value) or 1
    self.qtyStepper:setValue(math.max(1, math.min(maxAvail, cur)))
    self.btnAddBasket:setEnable(it.loadedAvail > 0)
end

function TabCtrl:refreshBasketList()
    local rows = {}
    self.basketRev = (self.basketRev or 0) + 1
    for i = 1, #self.basket do
        local b = self.basket[i]
        rows[#rows + 1] = {
            key = b.fullType,
            fullType = b.fullType,
            leftText = b.displayName,
            rightText = "x" .. tostring(b.count),
            caption = b.displayName .. " x" .. tostring(b.count),
            tooltip = b.fullType,
        }
    end
    CachedList.reconcile(self.basketList, rows, rowCaption, rowKey, true, self.basketRev)
    self.lblBasketHdr:setText(getText("IGUI_ER_Term_BasketHdrFmt", tostring(#self.basket)))
    local hasItems = #self.basket > 0
    self.btnSubmitOrders:setEnable(hasItems)
    self.btnClearBasket:setEnable(hasItems)
    self.btnRemoveBasketItem:setEnable(hasItems)
end

function TabCtrl:refreshTargetDropdown()
    if not self.ddTarget then return end
    local prevSel = self.ddTarget:getSelectedData() or ""
    local opts = {
        { id = "", label = getText("IGUI_ER_Term_TargetPlayer") },
    }
    if self.settlementId and EmberRoutesJava and EmberRoutesJava.listSettlementContainers then
        local containers = EmberRoutesJava.listSettlementContainers(self.settlementId) or {}
        for i = 1, #containers do
            local c = containers[i]
            if c.role == "PICKUP_OUT" and not c.isPrivate then
                local lbl = string.format(
                    "%s (%d, %d, Z=%d)",
                    tostring(c.displayName or "PICKUP_OUT"),
                    math.floor(c.x or 0),
                    math.floor(c.y or 0),
                    math.floor(c.z or 0)
                )
                opts[#opts + 1] = { id = c.containerId, label = lbl }
            end
        end
    end
    self.ddTarget:setOptions(opts, prevSel)
end

function TabCtrl:refreshOrdersList()
    local rows = {}
    self.ordersRev = (self.ordersRev or 0) + 1
    if self.settlementId and EmberRoutesJava and EmberRoutesJava.listDeliveryOrders then
        local orders = EmberRoutesJava.listDeliveryOrders(self.settlementId) or {}
        for i = 1, #orders do
            local o = orders[i]
            local name = CategoryCatalog.resolveItemName(o.fullType)
            local deliv = math.floor(o.deliveredCount or 0)
            local req = math.floor(o.requestedCount or 1)
            local targetTag = o.targetPickupOutContainerId and getText("IGUI_ER_Term_OrderBoxTag") or ""
            local npcTag = o.assignedNpcId and ("[" .. tostring(o.assignedNpcId) .. "] ") or ""
            rows[#rows + 1] = {
                key = o.orderId,
                orderId = o.orderId,
                status = o.status,
                leftText = string.format("%s%s (%d/%d)%s", npcTag, name, deliv, req, targetTag),
                badgeKey = CategoryCatalog.getOrderBadgeKey(o.status),
                badgeLabel = CategoryCatalog.getOrderStatusLabel(o.status),
                caption = name,
                tooltip = string.format(
                    "%s (%s)\n%s: %d / %d\n%s",
                    name,
                    tostring(o.orderId),
                    CategoryCatalog.getOrderStatusLabel(o.status),
                    deliv,
                    req,
                    tostring(o.targetPickupOutContainerId or getText("IGUI_ER_Term_TargetPlayer"))
                ),
            }
        end
    end
    local sel = CachedList.reconcile(self.ordersList, rows, rowCaption, rowKey, true, self.ordersRev)
    self.selectedOrder = (type(sel) == "table") and sel or rows[1]
    local canCancel = self.selectedOrder ~= nil
        and (self.selectedOrder.status == "PENDING" or self.selectedOrder.status == "ASSIGNED")
    self.btnCancelOrder:setEnable(canCancel)
end

function TabCtrl:onAddToBasket()
    if not self.selectedItem then return end
    local qty = math.max(1, math.floor(tonumber(self.qtyStepper.value) or 1))
    local maxAvail = math.max(1, math.floor(self.selectedItem.count or 1))
    local ft = self.selectedItem.fullType
    local existing = nil
    for i = 1, #self.basket do
        if self.basket[i].fullType == ft then
            existing = self.basket[i]
            break
        end
    end
    if existing then
        existing.count = math.min(maxAvail, existing.count + qty)
    else
        self.basket[#self.basket + 1] = {
            fullType = ft,
            displayName = self.selectedItem.displayName,
            count = math.min(maxAvail, qty),
        }
    end
    self:refreshBasketList()
end

function TabCtrl:onRemoveSelectedBasketItem()
    local selIdx = self.basketList and self.basketList.selected or 0
    if selIdx >= 1 and selIdx <= #self.basket then
        table.remove(self.basket, selIdx)
        self:refreshBasketList()
    end
end

function TabCtrl:onClearBasket()
    self.basket = {}
    self:refreshBasketList()
end

function TabCtrl:onSubmitBasket()
    if not self.settlementId or #self.basket == 0 or not EmberRoutesJava then return end
    local targetBoxId = self.ddTarget and self.ddTarget:getSelectedData() or ""
    for i = 1, #self.basket do
        local line = self.basket[i]
        if targetBoxId ~= "" and EmberRoutesJava.submitDeliveryOrderToBox then
            EmberRoutesJava.submitDeliveryOrderToBox(self.settlementId, 0, line.fullType, line.count, targetBoxId)
        elseif EmberRoutesJava.submitDeliveryOrder then
            EmberRoutesJava.submitDeliveryOrder(self.settlementId, 0, line.fullType, line.count)
        end
    end
    self.basket = {}
    self:refreshBasketList()
    self:refreshOrdersList()
end

function TabCtrl:onCancelSelectedOrder()
    if not self.selectedOrder or not EmberRoutesJava or not EmberRoutesJava.cancelDeliveryOrder then return end
    EmberRoutesJava.cancelDeliveryOrder(self.selectedOrder.orderId)
    self:refreshOrdersList()
end

function TabCtrl:refreshData(settlementId, snap, _containers)
    if self.settlementId ~= settlementId then
        self.settlementId = settlementId
        self.basket = {}
        self:refreshBasketList()
    end

    local nextAll = {}
    local counts = {}
    local rows = snap and snap.rows
    if rows then
        for i = 1, #rows do
            local r = rows[i]
            if r and r.fullType then
                local node = tostring(r.node or "other")
                local major = CategoryCatalog.majorOf(node)
                counts[major] = (counts[major] or 0) + 1
                local dispName = CategoryCatalog.resolveItemName(r.fullType)
                local catLabel = CategoryCatalog.getLabel(node)
                local totalCnt = math.floor(r.count or 0)
                local degCnt = math.floor(r.degradedCount or 0)
                local okCnt = math.floor(r.usableCount or (totalCnt - degCnt))
                local loadedCnt = 0
                if r.sources then
                    for si = 1, #r.sources do
                        if r.sources[si] and r.sources[si].loaded then
                            loadedCnt = loadedCnt + (r.sources[si].count or 0)
                        end
                    end
                end
                local leftText = string.format("[%s] %s", CategoryCatalog.getLabel(major), dispName)
                local rightText = degCnt > 0
                    and getText("IGUI_ER_Term_CountBadFmt", tostring(okCnt), tostring(degCnt))
                    or getText("IGUI_ER_Term_CountOkFmt", tostring(totalCnt))

                nextAll[#nextAll + 1] = {
                    key = tostring(r.fullType),
                    fullType = tostring(r.fullType),
                    displayName = dispName,
                    node = node,
                    major = major,
                    count = totalCnt,
                    usableCount = okCnt,
                    degradedCount = degCnt,
                    loadedAvail = loadedCnt > 0 and loadedCnt or totalCnt,
                    leftText = leftText,
                    rightText = rightText,
                    caption = leftText .. " " .. rightText,
                    hasDegraded = degCnt > 0,
                    searchBlob = string.lower(dispName .. " " .. tostring(r.fullType) .. " " .. catLabel),
                    tooltip = getText(
                        "IGUI_ER_Term_ItemTipFmt",
                        dispName,
                        tostring(r.fullType),
                        catLabel,
                        tostring(okCnt),
                        tostring(degCnt)
                    ),
                }
            end
        end
    end

    self.allItems = nextAll
    self.countsByMajor = counts
    self.totalRowCount = #nextAll
    self:buildChipOptions()
    self:refreshFilteredItems()
    self:refreshTargetDropdown()
    self:refreshOrdersList()
end

function TabCtrl:syncLiveOrders()
    self:refreshOrdersList()
end

function WarehouseTerminalTab.createTabPage(panelCtrl)
    local host = EmberUI.LayoutHost:new(0, 0, 800, 400, { direction = "column" })
    host:initialise()
    host:instantiate()

    local ctrl = setmetatable({
        parentCtrl = panelCtrl,
        settlementId = nil,
        catFilter = "all",
        statusFilter = "all",
        searchQuery = "",
        allItems = {},
        filteredItems = {},
        countsByMajor = {},
        totalRowCount = 0,
        selectedItem = nil,
        basket = {},
        selectedOrder = nil,
        itemsRev = 0,
        basketRev = 0,
        ordersRev = 0,
    }, TabCtrl)

    ctrl.searchBox = UI.SearchInput(getText("IGUI_ER_Term_SearchHint"), ctrl, function(target, val)
        target.searchQuery = val or ""
        target:refreshFilteredItems()
    end, { h = 24 })

    local function selectCat(id)
        ctrl.catFilter = id or "all"
        ctrl:buildChipOptions()
        ctrl:refreshFilteredItems()
    end

    ctrl.chips1 = CategoryCatalog.bindFilterChipsResize(
        UI.FilterChips({}, "all", function(id) selectCat(id) end, { h = 24, gap = 3 })
    )
    ctrl.chips2 = CategoryCatalog.bindFilterChipsResize(
        UI.FilterChips({}, nil, function(id) selectCat(id) end, { h = 24, gap = 3 })
    )
    ctrl.statusChips = CategoryCatalog.bindFilterChipsResize(
        UI.FilterChips({
            { id = "all", label = getText("IGUI_ER_Term_StatusAll") },
            { id = "usable", label = getText("IGUI_ER_Term_StatusUsable") },
            { id = "degraded", label = getText("IGUI_ER_Term_StatusDegraded") },
        }, "all", function(id)
            ctrl.statusFilter = id or "all"
            ctrl:refreshFilteredItems()
        end, { h = 22, gap = 4 })
    )

    ctrl.itemList = UI.List({
        owner = ctrl,
        itemheight = 24,
        style = ITEM_STYLE,
        well = true,
        emptyState = {
            title = getText("IGUI_ER_Term_EmptyTitle"),
            hint = getText("IGUI_ER_Term_EmptyHint"),
        },
        onSelect = function(target, val)
            target:selectStorageItem(val)
        end,
    })

    local leftPane = UI.Column({ padding = 6, gap = 4 }, {
        UI.Item(ctrl.searchBox, { basis = 24, grow = 0 }),
        UI.Item(ctrl.chips1, { basis = 24, grow = 0 }),
        UI.Item(ctrl.chips2, { basis = 24, grow = 0 }),
        UI.Item(ctrl.statusChips, { basis = 22, grow = 0 }),
        UI.Item(ctrl.itemList, { grow = 1 }),
    })

    -- Right pane: delivery basket & active orders
    ctrl.lblSelectedItem = UI.Label(getText("IGUI_ER_Term_SelectPrompt"), { h = 20, color = Theme.accent, truncate = true })
    ctrl.qtyStepper = UI.Stepper(1, 1, 99, { -10, -1, 1, 10 }, nil, { h = 24 })
    ctrl.btnAddBasket = UI.Button(getText("IGUI_ER_Term_BtnAddBasket"), ctrl, TabCtrl.onAddToBasket, { h = 24, primary = true })
    ctrl.lblBasketHdr = UI.Label(getText("IGUI_ER_Term_BasketHdrFmt", "0"), { h = 18, color = Theme.accent })
    ctrl.btnRemoveBasketItem = UI.Button(getText("IGUI_ER_Term_BtnRemove"), ctrl, TabCtrl.onRemoveSelectedBasketItem, { h = 22 })
    ctrl.btnClearBasket = UI.Button(getText("IGUI_ER_Term_BtnClear"), ctrl, TabCtrl.onClearBasket, { h = 22 })

    ctrl.basketList = UI.List({
        owner = ctrl,
        itemheight = 22,
        style = BASKET_STYLE,
        well = true,
        emptyState = { title = getText("IGUI_ER_Term_BasketEmpty"), hint = "" },
    })

    ctrl.ddTarget = UI.Dropdown(ctrl, nil, { h = 24 })
    ctrl.btnSubmitOrders = UI.Button(getText("IGUI_ER_Term_BtnSubmit"), ctrl, TabCtrl.onSubmitBasket, { h = 26, primary = true, active = true })

    ctrl.btnCancelOrder = UI.Button(getText("IGUI_ER_Term_BtnCancelOrder"), ctrl, TabCtrl.onCancelSelectedOrder, { h = 22 })
    ctrl.ordersList = UI.List({
        owner = ctrl,
        itemheight = 26,
        style = ORDER_STYLE,
        well = true,
        emptyState = { title = getText("IGUI_ER_Term_OrdersEmpty"), hint = "" },
        onSelect = function(target, val)
            target.selectedOrder = val
            local canCancel = val ~= nil and (val.status == "PENDING" or val.status == "ASSIGNED")
            target.btnCancelOrder:setEnable(canCancel)
        end,
    })

    local rightPane = UI.Column({ padding = 6, gap = 4 }, {
        UI.Item(ctrl.lblSelectedItem, { basis = 20, grow = 0 }),
        UI.Item(UI.Row({ gap = 4 }, {
            UI.Item(ctrl.qtyStepper, { grow = 1 }),
            UI.Item(ctrl.btnAddBasket, { basis = 86, grow = 0 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(UI.Row({ gap = 4, align = "center" }, {
            UI.Item(ctrl.lblBasketHdr, { grow = 1 }),
            UI.Item(ctrl.btnRemoveBasketItem, { basis = 52, grow = 0 }),
            UI.Item(ctrl.btnClearBasket, { basis = 52, grow = 0 }),
        }), { basis = 22, grow = 0 }),
        UI.Item(ctrl.basketList, { basis = 76, grow = 0 }),
        UI.Item(ctrl.ddTarget, { basis = 24, grow = 0 }),
        UI.Item(ctrl.btnSubmitOrders, { basis = 26, grow = 0 }),
        UI.Item(UI.Row({ gap = 4, align = "center" }, {
            UI.Item(UI.Label(getText("IGUI_ER_Term_OrdersHdr"), { h = 18, color = Theme.accent }), { grow = 1 }),
            UI.Item(ctrl.btnCancelOrder, { basis = 74, grow = 0 }),
        }), { basis = 22, grow = 0 }),
        UI.Item(ctrl.ordersList, { grow = 1 }),
    })

    local split = UI.SplitView(leftPane, rightPane, {
        ratio = 0.56,
        minFirst = 340,
        minSecond = 260,
        handleSize = 6,
    })

    host:build(UI.Column({ padding = 0, gap = 0 }, {
        UI.Item(split, { grow = 1 }),
    }))
    host.ctrl = ctrl
    ctrl:buildChipOptions()
    ctrl:refreshBasketList()
    ctrl:refreshTargetDropdown()
    return host
end

return WarehouseTerminalTab
