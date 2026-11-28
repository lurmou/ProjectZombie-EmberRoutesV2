package emberroutes;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.NpcNullInputComponent;
import emberroutes.npc.combat.CombatHookContext;
import java.lang.reflect.Field;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.AttackType;
import zombie.ai.states.SwipeStatePlayer;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.CharacterTimedActions.BaseAction;
import zombie.core.physics.BallisticsController;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.ui.ActionProgressBar;
import zombie.ui.UIManager;

/**
 * B42 原版战斗 7 大引擎红线（C1–C7）最小 {@code ZombieBuddy} 字节码织入点（全部直驻根包 {@code emberroutes}，0 Kahlua 依赖）：
 *
 * <ol>
 *   <li><b>C1 / C2 / C5（{@link SwipeStatePlayerEnter} + 6 个 {@code OnAnimEvent_*}）</b>：
 *       {@code enter()} 期间保持 {@code isLocalPlayer()==false} 保护真玩家鼠标拖拽，同时保住全局倍速与 {@code attackTargetSquare}，
 *       退出时补齐踩踏/推搡判定与 {@code PlayedSwingSound} 清理；仅在 {@code OnAnimEvent_*} 回调窗口瞬开 {@code forceLocalForCombat} 复用 100% 原版命中、音效、血迹、曳光与耐久结算；</li>
 *   <li><b>C3（{@link BallisticsUpdate} + {@link AimingReticleGetX} + {@link AimingReticleGetY} + {@link CombatHighlightTarget} + {@link ReticleModeGuard}）</b>：
 *       为 {@code BallisticsController} 注入 {@code NpcVirtualAim} 逆投影屏幕准星，并屏蔽 NPC 触发的玩家屏幕高亮与准星模式篡改；</li>
 *   <li><b>C4（{@link MoodlesWiggleGuard}）</b>：屏蔽 NPC 攻击/射击对真玩家 {@code MoodlesUI} 的情绪摇晃；</li>
 *   <li><b>C7（{@link CombatCheckPvp}）</b>：硬隔离全部玩家↔NPC、NPC↔NPC 友伤（即使开启 {@code CoopPVP} 或霰弹散射）；</li>
 *   <li><b>HUD 进度条隔离（{@link BaseActionStopProgressGuard} + {@link BaseActionPerformProgressGuard}）</b>：
 *       隔离原版 {@code BaseAction.stop/perform} 对 {@code UIManager.getProgressBar(0)} 的无条件覆写；</li>
 *   <li><b>非玩家槽位丧尸视线隔离（{@link ZombieTargetVisibleForNpc}）</b>：
 *       拦截 {@code IsoZombie.isTargetVisible()}，当丧尸目标为 {@link IsoNPC} 时改用实体间真实 {@code LosUtil.lineClear} 判定；</li>
 *   <li><b>鼠标可见性隔离（{@link CursorVisibilityGuard}）</b>：
 *       拦截 {@code IsoPlayer.updateCursorVisibility()}，阻止 {@link IsoNPC} 在瞄准态调用 {@code Mouse.setCursorVisible(false)} 隐藏真玩家鼠标；</li>
 *   <li><b>友方分离只禁绊倒保留物理碰撞体积（{@link SeparateBumpScope} + {@link SeparateCandidateTracker} + {@link SeparateFriendlyBumpGuard}）</b>：
 *       保持 {@code isSolidForSeparate()} 100% 原版返回值以保留 {@code IsoMovingObject.separate()} 物理推开，
 *       仅在 {@code AttackType.CHARGE} 判定处短路玩家↔NPC / NPC↔NPC 友方绊倒及 {@code bumped} 重入刷新。</li>
 * </ol>
 */
public final class Patch_CombatHooks {
    private static final Field BALLISTICS_OWNER_FIELD =
            findDeclaredField(BallisticsController.class, "isoGameCharacter");

    private Patch_CombatHooks() {}

