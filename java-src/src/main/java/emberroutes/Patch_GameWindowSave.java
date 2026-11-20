package emberroutes;

import emberroutes.npc.NpcManager;
import me.zed_0xff.zombie_buddy.Patch;

/**
 * 仅在原版正常存盘/退出入口 {@code GameWindow.save(boolean)} 时写入 {@code emberroutes_npcs.bin} 快照：
 * 保证日常运行与高速驾驶零主线程序列化税；若存在正在倒地的 NPC 则先完成 {@code die()} 交接给 {@code IsoDeadBody}。
 */
public final class Patch_GameWindowSave {
    private Patch_GameWindowSave() {}

    @Patch(className = "zombie.GameWindow", methodName = "save")
    public static final class OnSave {
        @Patch.OnEnter
        public static void enter() {
            NpcManager.onGameWindowSave();
        }
    }
}
