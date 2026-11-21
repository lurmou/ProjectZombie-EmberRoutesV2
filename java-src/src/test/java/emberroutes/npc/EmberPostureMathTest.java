package emberroutes.npc;

import emberroutes.npc.EmberPostureMath.BedApproach;
import emberroutes.npc.EmberPostureMath.BedFacing;
import emberroutes.npc.EmberPostureMath.BedSlot;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmberPostureMathTest {
    private static final Set<String> ALLOWED_ON_BED_DIRS = Set.of(
            "HeadLeft", "HeadRight", "FootLeft", "FootRight");

    @Test
    void singleBedFourFacingsMatchVanillaSnapAndHeadOrientation() {
        verifyBedAllApproaches(BedFacing.N, 1, 2, 100, 200, 1, 0, 1, 0, 0);
        verifyBedAllApproaches(BedFacing.S, 1, 2, 100, 200, 1, 0, 0, 0, 1);
        verifyBedAllApproaches(BedFacing.W, 2, 1, 100, 200, 1, 1, 0, 0, 0);
        verifyBedAllApproaches(BedFacing.E, 2, 1, 100, 200, 1, 0, 0, 1, 0);
    }

    @Test
    void doubleBedFourFacingsOfferTwoOuterSlotsWithoutInteriorCrossing() {
        List<BedSlot> nSlots = verifyBedAllApproaches(BedFacing.N, 2, 2, 50, 80, 2, 0, 1, 0, 0);
        assertTrue(nSlots.get(0).approaches().stream().allMatch(a -> a.beforeDir() == BedFacing.E));
        assertTrue(nSlots.get(1).approaches().stream().allMatch(a -> a.beforeDir() == BedFacing.W));
        assertEquals(2, nSlots.get(0).approaches().size());
        assertEquals(2, nSlots.get(1).approaches().size());

        List<BedSlot> sSlots = verifyBedAllApproaches(BedFacing.S, 2, 2, 50, 80, 2, 0, 0, 0, 1);
        assertTrue(sSlots.get(0).approaches().stream().allMatch(a -> a.beforeDir() == BedFacing.E));
        assertTrue(sSlots.get(1).approaches().stream().allMatch(a -> a.beforeDir() == BedFacing.W));

        List<BedSlot> wSlots = verifyBedAllApproaches(BedFacing.W, 2, 2, 50, 80, 2, 1, 0, 0, 0);
        assertTrue(wSlots.get(0).approaches().stream().allMatch(a -> a.beforeDir() == BedFacing.S));
        assertTrue(wSlots.get(1).approaches().stream().allMatch(a -> a.beforeDir() == BedFacing.N));

        List<BedSlot> eSlots = verifyBedAllApproaches(BedFacing.E, 2, 2, 50, 80, 2, 0, 0, 1, 0);
        assertTrue(eSlots.get(0).approaches().stream().allMatch(a -> a.beforeDir() == BedFacing.S));
        assertTrue(eSlots.get(1).approaches().stream().allMatch(a -> a.beforeDir() == BedFacing.N));
    }

    @Test
    void oneByOneCotEmitsHeadApproachesOnly() {
        for (BedFacing f : BedFacing.values()) {
            List<BedSlot> slots = EmberPostureMath.enumerateBedSlots(f, 1, 1, 10, 20);
            assertEquals(1, slots.size());
            assertEquals(2, slots.get(0).approaches().size());
            for (BedApproach a : slots.get(0).approaches()) {
                assertTrue(a.headEntry());
                assertTrue(ALLOWED_ON_BED_DIRS.contains(a.onBedDirection()));
                assertEquals(f, a.lieDir());
            }
        }
    }

    @Test
    void resolveSeatBeforeDirMatchesVanillaPlayerSitOnFurnitureState() {
        assertEquals(BedFacing.N, EmberPostureMath.resolveSeatBeforeDir(BedFacing.N, "Front"));
        assertEquals(BedFacing.E, EmberPostureMath.resolveSeatBeforeDir(BedFacing.N, "Left"));
        assertEquals(BedFacing.W, EmberPostureMath.resolveSeatBeforeDir(BedFacing.N, "Right"));

        assertEquals(BedFacing.S, EmberPostureMath.resolveSeatBeforeDir(BedFacing.S, "Front"));
        assertEquals(BedFacing.W, EmberPostureMath.resolveSeatBeforeDir(BedFacing.S, "Left"));
        assertEquals(BedFacing.E, EmberPostureMath.resolveSeatBeforeDir(BedFacing.S, "Right"));

        assertEquals(BedFacing.W, EmberPostureMath.resolveSeatBeforeDir(BedFacing.W, "Front"));
        assertEquals(BedFacing.N, EmberPostureMath.resolveSeatBeforeDir(BedFacing.W, "Left"));
        assertEquals(BedFacing.S, EmberPostureMath.resolveSeatBeforeDir(BedFacing.W, "Right"));

        assertEquals(BedFacing.E, EmberPostureMath.resolveSeatBeforeDir(BedFacing.E, "Front"));
        assertEquals(BedFacing.S, EmberPostureMath.resolveSeatBeforeDir(BedFacing.E, "Left"));
        assertEquals(BedFacing.N, EmberPostureMath.resolveSeatBeforeDir(BedFacing.E, "Right"));
    }

    @Test
    void clickedSlotIndexAndFacingNormalization() {
        assertEquals(BedFacing.N, BedFacing.normalize(" n "));
        assertNull(BedFacing.normalize("NE"));
        assertEquals(BedFacing.E, BedFacing.inferFromGrid(2, 1));
        assertEquals(BedFacing.S, BedFacing.inferFromGrid(1, 2));

        assertEquals(0, EmberPostureMath.resolveClickedSlotIndex(BedFacing.N, 2, 2, 0, 1));
        assertEquals(1, EmberPostureMath.resolveClickedSlotIndex(BedFacing.N, 2, 2, 1, 0));
        assertEquals(0, EmberPostureMath.resolveClickedSlotIndex(BedFacing.E, 2, 2, 1, 0));
        assertEquals(1, EmberPostureMath.resolveClickedSlotIndex(BedFacing.E, 2, 2, 0, 1));
    }

    @Test
    void realBedClassificationRejectsChairsAndCouches() {
        assertFalse(EmberPostureMath.isRealBedObject("furniture_seating_indoor_03_120", "Chair", BedFacing.E, 1, 1));
        assertFalse(EmberPostureMath.isRealBedObject("furniture_seating_indoor_03_133", "Couch", BedFacing.N, 2, 1));
        assertFalse(EmberPostureMath.isRealBedObject("carpentry_01_40", "Chair", BedFacing.S, 1, 1));
        assertFalse(EmberPostureMath.isRealBedObject("mod_custom_couch_0", "Sofa", BedFacing.S, 2, 1));
        assertFalse(EmberPostureMath.isRealBedObject("location_community_medical_01_1", null, BedFacing.S, 2, 1));

        assertTrue(EmberPostureMath.isRealBedObject("furniture_bedding_01_19", "Large Bed", BedFacing.W, 2, 2));
        assertTrue(EmberPostureMath.isRealBedObject("furniture_bedding_01_0", "Single Bed", BedFacing.S, 1, 2));
        assertTrue(EmberPostureMath.isRealBedObject("location_community_medical_01_76", "Hospital Bed", BedFacing.E, 2, 1));
        assertTrue(EmberPostureMath.isRealBedObject("carpentry_02_74", "Bed", BedFacing.S, 1, 2));
        assertTrue(EmberPostureMath.isRealBedObject("camping_01_16", "Tent", BedFacing.N, 1, 1));
    }

    @Test
    void smoothBlendStepConvergesExactlyWithConcurrentVanillaMotion() {
        assertEquals(0.0F, EmberPostureMath.smoothstep01(-0.5F), 1e-6F);
        assertEquals(0.5F, EmberPostureMath.smoothstep01(0.5F), 1e-6F);
        assertEquals(1.0F, EmberPostureMath.smoothstep01(1.5F), 1e-6F);

        // 模拟原版 FootRight（复用 Right.x，无床尾爬行，仅进床 0.43 格且偏移 0.15 格）与补位叠加
        float targetX = 100.50F;
        float targetY = 200.50F;
        float curX = 101.30F;
        float curY = 199.50F;
        float prevU = 0.0F;
        for (int step = 1; step <= 40; step++) {
            float frac = step / 40.0F;
            // 原版动画在爬床过程同步贡献的位移/漂移
            curX += -0.43F / 40.0F;
            curY += -0.15F / 40.0F;
            float u = EmberPostureMath.smoothstep01((frac - 0.15F) / 0.85F);
            curX = EmberPostureMath.computeSmoothBlendStep(curX, targetX, prevU, u);
            curY = EmberPostureMath.computeSmoothBlendStep(curY, targetY, prevU, u);
            assertFalse(Float.isNaN(curX) || Float.isNaN(curY));
            prevU = u;
        }
        assertEquals(targetX, curX, 1e-4F);
        assertEquals(targetY, curY, 1e-4F);

        // 进度回退或静止时不发生任何跃迁
        assertEquals(42.25F, EmberPostureMath.computeSmoothBlendStep(42.25F, 50.0F, 0.5F, 0.4F), 1e-6F);
        assertEquals(42.25F, EmberPostureMath.computeSmoothBlendStep(42.25F, 50.0F, 0.5F, 0.5F), 1e-6F);
    }

    @Test
    void bedRenderLiftAndElevationLerpMatchB42MattressGeometry() {
        float expectedDoubleLift = (0.7164F - 0.44F) / EmberPostureMath.WORLD_Z_UNITS_PER_STORY;
        assertEquals(expectedDoubleLift, EmberPostureMath.computeBedRenderLiftZ(0.7164F), 1e-5F);

        float expectedMediumLift = (0.620F - 0.44F) / EmberPostureMath.WORLD_Z_UNITS_PER_STORY;
        assertEquals(expectedMediumLift, EmberPostureMath.computeBedRenderLiftZ(0.620F), 1e-5F);

        assertEquals(0.0F, EmberPostureMath.computeBedRenderLiftZ(0.051F), 1e-6F);
        assertEquals(0.0F, EmberPostureMath.computeBedRenderLiftZ(0.40F), 1e-6F);
        assertEquals(0.0F, EmberPostureMath.computeBedRenderLiftZ(Float.NaN), 1e-6F);

        // 入床 [0.20, 0.50] 平滑抬升
        assertEquals(0.0F, EmberPostureMath.computeBedElevationLerp(true, false, false, 0.0F), 1e-6F);
        assertEquals(0.0F, EmberPostureMath.computeBedElevationLerp(true, false, false, 0.20F), 1e-6F);
        assertEquals(0.5F, EmberPostureMath.computeBedElevationLerp(true, false, false, 0.35F), 1e-5F);
        assertEquals(1.0F, EmberPostureMath.computeBedElevationLerp(true, false, false, 0.50F), 1e-6F);
        assertEquals(1.0F, EmberPostureMath.computeBedElevationLerp(true, false, false, 1.0F), 1e-6F);

        // 稳态卧床（含睡眠与唤醒）恒为 1.0
        assertEquals(1.0F, EmberPostureMath.computeBedElevationLerp(false, true, false, 0.0F), 1e-6F);
        assertEquals(1.0F, EmberPostureMath.computeBedElevationLerp(true, true, true, 0.3F), 1e-6F);

        // 出床 [0.60, 0.85] 平滑回落
        assertEquals(1.0F, EmberPostureMath.computeBedElevationLerp(false, false, true, 0.0F), 1e-6F);
        assertEquals(1.0F, EmberPostureMath.computeBedElevationLerp(false, false, true, 0.60F), 1e-6F);
        assertEquals(0.5F, EmberPostureMath.computeBedElevationLerp(false, false, true, 0.725F), 1e-5F);
        assertEquals(0.0F, EmberPostureMath.computeBedElevationLerp(false, false, true, 0.85F), 1e-6F);
        assertEquals(0.0F, EmberPostureMath.computeBedElevationLerp(false, false, true, 1.0F), 1e-6F);

        // 站立态恒为 0.0
        assertEquals(0.0F, EmberPostureMath.computeBedElevationLerp(false, false, false, 0.5F), 1e-6F);
    }

    @Test
    void sitAdapterChiralityLiftAndProgressHelpers() {
        // 四向躺卧朝向的左右坐缘互映射与可逆性
        assertEquals("HeadLeft", EmberPostureMath.resolveOnBedSideFromSitDir(BedFacing.E, BedFacing.N));
        assertEquals("HeadRight", EmberPostureMath.resolveOnBedSideFromSitDir(BedFacing.E, BedFacing.S));
        assertNull(EmberPostureMath.resolveOnBedSideFromSitDir(BedFacing.E, BedFacing.E));
        assertNull(EmberPostureMath.resolveOnBedSideFromSitDir(BedFacing.E, BedFacing.W));
        assertNull(EmberPostureMath.resolveOnBedSideFromSitDir(null, BedFacing.N));

        for (BedFacing lie : BedFacing.values()) {
            BedFacing leftOut = EmberPostureMath.resolveOutwardSitDir(lie, "HeadLeft");
            BedFacing rightOut = EmberPostureMath.resolveOutwardSitDir(lie, "HeadRight");
            assertEquals("HeadLeft", EmberPostureMath.resolveOnBedSideFromSitDir(lie, leftOut));
            assertEquals("HeadRight", EmberPostureMath.resolveOnBedSideFromSitDir(lie, rightOut));
            assertEquals(leftOut, EmberPostureMath.resolveOutwardSitDir(lie, "FootLeft"));
            assertEquals(rightOut, EmberPostureMath.resolveOutwardSitDir(lie, "FootRight"));
            assertNull(EmberPostureMath.resolveOutwardSitDir(lie, "Front"));
        }

        assertTrue(EmberPostureMath.isLeftSideOnBed("HeadLeft"));
        assertTrue(EmberPostureMath.isLeftSideOnBed("FootLeft"));
        assertFalse(EmberPostureMath.isLeftSideOnBed("HeadRight"));
        assertFalse(EmberPostureMath.isLeftSideOnBed("FootRight"));
        assertFalse(EmberPostureMath.isLeftSideOnBed(null));

        // 坐姿高度 <-> 床垫卧高平滑桥接（支持 seatZ > bedMaxLiftZ 与 seatZ < bedMaxLiftZ 双方向）
        assertEquals(0.150F, EmberPostureMath.computeSitAdapterLiftZ(0.150F, 0.113F, 0.0F), 1e-6F);
        assertEquals(0.1315F, EmberPostureMath.computeSitAdapterLiftZ(0.150F, 0.113F, 0.5F), 1e-5F);
        assertEquals(0.113F, EmberPostureMath.computeSitAdapterLiftZ(0.150F, 0.113F, 1.0F), 1e-6F);
        assertEquals(0.080F, EmberPostureMath.computeSitAdapterLiftZ(0.080F, 0.140F, -0.2F), 1e-6F);
        assertEquals(0.140F, EmberPostureMath.computeSitAdapterLiftZ(0.080F, 0.140F, 1.5F), 1e-6F);

        // 帧步长钳位至 [0, 0.10s]，防止掉帧/切换标签页瞬间跳变
        float p = EmberPostureMath.advanceAdapterProgress(0.0F, 0.02F, 0.80F);
        assertEquals(0.025F, p, 1e-6F);
        float pClampedDt = EmberPostureMath.advanceAdapterProgress(0.0F, 2.0F, 0.80F);
        assertEquals(0.125F, pClampedDt, 1e-6F);
        assertEquals(1.0F, EmberPostureMath.advanceAdapterProgress(0.95F, 0.08F, 0.80F), 1e-6F);
    }

    private static List<BedSlot> verifyBedAllApproaches(
            BedFacing facing, int w, int h, int originX, int originY,
            int expectedSlots, int expectedSlot0Hx, int expectedSlot0Hy, int expectedSlot0Fx, int expectedSlot0Fy) {
        List<BedSlot> slots = EmberPostureMath.enumerateBedSlots(facing, w, h, originX, originY);
        assertEquals(expectedSlots, slots.size());
        assertEquals(expectedSlot0Hx, slots.get(0).headGridX());
        assertEquals(expectedSlot0Hy, slots.get(0).headGridY());
        assertEquals(expectedSlot0Fx, slots.get(0).footGridX());
        assertEquals(expectedSlot0Fy, slots.get(0).footGridY());

        for (BedSlot slot : slots) {
            assertFalse(slot.approaches().isEmpty());
            for (BedApproach a : slot.approaches()) {
                assertNotEquals("Foot", a.onBedDirection(), "Foot freezes PlayerGetUpState due to fromOnBed.xml bug");
                assertTrue(ALLOWED_ON_BED_DIRS.contains(a.onBedDirection()));
                assertEquals(facing, a.lieDir());
                assertEquals(a.adjTileX(), (int) Math.floor(a.approachX()));
                assertEquals(a.adjTileY(), (int) Math.floor(a.approachY()));
                assertEquals(a.adjacentTargetTileX(), a.adjTileX() + a.beforeDir().dx);
                assertEquals(a.adjacentTargetTileY(), a.adjTileY() + a.beforeDir().dy);

                // PlayerOnBedState.enter() (int)x + 0.3F / 0.7F 精确契约不变量校验
                switch (a.beforeDir()) {
                    case N -> assertEquals((int) a.approachY() + 0.3F, a.approachY(), 1e-4F);
                    case S -> assertEquals((int) a.approachY() + 0.7F, a.approachY(), 1e-4F);
                    case W -> assertEquals((int) a.approachX() + 0.3F, a.approachX(), 1e-4F);
                    case E -> assertEquals((int) a.approachX() + 0.7F, a.approachX(), 1e-4F);
                }
            }
        }
        return slots;
    }
}