    @Patch(className = "zombie.ai.states.SwipeStatePlayer", methodName = "enter")
    public static final class SwipeStatePlayerEnter {
        @Patch.OnEnter
        public static void enter(
                @Patch.Argument(0) IsoGameCharacter owner,
                @Patch.Local("savedSpeed") int savedSpeed,
                @Patch.Local("savedTargetSq") IsoGridSquare savedTargetSq,
                @Patch.Local("wasDoShove") boolean wasDoShove,
                @Patch.Local("combatEntered") boolean combatEntered) {
            savedSpeed = -1;
            savedTargetSq = null;
            wasDoShove = false;
            combatEntered = false;
            if (owner instanceof IsoNPC npc) {
                if (UIManager.speedControls != null) {
                    savedSpeed = UIManager.speedControls.getCurrentGameSpeed();
                }
                savedTargetSq = npc.getAttackTargetSquare();
                wasDoShove = npc.isDoShove();
                CombatHookContext.enterNpcCombat();
                combatEntered = true;
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Argument(0) IsoGameCharacter owner,
                @Patch.Local("savedSpeed") int savedSpeed,
                @Patch.Local("savedTargetSq") IsoGridSquare savedTargetSq,
                @Patch.Local("wasDoShove") boolean wasDoShove,
                @Patch.Local("combatEntered") boolean combatEntered) {
            if (!combatEntered || !(owner instanceof IsoNPC npc)) {
                return;
            }
            try {
                if (savedSpeed > 0
                        && UIManager.speedControls != null
                        && UIManager.speedControls.getCurrentGameSpeed() != savedSpeed) {
                    UIManager.speedControls.SetCurrentGameSpeed(savedSpeed);
                }
                if (savedTargetSq != null) {
                    npc.setAttackTargetSquare(savedTargetSq);
                }
                // 还原 SwipeStatePlayer.java:217-233 因保持 isLocalPlayer()==false 而跳过的音效变量与踩踏/推搡分支
                HandWeapon weapon = npc.getAttackVars().getWeapon(npc);
                boolean shoveNow = npc.isDoShove() || wasDoShove;
                if (!shoveNow && weapon != null && !weapon.isRanged()) {
                    npc.clearVariable("PlayedSwingSound");
                } else if (shoveNow && !npc.isGrappling()) {
                    boolean isStomp = npc.targetOnGround != null
                            && npc.getAttackVars().targetDistance < 0.6F
                            && !SwipeStatePlayer.isStompingDisabled(npc, true);
                    npc.setShoveStompAnim(isStomp);
                    npc.playSound(isStomp ? "AttackStomp" : "AttackShove");
                }
            } finally {
                NpcNullInputComponent in = npc.getNullInputComponent();
                if (in != null) {
                    in.consumeOnSwipeEnter();
                }
                CombatHookContext.exitNpcCombat();
            }
        }
    }

    @Patch(className = "zombie.ai.states.SwipeStatePlayer", methodName = "OnAnimEvent_AttackCollisionCheck")
    public static final class SwipeAttackCollisionCheck {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) IsoGameCharacter owner, @Patch.Local("prev") int prev) {
            prev = enterLocalCombat(owner);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(@Patch.Argument(0) IsoGameCharacter owner, @Patch.Local("prev") int prev) {
            exitLocalCombat(owner, prev);
        }
    }

