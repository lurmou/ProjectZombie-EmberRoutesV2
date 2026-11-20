package emberroutes;

import emberroutes.npc.NpcManager;
import emberroutes.warehouse.ContainerDirtyQueue;
import emberroutes.warehouse.TerritoryRenderer;
import emberroutes.warehouse.WarehouseFastIndex;
import me.zed_0xff.zombie_buddy.Patch;

/**
 * 退回主菜单或退出游戏状态 {@code IngameState.exit()} 结束时立即释放全部 Java 域静态引用与脏队列：
 * 填补原版在 {@code IngameState.exit()} 重建 Lua VM 到 {@code OnMainMenuEnter} 之间的跨档强引用空窗。
 */
public final class Patch_IngameExit {
    private Patch_IngameExit() {}

    @Patch(className = "zombie.gameStates.IngameState", methodName = "exit")
    public static final class OnExit {
        @Patch.OnExit
        public static void exit() {
            ContainerDirtyQueue.reset();
            WarehouseFastIndex.reset();
            TerritoryRenderer.reset();
            NpcManager.reset();
        }
    }
}
