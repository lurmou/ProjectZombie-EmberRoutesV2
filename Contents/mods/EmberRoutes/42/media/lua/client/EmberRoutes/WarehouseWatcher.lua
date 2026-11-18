local SettlementStore = require "EmberRoutes/SettlementStore"

local WarehouseWatcher = {}

--- 运行态每 Tick：排空 @Patch 即时脏队列 + 推进 AE2 最小堆自适应退避巡检（暂停期不推进巡检）。
local function onTick()
    if EmberRoutesJava then
        EmberRoutesJava.tickWarehouse()
    end
end

--- 全状态脏队列即时排空（根治 E2）：按 Space 暂停划区、切换私密或拿放物品时亦立即刷新快照；空闲时 O(1) 判空零开销。
local function onTickEvenPaused()
    if EmberRoutesJava then
        EmberRoutesJava.flushDirtyContainers()
    end
end

local function onLoadGridsquare(sq)
    if sq and EmberRoutesJava then
        EmberRoutesJava.onSquareLoaded(sq)
    end
end

local function onReuseGridsquare(sq)
    if sq and EmberRoutesJava then
        EmberRoutesJava.onSquareUnloaded(sq)
    end
end

Events.OnInitGlobalModData.Add(function()
    SettlementStore.reconcileAndMountAll()
end)

Events.OnGameStart.Add(function()
    SettlementStore.reconcileAndMountAll()
end)

Events.OnMainMenuEnter.Add(function()
    if EmberRoutesJava then
        EmberRoutesJava.reset()
    end
end)

Events.OnTick.Add(onTick)
if Events.OnTickEvenPaused then
    Events.OnTickEvenPaused.Add(onTickEvenPaused)
end
Events.LoadGridsquare.Add(onLoadGridsquare)
Events.ReuseGridsquare.Add(onReuseGridsquare)

return WarehouseWatcher
