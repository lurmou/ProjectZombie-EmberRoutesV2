package emberroutes.npc.combat;

/**
 * 第 6B 号战斗微操内核：{@code AiAimingReadiness}（原版缩圈就绪判定 + {@code ±12°} 友军安全锥开火门控，零 {@code zombie.*} 依赖）。
 *
 * <p><b>B42 全链路核实对齐</b>：{@code IsoPlayer.updateInternal2:2473} 与 {@code IsoGameCharacter.updateInternal:9575}
 * 已每帧为 {@code IsoNPC} 原生衰减 {@code aimingDelay / recoilDelay / meleeDelay}；
 * 本类<b>绝不重复递减</b>上述延迟（防止出现 2 倍速作弊缩圈），仅结合目标距离急迫度与 {@code NpcVirtualAim} 跟踪误差计算开火许可。</p>
 */
public final class AiAimingReadiness {
    public static final float FRIENDLY_SAFETY_CONE_DEG = 12.0F;
    public static final float MIN_ALLY_CONE_DIST = 0.35F;

    private AiAimingReadiness() {}

    /** 纯函数判定是否已通过瞄准缩圈与角误差门限（供单测及运行期共享）。 */
    public static boolean isAimReady(
            float engineAimingDelay,
            float engineRecoilDelay,
            float virtualAimErrorDeg,
            float targetDistTiles,
            float maxWeaponRange,
            boolean shotgun) {
        return isAimReady(
                engineAimingDelay, engineRecoilDelay, virtualAimErrorDeg,
                targetDistTiles, maxWeaponRange, shotgun, 0.0F);
    }

    /** 结合引擎 {@code beenMovingFor} 动态收紧中远距移动射击精度门控。 */
    public static boolean isAimReady(
            float engineAimingDelay,
            float engineRecoilDelay,
            float virtualAimErrorDeg,
            float targetDistTiles,
            float maxWeaponRange,
            boolean shotgun,
            float beenMovingFor) {
        if (engineRecoilDelay > 0.5F) {
            return false;
        }
        float normRange = maxWeaponRange > 0.5F
                ? Math.max(0.0F, Math.min(1.0F, targetDistTiles / maxWeaponRange))
                : 0.5F;
        // 贴脸高急迫度允许更快甩枪扣扳机，远距离要求更大程度等待原生 aimingDelay 缩圈完成
        float allowedAimingDelay = (targetDistTiles <= 2.4F)
                ? (shotgun ? 14.0F : 9.0F)
                : (3.5F * (1.0F - normRange * 0.72F));
        float maxAngleErrDeg = (targetDistTiles <= 2.6F)
                ? (shotgun ? 14.0F : 10.0F)
                : (shotgun ? 9.0F : Math.max(3.2F, 6.5F * (1.0F - normRange * 0.45F)));
        if (beenMovingFor > 0.15F && targetDistTiles > 3.0F) {
            allowedAimingDelay *= 0.75F;
            maxAngleErrDeg *= 0.85F;
        }
        if (engineAimingDelay > allowedAimingDelay) {
            return false;
        }
        return virtualAimErrorDeg <= maxAngleErrDeg;
    }

    /**
     * {@code ±12°} 友军安全射界锥检查（C7 硬免伤之外的战术停火礼仪，避免射手浪费弹药往队友背上打）。
     */
    public static boolean isBlockedByFriendlyCone(
            float shooterX,
            float shooterY,
            float aimDirX,
            float aimDirY,
            float targetDist,
            float[] allyXs,
            float[] allyYs,
            int allyCount) {
        float cosHalfCone = (float) Math.cos(Math.toRadians(FRIENDLY_SAFETY_CONE_DEG));
        float maxCheckDist = targetDist + 0.65F;
        for (int i = 0; i < allyCount; i++) {
            if (isPointInsideCone(shooterX, shooterY, aimDirX, aimDirY, cosHalfCone, maxCheckDist, allyXs[i], allyYs[i])) {
                return true;
            }
        }
        return false;
    }

    /** 单个友军坐标是否落入射手前方 {@code ±12°} 安全锥内。 */
    public static boolean isPointInsideCone(
            float shooterX,
            float shooterY,
            float aimDirX,
            float aimDirY,
            float cosHalfCone,
            float maxCheckDist,
            float allyX,
            float allyY) {
        float dx = allyX - shooterX;
        float dy = allyY - shooterY;
        float distSq = dx * dx + dy * dy;
        if (distSq < MIN_ALLY_CONE_DIST * MIN_ALLY_CONE_DIST || distSq > maxCheckDist * maxCheckDist) {
            return false;
        }
        float dist = (float) Math.sqrt(distSq);
        float dot = (dx / dist) * aimDirX + (dy / dist) * aimDirY;
        return dot >= cosHalfCone;
    }
}
