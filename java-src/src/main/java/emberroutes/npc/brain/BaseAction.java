package emberroutes.npc.brain;

import emberroutes.npc.IsoNPC;
import java.util.List;
import java.util.function.Predicate;
import zombie.GameTime;
import zombie.characters.CharacterStat;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;

/**
 * M3 原版直继 TimedAction 基类：直接继承 {@link zombie.characters.CharacterTimedActions.BaseAction}
 * 并经 {@code npc.StartAction(this)} 交由引擎统一推进、清理手模与响应 {@code stopOnWalk / stopOnRun / stopOnAim}。
 *
 * <p>进度条写保护由 {@code BaseActionStopProgressGuard / BaseActionPerformProgressGuard}
 * 在原版方法外围统一拦截，此处不再自建平行哑壳动作或双套推进时钟。</p>
 */
public abstract class BaseAction extends zombie.characters.CharacterTimedActions.BaseAction {
    public enum ActionStatus {
        IDLE,
        RUNNING,
        COMPLETED,
        INTERRUPTED,
        FAILED
    }

    public record ItemLocation(ItemContainer container, InventoryItem item) {}

    protected final IsoNPC npc;
    private BrainContext ctx;
    private String performingActionAnim;
    private String extraVarKey;
    private String extraVarValue;
    private String reportEventName;
    private boolean loopSoundImmediateStop;
    private boolean failedOnStart;
    private boolean terminated;
    private String interruptReason = "INTERRUPTED";
    private ActionStatus status = ActionStatus.IDLE;

    protected BaseAction(IsoNPC npc, double durationSimSec, String performingActionAnim) {
        this(npc, durationSimSec, performingActionAnim, null, null);
    }

    protected BaseAction(
            IsoNPC npc,
            double durationSimSec,
            String performingActionAnim,
            String extraVarKey,
            String extraVarValue) {
        super(npc);
        this.npc = npc;
        this.performingActionAnim = performingActionAnim;
        this.extraVarKey = extraVarKey;
        this.extraVarValue = extraVarValue;
        this.useProgressBar = false;
        this.forceProgressBar = false;
        this.stopOnWalk = false;
        this.stopOnRun = true;
        this.stopOnAim = false;
        setDurationSimSec(durationSimSec);
    }

    private static double ticksPerSimSec() {
        try {
            if (GameTime.getInstance() != null) {
                float ticks = GameTime.getInstance().getMultiplierFromTimeDelta(1.0F);
                if (Float.isFinite(ticks) && ticks > 1.0F) {
                    return ticks;
                }
            }
        } catch (Throwable ignored) {
        }
        return 48.0;
    }

    public final ActionStatus getStatus() {
        return status;
    }

    public final boolean isRunning() {
        return status == ActionStatus.RUNNING && !terminated;
    }

    public final double getElapsedSimSec() {
        return Math.max(0.0, Math.max(0.0F, this.currentTime) / ticksPerSimSec());
    }

    public final double getDurationSimSec() {
        return Math.max(0.05, Math.max(1, this.maxTime) / ticksPerSimSec());
    }

    protected final void setDurationSimSec(double durationSimSec) {
        double clamped = Math.max(0.05, durationSimSec);
        this.maxTime = Math.max(1, (int) Math.round(clamped * ticksPerSimSec()));
    }

    /** 启用原版事件驱动模式（{@code maxTime = -1}），交由 {@code OnAnimEvent} 或看门狗调用 {@code forceComplete()} 收口。 */
    protected final void enableEventDrivenMode() {
        this.maxTime = -1;
    }

    protected final void setPerformingActionAnim(String performingActionAnim) {
        this.performingActionAnim = performingActionAnim;
    }

    public final float getProgress01() {
        if (this.maxTime > 0) {
            return Math.max(0.0F, Math.min(1.0F, Math.max(0.0F, this.currentTime) / (float) this.maxTime));
        }
        return Math.max(0.0F, Math.min(1.0F, this.delta));
    }

    protected final void setExtraAnimVar(String key, String value) {
        this.extraVarKey = key;
        this.extraVarValue = value;
        if (this.started && key != null && !key.isEmpty() && value != null) {
            setAnimVariable(key, value);
        }
    }

    protected final void setReportEventName(String eventName) {
        this.reportEventName = eventName;
    }

    protected final void failOnStart() {
        this.failedOnStart = true;
        forceStop();
    }

    /** 配置动作期间左右手临时显示的真实物品原件（直调原版 {@code setOverrideHandModels}）。 */
    protected final void configureHandModels(InventoryItem primaryHand, InventoryItem secondaryHand) {
        setOverrideHandModels(primaryHand, secondaryHand);
    }

    protected final void playActionSound(IsoNPC targetNpc, String soundName) {
        if (targetNpc == null || soundName == null || soundName.isEmpty() || targetNpc.getEmitter() == null) {
            return;
        }
        try {
            this.loopSoundImmediateStop = false;
            this.soundEffect = targetNpc.getEmitter().playSound(soundName);
        } catch (Throwable ignored) {
        }
    }

    /** 播放需要 {@code emitter.stopSound()} 硬停的循环动作音效（对齐 {@code ISInventoryTransferAction} 的 {@code RummageInInventory}）。 */
    protected final void playLoopingActionSound(IsoNPC targetNpc, String soundName) {
        if (targetNpc == null || soundName == null || soundName.isEmpty() || targetNpc.getEmitter() == null) {
            return;
        }
        try {
            this.loopSoundImmediateStop = true;
            this.soundEffect = targetNpc.getEmitter().playSound(soundName);
        } catch (Throwable ignored) {
        }
    }

