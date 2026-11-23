package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import java.util.List;
import java.util.Stack;
import java.util.function.Predicate;
import zombie.characters.CharacterStat;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;

/**
 * M3 纯 Java 确定性动作器基类（直接挂载原版 {@link zombie.characters.CharacterTimedActions.BaseAction} 驱动手模与动画 + 严格倍速对齐计时结算）：
 * <ul>
 *   <li>通过向 {@code npc.getCharacterActions()} 挂载无进度条（{@code useProgressBar = false}）的原版 {@code CharacterTimedActions.BaseAction}，
 *       100% 复用原版 {@code ModelManager#ResetCharacterEquippedHands} 渲染管线（自动隐藏原武器、按 {@code FluidContainer > 0.5F} 选 {@code _Fluid} 水位模型、初始化 {@code SmartTexture} 贴图并挂载至 {@code Bip01_Prop1 / Bip01_Prop2}）；</li>
 *   <li>由本类根据 {@code dtSim} 推进进度并在完成/打断时直接调用原版 {@code stopTimedActionAnim()} + 从 {@code characterActions} 移除，绝不触发原版 {@code UIManager.getProgressBar} 污染真玩家 HUD。</li>
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

    private double durationSimSec;
    private String performingActionAnim;
    private String extraVarKey;
    private String extraVarValue;
    private String reportEventName;
    private InventoryItem overridePrimaryItem;
    private InventoryItem overrideSecondaryItem;
    private boolean handModelsConfigured;
    private zombie.characters.CharacterTimedActions.BaseAction nativeAction;
    private long soundHandle = -1L;
    private boolean loopSoundImmediateStop;

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

    protected final void setDurationSimSec(double durationSimSec) {
        this.durationSimSec = Math.max(0.05, durationSimSec);
    }

    protected final void setPerformingActionAnim(String performingActionAnim) {
        this.performingActionAnim = performingActionAnim;
    }

    public final float getProgress01() {
        return (float) Math.min(1.0, Math.max(0.0, elapsedSimSec / durationSimSec));
    }

    protected final void setExtraAnimVar(String key, String value) {
        this.extraVarKey = key;
        this.extraVarValue = value;
    }

    protected final void setReportEventName(String eventName) {
        this.reportEventName = eventName;
    }

    /** 配置动作期间左右手临时显示的真实物品原件（对应原版 {@code setOverrideHandModels(primaryHand, secondaryHand)}）。 */
    protected final void configureHandModels(InventoryItem primaryHand, InventoryItem secondaryHand) {
        this.overridePrimaryItem = primaryHand;
        this.overrideSecondaryItem = secondaryHand;
        this.handModelsConfigured = true;
    }

    protected final void playActionSound(IsoNPC npc, String soundName) {
        if (npc == null || soundName == null || soundName.isEmpty() || npc.getEmitter() == null) {
            return;
        }
        try {
            this.loopSoundImmediateStop = false;
            this.soundHandle = npc.getEmitter().playSound(soundName);
        } catch (Throwable ignored) {
        }
    }

    /** 播放需要 {@code emitter.stopSound()} 硬停的循环动作音效（对齐 {@code ISInventoryTransferAction} 的 {@code RummageInInventory}）。 */
    protected final void playLoopingActionSound(IsoNPC npc, String soundName) {
        if (npc == null || soundName == null || soundName.isEmpty() || npc.getEmitter() == null) {
            return;
        }
        try {
            this.loopSoundImmediateStop = true;
            this.soundHandle = npc.getEmitter().playSound(soundName);
        } catch (Throwable ignored) {
        }
    }

    /** 播放单次容器拿取/放入音效，不覆盖主动作循环音句柄。 */
    protected final void playOneShotSound(IsoNPC npc, String soundName) {
        if (npc == null || soundName == null || soundName.isEmpty() || npc.getEmitter() == null) {
            return;
        }
        try {
            if (!npc.getEmitter().isPlaying(soundName)) {
                npc.getEmitter().playSound(soundName);
            }
        } catch (Throwable ignored) {
        }
    }

    protected final void ensureActionSoundPlaying(IsoNPC npc, String soundName) {
        if (npc == null || soundName == null || soundName.isEmpty() || npc.getEmitter() == null) {
            return;
        }
        try {
            if (this.soundHandle != -1L && !npc.getEmitter().isPlaying(this.soundHandle)) {
                this.soundHandle = npc.getEmitter().playSound(soundName);
            }
        } catch (Throwable ignored) {
        }
    }

    public final void start(BrainContext ctx) {
        this.elapsedSimSec = 0.0;
        this.status = ActionStatus.RUNNING;
        this.handModelsConfigured = false;
        this.overridePrimaryItem = null;
        this.overrideSecondaryItem = null;
        this.soundHandle = -1L;
        this.loopSoundImmediateStop = false;
        if (ctx != null) {
            ctx.activeAction = this;
        }
        onStart(ctx);
        if (ctx != null && status == ActionStatus.RUNNING) {
            applyAnimVarsAndNativeAction(ctx.npc);
        }
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
                    clearAnimVarsAndNativeAction(ctx.npc);
                    refreshContextStats(ctx);
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
        try {
            onInterrupt(ctx, reason);
        } finally {
            if (ctx != null) {
                clearAnimVarsAndNativeAction(ctx.npc);
                refreshContextStats(ctx);
                if (ctx.activeAction == this) {
                    ctx.activeAction = null;
                }
            }
            this.status = ActionStatus.INTERRUPTED;
        }
    }

    protected void onStart(BrainContext ctx) {}

    protected void onTick(BrainContext ctx, double dtSim) {}

    protected abstract boolean onComplete(BrainContext ctx);

    protected void onInterrupt(BrainContext ctx, String reason) {}

    private static void refreshContextStats(BrainContext ctx) {
        if (ctx != null && ctx.npc != null && ctx.npc.getStats() != null) {
            ctx.hunger = ctx.npc.getStats().get(CharacterStat.HUNGER);
            ctx.thirst = ctx.npc.getStats().get(CharacterStat.THIRST);
            ctx.fatigue = ctx.npc.getStats().get(CharacterStat.FATIGUE);
            ctx.endurance = ctx.npc.getStats().get(CharacterStat.ENDURANCE);
        }
    }

    private void applyAnimVarsAndNativeAction(IsoNPC npc) {
        if (npc == null) {
            return;
        }
        try {
            zombie.characters.CharacterTimedActions.BaseAction act =
                    new zombie.characters.CharacterTimedActions.BaseAction(npc);
            act.useProgressBar = false;
            act.forceProgressBar = false;
            // maxTime = -1 使 IsoGameCharacter.updateInternal 中 act.finished() 恒为 false，完全由本类 dtSim 驱动生命周期
            act.maxTime = -1;
            act.started = true;
            act.stopOnWalk = false;
            act.stopOnRun = false;
            act.stopOnAim = false;

            Stack<zombie.characters.CharacterTimedActions.BaseAction> stack = npc.getCharacterActions();
            if (stack != null) {
                stack.clear();
                stack.push(act);
            }
            this.nativeAction = act;

            if (extraVarKey != null && !extraVarKey.isEmpty() && extraVarValue != null) {
                act.setAnimVariable(extraVarKey, extraVarValue);
            }
            if (performingActionAnim != null && !performingActionAnim.isEmpty()) {
                act.setActionAnim(performingActionAnim);
            }
            if (handModelsConfigured) {
                act.setOverrideHandModels(overridePrimaryItem, overrideSecondaryItem);
            }

            if (reportEventName != null && !reportEventName.isEmpty()) {
                npc.reportEvent(reportEventName);
            }
        } catch (Throwable ignored) {
            // 脱机单测环境（无完整引擎实例）退化为直接写变量
            if (performingActionAnim != null && !performingActionAnim.isEmpty()) {
                npc.setVariable("PerformingAction", performingActionAnim);
            }
            if (extraVarKey != null && !extraVarKey.isEmpty() && extraVarValue != null) {
                npc.setVariable(extraVarKey, extraVarValue);
            }
            npc.setVariable("IsPerformingAnAction", true);
        }
    }

    private void clearAnimVarsAndNativeAction(IsoNPC npc) {
        if (npc == null) {
            return;
        }
        if (this.soundHandle != -1L && npc.getEmitter() != null) {
            try {
                if (this.loopSoundImmediateStop) {
                    npc.getEmitter().stopSound(this.soundHandle);
                } else if (npc.getEmitter().isPlaying(this.soundHandle)) {
                    npc.stopOrTriggerSound(this.soundHandle);
                }
            } catch (Throwable ignored) {
            }
            this.soundHandle = -1L;
        }
        this.loopSoundImmediateStop = false;
        zombie.characters.CharacterTimedActions.BaseAction act = this.nativeAction;
        this.nativeAction = null;
        if (act != null) {
            try {
                act.stopTimedActionAnim();
            } catch (Throwable ignored) {
            }
            try {
                Stack<zombie.characters.CharacterTimedActions.BaseAction> stack = npc.getCharacterActions();
                if (stack != null) {
                    stack.removeElement(act);
                }
            } catch (Throwable ignored) {
            }
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
