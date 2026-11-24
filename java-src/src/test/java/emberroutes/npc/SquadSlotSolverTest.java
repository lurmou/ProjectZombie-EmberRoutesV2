package emberroutes.npc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SquadSlotSolverTest {

    private static final class MockTerrainProbe implements SquadSlotSolver.TerrainProbe {
        private final boolean[][] blockedTile = new boolean[24][24];
        private final boolean[][] bushTile = new boolean[24][24];
        private final boolean[][] wallH = new boolean[25][25]; // horizontal wall along south edge of y (between y and y+1)
        private final boolean[][] wallV = new boolean[25][25]; // vertical wall along east edge of x (between x and x+1)
        private float obsMinX = Float.NaN;
        private float obsMinY = Float.NaN;
        private float obsMaxX = Float.NaN;
        private float obsMaxY = Float.NaN;

        void setBoxObstacle(float minX, float minY, float maxX, float maxY) {
            this.obsMinX = minX;
            this.obsMinY = minY;
            this.obsMaxX = maxX;
            this.obsMaxY = maxY;
        }

        void blockTilesRect(int x0, int y0, int x1, int y1) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    blockedTile[x][y] = true;
                }
            }
        }

        void setBushTile(int x, int y) {
            bushTile[x][y] = true;
        }

        void setHorizontalBarrier(int x0, int x1, int yNorthTile) {
            for (int x = x0; x <= x1; x++) {
                wallH[x][yNorthTile] = true;
            }
        }

        void setVerticalBarrier(int xWestTile, int y0, int y1) {
            for (int y = y0; y <= y1; y++) {
                wallV[xWestTile][y] = true;
            }
        }

        private boolean inBounds(int tx, int ty) {
            return tx >= 0 && tx < 24 && ty >= 0 && ty < 24;
        }

        private boolean pointInObstacle(float x, float y, float pad) {
            if (!Float.isFinite(obsMinX)) {
                return false;
            }
            return x >= obsMinX - pad && x <= obsMaxX + pad && y >= obsMinY - pad && y <= obsMaxY + pad;
        }

        @Override
        public boolean canStandSafe(float x, float y) {
            int tx = (int) Math.floor(x);
            int ty = (int) Math.floor(y);
            if (!inBounds(tx, ty) || blockedTile[tx][ty] || pointInObstacle(x, y, 0.30F)) {
                return false;
            }
            // Enforce 0.30 tile margin from blocked adjacent tiles or thin wall/fence edges (mirrors B42 closeToWalls=false)
            if (x - tx < 0.30F && (!inBounds(tx - 1, ty) || blockedTile[tx - 1][ty] || wallV[tx - 1][ty])) {
                return false;
            }
            if ((tx + 1) - x < 0.30F && (!inBounds(tx + 1, ty) || blockedTile[tx + 1][ty] || wallV[tx][ty])) {
                return false;
            }
            if (y - ty < 0.30F && (!inBounds(tx, ty - 1) || blockedTile[tx][ty - 1] || wallH[tx][ty - 1])) {
                return false;
            }
            if ((ty + 1) - y < 0.30F && (!inBounds(tx, ty + 1) || blockedTile[tx][ty + 1] || wallH[tx][ty])) {
                return false;
            }
            return true;
        }

        @Override
        public boolean isOpenGround(float x, float y) {
            int tx = (int) Math.floor(x);
            int ty = (int) Math.floor(y);
            return inBounds(tx, ty) && !bushTile[tx][ty];
        }

        @Override
        public boolean isSegmentClear(float x1, float y1, float x2, float y2) {
            int steps = 20;
            int prevTx = (int) Math.floor(x1);
            int prevTy = (int) Math.floor(y1);
            for (int s = 1; s <= steps; s++) {
                float t = s / (float) steps;
                float px = x1 + (x2 - x1) * t;
                float py = y1 + (y2 - y1) * t;
                if (pointInObstacle(px, py, 0.25F)) {
                    return false;
                }
                int tx = (int) Math.floor(px);
                int ty = (int) Math.floor(py);
                if (!inBounds(tx, ty) || blockedTile[tx][ty]) {
                    return false;
                }
                if (tx != prevTx || ty != prevTy) {
                    if (tx > prevTx && wallV[prevTx][prevTy]) return false;
                    if (tx < prevTx && wallV[tx][prevTy]) return false;
                    if (ty > prevTy && wallH[prevTx][prevTy]) return false;
                    if (ty < prevTy && wallH[tx][prevTy]) return false;
                }
                prevTx = tx;
                prevTy = ty;
            }
            return true;
        }

        private boolean canStepEdge(int fx, int fy, int tx, int ty) {
            if (!inBounds(fx, fy) || !inBounds(tx, ty)) return false;
            if (blockedTile[tx][ty]) return false;
            if (tx == fx + 1 && wallV[fx][fy]) return false;
            if (tx == fx - 1 && wallV[tx][fy]) return false;
            if (ty == fy + 1 && wallH[fx][fy]) return false;
            if (ty == fy - 1 && wallH[fx][ty]) return false;
            if (pointInObstacle(tx + 0.5F, ty + 0.5F, 0.30F)) return false;
            return isSegmentClear(fx + 0.5F, fy + 0.5F, tx + 0.5F, ty + 0.5F);
        }

        @Override
        public boolean canStepTile(int fromTx, int fromTy, int toTx, int toTy) {
            int dx = toTx - fromTx;
            int dy = toTy - fromTy;
            if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || (dx == 0 && dy == 0)) {
                return false;
            }
            if (!canStepEdge(fromTx, fromTy, toTx, toTy)) {
                return false;
            }
            if (dx != 0 && dy != 0) {
                return canStepEdge(fromTx, fromTy, fromTx + dx, fromTy)
                        && canStepEdge(fromTx + dx, fromTy, toTx, toTy)
                        && canStepEdge(fromTx, fromTy, fromTx, fromTy + dy)
                        && canStepEdge(fromTx, fromTy + dy, toTx, toTy);
            }
            return true;
        }
    }

    @Test
    void screenshotScenarioTenMembersHouseBushesVanFenceNoOverlapAndValid() {
        MockTerrainProbe probe = new MockTerrainProbe();
        // House wall on north side: tiles y <= 7 are indoor / inaccessible
        probe.blockTilesRect(0, 0, 23, 7);
        // Shrubs along the house wall at y = 8
        for (int x = 6; x <= 14; x++) {
            probe.setBushTile(x, 8);
        }
        // Wooden fence blocking south side beyond y = 14
        probe.setHorizontalBarrier(0, 23, 14);
        probe.blockTilesRect(0, 15, 23, 23);
        // Parked van in the middle of the driveway
        probe.setBoxObstacle(9.6F, 11.4F, 12.1F, 13.6F);

        int count = 10;
        float[] slotX = new float[count];
        float[] slotY = new float[count];
        SquadSlotSolver.Workspace ws = new SquadSlotSolver.Workspace();
        int resolved = SquadSlotSolver.resolveWorldSlots(
                count, FormationType.WEDGE, FormationSpacing.STANDARD,
                10.5F, 10.3F, 10.5F, 10.3F,
                0.0F, 1.0F, 0.0F, probe, slotX, slotY, ws);
        assertEquals(count, resolved);

        int bushCount = 0;
        for (int i = 0; i < count; i++) {
            assertTrue(probe.canStandSafe(slotX[i], slotY[i]),
                    "Slot " + i + " must respect walls and van bounds: (" + slotX[i] + "," + slotY[i] + ")");
            assertTrue(slotY[i] >= 8.0F && slotY[i] < 15.0F,
                    "Slot " + i + " leaked past house wall or fence: y=" + slotY[i]);
            if (!probe.isOpenGround(slotX[i], slotY[i])) {
                bushCount++;
            }
            for (int j = i + 1; j < count; j++) {
                float d = (float) Math.hypot(slotX[i] - slotX[j], slotY[i] - slotY[j]);
                assertTrue(d >= SquadSlotSolver.MIN_SLOT_SEPARATION - 1.0E-3F,
                        "Slots " + i + " and " + j + " too close: d=" + d);
            }
        }
        assertTrue(bushCount <= 2, "Open driveway ground must be preferred over wall bushes, bushCount=" + bushCount);

        // Verify Hungarian bijection onto these resolved world slots assigns unique 0..9 ranks
        float[] fx = new float[count];
        float[] fy = new float[count];
        float[] fz = new float[count];
        for (int i = 0; i < count; i++) {
            fx[i] = 7.5F + (i % 5) * 1.4F;
            fy[i] = 9.5F + (i / 5) * 1.2F;
        }
        int[] outRanks = new int[count];
        EmberFormationMatcher.assignRanksToWorldSlots(
                count, fx, fy, fz, null, null, null,
                FormationType.WEDGE, true, 0.0F, slotX, slotY, probe, outRanks, null);
        boolean[] seen = new boolean[count];
        for (int r : outRanks) {
            assertTrue(r >= 0 && r < count && !seen[r], "Invalid or duplicate rank " + r);
            seen[r] = true;
        }
    }

    @Test
    void narrowAlleyTriggersElasticWidthCompression() {
        MockTerrainProbe probe = new MockTerrainProbe();
        // Walkable corridor only along x = 9..10
        probe.blockTilesRect(0, 0, 8, 23);
        probe.blockTilesRect(11, 0, 23, 23);

        float[] slotX = new float[4];
        float[] slotY = new float[4];
        SquadSlotSolver.Workspace ws = new SquadSlotSolver.Workspace();

        for (FormationType fmt : new FormationType[]{FormationType.LINE, FormationType.WEDGE}) {
            int resolved = SquadSlotSolver.resolveWorldSlots(
                    4, fmt, FormationSpacing.STANDARD,
                    10.0F, 9.5F, 10.0F, 9.5F,
                    0.0F, -1.0F, 0.0F, probe, slotX, slotY, ws);
            assertEquals(4, resolved);
            assertTrue(ws.getLastWidthScale() < 1.0F,
                    fmt + " in 2-tile alley should compress laterally, got " + ws.getLastWidthScale());
            for (int i = 0; i < 4; i++) {
                assertTrue(probe.canStandSafe(slotX[i], slotY[i]));
                assertTrue(slotX[i] >= 9.3F && slotX[i] <= 10.7F,
                        "Slot " + i + " exceeded alley bounds: x=" + slotX[i]);
                for (int j = i + 1; j < 4; j++) {
                    float d = (float) Math.hypot(slotX[i] - slotX[j], slotY[i] - slotY[j]);
                    assertTrue(d >= SquadSlotSolver.MIN_SLOT_SEPARATION - 1.0E-3F);
                }
            }
        }
    }

    @Test
    void closedWallPreventsLeakingSlotsIntoAdjacentRoom() {
        MockTerrainProbe probe = new MockTerrainProbe();
        // Solid horizontal wall between y=11 and y=12 (with open rooms on both sides)
        probe.setHorizontalBarrier(0, 23, 11);

        float[] slotX = new float[4];
        float[] slotY = new float[4];
        SquadSlotSolver.Workspace ws = new SquadSlotSolver.Workspace();
        // Anchor is north of wall facing north (0, -1), so ideal slots project south into y > 12 behind the wall
        int resolved = SquadSlotSolver.resolveWorldSlots(
                4, FormationType.LINE, FormationSpacing.STANDARD,
                10.5F, 10.5F, 10.5F, 10.5F,
                0.0F, -1.0F, 0.0F, probe, slotX, slotY, ws);
        assertEquals(4, resolved);
        for (int i = 0; i < 4; i++) {
            assertTrue(slotY[i] < 12.0F,
                    "Slot " + i + " crossed closed wall into isolated room: y=" + slotY[i]);
            assertTrue(probe.canStandSafe(slotX[i], slotY[i]));
        }
    }

    @Test
    void obstacleCrossingPenaltyKeepsLeftAndRightFollowersOnTheirSideOfVan() {
        MockTerrainProbe probe = new MockTerrainProbe();
        probe.setBoxObstacle(9.6F, 10.2F, 11.4F, 13.8F);

        float[] slotX = new float[4];
        float[] slotY = new float[4];
        SquadSlotSolver.Workspace ws = new SquadSlotSolver.Workspace();
        SquadSlotSolver.resolveWorldSlots(
                4, FormationType.LINE, FormationSpacing.WIDE,
                10.5F, 9.5F, 10.5F, 9.5F,
                0.0F, -1.0F, 0.0F, probe, slotX, slotY, ws);

        // Two followers start on the left of the van, two on the right
        float[] fx = {8.5F, 8.5F, 12.5F, 12.5F};
        float[] fy = {11.5F, 12.8F, 11.5F, 12.8F};
        float[] fz = new float[4];
        int[] outRanks = new int[4];
        EmberFormationMatcher.assignRanksToWorldSlots(
                4, fx, fy, fz, null, null, null,
                FormationType.LINE, true, 0.0F, slotX, slotY, probe, outRanks, null);

        for (int i = 0; i < 2; i++) {
            assertTrue(slotX[outRanks[i]] < 10.2F,
                    "Left follower " + i + " should stay left of van, got x=" + slotX[outRanks[i]]);
        }
        for (int i = 2; i < 4; i++) {
            assertTrue(slotX[outRanks[i]] > 10.8F,
                    "Right follower " + i + " should stay right of van, got x=" + slotX[outRanks[i]]);
        }
    }

    @Test
    void extremeCrampedRoomDegradesGracefullyWithoutInvalidCoords() {
        MockTerrainProbe probe = new MockTerrainProbe();
        // Only a 3x3 closet [9..11] x [9..11] is open
        probe.blockTilesRect(0, 0, 8, 23);
        probe.blockTilesRect(12, 0, 23, 23);
        probe.blockTilesRect(0, 0, 23, 8);
        probe.blockTilesRect(0, 12, 23, 23);

        int count = 8;
        float[] slotX = new float[count];
        float[] slotY = new float[count];
        SquadSlotSolver.Workspace ws = new SquadSlotSolver.Workspace();
        int resolved = SquadSlotSolver.resolveWorldSlots(
                count, FormationType.RING, FormationSpacing.STANDARD,
                10.5F, 9.5F, 10.5F, 9.5F,
                0.0F, -1.0F, 0.0F, probe, slotX, slotY, ws);
        assertEquals(count, resolved);
        for (int i = 0; i < count; i++) {
            assertTrue(Float.isFinite(slotX[i]) && Float.isFinite(slotY[i]));
            assertTrue(probe.canStandSafe(slotX[i], slotY[i]),
                    "Cramped closet slot " + i + " must remain standable: (" + slotX[i] + "," + slotY[i] + ")");
        }
    }

    @Test
    void outsideVehicleExteriorSeedAvoidsVehicleAndClosedHouse() {
        MockTerrainProbe probe = new MockTerrainProbe();
        // House wall on x <= 9
        probe.blockTilesRect(0, 0, 9, 23);
        // Parked vehicle covering [10.4..12.6] x [9.2..11.8]
        probe.setBoxObstacle(10.4F, 9.2F, 12.6F, 11.8F);

        float anchorX = 11.5F;
        float anchorY = 10.5F;
        float doorSeedX = 13.5F;
        float doorSeedY = 10.5F;
        float minVehSafe = 2.60F;

        float[] slotX = new float[4];
        float[] slotY = new float[4];
        SquadSlotSolver.Workspace ws = new SquadSlotSolver.Workspace();
        int resolved = SquadSlotSolver.resolveWorldSlots(
                4, FormationType.WEDGE, FormationSpacing.STANDARD,
                anchorX, anchorY, doorSeedX, doorSeedY,
                0.0F, -1.0F, minVehSafe, probe, slotX, slotY, ws);
        assertEquals(4, resolved);
        for (int i = 0; i < 4; i++) {
            assertTrue(slotX[i] >= 10.0F, "Exterior vehicle seed must not leak into house: x=" + slotX[i]);
            assertTrue(probe.canStandSafe(slotX[i], slotY[i]));
            float distAnchor = (float) Math.hypot(slotX[i] - anchorX, slotY[i] - anchorY);
            assertTrue(distAnchor >= minVehSafe - 1.0E-3F,
                    "Overflow vehicle guard slot " + i + " inside minVehSafe radius: d=" + distAnchor);
        }
    }
}
