package emberroutes.npc;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import zombie.characters.CharacterStat;
import zombie.characters.skills.PerkFactory;

/**
 * 新生成 NPC 原版随机属性与技能初始化器：
 * <p>
 * 仅在全新生成（{@link NpcManager#spawnNew}）时运行一次，绝不覆盖从 {@link NpcSnapshot}
 * 读档或离屏恢复的既有等级与经验。体格、力量与灵巧保证非零基线，避免 AI 近战走位与耐久沦为 0 级残废。
 * </p>
 */
public final class NpcPerkInitializer {
    private static final int MELEE_COUNT = 6;
    private static final int LIFE_COUNT = 9;

    public record PerkRoll(
            int strength,
            int fitness,
            int nimble,
            int sprinting,
            int lightfoot,
            int sneak,
            int maintenance,
            int aiming,
            int reloading,
            int[] meleeLevels,
            int[] lifeLevels) {

        public PerkRoll {
            meleeLevels = meleeLevels != null ? meleeLevels.clone() : new int[MELEE_COUNT];
            lifeLevels = lifeLevels != null ? lifeLevels.clone() : new int[LIFE_COUNT];
        }

        @Override
        public int[] meleeLevels() {
            return meleeLevels.clone();
        }

        @Override
        public int[] lifeLevels() {
            return lifeLevels.clone();
        }
    }

    private NpcPerkInitializer() {}

    /** 纯 Java 随机技能掷骰（零 {@code zombie.*} 依赖，供运行期与 JUnit 千次统计复用）。 */
    public static PerkRoll rollRandom(Random rng) {
        Random r = (rng != null) ? rng : ThreadLocalRandom.current();
        // 体格/力量：居中分布 [4..7]，保证基本负重与近战推击门槛
        int strength = 4 + (r.nextInt(4) + r.nextInt(4)) / 2;
        int fitness = 4 + (r.nextInt(4) + r.nextInt(4)) / 2;
        // 灵巧 [1..4]：直接决定原版举架后退 StrafeSpeed，不再出现 0 级龟速倒退
        int nimble = 1 + (r.nextInt(4) + r.nextInt(4)) / 2;

        int sprinting = (r.nextInt(4) + r.nextInt(4)) / 2;
        int lightfoot = (r.nextInt(4) + r.nextInt(4)) / 2;
        int sneak = (r.nextInt(4) + r.nextInt(4)) / 2;
        int maintenance = (r.nextInt(4) + r.nextInt(4)) / 2;
        int aiming = 1 + (r.nextInt(4) + r.nextInt(4)) / 2;
        int reloading = 1 + (r.nextInt(3) + r.nextInt(3)) / 2;

        // 近战武器：随机 1-2 项主修专精 [2..4]，其余 [0..2]
        int[] melee = new int[MELEE_COUNT];
        for (int i = 0; i < MELEE_COUNT; i++) {
            melee[i] = r.nextInt(3);
        }
        int meleeSpecs = 1 + r.nextInt(2);
        int firstMelee = r.nextInt(MELEE_COUNT);
        melee[firstMelee] = 2 + r.nextInt(3);
        if (meleeSpecs > 1) {
            int secondMelee = (firstMelee + 1 + r.nextInt(MELEE_COUNT - 1)) % MELEE_COUNT;
            melee[secondMelee] = 2 + r.nextInt(3);
        }

        // 生活技能：随机 1-2 项职业特长 [2..4]，其余 [0..1]
        int[] life = new int[LIFE_COUNT];
        for (int i = 0; i < LIFE_COUNT; i++) {
            life[i] = r.nextInt(2);
        }
        int lifeSpecs = 1 + r.nextInt(2);
        int firstLife = r.nextInt(LIFE_COUNT);
        life[firstLife] = 2 + r.nextInt(3);
        if (lifeSpecs > 1) {
            int secondLife = (firstLife + 1 + r.nextInt(LIFE_COUNT - 1)) % LIFE_COUNT;
            life[secondLife] = 2 + r.nextInt(3);
        }

        return new PerkRoll(
                strength,
                fitness,
                nimble,
                sprinting,
                lightfoot,
                sneak,
                maintenance,
                aiming,
                reloading,
                melee,
                life);
    }

