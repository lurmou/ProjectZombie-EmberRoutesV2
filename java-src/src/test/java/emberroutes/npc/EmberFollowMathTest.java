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

    @Test
    void multiNpcRearRankVelocityMatchDoesNotFalseRun() {
        assertEquals(2.50F, EmberFollowGaitControl.effectiveDistToLeader(0, 2.50F), 1e-4F);
        assertEquals(8.0F, EmberFollowGaitControl.catchUpEnterDist(0), 1e-4F);

        int rank = 9;
        assertEquals(6.60F, EmberFollowGaitControl.slotBack(rank), 1e-4F);
        assertEquals(11.95F, EmberFollowGaitControl.columnBack(rank), 1e-4F);
        assertTrue(EmberFollowGaitControl.catchUpEnterDist(rank) >= 15.95F);
        assertEquals(1.60F, EmberFollowGaitControl.effectiveDistToLeader(rank, EmberFollowGaitControl.slotBack(rank)), 1e-4F);

        EmberFollowGaitControl ctrl = new EmberFollowGaitControl();
        float dt = 1.0F / 60.0F;
        float leaderPos = EmberFollowGaitControl.slotBack(rank);
        float npcPos = 0.0F;
        long nowMs = 1_000L;

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
            float effectiveDist = EmberFollowGaitControl.effectiveDistToLeader(rank, distToLeader);
            float signedErr = (leaderPos - EmberFollowGaitControl.slotBack(rank)) - npcPos;
            float vCmd = ctrl.computeCommandSpeed(signedErr);
            EmberFollowGaitControl.Gear gear = ctrl.updateGear(false, effectiveDist, vCmd, nowMs);
            assertEquals(EmberFollowGaitControl.Gear.WALK, gear, "Rear rank 9 in slot must not falsely upshift to RUN");
            ctrl.updateSpeedDrive(effectiveDist <= 5.0F, true, vCmd, dt);
        }

        float finalScale = ctrl.getTrackSpeedScale(true);
        assertTrue(finalScale >= 0.65F && finalScale <= 0.85F, "Rear rank track scale should settle near 0.76, got " + finalScale);
    }

    @Test
    void totalWar2dHungarianFormationMatcherPreventsCrossingAndPrioritizesUpstairs() {
        final int n = 10;
        float leaderX = 100.0F;
        float leaderY = 100.0F;
        float[] fx = new float[n];
        float[] fy = new float[n];
        float[] fz = new float[n];
        boolean[] onStairs = new boolean[n];

        // 初始面向南 (0, 1) 的 10 人完美 V 阵
        for (int i = 0; i < n; i++) {
            float back = EmberFollowGaitControl.slotBack(i);
            float side = EmberFollowGaitControl.slotSide(i);
            fx[i] = leaderX - 0.0F * back - 1.0F * side;
            fy[i] = leaderY - 1.0F * back + 0.0F * side;
        }

        // 1A. 180° 掉头朝北 (0, -1)：外翼展开型楔阵就地换向保持 0 交叉；领袖越过队尾反穿时原最末尾接管最前排且 0 交叉
        int[] ranksNorthInPlace = EmberFormationMatcher.assignRanks(
                n, fx, fy, fz, onStairs, null, leaderX, leaderY, 0.0F, 0.0F, -1.0F);
        assertValidPermutation(ranksNorthInPlace, n);
        assertNoSegmentCrossings(n, fx, fy, leaderX, leaderY, 0.0F, -1.0F, ranksNorthInPlace);

        int[] ranksNorthPassed = EmberFormationMatcher.assignRanks(
                n, fx, fy, fz, onStairs, null, leaderX, 91.5F, 0.0F, 0.0F, -1.0F);
        assertValidPermutation(ranksNorthPassed, n);
        assertTrue(ranksNorthPassed[9] <= 2 && ranksNorthPassed[8] <= 2 && ranksNorthPassed[7] <= 2,
                "Rear-most followers should take front-most ranks (0..2) when leader reverses past the rear");
        assertNoSegmentCrossings(n, fx, fy, leaderX, 91.5F, 0.0F, -1.0F, ranksNorthPassed);

        // 共线纵队 (COLUMN) 180° 就地掉头：最末尾稳拿前排 0..2
        float[] colX = new float[n];
        float[] colY = new float[n];
        float[] colZ = new float[n];
        float[] colScratch = new float[2];
        for (int i = 0; i < n; i++) {
            EmberFormationMatcher.slotOffset(FormationType.COLUMN, i, n, 1.0F, colScratch);
            colX[i] = leaderX;
            colY[i] = leaderY - colScratch[0];
        }
        int[] colNorth = EmberFormationMatcher.assignRanks(
                n, colX, colY, colZ, onStairs, null, null,
                FormationType.COLUMN, FormationSpacing.STANDARD, true,
                leaderX, leaderY, 0.0F, 0.0F, -1.0F);
        assertValidPermutation(colNorth, n);
        assertTrue(colNorth[9] <= 2 && colNorth[8] <= 2 && colNorth[7] <= 2,
                "Column rear-most followers should take front-most ranks (0..2) on 180° reversal");

        // 1B. 90° 转弯朝东 (1, 0)：验证全局双射与零交叉
        int[] ranksEast = EmberFormationMatcher.assignRanks(
                n, fx, fy, fz, onStairs, null, leaderX, leaderY, 0.0F, 1.0F, 0.0F);
        assertValidPermutation(ranksEast, n);
        assertNoSegmentCrossings(n, fx, fy, leaderX, leaderY, 1.0F, 0.0F, ranksEast);

        // 2. 3D 楼层/楼梯拓扑分级：二楼离梯 > 楼梯高程递减 > 一楼未登梯
        float[] sX = { 60.0F, 61.0F, 59.0F, 60.5F, 68.5F, 68.5F, 68.5F, 68.5F, 67.5F, 69.5F };
        float[] sY = { 51.0F, 51.5F, 52.0F, 52.5F, 54.8F, 55.5F, 56.5F, 57.5F, 58.0F, 58.0F };
        float[] sZ = {  1.0F,  1.0F,  1.0F,  1.0F, 0.85F, 0.55F, 0.25F,  0.0F,  0.0F,  0.0F };
        boolean[] sStairs = { false, false, false, false, true, true, true, false, false, false };
        int[] stairRanks = EmberFormationMatcher.assignRanks(
                n, sX, sY, sZ, sStairs, null, 60.0F, 50.0F, 1.0F, 0.0F, -1.0F);
        assertValidPermutation(stairRanks, n);
        for (int i = 0; i <= 3; i++) {
            assertTrue(stairRanks[i] >= 0 && stairRanks[i] <= 3,
                    "Floor-1 follower " + i + " should stay in front slots 0..3, got " + stairRanks[i]);
        }
        assertEquals(4, stairRanks[4], "Highest stair climber (z=0.85) should hold rank 4");
        assertEquals(5, stairRanks[5], "Mid stair climber (z=0.55) should hold rank 5");
        assertEquals(6, stairRanks[6], "Lowest stair climber (z=0.25) should hold rank 6");
        for (int i = 7; i <= 9; i++) {
            assertTrue(stairRanks[i] >= 7 && stairRanks[i] <= 9,
                    "Floor-0 follower " + i + " should stay in rear slots 7..9, got " + stairRanks[i]);
        }

        // 3. 粘滞迟滞测试：中点微幅抖动 (< 0.20F) 不触发换位鬼畜
        float[] jX = { 10.12F, 9.88F };
        float[] jY = { 11.60F, 12.00F };
        float[] jZ = { 0.0F, 0.0F };
        boolean[] jStairs = { false, false };
        int[] prev = { 0, 1 };
        int[] held = EmberFormationMatcher.assignRanks(
                2, jX, jY, jZ, jStairs, prev, 10.0F, 10.0F, 0.0F, 0.0F, -1.0F);
        assertEquals(0, held[0], "Sticky hysteresis should keep follower 0 at rank 0 under small jitter");
        assertEquals(1, held[1], "Sticky hysteresis should keep follower 1 at rank 1 under small jitter");
    }

    private static void assertValidPermutation(int[] ranks, int n) {
        assertEquals(n, ranks.length, "Assigned rank array length must equal follower count");
        boolean[] seen = new boolean[n];
        for (int i = 0; i < n; i++) {
            int r = ranks[i];
            assertTrue(r >= 0 && r < n, "Rank out of bounds: " + r);
            assertTrue(!seen[r], "Duplicate rank assigned: " + r);
            seen[r] = true;
        }
    }

    private static void assertNoSegmentCrossings(
            int n, float[] fx, float[] fy,
            float leaderX, float leaderY, float hx, float hy, int[] ranks) {
        float[] sx = new float[n];
        float[] sy = new float[n];
        double len = Math.hypot(hx, hy);
        float ux = (float) (hx / len);
        float uy = (float) (hy / len);
        for (int i = 0; i < n; i++) {
            int r = ranks[i];
            float back = EmberFollowGaitControl.slotBack(r);
            float side = EmberFollowGaitControl.slotSide(r);
            sx[i] = leaderX - ux * back - uy * side;
            sy[i] = leaderY - uy * back + ux * side;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                assertTrue(!segmentsStrictlyIntersect(fx[i], fy[i], sx[i], sy[i], fx[j], fy[j], sx[j], sy[j]),
                        String.format("Trajectory crossing detected between follower %d (rank %d) and %d (rank %d)",
                                i, ranks[i], j, ranks[j]));
            }
        }
    }

    private static boolean segmentsStrictlyIntersect(
            double ax, double ay, double bx, double by,
            double cx, double cy, double dx, double dy) {
        double o1 = orient(ax, ay, bx, by, cx, cy);
        double o2 = orient(ax, ay, bx, by, dx, dy);
        double o3 = orient(cx, cy, dx, dy, ax, ay);
        double o4 = orient(cx, cy, dx, dy, bx, by);
        return (o1 * o2 < -1.0E-5) && (o3 * o4 < -1.0E-5);
    }

    private static double orient(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }
}
