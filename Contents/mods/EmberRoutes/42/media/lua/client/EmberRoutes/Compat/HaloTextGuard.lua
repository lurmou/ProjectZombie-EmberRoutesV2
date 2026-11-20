local Kernel = require "EmberRoutes/Kernel"

local METHODS = { "addText", "addTextWithArrow", "addGoodText", "addBadText" }

local HaloTextGuard = {}

local function isLocalPlayer(character)
    if not character or not character.isLocalPlayer then return false end
    local ok, result = pcall(character.isLocalPlayer, character)
    return ok and result == true
end

local function installGuards(scope)
    local helper = _G and _G.HaloTextHelper or HaloTextHelper
    if not helper then return false end
    for i = 1, #METHODS do
        local name = METHODS[i]
        if type(helper[name]) == "function" then
            scope:wrap(helper, name, function(base)
                return function(player, ...)
                    if not isLocalPlayer(player) then
                        return
                    end
                    return base(player, ...)
                end
            end)
        end
    end
    return true
end

Kernel.registerCompat({
    id = "compat-halo-text-guard",
    apply = function(scope)
        installGuards(scope)
    end,
})

return HaloTextGuard
