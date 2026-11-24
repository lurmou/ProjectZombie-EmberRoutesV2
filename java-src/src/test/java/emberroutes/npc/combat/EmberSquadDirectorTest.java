package emberroutes.npc.combat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EmberSquadDirectorTest {
    @Test
    void hordeClustersRequireFourDenseStandingZombies() {
        EmberSquadDirector dir = new EmberSquadDirector();
        long now = 10_000L;
        int standing = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_STANDING;

        // 4 只彼此紧贴（近邻间距 0.35 < 0.50）的站立丧尸应聚成一个 HordeCluster
        dir.upsertManualThreat(1, 10.0F, 10.0F, 0.0F, 0.0F, 0.0F, standing, now);
        dir.upsertManualThreat(2, 10.35F, 10.0F, 0.0F, 0.0F, 0.0F, standing, now);
        dir.upsertManualThreat(3, 10.0F, 10.35F, 0.0F, 0.0F, 0.0F, standing, now);
        dir.upsertManualThreat(4, 10.35F, 10.35F, 0.0F, 0.0F, 0.0F, standing, now);

        // 另 4 只零散分布（间距 1.4 > 0.50）不应聚为尸潮
        dir.upsertManualThreat(5, 30.0F, 30.0F, 0.0F, 0.0F, 0.0F, standing, now);
        dir.upsertManualThreat(6, 31.4F, 30.0F, 0.0F, 0.0F, 0.0F, standing, now);
        dir.upsertManualThreat(7, 30.0F, 31.4F, 0.0F, 0.0F, 0.0F, standing, now);
        dir.upsertManualThreat(8, 31.4F, 31.4F, 0.0F, 0.0F, 0.0F, standing, now);

        dir.rebuildClusters();
        assertEquals(1, dir.getActiveClusterCount());
        EmberSquadDirector.HordeCluster cluster = dir.getClusterAt(0);
        assertTrue(cluster.active);
        assertEquals(4, cluster.count);
        assertEquals(10.175F, cluster.centroidX, 0.05F);
        assertEquals(10.175F, cluster.centroidY, 0.05F);
    }

    @Test
    void meleeRingTokensEnforceThreeSlotsCommitLockAndRedirectFourthFollower() {
        EmberSquadDirector dir = new EmberSquadDirector();
        long now = 5_000L;
        int standing = CombatTargetState.FLAG_VALID | CombatTargetState.FLAG_STANDING;

        EmberSquadDirector.ThreatEntry z1 =
                dir.upsertManualThreat(101, 5.0F, 5.0F, 0.0F, 0.0F, 0.0F, standing, now);
        EmberSquadDirector.ThreatEntry z2 =
                dir.upsertManualThreat(102, 7.2F, 5.0F, 0.0F, 0.0F, 0.0F, standing, now);

        assertEquals(0, z1.ringTable.tryClaim("npc_1", now));
        assertEquals(1, z1.ringTable.tryClaim("npc_2", now));
        assertEquals(2, z1.ringTable.tryClaim("npc_3", now));
        assertTrue(z1.ringTable.isLockedFor("npc_1", now + 200L));
        assertEquals(-1, z1.ringTable.tryClaim("npc_4", now));

        // 第 4 名近战因 z1 已满（+1000.0 转火罚分）应被分配去打较远一点的 z2
        MeleeSlotAssigner.SelectedEngagement pick = new MeleeSlotAssigner.SelectedEngagement();
        EmberSquadDirector.ThreatEntry chosen = MeleeSlotAssigner.selectThreatAndSlot(
                dir, "npc_4", 4.5F, 5.0F, 0, 0.9F, 12.0F, -1, now, null, pick);
        assertSame(z2, chosen);
        assertEquals(0, pick.ringSlot);

        dir.releaseAllForNpc("npc_1");
        assertEquals(2, z1.ringTable.activeCount());
        assertEquals(0, z1.ringTable.tryClaim("npc_5", now + 600L));

        dir.resetAll();
        assertEquals(0, dir.getActiveThreatCount());
        assertEquals(0, dir.getActiveClusterCount());
    }

    @Test
    void reloadCoverPoolCapsAtHalfActiveShootersAndExpiresAfterFiveSeconds() {
        EmberSquadDirector dir = new EmberSquadDirector();
        EmberSquadDirector.ReloadCoverTokenPool pool = dir.getReloadPool();

        pool.setActiveShooterCount(4);
        assertEquals(2, pool.maxConcurrentReloads());

        long t0 = 1_000L;
        assertTrue(pool.acquire("s1", t0));
        assertTrue(pool.acquire("s2", t0));
        assertFalse(pool.acquire("s3", t0 + 100L));
        assertTrue(pool.acquire("s1", t0 + 200L)); // 已持有续期幂等

        // t0+5010ms 时 s2 (6000ms) 已自动过期释放，而 s1 于 t0+200ms 续期至 t0+5200ms 仍有效 -> 空出 1 席可由 s3 接管
        assertTrue(pool.acquire("s3", t0 + EmberSquadDirector.RELOAD_LEASE_MS + 10L));
        pool.release("s3");
        assertEquals(1, pool.activeLeaseCount(t0 + EmberSquadDirector.RELOAD_LEASE_MS + 20L));

        // 超过 s1 续期截止时间 (t0+5200ms) 后全部自动释放
        assertEquals(0, pool.activeLeaseCount(t0 + 200L + EmberSquadDirector.RELOAD_LEASE_MS + 20L));

        pool.setActiveShooterCount(1);
        assertEquals(1, pool.maxConcurrentReloads());
    }
}
