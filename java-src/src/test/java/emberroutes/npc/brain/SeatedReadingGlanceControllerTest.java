package emberroutes.npc.brain;

import emberroutes.npc.EmberPostureMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SeatedReadingGlanceControllerTest {

    @Test
    void glanceEnvelopeFollowsRaiseHoldReturnPhases() {
        assertEquals(0.0F, EmberPostureMath.computeGlanceEnvelope(0.0), 1.0E-5F);

        // 抬眼半途（0.30s = RAISE_DURATION_SEC / 2）smoothstep 应为 0.5
        float midRaise = EmberPostureMath.computeGlanceEnvelope(0.30);
        assertEquals(0.5F, midRaise, 1.0E-3F);

        // 停半拍区间（0.60s ~ 0.85s）恒为 1.0
        assertEquals(1.0F, EmberPostureMath.computeGlanceEnvelope(0.60), 1.0E-5F);
        assertEquals(1.0F, EmberPostureMath.computeGlanceEnvelope(0.75), 1.0E-5F);
        assertEquals(1.0F, EmberPostureMath.computeGlanceEnvelope(0.85), 1.0E-5F);

        // 收回半途（0.85 + 0.35 = 1.20s）应回到 0.5
        float midReturn = EmberPostureMath.computeGlanceEnvelope(1.20);
        assertEquals(0.5F, midReturn, 1.0E-3F);

        // 结束归零（>= 1.55s）
        assertEquals(0.0F, EmberPostureMath.computeGlanceEnvelope(1.55), 1.0E-5F);
        assertEquals(0.0F, EmberPostureMath.computeGlanceEnvelope(2.00), 1.0E-5F);
    }

    @Test
    void frontConeRejectsBehindDifferentFloorAndOutOfRange() {
        // NPC 朝南 (0, 1)，位于 (10, 10, 0)
        // 1. 背后靠近 (10, 8, 0) -> NaN
        assertTrue(Float.isNaN(EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                10.0F, 10.0F, 0, 0.0F, 1.0F,
                10.0F, 8.0F, 0,
                EmberPostureMath.GLANCE_TRIGGER_MIN_DIST,
                EmberPostureMath.GLANCE_TRIGGER_MAX_DIST,
                EmberPostureMath.GLANCE_TRIGGER_MIN_DOT)));

        // 2. 不同楼层 (10, 12, 1) -> NaN
        assertTrue(Float.isNaN(EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                10.0F, 10.0F, 0, 0.0F, 1.0F,
                10.0F, 12.0F, 1,
                EmberPostureMath.GLANCE_TRIGGER_MIN_DIST,
                EmberPostureMath.GLANCE_TRIGGER_MAX_DIST,
                EmberPostureMath.GLANCE_TRIGGER_MIN_DOT)));

        // 3. 超出距离 (10, 16, 0) -> NaN
        assertTrue(Float.isNaN(EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                10.0F, 10.0F, 0, 0.0F, 1.0F,
                10.0F, 16.0F, 0,
                EmberPostureMath.GLANCE_TRIGGER_MIN_DIST,
                EmberPostureMath.GLANCE_TRIGGER_MAX_DIST,
                EmberPostureMath.GLANCE_TRIGGER_MIN_DOT)));
    }

    @Test
    void frontConeDistinguishesLeftAndRightSidesFromSeatFacing() {
        // NPC 朝南 (0, 1)，在等轴测坐标系中：
        // 角色左手侧是东方 (+X = 1, 0)，右手侧是西方 (-X = -1, 0)
        float leftAngle = EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                10.0F, 10.0F, 0, 0.0F, 1.0F,
                12.0F, 12.0F, 0,
                EmberPostureMath.GLANCE_TRIGGER_MIN_DIST,
                EmberPostureMath.GLANCE_TRIGGER_MAX_DIST,
                EmberPostureMath.GLANCE_TRIGGER_MIN_DOT);
        assertFalse(Float.isNaN(leftAngle));
        assertTrue(leftAngle < 0.0F, "Player on NPC's left should produce negative angle (Bob_LookLeft)");
        assertTrue(EmberPostureMath.computeGlanceNormalizedHorizontal(leftAngle, 0.348F) < 0.0F);

        float rightAngle = EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                10.0F, 10.0F, 0, 0.0F, 1.0F,
                8.0F, 12.0F, 0,
                EmberPostureMath.GLANCE_TRIGGER_MIN_DIST,
                EmberPostureMath.GLANCE_TRIGGER_MAX_DIST,
                EmberPostureMath.GLANCE_TRIGGER_MIN_DOT);
        assertFalse(Float.isNaN(rightAngle));
        assertTrue(rightAngle > 0.0F, "Player on NPC's right should produce positive angle (Bob_LookRight)");
        assertTrue(EmberPostureMath.computeGlanceNormalizedHorizontal(rightAngle, 0.348F) > 0.0F);
    }
}
