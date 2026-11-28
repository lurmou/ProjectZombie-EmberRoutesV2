package emberroutes.npc.combat;

/**
 * 纯 Java 近战选敌与 8 方位围攻站点选择器（合并原 {@code MeleeSlotAssigner} 公共结构并砍除双头调度黑板）。
 */
public final class MeleeTargetSelector {
    public static final int NUM_SLOTS = 8;
    public static final float RESERVE_RING_RADIUS = 2.60F;
    public static final float STICKY_BONUS_FACTOR = EmberSquadDirector.MELEE_STICKY_BONUS_FACTOR;

    private static final float[] SLOT_COS = new float[NUM_SLOTS];
    private static final float[] SLOT_SIN = new float[NUM_SLOTS];
    private static final float[] SUPPORT_OFFSETS_DEG = { 65.0F, 90.0F, 45.0F, 110.0F };
    private static final float[] PRIMARY_OFFSETS_DEG = { 0.0F, 35.0F, -35.0F };
    private static final float[] RESERVE_APPROACH_OFFSETS_DEG = { 0.0F, 32.0F, -32.0F, 64.0F, -64.0F };

    static {
        for (int i = 0; i < NUM_SLOTS; i++) {
            double ang = i * (Math.PI * 0.25);
            SLOT_COS[i] = (float) Math.cos(ang);
            SLOT_SIN[i] = (float) Math.sin(ang);
        }
    }

    @FunctionalInterface
    public interface MeleeBarrierProbe {
        boolean isBlockedByWindowOrFence(float x1, float y1, float x2, float y2, int z);
    }

    public static final class SelectedEngagement {
        public EmberSquadDirector.ThreatEntry threat;
        public int ringSlot;
        public int azimuthIdx;
        public float standX;
        public float standY;
        public float score;
    }

    private MeleeTargetSelector() {}

