package emberroutes.npc;

import zombie.characters.CharacterInputMode;
import zombie.characters.component.CharacterInputComponent;
import zombie.core.skinnedmodel.animation.debug.AnimationPlayerRecorder;
import zombie.iso.Vector2;

/**
 * {@link IsoNPC} 专用零输入 ECS 组件（0 {@code @Patch} 隔离全部真玩家键鼠/手柄输入窜扰）：
 * <ul>
 *   <li>彻底阻断真玩家按 {@code E} / 瞄准 / 开火 / 蹲伏 / 换弹时对在场与同车 NPC 的输入与光标劫持；</li>
 *   <li>以带过期保护的 armed intent 向下一帧 {@code SwipeStatePlayer.enter()} 稳定透传
 *       {@link #isMeleeButtonDown()} 与 {@link #isManualFloorAtkButtonDown()}（根治关闭 {@code AutoProneAtk} 时踩踏/打地失效）；</li>
 *   <li>为全自动连发枪械提供短窗 {@link #armAutoFireHold(double, double)}，避免 {@code IsoPlayer.updateAttackLoopSound:7396} 次帧截断枪声；</li>
 *   <li>仅在用户手动开启 {@code AnimationPlayerRecorder} 时向 {@code *_Vars_values.csv} 写出原生与运动状态字段。</li>
 * </ul>
 */
public final class NpcNullInputComponent extends CharacterInputComponent {
    private final IsoNPC ownerNpc;

    private boolean combatMeleeButtonIntent;
    private boolean combatManualFloorAtkIntent;
    private double combatIntentExpireSim = -1.0;

    private double autoFireHoldUntilSim = -1.0;
    private double lastObservedSimTime = 0.0;

    public NpcNullInputComponent() {
        this(null);
    }

    public NpcNullInputComponent(IsoNPC ownerNpc) {
        this.ownerNpc = ownerNpc;
    }

    /**
     * 武装一次近战/推击/手动打地意图，维持至下一帧 {@code SwipeStatePlayer.enter()} 消费完成或超时过期。
     */
    public void armCombatIntents(boolean meleeButton, boolean manualFloorAtk, double nowSim, double ttlSim) {
        this.lastObservedSimTime = nowSim;
        this.combatMeleeButtonIntent = meleeButton;
        this.combatManualFloorAtkIntent = manualFloorAtk;
        this.combatIntentExpireSim = nowSim + Math.max(0.05, ttlSim);
    }

    /** 兼容旧单测/调用点设置单帧意图。 */
    public void setCombatMeleeButtonIntent(boolean combatMeleeButtonIntent) {
        this.combatMeleeButtonIntent = combatMeleeButtonIntent;
        if (combatMeleeButtonIntent && combatIntentExpireSim < 0.0) {
            this.combatIntentExpireSim = Double.MAX_VALUE;
        }
    }

    public boolean isCombatMeleeButtonIntent() {
        return combatMeleeButtonIntent;
    }

    public void setCombatManualFloorAtkIntent(boolean combatManualFloorAtkIntent) {
        this.combatManualFloorAtkIntent = combatManualFloorAtkIntent;
        if (combatManualFloorAtkIntent && combatIntentExpireSim < 0.0) {
            this.combatIntentExpireSim = Double.MAX_VALUE;
        }
    }

    public boolean isCombatManualFloorAtkIntent() {
        return combatManualFloorAtkIntent;
    }

    /** 在 {@code SwipeStatePlayer.enter()} 退出钩子中消费武装的近战/打地意图（确保 {@code isStompingDisabled} 读到真值）。 */
    public void consumeOnSwipeEnter() {
        this.combatMeleeButtonIntent = false;
        this.combatManualFloorAtkIntent = false;
        this.combatIntentExpireSim = -1.0;
    }

    /** 为 {@code "Auto"} 连发枪械保持短促开火输入窗，防止 {@code IsoPlayer.updateAttackLoopSound:7396} 次帧掐断枪声。 */
    public void armAutoFireHold(double nowSim, double holdDurationSim) {
        this.lastObservedSimTime = nowSim;
        this.autoFireHoldUntilSim = nowSim + Math.max(0.08, holdDurationSim);
    }

    public void clearAutoFireHold() {
        this.autoFireHoldUntilSim = -1.0;
    }

    /** 每帧检查意图与全自动连发保持窗过期（纯 O(1)，无分配）。 */
    public void tickTime(double nowSim) {
        this.lastObservedSimTime = nowSim;
        if (combatIntentExpireSim >= 0.0 && nowSim >= combatIntentExpireSim) {
            consumeOnSwipeEnter();
        }
        if (autoFireHoldUntilSim >= 0.0 && nowSim >= autoFireHoldUntilSim) {
            autoFireHoldUntilSim = -1.0;
        }
    }

    public boolean isAutoFireHoldActive() {
        return autoFireHoldUntilSim >= 0.0 && lastObservedSimTime < autoFireHoldUntilSim;
    }

    public void clearCombatIntents() {
        this.combatMeleeButtonIntent = false;
        this.combatManualFloorAtkIntent = false;
        this.combatIntentExpireSim = -1.0;
        this.autoFireHoldUntilSim = -1.0;
    }

