package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 精英怪【体型词条·巨大化/缩小化】尺寸系数 + 移速补偿 + 降档序列纯逻辑 GameTest (ChampionStarAffix spec 7.1 体型 +
 * 9A.3 #17 + 9.4 TDD)。只断言 {@link ChampionSizeScale} 与 {@link ChampionDamageReduction} 的纯数值折算 (不触世界/
 * 实体/网络), 全部为精确业务值 (删被测折算/降档序列必挂)。真服 (碰撞箱缩放/客户端渲染/形态守卫 blink) 由
 * {@code ChampionSizeHandler} 与 {@code ChampionSizeRenderClient} 承接。
 *
 * <p>核心防回归: 体型缩放系数唯一来自 {@link AffixDef} 副数值, 缩小化的等效减伤折算
 * ({@link ChampionDamageReduction#miniaturizationSizePct}) 亦读同一副数值 —— {@link #miniSizeTableSingleSource}
 * 锁死二者一致, 防历史"本类硬编码 + AffixDef 各存一份"的双表漂移复发。
 *
 * template = "empty", batch = "champion_size_scale"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionSizeScaleGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_size_scale";
    private static final double EPS = 1e-6D;

    // ============================================================
    // 十档体型乘数精确值 (5 巨大化 + 5 缩小化)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gigantismSizeMultipliers(GameTestHelper helper) {
        // 巨大化 尺寸 x(1+sv): 1.25/1.40/1.60/1.85/2.20 (sv = 0.25/0.40/0.60/0.85/1.20)。
        double[] expected = {1.25D, 1.40D, 1.60D, 1.85D, 2.20D};
        AffixQuality[] q = AffixQuality.values();
        for (int i = 0; i < q.length; i++) {
            double got = ChampionSizeScale.sizeMultiplierFor(AffixDef.GIGANTISM, q[i]);
            helper.assertTrue(Math.abs(got - expected[i]) < EPS,
                    "巨大化 " + q[i] + " 尺寸乘数应 = " + expected[i] + ", 实得 " + got);
        }
        // 防误读主数值 (血量 30/50/80/120/180%): COMMON 若读主数值会算成 1.30 而非 1.25。
        helper.assertTrue(Math.abs(ChampionSizeScale.sizeMultiplierFor(AffixDef.GIGANTISM, AffixQuality.COMMON) - 1.25D) < EPS,
                "巨大化 COMMON 必读副数值 (1.25), 非主数值 (会成 1.30)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miniaturizationSizeMultipliers(GameTestHelper helper) {
        // 缩小化 尺寸 x(1-sv): 0.85/0.75/0.65/0.55/0.45 (sv = 0.15/0.25/0.35/0.45/0.55)。
        double[] expected = {0.85D, 0.75D, 0.65D, 0.55D, 0.45D};
        AffixQuality[] q = AffixQuality.values();
        for (int i = 0; i < q.length; i++) {
            double got = ChampionSizeScale.sizeMultiplierFor(AffixDef.MINIATURIZATION, q[i]);
            helper.assertTrue(Math.abs(got - expected[i]) < EPS,
                    "缩小化 " + q[i] + " 尺寸乘数应 = " + expected[i] + ", 实得 " + got);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nonSizeAffixAndNullRejected(GameTestHelper helper) {
        // 非体型词条 (SIZE 族外) 不可取尺寸系数 (调用方 bug, 抛不掩盖)。
        helper.assertTrue(throwsIae(() -> ChampionSizeScale.sizeMultiplierFor(AffixDef.BURNING, AffixQuality.COMMON)),
                "非体型词条 BURNING 应抛 IllegalArgumentException");
        helper.assertTrue(throwsIae(() -> ChampionSizeScale.sizeMultiplierFor(AffixDef.GIGANTISM, null)),
                "null 品质应抛 IllegalArgumentException");
        helper.assertTrue(throwsIae(() -> ChampionSizeScale.sizeMultiplierFor(null, AffixQuality.COMMON)),
                "null 词条应抛 IllegalArgumentException");
        helper.succeed();
    }

    // ============================================================
    // 移速补偿 5 档 (仅巨大化: +10% x 品质序号)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void speedBonusFiveTiers(GameTestHelper helper) {
        double[] expected = {0.10D, 0.20D, 0.30D, 0.40D, 0.50D};
        AffixQuality[] q = AffixQuality.values();
        for (int i = 0; i < q.length; i++) {
            double got = ChampionSizeScale.speedBonusFor(q[i]);
            helper.assertTrue(Math.abs(got - expected[i]) < EPS,
                    "移速补偿 " + q[i] + " 应 = " + expected[i] + ", 实得 " + got);
        }
        helper.assertTrue(throwsIae(() -> ChampionSizeScale.speedBonusFor(null)),
                "null 品质移速补偿应抛 IllegalArgumentException");
        helper.succeed();
    }

    // ============================================================
    // 降档序列 (LEGENDARY -> EPIC ... COMMON 到底不再降)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void downgradeSequence(GameTestHelper helper) {
        helper.assertTrue(ChampionSizeScale.downgrade(AffixQuality.LEGENDARY) == AffixQuality.EPIC,
                "LEGENDARY 降 EPIC");
        helper.assertTrue(ChampionSizeScale.downgrade(AffixQuality.EPIC) == AffixQuality.RARE,
                "EPIC 降 RARE");
        helper.assertTrue(ChampionSizeScale.downgrade(AffixQuality.RARE) == AffixQuality.UNCOMMON,
                "RARE 降 UNCOMMON");
        helper.assertTrue(ChampionSizeScale.downgrade(AffixQuality.UNCOMMON) == AffixQuality.COMMON,
                "UNCOMMON 降 COMMON");
        helper.assertTrue(ChampionSizeScale.downgrade(AffixQuality.COMMON) == AffixQuality.COMMON,
                "COMMON 到底不再降 (巨大化恒 >= COMMON 1.25x)");
        helper.assertTrue(throwsIae(() -> ChampionSizeScale.downgrade(null)),
                "null 品质降档应抛 IllegalArgumentException");
        helper.succeed();
    }

    // ============================================================
    // 缩小化体型表单一真源一致性 (防双表漂移复发)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miniSizeTableSingleSource(GameTestHelper helper) {
        double[] expectedPct = {0.15D, 0.25D, 0.35D, 0.45D, 0.55D};
        for (AffixQuality q : AffixQuality.values()) {
            double pct = ChampionDamageReduction.miniaturizationSizePct(q);
            double affixSecondary = AffixDef.MINIATURIZATION.secondaryValueFor(q);
            // 真源: 减伤层读的缩小化体型缩减 == AffixDef 副数值 (二者同源, 任一改动另一未改则挂)。
            helper.assertTrue(Math.abs(pct - affixSecondary) < EPS,
                    "缩小化体型缩减 " + q + " 应与 AffixDef 副数值同源: dmg=" + pct + " affix=" + affixSecondary);
            helper.assertTrue(Math.abs(pct - expectedPct[q.valueIndex()]) < EPS,
                    "缩小化体型缩减 " + q + " 应 = " + expectedPct[q.valueIndex()]);
            // 尺寸系数 = 1 - 体型缩减 (ChampionSizeScale 与减伤层读同一副数值, 互推自洽)。
            double sizeMult = ChampionSizeScale.sizeMultiplierFor(AffixDef.MINIATURIZATION, q);
            helper.assertTrue(Math.abs(sizeMult - (1.0D - pct)) < EPS,
                    "缩小化 " + q + " 尺寸系数应 = 1 - 体型缩减");
        }
        helper.succeed();
    }

    /**
     * 眼高随箱高等比不变量 (2026-07-10 真服验收修): vanilla 僵尸站立眼高写死 1.74 不看尺寸, Size 事件若用
     * updateEyeHeight=true 重查会让巨人眼位停在胸口 (TACZ 爆头判定锚眼位随之错到胸口)。真实体端到端断言:
     * 盖章巨大化后 眼高缩放比 == 箱高缩放比 (删 ChampionSizeHandler 的显式等比眼高必挂 —— 届时眼高比恒 1.0)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void eyeHeightScalesProportionallyWithBox(GameTestHelper helper) {
        net.minecraft.world.entity.monster.Zombie zombie =
                helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, new net.minecraft.core.BlockPos(0, 1, 0));
        float baseHeight = zombie.getBbHeight();
        float baseEye = zombie.getEyeHeight();

        java.util.Map<AffixDef, AffixQuality> affixes = new java.util.EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.GIGANTISM, AffixQuality.UNCOMMON);
        com.miningdim.champion.integration.ChampionPromoter.applyChampion(zombie, 3, affixes);

        float heightRatio = zombie.getBbHeight() / baseHeight;
        float eyeRatio = zombie.getEyeHeight() / baseEye;
        // 生成期守卫可降档, 但 COMMON 1.25 兜底恒 >1 (巨大化真的变大了才谈得上等比)。
        helper.assertTrue(heightRatio > 1.01F, "gigantism must actually enlarge the box, ratio=" + heightRatio);
        helper.assertTrue(Math.abs(heightRatio - eyeRatio) < 1e-3F,
                "eye height must scale proportionally with box height: box=" + heightRatio + " eye=" + eyeRatio);
        helper.succeed();
    }

    /** 断言某段逻辑抛 IllegalArgumentException (GameTestHelper 无 assertThrows, 自封装)。 */
    private static boolean throwsIae(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
