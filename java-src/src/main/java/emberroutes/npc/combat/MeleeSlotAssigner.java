package emberroutes.npc.combat;

/**
 * 第 4 号战斗微操内核：{@code MeleeSlotAssigner}（8 方位环形站位分配 + {@code MELEE_CLAIM_PENALTY = 1000.0} 转火分流）。
 *
 * <ul>
 *   <li>围绕每只威胁维护 8 个 45° 方位站位候选点（结合 {@code MeleeRingTokenTable} 区分主攻 {@code 0°} 与侧翼 {@code ±30°/±60°}）；</li>
 *   <li>当目标被 {@code 3} 名友军占满（返回槽 {@code -1}）时，候选评分自动叠加 {@code MELEE_CLAIM_PENALTY = 1000.0}
 *       强制第 4 名近战转火次优威胁，避免所有人挤死同一只丧尸；</li>
 *   <li>对墙体/障碍物方向自动打入被拒掩码，支持 {@code -18%} 粘滞偏置防环绕鬼畜抖动。</li>
 * </ul>
 */
public final class MeleeSlotAssigner {
    public static final int NUM_SLOTS = 8;
    public static final float MELEE_CLAIM_PENALTY = EmberSquadDirector.MELEE_CLAIM_PENALTY;
    public static final float STICKY_BONUS_FACTOR = EmberSquadDirector.MELEE_STICKY_BONUS_FACTOR;
    private static final float[] SLOT_COS = new float[NUM_SLOTS];
    private static final float[] SLOT_SIN = new float[NUM_SLOTS];

    static {
        for (int i = 0; i < NUM_SLOTS; i++) {
            double ang = i * (Math.PI * 0.25);
            SLOT_COS[i] = (float) Math.cos(ang);
            SLOT_SIN[i] = (float) Math.sin(ang);
        }
    }

    public static final class SelectedEngagement {
        public EmberSquadDirector.ThreatEntry threat;
        public int ringSlot;       // 0=主攻, 1..2=侧翼, -1=外圈待命
        public int azimuthIdx;     // 0..7
        public float standX;
        public float standY;
        public float score;
    }

    private MeleeSlotAssigner() {}

    /**
     * 为近战 NPC 选出最优目标与无碰撞 8 方位围攻站点；支持脱机单测。
     */
    public static EmberSquadDirector.ThreatEntry selectThreatAndSlot(
            EmberSquadDirector director,
            String npcId,
            float npcX,
            float npcY,
            int npcZ,
            float desiredRadius,
            float maxSearchRadius,
            int currentTargetId,
            long nowMs,
            EmberSquadDirector.PassabilityProbe probe,
            SelectedEngagement out) {
        out.threat = null;
        out.ringSlot = -1;
        out.azimuthIdx = 0;
        out.score = Float.MAX_VALUE;
        if (director == null) return null;

        float bestScore = Float.MAX_VALUE;
        EmberSquadDirector.ThreatEntry bestThreat = null;
        int bestAz = 0;
        float bestSx = npcX;
        float bestSy = npcY;

        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || Math.abs(t.z - npcZ) > 0.6F) continue;
            float dist = (float) Math.hypot(t.x - npcX, t.y - npcY);
            if (dist > maxSearchRadius) continue;

            int rejectedMask = buildRejectedAzimuthMask(t.x, t.y, npcZ, desiredRadius, probe);
            int az = pickBestAzimuth(npcX, npcY, t.x, t.y, desiredRadius, rejectedMask);
            float sx = t.x + SLOT_COS[az] * desiredRadius;
            float sy = t.y + SLOT_SIN[az] * desiredRadius;

            float score = dist;
            // 优先压倒已硬直或倒地威胁
            if (CombatTargetState.isProneOrCrawl(t.stateMask)
                    || CombatTargetState.isPostureTransition(t.stateMask)
                    || CombatTargetState.isStaggered(t.stateMask)) {
                score *= 0.85F;
            }
            if (t.zombieId == currentTargetId) {
                score *= STICKY_BONUS_FACTOR;
            }

            // 已被 3 个队友占满且自己不在表中 → 叠加 1000.0 转火罚分
            boolean alreadyMember = t.ringTable.slotOf(npcId) >= 0;
            if (!alreadyMember && t.ringTable.activeCount() >= EmberSquadDirector.MeleeRingTokenTable.ACTIVE_CAPACITY) {
                score += MELEE_CLAIM_PENALTY;
            }

