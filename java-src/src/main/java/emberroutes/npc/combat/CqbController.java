package emberroutes.npc.combat;

/**
 * 第 7 号战斗微操内核：{@code CqbController}（室内 CQB 战术肃清、抑近战越线与 {@code 3.0m} 门窗 4 相位切角警戒，零 {@code zombie.*} 依赖）。
 *
 * <ol>
 *   <li>开启 {@code CQBMode} 时优先动用枪械，并通知同队近战收敛贴背避免横穿狭窄门道枪线；</li>
 *   <li>当前无可视现敌时，由 {@code B42CombatRuntime.scanDoorWindowSectors} 扫描 8 方位 {@code 3.0m} 内门窗开口建立警戒方位队列；</li>
 *   <li>以 {@code 210°/s} 角速度在最多 4 个战术相位角间平顺切角架枪戒备。</li>
 * </ol>
 */
public final class CqbController {
    public static final float OPENING_SCAN_RADIUS = 3.0F;
    public static final float SECTOR_SLEW_DEG_PER_SEC = 210.0F;
    public static final int MAX_PHASES = 4;
    public static final double PHASE_DWELL_SIM_SEC = 0.85;

    private final float[] phaseYawsRad = new float[MAX_PHASES];
    private int phaseCount;
    private int phaseCursor;
    private double phaseTimerSec;

    public void reset() {
        phaseCount = 0;
        phaseCursor = 0;
        phaseTimerSec = 0.0;
    }

    public int getPhaseCount() {
        return phaseCount;
    }

    public int getPhaseCursor() {
        return phaseCursor;
    }

    /** 显式载入 4 相位角列表（用于脱机 JUnit 与 {@code B42CombatRuntime} 扫描结果）。 */
    public void setSectorYaws(float[] yaws, int count) {
        int n = Math.max(0, Math.min(MAX_PHASES, count));
        this.phaseCount = n;
        for (int i = 0; i < n; i++) {
            this.phaseYawsRad[i] = yaws[i];
        }
        if (this.phaseCursor >= Math.max(1, n)) {
            this.phaseCursor = 0;
        }
    }

    /** 推进 4 相位 CQB 切角状态机，并以 {@code 210°/s} 驱动 {@link NpcVirtualAim} 平滑扫视。 */
    public float stepSectorSweep(
            NpcVirtualAim virtualAim,
            float currentBodyYawRad,
            float selfX,
            float selfY,
            float selfZ,
            float weaponRange,
            double dtSim) {
        if (phaseCount <= 0) {
            seedFallbackCrossSectors(currentBodyYawRad);
        }
        phaseTimerSec += Math.max(0.0, dtSim);
        if (phaseTimerSec >= PHASE_DWELL_SIM_SEC) {
            phaseTimerSec = 0.0;
            phaseCursor = (phaseCursor + 1) % phaseCount;
        }
        float desiredYaw = phaseYawsRad[phaseCursor];
        if (virtualAim != null) {
            virtualAim.aimAtYaw(
                    currentBodyYawRad,
                    selfX,
                    selfY,
                    selfZ,
                    desiredYaw,
                    Math.min(6.0F, Math.max(2.5F, weaponRange)),
                    SECTOR_SLEW_DEG_PER_SEC,
                    (float) Math.max(0.01, dtSim));
        }
        return desiredYaw;
    }

    /** 室内 CQB 开启时抑制近战随从越过活跃射手站位前方抢刀。 */
    public static boolean isMeleeAdvanceSuppressedByCqb(
            boolean cqbEnabled,
            float npcX,
            float npcY,
            EmberSquadDirector director,
            String selfId,
            int npcZ,
            EmberSquadDirector.PassabilityProbe probe) {
        if (!cqbEnabled || director == null) {
            return false;
        }
        EmberSquadDirector.CorridorAvoidanceResult res = new EmberSquadDirector.CorridorAvoidanceResult();
        director.evaluateCorridorAvoidance(npcX, npcY, npcZ, selfId, 3.5F, probe, res);
        return res.inCorridor;
    }

    public void seedFallbackCrossSectors(float baseYawRad) {
        phaseCount = MAX_PHASES;
        phaseYawsRad[0] = NpcVirtualAim.normalizeRadians(baseYawRad);
        phaseYawsRad[1] = NpcVirtualAim.normalizeRadians(baseYawRad + (float) (Math.PI * 0.25));
        phaseYawsRad[2] = NpcVirtualAim.normalizeRadians(baseYawRad - (float) (Math.PI * 0.25));
        phaseYawsRad[3] = NpcVirtualAim.normalizeRadians(baseYawRad + (float) Math.PI);
        if (phaseCursor >= MAX_PHASES) {
            phaseCursor = 0;
        }
    }

    public static int findSimilarSector(float[] list, int count, float yaw) {
        for (int i = 0; i < count; i++) {
            if (Math.abs(NpcVirtualAim.shortestAngleDelta(list[i], yaw)) < Math.toRadians(35.0)) {
                return i;
            }
        }
        return -1;
    }

    /** CQB 模式下按队员顺位错开警戒扇区相位游标，避免多名射手盯死同一开口。 */
    public void applyMemberPhaseOffset(int memberRank, int squadCount) {
        int activePhases = Math.max(1, Math.min(MAX_PHASES, phaseCount));
        if (squadCount <= 1 || memberRank < 0) {
            return;
        }
        this.phaseCursor = Math.floorMod(memberRank, activePhases);
    }

    /** 室内 CQB 队形自适应：窄通道/2 格净空以内自动切换纵队（COLUMN），开阔室内大厅展开横排（LINE）。 */
    public static emberroutes.npc.FormationType resolveIndoorFormation(
            boolean cqbEnabled,
            boolean indoor,
            int localTileClearance,
            emberroutes.npc.FormationType configured) {
        emberroutes.npc.FormationType fallback =
                configured != null ? configured : emberroutes.npc.FormationType.WEDGE;
        if (!cqbEnabled || !indoor) {
            return fallback;
        }
        if (localTileClearance <= 2) {
            return emberroutes.npc.FormationType.COLUMN;
        }
        if (localTileClearance >= 4 && fallback == emberroutes.npc.FormationType.COLUMN) {
            return emberroutes.npc.FormationType.LINE;
        }
        return fallback;
    }
}
