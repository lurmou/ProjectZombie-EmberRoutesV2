package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.action.ActionContext;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.LosUtil;
import zombie.vehicles.BaseVehicle;

/**
 * B42 原版战斗引擎红线（C1–C7）的零 GC 线程上下文状态载体。
 *
 * <p>位于 {@code emberroutes.npc.combat} 域内，供引擎织入层读写而不产生域向桥接反向依赖。</p>
 */
public final class CombatHookContext {
    private static final ThreadLocal<IsoNPC> ACTIVE_BALLISTICS_NPC = new ThreadLocal<>();
    private static final ThreadLocal<Integer> NPC_COMBAT_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> NPC_UPDATE_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<IsoPlayer> SEPARATING_PLAYER = new ThreadLocal<>();
    private static final ThreadLocal<IsoMovingObject> SEPARATE_OTHER = new ThreadLocal<>();

    private CombatHookContext() {}

    public static void resetAll() {
        ACTIVE_BALLISTICS_NPC.remove();
        NPC_COMBAT_DEPTH.remove();
        NPC_UPDATE_DEPTH.remove();
        SEPARATING_PLAYER.remove();
        SEPARATE_OTHER.remove();
    }

    public static IsoNPC getActiveBallisticsNpc() {
        return ACTIVE_BALLISTICS_NPC.get();
    }

    public static IsoNPC swapBallisticsNpc(IsoNPC next) {
        IsoNPC prev = ACTIVE_BALLISTICS_NPC.get();
        if (next == null) {
            ACTIVE_BALLISTICS_NPC.remove();
        } else {
            ACTIVE_BALLISTICS_NPC.set(next);
        }
        return prev;
    }

    public static void restoreBallisticsNpc(IsoNPC prev) {
        if (prev == null) {
            ACTIVE_BALLISTICS_NPC.remove();
        } else {
            ACTIVE_BALLISTICS_NPC.set(prev);
        }
    }

    public static IsoNPC resolveBallisticsNpc(Object owner) {
        if (owner instanceof IsoNPC npc && !npc.isDead() && npc.hasVirtualAim()) {
            return npc;
        }
        return null;
    }

    public static void enterNpcCombat() {
        NPC_COMBAT_DEPTH.set(NPC_COMBAT_DEPTH.get() + 1);
    }

    public static void exitNpcCombat() {
        int next = NPC_COMBAT_DEPTH.get() - 1;
        if (next <= 0) {
            NPC_COMBAT_DEPTH.remove();
        } else {
            NPC_COMBAT_DEPTH.set(next);
        }
    }

    public static boolean isNpcCombatActive() {
        return NPC_COMBAT_DEPTH.get() > 0;
    }

    public static void enterNpcUpdate() {
        NPC_UPDATE_DEPTH.set(NPC_UPDATE_DEPTH.get() + 1);
    }

    public static void exitNpcUpdate() {
        int next = NPC_UPDATE_DEPTH.get() - 1;
        if (next <= 0) {
            NPC_UPDATE_DEPTH.remove();
        } else {
            NPC_UPDATE_DEPTH.set(next);
        }
    }

    public static boolean isNpcUpdateOrCombatActive() {
        return NPC_UPDATE_DEPTH.get() > 0 || NPC_COMBAT_DEPTH.get() > 0;
    }

    /** C7 友伤硬盾判定，委派给零 B42 依赖的 {@link FriendlyFireRules}。 */
    public static boolean evaluateFriendlyFlags(
            boolean attackerIsPlayer,
            boolean attackerIsZombie,
            boolean victimIsPlayer,
            boolean victimIsZombie,
            boolean sameEntity) {
        return FriendlyFireRules.evaluateFriendlyFlags(
                attackerIsPlayer, attackerIsZombie, victimIsPlayer, victimIsZombie, sameEntity);
    }

    public static boolean isFriendlyPair(IsoGameCharacter attacker, IsoGameCharacter victim) {
        if (attacker == null || victim == null) {
            return false;
        }
        return FriendlyFireRules.evaluateFriendlyFlags(
                attacker instanceof IsoPlayer,
                attacker.isZombie(),
                victim instanceof IsoPlayer,
                victim.isZombie(),
                attacker == victim);
    }

    /**
     * 运行时 {@code CombatManager.checkPVP(IsoMovingObject, IsoMovingObject, boolean)} 友伤隔离入口。
     * 同时识别 {@link BaseVehicle} 驾驶员/乘客，防止扫射载具内友方玩家或随从。
     */
    public static boolean isFriendlyPvpBlocked(IsoMovingObject owner, IsoMovingObject target) {
        IsoGameCharacter a = resolveHumanCharacter(owner);
        if (a == null) {
            return false;
        }
        IsoGameCharacter b = resolveHumanCharacter(target);
        if (b == null) {
            return false;
        }
        if (isFriendlyPair(a, b)) {
            return true;
        }
        // 也检查载具全部乘客，确保副驾 NPC / 乘客玩家不会因 driver == null 被误伤
        if (target instanceof BaseVehicle veh && hasFriendlyOccupant(a, veh)) {
            return true;
        }
        return owner instanceof BaseVehicle veh && hasFriendlyOccupant(b, veh);
    }

    private static boolean hasFriendlyOccupant(IsoGameCharacter other, BaseVehicle veh) {
        if (other == null || veh == null || other.isZombie() || !(other instanceof IsoPlayer)) {
            return false;
        }
        int maxSeats = veh.getMaxPassengers();
        for (int i = 0; i < maxSeats; i++) {
            IsoGameCharacter occupant = veh.getCharacter(i);
            if (isFriendlyPair(other, occupant)) {
                return true;
            }
        }
        return false;
    }

