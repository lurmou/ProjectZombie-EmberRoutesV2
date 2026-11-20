package emberroutes.npc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmberFollowMathTest {

    @Test
    void endClampDisabledByDefaultAndResetRestoresIt() {
        EmberParams.resetDefaults();
        assertFalse(EmberParams.END_CLAMP_ENABLED);
        assertEquals(0.0F, EmberParams.get("EMBER_END_CLAMP"));

        assertTrue(EmberParams.set("EMBER_END_CLAMP", 1.0));
        assertTrue(EmberParams.END_CLAMP_ENABLED);
        assertEquals(1.0F, EmberParams.get("EMBER_END_CLAMP"));

        EmberParams.resetDefaults();
        assertFalse(EmberParams.END_CLAMP_ENABLED);
        assertEquals(0.0F, EmberParams.get("EMBER_END_CLAMP"));
    }

    @Test
    void leaderTrailRecordsSpacingInterpolatesAndResetsOnFloorOrJump() {
        EmberLeaderTrail trail = new EmberLeaderTrail();
        float[] sample = new float[4];

        assertTrue(trail.isEmpty());
        assertFalse(trail.sample(0.0F, sample));

        trail.record(10.0F, 20.0F, 0);
        trail.record(10.1F, 20.0F, 0); // < 0.25 tile spacing: ignored
        assertEquals(1, trail.getCount());

        trail.record(11.0F, 20.0F, 0);
        trail.record(12.0F, 20.0F, 0);
        assertEquals(3, trail.getCount());

        assertTrue(trail.sample(0.5F, sample));
        assertEquals(11.5F, sample[0], 0.02F);
        assertEquals(20.0F, sample[1], 0.02F);
        assertEquals(1.0F, sample[2], 0.02F);
        assertEquals(0.0F, sample[3], 0.02F);

        assertFalse(trail.sample(3.0F, sample)); // shorter than recorded trail

        // Floor change breaks trail cleanly
        trail.record(12.0F, 20.0F, 1);
        assertEquals(1, trail.getCount());
        assertEquals(1, trail.getFloor());

        // Teleport/vehicle jump > 2.5 tiles breaks trail cleanly
        trail.record(13.0F, 20.0F, 1);
        assertEquals(2, trail.getCount());
        trail.record(17.0F, 20.0F, 1);
        assertEquals(1, trail.getCount());
    }

    @Test
    void leaderTrailWrapsCircularBufferAndHandlesLeaderRespawnIdentity() {
        EmberLeaderTrail.resetAll();
        Object firstLeader = new Object();
        Object respawnedLeader = new Object();

        EmberLeaderTrail t1 = EmberLeaderTrail.forLeader(0, firstLeader);
        for (int i = 0; i < 120; i++) {
            t1.record(i * 0.5F, 5.0F, 0);
        }
        assertEquals(EmberLeaderTrail.CAPACITY, t1.getCount());

        float[] out = new float[4];
        assertTrue(t1.sample(2.0F, out));
        assertEquals(119 * 0.5F - 2.0F, out[0], 0.05F);
        assertEquals(5.0F, out[1], 0.05F);

        EmberLeaderTrail t2 = EmberLeaderTrail.forLeader(0, respawnedLeader);
        assertSame(t1, t2);
        assertTrue(t2.isEmpty());
        EmberLeaderTrail.resetAll();
    }

    @Test
    void aimSlowWalkConvergesWithoutIdleDrop() {
        EmberFollowGaitControl ctrl = new EmberFollowGaitControl();
        float dt = 1.0F / 60.0F;
        float leaderPos = 1.60F;
        float npcPos = 0.0F;
        long nowMs = 1_000L;

        // Realistic transition: 30 frames steady B42 walk (~1.95) -> 180 frames aiming slow walk (1.10)
        float minSignedErr = Float.POSITIVE_INFINITY;
        for (int i = 0; i < 210; i++) {
            float leaderSpeed = (i < 30) ? 1.95F : 1.10F;
            float cap = ctrl.getWalkSpeedCap(true);
            float scale = ctrl.getTrackSpeedScale(true);
            float npcBase = (cap < 0.0F) ? 2.00F : (1.45F + 0.55F * cap);
            float npcSpeed = npcBase * scale;

            float leaderStep = leaderSpeed * dt;
            float npcStep = npcSpeed * dt;
            leaderPos += leaderStep;
            npcPos += npcStep;

            ctrl.sampleSpeeds(leaderStep, npcStep, dt);
            nowMs = 1_000L + Math.round((i + 1) * (1000.0 / 60.0));
            float distToLeader = leaderPos - npcPos;
            float signedErr = (leaderPos - 1.60F) - npcPos;
            if (i >= 30) {
                minSignedErr = Math.min(minSignedErr, signedErr);
            }
            float vCmd = ctrl.computeCommandSpeed(signedErr);
            EmberFollowGaitControl.Gear gear = ctrl.updateGear(false, distToLeader, vCmd, nowMs);
            assertEquals(EmberFollowGaitControl.Gear.WALK, gear, "Aiming slow walk must keep NPC in WALK gear");
            ctrl.updateSpeedDrive(true, true, vCmd, dt);
        }

        assertTrue(minSignedErr >= -0.50F, "Overshoot must stay >= -0.50 to avoid creep Idle drop, got " + minSignedErr);
        assertEquals(0.0F, ctrl.getWalkSpeedCap(true), 1e-4F, "WalkSpeed cap must reach Bob_WalkSlow floor 0.0");
        float finalScale = ctrl.getTrackSpeedScale(true);
        assertTrue(finalScale >= 0.65F && finalScale <= 0.85F, "Track scale should settle near 0.76, got " + finalScale);
        float finalSpeed = 1.45F * finalScale;
        assertEquals(1.10F, finalSpeed, 0.12F, "NPC speed should match aiming walk ~1.10 tiles/sec");
    }

    @Test
    void rapidShiftSpamCausesZeroWalkRunFlapping() {
        EmberFollowGaitControl ctrl = new EmberFollowGaitControl();
        float dt = 1.0F / 60.0F;
        long nowMs = 5_000L;
        int transitions = 0;
        EmberFollowGaitControl.Gear prev = ctrl.getCurrentGear();

        // Phase A: B42 walk while rapidly tapping Shift (alternating 1.95 / 3.15 tiles/sec every 80ms)
        for (int i = 0; i < 240; i++) {
            long elapsedMs = Math.round(i * (1000.0 / 60.0));
            nowMs = 5_000L + elapsedMs;
            boolean pulseHigh = ((elapsedMs / 80L) % 2L) == 1L;
            float leaderSpeed = pulseHigh ? 3.15F : 1.95F;
            ctrl.sampleSpeeds(leaderSpeed * dt, 1.95F * dt, dt);
            float vCmd = ctrl.computeCommandSpeed(0.50F);
            EmberFollowGaitControl.Gear g = ctrl.updateGear(false, 2.10F, vCmd, nowMs);
            if (g != prev) {
                transitions++;
                prev = g;
            }
        }
        assertEquals(0, transitions, "Rapid Shift tapping near walk/run edge must trigger 0 gait changes");
        assertEquals(EmberFollowGaitControl.Gear.WALK, prev);

        // Phase B: sustained B42 run (~3.60 tiles/sec) with brief momentary 60ms dips every 350ms
        int phaseBTransitions = 0;
        for (int i = 0; i < 180; i++) {
            long elapsedMs = Math.round(i * (1000.0 / 60.0));
            nowMs = 9_000L + elapsedMs;
            boolean briefDip = (elapsedMs % 350L) < 60L;
            float leaderSpeed = briefDip ? 1.95F : 3.60F;
            ctrl.sampleSpeeds(leaderSpeed * dt, 2.00F * dt, dt);
            float dist = Math.min(3.40F, 2.30F + i * 0.02F);
            float vCmd = ctrl.computeCommandSpeed(1.50F);
            EmberFollowGaitControl.Gear g = ctrl.updateGear(false, dist, vCmd, nowMs);
            if (g != prev) {
                phaseBTransitions++;
                prev = g;
            }
        }
        assertEquals(1, phaseBTransitions, "Sustained run must perform exactly 1 clean upshift without flapping");
        assertEquals(EmberFollowGaitControl.Gear.RUN, prev);
    }

    @Test
    void gearboxHysteresisAndMinDwellLock() {
        EmberFollowGaitControl ctrl = new EmberFollowGaitControl();
        float dt = 1.0F / 60.0F;
        long nowMs = 1_000L;

        // Warm leader EMA to B42 run speed (~3.60 tiles/sec)
        for (int i = 0; i < 60; i++) {
            ctrl.sampleSpeeds(3.60F * dt, 1.95F * dt, dt);
        }
        // Starts confirm window at 1000ms
        assertEquals(EmberFollowGaitControl.Gear.WALK, ctrl.updateGear(false, 2.50F, 3.80F, nowMs));
        // Before 220ms confirm expires -> stays WALK
        assertEquals(EmberFollowGaitControl.Gear.WALK, ctrl.updateGear(false, 2.50F, 3.80F, nowMs + 150L));
        // After 220ms -> shifts cleanly to RUN
        assertEquals(EmberFollowGaitControl.Gear.RUN, ctrl.updateGear(false, 2.50F, 3.80F, nowMs + 250L));

        long runEnteredAt = nowMs + 250L;
        // Drop leader EMA abruptly to 0.0 and try to downshift immediately inside 500ms dwell lock
        ctrl.resetLeaderSpeed();
        assertEquals(EmberFollowGaitControl.Gear.RUN, ctrl.updateGear(false, 1.50F, 0.50F, runEnteredAt + 300L));
        // Still blocked even at 490ms (< 500ms dwell)
        assertEquals(EmberFollowGaitControl.Gear.RUN, ctrl.updateGear(false, 1.50F, 0.50F, runEnteredAt + 490L));
        // Past both 500ms dwell & 280ms confirm -> shifts down cleanly to WALK
        assertEquals(EmberFollowGaitControl.Gear.WALK, ctrl.updateGear(false, 1.50F, 0.50F, runEnteredAt + 600L));

        // Re-upshift into RUN, then test SPRINT hysteresis & dwell (B42 sprint ~5.20 tiles/sec)
        for (int i = 0; i < 90; i++) {
            ctrl.sampleSpeeds(5.20F * dt, 3.60F * dt, dt);
        }
        long walkEnteredAt = runEnteredAt + 600L;
        ctrl.updateGear(false, 4.00F, 5.80F, walkEnteredAt + 600L);
        ctrl.updateGear(false, 4.00F, 5.80F, walkEnteredAt + 900L);
        assertEquals(EmberFollowGaitControl.Gear.RUN, ctrl.getCurrentGear());

        long secondRunAt = walkEnteredAt + 900L;
        // Start sprint confirm + dwell
        ctrl.updateGear(false, 4.20F, 6.00F, secondRunAt + 350L);
        assertEquals(EmberFollowGaitControl.Gear.RUN, ctrl.updateGear(false, 4.20F, 6.00F, secondRunAt + 480L));
        assertEquals(EmberFollowGaitControl.Gear.SPRINT, ctrl.updateGear(false, 4.20F, 6.00F, secondRunAt + 700L));

        // Mid-deadband dip (leader 4.40, dist 3.2) keeps SPRINT without flapping back to RUN
        long sprintAt = secondRunAt + 700L;
        ctrl.resetLeaderSpeed();
        for (int i = 0; i < 60; i++) {
            ctrl.sampleSpeeds(4.40F * dt, 4.40F * dt, dt);
        }
        assertEquals(EmberFollowGaitControl.Gear.SPRINT, ctrl.updateGear(false, 3.20F, 4.40F, sprintAt + 1_000L));

        // Closing dist <= 2.40F for >= 260ms cleanly drops SPRINT -> RUN
        ctrl.updateGear(false, 2.20F, 4.40F, sprintAt + 1_100L);
        assertEquals(EmberFollowGaitControl.Gear.RUN, ctrl.updateGear(false, 2.20F, 4.40F, sprintAt + 1_400L));
    }

    @Test
    void sneakDebounceFiltersRapidTapping() {
        EmberFollowGaitControl ctrl = new EmberFollowGaitControl();
        // Rapid tap < 220ms never latches sneak
        assertFalse(ctrl.updateSneakIntent(true, 100L));
        assertFalse(ctrl.updateSneakIntent(true, 250L));
        assertFalse(ctrl.updateSneakIntent(false, 280L));
        assertFalse(ctrl.updateSneakIntent(true, 350L));
        assertFalse(ctrl.isSneakLatched());

        // Holding >= 220ms latches sneak cleanly
        assertFalse(ctrl.updateSneakIntent(true, 500L));
        assertTrue(ctrl.updateSneakIntent(true, 600L));
        assertTrue(ctrl.isSneakLatched());

        // Brief 100ms release does not unlatch; sustained >= 220ms release unlatches
        assertTrue(ctrl.updateSneakIntent(false, 700L));
        assertTrue(ctrl.updateSneakIntent(false, 850L));
        assertFalse(ctrl.updateSneakIntent(false, 930L));
        assertFalse(ctrl.isSneakLatched());
    }

    @Test
    void upshiftLiftsSpeedDriveAndSampleClampsSpikes() {
        EmberFollowGaitControl ctrl = new EmberFollowGaitControl();

        // Abnormal single-tick spike (0.40 / 0.016 = 25 tiles/s) is clamped to <= 7.5 tiles/s
        ctrl.sampleSpeeds(0.40F, 0.30F, 0.016F);
        assertEquals(7.50F, ctrl.getLeaderSpeedEma(), 1e-4F);
        assertEquals(7.50F, ctrl.getNpcSpeedEma(), 1e-4F);

        // Drive speedDrive down into slow-walk sub-1.0 regime
        ctrl.reset();
        float dt = 1.0F / 60.0F;
        for (int i = 0; i < 120; i++) {
            ctrl.sampleSpeeds(1.00F * dt, 1.50F * dt, dt);
            ctrl.updateSpeedDrive(true, true, 0.80F, dt);
        }
        assertTrue(ctrl.getSpeedDrive() <= 0.85F, "Expected slow-walk speedDrive <= 0.85, got " + ctrl.getSpeedDrive());

        // Leader sprints/runs away -> upshifting WALK -> RUN must immediately lift speedDrive >= 1.50F
        ctrl.sampleSpeeds(3.60F * dt, 1.00F * dt, dt);
        for (int i = 0; i < 40; i++) {
            ctrl.sampleSpeeds(3.60F * dt, 1.20F * dt, dt);
        }
        assertEquals(EmberFollowGaitControl.Gear.WALK, ctrl.updateGear(false, 3.00F, 3.80F, 2_000L));
        assertEquals(EmberFollowGaitControl.Gear.RUN, ctrl.updateGear(false, 3.00F, 3.80F, 2_300L));
        assertTrue(ctrl.getSpeedDrive() >= 1.50F, "Upshift into RUN must lift speedDrive >= 1.50, got " + ctrl.getSpeedDrive());
        assertTrue(ctrl.getWalkSpeedCap(true) >= 0.50F, "Upshift into RUN must set WalkSpeedCap >= 0.50");
    }
}
