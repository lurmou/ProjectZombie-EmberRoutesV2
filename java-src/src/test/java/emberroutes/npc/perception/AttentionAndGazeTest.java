package emberroutes.npc.perception;

import emberroutes.npc.EmberGazeController;
import emberroutes.npc.EmberPostureMath;
import emberroutes.npc.PostureGazeProfile;
import emberroutes.npc.PostureGazeProfile.GazeChannel;
import emberroutes.npc.PostureGazeProfile.GazeStyle;
import emberroutes.npc.perception.AttentionSelector.AttentionReason;
import emberroutes.npc.perception.AttentionSelector.AttentionTarget;
import emberroutes.npc.perception.StimulusSlot.StimulusKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttentionAndGazeTest {

    @Test
    void seatedReadingProfileEnvelopeMatchesOriginalThreePhaseTiming() {
        PostureGazeProfile p = PostureGazeProfile.SEATED_READING;
        assertEquals(GazeChannel.HEAD_LAYER, p.channel);
        assertEquals(GazeStyle.BRIEF_RAISE, p.style);
        assertTrue(p.hasBoneMaskRelease());
        assertEquals("Read", p.releaseTrackKeyword);

        assertEquals(0.0F, AttentionSelector.computeThreePhaseEnvelope(0.0, p.raiseSec, p.holdSec, p.returnSec), 1.0E-5F);
        assertEquals(0.5F, AttentionSelector.computeThreePhaseEnvelope(0.30, p.raiseSec, p.holdSec, p.returnSec), 1.0E-3F);
        assertEquals(1.0F, AttentionSelector.computeThreePhaseEnvelope(0.60, p.raiseSec, p.holdSec, p.returnSec), 1.0E-5F);
        assertEquals(1.0F, AttentionSelector.computeThreePhaseEnvelope(0.75, p.raiseSec, p.holdSec, p.returnSec), 1.0E-5F);
        assertEquals(1.0F, AttentionSelector.computeThreePhaseEnvelope(0.85, p.raiseSec, p.holdSec, p.returnSec), 1.0E-5F);
        assertEquals(0.5F, AttentionSelector.computeThreePhaseEnvelope(1.20, p.raiseSec, p.holdSec, p.returnSec), 1.0E-3F);
        assertEquals(0.0F, AttentionSelector.computeThreePhaseEnvelope(1.55, p.raiseSec, p.holdSec, p.returnSec), 1.0E-5F);
        assertEquals(0.0F, AttentionSelector.computeThreePhaseEnvelope(2.00, p.raiseSec, p.holdSec, p.returnSec), 1.0E-5F);
    }

    @Test
    void frontConeRejectsBehindDifferentFloorAndOutOfRange() {
        assertTrue(Float.isNaN(EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                10.0F, 10.0F, 0, 0.0F, 1.0F,
                10.0F, 8.0F, 0,
                NpcPerception.TRIGGER_MIN_DIST, NpcPerception.TRIGGER_MAX_DIST, NpcPerception.TRIGGER_MIN_DOT)));

        assertTrue(Float.isNaN(EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                10.0F, 10.0F, 0, 0.0F, 1.0F,
                10.0F, 12.0F, 1,
                NpcPerception.TRIGGER_MIN_DIST, NpcPerception.TRIGGER_MAX_DIST, NpcPerception.TRIGGER_MIN_DOT)));

        assertTrue(Float.isNaN(EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                10.0F, 10.0F, 0, 0.0F, 1.0F,
                10.0F, 16.0F, 0,
                NpcPerception.TRIGGER_MIN_DIST, NpcPerception.TRIGGER_MAX_DIST, NpcPerception.TRIGGER_MIN_DOT)));
    }

    @Test
    void frontConeDistinguishesLeftAndRightSidesFromSeatFacing() {
        float leftAngle = EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                10.0F, 10.0F, 0, 0.0F, 1.0F,
                12.0F, 12.0F, 0,
                NpcPerception.TRIGGER_MIN_DIST, NpcPerception.TRIGGER_MAX_DIST, NpcPerception.TRIGGER_MIN_DOT);
        assertFalse(Float.isNaN(leftAngle));
        assertTrue(leftAngle < 0.0F);
        assertTrue(EmberPostureMath.computeGlanceNormalizedHorizontal(leftAngle, 0.348F) < 0.0F);

        float rightAngle = EmberPostureMath.computeGlanceSignedAngleIfInFrontCone(
                10.0F, 10.0F, 0, 0.0F, 1.0F,
                8.0F, 12.0F, 0,
                NpcPerception.TRIGGER_MIN_DIST, NpcPerception.TRIGGER_MAX_DIST, NpcPerception.TRIGGER_MIN_DOT);
        assertFalse(Float.isNaN(rightAngle));
        assertTrue(rightAngle > 0.0F);
        assertTrue(EmberPostureMath.computeGlanceNormalizedHorizontal(rightAngle, 0.348F) > 0.0F);
    }

    @Test
    void stimulusMemoryHabituatesOnceAndRearmsAfterContinuousAbsence() {
        StimulusSlot slot = new StimulusSlot(StimulusKind.PLAYER);
        AttentionTarget target = new AttentionTarget();

        // 1. 首次进入视野 -> NEW_ARRIVAL
        AttentionSelector.updateStimulusMemory(
                slot, true, 12.0F, 12.0F, 0.0F, 0, 8.0F, -0.3F, 10.0, 0.12, 4.0);
        AttentionSelector.selectAmbientTarget(slot, true, target);
        assertTrue(target.hasTarget());
        assertEquals(AttentionReason.NEW_ARRIVAL, target.reason);
        assertEquals(10.0, slot.firstSeenSimTime, 1.0E-6);

        // 2. 触发抬眼后标记习惯化 -> BRIEF_RAISE 不再重触发，但 SOFT_TRACK 仍持续追踪
        slot.habituated = true;
        AttentionSelector.selectAmbientTarget(slot, true, target);
        assertFalse(target.hasTarget());
        AttentionSelector.selectAmbientTarget(slot, false, target);
        assertTrue(target.hasTarget());
        assertEquals(AttentionReason.TRACK, target.reason);

        // 3. 短暂离开 2.5s (< 4.0s) 后重新进入 -> 离场计时清零，仍保持习惯化不乱抬眼
        AttentionSelector.updateStimulusMemory(
                slot, false, 0.0F, 0.0F, 0.0F, 0, Float.POSITIVE_INFINITY, Float.NaN, 12.5, 2.5, 4.0);
        assertTrue(slot.habituated);
        AttentionSelector.updateStimulusMemory(
                slot, true, 12.0F, 12.0F, 0.0F, 0, 8.0F, -0.3F, 12.6, 0.1, 4.0);
        assertEquals(0.0, slot.continuousAbsenceSec, 1.0E-6);
        assertTrue(slot.habituated);

        // 4. 连续离开满 4.0s -> 解除习惯化，下次进入重新触发 NEW_ARRIVAL
        AttentionSelector.updateStimulusMemory(
                slot, false, 0.0F, 0.0F, 0.0F, 0, Float.POSITIVE_INFINITY, Float.NaN, 15.0, 2.4, 4.0);
        assertTrue(slot.habituated);
        AttentionSelector.advanceAbsence(slot, 1.7, 4.0);
        assertFalse(slot.habituated);

        AttentionSelector.updateStimulusMemory(
                slot, true, 8.0F, 12.0F, 0.0F, 0, 8.0F, 0.3F, 17.0, 0.12, 4.0);
        AttentionSelector.selectAmbientTarget(slot, true, target);
        assertTrue(target.hasTarget());
        assertEquals(AttentionReason.NEW_ARRIVAL, target.reason);
        assertEquals(0.3F, target.signedAngleRad, 1.0E-5F);
    }

    @Test
    void gazeSuppressorTokenAutoReleasesAndForceResetClearsState() {
        EmberGazeController gaze = new EmberGazeController();
        Object eatToken = new Object();
        assertFalse(gaze.isSuppressed());

        Runnable release = gaze.suppress(eatToken);
        assertTrue(gaze.isSuppressed());
        release.run();
        assertFalse(gaze.isSuppressed());

        gaze.suppress(eatToken);
        assertTrue(gaze.isSuppressed());
        gaze.forceReset(null);
        assertFalse(gaze.isSuppressed());
        assertEquals(PostureGazeProfile.NONE, gaze.getActiveProfile());
    }
}
