local Kernel = require "EmberRoutes/Kernel"
local NpcRuntime = require "EmberRoutes/Npc/NpcRuntime"

local NpcMedicalPanel = {}
local activePanels = {}
local openActionClass = nil

function NpcMedicalPanel.isSameCar(a, b)
    return a ~= nil and b ~= nil
        and ISHealthPanel ~= nil
        and ISHealthPanel.IsCharactersInSameCar ~= nil
        and ISHealthPanel.IsCharactersInSameCar(a, b) == true
end

function NpcMedicalPanel.isNpcLive(npcId, npc)
    if not npc or not npcId then return false end
    if npc.isDead and npc:isDead() then return false end
    if not NpcRuntime.isAttached(npcId) then return false end
    if npc.getCurrentSquare and npc:getCurrentSquare() == nil then return false end
    return true
end

function NpcMedicalPanel.isWithinRange(actor, npc)
    if not actor or not npc then return false end
    if NpcMedicalPanel.isSameCar(actor, npc) then return true end
    local az = math.floor(actor:getZ() or 0)
    local nz = math.floor(npc:getZ() or 0)
    if az ~= nz then return false end
    local dx = (actor:getX() or 0) - (npc:getX() or 0)
    local dy = (actor:getY() or 0) - (npc:getY() or 0)
    return (dx * dx + dy * dy) <= 4.0
end

local function ensureOpenActionClass()
    if openActionClass then return openActionClass end
    if not ISBaseTimedAction then return nil end

    local Action = ISBaseTimedAction:derive("ISOpenNpcMedicalAction")

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
        NpcMedicalPanel.displayHealthPanel(self.character, self.targetNpc)
        ISBaseTimedAction.perform(self)
    end

    function Action:new(doctor, npc, npcId)
        local o = ISBaseTimedAction.new(self, doctor)
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

local function clearHealthWindowRef(npcObj, wrap)
    if ISMedicalCheckAction and ISMedicalCheckAction.HealthWindows and npcObj then
        if ISMedicalCheckAction.HealthWindows[npcObj] == wrap then
            ISMedicalCheckAction.HealthWindows[npcObj] = nil
        end
    end
end

local function releasePanelRecord(npcId, wrap)
    if npcId and activePanels[npcId] == wrap then
        activePanels[npcId] = nil
    end
    local targetNpc = wrap and wrap.targetNpc
    clearHealthWindowRef(targetNpc, wrap)
end

function NpcMedicalPanel.close(npcId)
    local wrap = npcId and activePanels[npcId]
    if not wrap then return end
    activePanels[npcId] = nil
    clearHealthWindowRef(wrap.targetNpc, wrap)
    if wrap.nested then
        wrap.nested.otherPlayer = nil
    end
    if wrap.removeFromUIManager then
        pcall(wrap.removeFromUIManager, wrap)
    end
end