    public static int angleToAzimuthIndex(double angleRad) {
        double norm = angleRad;
        while (norm < 0.0) norm += Math.PI * 2.0;
        while (norm >= Math.PI * 2.0) norm -= Math.PI * 2.0;
        return Math.floorMod((int) Math.round(norm / (Math.PI * 0.25)), NUM_SLOTS);
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
        return Math.max(0, pickAzimuthLoop(npcX, npcY, targetX, targetY, radius, 0));
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

    public static EmberSquadDirector.ThreatEntry select(
            EmberSquadDirector director,
            String npcId,
            float npcX,
            float npcY,
            int npcZ,
            float desiredRadius,
            float groundSlotRadius,
            float maxSearchRadius,
            int currentTargetId,
            long nowMs,
            float[] allyXs,
            float[] allyYs,
            String[] allyIds,
            int allyCount,
            int extraRejectedAzimuthMask,
            boolean holdAnchorActive,
            float holdAnchorX,
            float holdAnchorY,
            float holdMaxRadius,
            CombatLocomotionSolver.SafeSideGuard guard,
            EmberSquadDirector.PassabilityProbe probe,
            MeleeBarrierProbe barrierProbe,
            boolean targetCommitActive,
            SelectedEngagement out) {
        out.threat = null;
        out.ringSlot = -1;
        out.azimuthIdx = 0;
        out.score = Float.MAX_VALUE;
        if (director == null) return null;

        boolean hasStandingAvailable = false;
        if (guard != null && guard.standingOverrideProne) {
            for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
                EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
                if (!t.active || !t.perceivedBySquad || Math.abs(t.z - npcZ) > 0.6F) continue;
                if (director.isUnreachable(npcId, t.zombieId, false, nowMs)) continue;
                if (CombatTargetState.isStanding(t.stateMask)
                        && Math.hypot(t.x - npcX, t.y - npcY) <= maxSearchRadius) {
                    hasStandingAvailable = true;
                    break;
                }
            }
        }

        float farStandingX = 0.0F;
        float farStandingY = 0.0F;
        float farStandingDistSq = Float.MAX_VALUE;
        boolean hasAnyStanding = false;
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || !t.perceivedBySquad || Math.abs(t.z - npcZ) > 0.6F) continue;
            if (!CombatTargetState.isStanding(t.stateMask)) continue;
            float dSq = (t.x - npcX) * (t.x - npcX) + (t.y - npcY) * (t.y - npcY);
            if (dSq < farStandingDistSq) {
                farStandingDistSq = dSq;
                farStandingX = t.x;
                farStandingY = t.y;
                hasAnyStanding = true;
            }
        }

        float bestScore = Float.MAX_VALUE;
        float bestDist = Float.MAX_VALUE;
        EmberSquadDirector.ThreatEntry bestThreat = null;
        int bestAz = 0;
        float bestSx = npcX;
        float bestSy = npcY;

        EmberSquadDirector.ThreatEntry currentCandidate = null;
        float curCandidateScore = Float.MAX_VALUE;
        float curCandidateDist = Float.MAX_VALUE;
        int curCandidateAz = 0;
        float curCandidateSx = npcX;
        float curCandidateSy = npcY;

        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (!t.active || !t.perceivedBySquad || Math.abs(t.z - npcZ) > 0.6F) continue;
            if (director.isUnreachable(npcId, t.zombieId, false, nowMs)) continue;
            if (holdAnchorActive && holdMaxRadius > 0.5F
                    && Math.hypot(t.x - holdAnchorX, t.y - holdAnchorY) > holdMaxRadius) {
                continue;
            }
            boolean isProneOrTransition = CombatTargetState.isProneOrCrawl(t.stateMask)
                    || CombatTargetState.isPostureTransition(t.stateMask);
            if (hasStandingAvailable && isProneOrTransition) {
                continue;
            }

            float anchorX = (CombatTargetState.isGroundAttackable(t.stateMask) && t.hasHeadPos) ? t.headX : t.x;
            float anchorY = (CombatTargetState.isGroundAttackable(t.stateMask) && t.hasHeadPos) ? t.headY : t.y;
            float dist = (float) Math.hypot(anchorX - npcX, anchorY - npcY);
            if (dist > maxSearchRadius) continue;

            float slotRadius = CombatTargetState.isGroundAttackable(t.stateMask)
                    ? groundSlotRadius
                    : desiredRadius;
            int rejectedMask = buildRejectedAzimuthMask(anchorX, anchorY, npcZ, slotRadius, probe);
            if (t.zombieId == currentTargetId) {
                rejectedMask |= extraRejectedAzimuthMask;
            }
            if (guard != null && guard.hasForbiddenHalfSpace) {
                for (int a = 0; a < NUM_SLOTS; a++) {
                    float cx = anchorX + SLOT_COS[a] * slotRadius;
                    float cy = anchorY + SLOT_SIN[a] * slotRadius;
                    if (guard.isPointInForbiddenPocket(cx, cy, -0.25F)) {
                        rejectedMask |= (1 << a);
                    }
                }
            }

            int az = pickSafeAzimuth(
                    npcX, npcY, anchorX, anchorY, slotRadius, rejectedMask,
                    CombatTargetState.isGroundAttackable(t.stateMask) && hasAnyStanding,
                    farStandingX, farStandingY);
            float sx = anchorX + SLOT_COS[az] * slotRadius;
            float sy = anchorY + SLOT_SIN[az] * slotRadius;

            float score = dist;
            if (t.attackedAllyPriority >= 2) {
                score *= 0.50F;
            } else if (t.attackedAllyPriority == 1) {
                if (npcId != null && npcId.equals(t.liveTargetEntityId)) {
                    score *= 0.68F;
                } else if (nowMs < t.distressPriorityUntilMs) {
                    score *= 0.75F;
                }
            }
            if (CombatTargetState.isSprinter(t.stateMask)) {
                score *= 0.78F;
            }
            if (CombatTargetState.isStaggered(t.stateMask)) {
                score *= 0.82F;
            }
            if (CombatTargetState.isGroundAttackable(t.stateMask)
                    && (guard == null || !guard.standingOverrideProne)) {
                score *= (dist <= 4.5F) ? 0.62F : 0.82F;
            }
            if (t.zombieId == currentTargetId) {
                score *= STICKY_BONUS_FACTOR;
            }
            if (barrierProbe != null && barrierProbe.isBlockedByWindowOrFence(npcX, npcY, t.x, t.y, npcZ)) {
                score += 12.0F;
            }

            boolean alreadyMember = t.ringTable.slotOf(npcId) >= 0;
            if (!alreadyMember) {
                int claimedCount = t.ringTable.activeCount();
                if (claimedCount >= EmberSquadDirector.MeleeRingTokenTable.ACTIVE_CAPACITY) {
                    score += EmberSquadDirector.MELEE_CLAIM_PENALTY;
                } else if (claimedCount >= 1 && dist > 1.60F) {
                    // 多 NPC 面对多尸时优先分摊各自盯紧一只空闲僵尸，仅单敌或贴身才合围
                    score += 8.0F;
                }
            }

            if (t.zombieId == currentTargetId) {
                currentCandidate = t;
                curCandidateScore = score;
                curCandidateDist = dist;
                curCandidateAz = az;
                curCandidateSx = sx;
                curCandidateSy = sy;
            }

            if (score < bestScore) {
                bestScore = score;
                bestDist = dist;
                bestThreat = t;
                bestAz = az;
                bestSx = sx;
                bestSy = sy;
            }
        }

        if (bestThreat == null) {
            return null;
        }

        if (targetCommitActive && currentCandidate != null && bestThreat != currentCandidate) {
            boolean immediateStandingEmergency = CombatTargetState.isStanding(bestThreat.stateMask)
                    && bestDist < 1.10F
                    && bestDist + 0.35F < curCandidateDist;
            if (!immediateStandingEmergency) {
                bestThreat = currentCandidate;
                bestScore = curCandidateScore;
                bestAz = curCandidateAz;
                bestSx = curCandidateSx;
                bestSy = curCandidateSy;
            }
        }

        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry t = director.getThreatAt(i);
            if (t.active && t != bestThreat) {
                t.ringTable.release(npcId);
            }
        }

        int claimedSlot = bestThreat.ringTable.tryClaim(npcId, nowMs);
        float anchorX = (CombatTargetState.isGroundAttackable(bestThreat.stateMask) && bestThreat.hasHeadPos)
                ? bestThreat.headX : bestThreat.x;
        float anchorY = (CombatTargetState.isGroundAttackable(bestThreat.stateMask) && bestThreat.hasHeadPos)
                ? bestThreat.headY : bestThreat.y;
        float slotRadius = CombatTargetState.isGroundAttackable(bestThreat.stateMask)
                ? groundSlotRadius
                : desiredRadius;

        double approachAng = Math.atan2(npcY - anchorY, npcX - anchorX);
        float sideSign = ((npcId != null ? npcId.hashCode() : 0) & 1) == 0 ? 1.0F : -1.0F;
        int effExtraRejectMask = (bestThreat.zombieId == currentTargetId) ? extraRejectedAzimuthMask : 0;

        if (claimedSlot >= 0) {
            boolean isPrimary = (claimedSlot == EmberSquadDirector.MeleeRingTokenTable.SLOT_PRIMARY);
            float[] offsets = isPrimary ? PRIMARY_OFFSETS_DEG : SUPPORT_OFFSETS_DEG;
            double baseAng = approachAng;
            if (!isPrimary) {
                String primaryHolder = bestThreat.ringTable.getPrimaryHolder();
                int pIdx = findAllyIndex(primaryHolder, npcId, allyIds, allyCount);
                if (pIdx >= 0 && allyXs != null && allyYs != null) {
                    baseAng = Math.atan2(allyYs[pIdx] - anchorY, allyXs[pIdx] - anchorX);
                }
            }
            float slotSide = (claimedSlot == EmberSquadDirector.MeleeRingTokenTable.SLOT_FLANK_LEFT)
                    ? 1.0F
                    : ((claimedSlot == EmberSquadDirector.MeleeRingTokenTable.SLOT_FLANK_RIGHT) ? -1.0F : sideSign);
            float bestPosScore = -Float.MAX_VALUE;
            float chosenX = bestSx;
            float chosenY = bestSy;
            boolean found = false;
            for (int idx = 0; idx < offsets.length; idx++) {
                double ang = baseAng + slotSide * Math.toRadians(offsets[idx]);
                if (effExtraRejectMask != 0 && ((effExtraRejectMask & (1 << angleToAzimuthIndex(ang))) != 0)) {
                    continue;
                }
                float cx = anchorX + (float) Math.cos(ang) * slotRadius;
                float cy = anchorY + (float) Math.sin(ang) * slotRadius;
                if (guard != null && guard.isPointInForbiddenPocket(cx, cy, -0.25F)) {
                    continue;
                }
                if (probe != null && !probe.isLineClear(anchorX, anchorY, cx, cy, npcZ)) {
                    continue;
                }
                float posScore = scoreCandidate(
                        director, npcX, npcY, npcZ, bestThreat, cx, cy, idx, allyXs, allyYs, allyCount,
                        CombatTargetState.isGroundAttackable(bestThreat.stateMask) && hasAnyStanding,
                        farStandingX, farStandingY, probe);
                if (!found || posScore > bestPosScore) {
                    bestPosScore = posScore;
                    chosenX = cx;
                    chosenY = cy;
                    bestAz = angleToAzimuthIndex(ang);
                    found = true;
                }
            }
            if (found) {
                bestSx = chosenX;
                bestSy = chosenY;
            }
        } else {
            float reserveRadius = Math.max(RESERVE_RING_RADIUS, desiredRadius + 2.2F);
            int len = RESERVE_APPROACH_OFFSETS_DEG.length;
            int baseIdx = Math.floorMod(npcId != null ? npcId.hashCode() : 0, len);
            float bestPosScore = -Float.MAX_VALUE;
            float chosenX = anchorX + (float) Math.cos(approachAng) * reserveRadius;
            float chosenY = anchorY + (float) Math.sin(approachAng) * reserveRadius;
            boolean found = false;
            for (int k = 0; k < len; k++) {
                float deg = RESERVE_APPROACH_OFFSETS_DEG[(baseIdx + k) % len];
                double ang = approachAng + Math.toRadians(deg);
                float rx = anchorX + (float) Math.cos(ang) * reserveRadius;
                float ry = anchorY + (float) Math.sin(ang) * reserveRadius;
                if (guard != null && guard.isPointInForbiddenPocket(rx, ry, -0.25F)) {
                    continue;
                }
                if (probe != null && !probe.isLineClear(anchorX, anchorY, rx, ry, npcZ)) {
                    continue;
                }
                float posScore = scoreCandidate(
                        director, npcX, npcY, npcZ, bestThreat, rx, ry, k, allyXs, allyYs, allyCount,
                        false, 0.0F, 0.0F, probe);
                if (!found || posScore > bestPosScore) {
                    bestPosScore = posScore;
                    chosenX = rx;
                    chosenY = ry;
                    found = true;
                }
            }
            bestSx = chosenX;
            bestSy = chosenY;
        }

        out.threat = bestThreat;
        out.ringSlot = claimedSlot;
        out.azimuthIdx = bestAz;
        out.standX = bestSx;
        out.standY = bestSy;
        out.score = bestScore;
        return bestThreat;
    }

    private static int pickSafeAzimuth(
            float npcX,
            float npcY,
            float anchorX,
            float anchorY,
            float radius,
            int rejectedMask,
            boolean avoidFarStandingSide,
            float farStandingX,
            float farStandingY) {
        int bestIdx = -1;
        float bestCost = Float.MAX_VALUE;
        float fsUx = 0.0F;
        float fsUy = 0.0F;
        boolean hasDir = false;
        if (avoidFarStandingSide) {
            float fdx = farStandingX - anchorX;
            float fdy = farStandingY - anchorY;
            float fd = (float) Math.hypot(fdx, fdy);
            if (fd > 0.2F) {
                fsUx = fdx / fd;
                fsUy = fdy / fd;
                hasDir = true;
            }
        }
        for (int a = 0; a < NUM_SLOTS; a++) {
            if ((rejectedMask & (1 << a)) != 0) continue;
            float sx = anchorX + SLOT_COS[a] * radius;
            float sy = anchorY + SLOT_SIN[a] * radius;
            float cost = (sx - npcX) * (sx - npcX) + (sy - npcY) * (sy - npcY);
            if (hasDir) {
                float dot = SLOT_COS[a] * fsUx + SLOT_SIN[a] * fsUy;
                if (dot > 0.1F) {
                    cost += dot * 2.5F;
                }
            }
            if (cost < bestCost) {
                bestCost = cost;
                bestIdx = a;
            }
        }
        if (bestIdx >= 0) return bestIdx;
        return pickBestAzimuth(npcX, npcY, anchorX, anchorY, radius, 0);
    }

    private static float scoreCandidate(
            EmberSquadDirector director,
            float npcX,
            float npcY,
            int npcZ,
            EmberSquadDirector.ThreatEntry mainThreat,
            float cx,
            float cy,
            int orderIdx,
            float[] allyXs,
            float[] allyYs,
            int allyCount,
            boolean avoidFarStandingSide,
            float farStandingX,
            float farStandingY,
            EmberSquadDirector.PassabilityProbe probe) {
        float score = -((float) Math.hypot(cx - npcX, cy - npcY)) - orderIdx * 0.35F;
        if (probe == null || probe.isLineClear(npcX, npcY, cx, cy, npcZ)) {
            score += 1.5F;
        }
        if (avoidFarStandingSide) {
            float bdx = farStandingX - mainThreat.x;
            float bdy = farStandingY - mainThreat.y;
            float bd = (float) Math.hypot(bdx, bdy);
            float cdx = cx - mainThreat.x;
            float cdy = cy - mainThreat.y;
            float cd = (float) Math.hypot(cdx, cdy);
            if (bd > 0.2F && cd > 0.1F) {
                float dot = (cdx / cd) * (bdx / bd) + (cdy / cd) * (bdy / bd);
                if (dot > 0.15F) {
                    score -= dot * 2.2F;
                }
            }
        }
        for (int i = 0; i < EmberSquadDirector.MAX_THREATS; i++) {
            EmberSquadDirector.ThreatEntry other = director.getThreatAt(i);
            if (!other.active || other == mainThreat || Math.abs(other.z - npcZ) > 0.6F) {
                continue;
            }
            float d = (float) Math.hypot(cx - other.x, cy - other.y);
            if (d < 2.5F) {
                score -= (2.5F - d) * 6.0F;
            }
        }
        if (allyXs != null && allyYs != null && allyCount > 0) {
            int n = Math.min(allyCount, Math.min(allyXs.length, allyYs.length));
            for (int i = 0; i < n; i++) {
                float d = (float) Math.hypot(cx - allyXs[i], cy - allyYs[i]);
                if (d < 1.05F) {
                    score -= (1.05F - d) * 8.0F;
                }
            }
        }
        return score;
    }

    private static int findAllyIndex(String targetNpcId, String selfNpcId, String[] allyIds, int allyCount) {
        if (targetNpcId == null || targetNpcId.isEmpty() || targetNpcId.equals(selfNpcId) || allyIds == null) {
            return -1;
        }
        int n = Math.min(allyCount, allyIds.length);
        for (int i = 0; i < n; i++) {
            if (targetNpcId.equals(allyIds[i])) {
                return i;
            }
        }
        return -1;
    }
}
