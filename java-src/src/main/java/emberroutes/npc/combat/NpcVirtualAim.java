package emberroutes.npc.combat;

/**
 * 第 6 号战斗微操内核：{@code NpcVirtualAim}（零 GC 单兵实例持有，零 B42 引擎依赖）。
 *
 * <ol>
 *   <li>对目标速度做 {@code 0.12s} 视觉提前量预测；</li>
 *   <li>以 {@code 90°/s -> 270°/s} 阻尼角速度坡度从当前朝向平滑追随至目标角，并支持 CQB 指定角速度切角；</li>
 *   <li>按 B42 轴测相机垂直比例系数 {@code 2.44949f} 计算俯仰补偿角；</li>
 *   <li>保存虚拟准星世界坐标，供 {@code B42CombatRuntime} 投影至 {@code AimingReticle.getX/getY}。</li>
 * </ol>
 */
public final class NpcVirtualAim {
    public static final float LEAD_TIME_SEC = 0.12F;
    public static final float MIN_SLEW_DEG_PER_SEC = 90.0F;
    public static final float MAX_SLEW_DEG_PER_SEC = 270.0F;
    public static final float VERTICAL_PROJ_SCALE = 2.44949F;
    public static final float CHEST_HEIGHT_OFFSET = 0.495F;
    public static final float PRONE_HEIGHT_OFFSET = 0.22F;

    private boolean active;
    private float aimYawRad;
    private float aimDirX = 1.0F;
    private float aimDirY = 0.0F;
    private float worldX;
    private float worldY;
    private float worldZ;
    private float desiredWorldX;
    private float desiredWorldY;
    private float desiredWorldZ;
    private float errorDegrees = 90.0F;
    private float pitchDegrees;

    public boolean isActive() {
        return active;
    }

    public float getAimYawRad() {
        return aimYawRad;
    }

    public float getAimYawDeg() {
        return (float) Math.toDegrees(aimYawRad);
    }

    public float getAimDirX() {
        return aimDirX;
    }

    public float getAimDirY() {
        return aimDirY;
    }

    public float getWorldX() {
        return worldX;
    }

    public float getWorldY() {
        return worldY;
    }

    public float getWorldZ() {
        return worldZ;
    }

    public float getDesiredWorldX() {
        return desiredWorldX;
    }

    public float getDesiredWorldY() {
        return desiredWorldY;
    }

    public float getDesiredWorldZ() {
        return desiredWorldZ;
    }

    public float getErrorDegrees() {
        return errorDegrees;
    }

    public float getPitchDegrees() {
        return pitchDegrees;
    }

    public void clear() {
        active = false;
        errorDegrees = 90.0F;
        pitchDegrees = 0.0F;
    }

    /** 以 {@code 90°/s -> 270°/s} 默认阻尼角速度追踪移动目标点（自动叠加 {@code 0.12s} 提前量）。 */
    public void aimAtTarget(
            float currentBodyYawRad,
            float selfX,
            float selfY,
            float selfZ,
            float targetX,
            float targetY,
            float targetZ,
            float targetVelX,
            float targetVelY,
            float maxRange,
            float dtSec) {
        aimAtTarget(
                currentBodyYawRad, selfX, selfY, selfZ,
                targetX, targetY, targetZ, targetVelX, targetVelY, false, maxRange, dtSec);
    }

    /** 以 {@code 90°/s -> 270°/s} 默认阻尼角速度追踪移动目标点（区分站立胸口 {@code 0.495F} 与倒地/爬行 {@code 0.22F} 高度）。 */
    public void aimAtTarget(
            float currentBodyYawRad,
            float selfX,
            float selfY,
            float selfZ,
            float targetX,
            float targetY,
            float targetZ,
            float targetVelX,
            float targetVelY,
            boolean targetProne,
            float maxRange,
            float dtSec) {
        float leadX = targetX + targetVelX * LEAD_TIME_SEC;
        float leadY = targetY + targetVelY * LEAD_TIME_SEC;
        float leadZ = targetZ + (targetProne ? PRONE_HEIGHT_OFFSET : CHEST_HEIGHT_OFFSET);
        updateInternal(currentBodyYawRad, selfX, selfY, selfZ, leadX, leadY, leadZ, maxRange, dtSec, -1.0F);
    }

