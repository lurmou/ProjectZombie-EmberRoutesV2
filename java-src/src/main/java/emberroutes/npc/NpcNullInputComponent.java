package emberroutes.npc;

import zombie.characters.CharacterInputMode;
import zombie.characters.component.CharacterInputComponent;
import zombie.iso.Vector2;

/**
 * {@link IsoNPC} 专用零输入 ECS 组件（0 {@code @Patch} 隔离全部真玩家键鼠/手柄输入窜扰）：
 * <p>
 * B42 原版 {@code IsoPlayer.updateInternal2()} 中的 {@code enterExitVehicle()}、{@code updateWhileInVehicle()}、
 * {@code checkReloading()}、{@code updateSneakKey()} 与 {@code checkWalkTo()} 未检查 {@code isNpc()}。
 * 通过在 {@link IsoNPC#registerECSComponents()} 中以同类 {@code ECSComponent} 覆盖注册，
 * 彻底阻断真玩家按 {@code E} / 瞄准 / 开火 / 蹲伏 / 换弹时对在场与同车 NPC 的输入与光标劫持。
 * </p>
 */
public final class NpcNullInputComponent extends CharacterInputComponent {
    private boolean combatStompIntent;
    private boolean combatMeleeButtonIntent;

    public void setCombatStompIntent(boolean combatStompIntent) {
        this.combatStompIntent = combatStompIntent;
    }

    public boolean isCombatStompIntent() {
        return combatStompIntent;
    }

    public void setCombatMeleeButtonIntent(boolean combatMeleeButtonIntent) {
        this.combatMeleeButtonIntent = combatMeleeButtonIntent;
    }

    public boolean isCombatMeleeButtonIntent() {
        return combatMeleeButtonIntent;
    }

    public void clearCombatIntents() {
        this.combatStompIntent = false;
        this.combatMeleeButtonIntent = false;
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
        return false;
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
        return false;
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
        return false;
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
        return combatStompIntent;
    }

    @Override
    public boolean isZoomInButtonPressed() {
        return false;
    }

    @Override
    public boolean isZoomOutButtonPressed() {
        return false;
    }

    @Override
    public boolean isLShiftKeyDown() {
        return false;
    }

    @Override
    public boolean isRShiftKeyDown() {
        return false;
    }

    @Override
    public boolean isShiftKeyDown() {
        return false;
    }

    @Override
    public boolean isLCtrlKeyDown() {
        return false;
    }

    @Override
    public boolean isRCtrlKeyDown() {
        return false;
    }

    @Override
    public boolean isCtrlKeyDown() {
        return false;
    }

    @Override
    public boolean isF12KeyDown() {
        return false;
    }

    @Override
    public boolean isChangeCharacterKeyDown() {
        return false;
    }
}
