package emberroutes.npc;

import emberroutes.npc.brain.BrainContext;
import emberroutes.npc.brain.BrainRouter;
import emberroutes.npc.brain.TempPropHelper;
import emberroutes.npc.perception.NpcPerception;
import java.util.List;
import zombie.Lua.LuaEventManager;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.core.skinnedmodel.advancedanimation.AnimLayer;
import zombie.core.skinnedmodel.advancedanimation.LiveAnimNode;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.iso.Vector2;
import zombie.iso.sprite.IsoReticle;
import zombie.iso.sprite.IsoReticleMode;

/**
 * 余烬商路非玩家槽 NPC 物理躯壳（IsoPlayer 子类）：
 * 1. 严禁写入 {@code IsoPlayer.players[]}，{@code isLocalPlayer()} 平时恒为 {@code false}；
 * 2. {@link #update()} 使用 {@code try/finally} 严格保存并还原 {@code IsoPlayer.instance}、{@code IsoCamera.cameraCharacter} 与准星模式；
 * 3. 对后台 C++ 寻路线程隐藏 {@code isNpc()}，解锁可见性图 smoothPath 顺滑拐弯；
 * 4. 覆写 {@link #removeSaveFile()} 与 {@link #OnDeath()}，防止死亡触发本地玩家删档/黑屏或重复掉物；
 * 5. 唯一身体控制权由自身持有的 {@link EmberLocomotionController} 驱动。
 */
public final class IsoNPC extends IsoPlayer {
    private final String npcId;
    private final EmberLocomotionController locomotion = new EmberLocomotionController();
    private final EmberFollowController follow = new EmberFollowController();
    private final NpcPostureController posture = new NpcPostureController();
    private final EmberGazeController gaze = new EmberGazeController();
    private final NpcPerception perception;
    private final BrainRouter brain = new BrainRouter();
    private final BrainContext brainContext;

    private boolean attached;
    private boolean runningMode;
    private boolean forceLocalForRootMotion;

    private volatile boolean nativeSmoothPathUnlock = true;
    private boolean emberControlActive;
    private boolean emberMoving;
    private boolean emberDesiredRunning;
    private boolean emberDesiredSprinting;
    private float emberWalkSpeedCap = -1.0F;
    private float emberTrackSpeedScale = 1.0F;
    private boolean trackSpeedScaleDirty;
    private boolean inPreMovementRatesPhase;
    private float emberAnimSpeedScalar = 1.0F;
    private float emberTurnDelta = EmberParams.TURN_GAIN;

    private boolean manualMovementClampActive;
    private float manualMovementClampX;
    private float manualMovementClampY;
    private int manualMovementClampZ;
    private boolean manualMovementClampIsFinalGoal;

    public IsoNPC(String npcId, IsoCell cell, SurvivorDesc desc, int x, int y, int z) {
        super(cell, desc, 0, 0, 0);
        this.npcId = npcId;
        this.perception = new NpcPerception(npcId);
        this.brainContext = new BrainContext(npcId);
        this.brainContext.npc = this;
        setNpc(true);
        setCollidable(true);
        setGhostMode(false);
        setGodMod(false);
        setInvisible(false);
        setNoDamage(false);
        if (getFitness() != null) {
            getFitness().init();
        }
    }

    public String getNpcId() {
        return npcId;
    }

    public EmberLocomotionController getLocomotion() {
        return locomotion;
    }

    public EmberFollowController getFollow() {
        return follow;
    }

    public NpcPostureController getPosture() {
        return posture;
    }

    public EmberGazeController getGaze() {
        return gaze;
    }

    public NpcPerception getPerception() {
        return perception;
    }

    public BrainRouter getBrain() {
        return brain;
    }

    public BrainContext getBrainContext() {
        return brainContext;
    }

    public void setAttached(boolean attached) {
        this.attached = attached;
    }

    public boolean isAttached() {
        return attached;
    }

    public void setRunningMode(boolean running) {
        this.runningMode = running;
    }

    public boolean isRunningMode() {
        return runningMode;
    }

    public void setNativeSmoothPathUnlock(boolean unlock) {
        this.nativeSmoothPathUnlock = unlock;
    }