    /** 指定角速度（如 CQB {@code 210°/s}）转向世界方位角 {@code desiredYawRad}。 */
    public void aimAtYaw(
            float currentBodyYawRad,
            float selfX,
            float selfY,
            float selfZ,
            float desiredYawRad,
            float probeDist,
            float maxSlewDegPerSec,
            float dtSec) {
        float dist = Math.max(1.5F, probeDist);
        float dx = (float) Math.cos(desiredYawRad) * dist;
        float dy = (float) Math.sin(desiredYawRad) * dist;
        updateInternal(
                currentBodyYawRad,
                selfX,
                selfY,
                selfZ,
                selfX + dx,
                selfY + dy,
                selfZ + CHEST_HEIGHT_OFFSET,
                dist,
                dtSec,
                maxSlewDegPerSec);
    }

    private void updateInternal(
            float currentBodyYawRad,
            float selfX,
            float selfY,
            float selfZ,
            float leadX,
            float leadY,
            float leadZ,
            float maxRange,
            float dtSec,
            float explicitDegPerSec) {
        if (!Float.isFinite(leadX) || !Float.isFinite(leadY) || !Float.isFinite(leadZ)) {
            return;
        }
        this.desiredWorldX = leadX;
        this.desiredWorldY = leadY;
        this.desiredWorldZ = leadZ;

        float dx = leadX - selfX;
        float dy = leadY - selfY;
        float horizDist = (float) Math.hypot(dx, dy);
        float desiredYawRad = (horizDist > 1.0E-4F)
                ? (float) Math.atan2(dy, dx)
                : normalizeRadians(currentBodyYawRad);

        if (!active) {
            this.aimYawRad = Float.isFinite(currentBodyYawRad)
                    ? normalizeRadians(currentBodyYawRad)
                    : desiredYawRad;
            this.active = true;
        }

        float delta = shortestAngleDelta(this.aimYawRad, desiredYawRad);
        float absErrDeg = (float) Math.toDegrees(Math.abs(delta));
        float slewDegPerSec;
        if (explicitDegPerSec > 0.0F) {
            slewDegPerSec = explicitDegPerSec;
        } else {
            float t = clamp((absErrDeg - 8.0F) / 52.0F, 0.0F, 1.0F);
            slewDegPerSec = MIN_SLEW_DEG_PER_SEC + (MAX_SLEW_DEG_PER_SEC - MIN_SLEW_DEG_PER_SEC) * t;
        }

        float stepRad = (float) Math.toRadians(slewDegPerSec) * clamp(dtSec, 0.004F, 0.1F);
        if (Math.abs(delta) <= stepRad) {
            this.aimYawRad = desiredYawRad;
        } else {
            this.aimYawRad = normalizeRadians(this.aimYawRad + Math.signum(delta) * stepRad);
        }

        this.aimDirX = (float) Math.cos(this.aimYawRad);
        this.aimDirY = (float) Math.sin(this.aimYawRad);
        float remErrDeg = (float) Math.toDegrees(Math.abs(shortestAngleDelta(this.aimYawRad, desiredYawRad)));
        this.errorDegrees = remErrDeg;

        float clampRange = Math.max(1.5F, maxRange > 0.0F ? maxRange : 12.0F);
        float effDist = clamp(horizDist, 1.0F, clampRange);
        this.worldX = selfX + this.aimDirX * effDist;
        this.worldY = selfY + this.aimDirY * effDist;
        this.worldZ = leadZ;

        float dz = leadZ - (selfZ + CHEST_HEIGHT_OFFSET);
        this.pitchDegrees = (float) Math.toDegrees(Math.atan2(dz, Math.max(0.35F, effDist)) * VERTICAL_PROJ_SCALE);
    }

    public static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    public static float normalizeRadians(float rad) {
        float r = rad;
        while (r > Math.PI) {
            r -= (float) (Math.PI * 2.0);
        }
        while (r < -Math.PI) {
            r += (float) (Math.PI * 2.0);
        }
        return r;
    }

    public static float shortestAngleDelta(float fromRad, float toRad) {
        return normalizeRadians(toRad - fromRad);
    }
}
