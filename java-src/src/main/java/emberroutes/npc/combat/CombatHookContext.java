package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoMovingObject;
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

    private CombatHookContext() {}

    public static void resetAll() {
        ACTIVE_BALLISTICS_NPC.remove();
        NPC_COMBAT_DEPTH.remove();
        NPC_UPDATE_DEPTH.remove();
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
}
