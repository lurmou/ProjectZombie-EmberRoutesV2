local StatusVisual = require "EmberUI/Widgets/StatusVisual"
local Kernel = require "EmberRoutes/Kernel"
local SquadKeys = require "EmberRoutes/Squad/SquadKeys"
local SquadService = require "EmberRoutes/Squad/SquadService"

local SquadWheel = {}

local wheelState = {
    menu = nil,
    open = false,
    mode = "hold", -- "hold" | "click"
    heldKey = nil,
    scope = nil,
}

local function resolveHudApi()
    return EmberRoutes and EmberRoutes.api and EmberRoutes.api.squadHud or nil
end

local function closeWheel()
    local m = wheelState.menu
    wheelState.open = false
    wheelState.heldKey = nil
    wheelState.mode = "click"
    if m then
        if m.removeFromUIManager then
            pcall(m.removeFromUIManager, m)
        end
        if m.setVisible then
            pcall(m.setVisible, m, false)
        end
    end
end

local function isAnyModalOrTextFocused()
    if MainScreen and MainScreen.instance and MainScreen.instance.isVisible and MainScreen.instance:isVisible() then
        return true
    end
    if UIManager then
        if UIManager.isModalVisible and UIManager.isModalVisible() then
            return true
        end
        if UIManager.getFocus and UIManager.getFocus() ~= nil then
            local focus = UIManager.getFocus()
            if focus ~= wheelState.menu and instanceof and instanceof(focus, "ISTextEntryBox") then
                return true
            end
        end
    end
    if ISChat and ISChat.instance and ISChat.instance.textEntry and ISChat.instance.textEntry.isFocused then
        local ok, focused = pcall(ISChat.instance.textEntry.isFocused, ISChat.instance.textEntry)
        if ok and focused then
            return true
        end
    end
    return false
end

local function isVanillaRadialOpen()
    if type(getPlayerRadialMenu) ~= "function" then
        return false
    end
    local ok, rm = pcall(getPlayerRadialMenu, 0)
    if ok and rm and rm.isReallyVisible and rm:isReallyVisible() then
        return true
    end
    return false
end

local function isZoneEditorBlocking()
    local hudApi = resolveHudApi()
    if hudApi and hudApi.isBlockedByEditor and hudApi.isBlockedByEditor() then
        return true
    end
    return false
end

local function canOpenWheel(bypassKeyGuards)
    if type(getSpecificPlayer) ~= "function" then
        return false
    end
    local player = getSpecificPlayer(0)
    if not player or (player.isDead and player:isDead()) then
        return false
    end
    if isAnyModalOrTextFocused() or isVanillaRadialOpen() or isZoneEditorBlocking() then
        return false
    end
    if not bypassKeyGuards then
        local queue = ISTimedActionQueue and ISTimedActionQueue.queues and ISTimedActionQueue.queues[player]
        if queue and #queue.queue > 0 and player.isCurrentlyBusy and player:isCurrentlyBusy() then
            return false
        end
    end
    return true
end

local function sliceIcon(name)
    if StatusVisual and StatusVisual.get then
        local tex = StatusVisual.get(name)
        if tex then
            return tex
        end
    end
    return nil
end

local function onSliceOrder()
    SquadService.toggleOrderMode(0)
end

local function onSliceFormation()
    SquadService.cycleFormation()
end

local function onSliceSpacing()
    SquadService.cycleSpacing()
end

local function onSliceRoe()
    SquadService.cycleRoe()
end

local function onSliceCqb()
    SquadService.toggleCqb()
end

local function onSliceAutoVehicle()
    SquadService.toggleAutoVehicle()
end

local function onSliceVehicleToggle()
    local res = SquadService.commandVehicleToggle(0)
    local hudApi = resolveHudApi()
    if hudApi and hudApi.showNotice then
        if res == "NO_VEHICLE" then
            hudApi.showNotice(getText("IGUI_ER_Squad_NoticeNoVehicle"), true)
        elseif res == "FULL" then
            hudApi.showNotice(getText("IGUI_ER_Squad_NoticeVehicleFull"), true)
        end
    end
end

local function onSliceToggleHud()
    local hudApi = resolveHudApi()
    if hudApi and hudApi.toggle then
        hudApi.toggle()
    end
end

local function ensureWheelWidget()
    if wheelState.menu then
        return wheelState.menu
    end
    if not ISRadialMenu then
        return nil
    end
    local menu = ISRadialMenu:new(0, 0, 74, 196, 0)
    menu:initialise()
    menu:instantiate()
    menu:setAlwaysOnTop(true)

    local origUndisplay = menu.undisplay
    menu.undisplay = function(self)
        wheelState.open = false
        wheelState.heldKey = nil
        if type(origUndisplay) == "function" then
            origUndisplay(self)
        end
    end
    menu.onRightMouseDown = function(_self)
        closeWheel()
        return true
    end
    menu.onRightMouseUp = function(_self)
        closeWheel()
        return true
    end
    wheelState.menu = menu
    return menu
end