    public void setEmberControlActive(boolean active) {
        this.emberControlActive = active;
        if (!active) {
            this.emberMoving = false;
            this.emberDesiredRunning = false;
            this.emberDesiredSprinting = false;
            this.emberWalkSpeedCap = -1.0F;
            this.emberTrackSpeedScale = 1.0F;
            this.emberAnimSpeedScalar = 1.0F;
            clearManualMovementClamp();
            applyEmberTrackSpeedScale();
        }
    }

    public void setEmberMotionState(boolean moving, float animSpeedScalar, float turnDelta) {
        this.emberMoving = moving;
        this.emberAnimSpeedScalar = animSpeedScalar;
        this.emberTurnDelta = turnDelta;
    }

    public void setEmberDesiredRunning(boolean running) {
        this.emberDesiredRunning = running;
    }

    public void setEmberDesiredSprinting(boolean sprinting) {
        this.emberDesiredSprinting = sprinting;
    }

    public void setEmberWalkSpeedCap(float cap) {
        this.emberWalkSpeedCap = (Float.isFinite(cap) && cap >= 0.0F) ? Math.min(1.0F, cap) : -1.0F;
    }

    public void setEmberTrackSpeedScale(float scale) {
        float clamped = (Float.isFinite(scale) && scale > 0.0F)
                ? Math.max(0.45F, Math.min(1.15F, scale))
                : 1.0F;
        if (Math.abs(clamped - 1.0F) > 0.01F) {
            this.emberTrackSpeedScale = clamped;
            this.trackSpeedScaleDirty = true;
        } else {
            this.emberTrackSpeedScale = 1.0F;
        }
    }

    private void applyEmberTrackSpeedScale() {
        if (!trackSpeedScaleDirty && Math.abs(emberTrackSpeedScale - 1.0F) <= 0.01F) {
            return;
        }
        AdvancedAnimator anim = getAdvancedAnimator();
        AnimLayer root = (anim != null) ? anim.getRootLayer() : null;
        if (root == null) {
            return;
        }
        boolean allowScale = emberControlActive && emberMoving && !isDead()
                && Math.abs(emberTrackSpeedScale - 1.0F) > 0.01F
                && "movement".equalsIgnoreCase(root.getCurrentStateName());
        List<LiveAnimNode> nodes = root.getLiveAnimNodes();
        if (nodes != null) {
            for (int i = 0, n = nodes.size(); i < n; i++) {
                LiveAnimNode node = nodes.get(i);
                if (node == null || node.getSourceNode() == null) {
                    continue;
                }
                boolean isMovementNode = node.getSourceNode().parentState != null
                        && "movement".equalsIgnoreCase(node.getSourceNode().parentState.name);
                if (!isMovementNode) {
                    continue;
                }
                float baseScale = node.getSpeedScale(this);
                if (!Float.isFinite(baseScale) || baseScale <= 0.0F) {
                    continue;
                }
                float targetDelta = (allowScale && node.isActive()) ? (baseScale * emberTrackSpeedScale) : baseScale;
                for (int j = 0, tc = node.getMainAnimationTracksCount(); j < tc; j++) {
                    AnimationTrack track = node.getMainAnimationTrackAt(j);
                    if (track != null) {
                        track.setSpeedDelta(targetDelta);
                    }
                }
            }
        }
        if (Math.abs(emberTrackSpeedScale - 1.0F) <= 0.01F) {
            trackSpeedScaleDirty = false;
        }
    }

    public void setManualMovementClamp(float x, float y, int z, boolean isFinalGoal) {
        if (Float.isFinite(x) && Float.isFinite(y)) {
            this.manualMovementClampX = x;
            this.manualMovementClampY = y;
            this.manualMovementClampZ = z;
            this.manualMovementClampIsFinalGoal = isFinalGoal;
            this.manualMovementClampActive = true;
        } else {
            clearManualMovementClamp();
        }
    }

    public void clearManualMovementClamp() {
        this.manualMovementClampActive = false;
        this.manualMovementClampIsFinalGoal = false;
    }

