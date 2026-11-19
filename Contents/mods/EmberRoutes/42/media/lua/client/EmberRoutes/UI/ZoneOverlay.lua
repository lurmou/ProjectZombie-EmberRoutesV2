-- 不规则领地世界渲染由 Java 端 TerritoryRenderer 在世界相机 FBO 内直接绘制（每帧 0 Lua 调用）。
-- 本模块仅提供右键菜单显隐开关入口。
local ZoneOverlay = {}

function ZoneOverlay.isVisible()
    if not EmberRoutesJava then return true end
    return EmberRoutesJava.isZoneOverlayVisible()
end

function ZoneOverlay.toggleVisible()
    if not EmberRoutesJava then return true end
    local nextState = not EmberRoutesJava.isZoneOverlayVisible()
    EmberRoutesJava.setZoneOverlayVisible(nextState)
    return nextState
end

return ZoneOverlay
