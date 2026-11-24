package emberroutes.npc.combat;

/**
 * 第 2 号战斗微操内核：{@code SpacingController}（近战连续风筝间距控制、爬行者排斥、切向墙避与射界走廊融合）。
 *
 * <ol>
 *   <li><b>0.25s 速度提前量预测</b>：预判目标 {@code (x + vx*0.25, y + vy*0.25)} 位置消除延迟；</li>
 *   <li><b>攻击冷却期动态死区外推 {@code +0.38} 格</b>：挥击刚结束/冷却中自动微退拉扯防吃抓咬；</li>
 *   <li><b>{@code < 2.6} 格爬行者反距离排斥</b>：防止被脚边爬行尸抱腿绊倒；</li>
 *   <li><b>{@code 1.5} 格 {@code wallAvoid} 切向滑移</b>：退到墙边时沿墙面切线滑动，绝不后退贴墙死锁；</li>
 *   <li><b>叠加 {@code FiringCorridor} 侧向让路斥力与窄走廊净空门控</b>。</li>
 * </ol>
 */
public final class SpacingController {
    public static final float TARGET_LEAD_SEC = 0.25F;
    public static final float COOLDOWN_PUSHOUT_TILES = 0.38F;
    public static final float CRAWLER_REPULSION_RADIUS = 2.6F;
    public static final float WALL_AVOID_RADIUS = 1.5F;

    public static final class MoveCommand {
        public float dirX;
        public float dirY;
        public boolean shouldMove;
        public boolean runGear;
        public boolean aimStrafe;
        public final EmberSquadDirector.CorridorAvoidanceResult corridor =
                new EmberSquadDirector.CorridorAvoidanceResult();

        public void reset() {
            dirX = 0.0F;
            dirY = 0.0F;
            shouldMove = false;
            runGear = false;
            aimStrafe = true;
            corridor.reset();
        }
    }

    private SpacingController() {}

    /**
     * 计算近战接战/拉扯本帧归一化世界移动矢量（纯函数可脱机单测）。
     *
     * @param desiredRange 武器理想近战打击距离（通常 {@code clamp(maxRange * 0.80, 0.75, 1.45)}）
     * @param attackOnCooldown true 表示刚出刀或 {@code meleeDelay > 0}，触发 {@code +0.38} 格动态后撤拉扯
     */
    public static void computeMeleeStep(
            float npcX,
            float npcY,
            int npcZ,
            String npcId,
            float slotX,
            float slotY,
            float targetX,
            float targetY,
            float targetVx,
            float targetVy,
            int targetMask,
            float desiredRange,
            boolean attackOnCooldown,
            EmberSquadDirector director,
            EmberSquadDirector.PassabilityProbe probe,
            MoveCommand out) {
        out.reset();
        float predX = targetX + targetVx * TARGET_LEAD_SEC;
        float predY = targetY + targetVy * TARGET_LEAD_SEC;
        float dxT = predX - npcX;
        float dyT = predY - npcY;
        float distT = (float) Math.hypot(dxT, dyT);

        // 将目标位同步平移到预测位置（若槽位直接贴目标），并叠加冷却期 +0.38 格动态死区外推
        float effRange = desiredRange + (attackOnCooldown ? COOLDOWN_PUSHOUT_TILES : 0.0F);
        if (CombatTargetState.isCrawler(targetMask)) {
            effRange = Math.max(effRange, Math.min(1.55F, desiredRange + 0.30F));
        }

        float vecX = 0.0F;
        float vecY = 0.0F;

        float anchorDx = (slotX + targetVx * TARGET_LEAD_SEC) - npcX;
        float anchorDy = (slotY + targetVy * TARGET_LEAD_SEC) - npcY;
        float anchorDist = (float) Math.hypot(anchorDx, anchorDy);

        if (attackOnCooldown && distT < effRange - 0.08F && distT > 1.0E-4F) {
            // 冷却期内与目标过近：向背离预测目标方向外推
            float pushStrength = Math.min(1.0F, (effRange - distT) * 1.4F + 0.35F);
            vecX -= (dxT / distT) * pushStrength;
            vecY -= (dyT / distT) * pushStrength;
        } else if (distT > desiredRange + 0.12F || anchorDist > 0.32F) {
            // 靠近最优 8 向围攻站位或追近理想攻击圈
            float gx = (distT > desiredRange + 0.12F && anchorDist < 0.25F) ? predX : (slotX + targetVx * TARGET_LEAD_SEC);
            float gy = (distT > desiredRange + 0.12F && anchorDist < 0.25F) ? predY : (slotY + targetVy * TARGET_LEAD_SEC);
            float gdX = gx - npcX;
            float gdY = gy - npcY;
            float gd = (float) Math.hypot(gdX, gdY);
            if (gd > 0.12F) {
                vecX += gdX / gd;
                vecY += gdY / gd;
            }
        } else if (distT < desiredRange - 0.28F && distT > 1.0E-4F) {
            vecX -= (dxT / distT) * 0.65F;
            vecY -= (dyT / distT) * 0.65F;
        }

        // 第 2.5 子规则：< 2.6 格爬行者反距离排斥（防脚边扑咬）
        if (director != null) {
            applyCrawlerRepulsion(npcX, npcY, npcZ, director, vecOutTmp);
            vecX += vecOutTmp[0];
            vecY += vecOutTmp[1];

            // 射界走廊斥力 + 横向净空门控
            director.evaluateCorridorAvoidance(npcX, npcY, npcZ, npcId, distT, probe, out.corridor);
            if (out.corridor.inCorridor) {
                if (out.corridor.meleePressAhead && distT > 1.0E-4F) {
                    vecX += (dxT / distT) * 0.55F;
                    vecY += (dyT / distT) * 0.55F;
                } else {
                    vecX += out.corridor.pushX;
                    vecY += out.corridor.pushY;
                }
            }
        }

        // 1.5 格 wallAvoid 切向防卡死滑移
        applyWallAvoidTangentialSlide(npcX, npcY, npcZ, vecX, vecY, probe, vecOutTmp);
        vecX = vecOutTmp[0];
        vecY = vecOutTmp[1];

        float mag = (float) Math.hypot(vecX, vecY);
        if (mag > 0.12F && Float.isFinite(mag)) {
            out.shouldMove = true;
            out.dirX = vecX / mag;
            out.dirY = vecY / mag;
            out.runGear = distT > 3.6F && !attackOnCooldown;
            out.aimStrafe = distT <= 3.6F || attackOnCooldown;
        } else {
            out.shouldMove = false;
            out.aimStrafe = true;
        }
    }