function NpcMedicalPanel.closeAll()
    local ids = {}
    for npcId, _ in pairs(activePanels) do
        ids[#ids + 1] = npcId
    end
    for i = 1, #ids do
        NpcMedicalPanel.close(ids[i])
    end
end

function NpcMedicalPanel.open(doctor, npc)
    if not doctor or (doctor.isDead and doctor:isDead()) then return end
    local isNpc, npcId = NpcRuntime.isTargetNpc(npc)
    if not isNpc or not NpcMedicalPanel.isNpcLive(npcId, npc) then return end

    local existing = activePanels[npcId]
    if existing and existing.getIsVisible and existing:getIsVisible() then
        if existing.bringToTop then existing:bringToTop() end
        if not NpcMedicalPanel.isWithinRange(doctor, npc) then
            local sq = npc.getCurrentSquare and npc:getCurrentSquare()
            if sq and luautils and luautils.walkAdj then
                luautils.walkAdj(doctor, sq)
            end
        end
        return
    end

    if NpcMedicalPanel.isWithinRange(doctor, npc) then
        NpcMedicalPanel.displayHealthPanel(doctor, npc)
        return
    end

    local sq = npc.getCurrentSquare and npc:getCurrentSquare()
    local OpenAction = ensureOpenActionClass()
    if sq and OpenAction and luautils and luautils.walkAdj and luautils.walkAdj(doctor, sq) then
        ISTimedActionQueue.add(OpenAction:new(doctor, npc, npcId))
    end
end

function NpcMedicalPanel.displayHealthPanel(doctor, npc)
    if not doctor or (doctor.isDead and doctor:isDead()) then return nil end
    local isNpc, npcId = NpcRuntime.isTargetNpc(npc)
    if not isNpc or not NpcMedicalPanel.isNpcLive(npcId, npc) then return nil end
    if not ISHealthPanel or not ISHealthPanel.new then return nil end

    if doctor.faceThisObject then
        doctor:faceThisObject(npc)
    end

    NpcMedicalPanel.close(npcId)

    local playerNum = doctor.getPlayerNum and doctor:getPlayerNum() or 0
    local screenLeft = getPlayerScreenLeft and getPlayerScreenLeft(playerNum) or 0
    local screenTop = getPlayerScreenTop and getPlayerScreenTop(playerNum) or 0
    local x = screenLeft + 70
    local y = screenTop + 50

    local healthPanel = ISHealthPanel:new(npc, x, y, 400, 400)
    healthPanel:initialise()

    local displayName = NpcRuntime.getDisplayName(npcId, npc)
    local title = (getTextOrNull and getTextOrNull("IGUI_health_playerHealth", displayName))
        or getText("IGUI_ER_NpcMed_TitleFmt", displayName)
    local wrap = healthPanel:wrapInCollapsableWindow(title, false)
    wrap:addToUIManager()

    healthPanel.character = npc
    healthPanel.otherPlayer = doctor
    healthPanel.doctorLevel = doctor.getPerkLevel and doctor:getPerkLevel(Perks.Doctor) or 0
    if healthPanel.setOtherPlayer then
        healthPanel:setOtherPlayer(doctor)
    end
    healthPanel.isNpcMedicalPanel = true
    healthPanel.erNpcId = npcId

    if ISMedicalCheckAction and ISMedicalCheckAction.HealthWindows then
        ISMedicalCheckAction.HealthWindows[npc] = wrap
    end

    wrap.targetNpc = npc
    wrap.erNpcId = npcId
    wrap.doctor = doctor
    local origClose = wrap.close
    wrap.close = function(self)
        releasePanelRecord(self.erNpcId, self)
        if self.removeFromUIManager then
            self:removeFromUIManager()
        end
        if type(origClose) == "function" then
            return origClose(self)
        end
    end

    activePanels[npcId] = wrap
    return wrap
end

Kernel.registerModule({
    id = "npc-medical",
    onWorldReady = function(scope)
        if ISHealthPanel then
            if type(ISHealthPanel.update) == "function" then
                scope:wrap(ISHealthPanel, "update", function(origUpdate)
                    return function(self)
                        local isNpc, npcId = NpcRuntime.isTargetNpc(self and self.character)
                        if self and self.otherPlayer and (self.isNpcMedicalPanel or isNpc) then
                            npcId = self.erNpcId or npcId
                            local wrap = self.parent
                            if not wrap or (wrap.getIsVisible and not wrap:getIsVisible()) then
                                if wrap then
                                    releasePanelRecord(npcId, wrap)
                                    if wrap.removeFromUIManager then wrap:removeFromUIManager() end
                                end
                                self.otherPlayer = nil
                                return
                            end

                            local patient = self.getPatient and self:getPatient() or self.character
                            local doctor = self.getDoctor and self:getDoctor() or self.otherPlayer
                            if not doctor or (doctor.isDead and doctor:isDead())
                                or not NpcMedicalPanel.isNpcLive(npcId, patient)
                            then
                                NpcMedicalPanel.close(npcId)
                                if wrap.removeFromUIManager then wrap:removeFromUIManager() end
                                return
                            end

                            self.characterX = patient:getX()
                            self.characterY = patient:getY()
                            self.otherPlayerX = doctor:getX()
                            self.otherPlayerY = doctor:getY()

                            local inRange = NpcMedicalPanel.isWithinRange(doctor, patient)
                                or (ISHealthPanel.cheat == true)
                            if not inRange then
                                if ISPanelJoypad and ISPanelJoypad.update then
                                    ISPanelJoypad.update(self)
                                end
                                local name = NpcRuntime.getDisplayName(npcId, patient)
                                self.blockingMessage = (getTextOrNull and getTextOrNull("IGUI_TradingUI_TooFarAway", name))
                                    or getText("IGUI_ER_NpcInv_TooFarMsg", name)
                                self.blockingAlpha = math.min(1.0, (self.blockingAlpha or 0.0) + 0.05)
                                return
                            end

                            self.blockingMessage = nil
                            self.blockingAlpha = math.max(0.0, (self.blockingAlpha or 0.0) - 0.05)
                            local savedOther = self.otherPlayer
                            self.otherPlayer = nil
                            local ok, err = pcall(origUpdate, self)
                            self.otherPlayer = savedOther
                            if not ok then
                                error(err)
                            end
                            return
                        end
                        return origUpdate(self)
                    end
                end)
            end

            if type(ISHealthPanel.DidPatientMove) == "function" then
                scope:wrap(ISHealthPanel, "DidPatientMove", function(origDidMove)
                    return function(doctor, patient, patientX, patientY)
                        local isNpc, npcId = NpcRuntime.isTargetNpc(patient)
                        if isNpc then
                            if not doctor or (doctor.isDead and doctor:isDead())
                                or not NpcMedicalPanel.isNpcLive(npcId, patient)
                            then
                                return true
                            end
                            return not NpcMedicalPanel.isWithinRange(doctor, patient)
                        end
                        return origDidMove(doctor, patient, patientX, patientY)
                    end
                end)
            end

            if type(ISHealthPanel.onCheat) == "function" then
                scope:wrap(ISHealthPanel, "onCheat", function(origOnCheat)
                    return function(bodyPart, action, player, otherPlayer)
                        if otherPlayer ~= nil and NpcRuntime.isTargetNpc(player) then
                            if ISHealthPanel.onCheatCurrentPlayer then
                                return ISHealthPanel.onCheatCurrentPlayer(bodyPart, action, player)
                            end
                        end
                        return origOnCheat(bodyPart, action, player, otherPlayer)
                    end
                end)
            end
        end

        scope:defer(NpcMedicalPanel.closeAll)
    end,
    onTeardown = function()
        NpcMedicalPanel.closeAll()
    end,
})

return NpcMedicalPanel
