package emberroutes.npc.combat;

import emberroutes.npc.IsoNPC;
import emberroutes.npc.NpcNullInputComponent;
import zombie.characters.IsoZombie;
import zombie.characters.component.AIComponent;
import zombie.core.Core;
import zombie.inventory.types.HandWeapon;

/**
 * 唯一权威近战/推击/地面处决攻击下发器（1:1 对齐原版 {@code SwipeStatePlayer} 跨帧裁决）：
 *
 * <ol>
 *   <li>严禁在 {@code pressedAttack()} 同帧立即清空 {@code manualFloorAtk / meleeButton} 意图，
 *       改为武装 0.30s TTL 由下一帧 {@code SwipeStatePlayer.enter()} 出口消费（修齐 C6）；</li>
 *   <li>严禁外部直写朝向或注入原版不存在的 {@code EventAttack} / 魔法 {@code useChargeDelta=16}，
 *       防御推搡朝向统一经 {@code EmberLocomotionController.setCombatAimStrafe} 下发。</li>
 * </ol>
 */
public final class MeleeAttackExecutor {
    private MeleeAttackExecutor() {}

    public static void commit(
            IsoNPC npc,
            HandWeapon weapon,
            MeleeWeaponMetrics metrics,
            EmberSquadDirector.ThreatEntry target,
            IsoZombie liveZ,
            int liveMask,
            float dist,
            int frontStandingCount,
            double nowSim,
            long nowMs) {
        if (npc == null || npc.isDead() || liveZ == null || liveZ.isDead() || !liveZ.isAlive()
                || B42CombatRuntime.classifyTarget(liveZ) == 0
                || npc.isCombatAttackBlocked(nowSim)) {
            return;
        }

        boolean allowStompOrShove = CombatTargetState.allowsStompOrShove(liveMask);
        boolean unarmed = (weapon == null || weapon.isRanged() || (metrics != null && metrics.unarmed()));
        float minRange = metrics != null ? metrics.minRange() : 0.35F;
        float maxRange = metrics != null ? metrics.maxRange() : 1.10F;
        boolean manualMultiShove = !unarmed && allowStompOrShove
                && metrics != null && metrics.shouldManualMultiShove(dist, frontStandingCount);
        boolean manualFloorIntent = !Core.getInstance().isOptionAutoProneAtk()
                && allowStompOrShove
                && liveZ.isProne()
                && frontStandingCount == 0;

        npc.setAttackStarted(false);
        npc.setInitiateAttack(false);
        npc.setDoShove(manualMultiShove);
        B42CombatRuntime.assignAttackTargetSquare(npc, target);

        NpcNullInputComponent in = npc.getNullInputComponent();
        if (in != null) {
            in.armCombatIntents(manualMultiShove, manualFloorIntent, nowSim, 0.30);
        }

        npc.clearVariable("PlayedSwingSound");
        npc.clearVariable("PlayerVoiceSound");
        npc.pressedAttack();
        if (!npc.isInitiateAttack() && !npc.isAttackStarted()) {
            if (in != null) {
                in.consumeOnSwipeEnter();
            }
            return;
        }

        AIComponent ai = npc.tryGetECSComponent(AIComponent.class);
        if (ai != null && ai.getHumanControlVars() != null) {
            ai.getHumanControlVars().melee = false;
        }

        boolean actualAimFloor = npc.isAimAtFloor();
        boolean actualShove = npc.isDoShove();
        boolean actualStomp = npc.isDoStomp();
        String kind = actualStomp ? "STOMP"
                : actualAimFloor ? "GROUND_WEAPON"
                : actualShove ? (manualMultiShove ? "MULTI_SHOVE" : "AUTO_SHOVE")
                : "MELEE_SWING";

        CombatDiagLog.logCommit(
                npc.getNpcId(),
                kind,
                target != null ? target.zombieId : -1,
                liveMask,
                dist,
                minRange,
                maxRange,
                actualAimFloor,
                actualShove,
                actualStomp);
    }

    /** 单次近距防身推搡（停火自卫或守卫突围专用，朝向经 Locomotion 单点控制器下发）。 */
    public static void commitDefensiveShove(IsoNPC npc, EmberSquadDirector.ThreatEntry threat, double nowSim) {
        if (npc == null || threat == null || npc.isDead() || npc.isCombatAttackBlocked(nowSim)) {
            return;
        }

        IsoZombie liveZ = B42CombatRuntime.getLiveZombie(threat);
        if (liveZ == null || liveZ.isDead() || !liveZ.isAlive() || B42CombatRuntime.classifyTarget(liveZ) == 0) {
            return;
        }
        float dx = liveZ.getX() - npc.getX();
        float dy = liveZ.getY() - npc.getY();
        float d = (float) Math.hypot(dx, dy);
        if (d > 1.0E-4F) {
            npc.getLocomotion().setCombatAimStrafe(
                    npc, 0.0F, 0.0F, false, true, true, dx / d, dy / d, false, -1.0F);
        }

        int liveMask = B42CombatRuntime.classifyTarget(liveZ);
        boolean manualFloor = !Core.getInstance().isOptionAutoProneAtk()
                && liveZ.isProne()
                && d < MeleeWeaponMetrics.VANILLA_MAX_STOMP_DIST
                && CombatTargetState.allowsStompOrShove(liveMask);

        npc.setAttackStarted(false);
        npc.setInitiateAttack(false);
        npc.setDoShove(true);
        B42CombatRuntime.assignAttackTargetSquare(npc, threat);

        NpcNullInputComponent in = npc.getNullInputComponent();
        if (in != null) {
            in.armCombatIntents(true, manualFloor, nowSim, 0.30);
        }
        npc.clearVariable("PlayedSwingSound");
        npc.clearVariable("PlayerVoiceSound");
        npc.pressedAttack();
        if (!npc.isInitiateAttack() && !npc.isAttackStarted()) {
            if (in != null) {
                in.consumeOnSwipeEnter();
            }
            return;
        }
        AIComponent ai = npc.tryGetECSComponent(AIComponent.class);
        if (ai != null && ai.getHumanControlVars() != null) {
            ai.getHumanControlVars().melee = false;
        }

        CombatDiagLog.logCommit(
                npc.getNpcId(),
                npc.isDoStomp() ? "DEF_STOMP" : "DEF_SHOVE",
                threat.zombieId,
                liveMask,
                d,
                0.0F,
                MeleeWeaponMetrics.VANILLA_BARE_HANDS_MAX_RANGE,
                npc.isAimAtFloor(),
                true,
                npc.isDoStomp());
    }
}
