package emberroutes;

import emberroutes.npc.IsoNPC;
import java.lang.reflect.Field;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.core.math.PZMath;
import zombie.core.skinnedmodel.ModelCameraRenderData;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;

/**
 * 补齐 B42 原版仅针对坐姿、未对卧床实现的渲染垂直抬升与 FBO 深度重算（与 {@code adjustForSittingOnFurniture} 对称）：
 * <ul>
 *   <li>仅调整渲染相机数据与 {@link ModelSlotRenderData#z} / {@code squareDepth}，绝不改动实体物理 {@code npc.z}；</li>
 *   <li>仅在目标为 {@link IsoNPC} 且 {@code npc.isOnBed() && cachedBedRenderLiftZ > 0} 时生效，对真玩家与非卧床状态 0 副作用。</li>
 * </ul>
 */
public final class Patch_BedRenderElevation {
    private static final Field CAMERA_Z = findDeclaredFloatField(ModelCameraRenderData.class, "z");
    private static final Field SLOT_CAM_X = findDeclaredFloatField(ModelSlotRenderData.class, "camCharacterX");
    private static final Field SLOT_CAM_Y = findDeclaredFloatField(ModelSlotRenderData.class, "camCharacterY");
    private static final Field SLOT_FIX_X = findDeclaredFloatField(ModelSlotRenderData.class, "fixJigglyModelsSquareX");
    private static final Field SLOT_FIX_Y = findDeclaredFloatField(ModelSlotRenderData.class, "fixJigglyModelsSquareY");

    private Patch_BedRenderElevation() {}

    @Patch(className = "zombie.core.skinnedmodel.ModelCameraRenderData", methodName = "adjustForSittingOnFurniture")
    public static final class OnModelCameraSitAdjust {
        @Patch.OnExit
        public static void exit(
                @Patch.This ModelCameraRenderData self,
                @Patch.Argument(0) IsoGameCharacter character) {
            applyCameraBedLift(self, character);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.model.ModelSlotRenderData", methodName = "adjustForSittingOnFurniture")
    public static final class OnModelSlotSitAdjust {
        @Patch.OnExit
        public static void exit(@Patch.This ModelSlotRenderData self) {
            applySlotBedLift(self);
        }
    }

    public static void applyCameraBedLift(ModelCameraRenderData self, IsoGameCharacter character) {
        if (self == null || CAMERA_Z == null || !(character instanceof IsoNPC npc) || !npc.isOnBed()) {
            return;
        }
        float liftZ = npc.getPosture().getCachedBedRenderLiftZ();
        if (liftZ <= 0.0F) {
            return;
        }
        try {
            CAMERA_Z.setFloat(self, CAMERA_Z.getFloat(self) + liftZ);
        } catch (Throwable ignored) {}
    }

    public static void applySlotBedLift(ModelSlotRenderData self) {
        if (self == null || !(self.character instanceof IsoNPC npc) || !npc.isOnBed()) {
            return;
        }
        float liftZ = npc.getPosture().getCachedBedRenderLiftZ();
        if (liftZ <= 0.0F) {
            return;
        }
        self.z += liftZ;
        float camX = readFloatOr(SLOT_CAM_X, self, IsoCamera.frameState.camCharacterX);
        float camY = readFloatOr(SLOT_CAM_Y, self, IsoCamera.frameState.camCharacterY);
        float fixX = readFloatOr(SLOT_FIX_X, self, 0.0F);
        float fixY = readFloatOr(SLOT_FIX_Y, self, 0.0F);
        IsoDepthHelper.Results results = IsoDepthHelper.getSquareDepthData(
                PZMath.fastfloor(camX),
                PZMath.fastfloor(camY),
                self.x + fixX,
                self.y + fixY,
                self.z);
        self.squareDepth = results.depthStart;
    }

    private static Field findDeclaredFloatField(Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float readFloatOr(Field f, Object target, float fallback) {
        if (f == null) {
            return fallback;
        }
        try {
            return f.getFloat(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
