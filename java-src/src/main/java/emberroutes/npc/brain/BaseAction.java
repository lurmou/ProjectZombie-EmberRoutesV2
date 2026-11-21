package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import java.util.List;
import java.util.function.Predicate;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;

/**
 * M3 纯 Java 确定性动作器基类（视觉壳 + 严格倍速对齐计时结算）：
 * <ul>
 *   <li>仅向引擎动画层写入 {@code PerformingAction} / {@code IsPerformingAnAction}（可选 {@code ReadType}），绝不构造原版 UI 读条或玩家槽 {@code TimedAction}；</li>
 *   <li>打断时立即清除全部动作动画变量，并由所属 {@link StateScope} 自动清理其余租约/临时道具。</li>
 * </ul>
 */
public abstract class BaseAction {
    public enum ActionStatus {
        IDLE,
        RUNNING,
        COMPLETED,
        INTERRUPTED,
        FAILED
    }

    public record ItemLocation(ItemContainer container, InventoryItem item) {}

    private final double durationSimSec;
    private final String performingActionAnim;
    private final String extraVarKey;
    private final String extraVarValue;

    private ActionStatus status = ActionStatus.IDLE;
    private double elapsedSimSec;

    protected BaseAction(double durationSimSec, String performingActionAnim) {
        this(durationSimSec, performingActionAnim, null, null);
    }

    protected BaseAction(
            double durationSimSec,
            String performingActionAnim,
            String extraVarKey,
            String extraVarValue) {
        this.durationSimSec = Math.max(0.05, durationSimSec);
        this.performingActionAnim = performingActionAnim;
        this.extraVarKey = extraVarKey;
        this.extraVarValue = extraVarValue;
    }

    public final ActionStatus getStatus() {
        return status;
    }

    public final boolean isRunning() {
        return status == ActionStatus.RUNNING;
    }

    public final double getElapsedSimSec() {
        return elapsedSimSec;
    }

    public final double getDurationSimSec() {
        return durationSimSec;
    }

    public final void start(BrainContext ctx) {
        this.elapsedSimSec = 0.0;
        this.status = ActionStatus.RUNNING;
        if (ctx != null) {
            ctx.activeAction = this;
            applyAnimVars(ctx.npc);
        }
        onStart(ctx);
    }

    public final void tick(BrainContext ctx, double dtSim) {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        this.elapsedSimSec += Math.max(0.0, dtSim);
        onTick(ctx, dtSim);
        if (status == ActionStatus.RUNNING && this.elapsedSimSec >= this.durationSimSec) {
            boolean ok = false;
            try {
                ok = onComplete(ctx);
            } finally {
                if (ctx != null) {
                    clearAnimVars(ctx.npc);
                    if (ctx.activeAction == this) {
                        ctx.activeAction = null;
                    }
                }
            }
            this.status = ok ? ActionStatus.COMPLETED : ActionStatus.FAILED;
        }
    }

    public final void interrupt(BrainContext ctx, String reason) {
        if (status != ActionStatus.RUNNING && status != ActionStatus.IDLE) {
            return;
        }
        if (ctx != null) {
            clearAnimVars(ctx.npc);
            if (ctx.activeAction == this) {
                ctx.activeAction = null;
            }
        }
        onInterrupt(ctx, reason);
        this.status = ActionStatus.INTERRUPTED;
    }

    protected void onStart(BrainContext ctx) {}

    protected void onTick(BrainContext ctx, double dtSim) {}

    protected abstract boolean onComplete(BrainContext ctx);

    protected void onInterrupt(BrainContext ctx, String reason) {}

    private void applyAnimVars(IsoNPC npc) {
        if (npc == null || performingActionAnim == null || performingActionAnim.isEmpty()) {
            return;
        }
        npc.setVariable("PerformingAction", performingActionAnim);
        if (extraVarKey != null && !extraVarKey.isEmpty() && extraVarValue != null) {
            npc.setVariable(extraVarKey, extraVarValue);
        }
        npc.setVariable("IsPerformingAnAction", true);
    }

    private void clearAnimVars(IsoNPC npc) {
        if (npc == null) {
            return;
        }
        if (performingActionAnim != null && !performingActionAnim.isEmpty()) {
            npc.clearVariable("PerformingAction");
        }
        if (extraVarKey != null && !extraVarKey.isEmpty()) {
            npc.clearVariable(extraVarKey);
        }
        npc.setVariable("IsPerformingAnAction", false);
    }

    /** 递归扫描 NPC 主背包与嵌套随身容器，零重复查找首个满足条件的真物品原件及所属容器。 */
    public static ItemLocation findItemRecursive(ItemContainer root, Predicate<InventoryItem> filter) {
        if (root == null || filter == null) {
            return null;
        }
        List<InventoryItem> items = root.getItems();
        if (items == null || items.isEmpty()) {
            return null;
        }
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem item = items.get(i);
            if (item == null) {
                continue;
            }
            if (filter.test(item)) {
                return new ItemLocation(root, item);
            }
        }
        for (int i = 0, n = items.size(); i < n; i++) {
            InventoryItem item = items.get(i);
            if (item instanceof InventoryContainer bag && bag.getInventory() != null) {
                ItemLocation nested = findItemRecursive(bag.getInventory(), filter);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