local function populateSlices(menu)
    menu:clear()
    local cfg = SquadService.getConfig()
    local hudApi = resolveHudApi()
    local hudOpen = hudApi and hudApi.isOpen and hudApi.isOpen() or false

    local orderNextLbl = (cfg.orderMode == "HOLD_POSITION")
        and getText("IGUI_ER_Wheel_OrderFollow")
        or getText("IGUI_ER_Wheel_OrderHold")
    local fmtName = getText(SquadService.KEYS.FMT[cfg.formation] or "IGUI_ER_Squad_Fmt_WEDGE")
    local spName = getText(SquadService.KEYS.SP[cfg.spacing] or "IGUI_ER_Squad_Sp_STANDARD")
    local roeName = getText(SquadService.KEYS.ROE[cfg.roe] or "IGUI_ER_Squad_Roe_FREE_FIRE")
    local cqbLbl = cfg.cqb and getText("IGUI_ER_Wheel_CqbOn") or getText("IGUI_ER_Wheel_CqbOff")
    local autoCarLbl = (cfg.autoVehicle ~= false)
        and getText("IGUI_ER_Wheel_AutoCarOn")
        or getText("IGUI_ER_Wheel_AutoCarOff")
    local vehLbl = cfg.anyInVehicle
        and getText("IGUI_ER_Wheel_DismountAll")
        or getText("IGUI_ER_Wheel_BoardAll")
    local hudLbl = hudOpen
        and getText("IGUI_ER_Wheel_HudHide")
        or getText("IGUI_ER_Wheel_HudShow")

    menu:addSlice(orderNextLbl, sliceIcon(cfg.orderMode == "HOLD_POSITION" and "active" or "paused"), onSliceOrder)
    menu:addSlice(getText("IGUI_ER_Wheel_FormationFmt", fmtName), sliceIcon("reconcile"), onSliceFormation)
    menu:addSlice(getText("IGUI_ER_Wheel_SpacingFmt", spName), sliceIcon("transit"), onSliceSpacing)
    menu:addSlice(getText("IGUI_ER_Wheel_RoeFmt", roeName), sliceIcon("combat"), onSliceRoe)
    menu:addSlice(cqbLbl, sliceIcon("shield"), onSliceCqb)
    menu:addSlice(autoCarLbl, sliceIcon("gear"), onSliceAutoVehicle)
    menu:addSlice(vehLbl, sliceIcon("cart"), onSliceVehicleToggle)
    menu:addSlice(hudLbl, sliceIcon("eye"), onSliceToggleHud)
end

local function openInternal(mode, heldKey)
    local menu = ensureWheelWidget()
    if not menu then
        return false
    end
    populateSlices(menu)
    menu:center()
    menu:addToUIManager()
    menu:setVisible(true)
    if menu.bringToTop then
        menu:bringToTop()
    end
    wheelState.open = true
    wheelState.mode = mode
    wheelState.heldKey = heldKey
    return true
end

function SquadWheel.openClickMode()
    if wheelState.open and wheelState.mode == "click" then
        closeWheel()
        return false
    end
    if not canOpenWheel(true) then
        return false
    end
    return openInternal("click", nil)
end

function SquadWheel.close()
    closeWheel()
end

function SquadWheel.isOpen()
    return wheelState.open == true
end

local function onKeyStartPressed(key)
    local bindKey = SquadKeys.getWheelKeyCode()
    if key ~= bindKey then
        return
    end
    if wheelState.open then
        return
    end
    if #SquadService.getMembers() == 0 then
        return
    end
    if not canOpenWheel(false) then
        return
    end
    openInternal("hold", key)
end

local function onKeyPressed(key)
    if not wheelState.open then
        return
    end
    local escKey = (Keyboard and Keyboard.KEY_ESCAPE) or 1
    if key == escKey then
        closeWheel()
        return
    end
    if wheelState.mode == "hold" and key == wheelState.heldKey then
        local menu = wheelState.menu
        local sliceIndex = -1
        if menu and menu.javaObject and menu.javaObject.getSliceIndexFromMouse then
            sliceIndex = menu.javaObject:getSliceIndexFromMouse(menu:getMouseX(), menu:getMouseY())
        end
        local cmd = (menu and sliceIndex ~= nil and sliceIndex >= 0) and menu:getSliceCommand(sliceIndex + 1) or nil
        closeWheel()
        if cmd and cmd[1] then
            cmd[1](cmd[2], cmd[3], cmd[4], cmd[5], cmd[6], cmd[7])
        end
    end
end

local function watchdogTick()
    if not wheelState.open then
        return
    end
    if wheelState.mode == "hold" and wheelState.heldKey and type(isKeyDown) == "function" then
        if not isKeyDown(wheelState.heldKey) then
            -- 切屏丢 KeyUp 防卡死：仅安全收起，不误触发指令
            closeWheel()
        end
    elseif isAnyModalOrTextFocused() then
        closeWheel()
    end
end

Kernel.exposeApi("squadWheel", SquadWheel)

Kernel.registerModule({
    id = "squad-wheel",
    onWorldReady = function(scope)
        wheelState.scope = scope
        if Events then
            if Events.OnKeyStartPressed then
                scope:on(Events.OnKeyStartPressed, onKeyStartPressed)
            end
            if Events.OnKeyPressed then
                scope:on(Events.OnKeyPressed, onKeyPressed)
            end
        end
        scope:defer(function()
            closeWheel()
            wheelState.menu = nil
            wheelState.scope = nil
        end)
    end,
    onTeardown = function()
        closeWheel()
        wheelState.menu = nil
        wheelState.scope = nil
    end,
    tick = { stage = "WORLD_SYNC", fn = watchdogTick },
    onTickPaused = watchdogTick,
})

return SquadWheel
