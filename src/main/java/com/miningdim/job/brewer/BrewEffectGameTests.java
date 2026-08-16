package com.miningdim.job.brewer;

import com.miningdim.combat.PlayerDamageReduction;
import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 喝酒效果引擎 GameTest: 强度软上限 (战斗类收紧/续航类放宽) + 各酒效果身份 + 瞬恢/经验缩放 + 月光赌博分布与不变式。
 * 全为具体业务断言 (放大等级 / 效果种类 / 概率), 纯函数 plan() 直接测, 无需起世界。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class BrewEffectGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "brewer";
    private static final double EPS = 1e-9D;
    private static final RandomSource NOOP_RNG = RandomSource.create(0L);

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void newWineHasNoEffect(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // 强度 0 (新酒) -> 空方案, 逼你陈酿。
        helper.assertTrue(BrewEffectEngine.plan(WineType.BRANDY, 0.0D, NOOP_RNG).isEmpty(), "strength 0 -> empty");
        helper.assertTrue(BrewEffectEngine.plan(WineType.WHISKEY, 0.0D, NOOP_RNG).isEmpty(), "whiskey 0 -> empty");
        helper.assertTrue(BrewEffectEngine.plan(WineType.MOONSHINE, 0.0D, NOOP_RNG).isEmpty(), "moonshine 0 -> empty");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void eachWineMapsToItsEffect(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        helper.assertTrue(firstEffect(WineType.BRANDY, 30.0D) == MobEffects.DIG_SPEED, "brandy -> haste");
        helper.assertTrue(firstEffect(WineType.RUM, 30.0D) == MobEffects.MOVEMENT_SPEED, "rum -> speed");
        helper.assertTrue(firstEffect(WineType.GIN, 30.0D) == MobEffects.ABSORPTION, "gin -> absorption");
        helper.assertTrue(firstEffect(WineType.CHAMPAGNE, 30.0D) == MobEffects.REGENERATION, "champagne -> regen");
        helper.assertTrue(firstEffect(WineType.VODKA, 30.0D) == MobEffects.DAMAGE_RESISTANCE, "vodka -> resistance");
        helper.assertTrue(firstEffect(WineType.TEQUILA, 30.0D) == MobEffects.DAMAGE_BOOST, "tequila -> strength");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void softCapsAmplifierByRegime(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // 极高强度: 战斗类 (力量/抗性) 放大封顶 AMP_CAP_COMBAT=1, 续航/工具类封顶 AMP_CAP_LOOSE=2。
        helper.assertTrue(firstAmp(WineType.TEQUILA, 1000.0D) == BrewerConfig.AMP_CAP_COMBAT.get(), "tequila amp caps at combat cap");
        helper.assertTrue(firstAmp(WineType.VODKA, 1000.0D) == BrewerConfig.AMP_CAP_COMBAT.get(), "vodka amp caps at combat cap");
        helper.assertTrue(firstAmp(WineType.BRANDY, 1000.0D) == BrewerConfig.AMP_CAP_LOOSE.get(), "brandy amp caps at loose cap");
        helper.assertTrue(firstAmp(WineType.GIN, 1000.0D) == BrewerConfig.AMP_CAP_LOOSE.get(), "gin amp caps at loose cap");
        // 战斗类软化曲线更收紧: 同样高强度下 softened(combat) < softened(loose)。
        helper.assertTrue(BrewEffectEngine.softened(100.0D, true) < BrewEffectEngine.softened(100.0D, false),
                "combat softening tighter than loose");
        // 极高强度时长封顶在 EFFECT_MAX_DURATION_TICKS。
        helper.assertTrue(firstDuration(WineType.BRANDY, 100000.0D) == BrewerConfig.EFFECT_MAX_DURATION_TICKS.get(),
                "duration caps at max");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void softenedStrengthDiminishesPastKnee(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // 拐点内线性: softened(S<=knee) == S。
        helper.assertTrue(Math.abs(BrewEffectEngine.softened(8.0D, true) - 8.0D) < EPS, "combat at knee = identity");
        helper.assertTrue(Math.abs(BrewEffectEngine.softened(16.0D, false) - 16.0D) < EPS, "loose at knee = identity");
        // 拐点外递减: combat softened(50)=8+(42)*0.15=14.3。
        helper.assertTrue(Math.abs(BrewEffectEngine.softened(50.0D, true) - 14.3D) < 1e-6D, "combat softened(50)=14.3");
        // loose softened(50)=16+(34)*0.40=29.6。
        helper.assertTrue(Math.abs(BrewEffectEngine.softened(50.0D, false) - 29.6D) < 1e-6D, "loose softened(50)=29.6");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void whiskeyHealsAndMaotaiGivesXpScaledBySoftStrength(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // 威士忌: softened(20,loose)=16+4*0.4=17.6; heal=17.6*0.5=8.8。
        BrewEffectPlan whiskey = BrewEffectEngine.plan(WineType.WHISKEY, 20.0D, NOOP_RNG);
        helper.assertTrue(whiskey.effects().isEmpty() && whiskey.xp() == 0, "whiskey is pure heal");
        helper.assertTrue(Math.abs(whiskey.instantHeal() - 8.8F) < 0.05F, "whiskey heal ~ 8.8 half-hearts");
        // 茅台: xp=round(17.6*2)=35。
        BrewEffectPlan maotai = BrewEffectEngine.plan(WineType.MAOTAI, 20.0D, NOOP_RNG);
        helper.assertTrue(maotai.effects().isEmpty() && maotai.instantHeal() <= 0.0F, "maotai is pure xp");
        helper.assertTrue(maotai.xp() == 35, "maotai xp = round(17.6*2) = 35");
        // 缩放单调: 强度更高 -> 恢复/经验更多。
        helper.assertTrue(BrewEffectEngine.plan(WineType.WHISKEY, 40.0D, NOOP_RNG).instantHeal()
                > whiskey.instantHeal(), "more strength -> more heal");
        helper.assertTrue(BrewEffectEngine.plan(WineType.MAOTAI, 40.0D, NOOP_RNG).xp() > maotai.xp(),
                "more strength -> more xp");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void moonshineGoodProbScalesAndCaps(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        helper.assertTrue(Math.abs(BrewEffectEngine.moonshineGoodProb(0.0D) - 0.40D) < EPS, "base good prob 0.40");
        helper.assertTrue(Math.abs(BrewEffectEngine.moonshineGoodProb(10.0D) - 0.50D) < EPS, "strength 10 -> 0.50");
        helper.assertTrue(Math.abs(BrewEffectEngine.moonshineGoodProb(50.0D) - 0.85D) < EPS, "caps at 0.85");
        helper.assertTrue(Math.abs(BrewEffectEngine.moonshineGoodProb(100.0D) - 0.85D) < EPS, "stays capped at 0.85");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void moonshineDistributionAndInvariants(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // 高强度 (prob 0.85) 抽样分布 + 不变式: 好结果属 GOOD_POOL+好提示; 坏结果属 BAD_POOL+坏提示+等级 I+小时长。
        RandomSource rng = RandomSource.create(0xB33FL);
        int n = 3000;
        int good = 0;
        for (int i = 0; i < n; i++) {
            BrewEffectPlan plan = BrewEffectEngine.plan(WineType.MOONSHINE, 100.0D, rng);
            helper.assertTrue(plan.effects().size() == 1, "moonshine yields exactly one effect");
            MobEffectInstance inst = plan.effects().get(0);
            boolean isGood = "message.miningdim.brewer.moonshine.good".equals(plan.messageKey());
            if (isGood) {
                good++;
                helper.assertTrue(inPool(BrewEffectEngine.MOONSHINE_GOOD_POOL, inst.getEffect()), "good effect in good pool");
            } else {
                helper.assertTrue("message.miningdim.brewer.moonshine.bad".equals(plan.messageKey()), "bad key set");
                helper.assertTrue(inPool(BrewEffectEngine.MOONSHINE_BAD_POOL, inst.getEffect()), "bad effect in bad pool");
                helper.assertTrue(inst.getAmplifier() == 0, "bad effect is level I");
                helper.assertTrue(inst.getDuration() == BrewerConfig.MOONSHINE_BAD_DURATION_TICKS.get(), "bad duration fixed");
            }
        }
        double frac = (double) good / n;
        // prob 0.85 +- 抽样误差。
        helper.assertTrue(frac > 0.80D && frac < 0.90D, "high-strength good fraction ~0.85, got " + frac);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brewerConfigDefaultsMatchPreMigrationValues(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // F086: BrewerConfig 是搬迁型改动 (原 BrewerConstants 硬编码 -> ForgeConfigSpec), 默认值必须零漂移。
        // 任何人把默认值顺手改掉, 本方法必挂。
        helper.assertTrue(BrewerConfig.DRIED_WHEAT_PER_BOTTLE_YEAR.get() == 16,
                "driedWheatPerBottleYear 默认值未漂移, got " + BrewerConfig.DRIED_WHEAT_PER_BOTTLE_YEAR.get());
        helper.assertTrue(Math.abs(BrewerConfig.FUEL_QUAD_COEF.get() - 5.0D) < EPS, "quadCoef 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.SPOILAGE_DECAY_YEARS_PER_DAY.get() - 200.0D) < EPS,
                "decayYearsPerDay 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.FULL_MOON_BONUS.get() - 0.25D) < EPS, "fullMoonBonus 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.GIN_MAX_HEALTH_PCT_PER_LAYER.get() - 0.10D) < EPS,
                "ginMaxHealthPctPerLayer 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.GLOBAL_BONUS_MAX_HEALTH_CAP_PCT.get() - 1.0D) < EPS,
                "globalBonusMaxHealthCapPct 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.VODKA_REDUCTION_PER_LAYER.get() - 0.05D) < EPS,
                "vodkaReductionPerLayer 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.WHISKEY_HEAL_PCT_PER_LAYER.get() - 0.05D) < EPS,
                "whiskeyHealPctPerLayer 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.CHAMPAGNE_HEAL_PCT_PER_LAYER.get() - 0.01D) < EPS,
                "champagneHealPctPerLayer 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.RUM_MOVE_SPEED_PCT_PER_LAYER.get() - 0.06D) < EPS,
                "rumMoveSpeedPctPerLayer 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.TEQUILA_ATTACK_PER_LAYER.get() - 3.0D) < EPS,
                "tequilaAttackPerLayer 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.MAOTAI_XP_PCT_PER_LAYER.get() - 0.10D) < EPS,
                "maotaiXpPctPerLayer 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.COMBAT_SOFTCAP_KNEE.get() - 8.0D) < EPS, "combatKnee 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.COMBAT_SOFTCAP_DIMINISH.get() - 0.15D) < EPS,
                "combatDiminish 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.LOOSE_SOFTCAP_KNEE.get() - 16.0D) < EPS, "looseKnee 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.LOOSE_SOFTCAP_DIMINISH.get() - 0.40D) < EPS,
                "looseDiminish 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.AMP_PER_SOFT_STRENGTH.get() - 6.0D) < EPS,
                "ampPerSoftStrength 默认值未漂移");
        helper.assertTrue(BrewerConfig.AMP_CAP_COMBAT.get() == 1, "ampCapCombat 默认值未漂移");
        helper.assertTrue(BrewerConfig.AMP_CAP_LOOSE.get() == 2, "ampCapLoose 默认值未漂移");
        helper.assertTrue(BrewerConfig.EFFECT_BASE_DURATION_TICKS.get() == 400,
                "effectBaseDurationTicks 默认值未漂移");
        helper.assertTrue(BrewerConfig.EFFECT_DURATION_PER_SOFT.get() == 30, "effectDurationPerSoft 默认值未漂移");
        helper.assertTrue(BrewerConfig.EFFECT_MAX_DURATION_TICKS.get() == 6000,
                "effectMaxDurationTicks 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.WHISKEY_HEAL_PER_SOFT.get() - 0.5D) < EPS,
                "whiskeyHealPerSoft 默认值未漂移");
        helper.assertTrue(BrewerConfig.MAOTAI_XP_PER_SOFT.get() == 2, "maotaiXpPerSoft 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.MOONSHINE_GOOD_BASE_PROB.get() - 0.40D) < EPS,
                "goodBaseProb 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.MOONSHINE_GOOD_PROB_PER_STRENGTH.get() - 0.01D) < EPS,
                "goodProbPerStrength 默认值未漂移");
        helper.assertTrue(Math.abs(BrewerConfig.MOONSHINE_GOOD_PROB_MAX.get() - 0.85D) < EPS,
                "goodProbMax 默认值未漂移");
        helper.assertTrue(BrewerConfig.MOONSHINE_BAD_DURATION_TICKS.get() == 300, "badDurationTicks 默认值未漂移");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vodkaReductionRateStaysWithinKeepFactorContract(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // F086: 满5层伏特加减伤率必须落在 PlayerDamageReduction.keepFactor 的 [0,1] 契约内 (否则脏值抛异常);
        // 若有人把 VODKA_REDUCTION_PER_LAYER 上界放开导致满层 rate 越过 1, keepFactor 调用本身就会抛出。
        double rate = BrewPermanentBuffs.vodkaReductionRate(5);
        helper.assertTrue(rate <= 1.0D, "满5层伏特加减伤率必须 <= 1.0, got " + rate);
        double keep = PlayerDamageReduction.keepFactor(rate);
        helper.assertTrue(Math.abs(keep - 0.75D) < EPS,
                "满5层伏特加 keepFactor(rate) 应为 1-rate=0.75, got " + keep);
        helper.succeed();
    }

    // ---- helpers ----

    private static MobEffect firstEffect(WineType type, double strength) {
        return BrewEffectEngine.plan(type, strength, NOOP_RNG).effects().get(0).getEffect();
    }

    private static int firstAmp(WineType type, double strength) {
        return BrewEffectEngine.plan(type, strength, NOOP_RNG).effects().get(0).getAmplifier();
    }

    private static int firstDuration(WineType type, double strength) {
        return BrewEffectEngine.plan(type, strength, NOOP_RNG).effects().get(0).getDuration();
    }

    private static boolean inPool(MobEffect[] pool, MobEffect effect) {
        for (MobEffect e : pool) {
            if (e == effect) {
                return true;
            }
        }
        return false;
    }
}
