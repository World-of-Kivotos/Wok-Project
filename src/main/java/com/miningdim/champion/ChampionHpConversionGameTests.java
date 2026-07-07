package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/**
 * 点数换血量换算纯逻辑 GameTest (ChampionStarAffix spec 第四章生存轴换算 + 第六章 6.1; 批2)。
 *
 * 断言 {@link ChampionHpConversion} 曲线 hpFraction = 0.35 + 0.65 × remFrac^1.5 与体型乘数的具体业务结果
 * (删曲线/删体型乘数/删生存池花费统计必挂): 逐位核对花费点数、期望比例、换算后有效血。宽容语义 (超预算钳
 * FLOOR) 与"战斗池词条不扣血"负向断言并测。
 *
 * template = "empty", batch = "champion_hp_conversion"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionHpConversionGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_hp_conversion";
    private static final double EPS = 1e-9D;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void noSurvivalAffixesKeepsFullBaseHp(GameTestHelper helper) {
        // 无词条 = 星表满额 (remFrac=1 -> frac 恒 1.0), 7★ = 6000。
        helper.assertTrue(Math.abs(ChampionHpConversion.hpFraction(StarRank.STAR_7, Map.of()) - 1.0D) < EPS,
                "无生存词条 hpFraction = 1.0");
        helper.assertTrue(Math.abs(ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_7, Map.of()) - 6000.0D) < EPS,
                "7★ 裸怪换算后仍 6000");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void combatAffixesDoNotCutHp(GameTestHelper helper) {
        // 战斗池词条不占生存池: 花费 0, 比例 1.0 (换算只看生存轴)。
        Map<AffixDef, AffixQuality> combatOnly = Map.of(
                AffixDef.BURNING, AffixQuality.RARE,
                AffixDef.REND, AffixQuality.RARE);
        helper.assertTrue(ChampionHpConversion.survivalSpent(combatOnly) == 0,
                "战斗池词条生存花费 = 0");
        helper.assertTrue(Math.abs(ChampionHpConversion.hpFraction(StarRank.STAR_6, combatOnly) - 1.0D) < EPS,
                "战斗池词条不扣血: hpFraction = 1.0");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void survivalSpendFollowsCurveExactly(GameTestHelper helper) {
        // 7★ 全防御 build: 复合 EPIC(32) + 超高分子 EPIC(28) + 偏斜 EPIC(40) = 100 / 预算 165。
        Map<AffixDef, AffixQuality> defensive = Map.of(
                AffixDef.COMPOSITE_ARMOR, AffixQuality.EPIC,
                AffixDef.UHMWPE_ARMOR, AffixQuality.EPIC,
                AffixDef.DEFLECTOR_SHIELD, AffixQuality.EPIC);
        helper.assertTrue(ChampionHpConversion.survivalSpent(defensive) == 100,
                "生存花费 32+28+40 = 100");

        double frac = ChampionHpConversion.hpFraction(StarRank.STAR_7, defensive);
        double expected = 0.35D + 0.65D * Math.pow(65.0D / 165.0D, 1.5D);
        helper.assertTrue(Math.abs(frac - expected) < EPS, "hpFraction 精确 = 0.35 + 0.65 x (65/165)^1.5");
        // 曲线值本身逐位核对 (防实现与期望式同错): 0.35 + 0.65 x 0.2472545 = 0.5107154。
        helper.assertTrue(Math.abs(frac - 0.5107154D) < 1e-4D, "hpFraction ~ 0.5107 (手算逐位核对)");

        double hp = ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_7, defensive);
        helper.assertTrue(Math.abs(hp - 6000.0D * expected) < EPS, "换算后血 = 6000 x hpFraction");
        helper.assertTrue(hp > 3063.0D && hp < 3065.0D, "7★ 全防御 build 血量落在 3064 附近 (原满额 6000)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void overspendClampsToFloorNotThrow(GameTestHelper helper) {
        // 1★ 预算 10 < 复合(8)+超高分子(7)=15: 命令调试越预算 -> 剩余钳 0 -> FLOOR 保底, 不抛。
        Map<AffixDef, AffixQuality> overspend = Map.of(
                AffixDef.COMPOSITE_ARMOR, AffixQuality.COMMON,
                AffixDef.UHMWPE_ARMOR, AffixQuality.COMMON);
        double frac = ChampionHpConversion.hpFraction(StarRank.STAR_1, overspend);
        helper.assertTrue(Math.abs(frac - ChampionHpConversion.HP_FLOOR) < EPS,
                "超预算 hpFraction = FLOOR 0.35");
        double hp = ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_1, overspend);
        helper.assertTrue(Math.abs(hp - 135.0D * 0.35D) < EPS, "1★ 超预算保底血 = 135 x 0.35 = 47.25");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gigantismMultipliesConvertedBase(GameTestHelper helper) {
        // 5★ 巨大化 RARE: 花 30/80 点 -> remFrac 0.625 -> frac = 0.35+0.65x0.625^1.5; 体型乘数 1.8 (RARE +80%)。
        Map<AffixDef, AffixQuality> gig = Map.of(AffixDef.GIGANTISM, AffixQuality.RARE);
        helper.assertTrue(ChampionHpConversion.survivalSpent(gig) == 30, "巨大化 RARE 花费 ceil(12x2.5)=30");
        helper.assertTrue(Math.abs(ChampionHpConversion.sizeMultiplier(gig) - 1.8D) < EPS,
                "巨大化 RARE 体型乘数 = 1.8");

        double expectedFrac = 0.35D + 0.65D * Math.pow(50.0D / 80.0D, 1.5D);
        double hp = ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_5, gig);
        helper.assertTrue(Math.abs(hp - 765.0D * expectedFrac * 1.8D) < EPS,
                "5★ 巨大化血 = 765 x frac x 1.8");
        helper.assertTrue(hp > 924.0D && hp < 924.4D, "5★ 巨大化 RARE ~ 924.2 (换算后仍 <1024 无需血池)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miniaturizationCutsHpAndIgnoresMobilityCost(GameTestHelper helper) {
        // 3★ 缩小化 COMMON + 强制机动伙伴 SPRINT COMMON: 生存花费只计缩小化 10 (SPRINT 占机动池不扣血);
        // 体型乘数 0.75 (COMMON -25%)。
        Map<AffixDef, AffixQuality> mini = Map.of(
                AffixDef.MINIATURIZATION, AffixQuality.COMMON,
                AffixDef.SPRINT, AffixQuality.COMMON);
        helper.assertTrue(ChampionHpConversion.survivalSpent(mini) == 10,
                "缩小化 COMMON 生存花费 = 10 (机动伙伴不计入)");
        helper.assertTrue(Math.abs(ChampionHpConversion.sizeMultiplier(mini) - 0.75D) < EPS,
                "缩小化 COMMON 体型乘数 = 0.75");

        double expectedFrac = 0.35D + 0.65D * Math.pow(25.0D / 35.0D, 1.5D);
        double hp = ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_3, mini);
        helper.assertTrue(Math.abs(hp - 360.0D * expectedFrac * 0.75D) < EPS,
                "3★ 缩小化血 = 360 x frac x 0.75");
        helper.assertTrue(hp > 200.0D && hp < 201.0D, "3★ 缩小化 ~ 200.4 (快小怪低血)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void spendingMoreAlwaysLowersHp(GameTestHelper helper) {
        // 单调性: 同星级花更多生存点 -> 血更低 (曲线保序; 删 GAMMA 幂/花费统计任意一处必挂本序)。
        Map<AffixDef, AffixQuality> light = Map.of(AffixDef.REGEN_TISSUE, AffixQuality.COMMON);   // 6 点
        Map<AffixDef, AffixQuality> heavy = Map.of(AffixDef.REGEN_TISSUE, AffixQuality.RARE);     // 15 点
        double fracLight = ChampionHpConversion.hpFraction(StarRank.STAR_6, light);
        double fracHeavy = ChampionHpConversion.hpFraction(StarRank.STAR_6, heavy);
        helper.assertTrue(fracLight < 1.0D, "花 6 点已低于满额");
        helper.assertTrue(fracHeavy < fracLight, "花 15 点低于花 6 点");
        helper.assertTrue(fracHeavy >= ChampionHpConversion.HP_FLOOR, "任何花费不破 FLOOR 下界");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sizeMutexDoubleHoldIsDeterministic(GameTestHelper helper) {
        // SIZE 互斥族合法装配至多一条; 命令调试双持 = 连乘 (1.3 x 0.75 = 0.975), 确定性不抛。
        Map<AffixDef, AffixQuality> both = Map.of(
                AffixDef.GIGANTISM, AffixQuality.COMMON,
                AffixDef.MINIATURIZATION, AffixQuality.COMMON);
        helper.assertTrue(Math.abs(ChampionHpConversion.sizeMultiplier(both) - 0.975D) < EPS,
                "巨大化+缩小化双持 (调试) 连乘 = 1.3 x 0.75 = 0.975");
        helper.succeed();
    }
}
