package emberroutes.npc.combat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FiringCorridorGateTest {
    @Test
    void openSpaceCorridorAppliesSmoothLateralRepulsion() {
        EmberSquadDirector dir = new EmberSquadDirector();
        long now = 2_000L;
        dir.registerCorridor("shooter_1", 0.0F, 5.0F, 10.0F, 5.0F, 0, now);

        EmberSquadDirector.CorridorAvoidanceResult out = new EmberSquadDirector.CorridorAvoidanceResult();
        EmberSquadDirector.PassabilityProbe openSpace = (x1, y1, x2, y2, z) -> true;

        // 近战在射线中段正下方 0.2 格（处于 0.65f 走廊半宽内，两侧 0.8 格开阔）
        dir.evaluateCorridorAvoidance(4.0F, 4.8F, 0, "melee_1", 5.0F, openSpace, out);
        assertTrue(out.inCorridor);
        assertFalse(out.narrowHallwayConfined);
        assertEquals(0.0F, out.pushX, 0.05F);
        assertTrue(out.pushY < -0.3F, "Expected negative-Y lateral push away from line y=5, got " + out.pushY);
    }

    @Test
    void narrowHallwayLateralClearanceGateSuppressesLateralPushAndAppliesDistanceRule() {
        EmberSquadDirector dir = new EmberSquadDirector();
        long now = 3_000L;
        // 枪手沿着东西向 y=5 走廊瞄准（上下 y=4.2 与 y=5.8 均被走廊墙壁封死）
        dir.registerCorridor("shooter_1", 0.0F, 5.0F, 8.0F, 5.0F, 0, now);

        EmberSquadDirector.PassabilityProbe narrowHallway =
                (x1, y1, x2, y2, z) -> (y2 >= 4.4F && y2 <= 5.6F);

        EmberSquadDirector.CorridorAvoidanceResult resFar = new EmberSquadDirector.CorridorAvoidanceResult();
        // 目标距离 >= 2.2 格：禁止横向挤墙，改为近战沿走廊后退 0.5 格让出射界
        dir.evaluateCorridorAvoidance(3.0F, 5.05F, 0, "melee_1", 6.0F, narrowHallway, resFar);
        assertTrue(resFar.inCorridor);
        assertTrue(resFar.narrowHallwayConfined);
        assertTrue(resFar.yieldBackAlongLine);
        assertFalse(resFar.meleePressAhead);
        assertEquals(-0.5F, resFar.pushX, 0.01F);
        assertEquals(0.0F, resFar.pushY, 0.01F);

        EmberSquadDirector.CorridorAvoidanceResult resClose = new EmberSquadDirector.CorridorAvoidanceResult();
        // 目标距离 < 2.2 格：近战顶上接管（横向斥力归零），射线由射手停火让行
        dir.evaluateCorridorAvoidance(1.5F, 5.05F, 0, "melee_1", 1.8F, narrowHallway, resClose);
        assertTrue(resClose.inCorridor);
        assertTrue(resClose.narrowHallwayConfined);
        assertTrue(resClose.meleePressAhead);
        assertFalse(resClose.yieldBackAlongLine);
        assertEquals(0.0F, resClose.pushX, 0.01F);
        assertEquals(0.0F, resClose.pushY, 0.01F);
    }

    @Test
    void shooterOutsideSegmentOrBehindCorridorDoesNotTriggerAvoidance() {
        EmberSquadDirector dir = new EmberSquadDirector();
        long now = 4_000L;
        dir.registerCorridor("shooter_1", 5.0F, 5.0F, 12.0F, 5.0F, 0, now);

        EmberSquadDirector.CorridorAvoidanceResult out = new EmberSquadDirector.CorridorAvoidanceResult();
        dir.evaluateCorridorAvoidance(2.0F, 5.0F, 0, "melee_1", 4.0F, (a, b, c, d, z) -> true, out);
        assertFalse(out.inCorridor);

        // 自身注册的走廊对自己无斥力
        dir.evaluateCorridorAvoidance(8.0F, 5.0F, 0, "shooter_1", 4.0F, (a, b, c, d, z) -> true, out);
        assertFalse(out.inCorridor);

        // 窄走廊近距接管时，射手前方 0.65f 走廊内的友军应触发停火，远距或旁路不触发
        assertTrue(EmberSquadDirector.shouldShooterHoldInNarrowCorridor(
                0.0F, 5.0F, 1.0F, 0.0F, 1.8F, new float[]{1.2F}, new float[]{5.1F}, 1));
        assertFalse(EmberSquadDirector.shouldShooterHoldInNarrowCorridor(
                0.0F, 5.0F, 1.0F, 0.0F, 3.5F, new float[]{1.2F}, new float[]{5.1F}, 1));
        assertFalse(EmberSquadDirector.shouldShooterHoldInNarrowCorridor(
                0.0F, 5.0F, 1.0F, 0.0F, 1.8F, null, null, 0));
    }
}