            if (score < bestScore) {
                bestScore = score;
                bestThreat = t;
                bestAz = az;
                bestSx = sx;
                bestSy = sy;
            }
        }

        if (bestThreat == null) {
            return null;
        }

        // 先释放其他威胁上的旧槽位，再申领新威胁的近战环形令牌
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (t.active && t != bestThreat) {
                if (t.ringTable.isLockedFor(npcId, nowMs) && bestScore < MELEE_CLAIM_PENALTY * 0.5F) {
                    // 只有新目标明显更优（非满员溢价）时才主动放弃锁定
                    t.ringTable.release(npcId);
                } else {
                    t.ringTable.release(npcId);
                }
            }
        }

        int claimedSlot = bestThreat.ringTable.tryClaim(npcId, nowMs);
        float angleOffsetRad = switch (claimedSlot) {
            case EmberSquadDirector.MeleeRingTokenTable.SLOT_FLANK_LEFT -> (float) Math.toRadians(38.0);
            case EmberSquadDirector.MeleeRingTokenTable.SLOT_FLANK_RIGHT -> (float) Math.toRadians(-38.0);
            default -> 0.0F;
        };
        if (claimedSlot > 0) {
            double ang = Math.atan2(bestSy - bestThreat.y, bestSx - bestThreat.x) + angleOffsetRad;
            float r = claimedSlot < 0 ? (desiredRadius + 1.15F) : desiredRadius;
            float fx = bestThreat.x + (float) Math.cos(ang) * r;
            float fy = bestThreat.y + (float) Math.sin(ang) * r;
            if (probe == null || probe.isLineClear(bestThreat.x, bestThreat.y, fx, fy, npcZ)) {
                bestSx = fx;
                bestSy = fy;
            }
        } else if (claimedSlot < 0) {
            // 外圈预备待命站位
            float ux = bestSx - bestThreat.x;
            float uy = bestSy - bestThreat.y;
            float ul = (float) Math.hypot(ux, uy);
            if (ul > 1.0E-4F) {
                bestSx = bestThreat.x + (ux / ul) * (desiredRadius + 1.15F);
                bestSy = bestThreat.y + (uy / ul) * (desiredRadius + 1.15F);
            }
        }

        out.threat = bestThreat;
        out.ringSlot = claimedSlot;
        out.azimuthIdx = bestAz;
        out.standX = bestSx;
        out.standY = bestSy;
        out.score = bestScore;
        return bestThreat;
    }

    public static int buildRejectedAzimuthMask(
            float targetX, float targetY, int z, float radius, EmberSquadDirector.PassabilityProbe probe) {
        if (probe == null) return 0;
        int mask = 0;
        for (int a = 0; a < NUM_SLOTS; a++) {
            float sx = targetX + SLOT_COS[a] * radius;
            float sy = targetY + SLOT_SIN[a] * radius;
            if (!probe.isLineClear(targetX, targetY, sx, sy, z)) {
                mask |= (1 << a);
            }
        }
        return mask;
    }

    public static int pickBestAzimuth(
            float npcX, float npcY, float targetX, float targetY, float radius, int rejectedMask) {
        int idx = pickAzimuthLoop(npcX, npcY, targetX, targetY, radius, rejectedMask);
        if (idx >= 0) {
            return idx;
        }
        int fallback = pickAzimuthLoop(npcX, npcY, targetX, targetY, radius, 0);
        return Math.max(0, fallback);
    }

    private static int pickAzimuthLoop(
            float npcX, float npcY, float targetX, float targetY, float radius, int rejectedMask) {
        int bestIdx = -1;
        float bestDistSq = Float.MAX_VALUE;
        for (int a = 0; a < NUM_SLOTS; a++) {
            if ((rejectedMask & (1 << a)) != 0) continue;
            float sx = targetX + SLOT_COS[a] * radius;
            float sy = targetY + SLOT_SIN[a] * radius;
            float dSq = (sx - npcX) * (sx - npcX) + (sy - npcY) * (sy - npcY);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestIdx = a;
            }
        }
        return bestIdx;
    }
}
