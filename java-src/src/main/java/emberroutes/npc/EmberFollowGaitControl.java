package emberroutes.npc;

/**
 * 纯内存跟随变速箱与闭环速度匹配控制器（零 {@code zombie.*} 依赖）：
 * - 基于领队平滑真实地速 + 沿轨迹位置误差输出连续期望地速 {@code vCmd}；
 * - 彻底脱钩领队瞬时 Shift 按键脉冲，仅由平滑地速与距离滞回双阈值驱动 {@code WALK / RUN / SPRINT}（并带确认窗口与最小驻留锁）；
 * - 将连续驱动量 {@code speedDrive ∈ [0.50, 2.00]} 拆分为两段无级执行：
 *   {@code [1.00, 2.00] -> WalkSpeed ∈ [0.00, 1.00]}（全速到 {@code Bob_WalkSlow}），
 *   {@code [0.50, 1.00] -> WalkSpeed = 0.00 + trackSpeedScale ∈ [0.50, 1.00]}（低于最慢步态时的根位移同比例缩速）。
 */
public final class EmberFollowGaitControl {
    public enum Gear {
        SNEAK,
        WALK,
        RUN,
        SPRINT
    }

    static final float EMA_TAU_LEADER_UP = 0.28F;
    static final float EMA_TAU_LEADER_DOWN = 0.35F;
    static final float EMA_TAU_NPC = 0.10F;
    static final float KP_POS_ERROR = 0.85F;

    static final long SNEAK_DEBOUNCE_MS = 220L;
    static final long GEAR_MIN_DWELL_MS = 500L;
    static final long UPSHIFT_RUN_CONFIRM_MS = 220L;
    static final long DOWNSHIFT_WALK_CONFIRM_MS = 280L;
    static final long UPSHIFT_SPRINT_CONFIRM_MS = 300L;
    static final long DOWNSHIFT_RUN_CONFIRM_MS = 260L;

    static final float CATCHUP_DIRECT_ENTER = 8.0F;
    static final float CATCHUP_DIRECT_EXIT = 6.0F;

    static float slotBack(int rank) {
        if (rank <= 0) {
            return 1.60F;
        }
        return 2.00F + ((rank - 1) / 2) * 1.15F;
    }

    static float slotSide(int rank) {
        if (rank <= 0) {
            return 0.0F;
        }
        int tier = (rank - 1) / 2;
        return ((rank & 1) == 1 ? -1.0F : 1.0F) * (0.95F + 0.35F * tier);
    }

    static float columnBack(int rank) {
        int r = Math.max(0, rank);
        return 1.60F + r * 1.15F;
    }

    static float effectiveDistToLeader(int rank, float distToLeader) {
        return effectiveDistToLeader(slotBack(rank), distToLeader);
    }

    static float effectiveDistToLeader(float nominalRadius, float distToLeader) {
        if (!Float.isFinite(distToLeader)) {
            return 0.0F;
        }
        float safeNom = Float.isFinite(nominalRadius) ? nominalRadius : 1.60F;
        float rankOffset = Math.max(0.0F, safeNom - 1.60F);
        return Math.max(0.0F, distToLeader - rankOffset);
    }

    static float catchUpEnterDist(int rank) {
        return Math.max(CATCHUP_DIRECT_ENTER, columnBack(rank) + 4.0F);
    }

    static float catchUpEnterDist(float nominalRadius) {
        float safeNom = Float.isFinite(nominalRadius) ? nominalRadius : 1.60F;
        return Math.max(CATCHUP_DIRECT_ENTER, safeNom + 4.50F);
    }

    private static final float MAX_VALID_STEP_DIST = 2.50F;

    private float leaderSpeedEma;
    private float npcSpeedEma;
    private boolean leaderSpeedInitialized;
    private boolean npcSpeedInitialized;

    private Gear currentGear = Gear.WALK;
    private boolean hasGearChanged;
    private long lastGearChangeMs;
    private Gear pendingGear;
    private long pendingGearSinceMs;

    private boolean sneakLatched;
    private boolean sneakPendingActive;
    private boolean sneakPendingTarget;
    private long sneakPendingSinceMs;

    private float speedDrive = 2.00F;