    private static IsoGameCharacter resolveHumanCharacter(IsoMovingObject obj) {
        if (obj instanceof IsoGameCharacter chr && !chr.isZombie()) {
            return chr;
        }
        if (obj instanceof BaseVehicle veh) {
            IsoGameCharacter driver = veh.getDriver();
            if (driver != null && !driver.isZombie()) {
                return driver;
            }
            int maxSeats = veh.getMaxPassengers();
            for (int i = 0; i < maxSeats; i++) {
                IsoGameCharacter occ = veh.getCharacter(i);
                if (occ != null && !occ.isZombie()) {
                    return occ;
                }
            }
        }
        return null;
    }

    /**
     * 为非玩家槽位 {@link IsoNPC} 提供与原版 {@code IsoZombie.isTargetVisible()} 等价的视线判定，
     * 避免原版单人分支调用 {@code getCurrentSquare().isCouldSee(player.getIndex())} 误读 Player 0 视野导致离屏断咬。
     */
    public static boolean isZombieTargetNpcVisible(IsoZombie zombie, IsoNPC npc) {
        if (zombie == null || npc == null || npc.isDead()) {
            return false;
        }
        IsoGridSquare zSq = zombie.getCurrentSquare();
        IsoGridSquare npcSq = npc.getCurrentSquare();
        if (zSq == null || npcSq == null) {
            return false;
        }
        if (zSq == npcSq) {
            return true;
        }
        if (Math.abs(zSq.getZ() - npcSq.getZ()) > 2) {
            return false;
        }
        try {
            if (zombie.getCell() == null) {
                return false;
            }
            LosUtil.TestResults los = LosUtil.lineClear(
                    zombie.getCell(),
                    zSq.getX(), zSq.getY(), zSq.getZ(),
                    npcSq.getX(), npcSq.getY(), npcSq.getZ(),
                    false);
            return los != LosUtil.TestResults.Blocked && los != LosUtil.TestResults.ClearThroughClosedDoor;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 隔离非本地槽位 {@link IsoNPC} 在 {@code IsoPlayer.updateCursorVisibility()} 隐藏真玩家鼠标光标的调用。 */
    public static boolean shouldSkipCursorVisibility(IsoPlayer self) {
        return self instanceof IsoNPC;
    }

    public static IsoPlayer swapSeparatingPlayer(IsoPlayer next) {
        IsoPlayer prev = SEPARATING_PLAYER.get();
        if (next == null) {
            SEPARATING_PLAYER.remove();
        } else {
            SEPARATING_PLAYER.set(next);
        }
        return prev;
    }

    public static IsoMovingObject swapSeparateCandidate(IsoMovingObject next) {
        IsoMovingObject prev = SEPARATE_OTHER.get();
        if (next == null) {
            SEPARATE_OTHER.remove();
        } else {
            SEPARATE_OTHER.set(next);
        }
        return prev;
    }

    public static void restoreSeparateContext(IsoPlayer prevPlayer, IsoMovingObject prevOther) {
        if (prevPlayer == null) {
            SEPARATING_PLAYER.remove();
        } else {
            SEPARATING_PLAYER.set(prevPlayer);
        }
        if (prevOther == null) {
            SEPARATE_OTHER.remove();
        } else {
            SEPARATE_OTHER.set(prevOther);
        }
    }

    public static void recordSeparateCandidate(IsoMovingObject candidate) {
        IsoPlayer separating = SEPARATING_PLAYER.get();
        if (separating != null && candidate != null && candidate != separating) {
            SEPARATE_OTHER.set(candidate);
        }
    }

    public static boolean isAlreadyBumpedStateName(String stateName) {
        return "bumped".equalsIgnoreCase(stateName) || "bumped-bump".equalsIgnoreCase(stateName);
    }

    public static boolean shouldSuppressSeparateBumpFlags(
            boolean selfIsHumanPlayer,
            boolean selfIsNpc,
            boolean otherIsHumanPlayer,
            boolean otherIsNpc,
            String selfActionStateName) {
        boolean friendlyNpcPair = selfIsHumanPlayer && otherIsHumanPlayer && (selfIsNpc || otherIsNpc);
        boolean alreadyBumpedNpc = selfIsNpc && isAlreadyBumpedStateName(selfActionStateName);
        return friendlyNpcPair || alreadyBumpedNpc;
    }

    /**
     * 仅在 {@code IsoMovingObject.separate()} 作用域内抑制友方（玩家↔NPC、NPC↔NPC）绊倒及 NPC 连续重撞刷新，
     * 保留 {@code isSolidForSeparate() == true} 与后续物理推开（{@code setNextX/Y}）及 {@code collideWith}。
     */
    public static boolean shouldSuppressSeparateBump(IsoPlayer self) {
        if (self == null || SEPARATING_PLAYER.get() != self) {
            return false;
        }
        IsoMovingObject other = SEPARATE_OTHER.get();
        if (other == null || other == self) {
            return false;
        }
        boolean selfIsHumanPlayer = !self.isAnimal() && !self.isZombie();
        boolean selfIsNpc = self instanceof IsoNPC;
        boolean otherIsHumanPlayer = (other instanceof IsoPlayer p) && !p.isAnimal() && !p.isZombie();
        boolean otherIsNpc = other instanceof IsoNPC;
        if (selfIsHumanPlayer && otherIsHumanPlayer && (selfIsNpc || otherIsNpc)) {
            return true;
        }
        if (selfIsNpc) {
            ActionContext ctx = self.getActionContext();
            String stateName = (ctx != null) ? ctx.getCurrentStateName() : null;
            return shouldSuppressSeparateBumpFlags(
                    selfIsHumanPlayer, true, otherIsHumanPlayer, otherIsNpc, stateName);
        }
        return false;
    }
}
