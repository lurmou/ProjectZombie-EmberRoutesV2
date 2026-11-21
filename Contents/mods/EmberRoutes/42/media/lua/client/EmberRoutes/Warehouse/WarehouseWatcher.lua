local Kernel = require "EmberRoutes/Kernel"
local SettlementStore = require "EmberRoutes/Settlement/SettlementStore"
local SmartZoningPresets = require "EmberRoutes/Warehouse/SmartZoningPresets"

local WarehouseWatcher = {}

--- WORLD_SYNC 阶段每帧入口：排空 @Patch 即时脏队列 + 推进 AE2 最小堆自适应退避巡检（暂停期不推进巡检）。
local function tickWarehouse()
    if EmberRoutesJava then
        EmberRoutesJava.tickWarehouse()
    end
end

--- 暂停态白名单入口（根治 E2）：按 Space 暂停划区、切换私密或拿放物品时立即刷新快照；空闲时 O(1) 判空零开销。
local function flushDirtyContainers()
    if EmberRoutesJava then
        EmberRoutesJava.flushDirtyContainers()
    end
end

Kernel.registerModule({
    id = "warehouse",
    onWorldReady = function()
        SmartZoningPresets.syncAllToJava()
        SettlementStore.reconcileAndMountAll()
    end,
    tick = { stage = "WORLD_SYNC", fn = tickWarehouse },
    onTickPaused = flushDirtyContainers,
})

return WarehouseWatcher