    public void applyManualMovementClamp(Vector2 deferred) {
        if (!EmberParams.END_CLAMP_ENABLED) return;
        if (deferred == null || !manualMovementClampActive) return;
        if (EmberTargetProjector.levelOf(getZ()) != manualMovementClampZ) return;

        float dx = manualMovementClampX - getX();
        float dy = manualMovementClampY - getY();
        float remSq = dx * dx + dy * dy;
        float defSq = deferred.x * deferred.x + deferred.y * deferred.y;
        if (Float.isFinite(remSq) && Float.isFinite(defSq) && defSq > remSq) {
            if (remSq <= 1.0E-6F) {
                deferred.set(0.0F, 0.0F);
            } else {
                deferred.setLength((float) Math.sqrt(remSq));
            }
        }
    }

    @Override
    public boolean isLocalPlayer() {
        return forceLocalForRootMotion;
    }

    @Override
    public boolean isNpc() {
        if (nativeSmoothPathUnlock) {
            String tn = Thread.currentThread().getName();
            if ("PathfindNativeThread".equals(tn) || "PolyPathThread".equals(tn)) {
                return false;
            }
        }
        return super.isNpc();
    }

    @Override
    public boolean isJustMoved() {
        if (emberControlActive && emberMoving
                && playerMoveDir != null
                && (playerMoveDir.x != 0.0F || playerMoveDir.y != 0.0F)) {
            return true;
        }
        return super.isJustMoved();
    }

    @Override
    public float getLookAngleRadians() {
        if (emberControlActive) {
            return getDirectionAngleRadians();
        }
        return super.getLookAngleRadians();
    }

    @Override
    public float getTurnDelta() {
        gaze.applyBoneMaskIfNeeded(this);
        if (emberControlActive && emberMoving && isRunning()
                && Math.abs(emberTurnDelta - 0.80F) > 0.01F) {
            return emberTurnDelta;
        }
        return super.getTurnDelta();
    }

    /** 仅限 {@link EmberGazeController} 调用的唯一原生头部混合写入出口。 */
    void applyGazeHeadLookDirect(float lookHorizontal, float lookVertical) {
        super.setHeadLookAroundDirection(lookHorizontal, lookVertical);
    }

    @Override
    public void setHeadLookAroundDirection(float lookHorizontal, float lookVertical) {
        gaze.interceptEngineHeadLookWrite(this, lookHorizontal, lookVertical);
    }

    @Override
    public void updateMovementRates() {
        inPreMovementRatesPhase = false;
        super.updateMovementRates();
    }

    @Override
    protected void calculateWalkSpeed() {
        super.calculateWalkSpeed();
        if (emberControlActive && emberMoving) {
            float physCap = getVariableFloat("WalkSpeed", 1.0F);
            float effCap = (emberWalkSpeedCap >= 0.0F) ? Math.min(physCap, emberWalkSpeedCap) : physCap;
            float minFloor = (emberWalkSpeedCap >= 0.0F && emberWalkSpeedCap < 0.05F) ? 0.0F : 0.05F;
            float cvt = Math.max(minFloor, Math.min(1.0F, effCap * emberAnimSpeedScalar));
            setVariable("WalkSpeed", cvt);
        }
    }

    @Override
    public void setRunning(boolean bRunning) {
        // 拦截 IsoPlayer.updateInternal2 中段对 running 的无条件清零（冲刺态除外）
        if (emberControlActive && emberMoving && emberDesiredRunning && !emberDesiredSprinting
                && !isSprinting() && !bRunning) {
            super.setRunning(true);
            return;
        }
        super.setRunning(bRunning);
    }

    @Override
    public void setSprinting(boolean bSprinting) {
        // 仅在 updateInternal2 读取输入前的清零阶段保持目标冲刺，放行后续树木/负伤自动降挡
        if (inPreMovementRatesPhase && emberControlActive && emberMoving && emberDesiredSprinting
                && !bSprinting && canSprint() && !isSneaking()) {
            super.setSprinting(true);
            return;
        }
        super.setSprinting(bSprinting);
    }

    @Override
    public Vector2 getAimVector(Vector2 vec) {
        return vec.set(getForwardDirectionX(), getForwardDirectionY());
    }

    @Override
    public void setAngleFromAim() {
    }

    @Override
    public void autoDrink() {
    }

    @Override
    public void removeSaveFile() {
        // 严禁 IsoDeadBody 构造器误删真玩家存档或强刷 PlayerDB
    }

