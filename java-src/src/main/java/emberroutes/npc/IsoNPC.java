package emberroutes.npc;

import zombie.Lua.LuaEventManager;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
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

    private boolean attached;
    private boolean runningMode;
    private boolean forceLocalForRootMotion;

    private volatile boolean nativeSmoothPathUnlock = true;
    private boolean emberControlActive;
    private boolean emberMoving;
    private boolean emberDesiredRunning;
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
            this.emberAnimSpeedScalar = 1.0F;
            clearManualMovementClamp();
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
        if (emberControlActive && emberMoving && isRunning()
                && Math.abs(emberTurnDelta - 0.80F) > 0.01F) {
            return emberTurnDelta;
        }
        return super.getTurnDelta();
    }

    @Override
    protected void calculateWalkSpeed() {
        super.calculateWalkSpeed();
        if (emberControlActive && emberMoving) {
            float physCap = getVariableFloat("WalkSpeed", 1.0F);
            float cvt = Math.max(0.05F, Math.min(1.0F, physCap * emberAnimSpeedScalar));
            setVariable("WalkSpeed", cvt);
        }
    }

    @Override
    public void setRunning(boolean bRunning) {
        // 拦截 IsoPlayer.updateInternal2 中段对 running 的无条件清零
        if (emberControlActive && emberMoving && emberDesiredRunning && !bRunning) {
            super.setRunning(true);
            return;
        }
        super.setRunning(bRunning);
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
    public UpdateSchedulerSimulationLevel getMinimumSimulationLevel() {
        return UpdateSchedulerSimulationLevel.FULL;
    }

    @Override
    public void OnDeath() {
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
        if (attached && (!isDead() || !isOnDeathDone())) {
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
                locomotion.beforeUpdate(this);
            }
            super.update();
        } finally {
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
        if (!attached || getCurrentSquare() == null || isDead()) {
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
        if (attached && (!isDead() || !isOnDeathDone())) {
            super.postupdate();
        }
    }

    public void discardRootMotion() {
        if (hasAnimationPlayer() && getAnimationPlayer() != null) {
            getAnimationPlayer().resetDeferredMovementAccum();
        }
    }
}
