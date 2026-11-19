local Kernel = require "EmberRoutes/Kernel"

-- 显式静态加载全部核心域、兼容槽与 UI 入口
require "EmberRoutes/Settlement/SettlementStore"
require "EmberRoutes/Warehouse/WarehouseWatcher"
require "EmberRoutes/Npc/NpcLocomotionPort"
require "EmberRoutes/Npc/NpcRuntime"
require "EmberRoutes/Compat/HaloTextGuard"
require "EmberRoutes/UI/DebugContextMenu"

-- 唯一权威核心模块启动顺序表（零魔法数字，肉眼即见确定性顺序；
-- PHYSICS_MOVE 内 npc-runtime 先推进物理并产出终结事件，npc-locomotion 同帧紧随其后批拉取派发）
Kernel.setBootOrder({
    "settlements",
    "warehouse",
    "npc-runtime",
    "npc-locomotion",
    "debug-context-menu",
})

local Boot = {}

local function onInitGlobalModData(isNewGame)
    Kernel.onInitModData(isNewGame)
end

local function onGameStart()
    Kernel.onWorldReady()
end

local function onCreatePlayer(playerIndex, player)
    Kernel.onPlayerCreated(playerIndex, player)
end

local function onTick(numberTicks)
    Kernel.dispatchTick(numberTicks)
end

local function onTickEvenPaused()
    Kernel.dispatchTickPaused()
end

local function onTeardown()
    Kernel.teardown()
end

-- 全模组唯一引擎顶层事件挂接点（稳定键去重防开发期 F11 重载重复订阅）
if Events then
    Kernel.subscribeOnce(Events.OnInitGlobalModData, "Boot.OnInitGlobalModData", onInitGlobalModData)
    Kernel.subscribeOnce(Events.OnGameStart, "Boot.OnGameStart", onGameStart)
    if Events.OnCreatePlayer then
        Kernel.subscribeOnce(Events.OnCreatePlayer, "Boot.OnCreatePlayer", onCreatePlayer)
    end
    Kernel.subscribeOnce(Events.OnTick, "Boot.OnTick", onTick)
    if Events.OnTickEvenPaused then
        Kernel.subscribeOnce(Events.OnTickEvenPaused, "Boot.OnTickEvenPaused", onTickEvenPaused)
    end
    if Events.OnPreMapLoad then
        Kernel.subscribeOnce(Events.OnPreMapLoad, "Boot.OnPreMapLoad", onTeardown)
    end
    Kernel.subscribeOnce(Events.OnMainMenuEnter, "Boot.OnMainMenuEnter", onTeardown)
end

return Boot