    /** 播放单次容器拿取/放入音效，不覆盖主动作循环音句柄。 */
    protected final void playOneShotSound(IsoNPC targetNpc, String soundName) {
        if (targetNpc == null || soundName == null || soundName.isEmpty() || targetNpc.getEmitter() == null) {
            return;
        }
        try {
            if (!targetNpc.getEmitter().isPlaying(soundName)) {
                targetNpc.getEmitter().playSound(soundName);
            }
        } catch (Throwable ignored) {
        }
    }

    protected final void ensureActionSoundPlaying(IsoNPC targetNpc, String soundName) {
        if (targetNpc == null || soundName == null || soundName.isEmpty() || targetNpc.getEmitter() == null) {
            return;
        }
        try {
            if (this.soundEffect != -1L && !targetNpc.getEmitter().isPlaying(this.soundEffect)) {
                this.soundEffect = targetNpc.getEmitter().playSound(soundName);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopActionSound() {
        long handle = this.soundEffect;
        this.soundEffect = -1L;
        if (handle > -1L && npc != null && npc.getEmitter() != null) {
            try {
                if (this.loopSoundImmediateStop) {
                    npc.getEmitter().stopSound(handle);
                } else if (npc.getEmitter().isPlaying(handle)) {
                    npc.stopOrTriggerSound(handle);
                }
            } catch (Throwable ignored) {
            }
        }
        this.loopSoundImmediateStop = false;
    }

    /** 由状态树节点发起：挂载至原版 {@code npc.StartAction(this)}，后续推进由 {@code IsoGameCharacter.updateInternal()} 自动执行。 */
    public final void start(BrainContext context) {
        this.ctx = context;
        this.terminated = false;
        this.failedOnStart = false;
        this.interruptReason = "INTERRUPTED";
        this.loopSoundImmediateStop = false;
        this.soundEffect = -1L;
        this.status = ActionStatus.RUNNING;
        if (context != null) {
            context.activeAction = this;
        }
        if (npc == null || npc.isDead()) {
            this.failedOnStart = true;
            stop();
            return;
        }
        npc.StartAction(this);
    }

    public final void interrupt(BrainContext context, String reason) {
        if (terminated || (status != ActionStatus.RUNNING && status != ActionStatus.IDLE)) {
            return;
        }
        if (this.ctx == null) {
            this.ctx = context;
        }
        this.interruptReason = (reason != null && !reason.isEmpty()) ? reason : "INTERRUPTED";
        forceStop();
        if (npc != null && !npc.getCharacterActions().isEmpty() && npc.getCharacterActions().get(0) == this) {
            npc.StopAllActionQueue();
        }
        if (!terminated) {
            if (this.started) {
                stop();
            } else {
                interruptWaitToStart();
            }
        }
    }

    @Override
    public boolean valid() {
        return !terminated && !failedOnStart && npc != null && !npc.isDead();
    }

    @Override
    public final void start() {
        super.start();
        if (extraVarKey != null && !extraVarKey.isEmpty() && extraVarValue != null) {
            setAnimVariable(extraVarKey, extraVarValue);
        }
        onStart(ctx);
        if (failedOnStart || forceStop) {
            forceStop();
            return;
        }
        if (forceComplete) {
            return;
        }
        if (performingActionAnim != null && !performingActionAnim.isEmpty()) {
            setActionAnim(performingActionAnim);
        }
        if (reportEventName != null && !reportEventName.isEmpty() && npc != null) {
            npc.reportEvent(reportEventName);
        }
    }

    @Override
    public final void update() {
        super.update();
        if (terminated || forceStop || forceComplete) {
            return;
        }
        double dtSim = (GameTime.getInstance() != null)
                ? Math.max(0.0, GameTime.getInstance().getTimeDelta())
                : (1.0 / 60.0);
        onTick(ctx, dtSim);
    }

    @Override
    public final void perform() {
        if (terminated) {
            return;
        }
        terminated = true;
        stopActionSound();
        boolean ok = false;
        try {
            ok = onComplete(ctx);
        } finally {
            this.status = ok ? ActionStatus.COMPLETED : ActionStatus.FAILED;
            refreshContextStats(ctx);
            if (ctx != null && ctx.activeAction == this) {
                ctx.activeAction = null;
            }
            super.perform();
        }
    }

    @Override
    public final void stop() {
        if (terminated) {
            return;
        }
        terminated = true;
        try {
            if (!failedOnStart) {
                onInterrupt(ctx, interruptReason);
            }
        } finally {
            stopActionSound();
            this.status = failedOnStart ? ActionStatus.FAILED : ActionStatus.INTERRUPTED;
            refreshContextStats(ctx);
            if (ctx != null && ctx.activeAction == this) {
                ctx.activeAction = null;
            }
            super.stop();
        }
    }

    @Override
    public final void interruptWaitToStart() {
        if (terminated) {
            return;
        }
        terminated = true;
        stopActionSound();
        this.status = failedOnStart ? ActionStatus.FAILED : ActionStatus.INTERRUPTED;
        refreshContextStats(ctx);
        if (ctx != null && ctx.activeAction == this) {
            ctx.activeAction = null;
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
