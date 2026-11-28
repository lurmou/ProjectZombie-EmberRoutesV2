local Kernel = require "EmberRoutes/Kernel"

local NpcRackGuard = {}

local function isEmberNpc(character)
    if not character or not EmberRoutesJava or not EmberRoutesJava.getNpcId then
        return false
    end
    local ok, id = pcall(EmberRoutesJava.getNpcId, character)
    return ok and id ~= nil
end

local function installGuard(scope)
    if not ISRackFirearm or type(ISRackFirearm.new) ~= "function" then
        return false
    end
    scope:wrap(ISRackFirearm, "new", function(base)
        return function(self, character, gun, ...)
            if isEmberNpc(character) then
                -- 原版 ISReloadWeaponAction.OnPlayerAttackFinished 为泵动霰弹/栓动枪自动排 ISRackFirearm；
                -- 对 IsoNPC 返回 ignoreAction=true，完全交由纯 Java NpcReloadAction 接管拉栓并防止 ISTimedActionQueue.queues 泄漏。
                return { ignoreAction = true }
            end
            return base(self, character, gun, ...)
        end
    end)
    if ISTimedActionQueue and type(ISTimedActionQueue.clear) == "function" then
        scope:wrap(ISTimedActionQueue, "clear", function(base)
            return function(character)
                if isEmberNpc(character) then
                    character:StopAllActionQueue()
                    if ISTimedActionQueue.queues then
                        ISTimedActionQueue.queues[character] = nil
                    end
                    return nil
                end
                return base(character)
            end
        end)
    end
    return true
end

Kernel.registerCompat({
    id = "compat-npc-rack-guard",
    apply = function(scope)
        installGuard(scope)
    end,
})

return NpcRackGuard
