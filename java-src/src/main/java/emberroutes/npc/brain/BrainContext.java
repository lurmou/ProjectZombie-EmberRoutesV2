package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.NpcManager;
import emberroutes.npc.brain.NeedsPolicy.UrgentNeed;
import zombie.iso.IsoObject;

/**
 * 单个 {@link IsoNPC} 的决策黑板、时钟信息与引擎「起身屏障（Posture Barrier）」统一执行端口。
 */
public class BrainContext {
    public final String npcId;
    public IsoNPC npc;
    public String settlementId;
    public long frameTick;
    public double dtSim;
    public double simTime;

    // 危险场与避险锚点
    public boolean threatDetected;
    public float threatX;
    public float threatY;
    public float threatZ;
    public boolean hasSafeAnchor;
    public float safeAnchorX;
    public float safeAnchorY;
    public int safeAnchorZ;

    // 生理需求评估缓存
    public float hunger;
    public float thirst;
    public float fatigue;
    public float endurance = 1.0F;
    public boolean bleeding;
    public boolean working;
    public UrgentNeed urgentNeed = UrgentNeed.NONE;

    // 借用工具归还与当前动作状态
    public boolean hasBorrowedTool;
    public BaseAction activeAction;

    public BrainContext(String npcId) {
        this.npcId = npcId;
    }

    /**
     * 经「异步起身屏障（Posture Barrier）」下发移动指令：
     * 若角色尚在床/椅或起身过渡期，自动转入 {@code queueMoveAfterStandUp} 排队等待回到 {@code STANDING} 当帧点火，
     * 杜绝未起身滑步或穿过家具。
     */
    public void requestMoveTo(double x, double y, double z, boolean running) {
        if (npc == null || npc.isDead()) {
            return;
        }
        npc.getFollow().stop(npc, false, "BRAIN_MOVE");
        if (npc.getPosture().isBlockingLocomotion()) {
            npc.getPosture().queueMoveAfterStandUp(npc, x, y, z, running);
            return;
        }
        if (npc.getPosture().isActive()) {
            npc.getPosture().commandStandUp(npc, true);
        }
        npc.getLocomotion().moveTo(npc, x, y, z, running);
    }

    /** 经「异步起身屏障」下发小队跟随指令。 */
    public void requestFollow(int playerIndex, int slot) {
        if (npc == null || npc.isDead()) {
            return;
        }
        npc.getLocomotion().interrupt(npc, "BRAIN_FOLLOW");
        if (npc.getPosture().isBlockingLocomotion()) {
            npc.getPosture().queueFollowAfterStandUp(npc, playerIndex, slot);
            return;
        }
        if (npc.getPosture().isActive()) {
            npc.getPosture().commandStandUp(npc, true);
        }
        npc.getFollow().start(playerIndex, slot);
    }

    /** 命令角色起身（若尚在家具/床上，物理防碰撞占用保留至 {@code STANDING} 为止）。 */
    public void requestStandUp(boolean urgent) {
        if (npc != null && npc.getPosture().isActive()) {
            npc.getPosture().commandStandUp(npc, urgent);
        }
    }

    /** 查询目标家具物理瓦片是否仍被另一名 NPC（含正从该家具起身离床者）占用。 */
    public boolean isFurniturePhysicallyOccupied(IsoObject originObj, boolean isBed) {
        if (npc == null) {
            return false;
        }
        return NpcManager.isSmartSlotPhysicallyClaimedByOther(npc, originObj, isBed);
    }
}