    private static final float[] vecOutTmp = new float[2];

    public static void applyCrawlerRepulsion(
            float npcX, float npcY, int npcZ, EmberSquadDirector director, float[] outXY) {
        float rx = 0.0F;
        float ry = 0.0F;
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || Math.abs(t.z - npcZ) > 0.6F || !CombatTargetState.isCrawler(t.stateMask)) {
                continue;
            }
            float dx = npcX - t.x;
            float dy = npcY - t.y;
            float distSq = dx * dx + dy * dy;
            if (distSq > CRAWLER_REPULSION_RADIUS * CRAWLER_REPULSION_RADIUS || distSq < 1.0E-4F) {
                continue;
            }
            float dist = (float) Math.sqrt(distSq);
            float weight = (CRAWLER_REPULSION_RADIUS - dist) / CRAWLER_REPULSION_RADIUS;
            rx += (dx / dist) * weight * 0.90F;
            ry += (dy / dist) * weight * 0.90F;
        }
        outXY[0] = rx;
        outXY[1] = ry;
    }

    /**
     * 1.5 格 {@code wallAvoid} 切向防卡死滑移：如果预期移动方向受墙阻塞，剔除指向墙体的法向分量并保留沿墙切向分力。
     */
    public static void applyWallAvoidTangentialSlide(
            float npcX,
            float npcY,
            int npcZ,
            float moveX,
            float moveY,
            EmberSquadDirector.PassabilityProbe probe,
            float[] outXY) {
        float len = (float) Math.hypot(moveX, moveY);
        if (probe == null || len < 1.0E-4F) {
            outXY[0] = moveX;
            outXY[1] = moveY;
            return;
        }
        float dirX = moveX / len;
        float dirY = moveY / len;
        if (probe.isLineClear(npcX, npcY, npcX + dirX * WALL_AVOID_RADIUS, npcY + dirY * WALL_AVOID_RADIUS, npcZ)) {
            outXY[0] = moveX;
            outXY[1] = moveY;
            return;
        }

        // 采样 8 向墙壁阻塞合力作为墙内指向，剔除朝向阻塞面的分量实现切向顺墙滑移
        float blockX = 0.0F;
        float blockY = 0.0F;
        for (int a = 0; a < 8; a++) {
            double ang = a * (Math.PI * 0.25);
            float ux = (float) Math.cos(ang);
            float uy = (float) Math.sin(ang);
            if (!probe.isLineClear(npcX, npcY, npcX + ux * WALL_AVOID_RADIUS, npcY + uy * WALL_AVOID_RADIUS, npcZ)) {
                blockX += ux;
                blockY += uy;
            }
        }
        float blockLen = (float) Math.hypot(blockX, blockY);
        if (blockLen > 1.0E-4F) {
            float nx = blockX / blockLen;
            float ny = blockY / blockLen;
            float intoWall = moveX * nx + moveY * ny;
            if (intoWall > 0.0F) {
                moveX -= nx * intoWall * 1.15F;
                moveY -= ny * intoWall * 1.15F;
            }
            // 若完全正对墙壁（抵消后几乎归零），按叉乘偏向切线绕行一侧
            if (Math.hypot(moveX, moveY) < 0.15F) {
                float tx1 = -ny;
                float ty1 = nx;
                if (probe.isLineClear(npcX, npcY, npcX + tx1 * WALL_AVOID_RADIUS, npcY + ty1 * WALL_AVOID_RADIUS, npcZ)) {
                    moveX = tx1 * 0.75F;
                    moveY = ty1 * 0.75F;
                } else if (probe.isLineClear(npcX, npcY, npcX - tx1 * WALL_AVOID_RADIUS, npcY - ty1 * WALL_AVOID_RADIUS, npcZ)) {
                    moveX = -tx1 * 0.75F;
                    moveY = -ty1 * 0.75F;
                }
            }
        }
        outXY[0] = moveX;
        outXY[1] = moveY;
    }
}