    public static PerkRoll applyRandomPerks(IsoNPC npc) {
        PerkRoll roll = rollRandom(ThreadLocalRandom.current());
        applyRoll(npc, roll);
        return roll;
    }

    public static void applyRoll(IsoNPC npc, PerkRoll roll) {
        if (npc == null || roll == null) {
            return;
        }
        applyPerkLevel(npc, PerkFactory.Perks.Strength, roll.strength(), true);
        applyPerkLevel(npc, PerkFactory.Perks.Fitness, roll.fitness(), true);
        applyPerkLevel(npc, PerkFactory.Perks.Nimble, roll.nimble(), roll.nimble() >= 2);
        applyPerkLevel(npc, PerkFactory.Perks.Sprinting, roll.sprinting(), false);
        applyPerkLevel(npc, PerkFactory.Perks.Lightfoot, roll.lightfoot(), false);
        applyPerkLevel(npc, PerkFactory.Perks.Sneak, roll.sneak(), false);
        applyPerkLevel(npc, PerkFactory.Perks.Maintenance, roll.maintenance(), false);
        applyPerkLevel(npc, PerkFactory.Perks.Aiming, roll.aiming(), roll.aiming() >= 2);
        applyPerkLevel(npc, PerkFactory.Perks.Reloading, roll.reloading(), roll.reloading() >= 2);

        PerkFactory.Perk[] meleePerks = {
                PerkFactory.Perks.Axe,
                PerkFactory.Perks.Blunt,
                PerkFactory.Perks.SmallBlunt,
                PerkFactory.Perks.LongBlade,
                PerkFactory.Perks.SmallBlade,
                PerkFactory.Perks.Spear
        };
        int[] melee = roll.meleeLevels();
        for (int i = 0; i < meleePerks.length && i < melee.length; i++) {
            applyPerkLevel(npc, meleePerks[i], melee[i], melee[i] >= 2);
        }

        PerkFactory.Perk[] lifePerks = {
                PerkFactory.Perks.Woodwork,
                PerkFactory.Perks.Cooking,
                PerkFactory.Perks.Farming,
                PerkFactory.Perks.Doctor,
                PerkFactory.Perks.Electricity,
                PerkFactory.Perks.MetalWelding,
                PerkFactory.Perks.Mechanics,
                PerkFactory.Perks.Tailoring,
                PerkFactory.Perks.PlantScavenging
        };
        int[] life = roll.lifeLevels();
        for (int i = 0; i < lifePerks.length && i < life.length; i++) {
            applyPerkLevel(npc, lifePerks[i], life[i], life[i] >= 2);
        }

        // 同步原版体能属性条与耐力系统（IsoGameCharacter.java:10832）
        if (npc.getStats() != null) {
            npc.getStats().set(CharacterStat.FITNESS, roll.fitness() / 5.0F - 1.0F);
        }
        if (npc.getFitness() != null) {
            npc.getFitness().init();
        }
    }

    private static void applyPerkLevel(IsoNPC npc, PerkFactory.Perk perk, int level, boolean setBoost) {
        int clamped = Math.max(0, Math.min(10, level));
        if (clamped <= 0) {
            return;
        }
        npc.setPerkLevelDebug(perk, clamped);
        if (npc.getXp() != null) {
            npc.getXp().setXPToLevel(perk, clamped);
        }
        if (setBoost && npc.getDescriptor() != null && npc.getDescriptor().getXPBoostMap() != null) {
            npc.getDescriptor().getXPBoostMap().put(perk, Math.min(3, clamped));
        }
    }
}