    public void reset() {
        leaderSpeedEma = 0.0F;
        npcSpeedEma = 0.0F;
        leaderSpeedInitialized = false;
        npcSpeedInitialized = false;
        currentGear = Gear.WALK;
        hasGearChanged = false;
        lastGearChangeMs = 0L;
        pendingGear = null;
        pendingGearSinceMs = 0L;
        sneakLatched = false;
        sneakPendingActive = false;
        sneakPendingTarget = false;
        sneakPendingSinceMs = 0L;
        speedDrive = 2.00F;
    }

    public void resetLeaderSpeed() {
        leaderSpeedEma = 0.0F;
        leaderSpeedInitialized = false;
    }

    public void sampleSpeeds(float leaderStepDist, float npcStepDist, float simDt) {
        if (!Float.isFinite(simDt) || simDt <= 1.0E-4F) {
            return;
        }
        if (Float.isFinite(leaderStepDist) && leaderStepDist >= 0.0F && leaderStepDist <= MAX_VALID_STEP_DIST) {
            float instLeader = clamp(leaderStepDist / simDt, 0.0F, 7.5F);
            if (!leaderSpeedInitialized) {
                if (leaderStepDist > 1.0E-4F) {
                    leaderSpeedEma = instLeader;
                    leaderSpeedInitialized = true;
                }
            } else {
                float tau = (instLeader >= leaderSpeedEma) ? EMA_TAU_LEADER_UP : EMA_TAU_LEADER_DOWN;
                float alpha = clamp(simDt / (tau + simDt), 0.02F, 0.50F);
                leaderSpeedEma += (instLeader - leaderSpeedEma) * alpha;
            }
        }
        if (Float.isFinite(npcStepDist) && npcStepDist >= 0.0F && npcStepDist <= MAX_VALID_STEP_DIST) {
            float instNpc = clamp(npcStepDist / simDt, 0.0F, 7.5F);
            if (!npcSpeedInitialized) {
                if (npcStepDist > 1.0E-4F) {
                    npcSpeedEma = instNpc;
                    npcSpeedInitialized = true;
                }
            } else {
                float alpha = clamp(simDt / (EMA_TAU_NPC + simDt), 0.02F, 0.50F);
                npcSpeedEma += (instNpc - npcSpeedEma) * alpha;
            }
        }
    }

    public float computeCommandSpeed(float signedErr) {
        if (!Float.isFinite(signedErr)) {
            return Math.max(0.0F, leaderSpeedEma);
        }
        return Math.max(0.0F, leaderSpeedEma + KP_POS_ERROR * signedErr);
    }

    public boolean updateSneakIntent(boolean rawLeaderSneaking, long nowMs) {
        if (rawLeaderSneaking == sneakLatched) {
            sneakPendingActive = false;
            return sneakLatched;
        }
        if (!sneakPendingActive || sneakPendingTarget != rawLeaderSneaking) {
            sneakPendingActive = true;
            sneakPendingTarget = rawLeaderSneaking;
            sneakPendingSinceMs = nowMs;
            return sneakLatched;
        }
        if (nowMs - sneakPendingSinceMs >= SNEAK_DEBOUNCE_MS) {
            sneakLatched = rawLeaderSneaking;
            sneakPendingActive = false;
        }
        return sneakLatched;
    }

