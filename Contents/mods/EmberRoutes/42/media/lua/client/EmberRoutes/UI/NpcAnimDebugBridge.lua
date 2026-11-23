local NpcRuntime = require "EmberRoutes/Npc/NpcRuntime"

local NpcAnimDebugBridge = {}

local function ensureVanillaPanelLoaded()
    if not ISAnimDebugMonitor then
        pcall(require, "DebugUIs/DebugMenu/Anims/ISAnimDebugMonitor")
    end
    return ISAnimDebugMonitor ~= nil
end

local function isTargetLive(char)
    if not char then return false end
    local okDead, dead = pcall(function() return char:isDead() end)
    if okDead and dead then return false end
    if char.getCurrentSquare then
        local okSq, sq = pcall(function() return char:getCurrentSquare() end)
        if not okSq or not sq then return false end
    end
    return true
end

local function callWithTargetPlayer(targetChar, fn, ...)
    if not targetChar or not getPlayer then
        return fn(...)
    end
    local origGetPlayer = _G.getPlayer
    _G.getPlayer = function() return targetChar end
    local ok, res = pcall(fn, ...)
    _G.getPlayer = origGetPlayer
    if not ok then
        error(res)
    end
    return res
end

local function resetPanelMonitorState(panel, detachTarget)
    if not panel then return end
    if detachTarget and panel.erTargetChar and panel.erTargetChar.setDebugMonitor then
        pcall(function()
            local cur = panel.erTargetChar:getDebugMonitor()
            if cur == panel.monitor or panel.monitor == nil then
                panel.erTargetChar:setDebugMonitor(nil)
            end
        end)
    end
    panel.erTargetChar = nil
    panel.monitor = nil
    panel.selectedVar = false
    panel.init = false
    if panel._erBaseTitle then
        panel.title = panel._erBaseTitle
    end
    if panel.buttonToggleMonitor and panel.buttonToggleMonitor.enableCancelColor then
        panel.buttonToggleMonitor:enableCancelColor()
    end
    if panel.toggleEditEnabled then
        panel:toggleEditEnabled(false)
    end
    if panel.clearLog then
        panel:clearLog()
    end
    if panel.floatPlotter and panel.floatPlotter.setData then
        panel.floatPlotter:setData(nil)
    end
end

local function rebindExistingPanelCallbacks(panel, origOnClick, newOnClick, origOnCombo, newOnCombo)
    if not panel or not panel.children then return end
    for _, child in pairs(panel.children) do
        if child then
            if child.onclick == origOnClick then
                child.onclick = newOnClick
            end
            if child.onChange == origOnCombo then
                child.onChange = newOnCombo
            end
        end
    end
end

local function patchVanillaPanelClass()
    if not ensureVanillaPanelLoaded() then return false end
    if ISAnimDebugMonitor._erNpcPatched then return true end
    ISAnimDebugMonitor._erNpcPatched = true

    local origOnClick = ISAnimDebugMonitor.onClick
    local origOnCombo = ISAnimDebugMonitor.onCombo
    local origUpdate = ISAnimDebugMonitor.update
    local origClose = ISAnimDebugMonitor.close

    local function wrappedOnClick(self, _button)
        local target = self.erTargetChar
        if target ~= nil then
            if not isTargetLive(target) then
                resetPanelMonitorState(self, true)
                return
            end
            return callWithTargetPlayer(target, origOnClick, self, _button)
        end
        return origOnClick(self, _button)
    end

    local function wrappedOnCombo(self, _combo)
        local target = self.erTargetChar
        if target ~= nil then
            if not isTargetLive(target) then
                resetPanelMonitorState(self, true)
                return
            end
            return callWithTargetPlayer(target, origOnCombo, self, _combo)
        end
        return origOnCombo(self, _combo)
    end

    ISAnimDebugMonitor.onClick = wrappedOnClick
    ISAnimDebugMonitor.onCombo = wrappedOnCombo

    ISAnimDebugMonitor.update = function(self)
        if self.erTargetChar and not isTargetLive(self.erTargetChar) then
            resetPanelMonitorState(self, true)
        elseif self.erTargetChar and self.monitor and self.erTargetChar.getDebugMonitor then
            local activeMon = self.erTargetChar:getDebugMonitor()
            if activeMon ~= self.monitor then
                resetPanelMonitorState(self, false)
            end
        end
        return origUpdate(self)
    end

    ISAnimDebugMonitor.close = function(self)
        if self.erTargetChar then
            resetPanelMonitorState(self, true)
        end
        return origClose(self)
    end

    if ISAnimDebugMonitor.instance then
        rebindExistingPanelCallbacks(ISAnimDebugMonitor.instance, origOnClick, wrappedOnClick, origOnCombo, wrappedOnCombo)
    end
    return true
end

function NpcAnimDebugBridge.detach()
    local panel = ISAnimDebugMonitor and ISAnimDebugMonitor.instance or nil
    if panel and panel.erTargetChar then
        resetPanelMonitorState(panel, true)
    end
    local player = getPlayer and getPlayer() or nil
    if player and player.getDebugMonitor and player:getDebugMonitor() then
        local mon = player:getDebugMonitor()
        if panel and panel.monitor == mon then
            panel.monitor = nil
        end
    end
end

function NpcAnimDebugBridge.openForNpc(npcId, npcObj)
    if not patchVanillaPanelClass() then
        print("[EmberRoutes][M3] ISAnimDebugMonitor unavailable")
        return false
    end
    if not AnimatorDebugMonitor or not AnimatorDebugMonitor.new then
        print("[EmberRoutes][M3] AnimatorDebugMonitor global class unavailable")
        return false
    end
    local npc = npcObj or NpcRuntime.getLiveObject(npcId)
    if not isTargetLive(npc) then
        print("[EmberRoutes][M3] AnimDebugMonitor target NPC offline or dead: " .. tostring(npcId))
        return false
    end

    local panel = ISAnimDebugMonitor.OnOpenPanel()
    if not panel then
        return false
    end
    if not panel._erBaseTitle then
        panel._erBaseTitle = panel.title or "Anim Debug Monitor"
    end

    if panel.erTargetChar and panel.erTargetChar ~= npc and panel.erTargetChar.setDebugMonitor then
        pcall(function() panel.erTargetChar:setDebugMonitor(nil) end)
    end
    local localPlayer = getPlayer and getPlayer() or nil
    if localPlayer and localPlayer.getDebugMonitor and localPlayer:getDebugMonitor() then
        pcall(function() localPlayer:setDebugMonitor(nil) end)
    end
    if localPlayer then
        panel.player = localPlayer
        panel.playerNum = localPlayer.getPlayerNum and localPlayer:getPlayerNum() or 0
    end

    local mon = AnimatorDebugMonitor.new(npc)
    if mon.setDoTickStamps then
        mon:setDoTickStamps(true)
    end
    npc:setDebugMonitor(mon)

    panel.erTargetChar = npc
    panel.monitor = mon
    panel.selectedVar = false
    panel.init = false
    panel.title = tostring(panel._erBaseTitle) .. " [" .. tostring(npcId) .. "]"
    if panel.buttonToggleMonitor and panel.buttonToggleMonitor.enableAcceptColor then
        panel.buttonToggleMonitor:enableAcceptColor()
    end
    if panel.toggleEditEnabled then
        panel:toggleEditEnabled(true)
    end
    print("[EmberRoutes][M3] AnimDebugMonitor attached -> " .. tostring(npcId))
    return true
end

return NpcAnimDebugBridge
