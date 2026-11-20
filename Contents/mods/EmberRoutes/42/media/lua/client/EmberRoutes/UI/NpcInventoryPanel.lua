local EmberUI = require "EmberUI"
local UI = EmberUI.UI
local Theme = EmberUI.Theme
local Chrome = EmberUI.Chrome

local Kernel = require "EmberRoutes/Kernel"
local NpcRuntime = require "EmberRoutes/Npc/NpcRuntime"
local NpcMedicalPanel = require "EmberRoutes/UI/NpcMedicalPanel"

local NpcInventoryPanel = {}

local DROP_REFRESH_TICKS = 90
local DROP_REFRESH_INTERVAL = 15

local activeWindows = {}
local pendingRefreshTicks = 0
local pendingRefreshInterval = 0
local openActionClass = nil
local queuedTransferClass = nil

if Theme and Theme.registerModule then
    Theme.registerModule({
        id = "er.npcInventory",
        labelKey = "IGUI_ER_ThemeMod_NpcInventory",
        label = "NPC Inventory",
        order = 30,
        colors = {},
    })
end

local function noop() end

local function wipeTable(t)
    if not t then return end
    if table and type(table.wipe) == "function" then
        table.wipe(t)
        return
    end
    local keys = {}
    for k, _ in pairs(t) do
        keys[#keys + 1] = k
    end
    for i = 1, #keys do
        t[keys[i]] = nil
    end
end

local function getRealPlayer(playerNum)
    if getSpecificPlayer then
        return getSpecificPlayer(playerNum or 0)
    end
    return nil
end

local function isOwnedCharacterAlive(char)
    return char ~= nil and not (char.isDead and char:isDead())
end

local function isLiveManagedOrReal(char)
    if not isOwnedCharacterAlive(char) then return false end
    local isNpc, npcId = NpcRuntime.isTargetNpc(char)
    if isNpc then
        return NpcMedicalPanel.isNpcLive(npcId, char)
    end
    return true
end

local function withNpcContext(npc, hotbar, playerNum, fn, ...)
    if not npc or type(fn) ~= "function" then return nil end
    local prevGetPlayer = getSpecificPlayer
    local prevGetHotbar = getPlayerHotbar
    local idxMatch = playerNum ~= nil and playerNum or 0

    getSpecificPlayer = function(idx)
        if idx == idxMatch then
            return npc
        end
        return prevGetPlayer and prevGetPlayer(idx) or nil
    end
    if hotbar ~= nil then
        getPlayerHotbar = function(idx)
            if idx == idxMatch then
                return hotbar
            end
            return prevGetHotbar and prevGetHotbar(idx) or nil
        end
    end

    local ok, r1, r2, r3 = pcall(fn, ...)
    getSpecificPlayer = prevGetPlayer
    getPlayerHotbar = prevGetHotbar
    if not ok then
        error(r1)
    end
    return r1, r2, r3
end

local function createNpcHotbar(npc)
    if not npc or not ISHotbar or not ISHotbar.new then return nil end
    local ok, hb = pcall(ISHotbar.new, ISHotbar, npc)
    if ok then return hb end
    return nil
end

local function findOpenHotbarForNpc(npc)
    for _, ctrl in pairs(activeWindows) do
        if ctrl and ctrl.npc == npc and ctrl.hotbar then
            return ctrl.hotbar
        end
    end
    return nil
end

local function withActionHotbar(action, fn, ...)
    if not action or not action.character then
        return fn(action, ...)
    end
    local isNpc = NpcRuntime.isTargetNpc(action.character)
    if not isNpc then
        return fn(action, ...)
    end
    local targetHotbar = action.hotbar or findOpenHotbarForNpc(action.character)
    if not targetHotbar then
        return fn(action, ...)
    end

    local prevGetHotbar = getPlayerHotbar
    local matchIdx = action.character.getPlayerNum and action.character:getPlayerNum() or 0
    getPlayerHotbar = function(idx)
        if idx == matchIdx then
            return targetHotbar
        end
        return prevGetHotbar and prevGetHotbar(idx) or nil
    end
    local ok, r1, r2, r3 = pcall(fn, action, ...)
    getPlayerHotbar = prevGetHotbar
    if not ok then
        error(r1)
    end
    return r1, r2, r3
end

local function isEquippedItem(owner, item)
    if not owner or not item then return false end
    if owner.isEquipped and owner:isEquipped(item) then return true end
    if owner.isHandItem and owner:isHandItem(item) then return true end
    if owner.getPrimaryHandItem and owner:getPrimaryHandItem() == item then return true end
    if owner.getSecondaryHandItem and owner:getSecondaryHandItem() == item then return true end
    local worn = owner.getWornItems and owner:getWornItems()
    if worn and worn.contains and worn:contains(item) then return true end
    return false
end

local function isAttachedItem(owner, item)
    if not owner or not item then return false end
    local slot = item.getAttachedSlot and item:getAttachedSlot()
    if slot and slot > -1 then return true end
    return owner.isAttachedItem and owner:isAttachedItem(item) == true
end

local function detachBeforeMove(owner, item)
    if not owner or not item or not isAttachedItem(owner, item) then return end
    pcall(function() owner:removeAttachedItem(item) end)
    pcall(function() item:setAttachedSlot(-1) end)
    pcall(function() item:setAttachedSlotType(nil) end)
    pcall(function() item:setAttachedToModel(nil) end)
end

local function isKeyRing(item)
    if not item then return false end
    if ItemType and item.isItemType then
        local ok, yes = pcall(function() return item:isItemType(ItemType.KEY_RING) end)
        if ok and yes then return true end
    end
    if ItemTag and item.hasTag then
        local ok, yes = pcall(function() return item:hasTag(ItemTag.KEY_RING) end)
        if ok and yes then return true end
    end
    return false
end

local function isEquippedContainer(owner, item)
    if not owner or not item then return false end
    local okCat, cat = pcall(function() return item:getCategory() end)
    if not okCat or cat ~= "Container" then return false end
    local okEq, eq = pcall(function() return owner:isEquipped(item) end)
    return okEq and eq == true
end

local function clearDragState(pane)
    if pane then
        pane.dragging = nil
        pane.dragStarted = false
        pane.draggingMarquis = false
        if pane.draggedItems and pane.draggedItems.reset then
            pcall(pane.draggedItems.reset, pane.draggedItems)
        end
    end
    if ISMouseDrag then
        ISMouseDrag.dragging = nil
        ISMouseDrag.draggingFocus = nil
    end
end

local function isNpcOwnedPane(pane)
    if not pane then return false end
    if pane.erNpcId ~= nil or pane.ownerCharacter ~= nil then return true end
    if pane.inventoryPage and (pane.inventoryPage.erNpcId ~= nil or pane.inventoryPage.ownerCharacter ~= nil) then
        return true
    end
    return false
end

local function resolvePaneOwner(pane)
    if not pane then return nil end
    if pane.ownerCharacter then return pane.ownerCharacter end
    if pane.inventoryPage and pane.inventoryPage.ownerCharacter then return pane.inventoryPage.ownerCharacter end
    if pane.parent and pane.parent.ownerCharacter then return pane.parent.ownerCharacter end
    local pNum = pane.player
    if pNum == nil and pane.inventoryPage then pNum = pane.inventoryPage.player end
    return getRealPlayer(pNum or 0)
end

local function refreshSingleCtrl(ctrl)
    if not ctrl or ctrl.closed or not ctrl.page then return end
    local page = ctrl.page
    if page.refreshBackpacks then
        pcall(page.refreshBackpacks, page)
    elseif page.inventoryPane and page.inventoryPane.refreshContainer then
        withNpcContext(ctrl.npc, ctrl.hotbar, page.player, page.inventoryPane.refreshContainer, page.inventoryPane)
    end
    ctrl:refreshHeaderMeta()
end

local function refreshVisibleInventories()
    if ISInventoryPage then
        ISInventoryPage.renderDirty = true
    end
    for _, ctrl in pairs(activeWindows) do
        refreshSingleCtrl(ctrl)
    end
    if getPlayerData then
        pcall(function()
            local pdata = getPlayerData(0)
            if pdata and pdata.playerInventory and pdata.playerInventory.refreshBackpacks then
                pdata.playerInventory:refreshBackpacks()
            end
            if pdata and pdata.lootInventory and pdata.lootInventory.refreshBackpacks then
                pdata.lootInventory:refreshBackpacks()
            end
        end)
    end
end

local function requestRefresh(ticks)
    local t = ticks or DROP_REFRESH_TICKS
    if t > pendingRefreshTicks then
        pendingRefreshTicks = t
    end
    pendingRefreshInterval = 0
end

local function isWithinActorInteractionRange(npc)
    local actor = getRealPlayer(0)
    if not isOwnedCharacterAlive(actor) then return false end
    local isNpc, npcId = NpcRuntime.isTargetNpc(npc)
    if not isNpc or not NpcMedicalPanel.isNpcLive(npcId, npc) then return false end
    return NpcMedicalPanel.isWithinRange(actor, npc)
end

local function verifyMoveActorsValid(sourceOwner, destOwner)
    if not isLiveManagedOrReal(sourceOwner) or not isLiveManagedOrReal(destOwner) then
        return false
    end
    if NpcRuntime.isTargetNpc(sourceOwner) and not isWithinActorInteractionRange(sourceOwner) then
        return false
    end
    if NpcRuntime.isTargetNpc(destOwner) and not isWithinActorInteractionRange(destOwner) then
        return false
    end
    return true
end

local function canTransferItem(sourceOwner, recipientOwner, destContainer, item, floorMode)
    if not verifyMoveActorsValid(sourceOwner, recipientOwner or sourceOwner) then
        return false, nil
    end
    if not destContainer or not item or not item.getContainer then return false, nil end
    local srcContainer = item:getContainer()
    if not srcContainer or srcContainer == destContainer then return false, nil end
    if not srcContainer:contains(item) then return false, nil end
    local srcParent = srcContainer.getParent and srcContainer:getParent()
    if srcParent and instanceof and instanceof(srcParent, "IsoDeadBody")
        and srcParent.getStaticMovingObjectIndex and srcParent:getStaticMovingObjectIndex() == -1
    then
        return false, nil
    end
    if srcContainer.isRemoveItemAllowed and not srcContainer:isRemoveItemAllowed(item) then
        return false, nil
    end
    if floorMode then
        if item.isFavorite and item:isFavorite() then return false, nil end
        if instanceof and instanceof(item, "Moveable") and item.CanBeDroppedOnFloor and not item:CanBeDroppedOnFloor() then
            return false, nil
        end
        return true, srcContainer
    end
    if destContainer.isExistYet and not destContainer:isExistYet() then return false, nil end
    local destParent = destContainer.getParent and destContainer:getParent()
    local actor = getRealPlayer(0)
    if destParent and actor and instanceof and instanceof(destParent, "IsoThumpable")
        and destParent.isLockedToCharacter and destParent:isLockedToCharacter(actor)
    then
        return false, nil
    end
    if destContainer.isInside and destContainer:isInside(item) then return false, nil end
    if actor and sourceOwner == actor and recipientOwner ~= actor and item.isFavorite and item:isFavorite() then
        return false, nil
    end
    if destContainer.isItemAllowed and not destContainer:isItemAllowed(item) then return false, nil end
    local checker = recipientOwner or sourceOwner
    if destContainer.hasRoomFor and not destContainer:hasRoomFor(checker, item) then return false, nil end
    return true, srcContainer
end

local function executeImmediateTransfer(op)
    if not op or not op.item or not op.srcContainer or not op.destContainer then return false end
    local item = op.item
    local srcContainer = op.srcContainer
    local destContainer = op.destContainer
    local sourceOwner = op.sourceOwner
    if not verifyMoveActorsValid(sourceOwner, op.recipientOwner or sourceOwner) then
        return false
    end
    if not srcContainer:contains(item) then return false end
    if isEquippedItem(sourceOwner, item) then return false end

    local dropSq = nil
    if op.floorMode then
        if not ISTransferAction or not ISTransferAction.getNotFullFloorSquare then return false end
        dropSq = ISTransferAction:getNotFullFloorSquare(sourceOwner, item, destContainer)
        if not dropSq then return false end
    end

    detachBeforeMove(sourceOwner, item)
    local ok = pcall(function()
        ISTransferAction:transferItem(sourceOwner, item, srcContainer, destContainer, dropSq)
    end)
    if not ok then return false end

    pcall(function() srcContainer:setDrawDirty(true) end)
    pcall(function() srcContainer:setHasBeenLooted(true) end)
    pcall(function() destContainer:setDrawDirty(true) end)
    return true
end

local function ensureQueuedTransferClass()
    if queuedTransferClass then return queuedTransferClass end
    if not ISBaseTimedAction then return nil end

    local Action = ISBaseTimedAction:derive("ISNpcInventoryQueuedTransferAction")

    function Action:isValid()
        local op = self.operation
        if not op or not op.item or not op.srcContainer or not op.destContainer then return false end
        if not verifyMoveActorsValid(op.sourceOwner, op.recipientOwner or op.sourceOwner) then
            return false
        end
        return op.srcContainer:contains(op.item) == true
    end

    function Action:start()
        if self.item and self.item.setJobDelta then
            self.item:setJobDelta(0.0)
        end
    end

    function Action:update()
        self:forceComplete()
    end

    function Action:perform()
        if self.item and self.item.setJobDelta then
            self.item:setJobDelta(0.0)
        end
        executeImmediateTransfer(self.operation)
        requestRefresh(DROP_REFRESH_TICKS)
        refreshVisibleInventories()
        ISBaseTimedAction.perform(self)
    end

    function Action:new(owner, operation)
        local o = ISBaseTimedAction.new(self, owner)
        o.operation = operation
        o.item = operation and operation.item or nil
        o.maxTime = 1
        o.stopOnWalk = false
        o.stopOnRun = false
        o.stopOnAim = false
        return o
    end

    queuedTransferClass = Action
    return queuedTransferClass
end

local function queueUnequipAndTransfer(sourceOwner, hotbar, op)
    local Queued = ensureQueuedTransferClass()
    if not ISUnequipAction or not ISTimedActionQueue or not Queued then return false end
    local unequip = withNpcContext(sourceOwner, hotbar, sourceOwner:getPlayerNum(), function()
        return ISUnequipAction:new(sourceOwner, op.item, 50, op.floorMode and "drop" or "transfer")
    end)
    if not unequip then return false end
    if hotbar then
        unequip.hotbar = hotbar
    end
    ISTimedActionQueue.add(unequip)
    ISTimedActionQueue.add(Queued:new(sourceOwner, op))
    return true
end

local function executeTransfer(sourceOwner, recipientOwner, destContainer, rawItems, floorMode)
    if not destContainer or not rawItems then return 0 end
    local actual = ISInventoryPane and ISInventoryPane.getActualItems
        and ISInventoryPane.getActualItems(rawItems) or rawItems
    if not actual or #actual == 0 then return 0 end

    local sourceHotbar = NpcRuntime.isTargetNpc(sourceOwner) and findOpenHotbarForNpc(sourceOwner) or nil
    local handled = 0
    for i = 1, #actual do
        local item = actual[i]
        local ok, srcContainer = canTransferItem(sourceOwner, recipientOwner, destContainer, item, floorMode)
        if ok and srcContainer then
            local op = {
                sourceOwner = sourceOwner,
                recipientOwner = recipientOwner,
                srcContainer = srcContainer,
                destContainer = destContainer,
                item = item,
                floorMode = floorMode == true,
            }
            if isEquippedItem(sourceOwner, item) then
                if queueUnequipAndTransfer(sourceOwner, sourceHotbar, op) then
                    handled = handled + 1
                end
            elseif executeImmediateTransfer(op) then
                handled = handled + 1
            end
        end
    end

    if handled > 0 then
        requestRefresh(DROP_REFRESH_TICKS)
        refreshVisibleInventories()
    end
    return handled
end

function NpcInventoryPanel.dropDraggedItemsToGround(sourcePane)
    if not ISMouseDrag or not ISMouseDrag.dragging or #ISMouseDrag.dragging == 0 then return false end
    if ISMouseDrag.draggingFocus ~= sourcePane then return false end

    local owner = resolvePaneOwner(sourcePane)
    local pNum = sourcePane.player or 0
    local floorContainer = ISInventoryPage and ISInventoryPage.GetFloorContainer and ISInventoryPage.GetFloorContainer(pNum)
    if not owner or not floorContainer then
        clearDragState(sourcePane)
        return false
    end

    local rawDragging = ISMouseDrag.dragging
    wipeTable(sourcePane.selected)
    clearDragState(sourcePane)
    executeTransfer(owner, owner, floorContainer, rawDragging, true)
    return true
end

function NpcInventoryPanel.handleDrop(targetPane, destContainer)
    if not ISMouseDrag or not ISMouseDrag.dragging or #ISMouseDrag.dragging == 0 then return false end
    local sourcePane = ISMouseDrag.draggingFocus
    local sourceOwner = resolvePaneOwner(sourcePane)
    local recipientOwner = resolvePaneOwner(targetPane)
    if not sourceOwner or not recipientOwner or not destContainer then return false end
    if not isNpcOwnedPane(sourcePane) and not isNpcOwnedPane(targetPane) then
        return false
    end

    local rawDragging = ISMouseDrag.dragging
    clearDragState(sourcePane)
    if sourcePane then wipeTable(sourcePane.selected) end
    executeTransfer(sourceOwner, recipientOwner, destContainer, rawDragging, false)
    return true
end

function NpcInventoryPanel.tryHandleExternalPaneDrop(targetPane)
    if not targetPane or not ISMouseDrag or not ISMouseDrag.dragging or #ISMouseDrag.dragging == 0 then
        return false
    end
    local sourcePane = ISMouseDrag.draggingFocus
    if not isNpcOwnedPane(sourcePane) then return false end

    local sourceOwner = resolvePaneOwner(sourcePane)
    local targetOwner = resolvePaneOwner(targetPane)
    if not sourceOwner or not targetOwner or not targetPane.inventory then
        clearDragState(sourcePane)
        return true
    end

    local rawDragging = ISMouseDrag.dragging
    clearDragState(sourcePane)
    if sourcePane then wipeTable(sourcePane.selected) end
    local isFloor = targetPane.inventory.getType and targetPane.inventory:getType() == "floor"
    executeTransfer(sourceOwner, targetOwner, targetPane.inventory, rawDragging, isFloor)
    return true
end

function NpcInventoryPanel.tryHandleExternalPageDrop(targetPage, button)
    if not targetPage or not button or not button.inventory then return false end
    if not ISMouseDrag or not ISMouseDrag.dragging or #ISMouseDrag.dragging == 0 then return false end
    local sourcePane = ISMouseDrag.draggingFocus
    if not isNpcOwnedPane(sourcePane) then return false end

    local sourceOwner = resolvePaneOwner(sourcePane)
    local targetOwner = targetPage.ownerCharacter or getRealPlayer(targetPage.player or 0)
    if not sourceOwner or not targetOwner then
        clearDragState(sourcePane)
        return true
    end

    local rawDragging = ISMouseDrag.dragging
    clearDragState(sourcePane)
    if sourcePane then wipeTable(sourcePane.selected) end
    local isFloor = button.inventory.getType and button.inventory:getType() == "floor"
    executeTransfer(sourceOwner, targetOwner, button.inventory, rawDragging, isFloor)
    return true
end

local function isDisallowedMenuCallback(menu, option, context)
    local cb = option and option.onSelect
    if ISMoveableContextMenu and cb == ISMoveableContextMenu.openMovableCursor then return true end
    if ISInventoryPaneContextMenu and cb == ISInventoryPaneContextMenu.onPlaceItemOnGround then return true end
    local subMenu = option and option.subOption and context:getSubMenu(option.subOption) or nil
    if subMenu and subMenu.isEmpty and subMenu:isEmpty() then return true end
    return false
end

local function sanitizeNpcContextMenu(pane, ctrl, context, clickedItems)
    if not context or not ctrl then return end
    local menus = {}
    if context.instanceMap then
        for _, subMenu in pairs(context.instanceMap) do
            menus[#menus + 1] = subMenu
        end
    end
    menus[#menus + 1] = context

    for _, menu in ipairs(menus) do
        local opts = menu and menu.options
        if opts then
            local changed = false
            for idx = #opts, 1, -1 do
                if isDisallowedMenuCallback(menu, opts[idx], context) then
                    if menu.optionPool then
                        table.insert(menu.optionPool, opts[idx])
                    end
                    table.remove(opts, idx)
                    changed = true
                end
            end
            if changed then
                menu.numOptions = #opts + 1
                if menu.calcHeight then menu:calcHeight() end
                if menu.setWidth and menu.calcWidth then menu:setWidth(menu:calcWidth()) end
            end
        end
    end

    local playerNum = pane and pane.player or 0
    local npc = ctrl.npc
    local hotbar = ctrl.hotbar

    for _, menu in ipairs(menus) do
        for _, option in ipairs(menu and menu.options or {}) do
            if type(option.onSelect) == "function" then
                local origSelect = option.onSelect
                option.onSelect = function(...)
                    if not NpcMedicalPanel.isNpcLive(ctrl.npcId, npc)
                        or not isWithinActorInteractionRange(npc)
                    then
                        return
                    end
                    if ISInventoryPaneContextMenu and origSelect == ISInventoryPaneContextMenu.onDropItems and hotbar then
                        local itemsArg = select(1, ...)
                        local actual = ISInventoryPane and ISInventoryPane.getActualItems
                            and ISInventoryPane.getActualItems(itemsArg) or itemsArg
                        for _, it in ipairs(actual or {}) do
                            if hotbar.isInHotbar and hotbar:isInHotbar(it) then
                                hotbar:removeItem(it, false)
                            end
                        end
                    end
                    local ok, res = pcall(withNpcContext, npc, hotbar, playerNum, origSelect, ...)
                    requestRefresh(DROP_REFRESH_TICKS)
                    refreshVisibleInventories()
                    if not ok then error(res) end
                    return res
                end
            end
        end
    end

    if clickedItems and #clickedItems > 0 and context.addOption then
        context:addOption(getText("ContextMenu_ER_NpcInv_TakeToPlayer"), nil, function()
            local actor = getRealPlayer(playerNum)
            if not actor or not NpcMedicalPanel.isNpcLive(ctrl.npcId, npc) then return end
            if not NpcMedicalPanel.isWithinRange(actor, npc) then return end
            local destInv = actor.getInventory and actor:getInventory()
            if destInv then
                executeTransfer(npc, actor, destInv, clickedItems, false)
            end
        end)
    end
end

local function collectSelectedPaneItems(pane)
    local raw = {}
    if not pane or not pane.items then return {} end
    if pane.selected then
        for _, v in pairs(pane.selected) do
            raw[#raw + 1] = v
        end
    end
    if #raw == 0 and pane.mouseOverOption and pane.mouseOverOption ~= 0 then
        local hover = pane.items[pane.mouseOverOption]
        if hover then
            raw[#raw + 1] = hover
        end
    end
    if #raw == 0 then return {} end
    if ISInventoryPane and ISInventoryPane.getActualItems then
        return ISInventoryPane.getActualItems(raw)
    end
    local out = {}
    for i = 1, #raw do
        local entry = raw[i]
        if instanceof and instanceof(entry, "InventoryItem") then
            out[#out + 1] = entry
        elseif type(entry) == "table" and entry.items then
            for j = 2, #entry.items do
                out[#out + 1] = entry.items[j]
            end
        end
    end
    return out
end

-- ==================== Panel Controller ====================

local PanelCtrl = {}
PanelCtrl.__index = PanelCtrl

function PanelCtrl:refreshHeaderMeta()
    if not self.weightLabel then return end
    local page = self.page
    local inv = page and page.inventoryPane and page.inventoryPane.inventory
    local containerName = (page and page.selectedButton and page.selectedButton.name)
        or getText("IGUI_ER_NpcInv_MainBag")
    local usedW = 0
    local capW = 0
    if inv and ISInventoryPage and ISInventoryPage.loadWeight then
        usedW = ISInventoryPage.loadWeight(inv) or 0
        local rootInv = self.npc and self.npc.getInventory and self.npc:getInventory()
        if inv == rootInv and self.npc and self.npc.getMaxWeight then
            capW = self.npc:getMaxWeight() or 0
        else
            capW = page.capacity or (inv.getEffectiveCapacity and inv:getEffectiveCapacity(self.npc)) or 0
        end
    elseif self.previewContainerWeight then
        usedW = self.previewContainerWeight
        capW = self.previewContainerCapacity or 50
        containerName = self.previewContainerName or containerName
    end

    local usedInt = math.floor(usedW * 10 + 0.5)
    local capInt = math.floor((capW or 0) * 10 + 0.5)
    if self._metaName == containerName and self._metaUsedInt == usedInt and self._metaCapInt == capInt then
        return
    end
    self._metaName = containerName
    self._metaUsedInt = usedInt
    self._metaCapInt = capInt
    self.weightLabel:setText(getText(
        "IGUI_ER_NpcInv_WeightFmt",
        tostring(containerName),
        string.format("%.1f", usedW),
        string.format("%.0f", capW or 0)
    ))
end

function PanelCtrl:updateProximityState()
    if self.closed then return end
    if self.fixedPreviewBlocked ~= nil then
        self.blocked = self.fixedPreviewBlocked == true
        self.blockingAlpha = self.blocked and 1.0 or 0.0
        self.blockingMessage = self.blocked
            and getText("IGUI_ER_NpcInv_TooFarMsg", self.displayName or "NPC")
            or nil
        if self.lastBlocked ~= self.blocked then
            self.lastBlocked = self.blocked
            if self.statusBadge then
                if self.blocked then
                    self.statusBadge:setBadge("WARNING", getText("IGUI_ER_NpcInv_TooFar"))
                else
                    self.statusBadge:setBadge("READY", getText("IGUI_ER_NpcInv_InRange"))
                end
            end
        end
        self:refreshHeaderMeta()
        return
    end

    local actor = getRealPlayer(0)
    if not actor or (actor.isDead and actor:isDead()) or not NpcMedicalPanel.isNpcLive(self.npcId, self.npc) then
        self:close()
        return
    end

    local inRange = NpcMedicalPanel.isWithinRange(actor, self.npc)
    local blockedNow = not inRange
    if blockedNow then
        self.blockingAlpha = math.min(1.0, (self.blockingAlpha or 0.0) + 0.08)
        self.blockingMessage = getText("IGUI_ER_NpcInv_TooFarMsg", self.displayName or self.npcId)
    else
        self.blockingAlpha = math.max(0.0, (self.blockingAlpha or 0.0) - 0.08)
        self.blockingMessage = nil
    end
    self.blocked = blockedNow

    if self.lastBlocked ~= blockedNow then
        self.lastBlocked = blockedNow
        if blockedNow and self.page and self.page.inventoryPane then
            clearDragState(self.page.inventoryPane)
        end
        if self.statusBadge then
            if blockedNow then
                self.statusBadge:setBadge("WARNING", getText("IGUI_ER_NpcInv_TooFar"))
            else
                self.statusBadge:setBadge("READY", getText("IGUI_ER_NpcInv_InRange"))
            end
        end
    end
    self:refreshHeaderMeta()
end

function PanelCtrl:onOpenMedical()
    if self.closed then return end
    local actor = getRealPlayer(0)
    if actor and self.npc then
        NpcMedicalPanel.open(actor, self.npc)
    end
end

function PanelCtrl:cleanupOnClose()
    if self.closed then return end
    self.closed = true
    if activeWindows[self.npcId] == self then
        activeWindows[self.npcId] = nil
    end
    if self.page and self.page.inventoryPane then
        if ISMouseDrag and ISMouseDrag.draggingFocus == self.page.inventoryPane then
            clearDragState(self.page.inventoryPane)
        end
        if self.page.inventoryPane.clearWorldObjectHighlights then
            pcall(self.page.inventoryPane.clearWorldObjectHighlights, self.page.inventoryPane)
        end
    end
    self.page = nil
    self.hotbar = nil
end

function PanelCtrl:close()
    if self.closed then return end
    if self.win and not self.win.disposed then
        self.win:close()
    else
        self:cleanupOnClose()
    end
end

-- ==================== Embedded Vanilla ISInventoryPage ====================

local function relayoutEmbeddedPage(page)
    if not page or not page.inventoryPane or not page.containerButtonPanel then return end
    local w = math.max(200, page.width or 380)
    local h = math.max(120, page.height or 400)
    local btnSize = page.buttonSize or 32
    local paneW = math.max(120, w - btnSize)

    page.inventoryPane:setX(0)
    page.inventoryPane:setY(0)
    page.inventoryPane:setWidth(paneW)
    page.inventoryPane:setHeight(h)
    if page.inventoryPane.onResize then
        page.inventoryPane:onResize()
    end

    page.containerButtonPanel:setX(w - btnSize)
    page.containerButtonPanel:setY(0)
    page.containerButtonPanel:setWidth(btnSize)
    page.containerButtonPanel:setHeight(h)
    if #page.backpacks > 0 then
        page.containerButtonPanel:setScrollHeight(page.backpacks[#page.backpacks]:getBottom())
    else
        page.containerButtonPanel:setScrollHeight(0)
    end
end

local function refreshNpcInventoryPage(self)
    local owner = self.ownerCharacter
    local rootInv = owner and owner.getInventory and owner:getInventory() or nil
    if not owner or not rootInv or not self.inventoryPane then return end

    if self.ownerHotbar then
        pcall(function() self.ownerHotbar:refresh() end)
        pcall(function() self.ownerHotbar:reloadIcons() end)
        self.inventoryPane.ownerHotbar = self.ownerHotbar
        self.inventoryPane.hotbar = self.ownerHotbar
    end

    self.buttonPool = self.buttonPool or {}
    for i, button in ipairs(self.backpacks) do
        self.containerButtonPanel:removeChild(button)
        table.insert(self.buttonPool, i, button)
    end

    self.inventoryPane.lastinventory = self.inventoryPane.inventory
    self.inventoryPane:hideButtons()
    wipeTable(self.backpacks)

    local oldInventory = self.inventoryPane.lastinventory or rootInv

    local function wrapSidebarButton(button)
        if not button then return end
        button.onclick = function(pageSelf, btn)
            local ctrl = pageSelf.erPanelCtrl
            if ctrl and ctrl.blocked then return end
            withNpcContext(pageSelf.ownerCharacter, pageSelf.ownerHotbar, pageSelf.player, ISInventoryPage.onBackpackClick, pageSelf, btn)
        end
        button.onmousedown = function(pageSelf, btn, x, y)
            withNpcContext(pageSelf.ownerCharacter, pageSelf.ownerHotbar, pageSelf.player, ISInventoryPage.onBackpackMouseDown, pageSelf, btn, x, y)
        end
        button.onMouseUp = function(btnSelf, x, y)
            local page = btnSelf.parent and btnSelf.parent.parent
            local ctrl = page and page.erPanelCtrl
            if ctrl and ctrl.blocked then
                clearDragState(page and page.inventoryPane)
                return
            end
            return withNpcContext(page.ownerCharacter, page.ownerHotbar, page.player, ISInventoryPage.onBackpackMouseUp, btnSelf, x, y)
        end
        button.onRightMouseDown = function(btnSelf, x, y)
            local page = btnSelf.parent and btnSelf.parent.parent
            local ctrl = page and page.erPanelCtrl
            if not page or not ctrl or ctrl.blocked then return true end
            local container = btnSelf.inventory
            local item = container and container.getContainingItem and container:getContainingItem()
            if not item then return true end
            withNpcContext(page.ownerCharacter, page.ownerHotbar, page.player, function()
                local ctx = ISInventoryPaneContextMenu.createMenu(page.player, true, { item }, getMouseX(), getMouseY())
                sanitizeNpcContextMenu(page.inventoryPane, ctrl, ctx, { item })
            end)
            return true
        end
        if button.setOnMouseOverFunction then button:setOnMouseOverFunction(nil) end
        if button.setOnMouseOutFunction then button:setOnMouseOutFunction(nil) end
    end

    local function addContainer(container, texture, name, tooltip)
        if not container then return nil end
        local btn = withNpcContext(owner, self.ownerHotbar, self.player, ISInventoryPage.addContainerButton, self, container, texture, name, tooltip)
        if btn then
            local okCap, cap = pcall(function() return container:getEffectiveCapacity(owner) end)
            if okCap and cap then btn.capacity = cap end
            wrapSidebarButton(btn)
        end
        return btn
    end

    local mainLabel = (getTextOrNull and getTextOrNull("IGUI_InventoryTooltip")) or getText("IGUI_ER_NpcInv_MainBag")
    local mainBtn = addContainer(rootInv, self.invbasic, mainLabel, nil)
    if mainBtn then
        local okMax, maxW = pcall(function() return rootInv:getMaxWeight() end)
        if okMax and maxW then mainBtn.capacity = maxW end
    end

    local okItems, items = pcall(function() return rootInv:getItems() end)
    if okItems and items then
        for i = 0, items:size() - 1 do
            local item = items:get(i)
            if isEquippedContainer(owner, item) or isKeyRing(item) then
                local okInv, subInv = pcall(function() return item:getInventory() end)
                if okInv and subInv then
                    local okTex, tex = pcall(function() return item:getTex() end)
                    local okName, itemName = pcall(function() return item:getName() end)
                    local btn = addContainer(subInv, okTex and tex or nil, okName and itemName or "", okName and itemName or "")
                    if btn and item.getVisual and item.getClothingItem then
                        local okTint, tint = pcall(function()
                            if item:getVisual() and item:getClothingItem() then
                                return item:getVisual():getTint(item:getClothingItem())
                            end
                            return nil
                        end)
                        if okTint and tint then
                            btn:setTextureRGBA(tint:getRedFloat(), tint:getGreenFloat(), tint:getBlueFloat(), 1.0)
                        end
                    end
                end
            end
        end
    end

    local selectedBtn = nil
    for _, btn in ipairs(self.backpacks) do
        if btn.inventory == oldInventory then
            selectedBtn = btn
            break
        end
    end
    if not selectedBtn then selectedBtn = self.backpacks[1] end

    if selectedBtn then
        self.inventoryPane.inventory = selectedBtn.inventory
        self.inventory = selectedBtn.inventory
        self.capacity = selectedBtn.capacity
        self.selectedButton = selectedBtn
        for _, btn in ipairs(self.backpacks) do
            if btn == selectedBtn then
                btn:setBackgroundRGBA(0.7, 0.7, 0.7, 1.0)
            else
                btn:setBackgroundRGBA(0.0, 0.0, 0.0, 0.0)
            end
        end
    else
        self.inventoryPane.inventory = rootInv
        self.inventory = rootInv
        self.selectedButton = nil
    end

    self.inventoryPane:bringToTop()
    withNpcContext(owner, self.ownerHotbar, self.player, ISInventoryPane.refreshContainer, self.inventoryPane)
    relayoutEmbeddedPage(self)
    if self.erPanelCtrl then
        self.erPanelCtrl:refreshHeaderMeta()
    end
end

local function updateNpcInventoryPage(self)
    local npc = self.ownerCharacter
    if not npc or not self.inventoryPane then return end
    if self.inventory and self.inventory.getEffectiveCapacity then
        local cap = self.inventory:getEffectiveCapacity(npc)
        if cap ~= self.capacity then
            self.capacity = cap
        end
    end
    self.collapseCounter = 0
    self.isCollapsed = false
    if self.clearMaxDrawHeight then
        self:clearMaxDrawHeight()
    end
    if self.containerButtonPanel then
        self.containerButtonPanel:setHeight(self.inventoryPane.height)
        self.containerButtonPanel:setY(0)
        if #self.backpacks > 0 then
            self.containerButtonPanel:setScrollHeight(self.backpacks[#self.backpacks]:getBottom())
        end
    end
end

local function prerenderNpcInventoryPage(self)
    local w, h = self:getWidth(), self:getHeight()
    local btnSize = self.buttonSize or 32
    self:drawRect(0, 0, w, h, 0.72, 0.04, 0.05, 0.07)
    self:drawRect(w - btnSize, 0, btnSize, h, 0.55, 0.03, 0.04, 0.05)
    self:setStencilRect(0, 0, w + 1, h)
    if self.containerButtonPanel and #self.backpacks > 0 then
        self.containerButtonPanel:keepSelectedButtonVisible()
    end
    local npc = self.ownerCharacter
    if npc and npc.isInvPageDirty and npc:isInvPageDirty() then
        npc:setInvPageDirty(false)
        refreshNpcInventoryPage(self)
    end
end

function NpcInventoryPanel.drawBlockingScrim(el, ctrl)
    if not el or not ctrl or not ctrl.blockingAlpha or ctrl.blockingAlpha <= 0.01 or not ctrl.blockingMessage then
        return
    end
    local w, h = el:getWidth(), el:getHeight()
    el:drawRect(0, 0, w, h, 0.78 * ctrl.blockingAlpha, 0.02, 0.02, 0.03)
    local maxTextW = math.max(80, w - 24)
    if ctrl._scrimMsg ~= ctrl.blockingMessage or ctrl._scrimW ~= maxTextW or not ctrl._scrimLines then
        ctrl._scrimMsg = ctrl.blockingMessage
        ctrl._scrimW = maxTextW
        local Text = EmberUI and EmberUI.Text
        ctrl._scrimLines = (Text and Text.wrapLimited)
            and Text.wrapLimited(ctrl.blockingMessage, maxTextW, UIFont.Small, 2)
            or { ctrl.blockingMessage }
    end
    local lines = ctrl._scrimLines
    local tm = getTextManager and getTextManager()
    local fh = tm and tm.getFontHeight and tm:getFontHeight(UIFont.Small) or 16
    local totalH = #lines * (fh + 2)
    local startY = math.max(8, math.floor((h - totalH) * 0.5))
    for i = 1, #lines do
        local line = lines[i]
        local tw = tm and tm.MeasureStringX and tm:MeasureStringX(UIFont.Small, line) or 120
        local tx = math.max(8, math.floor((w - tw) * 0.5))
        el:drawText(line, tx, startY + (i - 1) * (fh + 2), 1.0, 0.85, 0.55, ctrl.blockingAlpha, UIFont.Small)
    end
end

local function renderNpcInventoryPage(self)
    local w, h = self:getWidth(), self:getHeight()
    local btnSize = self.buttonSize or 32
    self:drawRectBorder(w - btnSize, 0, btnSize, h, 0.55, 0.35, 0.38, 0.44)
    self:clearStencilRect()
    self:drawRectBorder(0, 0, w, h, 0.75, 0.32, 0.35, 0.42)
    NpcInventoryPanel.drawBlockingScrim(self, self.erPanelCtrl)
end

local function dropItemsInNpcContainer(self, button)
    local ctrl = self.erPanelCtrl
    if ctrl and ctrl.blocked then
        clearDragState(ISMouseDrag and ISMouseDrag.draggingFocus)
        return true
    end
    local pane = self.inventoryPane
    local destContainer = button and button.inventory or (pane and pane.inventory)
    return NpcInventoryPanel.handleDrop(pane, destContainer)
end

local function configureEmbeddedPane(pane, ctrl, npc, hotbar)
    pane.ownerCharacter = npc
    pane.ownerHotbar = hotbar
    pane.hotbar = hotbar
    pane.erNpcId = ctrl.npcId
    pane.erPanelCtrl = ctrl
    pane.anchorLeft = false
    pane.anchorTop = false
    pane.anchorRight = false
    pane.anchorBottom = false
    pane.transferAll = noop
    pane.lootAll = noop
    pane.doButtons = function(self)
        if self.contextButton1 then self.contextButton1:setVisible(false) end
        if self.contextButton2 then self.contextButton2:setVisible(false) end
        if self.contextButton3 then self.contextButton3:setVisible(false) end
    end
    pane.refreshContainer = function(self)
        return withNpcContext(npc, hotbar, self.player, ISInventoryPane.refreshContainer, self)
    end
    pane.renderdetails = function(self, doDragged)
        return withNpcContext(npc, hotbar, self.player, ISInventoryPane.renderdetails, self, doDragged)
    end
    pane.update = function(self)
        local outsideDrag = ISMouseDrag
            and ISMouseDrag.dragging ~= nil
            and ISMouseDrag.draggingFocus == self
            and not isMouseButtonDown(0)

        local overFloor = false
        if outsideDrag and self.draggedItems and not ctrl.blocked then
            self.draggedItems.playerNum = self.player or 0
            local okDrop, _, target = pcall(self.draggedItems.getDropContainer, self.draggedItems)
            overFloor = okDrop and target == "floor"
        end
        if overFloor and NpcInventoryPanel.dropDraggedItemsToGround(self) then
            return true
        end
        if outsideDrag and ctrl.blocked then
            clearDragState(self)
        end
        local res = withNpcContext(npc, hotbar, self.player, ISInventoryPane.update, self)
        if outsideDrag then
            requestRefresh(DROP_REFRESH_TICKS)
            refreshVisibleInventories()
        end
        return res
    end
    pane.onMouseDown = function(self, x, y)
        if ctrl.win and ctrl.win.bringToTop then ctrl.win:bringToTop() end
        if ctrl.blocked then return true end
        pcall(withNpcContext, npc, hotbar, self.player, ISInventoryPane.onMouseDown, self, x, y)
        return true
    end
    pane.onMouseMove = function(self, dx, dy)
        if ctrl.blocked then return true end
        pcall(withNpcContext, npc, hotbar, self.player, ISInventoryPane.onMouseMove, self, dx, dy)
        return true
    end
    pane.onMouseUp = function(self, x, y)
        if ctrl.blocked then
            clearDragState(self)
            clearDragState(ISMouseDrag and ISMouseDrag.draggingFocus)
            return true
        end
        if NpcInventoryPanel.handleDrop(self, self.inventory) then
            return true
        end
        self.previousMouseUp = self.mouseOverOption
        clearDragState(self)
        return true
    end
    pane.onRightMouseUp = function(self, x, y)
        if ctrl.blocked then return true end
        withNpcContext(npc, hotbar, self.player, function()
            ISInventoryPane.onRightMouseUp(self, x, y)
            local clickedItems = collectSelectedPaneItems(self)
            local ctx = getPlayerContextMenu and getPlayerContextMenu(self.player)
            sanitizeNpcContextMenu(self, ctrl, ctx, clickedItems)
        end)
        return true
    end
    pane.onMouseDoubleClick = function(self, x, y)
        if self.vscroll and self:isMouseOverScrollBar() then
            return self.vscroll:onMouseDoubleClick(x - self.vscroll.x, y + self:getYScroll() - self.vscroll.y)
        end
        if ctrl.blocked then return true end
        if self.items and self.mouseOverOption and self.mouseOverOption ~= 0 then
            local row = self.items[self.mouseOverOption]
            if row and not (instanceof and instanceof(row, "InventoryItem")) and x < self.column2 then
                self.collapsed[row.name] = not self.collapsed[row.name]
                self:refreshContainer()
            end
        end
        return true
    end
end

local function createEmbeddedInventoryPage(ctrl, npc, win, playerNum)
    if not ISInventoryPage or not ISInventoryPage.new then return nil end
    local rootInv = npc and npc.getInventory and npc:getInventory() or nil
    if not rootInv then return nil end

    local npcHotbar = createNpcHotbar(npc)
    ctrl.hotbar = npcHotbar

    local page = ISInventoryPage:new(0, 0, 384, 420, rootInv, true, 1)
    page.anchorLeft = true
    page.anchorTop = true
    page.anchorRight = false
    page.anchorBottom = false
    page.keepOnScreen = false

    page.player = playerNum
    page.ownerCharacter = npc
    page.ownerHotbar = npcHotbar
    page.ownerName = ctrl.displayName
    page.erNpcId = ctrl.npcId
    page.erPanelCtrl = ctrl
    page.erWindowHost = win
    page.refreshBackpacks = refreshNpcInventoryPage
    page.update = updateNpcInventoryPage
    page.prerender = prerenderNpcInventoryPage
    page.render = renderNpcInventoryPage
    page.selectContainer = function(self, button)
        return withNpcContext(npc, npcHotbar, self.player, ISInventoryPage.selectContainer, self, button)
    end
    page.selectButtonForContainer = function(self, container)
        return withNpcContext(npc, npcHotbar, self.player, ISInventoryPage.selectButtonForContainer, self, container)
    end
    page.dropItemsInContainer = dropItemsInNpcContainer
    page.updateContainerHighlight = noop
    page.onMouseDown = function(self)
        if self.erWindowHost and self.erWindowHost.bringToTop then
            self.erWindowHost:bringToTop()
        end
        return true
    end
    page.onMouseMove = noop
    page.onMouseUp = function() return true end
    page.onRightMouseDownOutside = noop
    page.onMouseDownOutside = noop
    page.onMouseUpOutside = noop
    page.onResize = relayoutEmbeddedPage

    page:initialise()
    withNpcContext(npc, npcHotbar, playerNum, page.instantiate, page)
    if page.setPinned then page:setPinned() end

    if page.closeButton then page.closeButton:setVisible(false) end
    if page.pinButton then page.pinButton:setVisible(false) end
    if page.collapseButton then page.collapseButton:setVisible(false) end
    if page.infoButton then page.infoButton:setVisible(false) end
    if page.resizeWidget then page.resizeWidget:setVisible(false) end
    if page.resizeWidget2 then page.resizeWidget2:setVisible(false) end
    if page.controlsUI then
        page:removeChild(page.controlsUI)
        page.controlsUI = nil
    end

    if page.containerButtonPanel then
        page.containerButtonPanel.anchorLeft = false
        page.containerButtonPanel.anchorTop = false
        page.containerButtonPanel.anchorRight = false
        page.containerButtonPanel.anchorBottom = false
    end
    if page.inventoryPane then
        configureEmbeddedPane(page.inventoryPane, ctrl, npc, npcHotbar)
    end
    refreshNpcInventoryPage(page)
    relayoutEmbeddedPage(page)
    return page
end

-- ==================== Walk & Open Action ====================

local function ensureOpenActionClass()
    if openActionClass then return openActionClass end
    if not ISBaseTimedAction then return nil end

    local Action = ISBaseTimedAction:derive("ISOpenNpcInventoryAction")

    function Action:isValid()
        if not self.character or self.character:isDead() then return false end
        if not NpcMedicalPanel.isNpcLive(self.targetNpcId, self.targetNpc) then return false end
        if NpcMedicalPanel.isSameCar(self.character, self.targetNpc) then return true end
        local cz = math.floor(self.character:getZ() or 0)
        local nz = math.floor(self.targetNpc:getZ() or 0)
        if cz ~= nz then return false end
        local dx = (self.character:getX() or 0) - (self.targetNpc:getX() or 0)
        local dy = (self.character:getY() or 0) - (self.targetNpc:getY() or 0)
        return (dx * dx + dy * dy) <= 9.0
    end

    function Action:waitToStart()
        if self.character.isSeatedInVehicle and self.character:isSeatedInVehicle() then
            return false
        end
        self.character:faceThisObject(self.targetNpc)
        return self.character:shouldBeTurning()
    end

    function Action:update()
        self.character:faceThisObject(self.targetNpc)
        self:forceComplete()
    end

    function Action:perform()
        NpcInventoryPanel.displayPanel(self.character, self.targetNpc)
        ISBaseTimedAction.perform(self)
    end

    function Action:new(player, npc, npcId)
        local o = ISBaseTimedAction.new(self, player)
        o.targetNpc = npc
        o.targetNpcId = npcId
        o.maxTime = 0
        o.stopOnWalk = false
        o.stopOnRun = true
        return o
    end

    openActionClass = Action
    return openActionClass
end

-- ==================== Public Entry Points ====================

function NpcInventoryPanel.getActive(npcId)
    return npcId and activeWindows[npcId] or nil
end

function NpcInventoryPanel.close(npcId)
    local ctrl = npcId and activeWindows[npcId]
    if ctrl then
        ctrl:close()
    end
end

function NpcInventoryPanel.closeAll()
    local ids = {}
    for npcId, _ in pairs(activeWindows) do
        ids[#ids + 1] = npcId
    end
    for i = 1, #ids do
        NpcInventoryPanel.close(ids[i])
    end
    pendingRefreshTicks = 0
    pendingRefreshInterval = 0
end

function NpcInventoryPanel.open(player, npc)
    if not player or (player.isDead and player:isDead()) then return nil end
    local isNpc, npcId = NpcRuntime.isTargetNpc(npc)
    if not isNpc or not NpcMedicalPanel.isNpcLive(npcId, npc) then return nil end

    local existing = activeWindows[npcId]
    if existing and not existing.closed and existing.win and not existing.win.disposed then
        existing.win:setVisible(true)
        if existing.win.bringToTop then existing.win:bringToTop() end
        if not NpcMedicalPanel.isWithinRange(player, npc) then
            local sq = npc.getCurrentSquare and npc:getCurrentSquare()
            if sq and luautils and luautils.walkAdj then
                luautils.walkAdj(player, sq)
            end
        end
        return existing
    end

    if NpcMedicalPanel.isWithinRange(player, npc) then
        return NpcInventoryPanel.displayPanel(player, npc)
    end

    local sq = npc.getCurrentSquare and npc:getCurrentSquare()
    local OpenAction = ensureOpenActionClass()
    if sq and OpenAction and luautils and luautils.walkAdj and luautils.walkAdj(player, sq) then
        ISTimedActionQueue.add(OpenAction:new(player, npc, npcId))
    end
    return nil
end

function NpcInventoryPanel.displayPanel(player, npc, opts)
    opts = opts or {}
    local isNpc, npcId = NpcRuntime.isTargetNpc(npc)
    if not opts.previewWidget then
        if not player or (player.isDead and player:isDead()) then return nil end
        if not isNpc or not NpcMedicalPanel.isNpcLive(npcId, npc) then return nil end
        if player.faceThisObject then player:faceThisObject(npc) end
    else
        npcId = opts.npcId or npcId or "npc_preview"
    end

    NpcInventoryPanel.close(npcId)

    local displayName = opts.displayName or NpcRuntime.getDisplayName(npcId, npc)
    local core = getCore and getCore()
    local screenW = core and core:getScreenWidth() or 1280
    local screenH = core and core:getScreenHeight() or 720
    local width = opts.width or 400
    local height = opts.height or 500
    local wx = opts.x or math.max(20, screenW - width - 48)
    local wy = opts.y or math.max(50, math.floor((screenH - height) * 0.45))

    local ctrl = setmetatable({
        npc = npc,
        npcId = npcId,
        displayName = displayName,
        closed = false,
        blocked = opts.previewBlocked == true,
        blockingAlpha = (opts.previewBlocked == true) and 1.0 or 0.0,
        blockingMessage = nil,
        lastBlocked = nil,
        fixedPreviewBlocked = opts.previewBlocked,
        previewContainerName = opts.previewContainerName,
        previewContainerWeight = opts.previewContainerWeight,
        previewContainerCapacity = opts.previewContainerCapacity,
    }, PanelCtrl)

    local winScope = Kernel.scope("npc-inv:" .. tostring(npcId))
    local win = EmberUI.WindowHost:new(wx, wy, width, height, {
        title = getText("IGUI_ER_NpcInv_TitleFmt", displayName),
        resizable = true,
        minWidth = 380,
        minHeight = 360,
        closeOnEscape = true,
        moduleId = "er.npcInventory",
        scope = winScope,
        onClose = function()
            ctrl:cleanupOnClose()
        end,
        hostProps = { direction = "column", padding = 8, gap = 6, align = "stretch" },
    })
    ctrl.win = win
    win:initialise()
    win:instantiate()
    win:addToUIManager()

    ctrl.statusBadge = UI.Badge("READY", { h = 24, customLabel = getText("IGUI_ER_NpcInv_InRange") })
    ctrl.weightLabel = UI.Label(getText("IGUI_ER_NpcInv_MainBag"), { h = 24, muted = true, truncate = true })
    ctrl.btnMedical = UI.Button(getText("IGUI_ER_NpcInv_BtnMedical"), ctrl, ctrl.onOpenMedical, { h = 24, primary = false })

    local playerNum = player and player.getPlayerNum and player:getPlayerNum() or 0
    local embeddedWidget = opts.previewWidget or createEmbeddedInventoryPage(ctrl, npc, win, playerNum)
    if not embeddedWidget then
        win:close()
        return nil
    end
    if opts.previewWidget then
        local origPrevRender = opts.previewWidget.render
        opts.previewWidget.render = function(self)
            if type(origPrevRender) == "function" then origPrevRender(self) end
            NpcInventoryPanel.drawBlockingScrim(self, ctrl)
        end
    end
    embeddedWidget.anchorLeft = true
    embeddedWidget.anchorTop = true
    embeddedWidget.anchorRight = false
    embeddedWidget.anchorBottom = false
    embeddedWidget.keepOnScreen = false
    ctrl.page = opts.previewWidget and nil or embeddedWidget

    win:setContentSpec(UI.Column({ padding = 8, gap = 6, align = "stretch" }, {
        UI.Item(UI.Row({ gap = 6, align = "center" }, {
            UI.Item(ctrl.statusBadge, { basis = 90, grow = 0 }),
            UI.Item(ctrl.weightLabel, { basis = 0, grow = 1 }),
            UI.Item(ctrl.btnMedical, { basis = 86, grow = 0 }),
        }), { basis = 24, grow = 0 }),
        UI.Item(embeddedWidget, { basis = 0, grow = 1 }),
    }))

    local basePrerender = EmberUI.WindowHost.prerender
    win.prerender = function(self)
        ctrl:updateProximityState()
        basePrerender(self)
    end

    ctrl:updateProximityState()
    activeWindows[npcId] = ctrl
    return ctrl
end

-- ==================== Microkernel Registration ====================

local function onRefreshTick()
    if pendingRefreshTicks <= 0 then return end
    pendingRefreshTicks = pendingRefreshTicks - 1
    pendingRefreshInterval = pendingRefreshInterval - 1
    if pendingRefreshInterval <= 0 then
        refreshVisibleInventories()
        pendingRefreshInterval = DROP_REFRESH_INTERVAL
    end
end

local function onClothingUpdated(character)
    if not character then return end
    for _, ctrl in pairs(activeWindows) do
        if ctrl and ctrl.npc == character then
            if ctrl.hotbar then
                ctrl.hotbar.needsRefresh = true
            end
            refreshSingleCtrl(ctrl)
        end
    end
end

Kernel.registerModule({
    id = "npc-inventory",
    onWorldReady = function(scope)
        if ISInventoryPane and type(ISInventoryPane.onMouseUp) == "function" then
            scope:wrap(ISInventoryPane, "onMouseUp", function(origOnMouseUp)
                return function(self, x, y)
                    if not isNpcOwnedPane(self) and NpcInventoryPanel.tryHandleExternalPaneDrop(self) then
                        return true
                    end
                    return origOnMouseUp(self, x, y)
                end
            end)
        end

        if ISInventoryPage then
            if type(ISInventoryPage.dropItemsInContainer) == "function" then
                scope:wrap(ISInventoryPage, "dropItemsInContainer", function(origDrop)
                    return function(self, button)
                        if not (self and self.erNpcId ~= nil) and NpcInventoryPanel.tryHandleExternalPageDrop(self, button) then
                            return true
                        end
                        return origDrop(self, button)
                    end
                end)
            end

            if type(ISInventoryPage.dirtyUI) == "function" then
                local inDirtyUI = false
                scope:wrap(ISInventoryPage, "dirtyUI", function(origDirtyUI)
                    return function()
                        if inDirtyUI then return end
                        inDirtyUI = true
                        local ok, err = pcall(origDirtyUI)
                        for _, ctrl in pairs(activeWindows) do
                            refreshSingleCtrl(ctrl)
                        end
                        inDirtyUI = false
                        if not ok then error(err) end
                    end
                end)
            end
        end

        if ISHotbar and type(ISHotbar.onMouseUp) == "function" then
            scope:wrap(ISHotbar, "onMouseUp", function(origHotbarMouseUp)
                return function(self, x, y)
                    if ISMouseDrag and ISMouseDrag.dragging and #ISMouseDrag.dragging > 0
                        and isNpcOwnedPane(ISMouseDrag.draggingFocus)
                    then
                        local dragOwner = resolvePaneOwner(ISMouseDrag.draggingFocus)
                        local hotbarOwner = self and (self.chr or self.character)
                        if dragOwner and hotbarOwner and dragOwner ~= hotbarOwner then
                            clearDragState(ISMouseDrag.draggingFocus)
                            return
                        end
                    end
                    return origHotbarMouseUp(self, x, y)
                end
            end)
        end

        local hotbarActionClasses = { ISAttachItemHotbar, ISDetachItemHotbar, ISEquipWeaponAction, ISUnequipAction }
        for _, cls in ipairs(hotbarActionClasses) do
            if cls then
                if type(cls.animEvent) == "function" then
                    scope:wrap(cls, "animEvent", function(origAnim)
                        return function(self, event, parameter)
                            return withActionHotbar(self, origAnim, event, parameter)
                        end
                    end)
                end
                if type(cls.perform) == "function" and (cls == ISEquipWeaponAction or cls == ISUnequipAction) then
                    scope:wrap(cls, "perform", function(origPerform)
                        return function(self)
                            return withActionHotbar(self, origPerform)
                        end
                    end)
                end
            end
        end

        if Events and Events.OnClothingUpdated then
            scope:on(Events.OnClothingUpdated, onClothingUpdated)
        end

        scope:defer(NpcInventoryPanel.closeAll)
    end,
    tick = { stage = "WORLD_SYNC", fn = onRefreshTick },
    onTeardown = function()
        NpcInventoryPanel.closeAll()
    end,
})

return NpcInventoryPanel