    @Patch(className = "zombie.ai.states.SwipeStatePlayer", methodName = "OnAnimEvent_PlaySwingSound")
    public static final class SwipePlaySwingSound {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) IsoGameCharacter owner, @Patch.Local("prev") int prev) {
            prev = enterLocalCombat(owner);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(@Patch.Argument(0) IsoGameCharacter owner, @Patch.Local("prev") int prev) {
            exitLocalCombat(owner, prev);
        }
    }

    @Patch(className = "zombie.ai.states.SwipeStatePlayer", methodName = "OnAnimEvent_PlaySwingSoundAlways")
    public static final class SwipePlaySwingSoundAlways {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) IsoGameCharacter owner, @Patch.Local("prev") int prev) {
            prev = enterLocalCombat(owner);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(@Patch.Argument(0) IsoGameCharacter owner, @Patch.Local("prev") int prev) {
            exitLocalCombat(owner, prev);
        }
    }

    @Patch(className = "zombie.ai.states.SwipeStatePlayer", methodName = "OnAnimEvent_PlayRackSound")
    public static final class SwipePlayRackSound {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) IsoGameCharacter owner, @Patch.Local("prev") int prev) {
            prev = enterLocalCombat(owner);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(@Patch.Argument(0) IsoGameCharacter owner, @Patch.Local("prev") int prev) {
            exitLocalCombat(owner, prev);
        }
    }

    @Patch(className = "zombie.ai.states.SwipeStatePlayer", methodName = "OnAnimEvent_PlayClickSound")
    public static final class SwipePlayClickSound {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) IsoGameCharacter owner, @Patch.Local("prev") int prev) {
            prev = enterLocalCombat(owner);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(@Patch.Argument(0) IsoGameCharacter owner, @Patch.Local("prev") int prev) {
            exitLocalCombat(owner, prev);
        }
    }

    @Patch(className = "zombie.ai.states.SwipeStatePlayer", methodName = "OnAnimEvent_WeaponEmptyCheck")
    public static final class SwipeWeaponEmptyCheck {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) IsoGameCharacter owner, @Patch.Local("prev") int prev) {
            prev = enterLocalCombat(owner);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(@Patch.Argument(0) IsoGameCharacter owner, @Patch.Local("prev") int prev) {
            exitLocalCombat(owner, prev);
        }
    }

    @Patch(className = "zombie.core.physics.BallisticsController", methodName = "update")
    public static final class BallisticsUpdate {
        @Patch.OnEnter
        public static void enter(
                @Patch.This BallisticsController self,
                @Patch.Local("pushed") boolean pushed,
                @Patch.Local("prevNpc") IsoNPC prevNpc) {
            pushed = false;
            prevNpc = null;
            IsoNPC npc = CombatHookContext.resolveBallisticsNpc(readBallisticsOwner(self));
            if (npc != null) {
                prevNpc = CombatHookContext.swapBallisticsNpc(npc);
                pushed = true;
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Local("pushed") boolean pushed,
                @Patch.Local("prevNpc") IsoNPC prevNpc) {
            if (pushed) {
                CombatHookContext.restoreBallisticsNpc(prevNpc);
            }
        }
    }

    @Patch(className = "zombie.input.AimingReticle", methodName = "getX")
    public static final class AimingReticleGetX {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Local("npc") IsoNPC npc) {
            npc = CombatHookContext.getActiveBallisticsNpc();
            return npc != null;
        }

        @Patch.OnExit
        public static void exit(
                @Patch.Local("npc") IsoNPC npc,
                @Patch.Return(readOnly = false) int ret) {
            if (npc != null) {
                ret = npc.getVirtualReticleScreenX();
            }
        }
    }

    @Patch(className = "zombie.input.AimingReticle", methodName = "getY")
    public static final class AimingReticleGetY {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Local("npc") IsoNPC npc) {
            npc = CombatHookContext.getActiveBallisticsNpc();
            return npc != null;
        }

        @Patch.OnExit
        public static void exit(
                @Patch.Local("npc") IsoNPC npc,
                @Patch.Return(readOnly = false) int ret) {
            if (npc != null) {
                ret = npc.getVirtualReticleScreenY();
            }
        }
    }

    @Patch(className = "zombie.CombatManager", methodName = "highlightTarget")
    public static final class CombatHighlightTarget {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            return CombatHookContext.isNpcUpdateOrCombatActive();
        }
    }

    @Patch(className = "zombie.iso.sprite.IsoReticle", methodName = "trySetTargetReticleMode")
    public static final class ReticleModeGuard {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            return CombatHookContext.isNpcUpdateOrCombatActive();
        }
    }

    @Patch(className = "zombie.ui.MoodlesUI", methodName = "wiggle")
    public static final class MoodlesWiggleGuard {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            return CombatHookContext.isNpcUpdateOrCombatActive();
        }
    }

    @Patch(className = "zombie.CombatManager", methodName = "checkPVP")
    public static final class CombatCheckPvp {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(
                @Patch.Argument(0) IsoMovingObject owner,
                @Patch.Argument(1) IsoMovingObject target,
                @Patch.Local("blocked") boolean blocked) {
            blocked = CombatHookContext.isFriendlyPvpBlocked(owner, target);
            return blocked;
        }

        @Patch.OnExit
        public static void exit(
                @Patch.Local("blocked") boolean blocked,
                @Patch.Return(readOnly = false) boolean ret) {
            if (blocked) {
                ret = false;
            }
        }
    }

    @Patch(className = "zombie.characters.CharacterTimedActions.BaseAction", methodName = "stop")
    public static final class BaseActionStopProgressGuard {
        @Patch.OnEnter
        public static void enter(@Patch.This BaseAction self, @Patch.Local("savedVal") float savedVal) {
            savedVal = captureProgressBar(self);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(@Patch.This BaseAction self, @Patch.Local("savedVal") float savedVal) {
            restoreProgressBar(self, savedVal);
        }
    }

    @Patch(className = "zombie.characters.CharacterTimedActions.BaseAction", methodName = "perform")
    public static final class BaseActionPerformProgressGuard {
        @Patch.OnEnter
        public static void enter(@Patch.This BaseAction self, @Patch.Local("savedVal") float savedVal) {
            savedVal = captureProgressBar(self);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(@Patch.This BaseAction self, @Patch.Local("savedVal") float savedVal) {
            restoreProgressBar(self, savedVal);
        }
    }

    @Patch(className = "zombie.characters.IsoPlayer", methodName = "updateCursorVisibility")
    public static final class CursorVisibilityGuard {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This IsoPlayer self) {
            return CombatHookContext.shouldSkipCursorVisibility(self);
        }
    }

    @Patch(className = "zombie.iso.IsoMovingObject", methodName = "separate")
    public static final class SeparateBumpScope {
        @Patch.OnEnter
        public static void enter(
                @Patch.This IsoMovingObject self,
                @Patch.Local("pushed") boolean pushed,
                @Patch.Local("prevPlayer") IsoPlayer prevPlayer,
                @Patch.Local("prevOther") IsoMovingObject prevOther) {
            pushed = false;
            prevPlayer = null;
            prevOther = null;
            if (self instanceof IsoPlayer p && !p.isAnimal() && !p.isZombie()) {
                prevPlayer = CombatHookContext.swapSeparatingPlayer(p);
                prevOther = CombatHookContext.swapSeparateCandidate(null);
                pushed = true;
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Local("pushed") boolean pushed,
                @Patch.Local("prevPlayer") IsoPlayer prevPlayer,
                @Patch.Local("prevOther") IsoMovingObject prevOther) {
            if (pushed) {
                CombatHookContext.restoreSeparateContext(prevPlayer, prevOther);
            }
        }
    }

    @Patch(className = "zombie.iso.IsoMovingObject", methodName = "isSolidForSeparate")
    public static final class SeparateCandidateTracker {
        @Patch.OnEnter
        public static void enter(@Patch.This IsoMovingObject self) {
            CombatHookContext.recordSeparateCandidate(self);
        }
    }

    @Patch(className = "zombie.characters.IsoPlayer", methodName = "isAttackType")
    public static final class SeparateFriendlyBumpGuard {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(
                @Patch.This IsoPlayer self,
                @Patch.Argument(0) AttackType attackType,
                @Patch.Local("suppress") boolean suppress) {
            suppress = (attackType == AttackType.CHARGE)
                    && CombatHookContext.shouldSuppressSeparateBump(self);
            return suppress;
        }

        @Patch.OnExit
        public static void exit(
                @Patch.Local("suppress") boolean suppress,
                @Patch.Return(readOnly = false) boolean ret) {
            if (suppress) {
                ret = true;
            }
        }
    }

    @Patch(className = "zombie.characters.IsoZombie", methodName = "isTargetVisible")
    public static final class ZombieTargetVisibleForNpc {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(
                @Patch.This IsoZombie self,
                @Patch.Local("isNpcTarget") boolean isNpcTarget,
                @Patch.Local("npcVisible") boolean npcVisible) {
            isNpcTarget = false;
            npcVisible = false;
            if (self != null && self.getTarget() instanceof IsoNPC npc) {
                isNpcTarget = true;
                npcVisible = CombatHookContext.isZombieTargetNpcVisible(self, npc);
                return true;
            }
            return false;
        }

        @Patch.OnExit
        public static void exit(
                @Patch.Local("isNpcTarget") boolean isNpcTarget,
                @Patch.Local("npcVisible") boolean npcVisible,
                @Patch.Return(readOnly = false) boolean ret) {
            if (isNpcTarget) {
                ret = npcVisible;
            }
        }
    }

    public static int enterLocalCombat(IsoGameCharacter owner) {
        if (!(owner instanceof IsoNPC npc)) {
            return -1;
        }
        int prev = npc.isForceLocalForCombat() ? 1 : 0;
        npc.setForceLocalForCombat(true);
        CombatHookContext.enterNpcCombat();
        return prev;
    }

    public static void exitLocalCombat(IsoGameCharacter owner, int prev) {
        if (prev < 0 || !(owner instanceof IsoNPC npc)) {
            return;
        }
        try {
            npc.setForceLocalForCombat(prev == 1);
        } finally {
            CombatHookContext.exitNpcCombat();
        }
    }

    public static Object readBallisticsOwner(BallisticsController controller) {
        if (controller == null || BALLISTICS_OWNER_FIELD == null) {
            return null;
        }
        try {
            return BALLISTICS_OWNER_FIELD.get(controller);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static float captureProgressBar(BaseAction action) {
        if (action == null || !(action.chr instanceof IsoNPC npc)) {
            return Float.NaN;
        }
        try {
            ActionProgressBar bar = UIManager.getProgressBar(((IsoPlayer) npc).getIndex());
            return bar != null ? bar.getValue() : Float.NaN;
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    public static void restoreProgressBar(BaseAction action, float savedVal) {
        if (!Float.isFinite(savedVal) && savedVal != Float.POSITIVE_INFINITY) {
            return;
        }
        if (action == null || !(action.chr instanceof IsoNPC npc)) {
            return;
        }
        try {
            ActionProgressBar bar = UIManager.getProgressBar(((IsoPlayer) npc).getIndex());
            if (bar != null) {
                bar.setValue(savedVal);
            }
        } catch (Throwable ignored) {}
    }

    private static Field findDeclaredField(Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
