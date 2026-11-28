package emberroutes.npc;

import emberroutes.npc.brain.BrainContext;
import emberroutes.npc.brain.BrainRouter;
import emberroutes.npc.brain.TempPropHelper;
import emberroutes.npc.brain.VehicleCoRideHelper;
import emberroutes.npc.combat.B42CombatRuntime;
import emberroutes.npc.combat.CombatHookContext;
import emberroutes.npc.combat.EmberSquadDirector;
import emberroutes.npc.combat.NpcVirtualAim;
import emberroutes.npc.perception.NpcPerception;
import java.util.ArrayList;
import java.util.List;
import zombie.GameTime;
import zombie.Lua.LuaEventManager;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.ai.states.BumpedState;
import zombie.ai.states.PlayerGetUpState;
import zombie.ai.states.PlayerHitReactionPVPState;
import zombie.ai.states.PlayerHitReactionState;
import zombie.ai.states.PlayerStrafeState;
import zombie.ai.states.SwipeStatePlayer;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.SurvivorDesc;
import zombie.characters.component.AIComponent;
import zombie.core.physics.BallisticsController;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.core.skinnedmodel.advancedanimation.AnimLayer;
import zombie.core.skinnedmodel.advancedanimation.LiveAnimNode;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.LosUtil;
import zombie.iso.Vector2;
import zombie.iso.sprite.IsoReticle;
import zombie.iso.sprite.IsoReticleMode;
import zombie.ui.UIManager;

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
    private final NpcVirtualAim virtualAim = new NpcVirtualAim();
    private final BallisticsController.AimingVectorParameters npcAimingVectorParams =
            new BallisticsController.AimingVectorParameters();
    private final Vector2 virtualAimTempVec = new Vector2();

    private boolean attached;
    private boolean runningMode;
    private boolean forceLocalForRootMotion;
    private boolean forceLocalForCombat;
    private int lastAttackerZombieId = -1;

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
    private boolean emberHasMoveDir;
    private float emberDesiredMoveX;
    private float emberDesiredMoveY;

    private boolean manualMovementClampActive;
    private float manualMovementClampX;
    private float manualMovementClampY;
    private int manualMovementClampZ;
    private boolean manualMovementClampIsFinalGoal;

    private boolean precisionDockClampActive;
    private float precisionDockTargetX;
    private float precisionDockTargetY;
    private int precisionDockTargetZ;
    private boolean precisionDockStopping;

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
        setVariable("hitpvp", false);
        if (getFitness() != null) {
            getFitness().init();
        }
    }

    @Override
    public void registerECSComponents() {
        super.registerECSComponents();
        // 0-@Patch 屏蔽真玩家键鼠/手柄输入窜扰，避免同车或车旁集体抢 E / 瞄准 / 换弹 / 隐藏光标
        this.setECSComponent(new NpcNullInputComponent(this));
    }

    public NpcNullInputComponent getNullInputComponent() {
        return getCharacterInputComponent() instanceof NpcNullInputComponent n ? n : null;
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
            clearEmberMoveDir();
            clearManualMovementClamp();
            applyEmberTrackSpeedScale();
        }
    }

    void setEmberMoveDir(float x, float y) {
        this.emberHasMoveDir = true;
        this.emberDesiredMoveX = x;
        this.emberDesiredMoveY = y;
    }

    void clearEmberMoveDir() {
        this.emberHasMoveDir = false;
        this.emberDesiredMoveX = 0.0F;
        this.emberDesiredMoveY = 0.0F;
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
        clearPrecisionDockClamp();
    }

    public void setPrecisionDockClamp(float x, float y, int z, boolean stopping) {
        if (Float.isFinite(x) && Float.isFinite(y)) {
            this.precisionDockTargetX = x;
            this.precisionDockTargetY = y;
            this.precisionDockTargetZ = z;
            this.precisionDockStopping = stopping;
            this.precisionDockClampActive = true;
        } else {
            clearPrecisionDockClamp();
        }
    }

    public void clearPrecisionDockClamp() {
        this.precisionDockClampActive = false;
        this.precisionDockStopping = false;
    }

    public void applyManualMovementClamp(Vector2 deferred) {
        if (deferred == null) return;
        if (precisionDockClampActive && EmberTargetProjector.levelOf(getZ()) == precisionDockTargetZ) {
            float mult = 1.0F;
            try {
                if (GameTime.instance != null && Float.isFinite(GameTime.instance.getMultiplier())) {
                    mult = GameTime.instance.getMultiplier();
                }
            } catch (Throwable ignored) {}
            float[] adj = EmberPostureMath.computePrecisionRootMotion(
                    getX(), getY(),
                    precisionDockTargetX, precisionDockTargetY,
                    deferred.x, deferred.y,
                    precisionDockStopping,
                    mult);
            deferred.set(adj[0], adj[1]);
            return;
        }
        if (!EmberParams.END_CLAMP_ENABLED) return;
        if (!manualMovementClampActive) return;
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
        return forceLocalForRootMotion || forceLocalForCombat;
    }

    public boolean isForceLocalForCombat() {
        return forceLocalForCombat;
    }

    public void setForceLocalForCombat(boolean forceLocalForCombat) {
        this.forceLocalForCombat = forceLocalForCombat;
    }

    public NpcVirtualAim getVirtualAim() {
        return virtualAim;
    }

    public boolean hasVirtualAim() {
        return virtualAim.isActive();
    }

    public void clearVirtualAim() {
        virtualAim.clear();
    }

    public int getVirtualReticleScreenX() {
        return emberroutes.npc.combat.B42CombatRuntime.projectReticleScreenX(virtualAim);
    }

    public int getVirtualReticleScreenY() {
        return emberroutes.npc.combat.B42CombatRuntime.projectReticleScreenY(virtualAim);
    }

    @Override
    public boolean isNpc() {
        if (forceLocalForCombat) {
            return false;
        }
        if (nativeSmoothPathUnlock) {
            String tn = Thread.currentThread().getName();
            if ("PathfindNativeThread".equals(tn) || "PolyPathThread".equals(tn)) {
                return false;
            }
        }
        return super.isNpc();
    }

    @Override
    public void setInitiateAttack(boolean initiate) {
        super.setInitiateAttack(initiate);
        AIComponent ai = tryGetECSComponent(AIComponent.class);
        if (ai != null && ai.getHumanControlVars() != null) {
            ai.getHumanControlVars().initiateAttack = initiate;
        }
    }

    @Override
    public void setIsAiming(boolean isAiming) {
        super.setIsAiming(isAiming);
        this.isCharging = isAiming;
        AIComponent ai = tryGetECSComponent(AIComponent.class);
        if (ai != null && ai.getHumanControlVars() != null) {
            ai.getHumanControlVars().aiming = isAiming;
        }
    }

    @Override
    public void clearHandToHandAttack() {
        super.clearHandToHandAttack();
        setDoShove(false);
        if (getAttackVars() != null) {
            getAttackVars().clear();
        }
        AIComponent ai = tryGetECSComponent(AIComponent.class);
        if (ai != null && ai.getHumanControlVars() != null) {
            ai.getHumanControlVars().melee = false;
            ai.getHumanControlVars().initiateAttack = false;
        }
    }

    @Override
    public boolean isJustMoved() {
        if (emberControlActive && emberMoving) {
            if (playerMoveDir != null && (playerMoveDir.x != 0.0F || playerMoveDir.y != 0.0F)) {
                return true;
            }
            AIComponent ai = tryGetECSComponent(AIComponent.class);
            if (ai != null && ai.getHumanControlVars() != null && ai.getHumanControlVars().justMoved) {
                return true;
            }
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
        if (emberControlActive && emberMoving && emberHasMoveDir && playerMoveDir != null) {
            float spd = isSprinting() ? 1.5F : (isRunning() ? 1.0F : 0.5F);
            playerMoveDir.set(emberDesiredMoveX * spd, emberDesiredMoveY * spd);
        } else if (emberControlActive && !emberMoving && playerMoveDir != null) {
            playerMoveDir.set(0.0F, 0.0F);
        }
    }

    @Override
    protected void calculateWalkSpeed() {
        super.calculateWalkSpeed();
        if (locomotion.isPrecisionShortStrafing()) {
            set(PlayerStrafeState.STRAFE_SPEED, 1.0F);
            setVariable("StrafeSpeed", 1.0F);
        }
        if (emberControlActive && emberMoving && !isAiming()) {
            float physCap = getVariableFloat("WalkSpeed", 1.0F);
            float effCap = (emberWalkSpeedCap >= 0.0F) ? Math.min(physCap, emberWalkSpeedCap) : physCap;
            float minFloor = (emberWalkSpeedCap >= 0.0F && emberWalkSpeedCap < 0.05F) ? 0.0F : 0.05F;
            float cvt = Math.max(minFloor, Math.min(1.0F, effCap * emberAnimSpeedScalar));
            setVariable("WalkSpeed", cvt);
        }
    }

    @Override
    public boolean isStrafing() {
        if (locomotion.isPrecisionShortStrafing()) {
            return true;
        }
        return super.isStrafing();
    }

    @Override
    public boolean isWeaponReady() {
        if (isHandModelOverriddenByCurrentCharacterAction()) {
            return false;
        }
        if (getPrimaryHandItem() == null) {
            return true;
        }
        return super.isWeaponReady();
    }

    @Override
    public boolean shouldIgnoreCollisionWithSquare(IsoGridSquare square) {
        if (square != null
                && (posture.shouldIgnoreCollisionWithSquare(square)
                        || locomotion.shouldIgnoreCollisionWithSquare(square))) {
            return true;
        }
        return super.shouldIgnoreCollisionWithSquare(square);
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
        if (virtualAim.isActive()) {
            return vec.set(virtualAim.getAimDirX(), virtualAim.getAimDirY());
        }
        return vec.set(getForwardDirectionX(), getForwardDirectionY());
    }

    @Override
    public void setAngleFromAim() {
        if (virtualAim.isActive()) {
            Vector2 dir = virtualAimTempVec.set(virtualAim.getAimDirX(), virtualAim.getAimDirY());
            if (dir.getLengthSquared() > 1.0E-6F) {
                dir.normalize();
                BallisticsController bc = getBallisticsController();
                if (bc != null && bc.updateAimingVector(this, npcAimingVectorParams)) {
                    setTargetVerticalAimAngle(npcAimingVectorParams.desiredForwardPitchRads * (180.0F / (float) Math.PI));
                } else {
                    setTargetVerticalAimAngle(virtualAim.getPitchDegrees());
                }
                setForwardDirection(dir);
            }
        }
    }

    @Override
    public void setHitReaction(String hitReaction) {
        if (hitReaction != null && !hitReaction.isEmpty() && !getVariableBoolean("hitpvp")) {
            setVariable("hitpvp", false);
        }
        super.setHitReaction(hitReaction);
        if (hitReaction != null && !hitReaction.isEmpty() && !isDead()) {
            if (brainContext != null && getAttackedBy() instanceof IsoZombie z && !z.isDead()) {
                this.lastAttackerZombieId = z.getID();
                EmberSquadDirector.getInstance().broadcastAllyDistress(
                        z.getID(),
                        z.getX(),
                        z.getY(),
                        z.getZ(),
                        false,
                        Math.round(brainContext.simTime * 1000.0));
            }
        }
    }

    /** 是否已由原版 ActionContext 切入 PlayerHitReactionState / PlayerHitReactionPVPState 受击硬直态。 */
    public boolean isInVanillaHitReactionState() {
        return isCurrentState(PlayerHitReactionState.instance())
                || isCurrentState(PlayerHitReactionPVPState.instance());
    }

    /** 是否处于原版受击状态机或其 1 帧进入过渡窗口。 */
    public boolean isInHitInterrupt(double simTime) {
        return isInVanillaHitReactionState() || hasHitReaction();
    }

    /** 是否已进入不可行动的被撞/倒地/起身原版状态。 */
    public boolean isInEnteredIncapacitatedState() {
        return isCurrentState(BumpedState.instance())
                || isCurrentState(PlayerGetUpState.instance())
                || isOnFloor()
                || isKnockedDown();
    }

    /** 是否处于被撞、倒地或起身（含 wasBumped 过渡帧），期间严禁发起任何攻击/推搡。 */
    public boolean isInCombatIncapacitatedState() {
        return isInEnteredIncapacitatedState() || isBumped();
    }

    /** 统一攻击禁入判定：受击硬直或被撞/倒地/起身期间均不可发起攻击。 */
    public boolean isCombatAttackBlocked(double simTime) {
        return isInHitInterrupt(simTime) || isInCombatIncapacitatedState();
    }

    /** 是否正在执行近战挥击、推搡或踩踏动作动画。 */
    public boolean isPerformingHostileSwing() {
        return !isDead()
                && !isInVanillaHitReactionState()
                && !isInCombatIncapacitatedState()
                && (isCurrentState(SwipeStatePlayer.instance())
                        || isPerformingAttackAnimation()
                        || isPerformingShoveAnimation()
                        || isPerformingStompAnimation());
    }

    public int getLastAttackerZombieId() {
        return lastAttackerZombieId;
    }

    public void resetLastAttackerZombieId() {
        this.lastAttackerZombieId = -1;
    }

    /** 释放战斗态全部瞬态控制权与 C++ 弹道控制器（死亡/离屏/注销/重制统一收口）。 */
    public void cleanupCombatResources() {
        B42CombatRuntime.cleanupNpcCombat(this, null);
        try {
            releaseBallisticsController();
        } catch (Throwable ignored) {}
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
        cleanupCombatResources();
        StopAllActionQueue();
        if (isAsleep()) {
            setAsleep(false);
        }
        if (getVehicle() != null) {
            VehicleCoRideHelper.forceDetachFromVehicle(this, "DEATH");
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
        int savedGameSpeed = -1;
        if (UIManager.speedControls != null) {
            savedGameSpeed = UIManager.speedControls.getCurrentGameSpeed();
        }
        CombatHookContext.enterNpcUpdate();
        try {
            if (!isDead()) {
                NpcNullInputComponent nullInput = getNullInputComponent();
                if (nullInput != null) {
                    nullInput.tickTime(brainContext.simTime);
                }
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
            CombatHookContext.exitNpcUpdate();
            inPreMovementRatesPhase = false;
            if (savedGameSpeed > 0
                    && UIManager.speedControls != null
                    && UIManager.speedControls.getCurrentGameSpeed() != savedGameSpeed) {
                UIManager.speedControls.SetCurrentGameSpeed(savedGameSpeed);
            }
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

    /**
     * 与原版 {@code IsoPlayer.updateLOS()} 保持逐帧调用契约（通过 {@code cell.getZombieList()} 与无状态
     * {@link LosUtil#lineClear} 隔离本地玩家渲染透明度、惊吓音效与强制 1x 降速副作用），禁止节流，
     * 确保 {@code IsoZombie.spottedNew/Old} 按真实单帧时间步长累加 {@code targetSeenTime} 且
     * {@code closestZombie}/{@code numChasingZombies} 逐帧准确。
     */
    @Override
    public void updateLOS() {
        this.spottedList.clear();
        this.stats.numVisibleZombies = 0;
        this.stats.setLastNumberChasingZombies(this.stats.numChasingZombies);
        this.stats.numChasingZombies = 0;
        this.closestZombie = 1000000.0F;

        IsoGridSquare npcSq = getCurrentSquare();
        IsoCell cell = getCell();
        if (npcSq == null || cell == null || isDead()) {
            this.stats.lastNumVisibleZombies = 0;
            return;
        }
        this.spottedList.add(this);

        float locX = getX();
        float locY = getY();
        float locZ = getZ();
        float rawViewDist = GameTime.getInstance() != null ? GameTime.getInstance().getViewDist() : 20.0F;
        float viewDist = (Float.isFinite(rawViewDist) && rawViewDist >= 10.0F) ? rawViewDist : 20.0F;
        float viewDistSq = viewDist * viewDist;

        ArrayList<IsoZombie> zombies = cell.getZombieList();
        if (zombies != null) {
            for (int i = 0, n = zombies.size(); i < n; i++) {
                IsoZombie z = zombies.get(i);
                if (z == null || z.isDead() || z.isUseless() || z.isReanimatedForGrappleOnly()) {
                    continue;
                }
                if (Math.abs(z.getZ() - locZ) > 2.0F) {
                    continue;
                }
                float dx = z.getX() - locX;
                float dy = z.getY() - locY;
                float distSq = dx * dx + dy * dy;
                if (distSq > viewDistSq) {
                    continue;
                }
                IsoGridSquare zSq = z.getCurrentSquare();
                if (zSq == null) {
                    continue;
                }
                if (zSq != npcSq) {
                    LosUtil.TestResults los = LosUtil.lineClear(
                            cell,
                            npcSq.getX(), npcSq.getY(), npcSq.getZ(),
                            zSq.getX(), zSq.getY(), zSq.getZ(),
                            false);
                    if (los == LosUtil.TestResults.Blocked || los == LosUtil.TestResults.ClearThroughClosedDoor) {
                        continue;
                    }
                }
                this.TestZombieSpotPlayer(z);
                if (!z.ghost && !z.isFakeDead()) {
                    this.spottedList.add(z);
                    if (z.getZ() >= locZ - 1.0F && distSq < 49.0F && zSq.getRoom() == npcSq.getRoom()) {
                        this.stats.numVisibleZombies++;
                    }
                }
            }
        }
        this.stats.lastNumVisibleZombies = this.stats.numVisibleZombies;
    }

    @Override
    protected void doDeferredMovement() {
        if (!attached || getCurrentSquare() == null || isDead()
                || getVehicle() != null || posture.isSuppressingDeferredMovement()) {
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
        if (reset) {
            applyManualMovementClamp(result);
        }
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

    @Override
    public boolean shouldAnimRecorderBeActive() {
        if (isDead() || !attached || getCurrentSquare() == null || IsoPlayer.getInstance() == null) {
            return false;
        }
        return super.shouldAnimRecorderBeActive();
    }

    /** 释放挂载到该 NPC 的原版动画调试监控器与录像器句柄，防止死亡/离屏/注销后泄漏。 */
    public void detachAnimDebugTools() {
        try {
            if (getDebugMonitor() != null && getDebugMonitor().getTarget() == this) {
                setDebugMonitor(null);
            }
        } catch (Throwable ignored) {}
        try {
            setAnimRecorderActive(false, false);
            closeAnimationRecorder();
        } catch (Throwable ignored) {}
    }
}
