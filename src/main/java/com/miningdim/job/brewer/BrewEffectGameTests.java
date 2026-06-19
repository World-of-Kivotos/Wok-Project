package com.miningdim.job.brewer;

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
        // 强度 0 (新酒) -> 空方案, 逼你陈酿。
        helper.assertTrue(BrewEffectEngine.plan(WineType.BRANDY, 0.0D, NOOP_RNG).isEmpty(), "strength 0 -> empty");
        helper.assertTrue(BrewEffectEngine.plan(WineType.WHISKEY, 0.0D, NOOP_RNG).isEmpty(), "whiskey 0 -> empty");
        helper.assertTrue(BrewEffectEngine.plan(WineType.MOONSHINE, 0.0D, NOOP_RNG).isEmpty(), "moonshine 0 -> empty");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void eachWineMapsToItsEffect(GameTestHelper helper) {
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
        // 极高强度: 战斗类 (力量/抗性) 放大封顶 AMP_CAP_COMBAT=1, 续航/工具类封顶 AMP_CAP_LOOSE=2。
        helper.assertTrue(firstAmp(WineType.TEQUILA, 1000.0D) == BrewerConstants.AMP_CAP_COMBAT, "tequila amp caps at combat cap");
        helper.assertTrue(firstAmp(WineType.VODKA, 1000.0D) == BrewerConstants.AMP_CAP_COMBAT, "vodka amp caps at combat cap");
        helper.assertTrue(firstAmp(WineType.BRANDY, 1000.0D) == BrewerConstants.AMP_CAP_LOOSE, "brandy amp caps at loose cap");
        helper.assertTrue(firstAmp(WineType.GIN, 1000.0D) == BrewerConstants.AMP_CAP_LOOSE, "gin amp caps at loose cap");
        // 战斗类软化曲线更收紧: 同样高强度下 softened(combat) < softened(loose)。
        helper.assertTrue(BrewEffectEngine.softened(100.0D, true) < BrewEffectEngine.softened(100.0D, false),
                "combat softening tighter than loose");
        // 极高强度时长封顶在 EFFECT_MAX_DURATION_TICKS。
        helper.assertTrue(firstDuration(WineType.BRANDY, 100000.0D) == BrewerConstants.EFFECT_MAX_DURATION_TICKS,
                "duration caps at max");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void softenedStrengthDiminishesPastKnee(GameTestHelper helper) {
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
        helper.assertTrue(Math.abs(BrewEffectEngine.moonshineGoodProb(0.0D) - 0.40D) < EPS, "base good prob 0.40");
        helper.assertTrue(Math.abs(BrewEffectEngine.moonshineGoodProb(10.0D) - 0.50D) < EPS, "strength 10 -> 0.50");
        helper.assertTrue(Math.abs(BrewEffectEngine.moonshineGoodProb(50.0D) - 0.85D) < EPS, "caps at 0.85");
        helper.assertTrue(Math.abs(BrewEffectEngine.moonshineGoodProb(100.0D) - 0.85D) < EPS, "stays capped at 0.85");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void moonshineDistributionAndInvariants(GameTestHelper helper) {
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
                helper.assertTrue(inst.getDuration() == BrewerConstants.MOONSHINE_BAD_DURATION_TICKS, "bad duration fixed");
            }
        }
        double frac = (double) good / n;
        // prob 0.85 +- 抽样误差。
        helper.assertTrue(frac > 0.80D && frac < 0.90D, "high-strength good fraction ~0.85, got " + frac);
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
