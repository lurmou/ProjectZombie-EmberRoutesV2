local Kernel = require "EmberRoutes/Kernel"

require "EmberRoutes/Settlement/SettlementStore"
require "EmberRoutes/Warehouse/WarehouseWatcher"
require "EmberRoutes/UI/DebugContextMenu"

local Boot = {}

local function onBootEvent()
    Kernel.boot()
end

local function onTeardownEvent()
    Kernel.teardown()
end

Events.OnInitGlobalModData.Add(onBootEvent)
Events.OnGameStart.Add(onBootEvent)
Events.OnMainMenuEnter.Add(onTeardownEvent)

return Boot