    public Gear updateGear(boolean sneakIntent, float distToLeader, float vCmd, long nowMs) {
        if (sneakIntent) {
            if (currentGear != Gear.SNEAK) {
                currentGear = Gear.SNEAK;
                hasGearChanged = true;
                lastGearChangeMs = nowMs;
            }
            pendingGear = null;
            return currentGear;
        }
        if (currentGear == Gear.SNEAK) {
            currentGear = Gear.WALK;
            hasGearChanged = true;
            lastGearChangeMs = nowMs;
            pendingGear = null;
            return currentGear;
        }

        float safeDist = Float.isFinite(distToLeader) ? distToLeader : 0.0F;
        float safeCmd = Float.isFinite(vCmd) ? vCmd : 0.0F;
        Gear target = currentGear;

        if (currentGear == Gear.WALK) {
            if ((leaderSpeedEma >= 2.95F && safeDist >= 2.20F)
                    || (safeCmd >= 3.45F && safeDist >= 2.80F)
                    || safeDist >= 5.50F) {
                target = Gear.RUN;
            }
        } else if (currentGear == Gear.RUN) {
            if ((leaderSpeedEma >= 4.70F && safeDist >= 3.80F) || safeDist >= 13.0F) {
                target = Gear.SPRINT;
            } else if (leaderSpeedEma <= 2.35F && safeDist <= 2.50F && safeCmd <= 2.55F) {
                target = Gear.WALK;
            }
        } else if (currentGear == Gear.SPRINT) {
            if ((leaderSpeedEma <= 4.05F && safeDist <= 5.00F) || safeDist <= 2.40F) {
                target = Gear.RUN;
            }
        }

        if (target == currentGear) {
            pendingGear = null;
            return currentGear;
        }

        if (pendingGear != target) {
            pendingGear = target;
            pendingGearSinceMs = nowMs;
        }

        long confirmMs = resolveConfirmMs(currentGear, target);
        boolean urgentBypass = (currentGear == Gear.WALK && target == Gear.RUN && safeDist >= 6.50F);
        boolean confirmCleared = urgentBypass || (nowMs - pendingGearSinceMs >= confirmMs);
        boolean dwellCleared = urgentBypass || !hasGearChanged || (nowMs - lastGearChangeMs >= GEAR_MIN_DWELL_MS);

        if (confirmCleared && dwellCleared) {
            boolean upshiftingFast = currentGear == Gear.WALK && (target == Gear.RUN || target == Gear.SPRINT);
            currentGear = target;
            hasGearChanged = true;
            lastGearChangeMs = nowMs;
            pendingGear = null;
            if (upshiftingFast) {
                speedDrive = Math.max(speedDrive, 1.50F);
            }
        }
        return currentGear;
    }

    public void seedDriveOnTakeoff() {
        if (leaderSpeedEma < 1.55F) {
            speedDrive = 0.80F;
        } else if (leaderSpeedEma < 2.15F) {
            speedDrive = 1.55F;
        } else {
            speedDrive = 1.90F;
        }
    }

    public void updateSpeedDrive(boolean closeMatch, boolean canIntegrate, float vCmd, float simDt) {
        if (!Float.isFinite(simDt) || simDt <= 0.0F) {
            return;
        }
        float dt = Math.min(0.10F, simDt);
        if (!closeMatch || currentGear == Gear.SPRINT) {
            float relaxStep = 1.50F * dt;
            speedDrive = (speedDrive + relaxStep >= 2.00F) ? 2.00F : (speedDrive + relaxStep);
            return;
        }
        if (!canIntegrate || !Float.isFinite(vCmd)) {
            return;
        }
        float vErr = vCmd - npcSpeedEma;
        float gain = (vErr < 0.0F) ? 2.40F : 1.60F;
        float minDrive = (currentGear == Gear.RUN) ? 1.00F : 0.50F;
        speedDrive = clamp(speedDrive + vErr * gain * dt, minDrive, 2.00F);
    }

    public float getWalkSpeedCap(boolean closeMatch) {
        if (!closeMatch || currentGear == Gear.SPRINT) {
            return -1.0F;
        }
        if (speedDrive >= 1.00F) {
            return clamp(speedDrive - 1.00F, 0.0F, 1.00F);
        }
        return 0.0F;
    }

    public float getTrackSpeedScale(boolean closeMatch) {
        if (!closeMatch || currentGear == Gear.SPRINT || currentGear == Gear.RUN || speedDrive >= 1.00F) {
            return 1.0F;
        }
        return clamp(speedDrive, 0.50F, 1.00F);
    }

    public float getLeaderSpeedEma() {
        return leaderSpeedEma;
    }

    public float getNpcSpeedEma() {
        return npcSpeedEma;
    }

    public Gear getCurrentGear() {
        return currentGear;
    }

    public float getSpeedDrive() {
        return speedDrive;
    }

    public boolean isSneakLatched() {
        return sneakLatched;
    }

    private static long resolveConfirmMs(Gear from, Gear to) {
        if (from == Gear.WALK && to == Gear.RUN) return UPSHIFT_RUN_CONFIRM_MS;
        if (from == Gear.RUN && to == Gear.WALK) return DOWNSHIFT_WALK_CONFIRM_MS;
        if (from == Gear.RUN && to == Gear.SPRINT) return UPSHIFT_SPRINT_CONFIRM_MS;
        return DOWNSHIFT_RUN_CONFIRM_MS;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