    @Override
    public void logVariablesToRecording(AnimationPlayerRecorder recorder) {
        if (recorder == null || ownerNpc == null) {
            return;
        }
        try {
            recorder.logVariable("er.npcId", ownerNpc.getNpcId() != null ? ownerNpc.getNpcId() : "");
            recorder.logVariable("er.brainMode", ownerNpc.getBrain().getMode().name());
            recorder.logVariable("er.brainPath", ownerNpc.getBrain().getActivePathString());
            recorder.logVariable("er.posture", ownerNpc.getPosture().getState().name());
            recorder.logVariable("er.posX", ownerNpc.getX());
            recorder.logVariable("er.posY", ownerNpc.getY());
            recorder.logVariable("er.posZ", ownerNpc.getZ());
            recorder.logVariable("er.fwdX", ownerNpc.getForwardDirectionX());
            recorder.logVariable("er.fwdY", ownerNpc.getForwardDirectionY());
            recorder.logVariable("er.dirAngleDeg", ownerNpc.getDirectionAngle());
            recorder.logVariable("er.animAngleDeg", ownerNpc.getAnimAngle());
            recorder.logVariable("er.isAiming", ownerNpc.isAiming());
            recorder.logVariable("er.isStrafing", ownerNpc.isStrafing());
            recorder.logVariable("er.isJustMoved", ownerNpc.isJustMoved());
            recorder.logVariable("er.isPlayerMoving", ownerNpc.isPlayerMoving());
            recorder.logVariable("er.meleeDelay", ownerNpc.getMeleeDelay());
            recorder.logVariable("er.recoilDelay", ownerNpc.getRecoilDelay());
            recorder.logVariable("er.combatSpeed", ownerNpc.getCombatSpeed());

            EmberLocomotionController loco = ownerNpc.getLocomotion();
            recorder.logVariable("er.locoCombatActive", loco.isCombatStrafeActive());
            recorder.logVariable("er.locoCombatMoving", loco.isCombatMoveMoving());
            recorder.logVariable("er.locoCombatAimStrafe", loco.isCombatAimStrafing());
            recorder.logVariable("er.locoCombatRun", loco.isCombatRunGear());
            recorder.logVariable("er.locoCombatDirX", loco.getCombatMoveDirX());
            recorder.logVariable("er.locoCombatDirY", loco.getCombatMoveDirY());
            recorder.logVariable("er.locoPathActive", loco.isNormalPathActive());
        } catch (Throwable ignored) {}
    }

    @Override
    public void frameStep() {
        // NPC 不采样真玩家键鼠输入状态机
    }

    @Override
    public CharacterInputMode getInputMode() {
        return CharacterInputMode.NONE;
    }

    @Override
    public boolean isJoypadControllerActive() {
        return false;
    }

    @Override
    public Vector2 getInputMoveVector(Vector2 out) {
        if (out != null) {
            out.set(0.0F, 0.0F);
        }
        return out;
    }

    @Override
    public float getInputMovementRate() {
        return 0.0F;
    }

    @Override
    public Vector2 getJoypadAimVector(Vector2 out) {
        if (out != null) {
            out.set(0.0F, 0.0F);
        }
        return out;
    }

    @Override
    public boolean isForwardKeyDown() {
        return false;
    }

    @Override
    public boolean isBackwardKeyDown() {
        return false;
    }

    @Override
    public boolean isLeftKeyDown() {
        return false;
    }

    @Override
    public boolean isRightKeyDown() {
        return false;
    }

    @Override
    public boolean isKeyboardSelectingAll() {
        return false;
    }

    @Override
    public boolean isAimKeyDown() {
        return isAutoFireHoldActive();
    }

    @Override
    public boolean isPrecisionAimKeyDown() {
        return false;
    }

    @Override
    public boolean isToggleAimKeyDown() {
        return false;
    }

    @Override
    public boolean isToggleAimKeyMouse() {
        return false;
    }

    @Override
    public boolean isToggleSprintButtonDown() {
        return false;
    }

    @Override
    public boolean isToggleRunButtonDown() {
        return false;
    }

    @Override
    public boolean isToggleCrouchButtonDown() {
        return false;
    }

    @Override
    public boolean isAimKeyDownInternal() {
        return false;
    }

    @Override
    public boolean isPrecisionAimKeyDownInternal() {
        return false;
    }

    @Override
    public boolean isAnyAimKeyDown() {
        return isAutoFireHoldActive();
    }

    @Override
    public boolean isMeleeButtonDown() {
        return combatMeleeButtonIntent;
    }

    @Override
    public boolean isMeleeButtonDownInternal() {
        return false;
    }

    @Override
    public boolean isAttackButtonDown() {
        return isAutoFireHoldActive();
    }

    @Override
    public boolean isBuildButtonDown() {
        return false;
    }

    @Override
    public boolean isBuildButtonReleased() {
        return false;
    }

    @Override
    public boolean isRunButtonDown() {
        return false;
    }

    @Override
    public boolean wasRunButtonDown() {
        return false;
    }

    @Override
    public boolean isInteractButtonPressed() {
        return false;
    }

    @Override
    public boolean isInteractButtonDown() {
        return false;
    }

    @Override
    public boolean isInteractButtonDownInternal() {
        return false;
    }

    @Override
    public boolean isInteractButtonClicked() {
        return false;
    }

    @Override
    public boolean isWalkToButtonDown() {
        return false;
    }

    @Override
    public boolean isCrouchButtonDown() {
        return false;
    }

    @Override
    public boolean isCrouchButtonPressed() {
        return false;
    }

    @Override
    public boolean isReloadWeaponButtonPressed() {
        return false;
    }

    @Override
    public boolean isRackFirearmButtonPressed() {
        return false;
    }

    @Override
    public boolean isSprintButtonDown() {
        return false;
    }

    @Override
    public boolean wasSprintButtonDown() {
        return false;
    }

    @Override
    public boolean isCancelActionButtonDown() {
        return false;
    }

    @Override
    public boolean isManualFloorAtkButtonDown() {
        return combatManualFloorAtkIntent;
    }
}
