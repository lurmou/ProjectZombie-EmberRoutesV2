local SquadKeys = {}

SquadKeys.SECTION_LABEL = "[EmberRoutes]"
SquadKeys.WHEEL_KEY_NAME = "EmberRoutesSquadWheel"

local function resolveDefaultPeriodKey()
    if Keyboard and Keyboard.KEY_PERIOD then
        return Keyboard.KEY_PERIOD
    end
    return 52
end

local function ensureKeyBindingsRegistered()
    local kb = _G and _G.keyBinding or nil
    if type(kb) ~= "table" then
        return
    end
    local hasSection = false
    local hasWheelKey = false
    for i = 1, #kb do
        local entry = kb[i]
        if entry then
            if entry.value == SquadKeys.SECTION_LABEL then
                hasSection = true
            elseif entry.value == SquadKeys.WHEEL_KEY_NAME then
                hasWheelKey = true
            end
        end
    end
    if not hasSection then
        table.insert(kb, { value = SquadKeys.SECTION_LABEL })
    end
    if not hasWheelKey then
        table.insert(kb, {
            value = SquadKeys.WHEEL_KEY_NAME,
            key = resolveDefaultPeriodKey(),
        })
    end
end

ensureKeyBindingsRegistered()

function SquadKeys.getWheelKeyCode()
    if type(getCore) == "function" then
        local ok, core = pcall(getCore)
        if ok and core and type(core.getKey) == "function" then
            local kOk, code = pcall(core.getKey, core, SquadKeys.WHEEL_KEY_NAME)
            if kOk and type(code) == "number" and code > 0 then
                return code
            end
        end
    end
    return resolveDefaultPeriodKey()
end

return SquadKeys
