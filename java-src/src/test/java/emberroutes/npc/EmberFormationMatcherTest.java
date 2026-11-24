package emberroutes.npc;

import emberroutes.npc.EmberFormationMatcher.RectangularHungarianWorkspace;
import emberroutes.npc.SquadTacticalConfig.OrderMode;
import emberroutes.npc.SquadTacticalConfig.VehicleCommandMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmberFormationMatcherTest {

    @Test
    void allFormationsAndSpacingsAssignZeroCrossingBijectionAcross360Degrees() {
        final int n = 5;
        float[] scratch = new float[2];
        float[] fx = new float[n];
        float[] fy = new float[n];
        float[] fz = new float[n];
        boolean[] onStairs = new boolean[n];
        int[] prevRank = new int[n];
        int[] outRanks = new int[n];
        RectangularHungarianWorkspace ws = new RectangularHungarianWorkspace(8);

        for (FormationType fmt : FormationType.values()) {
            for (FormationSpacing sp : FormationSpacing.values()) {
                for (int deg = 0; deg < 360; deg += 30) {
                    double rad = Math.toRadians(deg);
                    float hx = (float) Math.cos(rad);
                    float hy = (float) Math.sin(rad);

                    for (int i = 0; i < n; i++) {
                        EmberFormationMatcher.slotOffset(fmt, i, n, sp.multiplier(), scratch);
                        float back = scratch[0];
                        float side = scratch[1];
                        assertTrue(Float.isFinite(back) && Float.isFinite(side));
                        fx[i] = 100.0F - hx * back - hy * side + (i * 0.01F);
                        fy[i] = 200.0F - hy * back + hx * side;
                        fz[i] = 0.0F;
                        prevRank[i] = -1;
                    }

                    EmberFormationMatcher.assignRanks(
                            n, fx, fy, fz, onStairs, prevRank, null,
                            fmt, sp, true,
                            100.0F, 200.0F, 0.0F, hx, hy,
                            outRanks, ws);

                    boolean[] seen = new boolean[n];
                    for (int i = 0; i < n; i++) {
                        assertEquals(i, outRanks[i],
                                "Expected zero-crossing slot " + i + " for " + fmt + "/" + sp + "@" + deg);
                        assertFalse(seen[outRanks[i]]);
                        seen[outRanks[i]] = true;
                    }
                }
            }
        }
    }

    @Test
    void roleBiasGuidesSlotOrderWithinSameFloorWithoutOverridingTierBarrier() {
        final int n = 4;
        float[] fx = { 100.0F, 100.0F, 100.0F, 100.0F };
        float[] fy = { 102.5F, 102.5F, 102.5F, 102.5F };
        float[] fz = { 0.0F, 0.0F, 0.0F, 0.0F };
        boolean[] onStairs = new boolean[n];
        int[] prevRank = { -1, -1, -1, -1 };
        TacticalRole[] roles = {
            TacticalRole.MEDIC,
            TacticalRole.RANGED,
            TacticalRole.MELEE,
            TacticalRole.MELEE
        };

        // 同点等距起跑时：MELEE 抢前排 (0/1)，RANGED 与 MEDIC 在后排 (2/3)
        int[] wedgeRanks = EmberFormationMatcher.assignRanks(
                n, fx, fy, fz, onStairs, prevRank, roles,
                FormationType.WEDGE, FormationSpacing.STANDARD, true,
                100.0F, 100.0F, 0.0F, 0.0F, -1.0F);
        assertTrue(wedgeRanks[2] < wedgeRanks[0], "MELEE must take forward slot over MEDIC");
        assertTrue(wedgeRanks[3] < wedgeRanks[0], "MELEE must take forward slot over MEDIC");
        assertTrue(wedgeRanks[2] < wedgeRanks[1], "MELEE must take forward slot over RANGED");

        // 阶梯代价壁垒不可被职业偏置击穿：若 MEDIC 已与领袖同层(0楼)，MELEE 仍在 1 楼（落后层），绝不可抢走 0 号前排位
        float[] tieredZ = { 0.0F, 1.0F, 1.0F, 1.0F };
        int[] tieredRanks = EmberFormationMatcher.assignRanks(
                n, fx, fy, tieredZ, onStairs, prevRank, roles,
                FormationType.COLUMN, FormationSpacing.STANDARD, true,
                100.0F, 100.0F, 0.0F, 0.0F, -1.0F);
        assertEquals(0, tieredRanks[0], "Same-floor MEDIC must keep rank 0 ahead of off-floor MELEE");
    }

    @Test
    void slotNominalRadiusAndGaitThresholdsScaleMonotonically() {
        float[] scratch = new float[2];
        for (FormationType fmt : FormationType.values()) {
            for (int r = 0; r < 5; r++) {
                float rTight = EmberFormationMatcher.slotNominalRadius(
                        fmt, r, 5, FormationSpacing.TIGHT.multiplier(), scratch);
                float rStd = EmberFormationMatcher.slotNominalRadius(
                        fmt, r, 5, FormationSpacing.STANDARD.multiplier(), scratch);
                float rWide = EmberFormationMatcher.slotNominalRadius(
                        fmt, r, 5, FormationSpacing.WIDE.multiplier(), scratch);

                assertTrue(rTight > 0.5F && rTight < rStd && rStd < rWide,
                        fmt + " rank " + r + " nominal radius must increase monotonically with spacing");
                assertTrue(EmberFollowGaitControl.catchUpEnterDist(rWide) >= EmberFollowGaitControl.catchUpEnterDist(rTight));
                assertEquals(
                        Math.max(0.0F, 6.0F - Math.max(0.0F, rStd - 1.60F)),
                        EmberFollowGaitControl.effectiveDistToLeader(rStd, 6.0F),
                        1.0e-5F);
            }
        }
    }

    @Test
    void squadTacticalConfigVersionsAndOverridesWorkCleanly() {
        SquadTacticalConfig.reset();
        long v0 = SquadTacticalConfig.getVersion();

        assertFalse(SquadTacticalConfig.setFormation(FormationType.WEDGE));
        assertEquals(v0, SquadTacticalConfig.getVersion());

        assertTrue(SquadTacticalConfig.setFormation(FormationType.RING));
        long v1 = SquadTacticalConfig.getVersion();
        assertTrue(v1 > v0);

        SquadTacticalConfig.setHoldAnchor(50.0F, 60.0F, 1, 1.0F, 0.0F);
        assertEquals(OrderMode.HOLD_POSITION, SquadTacticalConfig.getOrderMode());
        assertTrue(SquadTacticalConfig.hasHoldAnchor());
        assertEquals(50.0F, SquadTacticalConfig.getHoldX(), 1e-4F);
        assertEquals(1, SquadTacticalConfig.getHoldZ());

        assertTrue(SquadTacticalConfig.setOrderMode(OrderMode.FOLLOW));
        assertFalse(SquadTacticalConfig.hasHoldAnchor());

        SquadTacticalConfig.setMemberRoeOverride("npc_1", RulesOfEngagement.HOLD_FIRE);
        assertEquals(RulesOfEngagement.HOLD_FIRE, SquadTacticalConfig.getEffectiveRoe("npc_1"));
        assertTrue(SquadTacticalConfig.hasMemberRoeOverride("npc_1"));
        SquadTacticalConfig.setMemberRoeOverride("npc_1", null);
        assertEquals(SquadTacticalConfig.getDefaultRoe(), SquadTacticalConfig.getEffectiveRoe("npc_1"));

        SquadTacticalConfig.setMemberCqbOverride("npc_1", Boolean.TRUE);
        assertTrue(SquadTacticalConfig.isEffectiveCqb("npc_1"));
        SquadTacticalConfig.clearMemberOverrides("npc_1");
        assertFalse(SquadTacticalConfig.hasMemberCqbOverride("npc_1"));

        Object veh = new Object();
        SquadTacticalConfig.setManualVehicleOverride(VehicleCommandMode.FORCE_BOARD, veh);
        assertEquals(VehicleCommandMode.FORCE_BOARD, SquadTacticalConfig.getVehicleOverride());
        assertTrue(SquadTacticalConfig.matchesOverrideVehicle(veh));
        SquadTacticalConfig.clearManualVehicleOverride();
        assertEquals(VehicleCommandMode.AUTO, SquadTacticalConfig.getVehicleOverride());
        assertNull(SquadTacticalConfig.getOverrideVehicle());

        SquadTacticalConfig.reset();
    }
}
