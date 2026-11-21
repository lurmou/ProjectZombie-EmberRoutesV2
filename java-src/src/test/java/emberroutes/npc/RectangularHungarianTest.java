package emberroutes.npc;

import emberroutes.npc.EmberFormationMatcher.RectangularHungarianWorkspace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RectangularHungarianTest {
    @Test
    void handlesSquareMoreColsMoreRowsAndUnreachableDummyEdgesZeroGc() {
        RectangularHungarianWorkspace ws = new RectangularHungarianWorkspace(8);

        // 1. 方阵 3x3
        double[][] sqCost = {
            {10.0, 2.0, 9.0},
            {3.0, 11.0, 8.0},
            {12.0, 14.0, 1.0}
        };
        int[] out = new int[4];
        EmberFormationMatcher.solveRectangularHungarian(3, 3, sqCost, out, ws);
        assertEquals(1, out[0]);
        assertEquals(0, out[1]);
        assertEquals(2, out[2]);

        // 2. M < K (2 bidders, 4 tasks)
        double[][] fewerBidders = {
            {50.0, 4.0, 20.0, 30.0},
            {25.0, 3.0, 2.0, 40.0}
        };
        EmberFormationMatcher.solveRectangularHungarian(2, 4, fewerBidders, out, ws);
        assertEquals(1, out[0]);
        assertEquals(2, out[1]);

        // 3. M > K (4 bidders, 2 tasks)
        double[][] moreBidders = {
            {30.0, 40.0},
            {5.0, 25.0},
            {20.0, 4.0},
            {15.0, 18.0}
        };
        EmberFormationMatcher.solveRectangularHungarian(4, 2, moreBidders, out, ws);
        assertEquals(-1, out[0]);
        assertEquals(0, out[1]);
        assertEquals(1, out[2]);
        assertEquals(-1, out[3]);

        // 4. 不可达 DUMMY_COST 边自动剔除，不得强制配对
        double[][] withUnreachable = {
            {EmberFormationMatcher.DUMMY_COST, EmberFormationMatcher.DUMMY_COST},
            {12.0, 50.0}
        };
        EmberFormationMatcher.solveRectangularHungarian(2, 2, withUnreachable, out, ws);
        assertEquals(-1, out[0]);
        assertEquals(0, out[1]);
    }
}