    @Override
    public void processWakingUp() {
        // 阻止原版早上 9:00 自动强制唤醒 NPC 或篡改本地玩家 SoundManager 音乐状态
    }

    @Override
    public UpdateSchedulerSimulationLevel getMinimumSimulationLevel() {
        return UpdateSchedulerSimulationLevel.FULL;
    }

    @Override
    public boolean isPushedByForSeparate(IsoMovingObject other) {
        if (other instanceof IsoNPC otherNpc
                && (EmberStairDiag.isNearStairs(this) || EmberStairDiag.isNearStairs(otherNpc))) {
            return false;
        }
        return super.isPushedByForSeparate(other);
    }

    @Override
    public void OnDeath() {
        detachAnimDebugTools();
        this.emberTrackSpeedScale = 1.0F;
        applyEmberTrackSpeedScale();
        brain.shutdown(brainContext);
        TempPropHelper.cleanupAllTempProps(this);
        gaze.forceReset(this);
        perception.reset();
        posture.forceImmediateReset(this, "DEATH", true);
        follow.stop(this, "DEATH");
        locomotion.interrupt(this, "DEATH");
        StopAllActionQueue();
        if (isAsleep()) {
            setAsleep(false);
        }
        if (getVehicle() != null) {
            getVehicle().exit(this);
        }
        NpcManager.onNpcDeath(this);
        LuaEventManager.triggerEvent("OnCharacterDeath", this);
    }

    @Override
    public void preupdate() {
        if (attached && getCurrentSquare() != null && (!isDead() || !isOnDeathDone())) {
            super.preupdate();
        }
    }

    @Override
    public void update() {
        if (!attached || getCurrentSquare() == null) return;
        if (isDead() && isOnDeathDone()) return;

        IsoPlayer playerBefore = IsoPlayer.getInstance();
        IsoGameCharacter cameraBefore = IsoCamera.getCameraCharacter();
        IsoReticle reticle = IsoReticle.getInstance(getIndex());
        IsoReticleMode reticleBefore = reticle != null ? reticle.getTargetReticleMode() : null;
        try {
            if (!isDead()) {
                posture.beforeUpdate(this);
                gaze.beforeUpdate(this);
                if (!posture.isBlockingLocomotion()) {
                    follow.beforeLocomotion(this);
                }
                locomotion.beforeUpdate(this);
            }
            applyEmberTrackSpeedScale();
            inPreMovementRatesPhase = true;
            super.update();
        } finally {
            inPreMovementRatesPhase = false;
            IsoPlayer.setInstance(playerBefore);
            if (cameraBefore == null) {
                IsoCamera.clearCameraCharacter();
            } else {
                IsoCamera.setCameraCharacter(cameraBefore);
            }
            if (reticle != null && reticleBefore != null) {
                reticle.setTargetReticleMode(reticleBefore);
            }
        }
    }

    @Override
    protected void doDeferredMovement() {
        if (!attached || getCurrentSquare() == null || isDead() || posture.isSuppressingDeferredMovement()) {
            discardRootMotion();
            return;
        }
        forceLocalForRootMotion = true;
        try {
            super.doDeferredMovement();
        } finally {
            forceLocalForRootMotion = false;
        }
    }

    @Override
    protected Vector2 getDeferredMovement(Vector2 result, boolean reset) {
        super.getDeferredMovement(result, reset);
        applyManualMovementClamp(result);
        return result;
    }

    @Override
    public void postupdate() {
        if (attached && getCurrentSquare() != null && (!isDead() || !isOnDeathDone())) {
            super.postupdate();
        }
    }

    public void discardRootMotion() {
        if (hasAnimationPlayer() && getAnimationPlayer() != null) {
            getAnimationPlayer().resetDeferredMovementAccum();
        }
    }

    /** 释放挂载到该 NPC 的原版动画调试监控器与录像器句柄，防止死亡/离屏/注销后泄漏。 */
    public void detachAnimDebugTools() {
        try {
            if (getDebugMonitor() != null && getDebugMonitor().getTarget() == this) {
                setDebugMonitor(null);
            }
        } catch (Throwable ignored) {}
        try {
            if (isAnimationRecorderActive()) {
                setAnimRecorderActive(false, false);
                closeAnimationRecorder();
            }
        } catch (Throwable ignored) {}
    }
}
