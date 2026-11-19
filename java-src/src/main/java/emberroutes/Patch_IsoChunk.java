package emberroutes;

import emberroutes.npc.NpcManager;
import emberroutes.warehouse.WarehouseFastIndex;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoChunk;

/**
 * 主线程 Chunk 级区块加载与回收无损传感器（直接位于根包 {@code emberroutes}）：
 * 替代 Lua 侧逐格 {@code Events.LoadGridsquare / ReuseGridsquare} 回调，
 * 在 Java 侧先做 O(1) 区块包围盒过滤与离屏 NPC 计数早退，彻底消灭高速驾驶时每秒数千次跨 Kahlua 往返。
 */
public final class Patch_IsoChunk {
    private Patch_IsoChunk() {}

    @Patch(className = "zombie.iso.IsoChunk", methodName = "doLoadGridsquare")
    public static final class OnChunkLoaded {
        @Patch.OnExit
        public static void exit(@Patch.This IsoChunk chunk) {
            WarehouseFastIndex.onChunkLoaded(chunk);
            NpcManager.onChunkLoaded(chunk);
        }
    }

    @Patch(className = "zombie.iso.IsoChunk", methodName = "doReuseGridsquares")
    public static final class OnChunkUnloaded {
        @Patch.OnEnter
        public static void enter(@Patch.This IsoChunk chunk) {
            WarehouseFastIndex.onChunkUnloaded(chunk);
        }
    }
}
